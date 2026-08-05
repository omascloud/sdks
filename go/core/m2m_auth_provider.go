// Copyright (c) 2026 Omas Cloud
//
// SPDX-License-Identifier: MIT

package core

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

const tokenExchangeEndpoint = "https://api.omas.cloud/v1/auth/token-exchange"

type M2MOption func(*m2mOptions) error

type m2mOptions struct {
	refreshSkew time.Duration
	httpClient  *http.Client
	endpoint    string
	clock       func() time.Time
}

func defaultM2MOptions() m2mOptions {
	return m2mOptions{
		refreshSkew: 30 * time.Second,
		endpoint:    tokenExchangeEndpoint,
		clock:       time.Now,
	}
}

func WithM2MRefreshSkew(refreshSkew time.Duration) M2MOption {
	return func(options *m2mOptions) error {
		if refreshSkew < 0 {
			return fmt.Errorf("refresh skew must not be negative")
		}
		options.refreshSkew = refreshSkew
		return nil
	}
}

func WithM2MHTTPClient(httpClient *http.Client) M2MOption {
	return func(options *m2mOptions) error {
		if httpClient == nil {
			return fmt.Errorf("HTTP client must not be nil")
		}
		options.httpClient = httpClient
		return nil
	}
}

func withM2MTokenEndpoint(endpoint string) M2MOption {
	return func(options *m2mOptions) error {
		parsed, err := url.Parse(endpoint)
		if err != nil || !parsed.IsAbs() || parsed.Host == "" ||
			(parsed.Scheme != "http" && parsed.Scheme != "https") {
			return fmt.Errorf("token endpoint must be an absolute HTTP(S) URL")
		}
		options.endpoint = endpoint
		return nil
	}
}

func withM2MClock(clock func() time.Time) M2MOption {
	return func(options *m2mOptions) error {
		if clock == nil {
			return fmt.Errorf("clock must not be nil")
		}
		options.clock = clock
		return nil
	}
}

type M2MAuthProvider struct {
	credential     string
	refreshSkew    time.Duration
	httpClient     *http.Client
	ownedTransport *http.Transport
	endpoint       string
	clock          func() time.Time
	slotsMutex     sync.Mutex
	slots          map[string]*tokenSlot
}

type cachedToken struct {
	value     string
	refreshAt time.Time
	expiresAt time.Time
}

type tokenSlot struct {
	mutex   sync.Mutex
	token   cachedToken
	refresh *tokenRefresh
}

type tokenRefresh struct {
	done  chan struct{}
	token cachedToken
	err   error
}

func NewM2MAuthProvider(credential string, optionFunctions ...M2MOption) (*M2MAuthProvider, error) {
	credential = strings.TrimSpace(credential)
	if credential == "" {
		return nil, fmt.Errorf("credential must not be blank")
	}
	options := defaultM2MOptions()
	for _, option := range optionFunctions {
		if option == nil {
			return nil, fmt.Errorf("M2M option must not be nil")
		}
		if err := option(&options); err != nil {
			return nil, err
		}
	}

	httpClient := options.httpClient
	var ownedTransport *http.Transport
	if httpClient == nil {
		ownedTransport = &http.Transport{
			Proxy:                 http.ProxyFromEnvironment,
			DialContext:           (&net.Dialer{Timeout: 3 * time.Second}).DialContext,
			ResponseHeaderTimeout: 10 * time.Second,
			MaxConnsPerHost:       50,
			MaxIdleConnsPerHost:   50,
		}
		httpClient = &http.Client{Timeout: 30 * time.Second, Transport: ownedTransport}
	}

	return &M2MAuthProvider{
		credential: credential, refreshSkew: options.refreshSkew, httpClient: httpClient,
		ownedTransport: ownedTransport, endpoint: options.endpoint, clock: options.clock,
		slots: make(map[string]*tokenSlot),
	}, nil
}

