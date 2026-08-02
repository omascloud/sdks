/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core.internal;

import java.time.Duration;
import java.util.Objects;

public final class Validation {

    private Validation() {}

    public static String requireNonBlank(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    public static Duration requirePositive(Duration value, String name) {
        Duration required = Objects.requireNonNull(value, name);
        if (required.isZero() || required.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return required;
    }

    public static int requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    public static Duration requireNonNegative(Duration value, String name) {
        Duration required = Objects.requireNonNull(value, name);
        if (required.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return required;
    }
}
