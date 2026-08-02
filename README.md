# Omas Cloud SDKs

Official SDKs for the Omas Cloud API.

The first release provides the Java metrics SDK. It includes synchronous and asynchronous clients, automatic M2M token exchange, request validation, and typed API exceptions.

## Java SDK

The Java SDK requires Java 17 or newer.

Add the metrics artifact to your project:

```xml
<dependency>
    <groupId>cloud.omas.sdk</groupId>
    <artifactId>metrics</artifactId>
    <version>0.1.0</version>
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

## Repository layout

- `schema/` contains the public OpenAPI contracts.
- `generators/` contains the language-specific generators.
- `java/core/` contains shared Java runtime and authentication code.
- `java/metrics/` contains the generated Java metrics client and models.

Generated sources are committed to the repository. Files under `java/metrics/src/generated/` should not be edited manually.

## Generating the SDK

Build the standalone Java generator from the repository root:

```shell
mvn -f generators/java/pom.xml package
```

Then run it without arguments:

```shell
java -jar generators/java/target/omas-sdk-java-generator-1.0.0-app.jar
```

The generator uses the repository conventions directly. It reads `schema/metrics.yaml`, writes to `java/metrics`, and derives the Java packages from the service name.

## Building and testing

Build and test all Java SDK modules from the repository root:

```shell
mvn -f java/pom.xml test
```

## License

This project is licensed under the [MIT License](LICENSE).
