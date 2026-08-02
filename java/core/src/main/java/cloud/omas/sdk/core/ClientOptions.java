/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core;

import cloud.omas.sdk.core.internal.Validation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ClientOptions {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_CONNECTION_ACQUIRE_TIMEOUT = Duration.ofSeconds(2);
    private static final int DEFAULT_MAX_CONNECTIONS = 50;

    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Duration readTimeout;
    private final Duration connectionAcquireTimeout;
    private final int maxConnections;
    private final ProxyConfiguration proxy;
    private final List<RequestInterceptor> interceptors;

    private ClientOptions(Builder builder) {
        connectTimeout = Validation.requirePositive(builder.connectTimeout, "connectTimeout");
        requestTimeout = Validation.requirePositive(builder.requestTimeout, "requestTimeout");
        readTimeout = Validation.requirePositive(builder.readTimeout, "readTimeout");
        connectionAcquireTimeout = Validation.requirePositive(
                builder.connectionAcquireTimeout, "connectionAcquireTimeout");
        maxConnections = Validation.requirePositive(builder.maxConnections, "maxConnections");
        proxy = builder.proxy;
        interceptors = List.copyOf(builder.interceptors);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public Duration readTimeout() {
        return readTimeout;
    }

    public Duration connectionAcquireTimeout() {
        return connectionAcquireTimeout;
    }

    public int maxConnections() {
        return maxConnections;
    }

    public ProxyConfiguration proxy() {
        return proxy;
    }

    public List<RequestInterceptor> interceptors() {
        return interceptors;
    }

    public static final class Builder {

        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private Duration readTimeout = DEFAULT_READ_TIMEOUT;
        private Duration connectionAcquireTimeout = DEFAULT_CONNECTION_ACQUIRE_TIMEOUT;
        private int maxConnections = DEFAULT_MAX_CONNECTIONS;
        private ProxyConfiguration proxy;
        private final List<RequestInterceptor> interceptors = new ArrayList<>();

        private Builder() {}

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder readTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        public Builder connectionAcquireTimeout(Duration connectionAcquireTimeout) {
            this.connectionAcquireTimeout = connectionAcquireTimeout;
            return this;
        }

        public Builder maxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
            return this;
        }

        public Builder proxy(ProxyConfiguration proxy) {
            this.proxy = proxy;
            return this;
        }

        public Builder addInterceptor(RequestInterceptor interceptor) {
            interceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
            return this;
        }

        public ClientOptions build() {
            return new ClientOptions(this);
        }
    }
}
