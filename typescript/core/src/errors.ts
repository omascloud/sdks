export class SdkError extends Error {
    constructor(message: string, options?: ErrorOptions) {
        super(message, options);
        this.name = "SdkError";
    }
}

export interface ApiErrorOptions<TDetails> {
    readonly status: number;
    readonly errorCode: string;
    readonly message: string;
    readonly requestId?: string | undefined;
    readonly retryAfterMs?: number | undefined;
    readonly headers: Headers;
    readonly details?: TDetails | undefined;
}

export class ApiError<TDetails = unknown> extends SdkError {
    readonly status: number;
    readonly errorCode: string;
    readonly serverMessage: string;
    readonly requestId: string | undefined;
    readonly retryAfterMs: number | undefined;
    readonly headers: Headers;
    readonly details: TDetails | undefined;

    constructor(options: ApiErrorOptions<TDetails>) {
        super(
            options.errorCode.length > 0
                ? `${options.errorCode}: ${options.message}`
                : options.message,
        );
        this.name = "ApiError";
        this.status = options.status;
        this.errorCode = options.errorCode;
        this.serverMessage = options.message;
        this.requestId = options.requestId;
        this.retryAfterMs = options.retryAfterMs;
        this.headers = new Headers(options.headers);
        this.details = options.details;
    }
}

abstract class OperationError extends SdkError {
    readonly operationId: string;

    constructor(
        name: string,
        operationId: string,
        message: string,
        cause: unknown,
    ) {
        super(message, { cause });
        this.name = name;
        this.operationId = operationId;
    }
}

export class AuthenticationError extends OperationError {
    constructor(operationId: string, message: string, cause: unknown) {
        super("AuthenticationError", operationId, message, cause);
    }
}

export class TransportError extends OperationError {
    constructor(operationId: string, message: string, cause: unknown) {
        super("TransportError", operationId, message, cause);
    }
}

export class RequestTimeoutError extends OperationError {
    constructor(operationId: string, message: string, cause: unknown) {
        super("RequestTimeoutError", operationId, message, cause);
    }
}

export class SerializationError extends OperationError {
    constructor(operationId: string, message: string, cause: unknown) {
        super("SerializationError", operationId, message, cause);
    }
}
