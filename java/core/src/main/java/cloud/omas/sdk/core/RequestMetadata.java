/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core;

import java.net.URI;
import java.util.Objects;

public record RequestMetadata(String service, String operationId, String method, URI uri) {

    public RequestMetadata {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(uri, "uri");
    }
}
