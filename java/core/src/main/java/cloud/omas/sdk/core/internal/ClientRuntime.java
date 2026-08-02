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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

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

        CompletionStage<Authentication> authentication;
        try {
            authentication = Objects.requireNonNull(
                    authProvider.resolve(new AuthContext(service, operationId)),
                    "authProvider returned null");
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(
                    new AuthenticationException("Authentication failed for " + operationId, exception));
        }
        return authentication.handle((resolved, failure) -> {
            if (failure != null) {
                throw new AuthenticationException("Authentication failed for " + operationId, unwrap(failure));
            }
            Objects.requireNonNull(resolved, "authProvider returned null").headers().forEach(authorizedRequest::header);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            Duration remaining = options.requestTimeout().minus(elapsed);
            if (remaining.isZero() || remaining.isNegative()) {
                throw new RequestTimeoutException("Request timed out during authentication for " + operationId);
            }
            return authorizedRequest.timeout(remaining).build();
        }).thenCompose(transport::execute).thenApply(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw apiExceptionFactory.create(response);
            }
            return response;
        }).toCompletableFuture();
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
