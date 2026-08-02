/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core;

import cloud.omas.sdk.core.http.HttpTransport;
import cloud.omas.sdk.core.http.SdkHttpRequest;
import cloud.omas.sdk.core.http.SdkHttpResponse;
import cloud.omas.sdk.core.internal.SdkFutures;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

public class M2mAuthProviderTest {

    @Test
    public void testExchangesAndCachesTokenForMetricsAudience() {
        MutableClock clock = new MutableClock();
        QueueTransport transport = new QueueTransport();
        transport.addResponse(tokenResponse("service-token", 60));
        M2mAuthProvider provider = provider(transport, clock, Duration.ofSeconds(20));

        Authentication first = resolve(provider, "listMetrics");
        Authentication second = resolve(provider, "getMetricData");

        assertEquals(first.headers().get("Authorization"), "Bearer service-token");
        assertEquals(second.headers().get("Authorization"), "Bearer service-token");
        assertEquals(transport.requests.size(), 1);
        SdkHttpRequest exchange = transport.requests.get(0);
        assertEquals(exchange.method(), "POST");
        assertEquals(exchange.uri().toString(), "https://api.omas.cloud/v1/auth/token-exchange");
        assertEquals(exchange.headers().values().get("Authorization"), "Bearer machine-credential");
        assertEquals(new String(exchange.body(), StandardCharsets.UTF_8), "{\"audience\":\"metrics\"}");
    }

    @Test
    public void testUsesAudienceSuppliedByEachSdk() {
        QueueTransport transport = new QueueTransport();
        transport.addResponse(tokenResponse("metrics-token", 60));
        transport.addResponse(tokenResponse("account-token", 60));
        M2mAuthProvider provider = provider(transport, new MutableClock(), Duration.ofSeconds(20));

        Authentication metrics = SdkFutures.await(
                provider.resolve(new AuthContext("metrics", "listMetrics")));
        Authentication account = SdkFutures.await(
                provider.resolve(new AuthContext("account", "getAccount")));

        assertEquals(metrics.headers().get("Authorization"), "Bearer metrics-token");
        assertEquals(account.headers().get("Authorization"), "Bearer account-token");
        assertEquals(new String(transport.requests.get(0).body(), StandardCharsets.UTF_8),
                "{\"audience\":\"metrics\"}");
        assertEquals(new String(transport.requests.get(1).body(), StandardCharsets.UTF_8),
                "{\"audience\":\"account\"}");
    }

    @Test
    public void testRefreshesBeforeExpiry() {
        MutableClock clock = new MutableClock();
        QueueTransport transport = new QueueTransport();
        transport.addResponse(tokenResponse("first-token", 60));
        transport.addResponse(tokenResponse("second-token", 60));
        M2mAuthProvider provider = provider(transport, clock, Duration.ofSeconds(20));

        assertToken(provider, "first-token");
        clock.advance(Duration.ofSeconds(39));
        assertToken(provider, "first-token");
        clock.advance(Duration.ofSeconds(1));
        assertToken(provider, "second-token");
        assertEquals(transport.requests.size(), 2);
    }

    @Test
    public void testUsesStillValidTokenWhenEarlyRefreshFails() {
        MutableClock clock = new MutableClock();
        QueueTransport transport = new QueueTransport();
        transport.addResponse(tokenResponse("cached-token", 60));
        transport.addFailure(new IllegalStateException("exchange unavailable"));
        transport.addFailure(new IllegalStateException("exchange unavailable"));
        M2mAuthProvider provider = provider(transport, clock, Duration.ofSeconds(20));

        assertToken(provider, "cached-token");
        clock.advance(Duration.ofSeconds(40));
        assertToken(provider, "cached-token");
        clock.advance(Duration.ofSeconds(20));
        expectThrows(IllegalStateException.class,
                () -> resolve(provider, "listMetrics"));
    }

    @Test
    public void testCollapsesConcurrentInitialExchange() {
        BlockingTransport transport = new BlockingTransport(tokenResponse("shared-token", 60));
        M2mAuthProvider provider = provider(transport, new MutableClock(), Duration.ofSeconds(20));
        List<CompletionStage<Authentication>> resolutions = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            resolutions.add(provider.resolve(new AuthContext("metrics", "listMetrics")));
        }
        transport.completeExchange();

        for (CompletionStage<Authentication> resolution : resolutions) {
            assertEquals(resolution.toCompletableFuture().join().headers().get("Authorization"),
                    "Bearer shared-token");
        }
        assertEquals(transport.exchangeCount.get(), 1);
    }

    private static M2mAuthProvider provider(
            HttpTransport transport, MutableClock clock, Duration refreshSkew) {
        return M2mAuthProvider.builder()
                .credential("machine-credential")
                .transport(transport)
                .refreshSkew(refreshSkew)
                .clock(clock)
                .build();
    }

    private static void assertToken(M2mAuthProvider provider, String expected) {
        Authentication authentication = resolve(provider, "listMetrics");
        assertEquals(authentication.headers().get("Authorization"), "Bearer " + expected);
    }

    private static Authentication resolve(M2mAuthProvider provider, String operation) {
        return SdkFutures.await(provider.resolve(new AuthContext("metrics", operation)));
    }

    private static SdkHttpResponse tokenResponse(String token, int expiresIn) {
        String body = "{\"accessToken\":\"" + token
                + "\",\"tokenType\":\"Bearer\",\"expiresIn\":" + expiresIn + "}";
        return new SdkHttpResponse(200, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    private static final class QueueTransport implements HttpTransport {

        private final Queue<Object> outcomes = new ArrayDeque<>();
        private final List<SdkHttpRequest> requests = new ArrayList<>();

        private void addResponse(SdkHttpResponse response) {
            outcomes.add(response);
        }

        private void addFailure(RuntimeException failure) {
            outcomes.add(failure);
        }

        @Override
        public CompletableFuture<SdkHttpResponse> execute(SdkHttpRequest request) {
            requests.add(request);
            Object outcome = outcomes.remove();
            if (outcome instanceof RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture((SdkHttpResponse) outcome);
        }
    }

    private static final class BlockingTransport implements HttpTransport {

        private final SdkHttpResponse response;
        private final AtomicInteger exchangeCount = new AtomicInteger();
        private final CompletableFuture<SdkHttpResponse> exchange = new CompletableFuture<>();

        private BlockingTransport(SdkHttpResponse response) {
            this.response = response;
        }

        @Override
        public CompletableFuture<SdkHttpResponse> execute(SdkHttpRequest request) {
            exchangeCount.incrementAndGet();
            return exchange;
        }

        private void completeExchange() {
            exchange.complete(response);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
