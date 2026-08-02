/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core.internal;

import cloud.omas.sdk.core.exception.ApiException;
import cloud.omas.sdk.core.http.SdkHttpResponse;

@FunctionalInterface
public interface ApiExceptionFactory {

    ApiException create(SdkHttpResponse response);
}
