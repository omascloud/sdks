import { describe, expect, it } from "vitest";

import {
    ApiError,
    AuthenticationError,
    RequestTimeoutError,
    SdkError,
    SerializationError,
    TransportError,
} from "./errors.js";

describe("ApiError", () => {
    it("retains safe protocol metadata and typed details", () => {
        const headers = new Headers({ "x-request-id": "request-1" });
        const error = new ApiError({
            status: 404,
            errorCode: "RESOURCE_NOT_FOUND",
            message: "missing",
            requestId: "request-1",
            retryAfterMs: 2_000,
            headers,
            details: { resourceType: "metric" },
        });
        headers.set("x-request-id", "changed");

        expect(error).toBeInstanceOf(SdkError);
        expect(error.name).toBe("ApiError");
        expect(error.message).toBe("RESOURCE_NOT_FOUND: missing");
        expect(error.serverMessage).toBe("missing");
        expect(error.status).toBe(404);
        expect(error.errorCode).toBe("RESOURCE_NOT_FOUND");
        expect(error.requestId).toBe("request-1");
        expect(error.retryAfterMs).toBe(2_000);
        expect(error.headers.get("x-request-id")).toBe("request-1");
        expect(error.details).toEqual({ resourceType: "metric" });
        expect(error).not.toHaveProperty("rawBody");
    });

    it("uses the server message when no stable code is available", () => {
        const error = new ApiError({
            status: 500,
            errorCode: "",
            message: "API request failed with HTTP 500",
            headers: new Headers(),
        });

        expect(error.message).toBe("API request failed with HTTP 500");
    });
});

describe("runtime errors", () => {
    it.each([
        [AuthenticationError, "AuthenticationError"],
        [TransportError, "TransportError"],
        [RequestTimeoutError, "RequestTimeoutError"],
        [SerializationError, "SerializationError"],
    ] as const)("%s preserves its cause", (ErrorType, name) => {
        const cause = new Error("safe cause");
        const error = new ErrorType("listMetrics", "request failed", cause);

        expect(error).toBeInstanceOf(SdkError);
        expect(error.name).toBe(name);
        expect(error.cause).toBe(cause);
        expect(error.message).toBe("request failed");
    });
});
