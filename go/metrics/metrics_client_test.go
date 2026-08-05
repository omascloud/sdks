// Copyright (c) 2026 Omas Cloud
//
// SPDX-License-Identifier: MIT

package metrics

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/omascloud/sdks/go/core"
)

type testAuthProvider struct {
	context core.AuthContext
	calls   atomic.Int32
}

func (p *testAuthProvider) Resolve(_ context.Context, authContext core.AuthContext) (core.Authentication, error) {
	p.calls.Add(1)
	p.context = authContext
	return core.BearerAuthentication("access-token"), nil
}

type roundTripperFunc func(*http.Request) (*http.Response, error)

func (f roundTripperFunc) RoundTrip(request *http.Request) (*http.Response, error) {
	return f(request)
}

func newTestClient(
	t *testing.T,
	authProvider *testAuthProvider,
	roundTrip roundTripperFunc,
) *Client {
	t.Helper()
	client, err := NewClient(authProvider, core.WithHTTPClient(&http.Client{Transport: roundTrip}))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := client.Close(); err != nil {
			t.Errorf("close client: %v", err)
		}
	})
	return client
}

func testResponse(status int, body string, headers http.Header) *http.Response {
	if headers == nil {
		headers = http.Header{}
	}
	return &http.Response{
		StatusCode: status,
		Body:       io.NopCloser(strings.NewReader(body)),
		Header:     headers,
	}
}

func pointer[T any](value T) *T {
	return &value
}

func validCreateAlarmRequest() CreateAlarmOperationRequest {
	return CreateAlarmOperationRequest{
		AlarmName: "cpu_high",
		Variables: []AlarmVariable{{
			Name:        "A",
			MetricName:  "cpu.usage",
			Aggregation: "avg",
		}},
		Expression:            "A > 90",
		EvaluationPeriodInMin: 5,
		DatapointsToAlarm:     3,
		Resolution:            60,
		TreatMissingDataAs:    TREATMISSINGDATAAS_MISSING,
	}
}

func TestMetricsClientBuildsFlatRequestAndPassesMetricsAudience(t *testing.T) {
	authProvider := &testAuthProvider{}
	client := newTestClient(t, authProvider, func(request *http.Request) (*http.Response, error) {
		if request.Method != http.MethodPost {
			t.Fatalf("unexpected method: %s", request.Method)
		}
		if request.URL.String() != "https://api.omas.cloud/v1/alarms/cpu_high" {
			t.Fatalf("unexpected URL: %s", request.URL)
		}
		if request.Header.Get("Authorization") != "Bearer access-token" {
			t.Fatalf("unexpected authorization header: %s", request.Header.Get("Authorization"))
		}
		var body map[string]any
		if err := json.NewDecoder(request.Body).Decode(&body); err != nil {
			t.Fatalf("decode request body: %v", err)
		}
		for _, field := range []string{
			"variables", "expression", "evaluationPeriodInMin", "datapointsToAlarm",
			"resolution", "treatMissingDataAs",
		} {
			if _, found := body[field]; !found {
				t.Fatalf("missing top-level field %q in %#v", field, body)
			}
		}
		if _, found := body["alarmName"]; found {
			t.Fatalf("path parameter leaked into JSON: %#v", body)
		}
		if _, found := body["body"]; found {
			t.Fatalf("request body was not flattened: %#v", body)
		}
		return testResponse(http.StatusOK, `{}`, nil), nil
	})

	if _, err := client.CreateAlarm(context.Background(), validCreateAlarmRequest()); err != nil {
		t.Fatal(err)
	}
	if authProvider.context.Service != "metrics" {
		t.Fatalf("unexpected audience: %s", authProvider.context.Service)
	}
	if authProvider.context.OperationID != "createAlarm" {
		t.Fatalf("unexpected operation: %s", authProvider.context.OperationID)
	}
}

