import { randomUUID } from "node:crypto";
import http from "node:http";

import type { Logger } from "pino";
import { WebSocket as WsWebSocket, WebSocketServer, type RawData, type WebSocket } from "ws";

import type { BridgeConfig } from "./config.js";
import type { PiProcessManager } from "./process-manager.js";
import { createPiProcessManager } from "./process-manager.js";
import {
    createBridgeEnvelope,
    createBridgeErrorEnvelope,
    createRpcEnvelope,
    parseBridgeEnvelope,
} from "./protocol.js";
import { createPiRpcForwarder } from "./rpc-forwarder.js";

export interface BridgeServerStartInfo {
    host: string;
    port: number;
}

export interface BridgeServer {
    start(): Promise<BridgeServerStartInfo>;
    stop(): Promise<void>;
}

interface BridgeServerDependencies {
    processManager?: PiProcessManager;
}

interface ClientConnectionContext {
    clientId: string;
    cwd?: string;
}

export function createBridgeServer(
    config: BridgeConfig,
    logger: Logger,
    dependencies: BridgeServerDependencies = {},
): BridgeServer {
    const server = http.createServer((request, response) => {
        if (request.url === "/health") {
            response.writeHead(200, { "content-type": "application/json" });
            response.end(JSON.stringify({ ok: true }));
            return;
        }

        response.writeHead(404, { "content-type": "application/json" });
        response.end(JSON.stringify({ error: "Not Found" }));
    });

    const wsServer = new WebSocketServer({ noServer: true });
    const processManager = dependencies.processManager ??
        createPiProcessManager({
            idleTtlMs: config.processIdleTtlMs,
            logger: logger.child({ component: "process-manager" }),
            forwarderFactory: (cwd: string) => {
                return createPiRpcForwarder(
                    {
                        command: "pi",
                        args: ["--mode", "rpc"],
                        cwd,
                    },
                    logger.child({ component: "rpc-forwarder", cwd }),
                );
            },
        });

    const clientContexts = new Map<WebSocket, ClientConnectionContext>();

    processManager.setMessageHandler((event) => {
        const rpcEnvelope = JSON.stringify(createRpcEnvelope(event.payload));

        for (const [client, context] of clientContexts.entries()) {
            if (context.cwd !== event.cwd) continue;
            if (client.readyState !== WsWebSocket.OPEN) continue;

            client.send(rpcEnvelope);
        }
    });

    server.on("upgrade", (request, socket, head) => {
        const requestUrl = parseRequestUrl(request);

        if (requestUrl?.pathname !== "/ws") {
            socket.destroy();
            return;
        }

        const providedToken = extractToken(request, requestUrl);
        if (providedToken !== config.authToken) {
            socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n");
            socket.destroy();
            logger.warn(
                {
                    remoteAddress: request.socket.remoteAddress,
                },
                "Rejected websocket connection due to invalid token",
            );
            return;
        }

        wsServer.handleUpgrade(request, socket, head, (client: WebSocket) => {
            wsServer.emit("connection", client, request);
        });
    });

    wsServer.on("connection", (client: WebSocket, request: http.IncomingMessage) => {
        const context: ClientConnectionContext = {
            clientId: randomUUID(),
        };
        clientContexts.set(client, context);

        logger.info(
            {
                clientId: context.clientId,
                remoteAddress: request.socket.remoteAddress,
            },
            "WebSocket client connected",
        );

        client.send(
            JSON.stringify(
                createBridgeEnvelope({
                    type: "bridge_hello",
                    message: "Bridge skeleton is running",
                }),
            ),
        );

        client.on("message", (data: RawData) => {
            handleClientMessage(client, data, logger, processManager, context);
        });

        client.on("close", () => {
            processManager.releaseClient(context.clientId);
            clientContexts.delete(client);

            logger.info({ clientId: context.clientId }, "WebSocket client disconnected");
        });
    });

    return {
        async start(): Promise<BridgeServerStartInfo> {
            await new Promise<void>((resolve) => {
                server.listen(config.port, config.host, () => {
                    resolve();
                });
            });

            const addressInfo = server.address();
            if (!addressInfo || typeof addressInfo === "string") {
                throw new Error("Failed to resolve bridge server address");
            }

            logger.info(
                {
                    host: addressInfo.address,
                    port: addressInfo.port,
                },
                "Bridge server listening",
            );

            return {
                host: addressInfo.address,
                port: addressInfo.port,
            };
        },
        async stop(): Promise<void> {
            wsServer.clients.forEach((client: WebSocket) => {
                client.close(1001, "Server shutting down");
            });

            await processManager.stop();

            await new Promise<void>((resolve, reject) => {
                wsServer.close((error?: Error) => {
                    if (error) {
                        reject(error);
                        return;
                    }

                    server.close((closeError) => {
                        if (closeError) {
                            reject(closeError);
                            return;
                        }

                        resolve();
                    });
                });
            });

            logger.info("Bridge server stopped");
        },
    };
}

