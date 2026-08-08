import { describe, expect, it } from "vitest";

import { BearerAuthProvider, ClientRuntime, TransportError } from "./index.js";

describe("Core browser runtime", () => {
    it("runs interceptor, bearer auth, fetch, and JSON decoding in a browser", async () => {
        const events: string[] = [];
        const runtime = new ClientRuntime(
            "metrics",
            "https://api.example.test/",
            {
                authProvider: new BearerAuthProvider("browser-token"),
                interceptors: [
                    (_metadata, headers) => {
                        events.push("interceptor");
                        headers.set("X-Browser", "yes");
                    },
                ],
                fetch: async (input, init) => {
                    events.push("fetch");
                    expect(String(input)).toBe(
                        "https://api.example.test/v1/metrics",
                    );
                    const headers = new Headers(init?.headers);
                    expect(headers.get("X-Browser")).toBe("yes");
                    expect(headers.get("Authorization")).toBe(
                        "Bearer browser-token",
                    );
                    return new Response(JSON.stringify({ metrics: [] }), {
                        status: 200,
                    });
                },
            },
        );

        await expect(
            runtime.request({
                operationId: "listMetrics",
                method: "GET",
                path: "v1/metrics",
            }),
        ).resolves.toEqual({ metrics: [] });
        expect(events).toEqual(["interceptor", "fetch"]);
    });

    it("propagates caller cancellation in a browser", async () => {
        const controller = new AbortController();
        const runtime = new ClientRuntime(
            "metrics",
            "https://api.example.test/",
            {
                authProvider: { resolve: () => ({}) },
                fetch: async (_input, init) => {
                    await new Promise((_resolve, reject) => {
                        if (init?.signal?.aborted) {
                            reject(init.signal.reason);
                            return;
                        }
                        init?.signal?.addEventListener("abort", () =>
                            reject(init.signal?.reason),
                        );
                    });
                    return new Response();
                },
            },
        );
        const pending = runtime.request(
            {
                operationId: "listMetrics",
                method: "GET",
                path: "v1/metrics",
            },
            { signal: controller.signal },
        );
        controller.abort("cancelled");

        await expect(pending).rejects.toBeInstanceOf(TransportError);
    });
});
