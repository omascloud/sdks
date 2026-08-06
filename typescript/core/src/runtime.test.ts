import { afterEach, describe, expect, it, vi } from "vitest";

import {
    ApiError,
    AuthenticationError,
    RequestTimeoutError,
    SerializationError,
    TransportError,
} from "./errors.js";
import { ClientRuntime } from "./runtime.js";

describe("ClientRuntime", () => {
    afterEach(() => {
        vi.useRealTimers();
    });
    it.each([
        "relative/path",
        "ftp://api.example.test/",
        "https://api.example.test/?query=value",
        "https://api.example.test/#fragment",
    ])("rejects invalid endpoint %s", (endpoint) => {
        expect(
            () =>
                new ClientRuntime("metrics", endpoint, {
                    authProvider: { resolve: () => ({}) },
                }),
        ).toThrow(
            "endpoint must be an absolute HTTP(S) URL without query or fragment",
        );
    });

    it.each([
        0,
        -1,
        Number.NaN,
        Number.POSITIVE_INFINITY,
    ])("rejects invalid request timeout %s", (requestTimeoutMs) => {
        expect(
            () =>
                new ClientRuntime("metrics", "https://api.example.test/", {
                    authProvider: { resolve: () => ({}) },
                    requestTimeoutMs,
                }),
        ).toThrow("requestTimeoutMs must be a positive finite number");
    });

    it("preserves endpoint base paths and applies authentication after interceptors", async () => {
        const events: string[] = [];
        let recordedUrl: string | undefined;
        let recordedInit: RequestInit | undefined;
        const runtime = new ClientRuntime(
            "metrics",
            "https://api.example.test/",
            {
                endpoint: "https://example.test/root/",
                authProvider: {
                    resolve: () => {
                        events.push("auth");
                        return { headers: { Authorization: "Bearer secret" } };
                    },
                },
                interceptors: [
                    (_metadata, headers) => {
                        events.push("interceptor");
                        headers.set("X-Trace", "trace-1");
                    },
                ],
                fetch: async (input, init) => {
                    recordedUrl = String(input);
                    recordedInit = init;
                    return new Response(JSON.stringify({ metrics: [] }), {
                        status: 200,
                        headers: { "Content-Type": "application/json" },
                    });
                },
            },
        );

        const result = await runtime.request<{ metrics: unknown[] }>({
            operationId: "listMetrics",
            method: "GET",
            path: "v1/metrics",
            query: [
                ["dimensions", "host"],
                ["dimensions", "region"],
            ],
        });

        expect(result).toEqual({ metrics: [] });
        expect(events).toEqual(["interceptor", "auth"]);
        expect(recordedUrl).toBe(
            "https://example.test/root/v1/metrics?dimensions=host&dimensions=region",
        );
        const headers = new Headers(recordedInit?.headers);
        expect(headers.get("Accept")).toBe("application/json");
        expect(headers.get("X-Trace")).toBe("trace-1");
        expect(headers.get("Authorization")).toBe("Bearer secret");
    });

    it("rejects Authorization set by an interceptor before authentication", async () => {
        let authCalls = 0;
        let fetchCalls = 0;
        const runtime = new ClientRuntime(
            "metrics",
            "https://api.example.test/",
            {
                authProvider: {
                    resolve: () => {
                        authCalls += 1;
                        return {};
                    },
                },
                interceptors: [
                    (_metadata, headers) => {
                        headers.set("authorization", "Bearer replacement");
                    },
                ],
                fetch: async () => {
                    fetchCalls += 1;
                    return new Response(null, { status: 204 });
                },
            },
        );

        await expect(
            runtime.request<void>({
                operationId: "deleteMetric",
                method: "DELETE",
                path: "v1/metrics/cpu",
            }),
        ).rejects.toThrow("Request interceptors must not set Authorization");
        expect(authCalls).toBe(0);
        expect(fetchCalls).toBe(0);
    });

    it("serializes JSON bodies, applies credentials, and accepts empty success", async () => {
        let recordedInit: RequestInit | undefined;
        const runtime = new ClientRuntime(
            "metrics",
            "https://api.example.test/",
            {
                authProvider: {
                    resolve: () => ({ credentials: "include" }),
                },
                fetch: async (_input, init) => {
                    recordedInit = init;
                    return new Response(null, { status: 204 });
                },
            },
        );

        const result = await runtime.request<void>({
            operationId: "putMetricData",
            method: "POST",
            path: "v1/metrics/cpu",
            body: { entries: [{ value: 42 }] },
        });

        expect(result).toBeUndefined();
        expect(recordedInit?.credentials).toBe("include");
        expect(recordedInit?.body).toBe('{"entries":[{"value":42}]}');
        const headers = new Headers(recordedInit?.headers);
        expect(headers.get("Content-Type")).toBe("application/json");
    });

    it("wraps authentication failures without reaching fetch", async () => {
        const cause = new Error("provider failed");
        let fetchCalls = 0;
        const runtime = new ClientRuntime(
            "metrics",
            "https://api.example.test/",
            {
                authProvider: { resolve: () => Promise.reject(cause) },
                fetch: async () => {
                    fetchCalls += 1;
                    return new Response(null, { status: 204 });
                },
            },
        );

        const rejected = runtime.request<void>({
            operationId: "listMetrics",
            method: "GET",
            path: "v1/metrics",
        });

        await expect(rejected).rejects.toBeInstanceOf(AuthenticationError);
        await expect(rejected).rejects.toMatchObject({
            operationId: "listMetrics",
            cause,
            message: "authenticate listMetrics request",
        });
        expect(fetchCalls).toBe(0);
    });

    it("wraps fetch failures as transport errors", async () => {
        const cause = new Error("connection failed");
        const runtime = new ClientRuntime(
            "metrics",
            "https://api.example.test/",
            {
                authProvider: { resolve: () => ({}) },
                fetch: () => Promise.reject(cause),
            },
        );

        const rejected = runtime.request<void>({
            operationId: "listMetrics",
            method: "GET",
            path: "v1/metrics",
        });

        await expect(rejected).rejects.toBeInstanceOf(TransportError);
        await expect(rejected).rejects.toMatchObject({
            operationId: "listMetrics",
            cause,
            message: "execute listMetrics request",
        });
    });

    it("rejects unserializable bodies before authentication or fetch", async () => {
        let authCalls = 0;
        let fetchCalls = 0;
        const runtime = new ClientRuntime(
            "metrics",
            "https://api.example.test/",
            {
                authProvider: {
                    resolve: () => {
                        authCalls += 1;
                        return {};
                    },
                },
                fetch: async () => {
                    fetchCalls += 1;
                    return new Response(null, { status: 204 });
                },
            },
        );

        const rejected = runtime.request<void>({
            operationId: "putMetricData",
            method: "POST",
            path: "v1/metrics/cpu",
            body: { value: 1n },
        });

        await expect(rejected).rejects.toBeInstanceOf(SerializationError);
        expect(authCalls).toBe(0);
        expect(fetchCalls).toBe(0);
    });

    it("wraps malformed successful JSON as a serialization error", async () => {
        const runtime = new ClientRuntime(
            "metrics",
            "https://api.example.test/",
            {
                authProvider: { resolve: () => ({}) },
                fetch: async () =>
                    new Response("not-json", {
                        status: 200,
                        headers: { "Content-Type": "application/json" },
                    }),
            },
        );

        await expect(
            runtime.request({
                operationId: "listMetrics",
                method: "GET",
                path: "v1/metrics",
            }),
        ).rejects.toBeInstanceOf(SerializationError);
    });

    it("decodes safe API error metadata and invokes the service decoder", async () => {
        let decoded: ApiError<unknown> | undefined;
        const runtime = new ClientRuntime(
            "metrics",
            "https://api.example.test/",
            {
                authProvider: { resolve: () => ({}) },
                fetch: async () =>
                    new Response(
                        JSON.stringify({
                            errorCode: "RATE_LIMITED",
                            error: "slow down",
                            extraData: { limit: 10 },
                        }),
                        {
                            status: 429,
                            headers: {
                                "Retry-After": "3",
                                "X-Request-Id": "request-1",
                            },
                        },
                    ),
            },
            (error) => {
                decoded = error;
                return error;
            },
        );

        await expect(
            runtime.request({
                operationId: "listMetrics",
                method: "GET",
                path: "v1/metrics",
            }),
        ).rejects.toBeInstanceOf(ApiError);
        expect(decoded).toMatchObject({
            status: 429,
            errorCode: "RATE_LIMITED",
            serverMessage: "slow down",
            requestId: "request-1",
            retryAfterMs: 3_000,
            details: { limit: 10 },
        });
        expect(decoded).not.toHaveProperty("rawBody");
    });

    it("times out a request across authentication and transport", async () => {
        vi.useFakeTimers();
        const runtime = new ClientRuntime(
            "metrics",
            "https://api.example.test/",
            {
                requestTimeoutMs: 100,
                authProvider: {
                    resolve: (_context, signal) =>
                        new Promise((_resolve, reject) => {
                            signal.addEventListener(
                                "abort",
                                () => reject(signal.reason),
                                {
                                    once: true,
                                },
                            );
                        }),
                },
                fetch: async () => new Response(null, { status: 204 }),
            },
        );

        const rejected = runtime.request<void>({
            operationId: "listMetrics",
            method: "GET",
            path: "v1/metrics",
        });
        const expectation =
            expect(rejected).rejects.toBeInstanceOf(RequestTimeoutError);
        await vi.advanceTimersByTimeAsync(100);

        await expectation;
        expect(vi.getTimerCount()).toBe(0);
    });

    it("propagates caller cancellation through a transport error", async () => {
        const controller = new AbortController();
        const reason = new Error("caller stopped");
        const runtime = new ClientRuntime(
            "metrics",
            "https://api.example.test/",
            {
                authProvider: { resolve: () => ({}) },
                fetch: (_input, init) => {
                    if (init?.signal?.aborted) {
                        return Promise.reject(init.signal.reason);
                    }
                    return new Promise((_resolve, reject) => {
                        init?.signal?.addEventListener(
                            "abort",
                            () => reject(init.signal?.reason),
                            { once: true },
                        );
                    });
                },
            },
        );

        const rejected = runtime.request<void>(
            {
                operationId: "listMetrics",
                method: "GET",
                path: "v1/metrics",
            },
            { signal: controller.signal },
        );
        controller.abort(reason);

        await expect(rejected).rejects.toMatchObject({
            constructor: TransportError,
            cause: reason,
        });
    });
});
