import { describe, expect, it } from "vitest";

import { parseBridgeConfig } from "../src/config.js";

describe("parseBridgeConfig", () => {
    it("parses defaults when auth token is present", () => {
        const config = parseBridgeConfig({ BRIDGE_AUTH_TOKEN: "test-token" });

        expect(config).toEqual({
            host: "127.0.0.1",
            port: 8787,
            logLevel: "info",
            authToken: "test-token",
        });
    });

    it("parses env values", () => {
        const config = parseBridgeConfig({
            BRIDGE_HOST: "100.64.0.10",
            BRIDGE_PORT: "7777",
            BRIDGE_LOG_LEVEL: "debug",
            BRIDGE_AUTH_TOKEN: "my-token",
        });

        expect(config.host).toBe("100.64.0.10");
        expect(config.port).toBe(7777);
        expect(config.logLevel).toBe("debug");
        expect(config.authToken).toBe("my-token");
    });

    it("fails on invalid port", () => {
        expect(() =>
            parseBridgeConfig({ BRIDGE_PORT: "invalid", BRIDGE_AUTH_TOKEN: "test-token" }),
        ).toThrow("Invalid BRIDGE_PORT: invalid");
    });

    it("fails when auth token is missing", () => {
        expect(() => parseBridgeConfig({})).toThrow("BRIDGE_AUTH_TOKEN is required");
    });
});
