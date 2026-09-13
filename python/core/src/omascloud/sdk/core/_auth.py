from __future__ import annotations

from ._types import AuthContext, Authentication


class BearerAuthProvider:
    def __init__(self, token: str) -> None:
        normalized = token.strip()
        if not normalized:
            raise ValueError("token must not be blank")
        self._token = normalized

    def resolve(self, _context: AuthContext) -> Authentication:
        return Authentication({"Authorization": f"Bearer {self._token}"})

    async def aresolve(self, context: AuthContext) -> Authentication:
        return self.resolve(context)
