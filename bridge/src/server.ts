import http from "node:http";

import type { Logger } from "pino";
import { WebSocketServer, type WebSocket } from "ws";

import type { BridgeConfig } from "./config.js";

export interface BridgeServer {
    start(): Promise<void>;
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
        if (request.url !== "/ws") {
            socket.destroy();
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
            JSON.stringify({
                channel: "bridge",
                payload: {
                    type: "bridge_hello",
                    message: "Bridge skeleton is running",
                },
            }),
        );

        client.on("close", () => {
            logger.info("WebSocket client disconnected");
        });
    });

    return {
        async start(): Promise<void> {
            await new Promise<void>((resolve) => {
                server.listen(config.port, config.host, () => {
                    logger.info(
                        {
                            host: config.host,
                            port: config.port,
                        },
                        "Bridge server listening",
                    );
                    resolve();
                });
            });
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
