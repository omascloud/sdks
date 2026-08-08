import assert from "node:assert/strict";
import { test } from "node:test";

import { build } from "esbuild";

const publicEntries = ["core/src/index.ts", "metrics/src/index.ts"];
const forbidden = [
    "node:",
    "M2mAuthProvider",
    "m2m-auth-provider",
    "OMAS_M2M_TOKEN",
    "token-exchange",
];

test("public TypeScript entries bundle for browsers without Node-only authentication", async () => {
    for (const entryPoint of publicEntries) {
        const result = await build({
            entryPoints: [entryPoint],
            bundle: true,
            platform: "browser",
            format: "esm",
            write: false,
        });
        const output = result.outputFiles.map((file) => file.text).join("\n");
        for (const value of forbidden) {
            assert.equal(
                output.includes(value),
                false,
                `${entryPoint} contains ${value}`,
            );
        }
    }
});

test("the explicit Core Node entry retains M2M authentication", async () => {
    const result = await build({
        entryPoints: ["core/src/node.ts"],
        bundle: true,
        platform: "node",
        format: "esm",
        write: false,
    });
    const output = result.outputFiles.map((file) => file.text).join("\n");
    assert.match(output, /M2mAuthProvider/);
    assert.match(output, /api\.omas\.cloud\/v1\/auth\/token-exchange/);
});
