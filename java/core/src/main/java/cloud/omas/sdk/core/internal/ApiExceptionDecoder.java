/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.core.internal;

import cloud.omas.sdk.core.http.SdkHttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;

public final class ApiExceptionDecoder {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ApiExceptionDecoder() {}

    public static DecodedApiError decode(SdkHttpResponse response) {
        JsonNode body = parseBody(response.body());
        String errorCode = text(body, "errorCode");
        String message = text(body, "error");
        if (message == null) {
            message = "API request failed with HTTP " + response.statusCode();
        }
        return new DecodedApiError(
                message,
                response.statusCode(),
                errorCode,
                response.firstHeader("x-request-id"),
                parseRetryAfter(response.firstHeader("retry-after")),
                body == null ? null : body.get("extraData"));
    }

    private static JsonNode parseBody(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(body);
            return parsed != null && parsed.isObject() ? parsed : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static Duration parseRetryAfter(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
