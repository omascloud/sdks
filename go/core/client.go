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
	"reflect"
	"strings"
	"sync"
	"time"
)

type Client struct {
	service        string
	endpoint       *url.URL
	authProvider   AuthProvider
	httpClient     *http.Client
	interceptors   []RequestInterceptor
	requestTimeout time.Duration
	errorDecoder   APIErrorDecoder
	ownedTransport *http.Transport
}

func NewClient(service string, endpoint string, authProvider AuthProvider, options ...ClientOption) (*Client, error) {
	if strings.TrimSpace(service) == "" {
		return nil, fmt.Errorf("service must not be blank")
	}
	if authProvider == nil {
		return nil, fmt.Errorf("auth provider is required")
	}
	baseURL, err := normalizeEndpoint(endpoint)
	if err != nil {
		return nil, err
	}
	configured, err := applyClientOptions(options...)
	if err != nil {
		return nil, err
	}

	httpClient := configured.httpClient
	var ownedTransport *http.Transport
	if httpClient == nil {
		ownedTransport = &http.Transport{
			Proxy:                 http.ProxyFromEnvironment,
			DialContext:           (&net.Dialer{Timeout: configured.connectTimeout}).DialContext,
			ResponseHeaderTimeout: configured.readTimeout,
			MaxConnsPerHost:       configured.maxConnections,
			MaxIdleConnsPerHost:   configured.maxConnections,
		}
		if configured.proxy != nil {
			ownedTransport.Proxy = http.ProxyURL(configured.proxy)
		}
		httpClient = &http.Client{Transport: newBoundedRoundTripper(
			ownedTransport,
			configured.maxConnections,
			configured.connectionAcquireTimeout,
		)}
	}

	return &Client{
		service:        service,
		endpoint:       baseURL,
		authProvider:   authProvider,
		httpClient:     httpClient,
		interceptors:   configured.interceptors,
		requestTimeout: configured.requestTimeout,
		errorDecoder:   configured.apiErrorDecoder,
		ownedTransport: ownedTransport,
	}, nil
}

func normalizeEndpoint(endpoint string) (*url.URL, error) {
	parsed, err := url.Parse(endpoint)
	if err != nil {
		return nil, fmt.Errorf("parse endpoint: %w", err)
	}
	if !parsed.IsAbs() || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") ||
		parsed.RawQuery != "" || parsed.Fragment != "" {
		return nil, fmt.Errorf("endpoint must be an absolute HTTP(S) URL without query or fragment")
	}
	if !strings.HasSuffix(parsed.Path, "/") {
		parsed.Path += "/"
	}
	return parsed, nil
}

func (c *Client) Do(
	ctx context.Context,
	operationID string,
	method string,
	path string,
	query url.Values,
	headers http.Header,
	body any,
	result any,
) error {
	if ctx == nil {
		return fmt.Errorf("context must not be nil")
	}
	requestContext, cancel := context.WithTimeout(ctx, c.requestTimeout)
	defer cancel()

	requestURL, err := c.requestURL(path, query)
	if err != nil {
		return &SerializationError{OperationID: operationID, Action: "build request URL", Err: err}
	}
	requestHeaders := headers.Clone()
	if requestHeaders == nil {
		requestHeaders = make(http.Header)
	}
	requestHeaders.Set("Accept", "application/json")

	var requestBody io.Reader
	if body != nil {
		encoded, encodeErr := json.Marshal(body)
		if encodeErr != nil {
			return &SerializationError{OperationID: operationID, Action: "serialize request", Err: encodeErr}
		}
		requestBody = bytes.NewReader(encoded)
		requestHeaders.Set("Content-Type", "application/json")
	}

	metadata := RequestMetadata{Service: c.service, OperationID: operationID, Method: method, URL: requestURL}
	for _, interceptor := range c.interceptors {
		if interceptorErr := interceptor(requestContext, metadata, requestHeaders); interceptorErr != nil {
			return interceptorErr
		}
		if requestHeaders.Get("Authorization") != "" {
			return fmt.Errorf("request interceptors must not set Authorization headers")
		}
	}

	authentication, err := c.authProvider.Resolve(requestContext, AuthContext{
		Service: c.service, OperationID: operationID,
	})
	if err != nil {
		if errors.Is(requestContext.Err(), context.DeadlineExceeded) {
			return &RequestTimeoutError{OperationID: operationID, Err: context.DeadlineExceeded}
		}
		return &AuthenticationError{OperationID: operationID, Err: err}
	}
	if err := requestContext.Err(); err != nil {
		if errors.Is(err, context.DeadlineExceeded) {
			return &RequestTimeoutError{OperationID: operationID, Err: err}
		}
		return &AuthenticationError{OperationID: operationID, Err: err}
	}
	for name, values := range authentication.Headers {
		requestHeaders[name] = append([]string(nil), values...)
	}

	request, err := http.NewRequestWithContext(requestContext, method, requestURL.String(), requestBody)
	if err != nil {
		return &SerializationError{OperationID: operationID, Action: "create request", Err: err}
	}
	request.Header = requestHeaders
	response, err := c.httpClient.Do(request)
	if err != nil {
		if errors.Is(requestContext.Err(), context.DeadlineExceeded) {
			return &RequestTimeoutError{OperationID: operationID, Err: context.DeadlineExceeded}
		}
		return &TransportError{OperationID: operationID, Err: err}
	}
	defer response.Body.Close()
	responseBody, err := io.ReadAll(response.Body)
	if err != nil {
		return &TransportError{OperationID: operationID, Err: err}
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return c.errorDecoder(decodeAPIError(response, responseBody))
	}
	if result != nil && len(responseBody) != 0 {
		if err := json.Unmarshal(responseBody, result); err != nil {
			return &SerializationError{OperationID: operationID, Action: "deserialize response", Err: err}
		}
	}
	return nil
}

