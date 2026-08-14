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
            stateDirectory: path.join(os.homedir(), ".pi-mobile"),
            shareOrigin: undefined,
            enableHealthEndpoint: true,
            websocketMaxPayloadBytes: 16 * 1024 * 1024,
            importMaxBytes: 10 * 1024 * 1024,
            piCommand: "pi",
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
            BRIDGE_STATE_DIR: "./tmp/bridge-state",
            BRIDGE_SHARE_ORIGIN: "https://bridge.example.test",
            BRIDGE_ENABLE_HEALTH_ENDPOINT: "false",
            BRIDGE_WEBSOCKET_MAX_PAYLOAD_BYTES: "2000000",
            BRIDGE_IMPORT_MAX_BYTES: "1000000",
            BRIDGE_PI_COMMAND: "/opt/pi/bin/pi",
        });

        expect(config.host).toBe("100.64.0.10");
        expect(config.port).toBe(7777);
        expect(config.logLevel).toBe("debug");
        expect(config.authToken).toBe("my-token");
        expect(config.processIdleTtlMs).toBe(90_000);
        expect(config.reconnectGraceMs).toBe(12_000);
        expect(config.sessionDirectory).toBe(path.resolve("./tmp/custom-sessions"));
        expect(config.stateDirectory).toBe(path.resolve("./tmp/bridge-state"));
        expect(config.shareOrigin).toBe("https://bridge.example.test");
        expect(config.enableHealthEndpoint).toBe(false);
        expect(config.websocketMaxPayloadBytes).toBe(2_000_000);
        expect(config.importMaxBytes).toBe(1_000_000);
        expect(config.piCommand).toBe("/opt/pi/bin/pi");
    });

    it.each([
        "ftp://bridge.example.test",
        "https://user@bridge.example.test",
        "https://bridge.example.test/prefix",
        "https://bridge.example.test?query=1",
    ])("fails on invalid share origin %s", (origin) => {
        expect(() => parseBridgeConfig({
            BRIDGE_AUTH_TOKEN: "test-token",
            BRIDGE_SHARE_ORIGIN: origin,
        })).toThrow("BRIDGE_SHARE_ORIGIN");
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

    it.each([
        ["BRIDGE_WEBSOCKET_MAX_PAYLOAD_BYTES", "0"],
        ["BRIDGE_IMPORT_MAX_BYTES", "1.5"],
    ])("fails when %s is invalid", (name, value) => {
        expect(() => parseBridgeConfig({ BRIDGE_AUTH_TOKEN: "test-token", [name]: value })).toThrow(
            `Invalid ${name}: ${value}`,
        );
    });

    it("fails when health endpoint flag is invalid", () => {
        expect(() =>
            parseBridgeConfig({ BRIDGE_AUTH_TOKEN: "test-token", BRIDGE_ENABLE_HEALTH_ENDPOINT: "maybe" }),
        ).toThrow("Invalid BRIDGE_ENABLE_HEALTH_ENDPOINT: maybe");
    });

    it("parses TLS cert and key files", () => {
        const config = parseBridgeConfig({
            BRIDGE_AUTH_TOKEN: "test-token",
            BRIDGE_TLS_CERT_FILE: "./tmp/server.pem",
            BRIDGE_TLS_KEY_FILE: "./tmp/server.key",
        });

        expect(config.tlsCertFile).toBe(path.resolve("./tmp/server.pem"));
        expect(config.tlsKeyFile).toBe(path.resolve("./tmp/server.key"));
    });

    it("leaves TLS unset when no TLS env is provided", () => {
        const config = parseBridgeConfig({ BRIDGE_AUTH_TOKEN: "test-token" });

        expect(config.tlsCertFile).toBeUndefined();
        expect(config.tlsKeyFile).toBeUndefined();
    });

    it.each([
        ["BRIDGE_TLS_CERT_FILE", "./tmp/server.pem"],
        ["BRIDGE_TLS_KEY_FILE", "./tmp/server.key"],
    ])("fails when only %s is set", (name, value) => {
        expect(() => parseBridgeConfig({ BRIDGE_AUTH_TOKEN: "test-token", [name]: value })).toThrow(
            "BRIDGE_TLS_CERT_FILE and BRIDGE_TLS_KEY_FILE must be set together",
        );
    });
});