function handleClientMessage(
    client: WebSocket,
    data: RawData,
    logger: Logger,
    processManager: PiProcessManager,
    context: ClientConnectionContext,
): void {
    const dataAsString = asUtf8String(data);
    const parsedEnvelope = parseBridgeEnvelope(dataAsString);

    if (!parsedEnvelope.success) {
        client.send(
            JSON.stringify(
                createBridgeErrorEnvelope(
                    "malformed_envelope",
                    parsedEnvelope.error,
                ),
            ),
        );

        logger.warn(
            {
                clientId: context.clientId,
                error: parsedEnvelope.error,
            },
            "Received malformed envelope",
        );
        return;
    }

    const envelope = parsedEnvelope.envelope;

    if (envelope.channel === "bridge") {
        handleBridgeControlMessage(client, context, envelope.payload, processManager);
        return;
    }

    handleRpcEnvelope(client, context, envelope.payload, processManager, logger);
}

function handleBridgeControlMessage(
    client: WebSocket,
    context: ClientConnectionContext,
    payload: Record<string, unknown>,
    processManager: PiProcessManager,
): void {
    const messageType = payload.type;

    if (messageType === "bridge_ping") {
        client.send(
            JSON.stringify(
                createBridgeEnvelope({
                    type: "bridge_pong",
                }),
            ),
        );
        return;
    }

    if (messageType === "bridge_set_cwd") {
        const cwd = payload.cwd;
        if (typeof cwd !== "string" || cwd.trim().length === 0) {
            client.send(JSON.stringify(createBridgeErrorEnvelope("invalid_cwd", "cwd must be a non-empty string")));
            return;
        }

        context.cwd = cwd;

        client.send(
            JSON.stringify(
                createBridgeEnvelope({
                    type: "bridge_cwd_set",
                    cwd,
                }),
            ),
        );
        return;
    }

    if (messageType === "bridge_acquire_control") {
        const cwd = getRequestedCwd(payload, context);
        if (!cwd) {
            client.send(
                JSON.stringify(
                    createBridgeErrorEnvelope(
                        "missing_cwd_context",
                        "Set cwd first via bridge_set_cwd or include cwd in bridge_acquire_control",
                    ),
                ),
            );
            return;
        }

        const sessionPath = typeof payload.sessionPath === "string" ? payload.sessionPath : undefined;
        const lockResult = processManager.acquireControl({
            clientId: context.clientId,
            cwd,
            sessionPath,
        });

        if (!lockResult.success) {
            client.send(
                JSON.stringify(
                    createBridgeErrorEnvelope(
                        "control_lock_denied",
                        lockResult.reason ?? "Control lock denied",
                    ),
                ),
            );
            return;
        }

        context.cwd = cwd;

        client.send(
            JSON.stringify(
                createBridgeEnvelope({
                    type: "bridge_control_acquired",
                    cwd,
                    sessionPath: sessionPath ?? null,
                }),
            ),
        );
        return;
    }

    if (messageType === "bridge_release_control") {
        const cwd = getRequestedCwd(payload, context);
        if (!cwd) {
            client.send(
                JSON.stringify(
                    createBridgeErrorEnvelope(
                        "missing_cwd_context",
                        "Set cwd first via bridge_set_cwd or include cwd in bridge_release_control",
                    ),
                ),
            );
            return;
        }

        const sessionPath = typeof payload.sessionPath === "string" ? payload.sessionPath : undefined;
        processManager.releaseControl(context.clientId, cwd, sessionPath);

        client.send(
            JSON.stringify(
                createBridgeEnvelope({
                    type: "bridge_control_released",
                    cwd,
                    sessionPath: sessionPath ?? null,
                }),
            ),
        );
        return;
    }

    client.send(
        JSON.stringify(
            createBridgeErrorEnvelope(
                "unsupported_bridge_message",
                "Unsupported bridge payload type",
            ),
        ),
    );
}

