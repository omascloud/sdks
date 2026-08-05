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
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type roundTripperFunc func(*http.Request) (*http.Response, error)

func (f roundTripperFunc) RoundTrip(request *http.Request) (*http.Response, error) {
	return f(request)
}

type mutableClock struct {
	mu  sync.Mutex
	now time.Time
}

func (clock *mutableClock) Now() time.Time {
	clock.mu.Lock()
	defer clock.mu.Unlock()
	return clock.now
}

func (clock *mutableClock) Advance(duration time.Duration) {
	clock.mu.Lock()
	defer clock.mu.Unlock()
	clock.now = clock.now.Add(duration)
}

func tokenResponse(token string, expiresIn int) *http.Response {
	return &http.Response{
		StatusCode: http.StatusOK,
		Header:     http.Header{},
		Body: io.NopCloser(strings.NewReader(
			`{"accessToken":"` + token + `","tokenType":"Bearer","expiresIn":` + formatValue(expiresIn) + `}`)),
	}
}

func TestNewM2MAuthProviderValidatesOptions(t *testing.T) {
	if _, err := NewM2MAuthProvider(" "); err == nil {
		t.Fatal("blank credential accepted")
	}
	if _, err := NewM2MAuthProvider("credential", WithM2MRefreshSkew(-time.Second)); err == nil {
		t.Fatal("negative refresh skew accepted")
	}
	if _, err := NewM2MAuthProvider("credential", WithM2MHTTPClient(nil)); err == nil {
		t.Fatal("nil HTTP client accepted")
	}
}

func TestM2MAuthProviderExchangesAndCachesTokenByService(t *testing.T) {
	clock := &mutableClock{now: time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)}
	var calls atomic.Int32
	httpClient := &http.Client{Transport: roundTripperFunc(func(request *http.Request) (*http.Response, error) {
		calls.Add(1)
		body, err := io.ReadAll(request.Body)
		if err != nil {
			t.Fatal(err)
		}
		if request.Method != http.MethodPost || request.URL.String() != tokenExchangeEndpoint ||
			string(body) != `{"audience":"metrics"}` {
			t.Fatalf("unexpected exchange request: %s %s %s", request.Method, request.URL, body)
		}
		if request.Header.Get("Authorization") != "Bearer credential" ||
			request.Header.Get("Content-Type") != "application/json" {
			t.Fatalf("unexpected exchange headers: %v", request.Header)
		}
		return tokenResponse("access-token", 300), nil
	})}

	provider, err := NewM2MAuthProvider(
		" credential ",
		WithM2MHTTPClient(httpClient),
		withM2MClock(clock.Now),
	)
	if err != nil {
		t.Fatal(err)
	}
	for range 2 {
		authentication, resolveErr := provider.Resolve(context.Background(), AuthContext{Service: "metrics"})
		if resolveErr != nil {
			t.Fatal(resolveErr)
		}
		if authentication.Headers.Get("Authorization") != "Bearer access-token" {
			t.Fatalf("authorization = %s", authentication.Headers.Get("Authorization"))
		}
	}
	if calls.Load() != 1 {
		t.Fatalf("exchange calls = %d", calls.Load())
	}
	if _, err := provider.Resolve(context.Background(), AuthContext{Service: " "}); err == nil {
		t.Fatal("blank service accepted")
	}
}

func TestM2MAuthProviderCoalescesConcurrentRefresh(t *testing.T) {
	var calls atomic.Int32
	started := make(chan struct{})
	release := make(chan struct{})
	httpClient := &http.Client{Transport: roundTripperFunc(func(*http.Request) (*http.Response, error) {
		if calls.Add(1) == 1 {
			close(started)
		}
		<-release
		return tokenResponse("shared-token", 300), nil
	})}
	provider, err := NewM2MAuthProvider("credential", WithM2MHTTPClient(httpClient))
	if err != nil {
		t.Fatal(err)
	}

	const callers = 20
	errorsByCaller := make(chan error, callers)
	for range callers {
		go func() {
			_, resolveErr := provider.Resolve(context.Background(), AuthContext{Service: "metrics"})
			errorsByCaller <- resolveErr
		}()
	}
	<-started
	close(release)
	for range callers {
		if err := <-errorsByCaller; err != nil {
			t.Fatal(err)
		}
	}
	if calls.Load() != 1 {
		t.Fatalf("exchange calls = %d", calls.Load())
	}
}

