/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core.internal;

import cloud.omas.sdk.core.AuthContext;
import cloud.omas.sdk.core.AuthProvider;
import cloud.omas.sdk.core.Authentication;
import cloud.omas.sdk.core.ClientOptions;
import cloud.omas.sdk.core.Headers;
import cloud.omas.sdk.core.RequestMetadata;
import cloud.omas.sdk.core.exception.AuthenticationException;
import cloud.omas.sdk.core.exception.RequestTimeoutException;
import cloud.omas.sdk.core.http.HttpTransport;
import cloud.omas.sdk.core.http.SdkHttpRequest;
import cloud.omas.sdk.core.http.SdkHttpResponse;
import cloud.omas.sdk.core.transport.ApacheHttpTransport;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ClientRuntime implements AutoCloseable {

    private final String service;
    private final AuthProvider authProvider;
    private final ClientOptions options;
    private final HttpTransport transport;
    private final boolean closeTransport;
    private final ApiExceptionFactory apiExceptionFactory;

    private ClientRuntime(
            String service,
            AuthProvider authProvider,
            ClientOptions options,
            HttpTransport transport,
            boolean closeTransport,
            ApiExceptionFactory apiExceptionFactory) {
        this.service = Objects.requireNonNull(service, "service");
        this.authProvider = Objects.requireNonNull(authProvider, "authProvider");
        this.options = Objects.requireNonNull(options, "options");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.closeTransport = closeTransport;
        this.apiExceptionFactory = Objects.requireNonNull(apiExceptionFactory, "apiExceptionFactory");
    }

    public static ClientRuntime create(String service, AuthProvider authProvider, ClientOptions options) {
        return new ClientRuntime(
                service, authProvider, options, new ApacheHttpTransport(options), true, defaultExceptionFactory());
    }

    public static ClientRuntime create(
            String service,
            AuthProvider authProvider,
            ClientOptions options,
            HttpTransport transport) {
        return new ClientRuntime(service, authProvider, options, transport, false, defaultExceptionFactory());
    }

    public static ClientRuntime create(
            String service,
            AuthProvider authProvider,
            ClientOptions options,
            HttpTransport transport,
            ApiExceptionFactory apiExceptionFactory) {
        return new ClientRuntime(service, authProvider, options, transport, false, apiExceptionFactory);
    }

    public static ClientRuntime create(
            String service,
            AuthProvider authProvider,
            ClientOptions options,
            ApiExceptionFactory apiExceptionFactory) {
        return new ClientRuntime(
                service, authProvider, options, new ApacheHttpTransport(options), true, apiExceptionFactory);
    }

    public CompletableFuture<SdkHttpResponse> execute(String operationId, SdkHttpRequest request) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(request, "request");
        long started = System.nanoTime();
        RequestMetadata metadata = new RequestMetadata(service, operationId, request.method(), request.uri());
        SdkHttpRequest.Builder authorizedRequest = request.toBuilder();
        options.interceptors().forEach(interceptor -> {
            Headers.Builder headers = Headers.builder();
            interceptor.intercept(metadata, headers);
            headers.build().values().forEach((name, value) -> {
                if ("authorization".equals(name.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("request interceptors cannot set authorization headers");
                }
                authorizedRequest.header(name, value);
            });
        });

        CompletableFuture<SdkHttpResponse> result = new CompletableFuture<>();
        AtomicReference<CompletableFuture<SdkHttpResponse>> inFlight = new AtomicReference<>();
        CompletableFuture<Void> deadline = new CompletableFuture<>();
        result.whenComplete((response, failure) -> {
            deadline.complete(null);
            CompletableFuture<SdkHttpResponse> exchange = inFlight.get();
            if (failure != null && exchange != null) {
                exchange.cancel(true);
            }
        });
        deadline.orTimeout(
                options.requestTimeout().toNanos() - (System.nanoTime() - started), TimeUnit.NANOSECONDS)
                .exceptionallyAsync(failure -> {
                    result.completeExceptionally(new RequestTimeoutException(
                            "Request timed out for " + operationId, failure));
                    return null;
                });

        CompletionStage<Authentication> authentication;
        try {
            remainingTimeout(operationId, started);
            authentication = Objects.requireNonNull(
                    authProvider.resolve(new AuthContext(service, operationId)),
                    "authProvider returned null");
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception instanceof RequestTimeoutException ? exception
                    : new AuthenticationException("Authentication failed for " + operationId, exception));
            return result;
        }
        authentication.handle((resolved, failure) -> {
            if (result.isDone()) {
                throw new CancellationException();
            }
            if (failure != null) {
                throw new AuthenticationException("Authentication failed for " + operationId, unwrap(failure));
            }
            Objects.requireNonNull(resolved, "authProvider returned null").headers().forEach(authorizedRequest::header);
            return authorizedRequest.timeout(remainingTimeout(operationId, started)).build();
        }).thenCompose(authorized -> {
            if (result.isDone()) {
                return CompletableFuture.<SdkHttpResponse>failedFuture(new CancellationException());
            }
            CompletableFuture<SdkHttpResponse> exchange = transport.execute(authorized);
            inFlight.set(exchange);
            if (result.isDone()) {
                exchange.cancel(true);
            }
            return exchange;
        }).thenApply(response -> {
            remainingTimeout(operationId, started);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw apiExceptionFactory.create(response);
            }
            return response;
        }).whenComplete((response, failure) -> {
            if (failure == null) {
                result.complete(response);
            } else {
                result.completeExceptionally(unwrap(failure));
            }
        });
        return result;
    }

    private Duration remainingTimeout(String operationId, long started) {
        Duration remaining = options.requestTimeout().minus(Duration.ofNanos(System.nanoTime() - started));
        if (remaining.isZero() || remaining.isNegative()) {
            throw new RequestTimeoutException("Request timed out for " + operationId);
        }
        return remaining;
    }

    private Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }

    private static ApiExceptionFactory defaultExceptionFactory() {
        return response -> ApiExceptionDecoder.decode(response).toException();
    }

    @Override
    public void close() {
        if (closeTransport) {
            transport.close();
        }
    }
}
