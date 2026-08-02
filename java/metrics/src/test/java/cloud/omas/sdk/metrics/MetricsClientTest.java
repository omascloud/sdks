/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.metrics;

import cloud.omas.sdk.core.BearerAuthProvider;
import cloud.omas.sdk.core.exception.ApiException;
import cloud.omas.sdk.core.http.HttpTransport;
import cloud.omas.sdk.core.http.SdkHttpRequest;
import cloud.omas.sdk.core.http.SdkHttpResponse;
import cloud.omas.sdk.metrics.exception.ApiExceptions;
import cloud.omas.sdk.metrics.exception.ResourceNotFoundException;
import cloud.omas.sdk.metrics.model.GetDimensionValuesOperationRequest;
import cloud.omas.sdk.metrics.model.ListMetricsOperationRequest;
import cloud.omas.sdk.metrics.model.ListMetricsResponse;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

public class MetricsClientTest {

    @Test
    public void testListsMetricsThroughConfiguredRuntime() {
        String responseBody = """
                {
                  "metrics": [{"name": "cpu.usage", "dimensions": ["host"]}],
                  "totalCount": 1,
                  "dimensionFacets": []
                }
                """;
        RecordingTransport transport = new RecordingTransport(new SdkHttpResponse(
                200, Map.of(), responseBody.getBytes(StandardCharsets.UTF_8)));
        MetricsClient client = MetricsClient.builder()
                .authProvider(new BearerAuthProvider("token"))
                .transport(transport)
                .build();

        ListMetricsResponse response = client.listMetrics(ListMetricsOperationRequest.builder()
                .maxResults(25)
                .query("cpu usage")
                .dimensions(List.of("host", "region"))
                .build());

        assertEquals(response.totalCount(), Long.valueOf(1));
        assertEquals(response.metrics().get(0).name(), "cpu.usage");
        assertEquals(transport.request.headers().values().get("Authorization"), "Bearer token");
        assertEquals(transport.request.uri().toString(),
                "https://api.omas.cloud/v1/metrics?maxResults=25&query=cpu%20usage&dimensions=host&dimensions=region");
    }

    @Test
    public void testListsMetricsAsynchronously() {
        String responseBody = "{\"metrics\":[],\"totalCount\":0,\"dimensionFacets\":[]}";
        RecordingTransport transport = new RecordingTransport(new SdkHttpResponse(
                200, Map.of(), responseBody.getBytes(StandardCharsets.UTF_8)));
        MetricsAsyncClient client = MetricsAsyncClient.builder()
                .authProvider(new BearerAuthProvider("token"))
                .transport(transport)
                .build();

        ListMetricsResponse response = client.listMetrics(ListMetricsOperationRequest.builder()
                        .maxResults(10)
                        .build())
                .join();

        assertEquals(response.totalCount(), Long.valueOf(0));
        assertEquals(transport.request.uri().toString(),
                "https://api.omas.cloud/v1/metrics?maxResults=10");
    }

    @Test
    public void testValidatesMaximumResults() {
        expectThrows(IllegalArgumentException.class, () -> ListMetricsOperationRequest.builder()
                .maxResults(101)
                .build());
    }

    @Test
    public void testMapsServerErrorToTypedException() {
        String responseBody = """
                {
                  "errorCode": "RESOURCE_NOT_FOUND",
                  "error": "Metric was not found",
                  "extraData": {"field": "metricName"}
                }
                """;
        RecordingTransport transport = new RecordingTransport(new SdkHttpResponse(
                404, Map.of(), responseBody.getBytes(StandardCharsets.UTF_8)));
        MetricsClient client = MetricsClient.builder()
                .authProvider(new BearerAuthProvider("token"))
                .transport(transport)
                .build();

        ResourceNotFoundException exception = expectThrows(
                ResourceNotFoundException.class,
                () -> client.getDimensionValues(GetDimensionValuesOperationRequest.builder()
                        .metricName("missing")
                        .dimensionName("host")
                        .build()));

        assertEquals(exception.getMessage(), "Metric was not found");
        assertEquals(exception.statusCode(), 404);
        assertEquals(exception.errorCode(), "RESOURCE_NOT_FOUND");
        assertEquals(exception.extraData().field(), "metricName");
    }

    @Test
    public void testFallsBackForMalformedExceptionDetails() {
        SdkHttpResponse response = new SdkHttpResponse(
                404,
                Map.of(),
                """
                {
                  "errorCode": "RESOURCE_NOT_FOUND",
                  "error": "Metric was not found",
                  "extraData": {"field": []}
                }
                """.getBytes(StandardCharsets.UTF_8));

        ApiException exception = ApiExceptions.fromResponse(response);

        assertEquals(exception.getClass(), ApiException.class);
        assertEquals(exception.errorCode(), "RESOURCE_NOT_FOUND");
        assertEquals(exception.getMessage(), "Metric was not found");
    }

    private static final class RecordingTransport implements HttpTransport {

        private final SdkHttpResponse response;
        private SdkHttpRequest request;

        private RecordingTransport(SdkHttpResponse response) {
            this.response = response;
        }

        @Override
        public CompletableFuture<SdkHttpResponse> execute(SdkHttpRequest request) {
            this.request = request;
            return CompletableFuture.completedFuture(response);
        }
    }
}
