// Copyright (c) 2026 Omas Cloud
//
// SPDX-License-Identifier: MIT

package core

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestDecodeAPIErrorPreservesProtocolDetails(t *testing.T) {
	response := &http.Response{
		StatusCode: http.StatusNotFound,
		Header: http.Header{
			"X-Request-Id": {"request-123"},
			"Retry-After":  {"17"},
		},
		Body: io.NopCloser(strings.NewReader("unused")),
	}
	body := []byte(`{"error":"missing metric","errorCode":"RESOURCE_NOT_FOUND","extraData":{"resourceType":"metric"}}`)

	apiError := decodeAPIError(response, body)

	if apiError.StatusCode != http.StatusNotFound || apiError.ErrorCode != "RESOURCE_NOT_FOUND" ||
		apiError.Message != "missing metric" || apiError.RequestID != "request-123" ||
		apiError.RetryAfter != 17*time.Second {
		t.Fatalf("unexpected API error: %+v", apiError)
	}
	if string(apiError.ExtraData) != `{"resourceType":"metric"}` || string(apiError.RawBody) != string(body) {
		t.Fatalf("response data not preserved: %+v", apiError)
	}
	response.Header.Set("X-Request-Id", "mutated")
	body[0] = 'x'
	if apiError.Headers.Get("X-Request-Id") != "request-123" || apiError.RawBody[0] != '{' {
		t.Fatal("API error retained mutable response storage")
	}
	if apiError.Error() != "RESOURCE_NOT_FOUND: missing metric" {
		t.Fatalf("error string = %q", apiError.Error())
	}
}

func TestDecodeAPIErrorFallsBackForMalformedBody(t *testing.T) {
	response := &http.Response{StatusCode: http.StatusBadGateway, Header: http.Header{}}
	apiError := decodeAPIError(response, []byte("not-json"))
	if apiError.Message != "API request failed with HTTP 502" || apiError.ErrorCode != "" {
		t.Fatalf("unexpected fallback: %+v", apiError)
	}
}

func TestRuntimeErrorsUnwrapCauses(t *testing.T) {
	cause := context.DeadlineExceeded
	errorsToCheck := []error{
		&AuthenticationError{OperationID: "ListMetrics", Err: cause},
		&RequestTimeoutError{OperationID: "ListMetrics", Err: cause},
		&SerializationError{OperationID: "ListMetrics", Err: cause},
		&TransportError{OperationID: "ListMetrics", Err: cause},
	}
	for _, sdkError := range errorsToCheck {
		if !errors.Is(sdkError, cause) {
			t.Fatalf("%T does not unwrap its cause", sdkError)
		}
		if sdkError.Error() == "" {
			t.Fatalf("%T has an empty message", sdkError)
		}
	}
}
