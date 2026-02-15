import { createHash, randomUUID, timingSafeEqual } from "node:crypto";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";

import type { Logger } from "pino";
import { WebSocket as WsWebSocket, WebSocketServer, type RawData, type WebSocket } from "ws";

import type { BridgeConfig } from "./config.js";
import type { PiProcessManager } from "./process-manager.js";
import { createPiProcessManager } from "./process-manager.js";
import type { SessionIndexer, SessionTreeFilter } from "./session-indexer.js";
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

interface TreeNavigationResultPayload {
    cancelled: boolean;
    editorText: string | null;
    currentLeafId: string | null;
    sessionPath: string | null;
    error?: string;
}

interface PendingRpcEventWaiter {
    cwd: string;
    consume: boolean;
    predicate: (payload: Record<string, unknown>) => boolean;
    resolve: (payload: Record<string, unknown>) => void;
    reject: (error: Error) => void;
    timeoutHandle: NodeJS.Timeout;
}

const PI_MOBILE_TREE_EXTENSION_PATH = path.resolve(
    fileURLToPath(new URL("./extensions/pi-mobile-tree.ts", import.meta.url)),
);
const PI_MOBILE_WORKFLOW_EXTENSION_PATH = path.resolve(
    fileURLToPath(new URL("./extensions/pi-mobile-workflows.ts", import.meta.url)),
);

