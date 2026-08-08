import { existsSync } from "node:fs";

import { playwright } from "@vitest/browser-playwright";
import { defineConfig } from "vitest/config";

const executablePath = existsSync("/usr/bin/google-chrome")
    ? "/usr/bin/google-chrome"
    : undefined;

export default defineConfig({
    test: {
        include: ["src/browser.test.ts"],
        browser: {
            enabled: true,
            headless: true,
            provider: playwright({
                launchOptions: {
                    ...(executablePath === undefined ? {} : { executablePath }),
                },
            }),
            instances: [{ browser: "chromium" }],
        },
    },
});
