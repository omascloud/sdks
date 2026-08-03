// Copyright (c) 2026 Omas Cloud
//
// SPDX-License-Identifier: MIT

package metrics_test

import (
	"context"
	"errors"
	"os"

	"github.com/omascloud/sdks/go/core"
	"github.com/omascloud/sdks/go/metrics"
)

func ExampleClient() {
	authProvider, err := core.NewM2MAuthProvider(os.Getenv("OMAS_M2M_TOKEN"))
	if err != nil {
		return
	}
	defer authProvider.Close()

	client, err := metrics.NewClient(authProvider)
	if err != nil {
		return
	}
	defer client.Close()

	maxResults := int32(25)
	_, err = client.ListMetrics(context.Background(), metrics.ListMetricsOperationRequest{
		MaxResults: &maxResults,
	})
	var notFound *metrics.ResourceNotFoundError
	if errors.As(err, &notFound) && notFound.Details != nil {
		_ = notFound.StatusCode
		_ = notFound.Details.Field
	}
}
