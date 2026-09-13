from __future__ import annotations

import asyncio
from dataclasses import dataclass
from threading import Lock
from time import monotonic
from typing import Any

import httpx

from ._types import AuthContext, Authentication

TOKEN_EXCHANGE_ENDPOINT = "https://api.omas.cloud/v1/auth/token-exchange"


@dataclass(frozen=True, slots=True)
class _Token:
    value: str
    refresh_at: float
    expires_at: float


class M2mAuthProvider:
    def __init__(
        self,
        credential: str,
        *,
        refresh_skew: float = 30.0,
        endpoint: str = TOKEN_EXCHANGE_ENDPOINT,
        transport: httpx.BaseTransport | None = None,
        async_transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        normalized = credential.strip()
        if not normalized:
            raise ValueError("credential must not be blank")
        if refresh_skew < 0:
            raise ValueError("refresh_skew must not be negative")
        self._credential = normalized
        self._refresh_skew = refresh_skew
        self._endpoint = endpoint
        self._sync_client = httpx.Client(transport=transport)
        self._async_client = httpx.AsyncClient(transport=async_transport)
        self._sync_tokens: dict[str, _Token] = {}
        self._async_tokens: dict[str, _Token] = {}
        self._sync_lock = Lock()
        self._async_locks: dict[str, asyncio.Lock] = {}

    def resolve(self, context: AuthContext) -> Authentication:
        service = self._service(context)
        with self._sync_lock:
            token = self._sync_tokens.get(service)
            if token is None or monotonic() >= token.refresh_at:
                try:
                    token = self._exchange(service)
                    self._sync_tokens[service] = token
                except Exception:
                    if token is None or monotonic() >= token.expires_at:
                        raise
        return self._authentication(token)

    async def aresolve(self, context: AuthContext) -> Authentication:
        service = self._service(context)
        lock = self._async_locks.setdefault(service, asyncio.Lock())
        async with lock:
            token = self._async_tokens.get(service)
            if token is None or monotonic() >= token.refresh_at:
                try:
                    token = await self._aexchange(service)
                    self._async_tokens[service] = token
                except Exception:
                    if token is None or monotonic() >= token.expires_at:
                        raise
        return self._authentication(token)

    def _exchange(self, service: str) -> _Token:
        response = self._sync_client.post(
            self._endpoint,
            headers={"Authorization": f"Bearer {self._credential}"},
            json={"audience": service},
        )
        return self._decode(response)

    async def _aexchange(self, service: str) -> _Token:
        response = await self._async_client.post(
            self._endpoint,
            headers={"Authorization": f"Bearer {self._credential}"},
            json={"audience": service},
        )
        return self._decode(response)

    def _decode(self, response: httpx.Response) -> _Token:
        if not response.is_success:
            raise RuntimeError(f"token exchange failed with HTTP {response.status_code}")
        try:
            payload: Any = response.json()
            raw_value = payload["accessToken"]
            expires_in = payload["expiresIn"]
            if not isinstance(raw_value, str):
                raise ValueError
            value = raw_value.strip()
            if not value or payload["tokenType"] != "Bearer":
                raise ValueError
            if isinstance(expires_in, bool) or not isinstance(expires_in, int) or expires_in <= 0:
                raise ValueError
        except (KeyError, TypeError, ValueError) as error:
            raise RuntimeError("invalid token exchange response") from error
        now = monotonic()
        skew = min(self._refresh_skew, expires_in / 2)
        return _Token(value, now + expires_in - skew, now + expires_in)

    @staticmethod
    def _authentication(token: _Token) -> Authentication:
        return Authentication({"Authorization": f"Bearer {token.value}"})

    @staticmethod
    def _service(context: AuthContext) -> str:
        service = context.service.strip()
        if not service:
            raise ValueError("auth context service must not be blank")
        return service

    def close(self) -> None:
        self._sync_client.close()

    async def aclose(self) -> None:
        await self._async_client.aclose()
