import "dotenv/config";

import { parseBridgeConfig } from "./config.js";
import { createLogger } from "./logger.js";
import { createBridgeServer } from "./server.js";

async function main(): Promise<void> {
    const config = parseBridgeConfig();
    const logger = createLogger(config.logLevel);
    const bridgeServer = createBridgeServer(config, logger);

    await bridgeServer.start();

    const shutdown = async (signal: string): Promise<void> => {
        logger.info({ signal }, "Shutting down bridge");
        await bridgeServer.stop();
        process.exit(0);
    };

    process.on("SIGINT", () => {
        void shutdown("SIGINT");
    });
    process.on("SIGTERM", () => {
        void shutdown("SIGTERM");
    });
}

void main();
