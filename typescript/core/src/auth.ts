import type { AuthContext, Authentication, AuthProvider } from "./options.js";

export class BearerAuthProvider implements AuthProvider {
    readonly #token: string;

    constructor(token: string) {
        const normalized = token.trim();
        if (normalized.length === 0) {
            throw new TypeError("token must not be blank");
        }
        this.#token = normalized;
    }

    resolve(_context: AuthContext, _signal: AbortSignal): Authentication {
        return { headers: { Authorization: `Bearer ${this.#token}` } };
    }
}
