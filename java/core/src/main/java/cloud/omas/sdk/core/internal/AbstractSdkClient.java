/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core.internal;

import cloud.omas.sdk.core.AuthProvider;
import cloud.omas.sdk.core.ClientOptions;
import cloud.omas.sdk.core.exception.SerializationException;
import cloud.omas.sdk.core.http.HttpTransport;
import cloud.omas.sdk.core.http.SdkHttpRequest;
import cloud.omas.sdk.core.http.SdkHttpResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public abstract class AbstractSdkClient implements AutoCloseable {

    private final ClientRuntime runtime;
    private final ObjectMapper objectMapper;
    private final URI endpoint;

    protected AbstractSdkClient(
            String service,
            URI endpoint,
            AuthProvider authProvider,
            ClientOptions options,
            HttpTransport transport) {
        this(
                service,
                endpoint,
                authProvider,
                options,
                transport,
                response -> ApiExceptionDecoder.decode(response).toException());
    }

    protected AbstractSdkClient(
            String service,
            URI endpoint,
            AuthProvider authProvider,
            ClientOptions options,
            HttpTransport transport,
            ApiExceptionFactory apiExceptionFactory) {
        AuthProvider requiredAuthProvider = Objects.requireNonNull(authProvider, "authProvider");
        ClientOptions effectiveOptions = options == null ? ClientOptions.builder().build() : options;
        this.endpoint = normalizeEndpoint(endpoint);
        runtime = transport == null
                ? ClientRuntime.create(service, requiredAuthProvider, effectiveOptions, apiExceptionFactory)
                : ClientRuntime.create(service, requiredAuthProvider, effectiveOptions, transport, apiExceptionFactory);
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    protected final RequestUriBuilder requestUri(String path) {
        return new RequestUriBuilder(path);
    }

    protected final SdkHttpRequest.Builder httpRequest(String method, RequestUriBuilder uri) {
        return SdkHttpRequest.builder()
                .method(method)
                .uri(uri.build(endpoint))
                .header("Accept", "application/json");
    }

    protected final CompletableFuture<SdkHttpResponse> execute(String operationId, SdkHttpRequest request) {
        return runtime.execute(operationId, request);
    }

    protected final byte[] serialize(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (IOException exception) {
            throw new SerializationException("Cannot encode request body", exception);
        }
    }

    protected final <T> T deserialize(byte[] value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (IOException exception) {
            throw new SerializationException("Cannot decode response body", exception);
        }
    }

    @Override
    public final void close() {
        runtime.close();
    }

    private static URI normalizeEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (!endpoint.isAbsolute()
                || endpoint.getHost() == null
                || !("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("endpoint must be an absolute HTTP(S) URI without query or fragment");
        }
        String value = endpoint.toString();
        return value.endsWith("/") ? endpoint : URI.create(value + "/");
    }

    protected static final class RequestUriBuilder {

        private String path;
        private final List<String> query = new ArrayList<>();

        private RequestUriBuilder(String path) {
            this.path = Objects.requireNonNull(path, "path");
        }

        public RequestUriBuilder pathParameter(String name, Object value) {
            path = path.replace(
                    "{" + name + "}", encode(Objects.requireNonNull(value, name).toString()));
            return this;
        }

        public RequestUriBuilder queryParameter(String name, Object value) {
            if (value instanceof Iterable<?> values) {
                values.forEach(item -> queryParameter(name, item));
            } else if (value != null) {
                query.add(encode(name) + "=" + encode(value.toString()));
            }
            return this;
        }

        private URI build(URI endpoint) {
            StringBuilder relativeUri = new StringBuilder(path.substring(1));
            if (!query.isEmpty()) {
                relativeUri.append('?').append(String.join("&", query));
            }
            return endpoint.resolve(relativeUri.toString());
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
        }
    }
}
