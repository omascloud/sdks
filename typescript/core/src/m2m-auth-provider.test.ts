import { afterEach, describe, expect, it, vi } from "vitest";

import { AuthenticationError } from "./errors.js";
import { M2mAuthProvider } from "./m2m-auth-provider.js";

const context = { service: "metrics", operationId: "listMetrics" };

describe("M2mAuthProvider", () => {
    afterEach(() => {
        vi.useRealTimers();
    });

    it.each([
        "",
        "   ",
        "\n\t",
    ])("rejects blank credential %#", (credential) => {
        expect(() => new M2mAuthProvider(credential)).toThrow(
            "credential must not be blank",
        );
    });

    it.each([
        -1,
        Number.NaN,
        Number.POSITIVE_INFINITY,
    ])("rejects invalid refresh skew %s", (refreshSkewMs) => {
        expect(
            () => new M2mAuthProvider("credential", { refreshSkewMs }),
        ).toThrow("refreshSkewMs must be a non-negative finite number");
    });

    it("rejects a blank target service without exchanging", async () => {
        let exchangeCalls = 0;
        const provider = new M2mAuthProvider("credential", {
            fetch: async () => {
                exchangeCalls += 1;
                return new Response(null, { status: 500 });
            },
        });

        await expect(
            provider.resolve(
                { service: "  ", operationId: "listMetrics" },
                new AbortController().signal,
            ),
        ).rejects.toThrow("auth context service must not be blank");
        expect(exchangeCalls).toBe(0);
    });

    it("exchanges once and caches a service-scoped bearer token", async () => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date("2026-08-03T00:00:00Z"));
        const requests: Array<{ url: string; init?: RequestInit }> = [];
        const provider = new M2mAuthProvider(" workspace-credential ", {
            fetch: async (input, init) => {
                requests.push({ url: String(input), init });
                return new Response(
                    JSON.stringify({
                        accessToken: "access-token",
                        tokenType: "Bearer",
                        expiresIn: 300,
                    }),
                    {
                        status: 200,
                        headers: { "Content-Type": "application/json" },
                    },
                );
            },
        });
        const signal = new AbortController().signal;

        const first = await provider.resolve(context, signal);
        const second = await provider.resolve(context, signal);

        expect(new Headers(first.headers).get("Authorization")).toBe(
            "Bearer access-token",
        );
        expect(new Headers(second.headers).get("Authorization")).toBe(
            "Bearer access-token",
        );
        expect(requests).toHaveLength(1);
        expect(requests[0]?.url).toBe(
            "https://api.omas.cloud/v1/auth/token-exchange",
        );
        expect(requests[0]?.init?.method).toBe("POST");
        expect(
            new Headers(requests[0]?.init?.headers).get("Authorization"),
        ).toBe("Bearer workspace-credential");
        expect(requests[0]?.init?.body).toBe('{"audience":"metrics"}');
    });

    it("keeps independent token caches for different services", async () => {
        const audiences: string[] = [];
        const provider = new M2mAuthProvider("credential", {
            fetch: async (_input, init) => {
                const body = JSON.parse(String(init?.body)) as {
                    audience: string;
                };
                audiences.push(body.audience);
                return new Response(
                    JSON.stringify({
                        accessToken: `${body.audience}-token`,
                        tokenType: "Bearer",
                        expiresIn: 300,
                    }),
                    { status: 200 },
                );
            },
        });
        const signal = new AbortController().signal;

        await provider.resolve(context, signal);
        await provider.resolve(
            { service: "operations", operationId: "getOperation" },
            signal,
        );
        await provider.resolve(context, signal);

        expect(audiences).toEqual(["metrics", "operations"]);
    });

    it("coalesces concurrent refreshes for one service", async () => {
        let exchangeCalls = 0;
        let releaseExchange: (() => void) | undefined;
        const exchangeStarted = new Promise<void>((resolve) => {
            releaseExchange = resolve;
        });
        const provider = new M2mAuthProvider("credential", {
            fetch: async () => {
                exchangeCalls += 1;
                await exchangeStarted;
                return new Response(
                    JSON.stringify({
                        accessToken: "shared-token",
                        tokenType: "Bearer",
                        expiresIn: 300,
                    }),
                    { status: 200 },
                );
            },
        });
        const signal = new AbortController().signal;

        const resolutions = Array.from({ length: 20 }, () =>
            provider.resolve(context, signal),
        );
        await vi.waitFor(() => expect(exchangeCalls).toBe(1));
        releaseExchange?.();

        const authentications = await Promise.all(resolutions);
        expect(exchangeCalls).toBe(1);
        expect(
            authentications.map((authentication) =>
                new Headers(authentication.headers).get("Authorization"),
            ),
        ).toEqual(Array.from({ length: 20 }, () => "Bearer shared-token"));
    });

    it("retains a valid token after failed early refresh but never after expiry", async () => {
        vi.useFakeTimers();
        const startedAt = new Date("2026-08-03T00:00:00Z");
        vi.setSystemTime(startedAt);
        let exchangeCalls = 0;
        const provider = new M2mAuthProvider("credential", {
            fetch: async () => {
                exchangeCalls += 1;
                if (exchangeCalls > 1) {
                    throw new Error("exchange unavailable");
                }
                return new Response(
                    JSON.stringify({
                        accessToken: "original-token",
                        tokenType: "Bearer",
                        expiresIn: 100,
                    }),
                    { status: 200 },
                );
            },
        });
        const signal = new AbortController().signal;

        await provider.resolve(context, signal);
        vi.setSystemTime(new Date(startedAt.getTime() + 70_000));
        const duringEarlyRefreshFailure = await provider.resolve(
            context,
            signal,
        );

        expect(
            new Headers(duringEarlyRefreshFailure.headers).get("Authorization"),
        ).toBe("Bearer original-token");
        vi.setSystemTime(new Date(startedAt.getTime() + 100_000));
        await expect(provider.resolve(context, signal)).rejects.toMatchObject({
            constructor: AuthenticationError,
            message: "authenticate listMetrics request",
        });
        expect(exchangeCalls).toBe(3);
    });

    it("allows a refresh waiter to cancel without cancelling the shared exchange", async () => {
        let releaseExchange: (() => void) | undefined;
        const exchangeGate = new Promise<void>((resolve) => {
            releaseExchange = resolve;
        });
        const provider = new M2mAuthProvider("credential", {
            fetch: async () => {
                await exchangeGate;
                return new Response(
                    JSON.stringify({
                        accessToken: "shared-token",
                        tokenType: "Bearer",
                        expiresIn: 300,
                    }),
                    { status: 200 },
                );
            },
        });
        const first = provider.resolve(context, new AbortController().signal);
        const waiterController = new AbortController();
        const waiter = provider.resolve(context, waiterController.signal);
        const reason = new Error("caller stopped waiting");

        waiterController.abort(reason);

        await expect(waiter).rejects.toMatchObject({
            constructor: AuthenticationError,
            cause: reason,
        });
        releaseExchange?.();
        await expect(first).resolves.toMatchObject({
            headers: { Authorization: "Bearer shared-token" },
        });
    });

    it.each([
        [{ accessToken: "", tokenType: "Bearer", expiresIn: 300 }],
        [{ accessToken: "token", tokenType: "bearer", expiresIn: 300 }],
        [{ accessToken: "token", tokenType: "Bearer", expiresIn: 0 }],
        [{ accessToken: "token", tokenType: "Bearer", expiresIn: 1.5 }],
    ])("rejects malformed token exchange payload %#", async (payload) => {
        const provider = new M2mAuthProvider("credential", {
            fetch: async () =>
                new Response(JSON.stringify(payload), {
                    status: 200,
                    headers: { "Content-Type": "application/json" },
                }),
        });

        await expect(
            provider.resolve(context, new AbortController().signal),
        ).rejects.toMatchObject({
            constructor: AuthenticationError,
            message: "authenticate listMetrics request",
        });
    });

    it("redacts the credential and response body from exchange errors", async () => {
        const credential = "workspace-super-secret";
        const returnedToken = "returned-super-secret";
        const provider = new M2mAuthProvider(credential, {
            fetch: async () =>
                new Response(
                    JSON.stringify({
                        error: `${credential} ${returnedToken}`,
                        errorCode: "ACCESS_DENIED",
                    }),
                    { status: 401 },
                ),
        });

        const rejected = provider.resolve(
            context,
            new AbortController().signal,
        );
        await expect(rejected).rejects.toBeInstanceOf(AuthenticationError);
        try {
            await rejected;
        } catch (error) {
            const serialized = String(error);
            expect(serialized).not.toContain(credential);
            expect(serialized).not.toContain(returnedToken);
        }
    });
});