func (c *Client) requestURL(path string, query url.Values) (*url.URL, error) {
	relative, err := url.Parse(strings.TrimPrefix(path, "/"))
	if err != nil {
		return nil, fmt.Errorf("parse operation path: %w", err)
	}
	resolved := c.endpoint.ResolveReference(relative)
	resolved.RawQuery = query.Encode()
	return resolved, nil
}

func (c *Client) Close() error {
	if c.ownedTransport != nil {
		c.ownedTransport.CloseIdleConnections()
	}
	return nil
}

func ReplacePathParameter(path string, name string, value any) string {
	return strings.ReplaceAll(path, "{"+name+"}", url.PathEscape(formatValue(value)))
}

func AddQueryParameter(query url.Values, name string, value any) {
	addValues(func(item string) { query.Add(name, item) }, value)
}

func AddHeader(headers http.Header, name string, value any) {
	addValues(func(item string) { headers.Add(name, item) }, value)
}

func addValues(add func(string), value any) {
	if value == nil {
		return
	}
	reflected := reflect.ValueOf(value)
	if reflected.Kind() == reflect.Pointer {
		if reflected.IsNil() {
			return
		}
		reflected = reflected.Elem()
	}
	if reflected.Kind() == reflect.Slice || reflected.Kind() == reflect.Array {
		for index := 0; index < reflected.Len(); index++ {
			add(formatValue(reflected.Index(index).Interface()))
		}
		return
	}
	add(formatValue(reflected.Interface()))
}

func formatValue(value any) string {
	if stringer, ok := value.(fmt.Stringer); ok {
		return stringer.String()
	}
	return fmt.Sprint(value)
}

type boundedRoundTripper struct {
	next           http.RoundTripper
	slots          chan struct{}
	acquireTimeout time.Duration
}

func newBoundedRoundTripper(next http.RoundTripper, maxConnections int, acquireTimeout time.Duration) http.RoundTripper {
	return &boundedRoundTripper{
		next: next, slots: make(chan struct{}, maxConnections), acquireTimeout: acquireTimeout,
	}
}

func (transport *boundedRoundTripper) RoundTrip(request *http.Request) (*http.Response, error) {
	acquireContext, cancel := context.WithTimeout(request.Context(), transport.acquireTimeout)
	defer cancel()
	select {
	case transport.slots <- struct{}{}:
	case <-acquireContext.Done():
		return nil, acquireContext.Err()
	}

	response, err := transport.next.RoundTrip(request)
	if err != nil {
		<-transport.slots
		return nil, err
	}
	if response.Body == nil {
		response.Body = http.NoBody
	}
	response.Body = &releaseOnCloseBody{ReadCloser: response.Body, release: func() { <-transport.slots }}
	return response, nil
}

type releaseOnCloseBody struct {
	io.ReadCloser
	release func()
	once    sync.Once
}

func (body *releaseOnCloseBody) Close() error {
	err := body.ReadCloser.Close()
	body.once.Do(body.release)
	return err
}
