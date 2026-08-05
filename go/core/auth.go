// Copyright (c) 2026 Omas Cloud
//
// SPDX-License-Identifier: MIT

package core

import (
	"context"
	"fmt"
	"net/http"
	"strings"
)

type AuthContext struct {
	Service     string
	OperationID string
}

type Authentication struct {
	Headers http.Header
}

func BearerAuthentication(token string) Authentication {
	return Authentication{Headers: http.Header{"Authorization": []string{"Bearer " + token}}}
}

type AuthProvider interface {
	Resolve(context.Context, AuthContext) (Authentication, error)
}

type BearerAuthProvider struct {
	token string
}

func NewBearerAuthProvider(token string) (*BearerAuthProvider, error) {
	token = strings.TrimSpace(token)
	if token == "" {
		return nil, fmt.Errorf("token must not be blank")
	}
	return &BearerAuthProvider{token: token}, nil
}

func (p *BearerAuthProvider) Resolve(context.Context, AuthContext) (Authentication, error) {
	return BearerAuthentication(p.token), nil
}
