from __future__ import annotations

import asyncio
from collections.abc import Iterator
from contextvars import ContextVar
from threading import Event
from time import monotonic, sleep

import httpx
import pytest
from omascloud.sdk.core import (
    ApiError,
    AsyncClientRuntime,
    AuthContext,
    Authentication,
    BearerAuthProvider,
    ClientOptions,
    ClientRuntime,
    RequestMetadata,
    RequestTimeoutError,
)
from pydantic import BaseModel, ConfigDict


class Result(BaseModel):
    model_config = ConfigDict(frozen=True)
    value: str


@pytest.mark.parametrize("status", [200, 503])
def test_sync_deadline_interrupts_a_streaming_response_and_releases_it(status: int) -> None:
    closed = Event()

    class SlowStream(httpx.SyncByteStream):
        def __iter__(self) -> Iterator[bytes]:
            for _ in range(100):
                sleep(0.01)
                yield b" "

        def close(self) -> None:
            closed.set()

    def endpoint(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/slow":
            return httpx.Response(status, stream=SlowStream())
        return httpx.Response(200, json={"value": "ok"})

    with ClientRuntime(
        service="metrics",
        endpoint="https://example.test",
        auth_provider=BearerAuthProvider("token"),
        options=ClientOptions(
            request_timeout=0.1,
            read_timeout=1,
            max_connections=1,
            transport=httpx.MockTransport(endpoint),
        ),
    ) as runtime:
        started = monotonic()
        with pytest.raises(RequestTimeoutError) as raised:
            runtime.request(operation_id="slow", method="GET", path="slow")
        assert monotonic() - started < 0.5
        assert raised.value.operation_id == "slow"
        assert closed.wait(0.5)
        assert runtime.request(
            operation_id="fast", method="GET", path="fast", response_model=Result
        ) == Result(value="ok")


def test_sync_deadline_includes_blocked_auth_and_bounds_pending_work() -> None:
    release = Event()
    auth_started = Event()
    sent: list[str] = []
    auth_calls = 0

    class BlockingAuth:
        def resolve(self, _context: AuthContext) -> Authentication:
            nonlocal auth_calls
            auth_calls += 1
            auth_started.set()
            release.wait(2)
            return Authentication({"Authorization": "Bearer token"})

    def endpoint(request: httpx.Request) -> httpx.Response:
        sent.append(request.url.path)
        return httpx.Response(204)

    with ClientRuntime(
        service="metrics",
        endpoint="https://example.test",
        auth_provider=BlockingAuth(),
        options=ClientOptions(
            request_timeout=0.1,
            max_connections=1,
            transport=httpx.MockTransport(endpoint),
        ),
    ) as runtime:
        try:
            started = monotonic()
            with pytest.raises(RequestTimeoutError):
                runtime.request(operation_id="first", method="GET", path="first")
            assert monotonic() - started < 0.5
            assert auth_started.is_set()
            with pytest.raises(RequestTimeoutError):
                runtime.request(operation_id="second", method="GET", path="second")
            assert auth_calls == 1
        finally:
            release.set()
        # A following successful call waits for the timed-out worker to release its slot.
        runtime.request(operation_id="third", method="GET", path="third")
        assert auth_calls == 2
        assert sent == ["/third"]


def test_sync_runtime_builds_authenticated_request_and_decodes_model() -> None:
    seen: list[RequestMetadata] = []
    trace_id = ContextVar("trace_id", default="missing")

    def endpoint(request: httpx.Request) -> httpx.Response:
        assert request.url == "https://example.test/v1/metrics/cpu?maxResults=25"
        assert request.headers["Authorization"] == "Bearer token"
        assert request.headers["X-Test"] == "yes"
        return httpx.Response(200, json={"value": "ok"})

    def intercept(metadata: RequestMetadata, headers: httpx.Headers) -> None:
        assert trace_id.get() == "caller-trace"
        seen.append(metadata)
        headers["X-Test"] = "yes"

    runtime = ClientRuntime(
        service="metrics",
        endpoint="https://example.test/v1/metrics",
        auth_provider=BearerAuthProvider("token"),
        options=ClientOptions(
            interceptors=(intercept,),
            transport=httpx.MockTransport(endpoint),
        ),
    )

    token = trace_id.set("caller-trace")
    try:
        result = runtime.request(
            operation_id="getMetric",
            method="GET",
            path="cpu",
            query={"maxResults": 25},
            response_model=Result,
        )
    finally:
        trace_id.reset(token)
        runtime.close()

    assert result == Result(value="ok")
    assert seen[0].operation_id == "getMetric"


@pytest.mark.asyncio
async def test_async_runtime_builds_request_and_decodes_model() -> None:
    async def endpoint(request: httpx.Request) -> httpx.Response:
        assert request.url == "https://example.test/v1/metrics/cpu"
        return httpx.Response(200, json={"value": "ok"})

    runtime = AsyncClientRuntime(
        service="metrics",
        endpoint="https://example.test/v1/metrics/",
        auth_provider=BearerAuthProvider("token"),
        options=ClientOptions(async_transport=httpx.MockTransport(endpoint)),
    )

    result = await runtime.request(
        operation_id="getMetric",
        method="GET",
        path="cpu",
        response_model=Result,
    )
    await runtime.aclose()

    assert result == Result(value="ok")


def test_runtime_decodes_unknown_api_error_and_blocks_auth_interception() -> None:
    def failure(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            429,
            headers={"X-Request-Id": "request-2", "Retry-After": "3"},
            json={"errorCode": "FutureError", "error": "later", "extraData": {"x": 1}},
        )

    runtime = ClientRuntime(
        service="metrics",
        endpoint="https://example.test",
        auth_provider=BearerAuthProvider("token"),
        options=ClientOptions(transport=httpx.MockTransport(failure)),
    )

    with pytest.raises(ApiError) as raised:
        runtime.request(operation_id="listMetrics", method="GET", path="metrics")

    assert raised.value.error_code == "FutureError"
    assert raised.value.request_id == "request-2"
    assert raised.value.retry_after == 3.0

    def invalid_interceptor(_metadata: RequestMetadata, headers: httpx.Headers) -> None:
        headers["Authorization"] = "not allowed"

    guarded = ClientRuntime(
        service="metrics",
        endpoint="https://example.test",
        auth_provider=BearerAuthProvider("token"),
        options=ClientOptions(
            interceptors=(invalid_interceptor,),
            transport=httpx.MockTransport(failure),
        ),
    )
    with pytest.raises(ValueError, match="must not set Authorization"):
        guarded.request(operation_id="listMetrics", method="GET", path="metrics")


@pytest.mark.asyncio
async def test_async_runtime_enforces_overall_deadline_and_preserves_cancellation() -> None:
    started = asyncio.Event()

    class SlowAuth:
        def resolve(self, _context: object) -> object:
            raise AssertionError("sync authentication must not be used")

        async def aresolve(self, _context: object) -> object:
            started.set()
            await asyncio.sleep(60)

    runtime = AsyncClientRuntime(
        service="metrics",
        endpoint="https://example.test",
        auth_provider=SlowAuth(),  # type: ignore[arg-type]
        options=ClientOptions(request_timeout=0.01),
    )
    with pytest.raises(RequestTimeoutError):
        await runtime.request(operation_id="listMetrics", method="GET", path="metrics")

    task = asyncio.create_task(
        runtime.request(operation_id="listMetrics", method="GET", path="metrics")
    )
    await started.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    await runtime.aclose()
