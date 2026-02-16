import { describe, expect, it } from "vitest";

import { parseBridgeEnvelope } from "../src/protocol.js";

describe("parseBridgeEnvelope", () => {
    it("parses valid envelope", () => {
        const result = parseBridgeEnvelope(
            JSON.stringify({
                channel: "bridge",
                payload: {
                    type: "bridge_ping",
                },
            }),
        );

        expect(result.success).toBe(true);
        if (!result.success) {
            throw new Error("Expected successful parse");
        }

        expect(result.envelope.channel).toBe("bridge");
        expect(result.envelope.payload.type).toBe("bridge_ping");
    });

    it("rejects invalid json", () => {
        const result = parseBridgeEnvelope("{ invalid");

        expect(result.success).toBe(false);
        if (result.success) {
            throw new Error("Expected parse failure");
        }

        expect(result.error).toContain("Invalid JSON");
    });

    it("rejects unsupported channel", () => {
        const result = parseBridgeEnvelope(
            JSON.stringify({
                channel: "invalid",
                payload: {},
            }),
        );

        expect(result.success).toBe(false);
        if (result.success) {
            throw new Error("Expected parse failure");
        }

        expect(result.error).toBe("Envelope channel must be one of: bridge, rpc");
    });
});
