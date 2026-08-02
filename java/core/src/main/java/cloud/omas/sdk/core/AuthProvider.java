/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface AuthProvider {

    CompletionStage<Authentication> resolve(AuthContext context);
}
