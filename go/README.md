# Omas Cloud Go SDK

The official Go SDK for the Omas Cloud API. It provides context-aware API
operations, automatic M2M token exchange, request validation, and typed service
errors.

## Requirements

Go 1.24 or newer.

## Installation

Install the Metrics SDK package:

```shell
go get github.com/omascloud/sdks/go/metrics@v0.1.0
```

The shared `core` package is included as part of the same Go module.

## Usage

Create an M2M authentication provider from your workspace credential and pass
it to the metrics client:

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
	response, err := client.ListMetrics(
		context.Background(),
		metrics.ListMetricsOperationRequest{MaxResults: &maxResults},
	)
	if err != nil {
		panic(err)
	}

	for _, metric := range response.Metrics {
		fmt.Println(metric.Name)
	}
}
```

`M2MAuthProvider` exchanges the credential for short-lived, service-scoped
access tokens and refreshes them automatically. API operations accept a
`context.Context`, allowing callers to propagate cancellation and deadlines.

## Typed errors

Every known service error has a distinct generated type that can be inspected
with `errors.As`. Each typed error embeds `*core.APIError`, which retains the
HTTP status, error code, message, response headers, request ID, retry delay, and
raw response body. Errors with structured `extraData` also expose typed details:

```go
var notFound *metrics.ResourceNotFoundError
if errors.As(err, &notFound) {
	fmt.Printf("request %s failed with status %d\n", notFound.RequestID, notFound.StatusCode)
	if notFound.Details != nil && notFound.Details.Field != nil {
		fmt.Printf("missing field: %s\n", *notFound.Details.Field)
	}
}
```

Unknown server error codes remain available as `*core.APIError`, allowing
clients to handle error codes introduced by newer API versions.

## Packages

- `core` contains authentication, configuration, HTTP transport, interceptors,
  and base SDK errors.
- `metrics` contains the generated Metrics API client, models, request types,
  and typed service errors.

Generated files in `metrics` should not be edited manually.

## Development

Run the test suite and static analysis from this directory:

```shell
go test -race ./...
go vet ./...
```

## License

This SDK is licensed under the [MIT License](LICENSE).
