import { afterEach, describe, expect, it } from "vitest";
import { WebSocket, type ClientOptions, type RawData } from "ws";

import { createLogger } from "../src/logger.js";
import type {
    AcquireControlRequest,
    AcquireControlResult,
    PiProcessManager,
    ProcessManagerEvent,
} from "../src/process-manager.js";
import type { PiRpcForwarder } from "../src/rpc-forwarder.js";
import type { BridgeServer } from "../src/server.js";
import { createBridgeServer } from "../src/server.js";
import type { SessionIndexGroup, SessionIndexer } from "../src/session-indexer.js";

describe("bridge websocket server", () => {
    let bridgeServer: BridgeServer | undefined;

    afterEach(async () => {
        if (bridgeServer) {
            await bridgeServer.stop();
        }
        bridgeServer = undefined;
    });

    it("rejects websocket connections without a valid token", async () => {
        const { baseUrl, server } = await startBridgeServer();
        bridgeServer = server;

        const statusCode = await new Promise<number>((resolve, reject) => {
            const ws = new WebSocket(baseUrl);

            const timeoutHandle = setTimeout(() => {
                reject(new Error("Timed out waiting for unexpected-response"));
            }, 1_000);

            ws.on("unexpected-response", (_request, response) => {
                clearTimeout(timeoutHandle);
                response.resume();
                resolve(response.statusCode ?? 0);
            });

            ws.on("open", () => {
                clearTimeout(timeoutHandle);
                ws.close();
                reject(new Error("Connection should have been rejected"));
            });

            ws.on("error", () => {
                // no-op: ws emits an error on unauthorized responses
            });
        });

        expect(statusCode).toBe(401);
    });

    it("returns bridge_error for malformed envelope", async () => {
        const { baseUrl, server } = await startBridgeServer();
        bridgeServer = server;

        const ws = await connectWebSocket(baseUrl, {
            headers: {
                authorization: "Bearer bridge-token",
            },
        });

        const waitForMalformedError = waitForEnvelope(ws, (envelope) => {
            return envelope.payload?.type === "bridge_error" && envelope.payload.code === "malformed_envelope";
        });
        ws.send("{ malformed-json");

        const errorEnvelope = await waitForMalformedError;

        expect(errorEnvelope.channel).toBe("bridge");
        expect(errorEnvelope.payload?.type).toBe("bridge_error");
        expect(errorEnvelope.payload?.code).toBe("malformed_envelope");

        ws.close();
    });

    it("returns grouped session metadata via bridge_list_sessions", async () => {
        const fakeSessionIndexer = new FakeSessionIndexer([
            {
                cwd: "/tmp/project-a",
                sessions: [
                    {
                        sessionPath: "/tmp/session-a.jsonl",
                        cwd: "/tmp/project-a",
                        createdAt: "2026-02-01T00:00:00.000Z",
                        updatedAt: "2026-02-01T00:05:00.000Z",
                        displayName: "Session A",
                        firstUserMessagePreview: "hello",
                        messageCount: 2,
                        lastModel: "gpt-5",
                    },
                ],
            },
        ]);
        const { baseUrl, server } = await startBridgeServer({ sessionIndexer: fakeSessionIndexer });
        bridgeServer = server;

        const ws = await connectWebSocket(baseUrl, {
            headers: {
                authorization: "Bearer bridge-token",
            },
        });

        const waitForSessions = waitForEnvelope(ws, (envelope) => envelope.payload?.type === "bridge_sessions");
        ws.send(
            JSON.stringify({
                channel: "bridge",
                payload: {
                    type: "bridge_list_sessions",
                },
            }),
        );

        const sessionsEnvelope = await waitForSessions;

        expect(fakeSessionIndexer.listCalls).toBe(1);
        expect(sessionsEnvelope.payload?.type).toBe("bridge_sessions");
        expect(sessionsEnvelope.payload?.groups).toEqual([
            {
                cwd: "/tmp/project-a",
                sessions: [
                    {
                        sessionPath: "/tmp/session-a.jsonl",
                        cwd: "/tmp/project-a",
                        createdAt: "2026-02-01T00:00:00.000Z",
                        updatedAt: "2026-02-01T00:05:00.000Z",
                        displayName: "Session A",
                        firstUserMessagePreview: "hello",
                        messageCount: 2,
                        lastModel: "gpt-5",
                    },
                ],
            },
        ]);

        ws.close();
    });

    it("forwards rpc payload using cwd-specific process manager context", async () => {
        const fakeProcessManager = new FakeProcessManager();
        const { baseUrl, server } = await startBridgeServer({ processManager: fakeProcessManager });
        bridgeServer = server;

        const ws = await connectWebSocket(baseUrl, {
            headers: {
                authorization: "Bearer bridge-token",
            },
        });

        const waitForCwdSet = waitForEnvelope(ws, (envelope) => envelope.payload?.type === "bridge_cwd_set");
        ws.send(
            JSON.stringify({
                channel: "bridge",
                payload: {
                    type: "bridge_set_cwd",
                    cwd: "/tmp/project-a",
                },
            }),
        );
        await waitForCwdSet;

        const waitForControlAcquired = waitForEnvelope(ws, (envelope) => envelope.payload?.type === "bridge_control_acquired");
        ws.send(
            JSON.stringify({
                channel: "bridge",
                payload: {
                    type: "bridge_acquire_control",
                    cwd: "/tmp/project-a",
                },
            }),
        );
        await waitForControlAcquired;

        const waitForRpcEnvelope = waitForEnvelope(ws, (envelope) => {
            return envelope.channel === "rpc" && envelope.payload?.id === "req-1";
        });
        ws.send(
            JSON.stringify({
                channel: "rpc",
                payload: {
                    id: "req-1",
                    type: "get_state",
                },
            }),
        );

        const rpcEnvelope = await waitForRpcEnvelope;

        expect(fakeProcessManager.sentPayloads).toEqual([
            {
                cwd: "/tmp/project-a",
                payload: {
                    id: "req-1",
                    type: "get_state",
                },
            },
        ]);
        expect(rpcEnvelope.payload?.type).toBe("response");
        expect(rpcEnvelope.payload?.command).toBe("get_state");

        ws.close();
    });

    it("rejects concurrent control lock attempts for the same cwd", async () => {
        const fakeProcessManager = new FakeProcessManager();
        const { baseUrl, server } = await startBridgeServer({ processManager: fakeProcessManager });
        bridgeServer = server;

        const wsA = await connectWebSocket(baseUrl, {
            headers: {
                authorization: "Bearer bridge-token",
            },
        });
        const wsB = await connectWebSocket(baseUrl, {
            headers: {
                authorization: "Bearer bridge-token",
            },
        });

        for (const ws of [wsA, wsB]) {
            const waitForCwdSet = waitForEnvelope(ws, (envelope) => envelope.payload?.type === "bridge_cwd_set");
            ws.send(
                JSON.stringify({
                    channel: "bridge",
                    payload: {
                        type: "bridge_set_cwd",
                        cwd: "/tmp/shared-project",
                    },
                }),
            );
            await waitForCwdSet;
        }

        const waitForControlAcquired = waitForEnvelope(wsA, (envelope) => envelope.payload?.type === "bridge_control_acquired");
        wsA.send(
            JSON.stringify({
                channel: "bridge",
                payload: {
                    type: "bridge_acquire_control",
                },
            }),
        );
        await waitForControlAcquired;

        const waitForLockRejection = waitForEnvelope(wsB, (envelope) => {
            return envelope.payload?.type === "bridge_error" && envelope.payload?.code === "control_lock_denied";
        });
        wsB.send(
            JSON.stringify({
                channel: "bridge",
                payload: {
                    type: "bridge_acquire_control",
                },
            }),
        );

        const rejection = await waitForLockRejection;

        expect(rejection.payload?.message).toContain("cwd is controlled by another client");

        wsA.close();
        wsB.close();
    });
});

