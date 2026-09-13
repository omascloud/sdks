from __future__ import annotations

import httpx
from omascloud.sdk.core import ApiError, AuthenticationError


def test_api_error_retains_response_metadata() -> None:
    error = ApiError(
        status=404,
        error_code="ResourceNotFound",
        message="missing",
        request_id="request-1",
        retry_after=2.0,
        headers=httpx.Headers({"X-Request-Id": "request-1"}),
        details={"field": "metricName"},
        response_body=b'{"error":"missing"}',
    )

    assert str(error) == "ResourceNotFound: missing"
    assert error.status == 404
    assert error.server_message == "missing"
    assert error.details == {"field": "metricName"}
    assert error.response_body == b'{"error":"missing"}'


def test_operation_error_retains_operation_and_cause() -> None:
    cause = RuntimeError("no token")
    error = AuthenticationError("listMetrics", "authentication failed", cause)

    assert error.operation_id == "listMetrics"
    assert error.__cause__ is cause
