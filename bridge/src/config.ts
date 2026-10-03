import os from "node:os";
import path from "node:path";

import type { LevelWithSilent } from "pino";

const DEFAULT_HOST = "127.0.0.1";
const DEFAULT_PORT = 8787;
const DEFAULT_LOG_LEVEL: LevelWithSilent = "info";
const DEFAULT_PROCESS_IDLE_TTL_MS = 5 * 60 * 1000;
const DEFAULT_RECONNECT_GRACE_MS = 30 * 1000;
const DEFAULT_SESSION_DIRECTORY = path.join(os.homedir(), ".pi", "agent", "sessions");
const DEFAULT_STATE_DIRECTORY = path.join(os.homedir(), ".pi-mobile");
const DEFAULT_WEBSOCKET_MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
const DEFAULT_IMPORT_MAX_BYTES = 10 * 1024 * 1024;
const DEFAULT_PI_COMMAND = "pi";

export interface BridgeConfig {
    host: string;
    port: number;
    logLevel: LevelWithSilent;
    authToken: string;
    processIdleTtlMs: number;
    reconnectGraceMs: number;
    sessionDirectory: string;
    stateDirectory?: string;
    shareOrigin?: string;
    enableHealthEndpoint: boolean;
    websocketMaxPayloadBytes: number;
    importMaxBytes: number;
    piCommand: string;
    tlsCertFile?: string;
    tlsKeyFile?: string;
}

export function parseBridgeConfig(env: NodeJS.ProcessEnv = process.env): BridgeConfig {
    const host = env.BRIDGE_HOST?.trim() || DEFAULT_HOST;
    const port = parsePort(env.BRIDGE_PORT);
    const logLevel = parseLogLevel(env.BRIDGE_LOG_LEVEL);
    const authToken = parseAuthToken(env.BRIDGE_AUTH_TOKEN);
    const processIdleTtlMs = parseProcessIdleTtlMs(env.BRIDGE_PROCESS_IDLE_TTL_MS);
    const reconnectGraceMs = parseReconnectGraceMs(env.BRIDGE_RECONNECT_GRACE_MS);
    const sessionDirectory = parseSessionDirectory(env.BRIDGE_SESSION_DIR);
    const stateDirectory = parseStateDirectory(env.BRIDGE_STATE_DIR);
    const shareOrigin = parseConfiguredShareOrigin(env.BRIDGE_SHARE_ORIGIN);
    const enableHealthEndpoint = parseEnableHealthEndpoint(env.BRIDGE_ENABLE_HEALTH_ENDPOINT);
    const websocketMaxPayloadBytes = parsePositiveInteger(
        "BRIDGE_WEBSOCKET_MAX_PAYLOAD_BYTES",
        env.BRIDGE_WEBSOCKET_MAX_PAYLOAD_BYTES,
        DEFAULT_WEBSOCKET_MAX_PAYLOAD_BYTES,
    );
    const importMaxBytes = parsePositiveInteger(
        "BRIDGE_IMPORT_MAX_BYTES",
        env.BRIDGE_IMPORT_MAX_BYTES,
        DEFAULT_IMPORT_MAX_BYTES,
    );
    const piCommand = env.BRIDGE_PI_COMMAND?.trim() || DEFAULT_PI_COMMAND;
    const tls = parseTlsConfig(env.BRIDGE_TLS_CERT_FILE, env.BRIDGE_TLS_KEY_FILE);

    return {
        host,
        port,
        logLevel,
        authToken,
        processIdleTtlMs,
        reconnectGraceMs,
        sessionDirectory,
        stateDirectory,
        shareOrigin,
        enableHealthEndpoint,
        websocketMaxPayloadBytes,
        importMaxBytes,
        piCommand,
        ...tls,
    };
}

function parsePositiveInteger(name: string, raw: string | undefined, defaultValue: number): number {
    if (!raw) return defaultValue;

    const value = Number(raw);
    if (!Number.isSafeInteger(value) || value <= 0) {
        throw new Error(`Invalid ${name}: ${raw}`);
    }

    return value;
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

function parseReconnectGraceMs(graceRaw: string | undefined): number {
    if (!graceRaw) return DEFAULT_RECONNECT_GRACE_MS;

    const graceMs = Number.parseInt(graceRaw, 10);
    if (Number.isNaN(graceMs) || graceMs < 0) {
        throw new Error(`Invalid BRIDGE_RECONNECT_GRACE_MS: ${graceRaw}`);
    }

    return graceMs;
}

function parseEnableHealthEndpoint(enableHealthEndpointRaw: string | undefined): boolean {
    const value = enableHealthEndpointRaw?.trim();
    if (!value) return true;

    if (value === "true") return true;
    if (value === "false") return false;

    throw new Error(`Invalid BRIDGE_ENABLE_HEALTH_ENDPOINT: ${enableHealthEndpointRaw}`);
}

function parseSessionDirectory(sessionDirectoryRaw: string | undefined): string {
    const fromEnv = sessionDirectoryRaw?.trim();
    if (!fromEnv) return DEFAULT_SESSION_DIRECTORY;

    return path.resolve(fromEnv);
}

function parseStateDirectory(stateDirectoryRaw: string | undefined): string {
    const fromEnv = stateDirectoryRaw?.trim();
    return fromEnv ? path.resolve(fromEnv) : DEFAULT_STATE_DIRECTORY;
}

function parseConfiguredShareOrigin(raw: string | undefined): string | undefined {
    const value = raw?.trim();
    if (!value) return undefined;
    let parsed: URL;
    try {
        parsed = new URL(value);
    } catch {
        throw new Error("Invalid BRIDGE_SHARE_ORIGIN");
    }
    if ((parsed.protocol !== "http:" && parsed.protocol !== "https:") || !parsed.hostname ||
        parsed.username || parsed.password || parsed.search || parsed.hash ||
        (parsed.pathname !== "/" && parsed.pathname !== "")) {
        throw new Error("BRIDGE_SHARE_ORIGIN must be an http(s) origin without path, userinfo, query, or fragment");
    }
    return parsed.origin;
}

function parseTlsConfig(
    certFileRaw: string | undefined,
    keyFileRaw: string | undefined,
): { tlsCertFile?: string; tlsKeyFile?: string } {
    const certFile = certFileRaw?.trim();
    const keyFile = keyFileRaw?.trim();

    if (!certFile && !keyFile) return {};
    if (!certFile || !keyFile) {
        throw new Error(
            "BRIDGE_TLS_CERT_FILE and BRIDGE_TLS_KEY_FILE must be set together to enable TLS",
        );
    }

    return {
        tlsCertFile: path.resolve(certFile),
        tlsKeyFile: path.resolve(keyFile),
    };
}
