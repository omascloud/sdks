/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core;

import cloud.omas.sdk.core.internal.Validation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class Authentication {

    private static final Authentication NONE = new Authentication(Map.of());

    private final Map<String, String> headers;

    private Authentication(Map<String, String> headers) {
        this.headers = Map.copyOf(headers);
    }

    public static Authentication none() {
        return NONE;
    }

    public static Authentication bearer(String token) {
        String value = Validation.requireNonBlank(token, "token");
        return headers(Map.of("Authorization", "Bearer " + value));
    }

    public static Authentication headers(Map<String, String> headers) {
        Objects.requireNonNull(headers, "headers");
        Map<String, String> copy = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            String normalizedName = Validation.requireNonBlank(name, "authentication header name");
            copy.put(normalizedName, Objects.requireNonNull(value, "authentication header value"));
        });
        return copy.isEmpty() ? NONE : new Authentication(copy);
    }

    public Map<String, String> headers() {
        return headers;
    }
}
