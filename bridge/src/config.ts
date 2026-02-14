import os from "node:os";
import path from "node:path";

import type { LevelWithSilent } from "pino";

const DEFAULT_HOST = "127.0.0.1";
const DEFAULT_PORT = 8787;
const DEFAULT_LOG_LEVEL: LevelWithSilent = "info";
const DEFAULT_PROCESS_IDLE_TTL_MS = 5 * 60 * 1000;
const DEFAULT_SESSION_DIRECTORY = path.join(os.homedir(), ".pi", "agent", "sessions");

export interface BridgeConfig {
    host: string;
    port: number;
    logLevel: LevelWithSilent;
    authToken: string;
    processIdleTtlMs: number;
    sessionDirectory: string;
}

export function parseBridgeConfig(env: NodeJS.ProcessEnv = process.env): BridgeConfig {
    const host = env.BRIDGE_HOST?.trim() || DEFAULT_HOST;
    const port = parsePort(env.BRIDGE_PORT);
    const logLevel = parseLogLevel(env.BRIDGE_LOG_LEVEL);
    const authToken = parseAuthToken(env.BRIDGE_AUTH_TOKEN);
    const processIdleTtlMs = parseProcessIdleTtlMs(env.BRIDGE_PROCESS_IDLE_TTL_MS);
    const sessionDirectory = parseSessionDirectory(env.BRIDGE_SESSION_DIR);

    return {
        host,
        port,
        logLevel,
        authToken,
        processIdleTtlMs,
        sessionDirectory,
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

function parseAuthToken(tokenRaw: string | undefined): string {
    const token = tokenRaw?.trim();
    if (!token) {
        throw new Error("BRIDGE_AUTH_TOKEN is required");
    }

    return token;
}

function parseProcessIdleTtlMs(ttlRaw: string | undefined): number {
    if (!ttlRaw) return DEFAULT_PROCESS_IDLE_TTL_MS;

    const ttlMs = Number.parseInt(ttlRaw, 10);
    if (Number.isNaN(ttlMs) || ttlMs < 1_000) {
        throw new Error(`Invalid BRIDGE_PROCESS_IDLE_TTL_MS: ${ttlRaw}`);
    }

    return ttlMs;
}

function parseSessionDirectory(sessionDirectoryRaw: string | undefined): string {
    const fromEnv = sessionDirectoryRaw?.trim();
    if (!fromEnv) return DEFAULT_SESSION_DIRECTORY;

    return path.resolve(fromEnv);
}
