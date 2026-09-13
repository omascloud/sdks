from __future__ import annotations

import asyncio
import json

import httpx
import omascloud.sdk.core._m2m as m2m_module
import pytest
from omascloud.sdk.core import AuthContext, BearerAuthProvider, M2mAuthProvider


def test_bearer_provider_rejects_blank_tokens_and_resolves_trimmed_token() -> None:
    with pytest.raises(ValueError, match="token must not be blank"):
        BearerAuthProvider("  ")

    provider = BearerAuthProvider("  secret  ")

    assert provider.resolve(AuthContext("metrics", "listMetrics")).headers == {
        "Authorization": "Bearer secret"
    }


def test_m2m_provider_caches_service_scoped_tokens() -> None:
    requests: list[httpx.Request] = []

    def exchange(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(
            200,
            json={"accessToken": "access", "tokenType": "Bearer", "expiresIn": 300},
        )

    provider = M2mAuthProvider(
        "credential",
        transport=httpx.MockTransport(exchange),
    )

    first = provider.resolve(AuthContext("metrics", "listMetrics"))
    second = provider.resolve(AuthContext("metrics", "getMetricData"))
    provider.close()

    assert first.headers == second.headers == {"Authorization": "Bearer access"}
    assert len(requests) == 1
    assert json.loads(requests[0].content) == {"audience": "metrics"}
    assert requests[0].headers["Authorization"] == "Bearer credential"


def test_m2m_provider_wraps_malformed_token_responses() -> None:
    provider = M2mAuthProvider(
        "credential",
        transport=httpx.MockTransport(
            lambda _request: httpx.Response(
                200,
                json={"accessToken": 123, "tokenType": "Bearer", "expiresIn": 300},
            )
        ),
    )

    with pytest.raises(RuntimeError, match="invalid token exchange response"):
        provider.resolve(AuthContext("metrics", "listMetrics"))
    provider.close()


def test_m2m_provider_uses_still_valid_token_when_refresh_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    now = 0.0
    calls = 0

    def exchange(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if calls == 1:
            return httpx.Response(
                200,
                json={"accessToken": "access", "tokenType": "Bearer", "expiresIn": 100},
            )
        return httpx.Response(503)

    monkeypatch.setattr(m2m_module, "monotonic", lambda: now)
    provider = M2mAuthProvider(
        "credential",
        refresh_skew=50,
        transport=httpx.MockTransport(exchange),
    )
    first = provider.resolve(AuthContext("metrics", "listMetrics"))
    now = 51.0
    second = provider.resolve(AuthContext("metrics", "listMetrics"))
    provider.close()

    assert first == second
    assert calls == 2


@pytest.mark.asyncio
async def test_m2m_provider_coalesces_concurrent_async_refreshes() -> None:
    calls = 0

    async def exchange(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        await asyncio.sleep(0.01)
        return httpx.Response(
            200,
            json={"accessToken": "access", "tokenType": "Bearer", "expiresIn": 300},
        )

    provider = M2mAuthProvider(
        "credential",
        async_transport=httpx.MockTransport(exchange),
    )
    context = AuthContext("metrics", "listMetrics")

    resolved = await asyncio.gather(*(provider.aresolve(context) for _ in range(5)))
    await provider.aclose()

    assert calls == 1
    assert all(item.headers == {"Authorization": "Bearer access"} for item in resolved)
