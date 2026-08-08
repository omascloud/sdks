# @omascloud/sdk-metrics

Public Omas Cloud Metrics client for TypeScript. The package is ESM-only, supports Node.js 22 or newer and modern browsers, and uses `@omascloud/sdk-core` for authentication and Fetch execution.

```ts
import { BearerAuthProvider } from "@omascloud/sdk-core";
import { MetricsClient, ResourceNotFoundError } from "@omascloud/sdk-metrics";

const metrics = new MetricsClient({
    authProvider: new BearerAuthProvider(token),
});

try {
    const response = await metrics.getMetricData({
        metricName: "cpu.load",
        startTimestamp: Date.now() - 3_600_000,
        maxResults: 100,
    }, { signal });
    console.log(response);
} catch (error) {
    if (error instanceof ResourceNotFoundError) {
        console.error(error.requestId, error.details);
    }
}
```

Operation requests are flat: path and query parameters sit beside JSON body fields, while the generated client serializes each field to its contract location. Known service errors have distinct classes usable with `instanceof`; unknown codes remain `ApiError`.

For Node-only M2M authentication, import `M2mAuthProvider` from `@omascloud/sdk-core/node`.

MIT licensed.