func TestMetricsClientEncodesRepeatedQueryParameters(t *testing.T) {
	client := newTestClient(t, &testAuthProvider{}, func(request *http.Request) (*http.Response, error) {
		query := request.URL.Query()
		if values := query["includeDimensions"]; len(values) != 2 || values[0] != "host:a" || values[1] != "host:b" {
			t.Fatalf("unexpected include dimensions: %#v", values)
		}
		if values := query["excludeDimensions"]; len(values) != 2 || values[0] != "zone:x" || values[1] != "zone:y" {
			t.Fatalf("unexpected exclude dimensions: %#v", values)
		}
		for _, omitted := range []string{"resolution", "aggregation", "endTimestamp", "maxResults", "nextToken"} {
			if query.Has(omitted) {
				t.Fatalf("optional query parameter %q was not omitted: %s", omitted, request.URL.RawQuery)
			}
		}
		return testResponse(http.StatusOK, `{}`, nil), nil
	})

	_, err := client.GetMetricData(context.Background(), GetMetricDataOperationRequest{
		MetricName:        "cpu.usage",
		StartTimestamp:    1000,
		IncludeDimensions: []string{"host:a", "host:b"},
		ExcludeDimensions: []string{"zone:x", "zone:y"},
	})
	if err != nil {
		t.Fatal(err)
	}
}

func TestMetricsClientDecodesResponsesAndErrors(t *testing.T) {
	t.Run("empty success", func(t *testing.T) {
		client := newTestClient(t, &testAuthProvider{}, func(*http.Request) (*http.Response, error) {
			return testResponse(http.StatusNoContent, "", nil), nil
		})
		if err := client.DeleteMetric(context.Background(), DeleteMetricOperationRequest{MetricName: "cpu.usage"}); err != nil {
			t.Fatal(err)
		}
	})

	t.Run("decoded success", func(t *testing.T) {
		client := newTestClient(t, &testAuthProvider{}, func(*http.Request) (*http.Response, error) {
			return testResponse(http.StatusOK, `{"metrics":[{"name":"cpu.usage"}],"totalCount":1,"dimensionFacets":[]}`, nil), nil
		})
		response, err := client.ListMetrics(context.Background(), ListMetricsOperationRequest{})
		if err != nil {
			t.Fatal(err)
		}
		if len(response.Metrics) != 1 || response.Metrics[0].Name != "cpu.usage" {
			t.Fatalf("unexpected response: %#v", response)
		}
	})

	t.Run("malformed success", func(t *testing.T) {
		client := newTestClient(t, &testAuthProvider{}, func(*http.Request) (*http.Response, error) {
			return testResponse(http.StatusOK, `{`, nil), nil
		})
		_, err := client.ListMetrics(context.Background(), ListMetricsOperationRequest{})
		var serializationError *core.SerializationError
		if !errors.As(err, &serializationError) {
			t.Fatalf("expected SerializationError, got %T: %v", err, err)
		}
	})

	t.Run("known error with details", func(t *testing.T) {
		headers := http.Header{}
		headers.Set("X-Request-ID", "request-123")
		headers.Set("Retry-After", "7")
		client := newTestClient(t, &testAuthProvider{}, func(*http.Request) (*http.Response, error) {
			return testResponse(http.StatusNotFound,
				`{"error":"missing","errorCode":"RESOURCE_NOT_FOUND","extraData":{"field":"metric"}}`,
				headers), nil
		})
		_, err := client.ListMetrics(context.Background(), ListMetricsOperationRequest{})
		var notFound *ResourceNotFoundError
		if !errors.As(err, &notFound) {
			t.Fatalf("expected ResourceNotFoundError, got %T: %v", err, err)
		}
		if notFound.Details == nil || notFound.Details.Field == nil || *notFound.Details.Field != "metric" {
			t.Fatalf("unexpected typed details: %#v", notFound.Details)
		}
		if notFound.StatusCode != http.StatusNotFound || notFound.RequestID != "request-123" || notFound.RetryAfter != 7*time.Second {
			t.Fatalf("unexpected base error: %#v", notFound.APIError)
		}
		var apiError *core.APIError
		if !errors.As(err, &apiError) {
			t.Fatal("typed error does not expose core.APIError")
		}
	})

	t.Run("known error without details", func(t *testing.T) {
		client := newTestClient(t, &testAuthProvider{}, func(*http.Request) (*http.Response, error) {
			return testResponse(http.StatusForbidden,
				`{"error":"denied","errorCode":"ACCESS_DENIED"}`, nil), nil
		})
		_, err := client.ListMetrics(context.Background(), ListMetricsOperationRequest{})
		var accessDenied *AccessDeniedError
		if !errors.As(err, &accessDenied) {
			t.Fatalf("expected AccessDeniedError, got %T: %v", err, err)
		}
	})

	t.Run("unknown error", func(t *testing.T) {
		client := newTestClient(t, &testAuthProvider{}, func(*http.Request) (*http.Response, error) {
			return testResponse(http.StatusTeapot,
				`{"error":"new failure","errorCode":"FUTURE_ERROR"}`, nil), nil
		})
		_, err := client.ListMetrics(context.Background(), ListMetricsOperationRequest{})
		var apiError *core.APIError
		if !errors.As(err, &apiError) || apiError.ErrorCode != "FUTURE_ERROR" {
			t.Fatalf("expected fallback APIError, got %T: %v", err, err)
		}
	})

	t.Run("malformed error", func(t *testing.T) {
		client := newTestClient(t, &testAuthProvider{}, func(*http.Request) (*http.Response, error) {
			return testResponse(http.StatusBadGateway, `<html>bad gateway</html>`, nil), nil
		})
		_, err := client.ListMetrics(context.Background(), ListMetricsOperationRequest{})
		var apiError *core.APIError
		if !errors.As(err, &apiError) {
			t.Fatalf("expected APIError, got %T: %v", err, err)
		}
		if apiError.Message != "API request failed with HTTP 502" || string(apiError.RawBody) != "<html>bad gateway</html>" {
			t.Fatalf("unexpected fallback error: %#v", apiError)
		}
	})
}

