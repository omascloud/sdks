/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core.exception;

import java.time.Duration;

public class ApiException extends SdkException {

    private final int statusCode;
    private final String errorCode;
    private final String requestId;
    private final Duration retryAfter;
    private final Object extraData;

    public ApiException(
            String message,
            int statusCode,
            String errorCode,
            String requestId,
            Duration retryAfter) {
        this(message, statusCode, errorCode, requestId, retryAfter, null);
    }

    public ApiException(
            String message,
            int statusCode,
            String errorCode,
            String requestId,
            Duration retryAfter,
            Object extraData) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.requestId = requestId;
        this.retryAfter = retryAfter;
        this.extraData = extraData;
    }

    public int statusCode() {
        return statusCode;
    }

    public String errorCode() {
        return errorCode;
    }

    public String requestId() {
        return requestId;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public Object extraData() {
        return extraData;
    }
}
