import { execFile } from "node:child_process";
import { createHash, randomUUID, timingSafeEqual } from "node:crypto";
import { readFileSync } from "node:fs";
import { access, mkdir, unlink, writeFile } from "node:fs/promises";
import http from "node:http";
import https from "node:https";
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
    buildShareLandingPage,
    buildWebShareUrl,
    isValidShareReference,
    SHARE_LANDING_HEADERS,
} from "./share-links.js";
import type { ShareReferenceStore } from "./share-reference-store.js";
import {
    createShareReferenceStore,
    ShareStateUnavailableError,
} from "./share-reference-store.js";
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
    shareReferenceStore?: ShareReferenceStore;
    probePiVersion?: (command: string) => Promise<string>;
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

interface RuntimeLeafOverride {
    currentLeafId: string | null;
    cwd: string;
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
const BRIDGE_GET_SESSION_FRESHNESS_TYPE = "bridge_get_session_freshness";
const BRIDGE_SESSION_FRESHNESS_TYPE = "bridge_session_freshness";
const BRIDGE_IMPORT_SESSION_JSONL_TYPE = "bridge_import_session_jsonl";
const BRIDGE_SESSION_IMPORTED_TYPE = "bridge_session_imported";
const BRIDGE_SESSION_INVALIDATED_TYPE = "bridge_session_invalidated";
const BRIDGE_INTERNAL_RPC_TIMEOUT_MS = 10_000;
const PI_VERSION_PROBE_TIMEOUT_MS = 3_000;

export async function probePiVersion(command: string): Promise<string> {
    return await new Promise<string>((resolve, reject) => {
        execFile(command, ["--version"], { timeout: PI_VERSION_PROBE_TIMEOUT_MS }, (error, stdout) => {
            if (error) {
                reject(new Error(`Unable to run ${command} --version: ${error.message}`));
                return;
            }

            const version = stdout.trim();
            if (!version) {
                reject(new Error(`${command} --version returned no version`));
                return;
            }

            resolve(version);
        });
    });
}

export function buildPiRpcArgs(sessionDirectory: string): string[] {
    return [
        "--mode",
        "rpc",
        "--session-dir",
        sessionDirectory,
        "--extension",
        PI_MOBILE_TREE_EXTENSION_PATH,
        "--extension",
        PI_MOBILE_WORKFLOW_EXTENSION_PATH,
    ];
}

export function createBridgeServer(
    config: BridgeConfig,
    logger: Logger,
    dependencies: BridgeServerDependencies = {},
): BridgeServer {
    const startedAt = Date.now();

    const wsServer = new WebSocketServer({
        noServer: true,
        maxPayload: config.websocketMaxPayloadBytes,
    });
    const piVersionProbe = dependencies.probePiVersion ?? probePiVersion;
    const processManager = dependencies.processManager ??
        createPiProcessManager({
            idleTtlMs: config.processIdleTtlMs,
            logger: logger.child({ component: "process-manager" }),
            forwarderFactory: (cwd: string) => {
                return createPiRpcForwarder(
                    {
                        command: config.piCommand,
                        args: buildPiRpcArgs(config.sessionDirectory),
                        cwd,
                    },
                    logger.child({ component: "rpc-forwarder" }),
                );
            },
        });
    const sessionIndexer = dependencies.sessionIndexer ??
        createSessionIndexer({
            sessionsDirectory: config.sessionDirectory,
            logger: logger.child({ component: "session-indexer" }),
        });
    const shareReferenceStore = dependencies.shareReferenceStore ??
        createShareReferenceStore({
            stateDirectory: config.stateDirectory ?? path.join(process.env.HOME ?? ".", ".pi-mobile"),
        });

    const clientContexts = new Map<WebSocket, ClientConnectionContext>();
    const disconnectedClients = new Map<string, DisconnectedClientState>();
    const runtimeLeafBySessionPath = new Map<string, RuntimeLeafOverride>();
    const pendingRpcWaiters = new Set<PendingRpcEventWaiter>();

    const clearRuntimeLeafOverridesForCwd = (cwd: string): void => {
        for (const [sessionPath, override] of runtimeLeafBySessionPath.entries()) {
            if (override.cwd === cwd) {
                runtimeLeafBySessionPath.delete(sessionPath);
            }
        }
    };

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

    const requestListener = (request: http.IncomingMessage, response: http.ServerResponse): void => {
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

        const landingReference = config.shareOrigin ? parseLandingReference(request.url) : undefined;
        if (landingReference && config.shareOrigin) {
            response.writeHead(200, SHARE_LANDING_HEADERS);
            response.end(buildShareLandingPage(config.shareOrigin, landingReference));
            return;
        }

        response.writeHead(404, { "content-type": "application/json", "cache-control": "no-store" });
        response.end(JSON.stringify({ error: "Not Found" }));
    };

    if (Boolean(config.tlsCertFile) !== Boolean(config.tlsKeyFile)) {
        throw new Error(
            "BRIDGE_TLS_CERT_FILE and BRIDGE_TLS_KEY_FILE must be set together to enable TLS",
        );
    }

    const server = config.tlsCertFile && config.tlsKeyFile
        ? https.createServer(loadTlsCredentials(config.tlsCertFile, config.tlsKeyFile), requestListener)
        : http.createServer(requestListener);

    if (isUnsafeBindHost(config.host)) {
        logger.warn(
            "Bridge is listening on a non-loopback interface; restrict exposure with Tailscale/firewall rules",
        );
    }

    if (config.tlsCertFile && config.tlsKeyFile) {
        logger.info("TLS is enabled; websocket clients must connect over wss://");
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

        if (isSuccessfulRpcResponse(event.payload, "prompt")) {
            clearRuntimeLeafOverridesForCwd(event.cwd);
        }

        if (isSessionMutationEvent(event.payload)) {
            const invalidationEnvelope = JSON.stringify(
                createBridgeEnvelope({
                    type: BRIDGE_SESSION_INVALIDATED_TYPE,
                    reason: sessionMutationReason(event.payload),
                }),
            );
            for (const [client, context] of clientContexts.entries()) {
                if (client.readyState !== WsWebSocket.OPEN) continue;
                if (!canReceiveRpcEvent(context, event.cwd, processManager)) continue;
                client.send(invalidationEnvelope);
            }
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
            logger.warn("Rejected websocket connection due to invalid token");
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

        logger.info({ resumed: restored.resumed }, "WebSocket client connected");

        client.send(
            JSON.stringify(
                createBridgeEnvelope({
                    type: "bridge_hello",
                    message: "Bridge skeleton is running",
                    clientId: restored.context.clientId,
                    resumed: restored.resumed,
                    cwd: restored.context.cwd ?? null,
                    reconnectGraceMs: config.reconnectGraceMs,
                    shareOrigin: config.shareOrigin ?? null,
                }),
            ),
        );

        let controlPlaneQueue = Promise.resolve();
        client.on("message", (data: RawData) => {
            const dispatch = (): Promise<void> =>
                handleClientMessage(
                    client,
                    data,
                    logger,
                    processManager,
                    sessionIndexer,
                    shareReferenceStore,
                    restored.context,
                    config,
                    awaitRpcEvent,
                    runtimeLeafBySessionPath,
                );
            const parsedEnvelope = parseBridgeEnvelope(asUtf8String(data));
            if (parsedEnvelope.success && parsedEnvelope.envelope.channel === "bridge") {
                controlPlaneQueue = controlPlaneQueue
                    .then(dispatch)
                    .catch(() => {
                        logger.error("Control-plane message failed");
                    });
            } else {
                void dispatch();
            }
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

            logger.info("WebSocket client disconnected");
        });
    });

    return {
        async start(): Promise<BridgeServerStartInfo> {
            const piVersion = await piVersionProbe(config.piCommand);
            logger.info({ version: piVersion }, "Detected Pi executable");

            await new Promise<void>((resolve) => {
                server.listen(config.port, config.host, () => {
                    resolve();
                });
            });

            const addressInfo = server.address();
            if (!addressInfo || typeof addressInfo === "string") {
                throw new Error("Failed to resolve bridge server address");
            }

            logger.info({ port: addressInfo.port }, "Bridge server listening");

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
    shareReferenceStore: ShareReferenceStore,
    context: ClientConnectionContext,
    config: BridgeConfig,
    awaitRpcEvent: (
        cwd: string,
        predicate: (payload: Record<string, unknown>) => boolean,
        options?: { timeoutMs?: number; consume?: boolean },
    ) => Promise<Record<string, unknown>>,
    runtimeLeafBySessionPath: Map<string, RuntimeLeafOverride>,
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

        logger.warn("Received malformed envelope");
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
            shareReferenceStore,
            logger,
            config,
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
    shareReferenceStore: ShareReferenceStore,
    logger: Logger,
    config: BridgeConfig,
    awaitRpcEvent: (
        cwd: string,
        predicate: (payload: Record<string, unknown>) => boolean,
        options?: { timeoutMs?: number; consume?: boolean },
    ) => Promise<Record<string, unknown>>,
    runtimeLeafBySessionPath: Map<string, RuntimeLeafOverride>,
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

    if (messageType === "bridge_get_or_create_session_share") {
        const sessionPath = normalizeOptionalString(payload.sessionPath);
        if (!sessionPath) {
            sendShareError(client, "session_not_shareable", "This session cannot be shared");
            return;
        }
        try {
            const sessions = (await sessionIndexer.listSessions()).flatMap((group) => group.sessions);
            const matching = sessions.filter((session) => session.sessionPath === sessionPath);
            const session = matching.length === 1 ? matching[0] : undefined;
            if (!session?.sessionId || session.isSessionIdUnique !== true) {
                sendShareError(
                    client,
                    session?.sessionId ? "session_identity_ambiguous" : "session_not_shareable",
                    session?.sessionId ? "This session identity is ambiguous" : "This session cannot be shared",
                );
                return;
            }
            const shareReference = await shareReferenceStore.getOrCreate(session.sessionId);
            client.send(JSON.stringify(createBridgeEnvelope({
                type: "bridge_session_share",
                shareReference,
                webUrl: config.shareOrigin ? buildWebShareUrl(config.shareOrigin, shareReference) : null,
            })));
        } catch (error: unknown) {
            handleShareFailure(client, error, "Could not create the session share");
        }
        return;
    }

    if (messageType === "bridge_resolve_session_share") {
        const shareReference = payload.shareReference;
        if (!isValidShareReference(shareReference)) {
            sendShareError(client, "share_not_found", "Shared session is unavailable");
            return;
        }
        try {
            const sessionId = await shareReferenceStore.resolve(shareReference);
            if (!sessionId) {
                sendShareError(client, "share_not_found", "Shared session is unavailable");
                return;
            }
            const sessions = (await sessionIndexer.listSessions()).flatMap((group) => group.sessions);
            const matches = sessions.filter((session) =>
                session.sessionId === sessionId && session.isSessionIdUnique === true);
            if (matches.length !== 1) {
                sendShareError(client, "share_not_found", "Shared session is unavailable");
                return;
            }
            client.send(JSON.stringify(createBridgeEnvelope({
                type: "bridge_session_share_resolved",
                session: matches[0],
            })));
        } catch (error: unknown) {
            handleShareFailure(client, error, "Could not resolve the session share");
        }
        return;
    }

    if (messageType === "bridge_revoke_session_share") {
        const sessionPath = normalizeOptionalString(payload.sessionPath);
        if (!sessionPath) {
            sendShareError(client, "session_not_shareable", "This session cannot be shared");
            return;
        }
        try {
            const sessions = (await sessionIndexer.listSessions()).flatMap((group) => group.sessions);
            const matching = sessions.filter((session) => session.sessionPath === sessionPath);
            const session = matching.length === 1 ? matching[0] : undefined;
            if (!session?.sessionId || session.isSessionIdUnique !== true) {
                sendShareError(client, "session_not_shareable", "This session cannot be shared");
                return;
            }
            await shareReferenceStore.revoke(session.sessionId);
            client.send(JSON.stringify(createBridgeEnvelope({ type: "bridge_session_share_revoked" })));
        } catch (error: unknown) {
            handleShareFailure(client, error, "Could not revoke the session share");
        }
        return;
    }

    if (messageType === "bridge_list_sessions") {
        const startedAt = performance.now();
        try {
            const groupedSessions = await sessionIndexer.listSessions();
            const sessionCount = groupedSessions.reduce((total, group) => total + group.sessions.length, 0);
            logger.info(
                {
                    operation: "session_index_fetch",
                    durationMs: Math.round(performance.now() - startedAt),
                    entryCount: sessionCount,
                    status: "success",
                },
                "Performance operation",
            );

            client.send(
                JSON.stringify(
                    createBridgeEnvelope({
                        type: "bridge_sessions",
                        groups: groupedSessions,
                    }),
                ),
            );
        } catch {
            logger.info(
                {
                    operation: "session_index_fetch",
                    durationMs: Math.round(performance.now() - startedAt),
                    status: "failure",
                },
                "Performance operation",
            );
            logger.error("Failed to list sessions");
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

        const startedAt = performance.now();
        try {
            const tree = await sessionIndexer.getSessionTree(sessionPath, requestedFilter);
            logger.info(
                {
                    operation: "session_tree_fetch",
                    durationMs: Math.round(performance.now() - startedAt),
                    entryCount: tree.entries.length,
                    status: "success",
                },
                "Performance operation",
            );
            const runtimeLeafOverride = runtimeLeafBySessionPath.get(tree.sessionPath);
            const runtimeLeafId = runtimeLeafOverride?.currentLeafId;

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
        } catch {
            logger.info(
                {
                    operation: "session_tree_fetch",
                    durationMs: Math.round(performance.now() - startedAt),
                    status: "failure",
                },
                "Performance operation",
            );
            logger.error("Failed to build session tree");
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

    if (messageType === BRIDGE_GET_SESSION_FRESHNESS_TYPE) {
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

        try {
            const freshness = await sessionIndexer.getSessionFreshness(sessionPath);
            const lock = processManager.getControlSnapshot(freshness.cwd, freshness.sessionPath);

            client.send(
                JSON.stringify(
                    createBridgeEnvelope({
                        type: BRIDGE_SESSION_FRESHNESS_TYPE,
                        sessionPath: freshness.sessionPath,
                        cwd: freshness.cwd,
                        fingerprint: freshness.fingerprint,
                        lock: {
                            cwdOwnerClientId: lock.cwdOwnerClientId ?? null,
                            sessionOwnerClientId: lock.sessionOwnerClientId ?? null,
                            isCurrentClientCwdOwner: lock.cwdOwnerClientId === context.clientId,
                            isCurrentClientSessionOwner: lock.sessionOwnerClientId === context.clientId,
                        },
                    }),
                ),
            );
        } catch {
            logger.error("Failed to read session freshness");
            client.send(
                JSON.stringify(
                    createBridgeErrorEnvelope(
                        "session_freshness_failed",
                        "Failed to read session freshness",
                    ),
                ),
            );
        }

        return;
    }

    if (messageType === BRIDGE_IMPORT_SESSION_JSONL_TYPE) {
        const cwd = getRequestedCwd(payload, context);
        if (!cwd) {
            client.send(
                JSON.stringify(
                    createBridgeErrorEnvelope(
                        "missing_cwd_context",
                        "Set cwd first via bridge_set_cwd or include cwd in bridge_import_session_jsonl",
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

        const content = typeof payload.content === "string" ? payload.content : undefined;
        if (!content) {
            client.send(
                JSON.stringify(
                    createBridgeErrorEnvelope(
                        "invalid_import_payload",
                        "content must be a non-empty JSONL string",
                    ),
                ),
            );
            return;
        }

        const contentBytes = Buffer.byteLength(content, "utf8");
        if (contentBytes > config.importMaxBytes) {
            client.send(
                JSON.stringify(
                    createBridgeErrorEnvelope(
                        "import_payload_too_large",
                        `Import exceeds the ${config.importMaxBytes} byte limit`,
                    ),
                ),
            );
            return;
        }

        const requestedFileName = typeof payload.fileName === "string" ? payload.fileName : undefined;

        try {
            const sessionPath = await importSessionJsonlIntoRuntime({
                cwd,
                content,
                requestedFileName,
                sessionDirectory: config.sessionDirectory,
                processManager,
                awaitRpcEvent,
            });

            client.send(
                JSON.stringify(
                    createBridgeEnvelope({
                        type: BRIDGE_SESSION_IMPORTED_TYPE,
                        sessionPath,
                    }),
                ),
            );
        } catch {
            logger.error("Failed to import JSONL session into active runtime");
            client.send(
                JSON.stringify(
                    createBridgeErrorEnvelope(
                        "session_import_failed",
                        "Failed to import session",
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
                    {
                        currentLeafId: navigationResult.currentLeafId,
                        cwd,
                    },
                );
            }

            client.send(
                JSON.stringify(
                    createBridgeEnvelope({
                        type: BRIDGE_SESSION_INVALIDATED_TYPE,
                        reason: "navigation",
                    }),
                ),
            );
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
        } catch {
            logger.error("Failed to navigate tree in active session");
            client.send(
                JSON.stringify(
                    createBridgeErrorEnvelope(
                        "tree_navigation_failed",
                        "Failed to navigate tree",
                    ),
                ),
            );
        }

        return;
    }

    if (messageType === "bridge_set_cwd") {
        const startedAt = performance.now();
        const cwd = normalizeCwd(payload.cwd);
        if (!cwd) {
            client.send(JSON.stringify(createBridgeErrorEnvelope("invalid_cwd", "cwd must be a non-empty string")));
            return;
        }

        if (context.cwd && context.cwd !== cwd) {
            processManager.releaseControl(context.clientId, context.cwd);
        }

        context.cwd = cwd;
        logger.info(
            {
                operation: "cwd_setup",
                durationMs: Math.round(performance.now() - startedAt),
                status: "success",
            },
            "Performance operation",
        );

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
        const startedAt = performance.now();
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

        const sessionPath = normalizeOptionalString(payload.sessionPath);
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
        logger.info(
            {
                operation: "lock_acquisition",
                durationMs: Math.round(performance.now() - startedAt),
                status: "success",
            },
            "Performance operation",
        );

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

        const sessionPath = normalizeOptionalString(payload.sessionPath);
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

async function importSessionJsonlIntoRuntime(options: {
    cwd: string;
    content: string;
    requestedFileName?: string;
    sessionDirectory: string;
    processManager: PiProcessManager;
    awaitRpcEvent: (
        cwd: string,
        predicate: (payload: Record<string, unknown>) => boolean,
        options?: { timeoutMs?: number; consume?: boolean },
    ) => Promise<Record<string, unknown>>;
}): Promise<string> {
    const { cwd, content, requestedFileName, sessionDirectory, processManager, awaitRpcEvent } = options;

    await mkdir(sessionDirectory, { recursive: true });

    const sanitizedFileName = sanitizeImportedSessionFileName(requestedFileName);
    const sessionPath = await allocateImportedSessionPath(sessionDirectory, sanitizedFileName);
    await writeFile(sessionPath, content, "utf8");

    const switchRequestId = randomUUID();

    try {
        const switchResponsePromise = awaitRpcEvent(
            cwd,
            (payload) => isRpcResponseForId(payload, switchRequestId),
            { consume: true },
        );

        processManager.sendRpc(cwd, {
            id: switchRequestId,
            type: "switch_session",
            sessionPath,
        });

        const switchResponse = await switchResponsePromise;
        ensureSuccessfulRpcResponse(switchResponse, "switch_session");

        const responseData = asRecord(switchResponse.data);
        if (responseData?.cancelled === true) {
            throw new Error("Session import was cancelled");
        }

        return sessionPath;
    } catch (error) {
        await unlink(sessionPath).catch(() => undefined);
        throw error;
    }
}

async function allocateImportedSessionPath(
    sessionDirectory: string,
    fileName: string,
): Promise<string> {
    const extension = path.extname(fileName) || ".jsonl";
    const baseName = path.basename(fileName, extension);
    let suffix = 0;

    while (true) {
        const candidateFileName = suffix === 0 ? `${baseName}${extension}` : `${baseName}-${suffix}${extension}`;
        const candidatePath = path.join(sessionDirectory, candidateFileName);

        if (!(await pathExists(candidatePath))) {
            return candidatePath;
        }

        suffix += 1;
    }
}

function sanitizeImportedSessionFileName(fileNameRaw: string | undefined): string {
    const fallback = `imported-session-${Date.now()}.jsonl`;
    const trimmed = fileNameRaw?.trim();
    const baseName = trimmed ? path.basename(trimmed) : fallback;
    const normalized = baseName.replace(/[^A-Za-z0-9._-]+/g, "-").replace(/^-+|-+$/g, "");
    const safeName = normalized || fallback;

    if (safeName.endsWith(".jsonl")) {
        return safeName;
    }

    return `${safeName}.jsonl`;
}

async function pathExists(targetPath: string): Promise<boolean> {
    try {
        await access(targetPath);
        return true;
    } catch {
        return false;
    }
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
    } catch {
        logger.error("Failed to forward RPC payload");

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

function isSessionMutationEvent(payload: Record<string, unknown>): boolean {
    if (payload.type === "message_end" || payload.type === "compaction_end") {
        return true;
    }

    if (payload.type !== "response" || payload.success !== true) {
        return false;
    }

    return typeof payload.command === "string" && [
        "prompt",
        "bash",
        "compact",
        "fork",
        "clone",
        "new_session",
        "switch_session",
        "set_session_name",
    ].includes(payload.command);
}

function sessionMutationReason(payload: Record<string, unknown>): string {
    if (typeof payload.command === "string") {
        return payload.command;
    }
    return typeof payload.type === "string" ? payload.type : "runtime";
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

        logger.info("Released client locks after reconnect grace period");
    }, reconnectGraceMs);

    disconnectedClients.set(context.clientId, {
        context,
        timer,
    });
}

function getRequestedCwd(payload: Record<string, unknown>, context: ClientConnectionContext): string | undefined {
    return normalizeCwd(payload.cwd) ?? normalizeCwd(context.cwd);
}

function normalizeCwd(value: unknown): string | undefined {
    if (typeof value !== "string") {
        return undefined;
    }

    const trimmed = value.trim();
    if (!trimmed) {
        return undefined;
    }

    return path.resolve(trimmed);
}

function normalizeOptionalString(value: unknown): string | undefined {
    if (typeof value !== "string") {
        return undefined;
    }

    const trimmed = value.trim();
    return trimmed || undefined;
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

    return new URL(request.url, "http://localhost");
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

function loadTlsCredentials(certFile: string, keyFile: string): https.ServerOptions {
    try {
        return {
            cert: readFileSync(certFile),
            key: readFileSync(keyFile),
        };
    } catch (error) {
        const reason = error instanceof Error ? error.message : String(error);
        throw new Error(`Failed to read TLS credentials: ${reason}`, { cause: error });
    }
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

function parseLandingReference(requestUrl: string | undefined): string | undefined {
    if (!requestUrl || [...requestUrl].some((character) => character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127)) return undefined;
    let parsed: URL;
    try {
        parsed = new URL(requestUrl, "http://localhost");
    } catch {
        return undefined;
    }
    if (parsed.search || parsed.hash) return undefined;
    const match = /^\/s\/v1\/([A-Za-z0-9_-]{22})$/.exec(parsed.pathname);
    return match?.[1];
}

function sendShareError(client: WebSocket, code: string, message: string): void {
    client.send(JSON.stringify(createBridgeErrorEnvelope(code, message)));
}

function handleShareFailure(client: WebSocket, error: unknown, fallbackMessage: string): void {
    if (error instanceof ShareStateUnavailableError) {
        sendShareError(client, error.code, "Bridge share state is unavailable; ask the operator to restore or repair it");
        return;
    }
    sendShareError(client, "share_operation_failed", fallbackMessage);
}
