from __future__ import annotations

from typing import Generic, TypeVar

import httpx


class SdkError(Exception):
    """Base class for failures raised by Omas Cloud SDKs."""


DetailsT = TypeVar("DetailsT")


class ApiError(SdkError, Generic[DetailsT]):
    def __init__(
        self,
        *,
        status: int,
        error_code: str,
        message: str,
        request_id: str | None = None,
        retry_after: float | None = None,
        headers: httpx.Headers | None = None,
        details: DetailsT | None = None,
        response_body: bytes = b"",
    ) -> None:
        super().__init__(f"{error_code}: {message}" if error_code else message)
        self.status = status
        self.error_code = error_code
        self.server_message = message
        self.request_id = request_id
        self.retry_after = retry_after
        self.headers = httpx.Headers(headers)
        self.details = details
        self.response_body = response_body


class OperationError(SdkError):
    def __init__(self, operation_id: str, message: str, cause: BaseException) -> None:
        super().__init__(message)
        self.operation_id = operation_id
        self.__cause__ = cause


class AuthenticationError(OperationError):
    pass


class TransportError(OperationError):
    pass


class RequestTimeoutError(OperationError):
    pass


class SerializationError(OperationError):
    pass
