/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core.internal;

import cloud.omas.sdk.core.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;

public record DecodedApiError(
        String message,
        int statusCode,
        String errorCode,
        String requestId,
        Duration retryAfter,
        JsonNode extraData) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public <T> T extraData(Class<T> type) {
        return extraData == null || extraData.isNull() ? null : OBJECT_MAPPER.convertValue(extraData, type);
    }

    public ApiException toException() {
        return new ApiException(message, statusCode, errorCode, requestId, retryAfter, extraData);
    }
}