func TestM2MAuthProviderRefreshWaiterCanCancel(t *testing.T) {
	started := make(chan struct{})
	release := make(chan struct{})
	httpClient := &http.Client{Transport: roundTripperFunc(func(*http.Request) (*http.Response, error) {
		close(started)
		<-release
		return tokenResponse("shared-token", 300), nil
	})}
	provider, err := NewM2MAuthProvider("credential", WithM2MHTTPClient(httpClient))
	if err != nil {
		t.Fatal(err)
	}
	leaderDone := make(chan error, 1)
	go func() {
		_, resolveErr := provider.Resolve(context.Background(), AuthContext{Service: "metrics"})
		leaderDone <- resolveErr
	}()
	<-started

	waiterContext, cancel := context.WithTimeout(context.Background(), 10*time.Millisecond)
	defer cancel()
	_, err = provider.Resolve(waiterContext, AuthContext{Service: "metrics"})
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("waiter error = %v", err)
	}
	close(release)
	if err := <-leaderDone; err != nil {
		t.Fatal(err)
	}
}

func TestM2MAuthProviderUsesValidTokenAfterEarlyRefreshFailure(t *testing.T) {
	clock := &mutableClock{now: time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)}
	var calls atomic.Int32
	httpClient := &http.Client{Transport: roundTripperFunc(func(*http.Request) (*http.Response, error) {
		if calls.Add(1) == 1 {
			return tokenResponse("initial-token", 100), nil
		}
		return nil, errors.New("exchange unavailable")
	})}
	provider, err := NewM2MAuthProvider(
		"credential",
		WithM2MHTTPClient(httpClient),
		WithM2MRefreshSkew(30*time.Second),
		withM2MClock(clock.Now),
	)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := provider.Resolve(context.Background(), AuthContext{Service: "metrics"}); err != nil {
		t.Fatal(err)
	}
	clock.Advance(75 * time.Second)
	authentication, err := provider.Resolve(context.Background(), AuthContext{Service: "metrics"})
	if err != nil {
		t.Fatal(err)
	}
	if authentication.Headers.Get("Authorization") != "Bearer initial-token" {
		t.Fatalf("authorization = %s", authentication.Headers.Get("Authorization"))
	}

	clock.Advance(26 * time.Second)
	_, err = provider.Resolve(context.Background(), AuthContext{Service: "metrics"})
	var authenticationError *AuthenticationError
	if !errors.As(err, &authenticationError) {
		t.Fatalf("expired token error = %T %v", err, err)
	}
}

func TestM2MAuthProviderRejectsInvalidExchangeResponseWithoutLeakingCredential(t *testing.T) {
	httpClient := &http.Client{Transport: roundTripperFunc(func(*http.Request) (*http.Response, error) {
		return &http.Response{
			StatusCode: http.StatusOK,
			Header:     http.Header{},
			Body:       io.NopCloser(strings.NewReader(`{"accessToken":"credential-secret","tokenType":"Basic","expiresIn":0}`)),
		}, nil
	})}
	provider, err := NewM2MAuthProvider("credential-secret", WithM2MHTTPClient(httpClient))
	if err != nil {
		t.Fatal(err)
	}
	_, err = provider.Resolve(context.Background(), AuthContext{Service: "metrics"})
	if err == nil || strings.Contains(err.Error(), "credential-secret") {
		t.Fatalf("unsafe error = %v", err)
	}
}

type closeAwareTransport struct{ closed atomic.Int32 }

func (*closeAwareTransport) RoundTrip(*http.Request) (*http.Response, error) {
	return tokenResponse("token", 300), nil
}
func (transport *closeAwareTransport) CloseIdleConnections() { transport.closed.Add(1) }

func TestM2MAuthProviderDoesNotCloseInjectedClient(t *testing.T) {
	transport := &closeAwareTransport{}
	provider, err := NewM2MAuthProvider(
		"credential",
		WithM2MHTTPClient(&http.Client{Transport: transport}),
	)
	if err != nil {
		t.Fatal(err)
	}
	if err := provider.Close(); err != nil {
		t.Fatal(err)
	}
	if transport.closed.Load() != 0 {
		t.Fatalf("injected transport closed %d times", transport.closed.Load())
	}
}
