import {
    ApiError,
    AuthenticationError,
    RequestTimeoutError,
    SdkError,
    SerializationError,
    TransportError,
} from "./errors.js";
import type {
    Authentication,
    AuthProvider,
    CallOptions,
    ClientOptions,
    Fetch,
    RequestInterceptor,
} from "./options.js";

export interface RuntimeRequest {
    readonly operationId: string;
    readonly method: string;
    readonly path: string;
    readonly query?: ReadonlyArray<readonly [string, string]>;
    readonly headers?: HeadersInit;
    readonly body?: unknown;
}

export type ApiErrorDecoder = (error: ApiError<unknown>) => ApiError<unknown>;

export class ClientRuntime {
    readonly #service: string;
    readonly #endpoint: URL;
    readonly #authProvider: AuthProvider;
    readonly #interceptors: ReadonlyArray<RequestInterceptor>;
    readonly #fetch: Fetch;
    readonly #requestTimeoutMs: number;
    readonly #errorDecoder: ApiErrorDecoder;

    constructor(
        service: string,
        defaultEndpoint: string,
        options: ClientOptions,
        errorDecoder: ApiErrorDecoder = (error) => error,
    ) {
        this.#service = service;
        this.#endpoint = normalizeEndpoint(options.endpoint ?? defaultEndpoint);
        this.#authProvider = options.authProvider;
        this.#interceptors = [...(options.interceptors ?? [])];
        this.#fetch = options.fetch ?? globalThis.fetch.bind(globalThis);
        this.#errorDecoder = errorDecoder;
        this.#requestTimeoutMs = options.requestTimeoutMs ?? 30_000;
        if (
            !Number.isFinite(this.#requestTimeoutMs) ||
            this.#requestTimeoutMs <= 0
        ) {
            throw new TypeError(
                "requestTimeoutMs must be a positive finite number",
            );
        }
    }

    async request<T>(
        request: RuntimeRequest,
        options?: CallOptions,
    ): Promise<T> {
        const url = new URL(request.path.replace(/^\//, ""), this.#endpoint);
        for (const [name, value] of request.query ?? []) {
            url.searchParams.append(name, value);
        }
        const headers = new Headers(request.headers);
        headers.set("Accept", "application/json");
        let body: string | undefined;
        if (request.body !== undefined) {
            try {
                body = JSON.stringify(request.body);
                if (body === undefined) {
                    throw new TypeError(
                        "request body is not JSON serializable",
                    );
                }
            } catch (error) {
                throw new SerializationError(
                    request.operationId,
                    `serialize ${request.operationId} request`,
                    error,
                );
            }
            headers.set("Content-Type", "application/json");
        }
        const controller = new AbortController();
        let timedOut = false;
        const abortFromCaller = () => {
            controller.abort(
                options?.signal?.reason ??
                    new DOMException("The request was aborted", "AbortError"),
            );
        };
        if (options?.signal?.aborted) {
            abortFromCaller();
        } else {
            options?.signal?.addEventListener("abort", abortFromCaller, {
                once: true,
            });
        }
        const timeout = setTimeout(() => {
            timedOut = true;
            controller.abort(
                new DOMException("Request timed out", "TimeoutError"),
            );
        }, this.#requestTimeoutMs);
        const signal = controller.signal;
        const metadata = {
            service: this.#service,
            operationId: request.operationId,
            method: request.method,
            url,
        };
        try {
            for (const interceptor of this.#interceptors) {
                await interceptor(metadata, headers, signal);
                if (headers.has("Authorization")) {
                    throw new SdkError(
                        "Request interceptors must not set Authorization headers",
                    );
                }
            }
            let authentication: Authentication;
            try {
                authentication = await this.#authProvider.resolve(
                    {
                        service: this.#service,
                        operationId: request.operationId,
                    },
                    signal,
                );
            } catch (error) {
                if (timedOut) {
                    throw timeoutError(request.operationId, error);
                }
                if (options?.signal?.aborted) {
                    throw cancellationError(
                        request.operationId,
                        options.signal.reason,
                    );
                }
                throw new AuthenticationError(
                    request.operationId,
                    `authenticate ${request.operationId} request`,
                    error,
                );
            }
            new Headers(authentication.headers).forEach((value, name) => {
                headers.set(name, value);
            });
            const requestInit: RequestInit = {
                method: request.method,
                headers,
                signal,
            };
            if (body !== undefined) {
                requestInit.body = body;
            }
            if (authentication.credentials !== undefined) {
                requestInit.credentials = authentication.credentials;
            }
            let response: Response;
            try {
                response = await this.#fetch(url, requestInit);
            } catch (error) {
                if (timedOut) {
                    throw timeoutError(request.operationId, error);
                }
                if (options?.signal?.aborted) {
                    throw cancellationError(
                        request.operationId,
                        options.signal.reason,
                    );
                }
                throw new TransportError(
                    request.operationId,
                    `execute ${request.operationId} request`,
                    error,
                );
            }
            if (!response.ok) {
                throw this.#errorDecoder(await decodeApiError(response));
            }
            if (response.status === 204) {
                return undefined as T;
            }
            const responseBody = await response.text();
            if (responseBody.length === 0) {
                return undefined as T;
            }
            try {
                return JSON.parse(responseBody) as T;
            } catch (error) {
                throw new SerializationError(
                    request.operationId,
                    `deserialize ${request.operationId} response`,
                    error,
                );
            }
        } finally {
            clearTimeout(timeout);
            options?.signal?.removeEventListener("abort", abortFromCaller);
        }
    }
}

function timeoutError(
    operationId: string,
    cause: unknown,
): RequestTimeoutError {
    return new RequestTimeoutError(
        operationId,
        `${operationId} request timed out`,
        cause,
    );
}

function cancellationError(
    operationId: string,
    cause: unknown,
): TransportError {
    return new TransportError(
        operationId,
        `${operationId} request was cancelled`,
        cause,
    );
}

async function decodeApiError(response: Response): Promise<ApiError<unknown>> {
    const responseBody = await response.text();
    let errorCode = "";
    let message = `API request failed with HTTP ${response.status}`;
    let details: unknown;
    try {
        const payload = JSON.parse(responseBody) as {
            errorCode?: unknown;
            error?: unknown;
            extraData?: unknown;
        };
        if (typeof payload.errorCode === "string") {
            errorCode = payload.errorCode;
        }
        if (typeof payload.error === "string" && payload.error.length > 0) {
            message = payload.error;
        }
        details = payload.extraData;
    } catch {
        // The status and safe fallback remain available for malformed payloads.
    }
    const retryAfterSeconds = Number.parseInt(
        response.headers.get("Retry-After") ?? "",
        10,
    );
    return new ApiError({
        status: response.status,
        errorCode,
        message,
        requestId: response.headers.get("X-Request-Id") ?? undefined,
        retryAfterMs: Number.isNaN(retryAfterSeconds)
            ? undefined
            : retryAfterSeconds * 1_000,
        headers: response.headers,
        details,
    });
}

function normalizeEndpoint(value: string): URL {
    let endpoint: URL;
    try {
        endpoint = new URL(value);
    } catch {
        throw new TypeError(
            "endpoint must be an absolute HTTP(S) URL without query or fragment",
        );
    }
    if (
        (endpoint.protocol !== "http:" && endpoint.protocol !== "https:") ||
        endpoint.search.length > 0 ||
        endpoint.hash.length > 0
    ) {
        throw new TypeError(
            "endpoint must be an absolute HTTP(S) URL without query or fragment",
        );
    }
    if (!endpoint.pathname.endsWith("/")) {
        endpoint.pathname += "/";
    }
    return endpoint;
}
