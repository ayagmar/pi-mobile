import { describe, expect, it } from "vitest";

import { createLogger } from "../src/logger.js";
import {
    createPiProcessManager,
    type ProcessManagerEvent,
} from "../src/process-manager.js";
import type {
    PiRpcForwarder,
    PiRpcForwarderLifecycleEvent,
    PiRpcForwarderMessage,
} from "../src/rpc-forwarder.js";

describe("createPiProcessManager", () => {
    it("creates and routes to one RPC forwarder per cwd", () => {
        const createdForwarders = new Map<string, FakeRpcForwarder>();

        const manager = createPiProcessManager({
            idleTtlMs: 60_000,
            logger: createLogger("silent"),
            enableEvictionTimer: false,
            forwarderFactory: (cwd) => {
                const fakeForwarder = new FakeRpcForwarder();
                createdForwarders.set(cwd, fakeForwarder);
                return fakeForwarder;
            },
        });

        manager.sendRpc("/tmp/project-a", { id: "a", type: "get_state" });
        manager.sendRpc("/tmp/project-b", { id: "b", type: "get_state" });
        manager.sendRpc("/tmp/project-a", { id: "c", type: "get_messages" });

        expect(createdForwarders.size).toBe(2);
        expect(manager.getStats()).toEqual({
            activeProcessCount: 2,
            lockedCwdCount: 0,
            lockedSessionCount: 0,
        });
        expect(createdForwarders.get("/tmp/project-a")?.sentPayloads).toEqual([
            { id: "a", type: "get_state" },
            { id: "c", type: "get_messages" },
        ]);
        expect(createdForwarders.get("/tmp/project-b")?.sentPayloads).toEqual([
            { id: "b", type: "get_state" },
        ]);
    });

    it("rejects concurrent control locks on the same cwd", () => {
        const manager = createPiProcessManager({
            idleTtlMs: 60_000,
            logger: createLogger("silent"),
            enableEvictionTimer: false,
            forwarderFactory: () => new FakeRpcForwarder(),
        });

        const firstResult = manager.acquireControl({
            clientId: "client-a",
            cwd: "/tmp/project-a",
        });
        const secondResult = manager.acquireControl({
            clientId: "client-b",
            cwd: "/tmp/project-a",
        });

        expect(firstResult.success).toBe(true);
        expect(secondResult.success).toBe(false);
        expect(secondResult.reason).toContain("cwd is controlled by another client");

        manager.releaseClient("client-a");
        const thirdResult = manager.acquireControl({
            clientId: "client-b",
            cwd: "/tmp/project-a",
        });

        expect(thirdResult.success).toBe(true);
    });

    it("releasing cwd control also releases session locks for that cwd", () => {
        const manager = createPiProcessManager({
            idleTtlMs: 60_000,
            logger: createLogger("silent"),
            enableEvictionTimer: false,
            forwarderFactory: () => new FakeRpcForwarder(),
        });

        const firstResult = manager.acquireControl({
            clientId: "client-a",
            cwd: "/tmp/project-a",
            sessionPath: "/tmp/session-a.jsonl",
        });
        expect(firstResult.success).toBe(true);

        manager.releaseControl("client-a", "/tmp/project-a");

        const secondResult = manager.acquireControl({
            clientId: "client-b",
            cwd: "/tmp/project-b",
            sessionPath: "/tmp/session-a.jsonl",
        });

        expect(secondResult.success).toBe(true);
    });

    it("exposes lock owners via getControlSnapshot", () => {
        const manager = createPiProcessManager({
            idleTtlMs: 60_000,
            logger: createLogger("silent"),
            enableEvictionTimer: false,
            forwarderFactory: () => new FakeRpcForwarder(),
        });

        const acquired = manager.acquireControl({
            clientId: "client-a",
            cwd: "/tmp/project-a",
            sessionPath: "/tmp/session-a.jsonl",
        });
        expect(acquired.success).toBe(true);

        expect(
            manager.getControlSnapshot(
                "/tmp/project-a",
                "/tmp/session-a.jsonl",
            ),
        ).toEqual({
            cwdOwnerClientId: "client-a",
            sessionOwnerClientId: "client-a",
        });

        manager.releaseClient("client-a");

        expect(
            manager.getControlSnapshot(
                "/tmp/project-a",
                "/tmp/session-a.jsonl",
            ),
        ).toEqual({
            cwdOwnerClientId: undefined,
            sessionOwnerClientId: undefined,
        });
    });

    it("evicts idle RPC forwarders based on ttl", async () => {
        let nowMs = 0;
        const fakeForwarder = new FakeRpcForwarder();

        const manager = createPiProcessManager({
            idleTtlMs: 1_000,
            logger: createLogger("silent"),
            now: () => nowMs,
            enableEvictionTimer: false,
            forwarderFactory: () => fakeForwarder,
        });

        manager.sendRpc("/tmp/project-a", { id: "one", type: "get_state" });
        expect(fakeForwarder.stopped).toBe(false);

        nowMs += 1_500;
        await manager.evictIdleProcesses();

        expect(fakeForwarder.stopped).toBe(true);
    });

    it("forwards subprocess events with the owning cwd", () => {
        const events: ProcessManagerEvent[] = [];
        const fakeForwarder = new FakeRpcForwarder();

        const manager = createPiProcessManager({
            idleTtlMs: 60_000,
            logger: createLogger("silent"),
            enableEvictionTimer: false,
            forwarderFactory: () => fakeForwarder,
        });

        manager.setMessageHandler((event) => {
            events.push(event);
        });

        manager.sendRpc("/tmp/project-a", { id: "one", type: "get_state" });
        fakeForwarder.emit({ id: "one", type: "response", success: true, command: "get_state" });

        expect(events).toEqual([
            {
                cwd: "/tmp/project-a",
                payload: { id: "one", type: "response", success: true, command: "get_state" },
            },
        ]);
    });
});

class FakeRpcForwarder implements PiRpcForwarder {
    sentPayloads: Record<string, unknown>[] = [];
    stopped = false;

    private messageHandler: (payload: PiRpcForwarderMessage) => void = () => {};
    private lifecycleHandler: (event: PiRpcForwarderLifecycleEvent) => void = () => {};

    constructor() {}

    setMessageHandler(handler: (payload: PiRpcForwarderMessage) => void): void {
        this.messageHandler = handler;
    }

    setLifecycleHandler(handler: (event: PiRpcForwarderLifecycleEvent) => void): void {
        this.lifecycleHandler = handler;
    }

    send(payload: Record<string, unknown>): void {
        this.sentPayloads.push(payload);
    }

    emit(payload: PiRpcForwarderMessage): void {
        this.messageHandler(payload);
    }

    async stop(): Promise<void> {
        this.stopped = true;
    }
}
