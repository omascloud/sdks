/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core;

import org.testng.annotations.Test;

import java.time.Duration;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

public class ClientOptionsTest {

    @Test
    public void testDefaults() {
        ClientOptions options = ClientOptions.builder().build();

        assertEquals(options.connectTimeout(), Duration.ofSeconds(3));
        assertEquals(options.requestTimeout(), Duration.ofSeconds(30));
        assertEquals(options.readTimeout(), Duration.ofSeconds(10));
        assertEquals(options.connectionAcquireTimeout(), Duration.ofSeconds(2));
        assertEquals(options.maxConnections(), 50);
    }

    @Test
    public void testRejectsInvalidValues() {
        expectThrows(IllegalArgumentException.class, () -> ClientOptions.builder()
                .connectTimeout(Duration.ZERO)
                .build());
        expectThrows(IllegalArgumentException.class, () -> ClientOptions.builder()
                .maxConnections(0)
                .build());
    }
}
