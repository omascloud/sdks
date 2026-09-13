from __future__ import annotations

import asyncio
import inspect
from collections.abc import Callable, Iterator, Mapping
from concurrent.futures import Future
from contextlib import suppress
from contextvars import copy_context
from threading import BoundedSemaphore, Event, Thread
from time import monotonic
from typing import Any, Self, TypeVar, cast
from urllib.parse import urlsplit, urlunsplit

import httpx
from pydantic import BaseModel, ValidationError

from ._errors import (
    ApiError,
    AuthenticationError,
    RequestTimeoutError,
    SerializationError,
    TransportError,
)
from ._options import ClientOptions
from ._types import AuthContext, Authentication, AuthProvider, RequestMetadata

T = TypeVar("T", bound=BaseModel)
ErrorDecoder = Callable[[ApiError[Any]], ApiError[Any]]


class _DeadlineStream(httpx.SyncByteStream):
    def __init__(self, stream: httpx.SyncByteStream, check: Callable[[], float]) -> None:
        self._stream = stream
        self._check = check

    def __iter__(self) -> Iterator[bytes]:
        self._check()
        for chunk in self._stream:
            self._check()
            yield chunk
            self._check()

    def close(self) -> None:
        self._stream.close()


def _endpoint(value: str) -> str:
    parsed = urlsplit(value)
    if (
        parsed.scheme not in {"http", "https"}
        or not parsed.netloc
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError("endpoint must be an absolute HTTP(S) URL without query or fragment")
    path = parsed.path if parsed.path.endswith("/") else f"{parsed.path}/"
    return urlunsplit((parsed.scheme, parsed.netloc, path, "", ""))


def _timeout(options: ClientOptions) -> httpx.Timeout:
    return httpx.Timeout(
        options.request_timeout,
        connect=options.connect_timeout,
        read=options.read_timeout,
        pool=options.pool_timeout,
    )


def _api_error(response: httpx.Response) -> ApiError[Any]:
    error_code = ""
    message = f"API request failed with HTTP {response.status_code}"
    details: Any = None
    try:
        payload = response.json()
        if isinstance(payload, dict):
            if isinstance(payload.get("errorCode"), str):
                error_code = payload["errorCode"]
            if isinstance(payload.get("error"), str) and payload["error"]:
                message = payload["error"]
            details = payload.get("extraData")
    except ValueError:
        pass
    retry_after: float | None = None
    with suppress(KeyError, ValueError):
        retry_after = float(response.headers["Retry-After"])
    return ApiError(
        status=response.status_code,
        error_code=error_code,
        message=message,
        request_id=response.headers.get("X-Request-Id"),
        retry_after=retry_after,
        headers=response.headers,
        details=details,
        response_body=response.content,
    )


class ClientRuntime:
    """Synchronous requests with an overall caller deadline.

    Callbacks run in a worker with the caller's context variables. Blocking custom
    callbacks/transports cannot be forcibly interrupted; after a timeout their
    worker exits at the next cancellation check. At most ``max_connections``
    workers may remain active, including timed-out requests.
    """

    def __init__(
        self,
        *,
        service: str,
        endpoint: str,
        auth_provider: AuthProvider,
        options: ClientOptions | None = None,
        error_decoder: ErrorDecoder | None = None,
    ) -> None:
        if not service.strip():
            raise ValueError("service must not be blank")
        self._service = service.strip()
        self._auth_provider = auth_provider
        self._options = options or ClientOptions()
        self._workers = BoundedSemaphore(self._options.max_connections)
        self._error_decoder = error_decoder or (lambda error: error)
        self._client = httpx.Client(
            base_url=_endpoint(endpoint),
            timeout=_timeout(self._options),
            limits=httpx.Limits(max_connections=self._options.max_connections),
            proxy=self._options.proxy,
            transport=self._options.transport,
        )

    def request(
        self,
        *,
        operation_id: str,
        method: str,
        path: str,
        query: Mapping[str, Any] | None = None,
        headers: Mapping[str, str] | None = None,
        json: Any = None,
        response_model: type[T] | None = None,
    ) -> T | None:
        deadline = monotonic() + self._options.request_timeout
        cancelled = Event()
        completed = Event()
        future: Future[T | None] = Future()

        def check_deadline() -> float:
            remaining = deadline - monotonic()
            if cancelled.is_set() or remaining <= 0:
                cause = TimeoutError(f"{operation_id} deadline elapsed")
                raise RequestTimeoutError(
                    operation_id, f"{operation_id} request timed out", cause
                ) from cause
            return remaining

        def execute() -> None:
            try:
                result = self._request(
                    operation_id=operation_id,
                    method=method,
                    path=path,
                    query=query,
                    headers=headers,
                    json=json,
                    response_model=response_model,
                    check_deadline=check_deadline,
                )
                check_deadline()
                future.set_result(result)
            except BaseException as error:
                future.set_exception(error)
            finally:
                self._workers.release()
                completed.set()

        if not self._workers.acquire(timeout=min(self._options.pool_timeout, check_deadline())):
            cause = TimeoutError(f"{operation_id} timed out waiting for a request worker")
            raise RequestTimeoutError(
                operation_id, f"{operation_id} request timed out", cause
            ) from cause
        try:
            context = copy_context()
            Thread(target=context.run, args=(execute,), daemon=True).start()
        except BaseException:
            self._workers.release()
            raise
        try:
            if not completed.wait(check_deadline()):
                cancelled.set()
                check_deadline()
            return future.result()
        finally:
            cancelled.set()

    def _request(
        self,
        *,
        operation_id: str,
        method: str,
        path: str,
        query: Mapping[str, Any] | None,
        headers: Mapping[str, str] | None,
        json: Any,
        response_model: type[T] | None,
        check_deadline: Callable[[], float],
    ) -> T | None:
        check_deadline()
        request_headers = httpx.Headers(headers)
        request_headers.setdefault("Accept", "application/json")
        url = str(self._client.base_url.join(path.lstrip("/")))
        metadata = RequestMetadata(self._service, operation_id, method, url)
        for interceptor in self._options.interceptors:
            check_deadline()
            result = interceptor(metadata, request_headers)
            if inspect.isawaitable(result):
                raise TypeError("synchronous interceptors must not return an awaitable")
            if "Authorization" in request_headers:
                raise ValueError("request interceptors must not set Authorization headers")
        check_deadline()
        try:
            authentication = self._auth_provider.resolve(AuthContext(self._service, operation_id))
        except Exception as error:
            raise AuthenticationError(
                operation_id, f"authenticate {operation_id} request", error
            ) from error
        remaining = check_deadline()
        request_headers.update(authentication.headers)
        try:
            with self._client.stream(
                method,
                path.lstrip("/"),
                params=query,
                headers=request_headers,
                json=json,
                timeout=httpx.Timeout(
                    remaining,
                    connect=min(self._options.connect_timeout, remaining),
                    read=min(self._options.read_timeout, remaining),
                    pool=min(self._options.pool_timeout, remaining),
                ),
            ) as response:
                check_deadline()
                response.stream = _DeadlineStream(
                    cast(httpx.SyncByteStream, response.stream), check_deadline
                )
                response.read()
                check_deadline()
                return self._decode(operation_id, response, response_model)
        except httpx.TimeoutException as error:
            raise RequestTimeoutError(
                operation_id, f"{operation_id} request timed out", error
            ) from error
        except httpx.HTTPError as error:
            raise TransportError(operation_id, f"execute {operation_id} request", error) from error

    def _decode(
        self, operation_id: str, response: httpx.Response, model: type[T] | None
    ) -> T | None:
        if not response.is_success:
            raise self._error_decoder(_api_error(response))
        if model is None or response.status_code == 204 or not response.content:
            return None
        try:
            return model.model_validate_json(response.content)
        except (ValidationError, ValueError) as error:
            raise SerializationError(
                operation_id, f"deserialize {operation_id} response", error
            ) from error

    def close(self) -> None:
        self._client.close()

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()


class AsyncClientRuntime:
    def __init__(
        self,
        *,
        service: str,
        endpoint: str,
        auth_provider: AuthProvider,
        options: ClientOptions | None = None,
        error_decoder: ErrorDecoder | None = None,
    ) -> None:
        if not service.strip():
            raise ValueError("service must not be blank")
        self._service = service.strip()
        self._auth_provider = auth_provider
        self._options = options or ClientOptions()
        self._error_decoder = error_decoder or (lambda error: error)
        self._client = httpx.AsyncClient(
            base_url=_endpoint(endpoint),
            timeout=_timeout(self._options),
            limits=httpx.Limits(max_connections=self._options.max_connections),
            proxy=self._options.proxy,
            transport=self._options.async_transport,
        )

    async def request(
        self,
        *,
        operation_id: str,
        method: str,
        path: str,
        query: Mapping[str, Any] | None = None,
        headers: Mapping[str, str] | None = None,
        json: Any = None,
        response_model: type[T] | None = None,
    ) -> T | None:
        try:
            async with asyncio.timeout(self._options.request_timeout):
                return await self._request(
                    operation_id=operation_id,
                    method=method,
                    path=path,
                    query=query,
                    headers=headers,
                    json=json,
                    response_model=response_model,
                )
        except TimeoutError as error:
            raise RequestTimeoutError(
                operation_id, f"{operation_id} request timed out", error
            ) from error

    async def _request(
        self,
        *,
        operation_id: str,
        method: str,
        path: str,
        query: Mapping[str, Any] | None = None,
        headers: Mapping[str, str] | None = None,
        json: Any = None,
        response_model: type[T] | None = None,
    ) -> T | None:
        request_headers = httpx.Headers(headers)
        request_headers.setdefault("Accept", "application/json")
        url = str(self._client.base_url.join(path.lstrip("/")))
        metadata = RequestMetadata(self._service, operation_id, method, url)
        for interceptor in self._options.interceptors:
            result = interceptor(metadata, request_headers)
            if inspect.isawaitable(result):
                await result
            if "Authorization" in request_headers:
                raise ValueError("request interceptors must not set Authorization headers")
        try:
            async_resolve = getattr(self._auth_provider, "aresolve", None)
            authentication = (
                await async_resolve(AuthContext(self._service, operation_id))
                if async_resolve is not None
                else self._auth_provider.resolve(AuthContext(self._service, operation_id))
            )
        except Exception as error:
            raise AuthenticationError(
                operation_id, f"authenticate {operation_id} request", error
            ) from error
        request_headers.update(cast(Authentication, authentication).headers)
        try:
            response = await self._client.request(
                method,
                path.lstrip("/"),
                params=query,
                headers=request_headers,
                json=json,
            )
        except httpx.TimeoutException as error:
            raise RequestTimeoutError(
                operation_id, f"{operation_id} request timed out", error
            ) from error
        except httpx.HTTPError as error:
            raise TransportError(operation_id, f"execute {operation_id} request", error) from error
        return self._decode(operation_id, response, response_model)

    def _decode(
        self, operation_id: str, response: httpx.Response, model: type[T] | None
    ) -> T | None:
        if not response.is_success:
            raise self._error_decoder(_api_error(response))
        if model is None or response.status_code == 204 or not response.content:
            return None
        try:
            return model.model_validate_json(response.content)
        except (ValidationError, ValueError) as error:
            raise SerializationError(
                operation_id, f"deserialize {operation_id} response", error
            ) from error

    async def aclose(self) -> None:
        await self._client.aclose()

    async def __aenter__(self) -> Self:
        return self

    async def __aexit__(self, *_args: object) -> None:
        await self.aclose()