func (provider *M2MAuthProvider) Resolve(ctx context.Context, authContext AuthContext) (Authentication, error) {
	if ctx == nil {
		return Authentication{}, fmt.Errorf("context must not be nil")
	}
	audience := strings.TrimSpace(authContext.Service)
	if audience == "" {
		return Authentication{}, fmt.Errorf("auth context service must not be blank")
	}
	slot := provider.slot(audience)
	now := provider.clock()

	slot.mutex.Lock()
	if slot.token.value != "" && now.Before(slot.token.refreshAt) {
		token := slot.token
		slot.mutex.Unlock()
		return BearerAuthentication(token.value), nil
	}
	if refresh := slot.refresh; refresh != nil {
		slot.mutex.Unlock()
		return provider.waitForRefresh(ctx, authContext.OperationID, slot, refresh)
	}
	refresh := &tokenRefresh{done: make(chan struct{})}
	slot.refresh = refresh
	slot.mutex.Unlock()

	token, err := provider.exchange(ctx, audience)
	slot.mutex.Lock()
	refresh.token = token
	refresh.err = err
	if err == nil {
		slot.token = token
	}
	slot.refresh = nil
	close(refresh.done)
	current := slot.token
	slot.mutex.Unlock()
	return provider.authenticationFromRefresh(authContext.OperationID, current, refresh)
}

func (provider *M2MAuthProvider) slot(audience string) *tokenSlot {
	provider.slotsMutex.Lock()
	defer provider.slotsMutex.Unlock()
	if provider.slots[audience] == nil {
		provider.slots[audience] = &tokenSlot{}
	}
	return provider.slots[audience]
}

func (provider *M2MAuthProvider) waitForRefresh(
	ctx context.Context,
	operationID string,
	slot *tokenSlot,
	refresh *tokenRefresh,
) (Authentication, error) {
	select {
	case <-refresh.done:
		slot.mutex.Lock()
		current := slot.token
		slot.mutex.Unlock()
		return provider.authenticationFromRefresh(operationID, current, refresh)
	case <-ctx.Done():
		return Authentication{}, &AuthenticationError{OperationID: operationID, Err: ctx.Err()}
	}
}

func (provider *M2MAuthProvider) authenticationFromRefresh(
	operationID string,
	current cachedToken,
	refresh *tokenRefresh,
) (Authentication, error) {
	if refresh.err == nil {
		return BearerAuthentication(refresh.token.value), nil
	}
	if current.value != "" && provider.clock().Before(current.expiresAt) {
		return BearerAuthentication(current.value), nil
	}
	return Authentication{}, &AuthenticationError{OperationID: operationID, Err: refresh.err}
}

func (provider *M2MAuthProvider) exchange(ctx context.Context, audience string) (cachedToken, error) {
	body, err := json.Marshal(struct {
		Audience string `json:"audience"`
	}{Audience: audience})
	if err != nil {
		return cachedToken{}, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, provider.endpoint, bytes.NewReader(body))
	if err != nil {
		return cachedToken{}, err
	}
	request.Header.Set("Authorization", "Bearer "+provider.credential)
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "application/json")
	response, err := provider.httpClient.Do(request)
	if err != nil {
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			return cachedToken{}, err
		}
		return cachedToken{}, fmt.Errorf("exchange access token: %w", err)
	}
	defer response.Body.Close()
	responseBody, err := io.ReadAll(response.Body)
	if err != nil {
		return cachedToken{}, fmt.Errorf("read token exchange response: %w", err)
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return cachedToken{}, decodeAPIError(response, responseBody)
	}
	payload := struct {
		AccessToken string `json:"accessToken"`
		TokenType   string `json:"tokenType"`
		ExpiresIn   int    `json:"expiresIn"`
	}{}
	if err := json.Unmarshal(responseBody, &payload); err != nil {
		return cachedToken{}, fmt.Errorf("decode token exchange response: %w", err)
	}
	if strings.TrimSpace(payload.AccessToken) == "" || payload.TokenType != "Bearer" || payload.ExpiresIn <= 0 {
		return cachedToken{}, fmt.Errorf("invalid token exchange response")
	}
	now := provider.clock()
	lifetime := time.Duration(payload.ExpiresIn) * time.Second
	skew := min(provider.refreshSkew, lifetime/2)
	return cachedToken{
		value: payload.AccessToken, refreshAt: now.Add(lifetime - skew), expiresAt: now.Add(lifetime),
	}, nil
}

func (provider *M2MAuthProvider) Close() error {
	if provider.ownedTransport != nil {
		provider.ownedTransport.CloseIdleConnections()
	}
	return nil
}
