/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core;

import cloud.omas.sdk.core.http.HttpTransport;
import cloud.omas.sdk.core.http.SdkHttpRequest;
import cloud.omas.sdk.core.internal.AbstractSdkClient;
import cloud.omas.sdk.core.internal.Validation;
import com.fasterxml.jackson.core.type.TypeReference;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Exchanges an M2M credential for short-lived service access tokens.
 *
 * <p>A token is refreshed asynchronously before expiry. Concurrent callers share
 * the same refresh, and an early refresh failure retains the previous token until
 * its actual expiry.</p>
 */
public final class M2mAuthProvider implements AuthProvider, AutoCloseable {

    private static final Duration DEFAULT_REFRESH_SKEW = Duration.ofSeconds(30);

    private final TokenExchangeClient tokenExchangeClient;
    private final Duration refreshSkew;
    private final Clock clock;
    private final ConcurrentMap<String, TokenSlot> tokens = new ConcurrentHashMap<>();

    private M2mAuthProvider(Builder builder) {
        String credential = Validation.requireNonBlank(builder.credential, "credential");
        refreshSkew = Validation.requireNonNegative(builder.refreshSkew, "refreshSkew");
        clock = Objects.requireNonNull(builder.clock, "clock");
        tokenExchangeClient = new TokenExchangeClient(
                credential,
                builder.options,
                builder.transport);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public CompletionStage<Authentication> resolve(AuthContext context) {
        Objects.requireNonNull(context, "context");
        try {
            String audience = Validation.requireNonBlank(context.service(), "context.service");
            return resolveToken(audience).thenApply(cached -> Authentication.bearer(cached.value()));
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private CompletableFuture<CachedToken> resolveToken(String audience) {
        TokenSlot token = tokens.computeIfAbsent(audience, ignored -> new TokenSlot());
        Instant now = clock.instant();
        CachedToken current = token.current;
        if (current != null && now.isBefore(current.refreshAt())) {
            return CompletableFuture.completedFuture(current);
        }

        synchronized (token) {
            now = clock.instant();
            current = token.current;
            if (current != null && now.isBefore(current.refreshAt())) {
                return CompletableFuture.completedFuture(current);
            }
            CompletableFuture<CachedToken> refresh = token.refresh;
            if (refresh == null) {
                refresh = exchange(audience, now);
                token.refresh = refresh;
                CompletableFuture<CachedToken> startedRefresh = refresh;
                startedRefresh.whenComplete((refreshed, failure) -> {
                    synchronized (token) {
                        if (failure == null) {
                            token.current = refreshed;
                        }
                        if (token.refresh == startedRefresh) {
                            token.refresh = null;
                        }
                    }
                });
            }
            return retainUsableTokenOnFailure(refresh, current);
        }
    }

    private CompletableFuture<CachedToken> exchange(String audience, Instant issuedAt) {
        return tokenExchangeClient.exchange(audience)
                .thenApply(response -> cachedToken(response, issuedAt));
    }

    private CachedToken cachedToken(ExchangeResponse response, Instant issuedAt) {
        String accessToken = Validation.requireNonBlank(response.accessToken(), "exchange response accessToken");
        if (!"Bearer".equals(response.tokenType())) {
            throw new IllegalStateException("Unsupported exchange response token type: " + response.tokenType());
        }
        int expiresIn = Objects.requireNonNull(response.expiresIn(), "exchange response expiresIn");
        if (expiresIn <= 0) {
            throw new IllegalStateException("exchange response expiresIn must be positive");
        }

        Duration lifetime = Duration.ofSeconds(expiresIn);
        Duration effectiveSkew = refreshSkew.compareTo(lifetime.dividedBy(2)) > 0
                ? lifetime.dividedBy(2)
                : refreshSkew;
        Instant expiresAt = issuedAt.plus(lifetime);
        return new CachedToken(accessToken, expiresAt.minus(effectiveSkew), expiresAt);
    }

    private CompletableFuture<CachedToken> retainUsableTokenOnFailure(
            CompletableFuture<CachedToken> refresh, CachedToken current) {
        return refresh.handle((refreshed, failure) -> {
            if (failure == null) {
                return refreshed;
            }
            if (current != null && clock.instant().isBefore(current.expiresAt())) {
                return current;
            }
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause()
                    : failure;
            throw new CompletionException(cause);
        });
    }

    @Override
    public void close() {
        tokenExchangeClient.close();
    }

    private record CachedToken(String value, Instant refreshAt, Instant expiresAt) {}

    private record ExchangeRequest(String audience) {}

    private record ExchangeResponse(String accessToken, String tokenType, Integer expiresIn) {}

    private static final class TokenSlot {

        private volatile CachedToken current;
        private CompletableFuture<CachedToken> refresh;
    }

    private static final class TokenExchangeClient extends AbstractSdkClient {

        private static final URI ENDPOINT = URI.create("https://api.omas.cloud/");

        private TokenExchangeClient(String credential, ClientOptions options, HttpTransport transport) {
            super("auth", ENDPOINT, new BearerAuthProvider(credential), options, transport);
        }

        private CompletableFuture<ExchangeResponse> exchange(String audience) {
            RequestUriBuilder uri = requestUri("/v1/auth/token-exchange");
            SdkHttpRequest request = httpRequest("POST", uri)
                    .header("Content-Type", "application/json")
                    .body(serialize(new ExchangeRequest(audience)))
                    .build();
            return execute("exchangeAccessToken", request)
                    .thenApply(response -> deserialize(response.body(), new TypeReference<ExchangeResponse>() {}));
        }
    }

    public static final class Builder {

        private String credential;
        private ClientOptions options;
        private HttpTransport transport;
        private Duration refreshSkew = DEFAULT_REFRESH_SKEW;
        private Clock clock = Clock.systemUTC();

        private Builder() {}

        public Builder credential(String credential) {
            this.credential = credential;
            return this;
        }

        public Builder options(ClientOptions options) {
            this.options = options;
            return this;
        }

        public Builder transport(HttpTransport transport) {
            this.transport = transport;
            return this;
        }

        public Builder refreshSkew(Duration refreshSkew) {
            this.refreshSkew = refreshSkew;
            return this;
        }

        Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public M2mAuthProvider build() {
            return new M2mAuthProvider(this);
        }
    }
}
