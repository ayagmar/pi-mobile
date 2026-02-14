import { afterEach, describe, expect, it } from "vitest";
import { WebSocket, type ClientOptions, type RawData } from "ws";

import { createLogger } from "../src/logger.js";
import type { PiRpcForwarder, PiRpcForwarderMessage } from "../src/rpc-forwarder.js";
import type { BridgeServer } from "../src/server.js";
import { createBridgeServer } from "../src/server.js";

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

        ws.send("{ malformed-json");

        const errorEnvelope = await waitForEnvelope(ws, (envelope) => {
            return envelope.payload?.type === "bridge_error" && envelope.payload.code === "malformed_envelope";
        });

        expect(errorEnvelope.channel).toBe("bridge");
        expect(errorEnvelope.payload?.type).toBe("bridge_error");
        expect(errorEnvelope.payload?.code).toBe("malformed_envelope");

        ws.close();
    });

    it("forwards rpc payloads to the rpc subprocess channel", async () => {
        const fakeRpcForwarder = new FakeRpcForwarder();
        const { baseUrl, server } = await startBridgeServer({ rpcForwarder: fakeRpcForwarder });
        bridgeServer = server;

        const ws = await connectWebSocket(baseUrl, {
            headers: {
                authorization: "Bearer bridge-token",
            },
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

        const rpcEnvelope = await waitForEnvelope(ws, (envelope) => {
            return envelope.channel === "rpc" && envelope.payload?.id === "req-1";
        });

        expect(fakeRpcForwarder.sentPayloads).toEqual([
            {
                id: "req-1",
                type: "get_state",
            },
        ]);
        expect(rpcEnvelope.channel).toBe("rpc");
        expect(rpcEnvelope.payload?.type).toBe("response");
        expect(rpcEnvelope.payload?.command).toBe("get_state");

        ws.close();
    });
});

async function startBridgeServer(deps?: { rpcForwarder?: PiRpcForwarder }): Promise<{ baseUrl: string; server: BridgeServer }> {
    const logger = createLogger("silent");
    const server = createBridgeServer(
        {
            host: "127.0.0.1",
            port: 0,
            logLevel: "silent",
            authToken: "bridge-token",
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

class FakeRpcForwarder implements PiRpcForwarder {
    sentPayloads: Record<string, unknown>[] = [];
    private messageHandler: (payload: PiRpcForwarderMessage) => void = () => {};

    setMessageHandler(handler: (payload: PiRpcForwarderMessage) => void): void {
        this.messageHandler = handler;
    }

    send(payload: Record<string, unknown>): void {
        this.sentPayloads.push(payload);

        this.messageHandler({
            id: payload.id,
            type: "response",
            command: payload.type,
            success: true,
            data: {
                forwarded: true,
            },
        });
    }

    async stop(): Promise<void> {
        return;
    }
}
