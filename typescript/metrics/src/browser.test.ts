import { BearerAuthProvider } from "@omascloud/sdk-core";
import { describe, expect, it } from "vitest";

import { MetricsClient } from "./index.js";

describe("MetricsClient browser runtime", () => {
    it("executes a generated operation with browser Fetch primitives", async () => {
        let requestedUrl = "";
        const client = new MetricsClient({
            authProvider: new BearerAuthProvider("browser-token"),
            endpoint: "https://api.example.test/root/",
            fetch: async (input, init) => {
                requestedUrl = String(input);
                expect(new Headers(init?.headers).get("Authorization")).toBe(
                    "Bearer browser-token",
                );
                return new Response(
                    JSON.stringify({ metrics: [], totalCount: 0 }),
                    {
                        status: 200,
                    },
                );
            },
        });

        await expect(
            client.listMetrics({
                maxResults: 2,
                dimensions: ["host", "region"],
            }),
        ).resolves.toEqual({ metrics: [], totalCount: 0 });
        expect(requestedUrl).toBe(
            "https://api.example.test/root/v1/metrics?maxResults=2&dimensions=host&dimensions=region",
        );
    });
});