async function startBridgeServer(
    deps?: { processManager?: PiProcessManager; sessionIndexer?: SessionIndexer },
): Promise<{ baseUrl: string; server: BridgeServer }> {
    const logger = createLogger("silent");
    const server = createBridgeServer(
        {
            host: "127.0.0.1",
            port: 0,
            logLevel: "silent",
            authToken: "bridge-token",
            processIdleTtlMs: 300_000,
            sessionDirectory: "/tmp/pi-sessions",
        },
        logger,
        deps,
    );

    const serverInfo = await server.start();

    return {
        baseUrl: `ws://127.0.0.1:${serverInfo.port}/ws`,
        server,
    };
}

async function connectWebSocket(url: string, options?: ClientOptions): Promise<WebSocket> {
    return await new Promise<WebSocket>((resolve, reject) => {
        const ws = new WebSocket(url, options);

        const timeoutHandle = setTimeout(() => {
            ws.terminate();
            reject(new Error("Timed out while opening websocket"));
        }, 1_000);

        ws.on("open", () => {
            clearTimeout(timeoutHandle);
            resolve(ws);
        });

        ws.on("error", (error) => {
            clearTimeout(timeoutHandle);
            reject(error);
        });
    });
}

interface EnvelopeLike {
    channel?: string;
    payload?: {
        [key: string]: unknown;
        type?: string;
        code?: string;
        id?: string;
        command?: string;
        message?: string;
    };
}

