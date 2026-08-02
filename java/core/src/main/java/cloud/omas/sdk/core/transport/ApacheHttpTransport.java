/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core.transport;

import cloud.omas.sdk.core.ClientOptions;
import cloud.omas.sdk.core.exception.RequestTimeoutException;
import cloud.omas.sdk.core.exception.TransportException;
import cloud.omas.sdk.core.http.HttpTransport;
import cloud.omas.sdk.core.http.SdkHttpRequest;
import cloud.omas.sdk.core.http.SdkHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

public final class ApacheHttpTransport implements HttpTransport {

    private final CloseableHttpAsyncClient client;
    private final Duration connectionAcquireTimeout;

    public ApacheHttpTransport(ClientOptions options) {
        connectionAcquireTimeout = options.connectionAcquireTimeout();
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(options.connectTimeout().toMillis()))
                .setSocketTimeout(Timeout.ofMilliseconds(options.readTimeout().toMillis()))
                .build();
        PoolingAsyncClientConnectionManager connectionManager = PoolingAsyncClientConnectionManagerBuilder.create()
                .setMaxConnTotal(options.maxConnections())
                .setMaxConnPerRoute(options.maxConnections())
                .setDefaultConnectionConfig(connectionConfig)
                .build();
        HttpAsyncClientBuilder builder = HttpAsyncClients.custom()
                .setConnectionManager(connectionManager)
                .disableAutomaticRetries();
        if (options.proxy() != null) {
            builder.setProxy(HttpHost.create(options.proxy().uri()));
        }
        client = builder.build();
        client.start();
    }

    @Override
    public CompletableFuture<SdkHttpResponse> execute(SdkHttpRequest request) {
        SimpleHttpRequest apacheRequest = SimpleHttpRequest.create(request.method(), request.uri());
        request.headers().values().forEach(apacheRequest::setHeader);
        byte[] body = request.body();
        if (body.length > 0) {
            apacheRequest.setBody(body, ContentType.APPLICATION_JSON);
        }
        apacheRequest.setConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(
                        effectiveConnectionAcquireTimeout(request.timeout()).toMillis()))
                .setResponseTimeout(Timeout.ofMilliseconds(request.timeout().toMillis()))
                .build());

        CompletableFuture<SdkHttpResponse> result = new CompletableFuture<>();
        Future<SimpleHttpResponse> execution = client.execute(apacheRequest, new FutureCallback<>() {
            @Override
            public void completed(SimpleHttpResponse response) {
                Map<String, List<String>> headers = new LinkedHashMap<>();
                for (Header header : response.getHeaders()) {
                    headers.computeIfAbsent(header.getName(), ignored -> new ArrayList<>()).add(header.getValue());
                }
                byte[] responseBody = response.getBody() == null ? new byte[0] : response.getBodyBytes();
                result.complete(new SdkHttpResponse(response.getCode(), headers, responseBody));
            }

            @Override
            public void failed(Exception exception) {
                if (exception instanceof SocketTimeoutException) {
                    result.completeExceptionally(new RequestTimeoutException("HTTP request timed out", exception));
                } else {
                    result.completeExceptionally(new TransportException("HTTP request failed", exception));
                }
            }

            @Override
            public void cancelled() {
                result.cancel(false);
            }
        });
        result.whenComplete((response, failure) -> {
            if (result.isCancelled()) {
                execution.cancel(true);
            }
        });
        return result;
    }

    private Duration effectiveConnectionAcquireTimeout(Duration requestTimeout) {
        return connectionAcquireTimeout.compareTo(requestTimeout) < 0
                ? connectionAcquireTimeout
                : requestTimeout;
    }

    @Override
    public void close() {
        client.close(CloseMode.GRACEFUL);
    }
}
