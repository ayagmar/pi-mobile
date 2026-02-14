import path from "node:path";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

import { createLogger } from "../src/logger.js";
import { createPiRpcForwarder } from "../src/rpc-forwarder.js";

describe("createPiRpcForwarder", () => {
    it("forwards RPC command payloads and emits subprocess stdout payloads", async () => {
        const fixtureScriptPath = path.resolve(
            path.dirname(fileURLToPath(import.meta.url)),
            "fixtures/fake-rpc-process.mjs",
        );

        const receivedMessages: Record<string, unknown>[] = [];
        const forwarder = createPiRpcForwarder(
            {
                command: process.execPath,
                args: [fixtureScriptPath],
                cwd: process.cwd(),
            },
            createLogger("silent"),
        );

        forwarder.setMessageHandler((payload) => {
            receivedMessages.push(payload);
        });

        forwarder.send({
            id: "rpc-1",
            type: "get_state",
        });

        const forwardedMessage = await waitForMessage(receivedMessages);

        expect(forwardedMessage.id).toBe("rpc-1");
        expect(forwardedMessage.type).toBe("response");
        expect(forwardedMessage.command).toBe("get_state");

        await forwarder.stop();
    });
});

async function waitForMessage(messages: Record<string, unknown>[]): Promise<Record<string, unknown>> {
    const timeoutAt = Date.now() + 1_500;

    while (Date.now() < timeoutAt) {
        if (messages.length > 0) {
            return messages[0];
        }

        await sleep(25);
    }

    throw new Error("Timed out waiting for forwarded RPC message");
}

async function sleep(durationMs: number): Promise<void> {
    await new Promise<void>((resolve) => {
        setTimeout(resolve, durationMs);
    });
}
