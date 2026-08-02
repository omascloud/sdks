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

public final class Headers {

    private final Map<String, String> values;

    private Headers(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<String, String> values() {
        return values;
    }

    public static final class Builder {

        private final Map<String, String> values = new LinkedHashMap<>();

        private Builder() {}

        public Builder put(String name, String value) {
            values.put(Validation.requireNonBlank(name, "header name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public Headers build() {
            return new Headers(values);
        }
    }
}
