/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core;

import java.util.Objects;

public record AuthContext(String service, String operationId) {

    public AuthContext {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(operationId, "operationId");
    }
}
