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

    it("restarts crashed subprocess with backoff while active", async () => {
        const fixtureScriptPath = path.resolve(
            path.dirname(fileURLToPath(import.meta.url)),
            "fixtures/crashy-rpc-process.mjs",
        );

        const lifecycleEvents: Array<{ type: string }> = [];
        const forwarder = createPiRpcForwarder(
            {
                command: process.execPath,
                args: [fixtureScriptPath],
                cwd: process.cwd(),
                restartBaseDelayMs: 50,
                maxRestartDelayMs: 100,
            },
            createLogger("silent"),
        );

        forwarder.setLifecycleHandler((event) => {
            lifecycleEvents.push({ type: event.type });
        });

        forwarder.send({
            id: "trigger",
            type: "get_state",
        });

        await waitForCondition(() => lifecycleEvents.filter((event) => event.type === "start").length >= 2);

        await forwarder.stop();

        const startCount = lifecycleEvents.filter((event) => event.type === "start").length;
        const exitCount = lifecycleEvents.filter((event) => event.type === "exit").length;
        expect(startCount).toBeGreaterThanOrEqual(2);
        expect(exitCount).toBeGreaterThanOrEqual(1);
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

async function waitForCondition(predicate: () => boolean): Promise<void> {
    const timeoutAt = Date.now() + 2_000;

    while (Date.now() < timeoutAt) {
        if (predicate()) {
            return;
        }

        await sleep(25);
    }

    throw new Error("Timed out waiting for condition");
}

async function sleep(durationMs: number): Promise<void> {
    await new Promise<void>((resolve) => {
        setTimeout(resolve, durationMs);
    });
}
