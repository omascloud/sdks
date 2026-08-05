// Copyright (c) 2026 Omas Cloud
//
// SPDX-License-Identifier: MIT

package core

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"
)

type authProviderFunc func(context.Context, AuthContext) (Authentication, error)

func (f authProviderFunc) Resolve(ctx context.Context, authContext AuthContext) (Authentication, error) {
	return f(ctx, authContext)
}

func TestNewClientRejectsInvalidConfiguration(t *testing.T) {
	auth, _ := NewBearerAuthProvider("token")
	tests := []struct {
		name     string
		service  string
		endpoint string
		auth     AuthProvider
	}{
		{"blank service", " ", "https://example.test", auth},
		{"nil auth", "metrics", "https://example.test", nil},
		{"relative endpoint", "metrics", "/api", auth},
		{"non-HTTP endpoint", "metrics", "ftp://example.test", auth},
		{"endpoint query", "metrics", "https://example.test?x=1", auth},
		{"endpoint fragment", "metrics", "https://example.test/#fragment", auth},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if _, err := NewClient(test.service, test.endpoint, test.auth); err == nil {
				t.Fatal("expected construction error")
			}
		})
	}
}

func TestClientBuildsRequestAndRunsInterceptorBeforeAuthentication(t *testing.T) {
	var order []string
	var received *http.Request
	httpClient := &http.Client{Transport: roundTripperFunc(func(request *http.Request) (*http.Response, error) {
		order = append(order, "transport")
		received = request.Clone(request.Context())
		return &http.Response{
			StatusCode: http.StatusOK,
			Header:     http.Header{},
			Body:       io.NopCloser(strings.NewReader(`{"name":"cpu"}`)),
		}, nil
	})}
	auth := authProviderFunc(func(_ context.Context, authContext AuthContext) (Authentication, error) {
		order = append(order, "auth")
		if authContext.Service != "metrics" || authContext.OperationID != "GetMetric" {
			t.Fatalf("unexpected auth context: %+v", authContext)
		}
		return BearerAuthentication("access-token"), nil
	})
	interceptor := func(_ context.Context, metadata RequestMetadata, headers http.Header) error {
		order = append(order, "interceptor")
		if metadata.Service != "metrics" || metadata.OperationID != "GetMetric" ||
			metadata.Method != http.MethodPost {
			t.Fatalf("unexpected metadata: %+v", metadata)
		}
		headers.Set("X-Trace-Id", "trace-1")
		return nil
	}
	client, err := NewClient(
		"metrics",
		"https://example.test/base/",
		auth,
		WithHTTPClient(httpClient),
		WithInterceptor(interceptor),
	)
	if err != nil {
		t.Fatal(err)
	}

	query := url.Values{"dimension": {"host/a", "region"}}
	var result struct {
		Name string `json:"name"`
	}
	err = client.Do(
		context.Background(),
		"GetMetric",
		http.MethodPost,
		ReplacePathParameter("v1/metrics/{name}", "name", "cpu/load"),
		query,
		http.Header{"X-Caller": {"caller"}},
		struct {
			Value int `json:"value"`
		}{Value: 3},
		&result,
	)
	if err != nil {
		t.Fatal(err)
	}

	if strings.Join(order, ",") != "interceptor,auth,transport" {
		t.Fatalf("request order = %v", order)
	}
	if received.URL.String() != "https://example.test/base/v1/metrics/cpu%2Fload?dimension=host%2Fa&dimension=region" {
		t.Fatalf("request URL = %s", received.URL)
	}
	if received.Header.Get("Authorization") != "Bearer access-token" ||
		received.Header.Get("X-Trace-Id") != "trace-1" || received.Header.Get("X-Caller") != "caller" {
		t.Fatalf("request headers = %v", received.Header)
	}
	body, err := io.ReadAll(received.Body)
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != `{"value":3}` || result.Name != "cpu" {
		t.Fatalf("body = %s, result = %+v", body, result)
	}
}

func TestClientRejectsAuthorizationFromInterceptorBeforeAuthAndTransport(t *testing.T) {
	authCalled := false
	transportCalled := false
	client, err := NewClient(
		"metrics",
		"https://example.test",
		authProviderFunc(func(context.Context, AuthContext) (Authentication, error) {
			authCalled = true
			return BearerAuthentication("token"), nil
		}),
		WithHTTPClient(&http.Client{Transport: roundTripperFunc(func(*http.Request) (*http.Response, error) {
			transportCalled = true
			return nil, errors.New("must not execute")
		})}),
		WithInterceptor(func(_ context.Context, _ RequestMetadata, headers http.Header) error {
			headers.Set("authorization", "Bearer replacement")
			return nil
		}),
	)
	if err != nil {
		t.Fatal(err)
	}

	err = client.Do(context.Background(), "ListMetrics", http.MethodGet, "v1/metrics", nil, nil, nil, nil)
	if err == nil || !strings.Contains(err.Error(), "Authorization") {
		t.Fatalf("error = %v", err)
	}
	if authCalled || transportCalled {
		t.Fatalf("auth called = %v, transport called = %v", authCalled, transportCalled)
	}
}

