/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core.internal;

import cloud.omas.sdk.core.BearerAuthProvider;
import cloud.omas.sdk.core.ClientOptions;
import cloud.omas.sdk.core.exception.RequestTimeoutException;
import cloud.omas.sdk.core.http.SdkHttpRequest;
import cloud.omas.sdk.core.http.SdkHttpResponse;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class ClientRuntimeHttpTest {

    @Test(timeOut = 5000)
    public void testDeadlineClosesAContinuouslyStreamingResponse() throws Exception {
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicInteger chunksSent = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 200);
                for (int i = 0; i < 200; i++) {
                    exchange.getResponseBody().write(' ');
                    exchange.getResponseBody().flush();
                    chunksSent.incrementAndGet();
                    Thread.sleep(10);
                }
            } catch (IOException expectedOnCancellation) {
                disconnected.countDown();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        server.createContext("/fast", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try (ClientRuntime runtime = ClientRuntime.create(
                "metrics", new BearerAuthProvider("token"), ClientOptions.builder()
                        .requestTimeout(Duration.ofMillis(400))
                        .readTimeout(Duration.ofSeconds(2))
                        .maxConnections(1)
                        .build())) {
            CompletableFuture<SdkHttpResponse> result = runtime.execute("slow", SdkHttpRequest.builder()
                    .method("GET").uri(endpoint.resolve("/slow")).build());
            ExecutionException failure = expectThrows(ExecutionException.class,
                    () -> result.get(1500, TimeUnit.MILLISECONDS));
            assertTrue(failure.getCause() instanceof RequestTimeoutException);
            assertTrue(chunksSent.get() >= 2, "Response was actively streaming before the deadline");
            assertTrue(disconnected.await(1, TimeUnit.SECONDS), "Deadline should close the connection");
            assertEquals(runtime.execute("fast", SdkHttpRequest.builder()
                    .method("GET").uri(endpoint.resolve("/fast")).build())
                    .get(1, TimeUnit.SECONDS).statusCode(), 204);
        } finally {
            server.stop(0);
        }
    }
}
