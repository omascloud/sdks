/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core.http;

import cloud.omas.sdk.core.Headers;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

public final class SdkHttpRequest {

    private final String method;
    private final URI uri;
    private final Headers headers;
    private final byte[] body;
    private final Duration timeout;

    private SdkHttpRequest(Builder builder) {
        method = Objects.requireNonNull(builder.method, "method");
        uri = Objects.requireNonNull(builder.uri, "uri");
        headers = builder.headers.build();
        body = Arrays.copyOf(builder.body, builder.body.length);
        timeout = Objects.requireNonNull(builder.timeout, "timeout");
    }

    public static Builder builder() {
        return new Builder();
    }

    public String method() {
        return method;
    }

    public URI uri() {
        return uri;
    }

    public Headers headers() {
        return headers;
    }

    public byte[] body() {
        return Arrays.copyOf(body, body.length);
    }

    public Duration timeout() {
        return timeout;
    }

    public Builder toBuilder() {
        Builder builder = builder().method(method).uri(uri).body(body).timeout(timeout);
        headers.values().forEach(builder::header);
        return builder;
    }

    public static final class Builder {

        private String method;
        private URI uri;
        private final Headers.Builder headers = Headers.builder();
        private byte[] body = new byte[0];
        private Duration timeout = Duration.ofSeconds(30);

        private Builder() {}

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder uri(URI uri) {
            this.uri = uri;
            return this;
        }

        public Builder header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public Builder body(byte[] body) {
            this.body = Arrays.copyOf(Objects.requireNonNull(body, "body"), body.length);
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public SdkHttpRequest build() {
            return new SdkHttpRequest(this);
        }
    }
}
