/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core;

@FunctionalInterface
public interface RequestInterceptor {

    void intercept(RequestMetadata metadata, Headers.Builder headers);
}
