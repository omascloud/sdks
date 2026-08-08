import { AuthenticationError } from "./errors.js";
import type {
    AuthContext,
    Authentication,
    AuthProvider,
    Fetch,
} from "./options.js";

const TOKEN_EXCHANGE_ENDPOINT = "https://api.omas.cloud/v1/auth/token-exchange";

export interface M2mAuthProviderOptions {
    readonly refreshSkewMs?: number;
    readonly fetch?: Fetch;
}

interface CachedToken {
    readonly value: string;
    readonly refreshAt: number;
    readonly expiresAt: number;
}

interface TokenSlot {
    token?: CachedToken;
    refresh?: Promise<CachedToken>;
}

export class M2mAuthProvider implements AuthProvider {
    readonly #credential: string;
    readonly #refreshSkewMs: number;
    readonly #fetch: Fetch;
    readonly #slots = new Map<string, TokenSlot>();

    constructor(credential: string, options: M2mAuthProviderOptions = {}) {
        const normalized = credential.trim();
        if (normalized.length === 0) {
            throw new TypeError("credential must not be blank");
        }
        this.#credential = normalized;
        this.#refreshSkewMs = options.refreshSkewMs ?? 30_000;
        if (!Number.isFinite(this.#refreshSkewMs) || this.#refreshSkewMs < 0) {
            throw new TypeError(
                "refreshSkewMs must be a non-negative finite number",
            );
        }
        this.#fetch = options.fetch ?? globalThis.fetch.bind(globalThis);
    }

    async resolve(
        context: AuthContext,
        signal: AbortSignal,
    ): Promise<Authentication> {
        const service = context.service.trim();
        if (service.length === 0) {
            throw new TypeError("auth context service must not be blank");
        }
        let slot = this.#slots.get(service);
        if (slot === undefined) {
            slot = {};
            this.#slots.set(service, slot);
        }
        const now = Date.now();
        if (slot.token !== undefined && now < slot.token.refreshAt) {
            return bearer(slot.token.value);
        }
        if (slot.refresh !== undefined) {
            return this.#waitForRefresh(slot, slot.refresh, context, signal);
        }
        const refresh = this.#exchange(service, signal);
        slot.refresh = refresh;
        try {
            return await this.#waitForRefresh(slot, refresh, context, signal);
        } finally {
            if (slot.refresh === refresh) {
                delete slot.refresh;
            }
        }
    }

    async #waitForRefresh(
        slot: TokenSlot,
        refresh: Promise<CachedToken>,
        context: AuthContext,
        signal: AbortSignal,
    ): Promise<Authentication> {
        try {
            const token = await waitForRefresh(refresh, signal);
            slot.token = token;
            return bearer(token.value);
        } catch (error) {
            if (signal.aborted) {
                throw new AuthenticationError(
                    context.operationId,
                    `authenticate ${context.operationId} request`,
                    signal.reason,
                );
            }
            if (slot.token !== undefined && Date.now() < slot.token.expiresAt) {
                return bearer(slot.token.value);
            }
            throw new AuthenticationError(
                context.operationId,
                `authenticate ${context.operationId} request`,
                error,
            );
        }
    }

    async #exchange(
        service: string,
        signal: AbortSignal,
    ): Promise<CachedToken> {
        let response: Response;
        try {
            response = await this.#fetch(TOKEN_EXCHANGE_ENDPOINT, {
                method: "POST",
                signal,
                headers: {
                    Accept: "application/json",
                    Authorization: `Bearer ${this.#credential}`,
                    "Content-Type": "application/json",
                },
                body: JSON.stringify({ audience: service }),
            });
        } catch {
            if (signal.aborted) {
                throw signal.reason;
            }
            throw new Error("token exchange request failed");
        }
        if (!response.ok) {
            throw new Error(
                `token exchange failed with HTTP ${response.status}`,
            );
        }
        let payload: unknown;
        try {
            payload = JSON.parse(await response.text());
        } catch {
            throw new Error("invalid token exchange response");
        }
        if (!isTokenExchangeResponse(payload)) {
            throw new Error("invalid token exchange response");
        }
        const now = Date.now();
        const lifetimeMs = payload.expiresIn * 1_000;
        const skewMs = Math.min(this.#refreshSkewMs, lifetimeMs / 2);
        return {
            value: payload.accessToken.trim(),
            refreshAt: now + lifetimeMs - skewMs,
            expiresAt: now + lifetimeMs,
        };
    }
}

function bearer(token: string): Authentication {
    return { headers: { Authorization: `Bearer ${token}` } };
}

interface TokenExchangeResponse {
    readonly accessToken: string;
    readonly tokenType: "Bearer";
    readonly expiresIn: number;
}

function isTokenExchangeResponse(
    value: unknown,
): value is TokenExchangeResponse {
    if (typeof value !== "object" || value === null) {
        return false;
    }
    const payload = value as Record<string, unknown>;
    return (
        typeof payload.accessToken === "string" &&
        payload.accessToken.trim().length > 0 &&
        payload.tokenType === "Bearer" &&
        typeof payload.expiresIn === "number" &&
        Number.isInteger(payload.expiresIn) &&
        payload.expiresIn > 0
    );
}

function waitForRefresh<T>(
    refresh: Promise<T>,
    signal: AbortSignal,
): Promise<T> {
    if (signal.aborted) {
        return Promise.reject(signal.reason);
    }
    return new Promise((resolve, reject) => {
        const abort = () => reject(signal.reason);
        signal.addEventListener("abort", abort, { once: true });
        refresh.then(resolve, reject).finally(() => {
            signal.removeEventListener("abort", abort);
        });
    });
}
