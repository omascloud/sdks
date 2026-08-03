// Copyright (c) 2026 Omas Cloud
//
// SPDX-License-Identifier: MIT

package core

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"time"
)

type APIError struct {
	StatusCode int
	ErrorCode  string
	Message    string
	RequestID  string
	RetryAfter time.Duration
	ExtraData  json.RawMessage
	Headers    http.Header
	RawBody    []byte
}

func (e *APIError) Error() string {
	if e.ErrorCode != "" {
		return fmt.Sprintf("%s: %s", e.ErrorCode, e.Message)
	}
	return e.Message
}

type AuthenticationError struct {
	OperationID string
	Err         error
}

func (e *AuthenticationError) Error() string {
	return fmt.Sprintf("authenticate %s request: %v", e.OperationID, e.Err)
}
func (e *AuthenticationError) Unwrap() error { return e.Err }

type RequestTimeoutError struct {
	OperationID string
	Err         error
}

func (e *RequestTimeoutError) Error() string {
	return fmt.Sprintf("%s request timed out: %v", e.OperationID, e.Err)
}
func (e *RequestTimeoutError) Unwrap() error { return e.Err }

type SerializationError struct {
	OperationID string
	Action      string
	Err         error
}

func (e *SerializationError) Error() string {
	action := e.Action
	if action == "" {
		action = "serialize request"
	}
	return fmt.Sprintf("%s for %s: %v", action, e.OperationID, e.Err)
}
func (e *SerializationError) Unwrap() error { return e.Err }

type TransportError struct {
	OperationID string
	Err         error
}

func (e *TransportError) Error() string {
	return fmt.Sprintf("execute %s request: %v", e.OperationID, e.Err)
}
func (e *TransportError) Unwrap() error { return e.Err }

func decodeAPIError(response *http.Response, body []byte) *APIError {
	payload := struct {
		ErrorCode string          `json:"errorCode"`
		Error     string          `json:"error"`
		ExtraData json.RawMessage `json:"extraData"`
	}{}
	_ = json.Unmarshal(body, &payload)
	message := payload.Error
	if message == "" {
		message = fmt.Sprintf("API request failed with HTTP %d", response.StatusCode)
	}
	retryAfter := time.Duration(0)
	if seconds, err := strconv.ParseInt(response.Header.Get("Retry-After"), 10, 64); err == nil {
		retryAfter = time.Duration(seconds) * time.Second
	}
	return &APIError{
		StatusCode: response.StatusCode,
		ErrorCode:  payload.ErrorCode,
		Message:    message,
		RequestID:  response.Header.Get("X-Request-Id"),
		RetryAfter: retryAfter,
		ExtraData:  append(json.RawMessage(nil), payload.ExtraData...),
		Headers:    response.Header.Clone(),
		RawBody:    append([]byte(nil), body...),
	}
}