const TREE_NAVIGATION_COMMAND = "pi-mobile-tree";
const TREE_NAVIGATION_STATUS_PREFIX = "pi_mobile_tree_result:";
const BRIDGE_NAVIGATE_TREE_TYPE = "bridge_navigate_tree";
const BRIDGE_TREE_NAVIGATION_RESULT_TYPE = "bridge_tree_navigation_result";
const BRIDGE_INTERNAL_RPC_TIMEOUT_MS = 10_000;

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
                        args: [
                            "--mode",
                            "rpc",
                            "--extension",
                            PI_MOBILE_TREE_EXTENSION_PATH,
                            "--extension",
                            PI_MOBILE_WORKFLOW_EXTENSION_PATH,
                        ],
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
    const runtimeLeafBySessionPath = new Map<string, string | null>();
    const pendingRpcWaiters = new Set<PendingRpcEventWaiter>();

    const awaitRpcEvent = (
        cwd: string,
        predicate: (payload: Record<string, unknown>) => boolean,
        options: { timeoutMs?: number; consume?: boolean } = {},
    ): Promise<Record<string, unknown>> => {
        const timeoutMs = options.timeoutMs ?? BRIDGE_INTERNAL_RPC_TIMEOUT_MS;
        const consume = options.consume ?? false;

        return new Promise<Record<string, unknown>>((resolve, reject) => {
            const waiter: PendingRpcEventWaiter = {
                cwd,
                consume,
                predicate,
                resolve: (payload) => {
                    clearTimeout(waiter.timeoutHandle);
                    pendingRpcWaiters.delete(waiter);
                    resolve(payload);
                },
                reject: (error) => {
                    clearTimeout(waiter.timeoutHandle);
                    pendingRpcWaiters.delete(waiter);
                    reject(error);
                },
                timeoutHandle: setTimeout(() => {
                    pendingRpcWaiters.delete(waiter);
                    reject(new Error(`Timed out waiting for RPC event after ${timeoutMs}ms`));
                }, timeoutMs),
            };

            pendingRpcWaiters.add(waiter);
        });
    };

    const drainMatchingWaiters = (event: { cwd: string; payload: Record<string, unknown> }): boolean => {
        let consumed = false;

        for (const waiter of pendingRpcWaiters) {
            if (waiter.cwd !== event.cwd) continue;
            if (!waiter.predicate(event.payload)) continue;

            waiter.resolve(event.payload);
            if (waiter.consume) {
                consumed = true;
            }
        }

        return consumed;
    };

    const server = http.createServer((request, response) => {
        if (request.url === "/health" && config.enableHealthEndpoint) {
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

    if (isUnsafeBindHost(config.host)) {
        logger.warn(
            {
                host: config.host,
            },
            "Bridge is listening on a non-loopback interface; restrict exposure with Tailscale/firewall rules",
        );
    }

    if (!config.enableHealthEndpoint) {
        logger.info("Health endpoint is disabled (BRIDGE_ENABLE_HEALTH_ENDPOINT=false)");
    } else if (!isLoopbackHost(config.host)) {
        logger.warn(
            "Health endpoint is enabled on a non-loopback host; disable it unless remote health checks are required",
        );
    }

    processManager.setMessageHandler((event) => {
        const consumedByInternalWaiter = drainMatchingWaiters(event);

        if (isSuccessfulRpcResponse(event.payload, "switch_session") ||
            isSuccessfulRpcResponse(event.payload, "new_session") ||
            isSuccessfulRpcResponse(event.payload, "fork")) {
            runtimeLeafBySessionPath.clear();
        }

        if (consumedByInternalWaiter) {
            return;
        }

        const rpcEnvelope = JSON.stringify(createRpcEnvelope(event.payload));

        for (const [client, context] of clientContexts.entries()) {
            if (client.readyState !== WsWebSocket.OPEN) continue;
            if (!canReceiveRpcEvent(context, event.cwd, processManager)) continue;

            client.send(rpcEnvelope);
        }
    });

    server.on("upgrade", (request, socket, head) => {
        const requestUrl = parseRequestUrl(request);

        if (requestUrl?.pathname !== "/ws") {
            socket.destroy();
            return;
        }

        const providedToken = extractToken(request);
        if (!secureTokenEquals(providedToken, config.authToken)) {
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
            void handleClientMessage(
                client,
                data,
                logger,
                processManager,
                sessionIndexer,
                restored.context,
                awaitRpcEvent,
                runtimeLeafBySessionPath,
            );
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

            for (const waiter of pendingRpcWaiters) {
                waiter.reject(new Error("Bridge server stopped"));
            }
            pendingRpcWaiters.clear();
            runtimeLeafBySessionPath.clear();

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
    awaitRpcEvent: (
        cwd: string,
        predicate: (payload: Record<string, unknown>) => boolean,
        options?: { timeoutMs?: number; consume?: boolean },
    ) => Promise<Record<string, unknown>>,
    runtimeLeafBySessionPath: Map<string, string | null>,
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
        await handleBridgeControlMessage(
            client,
            context,
            envelope.payload,
            processManager,
            sessionIndexer,
            logger,
            awaitRpcEvent,
            runtimeLeafBySessionPath,
        );
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
    awaitRpcEvent: (
        cwd: string,
        predicate: (payload: Record<string, unknown>) => boolean,
        options?: { timeoutMs?: number; consume?: boolean },
    ) => Promise<Record<string, unknown>>,
    runtimeLeafBySessionPath: Map<string, string | null>,
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

    if (messageType === "bridge_get_session_tree") {
        const sessionPath = typeof payload.sessionPath === "string" ? payload.sessionPath : undefined;
        if (!sessionPath) {
            client.send(
                JSON.stringify(
                    createBridgeErrorEnvelope(
                        "invalid_session_path",
                        "sessionPath must be a non-empty string",
                    ),
                ),
            );
            return;
        }

        const requestedFilterRaw =
            typeof payload.filter === "string" ? payload.filter : undefined;
        if (requestedFilterRaw && !isSessionTreeFilter(requestedFilterRaw)) {
            client.send(
                JSON.stringify(
                    createBridgeErrorEnvelope(
                        "invalid_tree_filter",
                        "filter must be one of: default, all, no-tools, user-only, labeled-only",
                    ),
                ),
            );
            return;
        }

        const requestedFilter = requestedFilterRaw as SessionTreeFilter | undefined;

        try {
            const tree = await sessionIndexer.getSessionTree(sessionPath, requestedFilter);
            const runtimeLeafId = runtimeLeafBySessionPath.get(tree.sessionPath);

            client.send(
                JSON.stringify(
                    createBridgeEnvelope({
                        type: "bridge_session_tree",
                        sessionPath: tree.sessionPath,
                        rootIds: tree.rootIds,
                        currentLeafId: runtimeLeafId ?? tree.currentLeafId ?? null,
                        entries: tree.entries,
                    }),
                ),
            );
        } catch (error: unknown) {
            logger.error({ error, sessionPath }, "Failed to build session tree");
            client.send(
                JSON.stringify(
                    createBridgeErrorEnvelope(
                        "session_tree_failed",
                        "Failed to build session tree",
                    ),
                ),
            );
        }

        return;
    }

    if (messageType === BRIDGE_NAVIGATE_TREE_TYPE) {
        const cwd = getRequestedCwd(payload, context);
        if (!cwd) {
            client.send(
                JSON.stringify(
                    createBridgeErrorEnvelope(
                        "missing_cwd_context",
                        "Set cwd first via bridge_set_cwd or include cwd in bridge_navigate_tree",
                    ),
                ),
            );
            return;
        }

        if (!processManager.hasControl(context.clientId, cwd)) {
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

        const entryId = typeof payload.entryId === "string" ? payload.entryId.trim() : "";
        if (!entryId) {
            client.send(
                JSON.stringify(
                    createBridgeErrorEnvelope(
                        "invalid_tree_entry_id",
                        "entryId must be a non-empty string",
                    ),
                ),
            );
            return;
        }

        try {
            const navigationResult = await navigateTreeUsingCommand({
                cwd,
                entryId,
                processManager,
                awaitRpcEvent,
            });

            if (navigationResult.sessionPath) {
                runtimeLeafBySessionPath.set(
                    navigationResult.sessionPath,
                    navigationResult.currentLeafId,
                );
            }

            client.send(
                JSON.stringify(
                    createBridgeEnvelope({
                        type: BRIDGE_TREE_NAVIGATION_RESULT_TYPE,
                        cancelled: navigationResult.cancelled,
                        editorText: navigationResult.editorText,
                        currentLeafId: navigationResult.currentLeafId,
                        sessionPath: navigationResult.sessionPath,
                    }),
                ),
            );
        } catch (error: unknown) {
            const message = error instanceof Error ? error.message : "Failed to navigate tree";
            logger.error({ error, cwd, entryId }, "Failed to navigate tree in active session");
            client.send(
                JSON.stringify(
                    createBridgeErrorEnvelope(
                        "tree_navigation_failed",
                        message,
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

async function navigateTreeUsingCommand(options: {
    cwd: string;
    entryId: string;
    processManager: PiProcessManager;
    awaitRpcEvent: (
        cwd: string,
        predicate: (payload: Record<string, unknown>) => boolean,
        options?: { timeoutMs?: number; consume?: boolean },
    ) => Promise<Record<string, unknown>>;
}): Promise<TreeNavigationResultPayload> {
    const { cwd, entryId, processManager, awaitRpcEvent } = options;

    const getCommandsRequestId = randomUUID();
    const getCommandsResponsePromise = awaitRpcEvent(
        cwd,
        (payload) => isRpcResponseForId(payload, getCommandsRequestId),
        { consume: true },
    );

    processManager.sendRpc(cwd, {
        id: getCommandsRequestId,
        type: "get_commands",
    });

    const getCommandsResponse = await getCommandsResponsePromise;
    ensureSuccessfulRpcResponse(getCommandsResponse, "get_commands");

    if (!hasTreeNavigationCommand(getCommandsResponse, TREE_NAVIGATION_COMMAND)) {
        throw new Error("Tree navigation command is unavailable in this runtime");
    }

    const navigationRequestId = randomUUID();
    const operationId = randomUUID();
    const statusKey = `${TREE_NAVIGATION_STATUS_PREFIX}${operationId}`;

    const navigationResponsePromise = awaitRpcEvent(
        cwd,
        (payload) => isRpcResponseForId(payload, navigationRequestId),
        { consume: true },
    );

    const statusResponsePromise = awaitRpcEvent(
        cwd,
        (payload) => isTreeNavigationStatusEvent(payload, statusKey),
        { consume: true },
    );

    processManager.sendRpc(cwd, {
        id: navigationRequestId,
        type: "prompt",
        message: `/${TREE_NAVIGATION_COMMAND} ${entryId} ${statusKey}`,
    });

    const navigationResponse = await navigationResponsePromise;
    ensureSuccessfulRpcResponse(navigationResponse, "prompt");

    const statusResponse = await statusResponsePromise;
    return parseTreeNavigationResult(statusResponse);
}

function hasTreeNavigationCommand(responsePayload: Record<string, unknown>, commandName: string): boolean {
    const data = asRecord(responsePayload.data);
    const commands = Array.isArray(data?.commands) ? data.commands : [];

    return commands.some((command) => {
        const commandObject = asRecord(command);
        return commandObject?.name === commandName;
    });
}

function isTreeNavigationStatusEvent(payload: Record<string, unknown>, statusKey: string): boolean {
    return payload.type === "extension_ui_request" && payload.method === "setStatus" &&
        payload.statusKey === statusKey && typeof payload.statusText === "string";
}

function parseTreeNavigationResult(payload: Record<string, unknown>): TreeNavigationResultPayload {
    const statusText = typeof payload.statusText === "string" ? payload.statusText : undefined;
    if (!statusText) {
        throw new Error("Tree navigation command did not return status text");
    }

    let parsed: unknown;
    try {
        parsed = JSON.parse(statusText);
    } catch {
        throw new Error("Tree navigation command returned invalid JSON payload");
    }

    const parsedObject = asRecord(parsed);
    if (!parsedObject) {
        throw new Error("Tree navigation command returned an invalid payload shape");
    }

    const cancelled = parsedObject.cancelled === true;
    const editorText = typeof parsedObject.editorText === "string" ? parsedObject.editorText : null;
    const currentLeafId = typeof parsedObject.currentLeafId === "string" ? parsedObject.currentLeafId : null;
    const sessionPath = typeof parsedObject.sessionPath === "string" ? parsedObject.sessionPath : null;
    const error = typeof parsedObject.error === "string" ? parsedObject.error : undefined;

    if (error) {
        throw new Error(error);
    }

    return {
        cancelled,
        editorText,
        currentLeafId,
        sessionPath,
    };
}

function ensureSuccessfulRpcResponse(payload: Record<string, unknown>, expectedCommand: string): void {
    const command = typeof payload.command === "string" ? payload.command : "unknown";
    const success = payload.success === true;

    if (command !== expectedCommand) {
        throw new Error(`Unexpected RPC response command: ${command}`);
    }

    if (!success) {
        const errorMessage = typeof payload.error === "string" ? payload.error : `RPC command ${command} failed`;
        throw new Error(errorMessage);
    }
}

function isRpcResponseForId(payload: Record<string, unknown>, expectedId: string): boolean {
    return payload.type === "response" && payload.id === expectedId;
}

function isSuccessfulRpcResponse(payload: Record<string, unknown>, command: string): boolean {
    return payload.type === "response" && payload.command === command && payload.success === true;
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
    if (typeof value !== "object" || value === null || Array.isArray(value)) {
        return undefined;
    }

    return value as Record<string, unknown>;
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

function canReceiveRpcEvent(
    context: ClientConnectionContext,
    cwd: string,
    processManager: PiProcessManager,
): boolean {
    if (!context.cwd || context.cwd !== cwd) {
        return false;
    }

    return processManager.hasControl(context.clientId, cwd);
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

function extractToken(request: http.IncomingMessage): string | undefined {
    return getBearerToken(request.headers.authorization) || getHeaderToken(request);
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

function secureTokenEquals(
    providedToken: string | undefined,
    expectedToken: string,
): boolean {
    if (!providedToken) {
        return false;
    }

    const providedHash = createHash("sha256").update(providedToken).digest();
    const expectedHash = createHash("sha256").update(expectedToken).digest();
    return timingSafeEqual(providedHash, expectedHash);
}

function isLoopbackHost(host: string): boolean {
    return host === "127.0.0.1" || host === "::1" || host === "localhost";
}

function isUnsafeBindHost(host: string): boolean {
    return !isLoopbackHost(host);
}

function isSessionTreeFilter(value: string): value is SessionTreeFilter {
    return value === "default" || value === "all" || value === "no-tools" || value === "user-only" ||
        value === "labeled-only";
}

function sanitizeClientId(clientIdRaw: string | undefined): string | undefined {
    if (!clientIdRaw) return undefined;

    const trimmedClientId = clientIdRaw.trim();
    if (!trimmedClientId) return undefined;
    if (trimmedClientId.length > 128) return undefined;

    return trimmedClientId;
}
