import { describe, expect, it } from "vitest";

import { BearerAuthProvider } from "./auth.js";

const context = { service: "metrics", operationId: "listMetrics" };

describe("BearerAuthProvider", () => {
    it("resolves a trimmed bearer token", async () => {
        const provider = new BearerAuthProvider(" token-value ");

        const authentication = await provider.resolve(
            context,
            new AbortController().signal,
        );

        expect(new Headers(authentication.headers).get("Authorization")).toBe(
            "Bearer token-value",
        );
    });

    it.each(["", "   ", "\n\t"])("rejects a blank token %#", (token) => {
        expect(() => new BearerAuthProvider(token)).toThrow(
            "token must not be blank",
        );
    });

    it("does not expose mutable provider headers", async () => {
        const provider = new BearerAuthProvider("token-value");
        const signal = new AbortController().signal;
        const first = await provider.resolve(context, signal);
        new Headers(first.headers).set("Authorization", "changed");

        const second = await provider.resolve(context, signal);

        expect(new Headers(second.headers).get("Authorization")).toBe(
            "Bearer token-value",
        );
    });
});
