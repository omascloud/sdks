import type { AuthProvider, Fetch } from "@omascloud/sdk-core";
import { ApiError, BearerAuthProvider } from "@omascloud/sdk-core";
import { describe, expect, it } from "vitest";

import {
    MetricsClient,
    ResourceNotFoundError,
    UnauthorizedError,
} from "./index.js";

interface RecordedRequest {
    readonly url: string;
    readonly init: RequestInit | undefined;
}

function recordingClient(
    response: Response,
    endpoint = "https://example.test/base/",
) {
    const requests: RecordedRequest[] = [];
    const fetch: Fetch = async (input, init) => {
        requests.push({ url: String(input), init });
        return response;
    };
    return {
        client: new MetricsClient({
            authProvider: new BearerAuthProvider("public-token"),
            endpoint,
            fetch,
        }),
        requests,
    };
}

describe("MetricsClient request behavior", () => {
    it("serializes optional and repeated list query values in contract order", async () => {
        const { client, requests } = recordingClient(
            new Response(JSON.stringify({ metrics: [], totalCount: 0 }), {
                status: 200,
            }),
        );

        await expect(
            client.listMetrics({
                maxResults: 25,
                nextToken: "next",
                query: "cpu",
                dimensions: ["host", "region"],
                dimensionValues: ["host:web", "region:west"],
            }),
        ).resolves.toEqual({ metrics: [], totalCount: 0 });

        expect(requests[0]?.url).toBe(
            "https://example.test/base/v1/metrics?maxResults=25&nextToken=next&query=cpu&dimensions=host&dimensions=region&dimensionValues=host%3Aweb&dimensionValues=region%3Awest",
        );
        expect(requests[0]?.init?.method).toBe("GET");
        const headers = new Headers(requests[0]?.init?.headers);
        expect(headers.get("Authorization")).toBe("Bearer public-token");
        expect(headers.get("Accept")).toBe("application/json");
    });

    it("encodes path fields and keeps query fields out of the path", async () => {
        const { client, requests } = recordingClient(
            new Response(JSON.stringify({ datapoints: [] }), { status: 200 }),
        );

        await client.getMetricData({
            metricName: "cpu.load",
            startTimestamp: 10,
            endTimestamp: 20,
            includeDimensions: ["host", "region"],
        });

        expect(requests[0]?.url).toBe(
            "https://example.test/base/v1/metrics/cpu.load?startTimestamp=10&endTimestamp=20&includeDimensions=host&includeDimensions=region",
        );
    });

    it("serializes only flattened body fields", async () => {
        const { client, requests } = recordingClient(
            new Response(null, { status: 204 }),
        );

        await client.putMetricData({
            metricName: "cpu.load",
            entries: [{ timestamp: 100, value: 2.5 }],
        });

        expect(requests[0]?.url).toBe(
            "https://example.test/base/v1/metrics/cpu.load",
        );
        expect(requests[0]?.init?.method).toBe("POST");
        expect(JSON.parse(String(requests[0]?.init?.body))).toEqual({
            entries: [{ timestamp: 100, value: 2.5 }],
        });
        expect(
            new Headers(requests[0]?.init?.headers).get("Content-Type"),
        ).toBe("application/json");
    });

    it("returns void for an empty delete response", async () => {
        const { client, requests } = recordingClient(
            new Response(null, { status: 204 }),
        );

        await expect(
            client.deleteMetric({ metricName: "cpu.load" }),
        ).resolves.toBeUndefined();
        expect(requests[0]?.init?.method).toBe("DELETE");
    });
});

describe("MetricsClient request validation", () => {
    it.each([
        [
            "missing required",
            (client: MetricsClient) =>
                client.getMetricData({ startTimestamp: 1 } as never),
        ],
        [
            "blank required string",
            (client: MetricsClient) =>
                client.deleteMetric({ metricName: "  " }),
        ],
        [
            "numeric minimum",
            (client: MetricsClient) => client.listMetrics({ maxResults: 0 }),
        ],
        [
            "numeric maximum",
            (client: MetricsClient) => client.listMetrics({ maxResults: 101 }),
        ],
        [
            "maximum string length",
            (client: MetricsClient) =>
                client.listMetrics({ query: "x".repeat(256) }),
        ],
        [
            "pattern",
            (client: MetricsClient) =>
                client.deleteMetric({ metricName: "not/valid" }),
        ],
        [
            "minimum collection size",
            (client: MetricsClient) =>
                client.putMetricData({ metricName: "cpu", entries: [] }),
        ],
        [
            "maximum collection size",
            (client: MetricsClient) =>
                client.listMetrics({ dimensions: Array(31).fill("host") }),
        ],
        [
            "unique collection items",
            (client: MetricsClient) =>
                client.listAlarms({ statuses: ["OK", "OK"] }),
        ],
    ])("rejects %s before authentication or fetch", async (_name, invoke) => {
        let authCalls = 0;
        let fetchCalls = 0;
        const authProvider: AuthProvider = {
            resolve: () => {
                authCalls += 1;
                return {};
            },
        };
        const client = new MetricsClient({
            authProvider,
            fetch: async () => {
                fetchCalls += 1;
                return new Response(null, { status: 204 });
            },
        });

        await expect(invoke(client)).rejects.toThrow(/^validate .* request:/);
        expect(authCalls).toBe(0);
        expect(fetchCalls).toBe(0);
    });
});

describe("MetricsClient API errors", () => {
    it("decodes a known error with typed details and protocol metadata", async () => {
        const details = {
            chartId: "chart-1",
            variableName: null,
            field: "name",
        };
        const { client } = recordingClient(
            new Response(
                JSON.stringify({
                    errorCode: "RESOURCE_NOT_FOUND",
                    error: "resource missing",
                    extraData: details,
                }),
                {
                    status: 404,
                    headers: {
                        "Retry-After": "3",
                        "X-Request-Id": "request-1",
                        "X-Test": "preserved",
                    },
                },
            ),
        );

        const error = await client
            .getDashboard({ dashboardId: "missing" })
            .catch((value) => value);

        expect(error).toBeInstanceOf(ResourceNotFoundError);
        expect(error).toMatchObject({
            status: 404,
            errorCode: "RESOURCE_NOT_FOUND",
            serverMessage: "resource missing",
            requestId: "request-1",
            retryAfterMs: 3000,
            details,
        });
        expect(error.headers.get("X-Test")).toBe("preserved");
    });

    it("decodes a known error without details", async () => {
        const { client } = recordingClient(
            new Response(
                JSON.stringify({
                    errorCode: "UNAUTHORIZED",
                    error: "no access",
                }),
                {
                    status: 401,
                },
            ),
        );

        await expect(
            client.deleteMetric({ metricName: "cpu" }),
        ).rejects.toBeInstanceOf(UnauthorizedError);
    });

    it.each([
        [
            "unknown code",
            JSON.stringify({ errorCode: "NEW_CODE", error: "new" }),
        ],
        ["non-JSON", "gateway failure"],
        ["malformed envelope", "{"],
    ])("retains base ApiError for %s", async (_name, body) => {
        const { client } = recordingClient(new Response(body, { status: 500 }));

        const error = await client
            .deleteMetric({ metricName: "cpu" })
            .catch((value) => value);

        expect(error).toBeInstanceOf(ApiError);
        expect(error).not.toBeInstanceOf(ResourceNotFoundError);
        expect(error.constructor).toBe(ApiError);
    });
});