func TestClientRequestTimeoutIncludesAuthentication(t *testing.T) {
	client, err := NewClient(
		"metrics",
		"https://example.test",
		authProviderFunc(func(ctx context.Context, _ AuthContext) (Authentication, error) {
			<-ctx.Done()
			return Authentication{}, ctx.Err()
		}),
		WithRequestTimeout(10*time.Millisecond),
		WithHTTPClient(&http.Client{}),
	)
	if err != nil {
		t.Fatal(err)
	}

	err = client.Do(context.Background(), "ListMetrics", http.MethodGet, "v1/metrics", nil, nil, nil, nil)
	var timeoutError *RequestTimeoutError
	if !errors.As(err, &timeoutError) || !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("error = %T %v", err, err)
	}
}

func TestClientWrapsSerializationTransportAndAPIErrors(t *testing.T) {
	auth, _ := NewBearerAuthProvider("token")

	t.Run("serialization", func(t *testing.T) {
		client, err := NewClient("metrics", "https://example.test", auth, WithHTTPClient(&http.Client{}))
		if err != nil {
			t.Fatal(err)
		}
		err = client.Do(context.Background(), "PutMetric", http.MethodPost, "v1/metrics", nil, nil, make(chan int), nil)
		var serializationError *SerializationError
		if !errors.As(err, &serializationError) {
			t.Fatalf("error = %T %v", err, err)
		}
	})

	t.Run("transport", func(t *testing.T) {
		cause := errors.New("network down")
		client, err := NewClient("metrics", "https://example.test", auth, WithHTTPClient(&http.Client{
			Transport: roundTripperFunc(func(*http.Request) (*http.Response, error) { return nil, cause }),
		}))
		if err != nil {
			t.Fatal(err)
		}
		err = client.Do(context.Background(), "ListMetrics", http.MethodGet, "v1/metrics", nil, nil, nil, nil)
		var transportError *TransportError
		if !errors.As(err, &transportError) || !errors.Is(err, cause) {
			t.Fatalf("error = %T %v", err, err)
		}
	})

	t.Run("API decoder", func(t *testing.T) {
		decoded := errors.New("typed service error")
		client, err := NewClient(
			"metrics",
			"https://example.test",
			auth,
			WithHTTPClient(&http.Client{Transport: roundTripperFunc(func(*http.Request) (*http.Response, error) {
				return &http.Response{
					StatusCode: http.StatusBadRequest,
					Header:     http.Header{},
					Body:       io.NopCloser(strings.NewReader(`{"error":"bad","errorCode":"BAD"}`)),
				}, nil
			})}),
			WithAPIErrorDecoder(func(apiError *APIError) error {
				if apiError.ErrorCode != "BAD" {
					t.Fatalf("unexpected base error: %+v", apiError)
				}
				return decoded
			}),
		)
		if err != nil {
			t.Fatal(err)
		}
		err = client.Do(context.Background(), "ListMetrics", http.MethodGet, "v1/metrics", nil, nil, nil, nil)
		if !errors.Is(err, decoded) {
			t.Fatalf("error = %T %v", err, err)
		}
	})
}

type blockingRoundTripper struct {
	entered chan struct{}
	release chan struct{}
	once    sync.Once
}

func (transport *blockingRoundTripper) RoundTrip(request *http.Request) (*http.Response, error) {
	transport.once.Do(func() { close(transport.entered) })
	select {
	case <-transport.release:
		return &http.Response{StatusCode: http.StatusNoContent, Header: http.Header{}, Body: http.NoBody}, nil
	case <-request.Context().Done():
		return nil, request.Context().Err()
	}
}

func TestBoundedRoundTripperTimesOutWaitingForSlot(t *testing.T) {
	transport := &blockingRoundTripper{entered: make(chan struct{}), release: make(chan struct{})}
	bounded := newBoundedRoundTripper(transport, 1, 10*time.Millisecond)
	firstRequest, _ := http.NewRequestWithContext(context.Background(), http.MethodGet, "https://example.test", nil)
	firstDone := make(chan error, 1)
	go func() {
		response, err := bounded.RoundTrip(firstRequest)
		if response != nil {
			_ = response.Body.Close()
		}
		firstDone <- err
	}()
	<-transport.entered

	secondRequest, _ := http.NewRequestWithContext(context.Background(), http.MethodGet, "https://example.test", nil)
	_, err := bounded.RoundTrip(secondRequest)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("second request error = %v", err)
	}
	close(transport.release)
	if err := <-firstDone; err != nil {
		t.Fatal(err)
	}
}

type closeRecordingTransport struct{ closes int }

func (*closeRecordingTransport) RoundTrip(*http.Request) (*http.Response, error) {
	return &http.Response{StatusCode: http.StatusNoContent, Header: http.Header{}, Body: http.NoBody}, nil
}
func (transport *closeRecordingTransport) CloseIdleConnections() { transport.closes++ }

func TestClientDoesNotCloseInjectedHTTPClient(t *testing.T) {
	transport := &closeRecordingTransport{}
	auth, _ := NewBearerAuthProvider("token")
	client, err := NewClient("metrics", "https://example.test", auth, WithHTTPClient(&http.Client{Transport: transport}))
	if err != nil {
		t.Fatal(err)
	}
	if err := client.Close(); err != nil {
		t.Fatal(err)
	}
	if transport.closes != 0 {
		t.Fatalf("injected transport closed %d times", transport.closes)
	}
}