func TestMetricsClientValidatesBeforeAuthenticationOrNetwork(t *testing.T) {
	authProvider := &testAuthProvider{}
	var transportCalls atomic.Int32
	client := newTestClient(t, authProvider, func(*http.Request) (*http.Response, error) {
		transportCalls.Add(1)
		return testResponse(http.StatusOK, `{}`, nil), nil
	})

	tests := []struct {
		name      string
		field     string
		operation func() error
	}{
		{
			name:  "invalid pattern",
			field: "alarmName",
			operation: func() error {
				request := validCreateAlarmRequest()
				request.AlarmName = "not valid"
				_, err := client.CreateAlarm(context.Background(), request)
				return err
			},
		},
		{
			name:  "missing required collection",
			field: "variables",
			operation: func() error {
				request := validCreateAlarmRequest()
				request.Variables = nil
				_, err := client.CreateAlarm(context.Background(), request)
				return err
			},
		},
		{
			name:  "out of range",
			field: "evaluationPeriodInMin",
			operation: func() error {
				request := validCreateAlarmRequest()
				request.EvaluationPeriodInMin = 1441
				_, err := client.CreateAlarm(context.Background(), request)
				return err
			},
		},
		{
			name:  "oversized collection",
			field: "notificationChannels",
			operation: func() error {
				request := validCreateAlarmRequest()
				request.NotificationChannels = make([]string, 11)
				_, err := client.CreateAlarm(context.Background(), request)
				return err
			},
		},
		{
			name:  "duplicate unique values",
			field: "statuses",
			operation: func() error {
				_, err := client.ListAlarms(context.Background(), ListAlarmsOperationRequest{
					Statuses: []AlarmStatus{ALARMSTATUS_OK, ALARMSTATUS_OK},
				})
				return err
			},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			err := test.operation()
			if err == nil || !strings.Contains(err.Error(), test.field) {
				t.Fatalf("expected %s validation error, got %v", test.field, err)
			}
		})
	}
	if authProvider.calls.Load() != 0 || transportCalls.Load() != 0 {
		t.Fatalf("validation reached auth or network: auth=%d transport=%d",
			authProvider.calls.Load(), transportCalls.Load())
	}
}
