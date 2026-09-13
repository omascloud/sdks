from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import dataclass

import httpx

from ._types import RequestMetadata

RequestInterceptor = Callable[[RequestMetadata, httpx.Headers], Awaitable[None] | None]


@dataclass(frozen=True, slots=True)
class ClientOptions:
    connect_timeout: float = 3.0
    request_timeout: float = 30.0
    read_timeout: float = 10.0
    pool_timeout: float = 2.0
    max_connections: int = 50
    proxy: str | None = None
    interceptors: tuple[RequestInterceptor, ...] = ()
    transport: httpx.BaseTransport | None = None
    async_transport: httpx.AsyncBaseTransport | None = None

    def __post_init__(self) -> None:
        for name in (
            "connect_timeout",
            "request_timeout",
            "read_timeout",
            "pool_timeout",
        ):
            if getattr(self, name) <= 0:
                raise ValueError(f"{name} must be positive")
        if self.max_connections <= 0:
            raise ValueError("max_connections must be positive")
