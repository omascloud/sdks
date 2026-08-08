import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

async function readPackage(path) {
    return JSON.parse(await readFile(path, "utf8"));
}

test("uses YAML-compatible workspace indentation", async () => {
    const workspace = await readFile("pnpm-workspace.yaml", "utf8");
    assert.doesNotMatch(workspace, /^\t/m);
    assert.doesNotMatch(workspace, /set this to true or false/);
});

test("publishes explicit ESM core and metrics package contracts", async () => {
    const root = await readPackage("package.json");
    const core = await readPackage("core/package.json");
    const metrics = await readPackage("metrics/package.json");

    assert.equal(root.packageManager, "pnpm@11.15.1");
    assert.equal(root.scripts.test, "pnpm test:node && pnpm test:browser");
    assert.equal(
        root.scripts["test:node"],
        "node --test *.test.mjs && pnpm --recursive run test:node",
    );
    assert.equal(
        root.scripts["test:browser"],
        "pnpm --recursive --if-present run test:browser",
    );
    assert.equal(core.version, metrics.version);
    assert.equal(core.type, "module");
    assert.deepEqual(Object.keys(core.exports).sort(), [".", "./node"]);
    assert.equal(core.exports["."].require, undefined);
    assert.equal(core.engines.node, ">=22");
    assert.deepEqual(core.files, ["dist", "README.md", "LICENSE"]);
    assert.equal(core.devDependencies.typescript, "catalog:");
    assert.equal(core.devDependencies.vitest, "catalog:");
    assert.equal(metrics.type, "module");
    assert.equal(metrics.exports["."].require, undefined);
    assert.equal(metrics.engines.node, ">=22");
    assert.deepEqual(metrics.files, ["dist", "README.md", "LICENSE"]);
    assert.equal(metrics.dependencies["@omascloud/sdk-core"], "workspace:^");
    assert.equal(metrics.devDependencies.typescript, "catalog:");
    assert.equal(metrics.devDependencies.vitest, "catalog:");
    assert.equal(
        core.scripts["test:node"],
        "vitest run --exclude src/browser.test.ts",
    );
    assert.equal(
        metrics.scripts["test:node"],
        "vitest run --exclude src/browser.test.ts",
    );
});
