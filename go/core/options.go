// Copyright (c) 2026 Omas Cloud
//
// SPDX-License-Identifier: MIT

package core

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"time"
)

type HTTPDoer interface {
	Do(*http.Request) (*http.Response, error)
}

type RequestMetadata struct {
	Service     string
	OperationID string
	Method      string
	URL         *url.URL
}

type RequestInterceptor func(context.Context, RequestMetadata, http.Header) error

type APIErrorDecoder func(*APIError) error

type clientOptions struct {
	connectTimeout           time.Duration
	requestTimeout           time.Duration
	readTimeout              time.Duration
	connectionAcquireTimeout time.Duration
	maxConnections           int
	proxy                    *url.URL
	interceptors             []RequestInterceptor
	httpClient               *http.Client
	apiErrorDecoder          APIErrorDecoder
}

type ClientOption func(*clientOptions) error

func defaultClientOptions() clientOptions {
	return clientOptions{
		connectTimeout:           3 * time.Second,
		requestTimeout:           30 * time.Second,
		readTimeout:              10 * time.Second,
		connectionAcquireTimeout: 2 * time.Second,
		maxConnections:           50,
		apiErrorDecoder:          func(apiError *APIError) error { return apiError },
	}
}

func applyClientOptions(options ...ClientOption) (clientOptions, error) {
	configured := defaultClientOptions()
	for _, option := range options {
		if option == nil {
			return clientOptions{}, fmt.Errorf("client option must not be nil")
		}
		if err := option(&configured); err != nil {
			return clientOptions{}, err
		}
	}
	configured.interceptors = append([]RequestInterceptor(nil), configured.interceptors...)
	return configured, nil
}

func positiveDurationOption(name string, value time.Duration, apply func(*clientOptions)) ClientOption {
	return func(options *clientOptions) error {
		if value <= 0 {
			return fmt.Errorf("%s must be positive", name)
		}
		apply(options)
		return nil
	}
}

func WithConnectTimeout(timeout time.Duration) ClientOption {
	return positiveDurationOption("connect timeout", timeout, func(options *clientOptions) {
		options.connectTimeout = timeout
	})
}

func WithRequestTimeout(timeout time.Duration) ClientOption {
	return positiveDurationOption("request timeout", timeout, func(options *clientOptions) {
		options.requestTimeout = timeout
	})
}

func WithReadTimeout(timeout time.Duration) ClientOption {
	return positiveDurationOption("read timeout", timeout, func(options *clientOptions) {
		options.readTimeout = timeout
	})
}

func WithConnectionAcquireTimeout(timeout time.Duration) ClientOption {
	return positiveDurationOption("connection acquire timeout", timeout, func(options *clientOptions) {
		options.connectionAcquireTimeout = timeout
	})
}

func WithMaxConnections(maxConnections int) ClientOption {
	return func(options *clientOptions) error {
		if maxConnections <= 0 {
			return fmt.Errorf("max connections must be positive")
		}
		options.maxConnections = maxConnections
		return nil
	}
}

func WithProxy(proxy *url.URL) ClientOption {
	return func(options *clientOptions) error {
		if proxy == nil || !proxy.IsAbs() || proxy.Host == "" ||
			(proxy.Scheme != "http" && proxy.Scheme != "https") {
			return fmt.Errorf("proxy must be an absolute HTTP(S) URL")
		}
		copyOfProxy := *proxy
		options.proxy = &copyOfProxy
		return nil
	}
}

func WithInterceptor(interceptor RequestInterceptor) ClientOption {
	return func(options *clientOptions) error {
		if interceptor == nil {
			return fmt.Errorf("interceptor must not be nil")
		}
		options.interceptors = append(options.interceptors, interceptor)
		return nil
	}
}

func WithHTTPClient(httpClient *http.Client) ClientOption {
	return func(options *clientOptions) error {
		if httpClient == nil {
			return fmt.Errorf("HTTP client must not be nil")
		}
		options.httpClient = httpClient
		return nil
	}
}

func WithAPIErrorDecoder(decoder APIErrorDecoder) ClientOption {
	return func(options *clientOptions) error {
		if decoder == nil {
			return fmt.Errorf("API error decoder must not be nil")
		}
		options.apiErrorDecoder = decoder
		return nil
	}
}