async function waitForEnvelope(
    ws: WebSocket,
    predicate: (envelope: EnvelopeLike) => boolean,
): Promise<EnvelopeLike> {
    return await new Promise<EnvelopeLike>((resolve, reject) => {
        const timeoutHandle = setTimeout(() => {
            cleanup();
            reject(new Error("Timed out waiting for websocket message"));
        }, 1_000);

        const onMessage = (rawMessage: RawData) => {
            const rawText = rawDataToString(rawMessage);

            let parsed: unknown;
            try {
                parsed = JSON.parse(rawText);
            } catch {
                return;
            }

            if (!isEnvelopeLike(parsed)) return;

            if (predicate(parsed)) {
                cleanup();
                resolve(parsed);
            }
        };

        const onError = (error: Error) => {
            cleanup();
            reject(error);
        };

        const cleanup = () => {
            clearTimeout(timeoutHandle);
            ws.off("message", onMessage);
            ws.off("error", onError);
        };

        ws.on("message", onMessage);
        ws.on("error", onError);
    });
}

function rawDataToString(rawData: RawData): string {
    if (typeof rawData === "string") return rawData;

    if (Array.isArray(rawData)) {
        return Buffer.concat(rawData).toString("utf-8");
    }

    if (rawData instanceof ArrayBuffer) {
        return Buffer.from(rawData).toString("utf-8");
    }

    return rawData.toString("utf-8");
}

function isEnvelopeLike(value: unknown): value is EnvelopeLike {
    if (typeof value !== "object" || value === null) return false;

    const envelope = value as {
        channel?: unknown;
        payload?: unknown;
    };

    if (typeof envelope.channel !== "string") return false;
    if (typeof envelope.payload !== "object" || envelope.payload === null) return false;

    return true;
}

class FakeSessionIndexer implements SessionIndexer {
    listCalls = 0;

    constructor(private readonly groups: SessionIndexGroup[]) {}

    async listSessions(): Promise<SessionIndexGroup[]> {
        this.listCalls += 1;
        return this.groups;
    }
}

class FakeProcessManager implements PiProcessManager {
    sentPayloads: Array<{ cwd: string; payload: Record<string, unknown> }> = [];

    private messageHandler: (event: ProcessManagerEvent) => void = () => {};
    private lockByCwd = new Map<string, string>();

    setMessageHandler(handler: (event: ProcessManagerEvent) => void): void {
        this.messageHandler = handler;
    }

    getOrStart(cwd: string): PiRpcForwarder {
        void cwd;
        throw new Error("Not used in FakeProcessManager");
    }

    sendRpc(cwd: string, payload: Record<string, unknown>): void {
        this.sentPayloads.push({ cwd, payload });

        this.messageHandler({
            cwd,
            payload: {
                id: payload.id,
                type: "response",
                command: payload.type,
                success: true,
                data: {
                    forwarded: true,
                },
            },
        });
    }

    acquireControl(request: AcquireControlRequest): AcquireControlResult {
        const owner = this.lockByCwd.get(request.cwd);
        if (owner && owner !== request.clientId) {
            return {
                success: false,
                reason: `cwd is controlled by another client: ${request.cwd}`,
            };
        }

        this.lockByCwd.set(request.cwd, request.clientId);
        return { success: true };
    }

    hasControl(clientId: string, cwd: string): boolean {
        return this.lockByCwd.get(cwd) === clientId;
    }

    releaseControl(clientId: string, cwd: string): void {
        if (this.lockByCwd.get(cwd) === clientId) {
            this.lockByCwd.delete(cwd);
        }
    }

    releaseClient(clientId: string): void {
        for (const [cwd, owner] of this.lockByCwd.entries()) {
            if (owner === clientId) {
                this.lockByCwd.delete(cwd);
            }
        }
    }

    async evictIdleProcesses(): Promise<void> {
        return;
    }

    async stop(): Promise<void> {
        this.lockByCwd.clear();
    }
}
