from __future__ import annotations

import httpx
import pytest
from omascloud.sdk.core import ApiError, BearerAuthProvider, ClientOptions
from omascloud.sdk.metrics import (
    AlarmStatus,
    AsyncMetricsClient,
    ChartVariable,
    ListAlarmsOperationRequest,
    ListMetricsOperationRequest,
    Metric,
    MetricName,
    MetricsClient,
    Resolution,
    ResourceNotFoundError,
    WebhookConfig,
)
from pydantic import ValidationError


def test_sync_client_serializes_flat_request_and_decodes_response() -> None:
    def endpoint(request: httpx.Request) -> httpx.Response:
        assert request.method == "GET"
        assert request.url == "https://example.test/v1/metrics?maxResults=25"
        return httpx.Response(
            200,
            json={
                "metrics": [
                    {"name": "cpu.load", "dimensions": ["host"], "futureMetricField": True},
                ],
                "totalCount": 1,
                "dimensionFacets": [],
                "futureResponseField": {"enabled": True},
            },
        )

    client = MetricsClient(
        BearerAuthProvider("token"),
        endpoint="https://example.test",
        options=ClientOptions(transport=httpx.MockTransport(endpoint)),
    )

    response = client.list_metrics(ListMetricsOperationRequest(max_results=25))
    client.close()

    assert response.metrics[0].name == "cpu.load"
    assert response.metrics[0].dimensions == ("host",)
    assert "futureResponseField" not in response.model_dump(by_alias=True)
    assert "futureMetricField" not in response.metrics[0].model_dump(by_alias=True)


@pytest.mark.asyncio
async def test_async_client_decodes_known_typed_error() -> None:
    async def endpoint(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            404,
            headers={"X-Request-Id": "request-404"},
            json={
                "errorCode": "RESOURCE_NOT_FOUND",
                "error": "missing",
                "extraData": {
                    "chartId": None,
                    "variableName": None,
                    "field": "metricName",
                },
            },
        )

    client = AsyncMetricsClient(
        BearerAuthProvider("token"),
        endpoint="https://example.test",
        options=ClientOptions(async_transport=httpx.MockTransport(endpoint)),
    )

    with pytest.raises(ResourceNotFoundError) as raised:
        await client.list_metrics(ListMetricsOperationRequest())
    await client.aclose()

    assert raised.value.request_id == "request-404"
    assert raised.value.details.field == "metricName"


@pytest.mark.asyncio
@pytest.mark.parametrize("async_client", [False, True])
@pytest.mark.parametrize("details", [{"field": []}, []])
async def test_clients_preserve_api_error_when_details_are_malformed(
    async_client: bool, details: object
) -> None:
    response = httpx.Response(
        404,
        headers={"X-Request-Id": "request-404", "Retry-After": "3"},
        json={"errorCode": "RESOURCE_NOT_FOUND", "error": "missing", "extraData": details},
    )
    transport = httpx.MockTransport(lambda _request: response)
    options = ClientOptions(transport=transport, async_transport=transport)

    with pytest.raises(ApiError) as raised:
        if async_client:
            async with AsyncMetricsClient(BearerAuthProvider("token"), options=options) as client:
                await client.list_metrics(ListMetricsOperationRequest())
        else:
            with MetricsClient(BearerAuthProvider("token"), options=options) as sync_client:
                sync_client.list_metrics(ListMetricsOperationRequest())

    error = raised.value
    assert type(error) is ApiError
    assert error.status == 404
    assert error.error_code == "RESOURCE_NOT_FOUND"
    assert error.server_message == "missing"
    assert error.request_id == "request-404"
    assert error.retry_after == 3.0
    assert error.headers == response.headers
    assert error.details == details
    assert error.response_body == response.content


def test_generated_request_enforces_openapi_constraints() -> None:
    with pytest.raises(ValidationError):
        ListMetricsOperationRequest(max_results=0)

    with pytest.raises(ValidationError):
        ListMetricsOperationRequest(next_token="x" * 4097)

    with pytest.raises(ValidationError):
        ListAlarmsOperationRequest(statuses=[AlarmStatus.OK, AlarmStatus.OK])


def test_generated_models_are_deeply_immutable_and_preserve_composed_fields() -> None:
    metric = Metric(name="cpu", dimensions=["host"])
    assert metric.dimensions == ("host",)
    with pytest.raises(AttributeError):
        metric.dimensions.append("zone")  # type: ignore[union-attr]

    webhook = WebhookConfig(url="https://example.test", headers={"X-Test": "yes"})
    with pytest.raises(TypeError):
        webhook.headers["X-Test"] = "changed"  # type: ignore[index]

    variable = ChartVariable.model_validate(
        {
            "name": "cpu",
            "visible": True,
            "type": "METRIC",
            "metricName": "cpu.load",
            "aggregation": "avg",
        }
    )
    assert variable.name == "cpu"
    assert variable.metric_name == "cpu.load"

    with pytest.raises(ValidationError):
        ChartVariable.model_validate({"name": "cpu", "visible": True, "type": "METRIC"})

    with pytest.raises(ValidationError):
        Resolution(0)
    with pytest.raises(ValidationError):
        MetricName("!")
