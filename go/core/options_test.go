// Copyright (c) 2026 Omas Cloud
//
// SPDX-License-Identifier: MIT

package core

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"testing"
	"time"
)

func TestApplyClientOptionsUsesJavaParityDefaults(t *testing.T) {
	options, err := applyClientOptions()
	if err != nil {
		t.Fatal(err)
	}

	if options.connectTimeout != 3*time.Second {
		t.Fatalf("connect timeout = %s", options.connectTimeout)
	}
	if options.requestTimeout != 30*time.Second {
		t.Fatalf("request timeout = %s", options.requestTimeout)
	}
	if options.readTimeout != 10*time.Second {
		t.Fatalf("read timeout = %s", options.readTimeout)
	}
	if options.connectionAcquireTimeout != 2*time.Second {
		t.Fatalf("connection acquire timeout = %s", options.connectionAcquireTimeout)
	}
	if options.maxConnections != 50 {
		t.Fatalf("max connections = %d", options.maxConnections)
	}
	base := &APIError{Message: "base"}
	if got := options.apiErrorDecoder(base); got != base {
		t.Fatalf("default decoder returned %T, want original error", got)
	}
}

func TestApplyClientOptionsAppliesFunctionalOptions(t *testing.T) {
	proxy, err := url.Parse("https://proxy.example:8443")
	if err != nil {
		t.Fatal(err)
	}
	httpClient := &http.Client{}
	interceptor := func(context.Context, RequestMetadata, http.Header) error { return nil }
	decoder := func(apiError *APIError) error { return &TransportError{OperationID: "decode", Err: apiError} }

	options, err := applyClientOptions(
		WithConnectTimeout(time.Second),
		WithRequestTimeout(2*time.Second),
		WithReadTimeout(3*time.Second),
		WithConnectionAcquireTimeout(4*time.Second),
		WithMaxConnections(7),
		WithProxy(proxy),
		WithInterceptor(interceptor),
		WithHTTPClient(httpClient),
		WithAPIErrorDecoder(decoder),
	)
	if err != nil {
		t.Fatal(err)
	}

	if options.connectTimeout != time.Second || options.requestTimeout != 2*time.Second ||
		options.readTimeout != 3*time.Second || options.connectionAcquireTimeout != 4*time.Second {
		t.Fatalf("unexpected timeouts: %+v", options)
	}
	if options.maxConnections != 7 || options.proxy.String() != proxy.String() || options.httpClient != httpClient {
		t.Fatalf("unexpected options: %+v", options)
	}
	if len(options.interceptors) != 1 {
		t.Fatalf("interceptor count = %d", len(options.interceptors))
	}
	var transportError *TransportError
	if !errors.As(options.apiErrorDecoder(&APIError{Message: "base"}), &transportError) {
		t.Fatal("custom API error decoder was not applied")
	}
}

func TestApplyClientOptionsRejectsInvalidValues(t *testing.T) {
	validProxy, _ := url.Parse("https://proxy.example")
	relativeProxy, _ := url.Parse("/proxy")
	tests := []struct {
		name   string
		option ClientOption
	}{
		{"connect timeout", WithConnectTimeout(0)},
		{"request timeout", WithRequestTimeout(-time.Second)},
		{"read timeout", WithReadTimeout(0)},
		{"connection acquire timeout", WithConnectionAcquireTimeout(0)},
		{"max connections", WithMaxConnections(0)},
		{"nil proxy", WithProxy(nil)},
		{"relative proxy", WithProxy(relativeProxy)},
		{"nil interceptor", WithInterceptor(nil)},
		{"nil HTTP client", WithHTTPClient(nil)},
		{"nil decoder", WithAPIErrorDecoder(nil)},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			_, err := applyClientOptions(test.option)
			if err == nil {
				t.Fatal("expected validation error")
			}
		})
	}

	if _, err := applyClientOptions(WithProxy(validProxy)); err != nil {
		t.Fatalf("valid proxy rejected: %v", err)
	}
}

func TestApplyClientOptionsCopiesInterceptorStorage(t *testing.T) {
	called := 0
	options, err := applyClientOptions(WithInterceptor(func(context.Context, RequestMetadata, http.Header) error {
		called++
		return nil
	}))
	if err != nil {
		t.Fatal(err)
	}
	copyOfInterceptors := append([]RequestInterceptor(nil), options.interceptors...)
	options.interceptors[0] = func(context.Context, RequestMetadata, http.Header) error { return nil }
	if err := copyOfInterceptors[0](context.Background(), RequestMetadata{}, http.Header{}); err != nil {
		t.Fatal(err)
	}
	if called != 1 {
		t.Fatalf("copied interceptor called %d times", called)
	}
}
