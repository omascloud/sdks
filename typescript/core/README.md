# @omascloud/sdk-core

Shared runtime and authentication for the Omas Cloud TypeScript SDKs. The package is ESM-only and supports Node.js 22 or newer and modern browsers.

Use a bearer token in Node.js or a browser:

```ts
import { BearerAuthProvider } from "@omascloud/sdk-core";

const authProvider = new BearerAuthProvider(token);
```

Machine-to-machine token exchange is Node-only and is deliberately isolated from the browser-safe default entry:

```ts
import { M2mAuthProvider } from "@omascloud/sdk-core/node";

const authProvider = new M2mAuthProvider(credential);
```

Service clients accept an `AbortSignal` in per-call options. Shared errors include `ApiError`, `AuthenticationError`, `RequestTimeoutError`, `SerializationError`, and `TransportError`.

MIT licensed.
