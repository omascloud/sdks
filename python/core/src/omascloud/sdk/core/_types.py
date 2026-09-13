from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from typing import Protocol


@dataclass(frozen=True, slots=True)
class AuthContext:
    service: str
    operation_id: str


@dataclass(frozen=True, slots=True)
class Authentication:
    headers: Mapping[str, str] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class RequestMetadata:
    service: str
    operation_id: str
    method: str
    url: str


class AuthProvider(Protocol):
    def resolve(self, context: AuthContext) -> Authentication: ...


class AsyncAuthProvider(Protocol):
    async def aresolve(self, context: AuthContext) -> Authentication: ...
