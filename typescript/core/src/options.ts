export interface AuthContext {
    readonly service: string;
    readonly operationId: string;
}

export interface Authentication {
    readonly headers?: HeadersInit;
    readonly credentials?: RequestCredentials;
}

export interface AuthProvider {
    resolve(
        context: AuthContext,
        signal: AbortSignal,
    ): Authentication | Promise<Authentication>;
}

export type Fetch = typeof globalThis.fetch;

export interface CallOptions {
    readonly signal?: AbortSignal;
}

export interface RequestMetadata {
    readonly service: string;
    readonly operationId: string;
    readonly method: string;
    readonly url: URL;
}

export type RequestInterceptor = (
    metadata: RequestMetadata,
    headers: Headers,
    signal: AbortSignal,
) => void | Promise<void>;

export interface ClientOptions {
    readonly authProvider: AuthProvider;
    readonly endpoint?: string;
    readonly requestTimeoutMs?: number;
    readonly interceptors?: ReadonlyArray<RequestInterceptor>;
    readonly fetch?: Fetch;
}
