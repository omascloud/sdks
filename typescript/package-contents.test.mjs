import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

const packages = ["@omascloud/sdk-core", "@omascloud/sdk-metrics"];
const forbidden = [
    "BrowserSessionAuthProvider",
    "/internal/",
    "internal-contract",
    "private-contract",
    "OMAS_M2M_TOKEN=",
];

for (const packageName of packages) {
    test(`${packageName} packs only public release files`, () => {
        execFileSync("pnpm", ["--filter", packageName, "build"], {
            stdio: "pipe",
        });
        const destination = mkdtempSync(
            join(tmpdir(), "omas-typescript-pack-"),
        );
        execFileSync(
            "pnpm",
            [
                "--filter",
                packageName,
                "pack",
                "--pack-destination",
                destination,
            ],
            { stdio: "pipe" },
        );
        const filename = readdirSync(destination).find((file) =>
            file.endsWith(".tgz"),
        );
        assert.ok(filename, `no archive created for ${packageName}`);
        const archive = join(destination, filename);
        const entries = execFileSync("tar", ["-tzf", archive], {
            encoding: "utf8",
        })
            .trim()
            .split("\n")
            .sort();
        for (const entry of entries) {
            assert.match(
                entry,
                /^package\/(?:package\.json|README\.md|LICENSE|dist\/.+)$/,
                `unexpected packed file: ${entry}`,
            );
        }
        for (const entry of entries.filter((value) =>
            /\.(?:js|d\.ts|json|md)$/.test(value),
        )) {
            const contents = execFileSync("tar", ["-xOzf", archive, entry], {
                encoding: "utf8",
            });
            for (const value of forbidden) {
                assert.equal(
                    contents.includes(value),
                    false,
                    `${entry} contains ${value}`,
                );
            }
        }
    });
}
