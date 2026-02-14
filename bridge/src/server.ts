import { randomUUID } from "node:crypto";
import http from "node:http";

import type { Logger } from "pino";
import { WebSocket as WsWebSocket, WebSocketServer, type RawData, type WebSocket } from "ws";

import type { BridgeConfig } from "./config.js";
import type { PiProcessManager } from "./process-manager.js";
import { createPiProcessManager } from "./process-manager.js";
import type { SessionIndexer } from "./session-indexer.js";
import { createSessionIndexer } from "./session-indexer.js";
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
    sessionIndexer?: SessionIndexer;
}

interface ClientConnectionContext {
    clientId: string;
    cwd?: string;
}

interface DisconnectedClientState {
    context: ClientConnectionContext;
    timer: NodeJS.Timeout;
}

export function createBridgeServer(
    config: BridgeConfig,
    logger: Logger,
    dependencies: BridgeServerDependencies = {},
): BridgeServer {
    const startedAt = Date.now();

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
    const sessionIndexer = dependencies.sessionIndexer ??
        createSessionIndexer({
            sessionsDirectory: config.sessionDirectory,
            logger: logger.child({ component: "session-indexer" }),
        });

    const clientContexts = new Map<WebSocket, ClientConnectionContext>();
    const disconnectedClients = new Map<string, DisconnectedClientState>();

    const server = http.createServer((request, response) => {
        if (request.url === "/health") {
            const processStats = processManager.getStats();
            response.writeHead(200, { "content-type": "application/json" });
            response.end(
                JSON.stringify({
                    ok: true,
                    uptimeMs: Date.now() - startedAt,
                    processes: processStats,
                    clients: {
                        connected: clientContexts.size,
                        reconnectable: disconnectedClients.size,
                    },
                }),
            );
            return;
        }

        response.writeHead(404, { "content-type": "application/json" });
        response.end(JSON.stringify({ error: "Not Found" }));
    });

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
        const requestUrl = parseRequestUrl(request);
        const requestedClientId = sanitizeClientId(requestUrl?.searchParams.get("clientId") ?? undefined);
        const restored = restoreOrCreateContext(requestedClientId, disconnectedClients, clientContexts);

        clientContexts.set(client, restored.context);

        logger.info(
            {
                clientId: restored.context.clientId,
                resumed: restored.resumed,
                remoteAddress: request.socket.remoteAddress,
            },
            "WebSocket client connected",
        );

        client.send(
            JSON.stringify(
                createBridgeEnvelope({
                    type: "bridge_hello",
                    message: "Bridge skeleton is running",
                    clientId: restored.context.clientId,
                    resumed: restored.resumed,
                    cwd: restored.context.cwd ?? null,
                    reconnectGraceMs: config.reconnectGraceMs,
                }),
            ),
        );

        client.on("message", (data: RawData) => {
            void handleClientMessage(client, data, logger, processManager, sessionIndexer, restored.context);
        });

        client.on("close", () => {
            clientContexts.delete(client);
            scheduleDisconnectedClientRelease(
                restored.context,
                config.reconnectGraceMs,
                disconnectedClients,
                processManager,
                logger,
            );

            logger.info({ clientId: restored.context.clientId }, "WebSocket client disconnected");
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

            for (const disconnectedState of disconnectedClients.values()) {
                clearTimeout(disconnectedState.timer);
            }
            disconnectedClients.clear();

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

async function handleClientMessage(
    client: WebSocket,
    data: RawData,
    logger: Logger,
    processManager: PiProcessManager,
    sessionIndexer: SessionIndexer,
    context: ClientConnectionContext,
): Promise<void> {
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
        await handleBridgeControlMessage(client, context, envelope.payload, processManager, sessionIndexer, logger);
        return;
    }

    handleRpcEnvelope(client, context, envelope.payload, processManager, logger);
}

async function handleBridgeControlMessage(
    client: WebSocket,
    context: ClientConnectionContext,
    payload: Record<string, unknown>,
    processManager: PiProcessManager,
    sessionIndexer: SessionIndexer,
    logger: Logger,
): Promise<void> {
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

    if (messageType === "bridge_list_sessions") {
        try {
            const groupedSessions = await sessionIndexer.listSessions();

            client.send(
                JSON.stringify(
                    createBridgeEnvelope({
                        type: "bridge_sessions",
                        groups: groupedSessions,
                    }),
                ),
            );
        } catch (error: unknown) {
            logger.error({ error }, "Failed to list sessions");
            client.send(
                JSON.stringify(
                    createBridgeErrorEnvelope(
                        "session_index_failed",
                        "Failed to list sessions",
                    ),
                ),
            );
        }

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

function restoreOrCreateContext(
    requestedClientId: string | undefined,
    disconnectedClients: Map<string, DisconnectedClientState>,
    activeContexts: Map<WebSocket, ClientConnectionContext>,
): { context: ClientConnectionContext; resumed: boolean } {
    if (requestedClientId) {
        const activeClientIds = new Set(Array.from(activeContexts.values()).map((context) => context.clientId));
        if (!activeClientIds.has(requestedClientId)) {
            const disconnected = disconnectedClients.get(requestedClientId);
            if (disconnected) {
                clearTimeout(disconnected.timer);
                disconnectedClients.delete(requestedClientId);

                return {
                    context: disconnected.context,
                    resumed: true,
                };
            }

            return {
                context: { clientId: requestedClientId },
                resumed: false,
            };
        }
    }

    return {
        context: { clientId: randomUUID() },
        resumed: false,
    };
}

function scheduleDisconnectedClientRelease(
    context: ClientConnectionContext,
    reconnectGraceMs: number,
    disconnectedClients: Map<string, DisconnectedClientState>,
    processManager: PiProcessManager,
    logger: Logger,
): void {
    if (reconnectGraceMs === 0) {
        processManager.releaseClient(context.clientId);
        return;
    }

    const existing = disconnectedClients.get(context.clientId);
    if (existing) {
        clearTimeout(existing.timer);
    }

    const timer = setTimeout(() => {
        processManager.releaseClient(context.clientId);
        disconnectedClients.delete(context.clientId);

        logger.info({ clientId: context.clientId }, "Released client locks after reconnect grace period");
    }, reconnectGraceMs);

    disconnectedClients.set(context.clientId, {
        context,
        timer,
    });
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

function sanitizeClientId(clientIdRaw: string | undefined): string | undefined {
    if (!clientIdRaw) return undefined;

    const trimmedClientId = clientIdRaw.trim();
    if (!trimmedClientId) return undefined;
    if (trimmedClientId.length > 128) return undefined;

    return trimmedClientId;
}
