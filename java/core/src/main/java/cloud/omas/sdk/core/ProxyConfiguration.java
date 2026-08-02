/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core;

import java.net.URI;
import java.util.Objects;

public record ProxyConfiguration(URI uri) {

    public ProxyConfiguration {
        Objects.requireNonNull(uri, "uri");
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("proxy URI must contain a host");
        }
    }
}
