import { describe, expect, it } from "vitest";

import type { BridgeConfig } from "../src/config.js";
import { createHostPairingPayload } from "../src/pairing.js";

describe("host pairing payload", () => {
    it("encodes the existing bridge connection for mobile import", () => {
        const config: BridgeConfig = {
            host: "127.0.0.1",
            port: 8787,
            logLevel: "info",
            authToken: "local-test-token",
            processIdleTtlMs: 300_000,
            reconnectGraceMs: 30_000,
            sessionDirectory: "/tmp/sessions",
            enableHealthEndpoint: true,
            websocketMaxPayloadBytes: 16_777_216,
            importMaxBytes: 10_485_760,
            piCommand: "pi",
            shareOrigin: "https://workstation.example.test",
        };

        expect(createHostPairingPayload(config, "workstation.example.ts.net")).toEqual({
            type: "pi-mobile-host",
            version: 2,
            name: "workstation",
            host: "workstation.example.ts.net",
            port: 8787,
            useTls: false,
            token: "local-test-token",
            shareOrigin: "https://workstation.example.test",
        });
    });

    it("advertises TLS when the bridge is configured with TLS material", () => {
        const config: BridgeConfig = {
            host: "127.0.0.1",
            port: 8787,
            logLevel: "info",
            authToken: "local-test-token",
            processIdleTtlMs: 300_000,
            reconnectGraceMs: 30_000,
            sessionDirectory: "/tmp/sessions",
            enableHealthEndpoint: true,
            websocketMaxPayloadBytes: 16_777_216,
            importMaxBytes: 10_485_760,
            piCommand: "pi",
            shareOrigin: "https://workstation.example.test",
            tlsCertFile: "/tmp/server.pem",
            tlsKeyFile: "/tmp/server.key",
        };

        expect(createHostPairingPayload(config, "workstation.example.ts.net").useTls).toBe(true);
    });
});
