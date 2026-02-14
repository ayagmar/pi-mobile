import type { LevelWithSilent } from "pino";

const DEFAULT_HOST = "127.0.0.1";
const DEFAULT_PORT = 8787;
const DEFAULT_LOG_LEVEL: LevelWithSilent = "info";

export interface BridgeConfig {
    host: string;
    port: number;
    logLevel: LevelWithSilent;
}

export function parseBridgeConfig(env: NodeJS.ProcessEnv = process.env): BridgeConfig {
    const host = env.BRIDGE_HOST?.trim() || DEFAULT_HOST;
    const port = parsePort(env.BRIDGE_PORT);
    const logLevel = parseLogLevel(env.BRIDGE_LOG_LEVEL);

    return {
        host,
        port,
        logLevel,
    };
}

function parsePort(portRaw: string | undefined): number {
    if (!portRaw) return DEFAULT_PORT;

    const port = Number.parseInt(portRaw, 10);
    if (Number.isNaN(port) || port <= 0 || port > 65_535) {
        throw new Error(`Invalid BRIDGE_PORT: ${portRaw}`);
    }

    return port;
}

function parseLogLevel(levelRaw: string | undefined): LevelWithSilent {
    const level = levelRaw?.trim();

    if (!level) return DEFAULT_LOG_LEVEL;

    const supportedLevels: LevelWithSilent[] = [
        "fatal",
        "error",
        "warn",
        "info",
        "debug",
        "trace",
        "silent",
    ];

    if (!supportedLevels.includes(level as LevelWithSilent)) {
        throw new Error(`Invalid BRIDGE_LOG_LEVEL: ${levelRaw}`);
    }

    return level as LevelWithSilent;
}