function handleRpcEnvelope(
    client: WebSocket,
    context: ClientConnectionContext,
    payload: Record<string, unknown>,
    processManager: PiProcessManager,
    logger: Logger,
): void {
    if (!context.cwd) {
        client.send(
            JSON.stringify(
                createBridgeErrorEnvelope(
                    "missing_cwd_context",
                    "Set cwd first via bridge_set_cwd",
                ),
            ),
        );
        return;
    }

    if (!processManager.hasControl(context.clientId, context.cwd)) {
        client.send(
            JSON.stringify(
                createBridgeErrorEnvelope(
                    "control_lock_required",
                    "Acquire control first via bridge_acquire_control",
                ),
            ),
        );
        return;
    }

    if (typeof payload.type !== "string") {
        client.send(
            JSON.stringify(
                createBridgeErrorEnvelope(
                    "invalid_rpc_payload",
                    "RPC payload must contain a string type field",
                ),
            ),
        );
        return;
    }

    try {
        processManager.sendRpc(context.cwd, payload);
    } catch (error: unknown) {
        logger.error({ error, clientId: context.clientId, cwd: context.cwd }, "Failed to forward RPC payload");

        client.send(
            JSON.stringify(
                createBridgeErrorEnvelope(
                    "rpc_forward_failed",
                    "Failed to forward RPC payload",
                ),
            ),
        );
    }
}

function getRequestedCwd(payload: Record<string, unknown>, context: ClientConnectionContext): string | undefined {
    if (typeof payload.cwd === "string" && payload.cwd.trim().length > 0) {
        return payload.cwd;
    }

    return context.cwd;
}

function asUtf8String(data: RawData): string {
    if (typeof data === "string") return data;

    if (Array.isArray(data)) {
        return Buffer.concat(data).toString("utf-8");
    }

    if (data instanceof ArrayBuffer) {
        return Buffer.from(data).toString("utf-8");
    }

    return data.toString("utf-8");
}

function parseRequestUrl(request: http.IncomingMessage): URL | undefined {
    if (!request.url) return undefined;

    const base = `http://${request.headers.host || "localhost"}`;

    return new URL(request.url, base);
}

function extractToken(request: http.IncomingMessage, requestUrl: URL): string | undefined {
    const fromHeader = getBearerToken(request.headers.authorization) || getHeaderToken(request);
    if (fromHeader) return fromHeader;

    return requestUrl.searchParams.get("token") || undefined;
}

function getBearerToken(authorizationHeader: string | undefined): string | undefined {
    if (!authorizationHeader) return undefined;
    const bearerPrefix = "Bearer ";
    if (!authorizationHeader.startsWith(bearerPrefix)) return undefined;

    const token = authorizationHeader.slice(bearerPrefix.length).trim();
    if (!token) return undefined;

    return token;
}

function getHeaderToken(request: http.IncomingMessage): string | undefined {
    const tokenHeader = request.headers["x-bridge-token"];

    if (!tokenHeader) return undefined;
    if (typeof tokenHeader === "string") return tokenHeader;

    return tokenHeader[0];
}
