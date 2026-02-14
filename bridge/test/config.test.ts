import { describe, expect, it } from "vitest";

import { parseBridgeConfig } from "../src/config.js";

describe("parseBridgeConfig", () => {
    it("uses defaults", () => {
        const config = parseBridgeConfig({});

        expect(config).toEqual({
            host: "127.0.0.1",
            port: 8787,
            logLevel: "info",
        });
    });

    it("parses env values", () => {
        const config = parseBridgeConfig({
            BRIDGE_HOST: "100.64.0.10",
            BRIDGE_PORT: "7777",
            BRIDGE_LOG_LEVEL: "debug",
        });

        expect(config.host).toBe("100.64.0.10");
        expect(config.port).toBe(7777);
        expect(config.logLevel).toBe("debug");
    });

    it("fails on invalid port", () => {
        expect(() => parseBridgeConfig({ BRIDGE_PORT: "invalid" })).toThrow(
            "Invalid BRIDGE_PORT: invalid",
        );
    });
});
