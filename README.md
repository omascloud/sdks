# Omas Cloud SDKs

Official Java, Go, and TypeScript SDKs for the Omas Cloud API. They provide M2M token exchange, request validation, and typed API errors. Java includes synchronous and asynchronous clients; Go uses context-aware synchronous methods; TypeScript uses promise-based Fetch clients in Node.js and browsers.

## Java SDK

The Java SDK requires Java 17 or newer.

Add the metrics artifact to your project:

```xml
<dependency>
    <groupId>cloud.omas.sdk</groupId>
    <artifactId>metrics</artifactId>
    <version>0.2.0</version>
</dependency>
```

The shared `core` artifact is included transitively.

Create an M2M authentication provider from your workspace credential and pass it to the metrics client:

```java
import cloud.omas.sdk.core.M2mAuthProvider;
import cloud.omas.sdk.metrics.MetricsClient;
import cloud.omas.sdk.metrics.model.ListMetricsOperationRequest;
import cloud.omas.sdk.metrics.model.ListMetricsResponse;

try (M2mAuthProvider authProvider = M2mAuthProvider.builder()
        .credential(System.getenv("OMAS_M2M_TOKEN"))
        .build();
     MetricsClient metrics = MetricsClient.builder()
        .authProvider(authProvider)
        .build()) {
    ListMetricsResponse response = metrics.listMetrics(
            ListMetricsOperationRequest.builder()
                    .maxResults(25)
                    .build());

    response.metrics().forEach(metric -> System.out.println(metric.name()));
}
```

`M2mAuthProvider` exchanges the credential for short-lived, metrics-scoped access tokens and refreshes them automatically. `MetricsAsyncClient` exposes the same operations using `CompletableFuture`.

## Go SDK

The Go SDK requires Go 1.24 or newer. Add the repository module and import the shared runtime and generated metrics package:

```shell
go get github.com/omascloud/sdks/go
```

Create an M2M provider and pass it to the metrics client. Operation requests are flat structs: path and query parameters sit beside JSON body fields, while the generated client puts each field in the correct part of the HTTP request.

```go
package main

import (
    "context"
    "fmt"
    "os"

    "github.com/omascloud/sdks/go/core"
    "github.com/omascloud/sdks/go/metrics"
)

func main() {
    authProvider, err := core.NewM2MAuthProvider(os.Getenv("OMAS_M2M_TOKEN"))
    if err != nil {
        panic(err)
    }
    defer authProvider.Close()

    client, err := metrics.NewClient(authProvider)
    if err != nil {
        panic(err)
    }
    defer client.Close()

    maxResults := int32(25)
    response, err := client.ListMetrics(context.Background(), metrics.ListMetricsOperationRequest{
        MaxResults: &maxResults,
    })
    if err != nil {
        panic(err)
    }
    for _, metric := range response.Metrics {
        fmt.Println(metric.Name)
    }
}
```

Every known service error has a distinct generated Go type usable with `errors.As`. It embeds `*core.APIError`, which retains the HTTP status, error code, message, response headers, request ID, retry delay, and raw response body. Errors with structured `extraData` also expose typed details:

```go
var notFound *metrics.ResourceNotFoundError
if errors.As(err, &notFound) {
    fmt.Printf("request %s failed with status %d\n", notFound.RequestID, notFound.StatusCode)
    if notFound.Details != nil && notFound.Details.Field != nil {
        fmt.Printf("missing field: %s\n", *notFound.Details.Field)
    }
}
```

Unknown server error codes remain available as `*core.APIError`, so clients remain forward-compatible.

## TypeScript SDK

The TypeScript workspace requires Node.js 22 or newer and pnpm 11. Install dependencies and build both public packages from the repository root:

```shell
pnpm --dir typescript install
pnpm --dir typescript build
```

The packages are ESM-only. The default Core entry and Metrics package are browser-safe:

```ts
import { BearerAuthProvider } from "@omascloud/sdk-core";
import { MetricsClient, ResourceNotFoundError } from "@omascloud/sdk-metrics";

const metrics = new MetricsClient({
	authProvider: new BearerAuthProvider(token),
});

const controller = new AbortController();

try {
	const response = await metrics.getMetricData({
		metricName: "cpu.load",
		startTimestamp: Date.now() - 3_600_000,
		maxResults: 100,
	}, { signal: controller.signal });
	console.log(response.datapoints);
} catch (error) {
	if (error instanceof ResourceNotFoundError) {
		console.error(error.requestId, error.details);
	}
}
```

Operation requests are flat. The generated client places path and query values and JSON body fields according to the public contract. Known service errors have distinct generated classes; unknown codes remain `ApiError` for forward compatibility.

M2M token exchange is available only through the explicit Node entry so it cannot leak into browser bundles:

```ts
import { M2mAuthProvider } from "@omascloud/sdk-core/node";

const authProvider = new M2mAuthProvider(credential);
```

## Repository layout

- `schema/` contains the public OpenAPI contracts.
- `generators/` contains the language-specific generators.
- `java/core/` contains shared Java runtime and authentication code.
- `java/metrics/` contains the generated Java metrics client and models.
- `go/core/` contains the handwritten Go runtime and authentication code.
- `go/metrics/` contains the generated Go metrics client and models.
- `typescript/core/` contains the browser-safe TypeScript runtime and the explicit Node M2M entry.
- `typescript/metrics/` contains the generated public TypeScript Metrics client and models.

Generated sources are committed to the repository. Files under `java/metrics/src/generated/`, generated `.go` files under `go/metrics/`, and files under `typescript/metrics/src/generated/` should not be edited manually.

## Generating the SDK

Build and test the unified generator from the repository root:

```shell
mvn -f generators/pom.xml clean test package
```

The shaded jar exposes Picocli subcommands for each language or all languages:

```shell
java -jar generators/target/omas-sdk-generator-1.0.0-app.jar java
java -jar generators/target/omas-sdk-generator-1.0.0-app.jar go
java -jar generators/target/omas-sdk-generator-1.0.0-app.jar typescript
java -jar generators/target/omas-sdk-generator-1.0.0-app.jar all
```

Each subcommand generates the metrics service by default. Use `--service` repeatedly or with comma-separated names when generating selected services:

```shell
java -jar generators/target/omas-sdk-generator-1.0.0-app.jar all --service metrics
```

The generator uses the repository conventions directly. It reads `schema/<service>.yaml`, writes Java sources beneath `java/<service>`, formatted Go sources beneath `go/<service>`, and formatted TypeScript sources beneath `typescript/<service>/src/generated/` while preserving handwritten code and tests.

## Building and testing

Build and test all Java SDK modules from the repository root:

```shell
mvn -f java/pom.xml test
```

Run the Go tests and static analysis from the repository root:

```shell
cd go
go test -race ./...
go vet ./...
```

Run all TypeScript formatting, type, build, Node, browser, bundle-safety, and package-content gates from the repository root:

```shell
pnpm --dir typescript check
pnpm --dir typescript build
pnpm --dir typescript test
```

## License

This project is licensed under the [MIT License](LICENSE).
