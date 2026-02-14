import http from "node:http";

import type { Logger } from "pino";
import { WebSocketServer, type RawData, type WebSocket } from "ws";

import type { BridgeConfig } from "./config.js";
import { createBridgeEnvelope, createBridgeErrorEnvelope, parseBridgeEnvelope } from "./protocol.js";

export interface BridgeServerStartInfo {
    host: string;
    port: number;
}

export interface BridgeServer {
    start(): Promise<BridgeServerStartInfo>;
    stop(): Promise<void>;
}

export function createBridgeServer(config: BridgeConfig, logger: Logger): BridgeServer {
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
        logger.info(
            {
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
            handleClientMessage(client, data, logger);
        });

        client.on("close", () => {
            logger.info("WebSocket client disconnected");
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

function handleClientMessage(client: WebSocket, data: RawData, logger: Logger): void {
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
                error: parsedEnvelope.error,
            },
            "Received malformed envelope",
        );
        return;
    }

    const envelope = parsedEnvelope.envelope;

    if (envelope.channel === "bridge") {
        const messageType = envelope.payload.type;

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

        client.send(
            JSON.stringify(
                createBridgeErrorEnvelope(
                    "unsupported_bridge_message",
                    "Unsupported bridge payload type",
                ),
            ),
        );
        return;
    }

    client.send(
        JSON.stringify(
            createBridgeErrorEnvelope(
                "rpc_not_ready",
                "RPC forwarding is not implemented yet",
            ),
        ),
    );
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
