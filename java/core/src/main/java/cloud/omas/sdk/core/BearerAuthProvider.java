/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class BearerAuthProvider implements AuthProvider {

    private final Supplier<String> tokenSupplier;

    public BearerAuthProvider(String token) {
        this(() -> token);
    }

    public BearerAuthProvider(Supplier<String> tokenSupplier) {
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier");
    }

    @Override
    public CompletionStage<Authentication> resolve(AuthContext context) {
        Objects.requireNonNull(context, "context");
        return CompletableFuture.completedFuture(Authentication.bearer(tokenSupplier.get()));
    }
}
