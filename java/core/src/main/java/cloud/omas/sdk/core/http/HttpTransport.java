/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core.http;

import java.util.concurrent.CompletableFuture;

public interface HttpTransport extends AutoCloseable {

    CompletableFuture<SdkHttpResponse> execute(SdkHttpRequest request);

    @Override
    default void close() {}
}
