/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core.internal;

import cloud.omas.sdk.core.BearerAuthProvider;
import cloud.omas.sdk.core.ClientOptions;
import cloud.omas.sdk.core.exception.ApiException;
import cloud.omas.sdk.core.http.HttpTransport;
import cloud.omas.sdk.core.http.SdkHttpRequest;
import cloud.omas.sdk.core.http.SdkHttpResponse;
import org.testng.annotations.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.expectThrows;

public class ClientRuntimeTest {

    @Test
    public void testAppliesInterceptorsAndAuthentication() {
        RecordingTransport transport = new RecordingTransport(new SdkHttpResponse(200, Map.of(), new byte[0]));
        ClientOptions options = ClientOptions.builder()
                .requestTimeout(Duration.ofSeconds(5))
                .addInterceptor((metadata, headers) -> headers.put("X-Operation", metadata.operationId()))
                .build();
        ClientRuntime runtime = ClientRuntime.create(
                "metrics", new BearerAuthProvider("secret"), options, transport);

        SdkFutures.await(runtime.execute("listMetrics", SdkHttpRequest.builder()
                .method("GET")
                .uri(URI.create("https://api.omas.cloud/v1/metrics"))
                .build()));

        assertEquals(transport.request.headers().values().get("Authorization"), "Bearer secret");
        assertEquals(transport.request.headers().values().get("X-Operation"), "listMetrics");
        assertNotNull(transport.request.timeout());
        runtime.close();
        assertFalse(transport.closed);
    }

    @Test
    public void testRejectsAuthorizationFromInterceptor() {
        RecordingTransport transport = new RecordingTransport(new SdkHttpResponse(200, Map.of(), new byte[0]));
        ClientOptions options = ClientOptions.builder()
                .addInterceptor((metadata, headers) -> headers.put("authorization", "unsafe"))
                .build();
        ClientRuntime runtime = ClientRuntime.create(
                "metrics", new BearerAuthProvider("secret"), options, transport);

        expectThrows(IllegalArgumentException.class, () -> runtime.execute(
                "listMetrics",
                SdkHttpRequest.builder()
                        .method("GET")
                        .uri(URI.create("https://api.omas.cloud/v1/metrics"))
                        .build()));
    }

    @Test
    public void testMapsApiFailureWithoutResponseBody() {
        RecordingTransport transport = new RecordingTransport(new SdkHttpResponse(
                429,
                Map.of("Retry-After", List.of("7"), "X-Request-Id", List.of("request-1")),
                "private response".getBytes()));
        ClientRuntime runtime = ClientRuntime.create(
                "metrics", new BearerAuthProvider("secret"), ClientOptions.builder().build(), transport);

        ApiException exception = expectThrows(ApiException.class, () -> SdkFutures.await(runtime.execute(
                "listMetrics",
                SdkHttpRequest.builder()
                        .method("GET")
                        .uri(URI.create("https://api.omas.cloud/v1/metrics"))
                        .build())));

        assertEquals(exception.statusCode(), 429);
        assertEquals(exception.requestId(), "request-1");
        assertEquals(exception.retryAfter(), Duration.ofSeconds(7));
        assertFalse(exception.getMessage().contains("private response"));
    }

    @Test
    public void testDecodesStructuredApiFailure() {
        RecordingTransport transport = new RecordingTransport(new SdkHttpResponse(
                400,
                Map.of(),
                """
                {"errorCode":"UNKNOWN_FIELD","error":"Unknown field","extraData":{"field":"secret"}}
                """.getBytes()));
        ClientRuntime runtime = ClientRuntime.create(
                "metrics", new BearerAuthProvider("secret"), ClientOptions.builder().build(), transport);

        ApiException exception = expectThrows(ApiException.class, () -> SdkFutures.await(runtime.execute(
                "listMetrics",
                SdkHttpRequest.builder()
                        .method("GET")
                        .uri(URI.create("https://api.omas.cloud/v1/metrics"))
                        .build())));

        assertEquals(exception.getMessage(), "Unknown field");
        assertEquals(exception.errorCode(), "UNKNOWN_FIELD");
        assertNotNull(exception.extraData());
    }

    private static final class RecordingTransport implements HttpTransport {

        private final SdkHttpResponse response;
        private SdkHttpRequest request;
        private boolean closed;

        private RecordingTransport(SdkHttpResponse response) {
            this.response = response;
        }

        @Override
        public CompletableFuture<SdkHttpResponse> execute(SdkHttpRequest request) {
            this.request = request;
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
