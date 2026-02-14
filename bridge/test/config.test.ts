import os from "node:os";
import path from "node:path";

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
            processIdleTtlMs: 300_000,
            reconnectGraceMs: 30_000,
            sessionDirectory: path.join(os.homedir(), ".pi", "agent", "sessions"),
        });
    });

    it("parses env values", () => {
        const config = parseBridgeConfig({
            BRIDGE_HOST: "100.64.0.10",
            BRIDGE_PORT: "7777",
            BRIDGE_LOG_LEVEL: "debug",
            BRIDGE_AUTH_TOKEN: "my-token",
            BRIDGE_PROCESS_IDLE_TTL_MS: "90000",
            BRIDGE_RECONNECT_GRACE_MS: "12000",
            BRIDGE_SESSION_DIR: "./tmp/custom-sessions",
        });

        expect(config.host).toBe("100.64.0.10");
        expect(config.port).toBe(7777);
        expect(config.logLevel).toBe("debug");
        expect(config.authToken).toBe("my-token");
        expect(config.processIdleTtlMs).toBe(90_000);
        expect(config.reconnectGraceMs).toBe(12_000);
        expect(config.sessionDirectory).toBe(path.resolve("./tmp/custom-sessions"));
    });

    it("fails on invalid port", () => {
        expect(() =>
            parseBridgeConfig({ BRIDGE_PORT: "invalid", BRIDGE_AUTH_TOKEN: "test-token" }),
        ).toThrow("Invalid BRIDGE_PORT: invalid");
    });

    it("fails when auth token is missing", () => {
        expect(() => parseBridgeConfig({})).toThrow("BRIDGE_AUTH_TOKEN is required");
    });

    it("fails when process idle ttl is invalid", () => {
        expect(() =>
            parseBridgeConfig({ BRIDGE_AUTH_TOKEN: "test-token", BRIDGE_PROCESS_IDLE_TTL_MS: "900" }),
        ).toThrow("Invalid BRIDGE_PROCESS_IDLE_TTL_MS: 900");
    });

    it("fails when reconnect grace is invalid", () => {
        expect(() =>
            parseBridgeConfig({ BRIDGE_AUTH_TOKEN: "test-token", BRIDGE_RECONNECT_GRACE_MS: "-1" }),
        ).toThrow("Invalid BRIDGE_RECONNECT_GRACE_MS: -1");
    });
});
