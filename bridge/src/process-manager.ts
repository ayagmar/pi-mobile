import type { Logger } from "pino";

import type { PiRpcForwarder, PiRpcForwarderMessage } from "./rpc-forwarder.js";

export interface ProcessManagerEvent {
    cwd: string;
    payload: PiRpcForwarderMessage;
}

export interface AcquireControlRequest {
    clientId: string;
    cwd: string;
    sessionPath?: string;
}

export interface AcquireControlResult {
    success: boolean;
    reason?: string;
}

export interface ProcessManagerStats {
    activeProcessCount: number;
    lockedCwdCount: number;
    lockedSessionCount: number;
}

export interface PiProcessManager {
    setMessageHandler(handler: (event: ProcessManagerEvent) => void): void;
    getOrStart(cwd: string): PiRpcForwarder;
    sendRpc(cwd: string, payload: Record<string, unknown>): void;
    acquireControl(request: AcquireControlRequest): AcquireControlResult;
    hasControl(clientId: string, cwd: string, sessionPath?: string): boolean;
    releaseControl(clientId: string, cwd: string, sessionPath?: string): void;
    releaseClient(clientId: string): void;
    getStats(): ProcessManagerStats;
    evictIdleProcesses(): Promise<void>;
    stop(): Promise<void>;
}

export interface ProcessManagerOptions {
    idleTtlMs: number;
    logger: Logger;
    forwarderFactory: (cwd: string) => PiRpcForwarder;
    now?: () => number;
    enableEvictionTimer?: boolean;
}

interface ForwarderEntry {
    cwd: string;
    forwarder: PiRpcForwarder;
    lastUsedAt: number;
}

export function createPiProcessManager(options: ProcessManagerOptions): PiProcessManager {
    const now = options.now ?? (() => Date.now());
    const entries = new Map<string, ForwarderEntry>();
    const lockByCwd = new Map<string, string>();
    const lockBySession = new Map<string, string>();
    let messageHandler: (event: ProcessManagerEvent) => void = () => {};

    const evictionIntervalMs = Math.max(1_000, Math.floor(options.idleTtlMs / 2));
    const shouldStartTimer = options.enableEvictionTimer ?? true;
    const evictionTimer = shouldStartTimer
        ? setInterval(() => {
            void evictIdleProcessesInternal();
        }, evictionIntervalMs)
        : undefined;

    evictionTimer?.unref();

    const getOrStart = (cwd: string): PiRpcForwarder => {
        const existingEntry = entries.get(cwd);
        if (existingEntry) {
            existingEntry.lastUsedAt = now();
            return existingEntry.forwarder;
        }

        const forwarder = options.forwarderFactory(cwd);
        const entry: ForwarderEntry = {
            cwd,
            forwarder,
            lastUsedAt: now(),
        };

        forwarder.setMessageHandler((payload) => {
            entry.lastUsedAt = now();
            messageHandler({ cwd, payload });
        });
        forwarder.setLifecycleHandler((event) => {
            options.logger.info({ cwd, event }, "RPC forwarder lifecycle event");
        });

        entries.set(cwd, entry);

        options.logger.info({ cwd }, "Started RPC forwarder for cwd");

        return forwarder;
    };

    const evictIdleProcessesInternal = async (): Promise<void> => {
        const evictionCutoff = now() - options.idleTtlMs;

        for (const [cwd, entry] of entries.entries()) {
            const shouldKeepRunning = entry.lastUsedAt >= evictionCutoff || lockByCwd.has(cwd);
            if (shouldKeepRunning) continue;

            await entry.forwarder.stop();
            entries.delete(cwd);

            options.logger.info({ cwd }, "Evicted idle RPC forwarder");
        }
    };

    return {
        setMessageHandler(handler: (event: ProcessManagerEvent) => void): void {
            messageHandler = handler;
        },
        getOrStart(cwd: string): PiRpcForwarder {
            return getOrStart(cwd);
        },
        sendRpc(cwd: string, payload: Record<string, unknown>): void {
            const forwarder = getOrStart(cwd);
            const entry = entries.get(cwd);
            if (entry) entry.lastUsedAt = now();
            forwarder.send(payload);
        },
        acquireControl(request: AcquireControlRequest): AcquireControlResult {
            const currentCwdOwner = lockByCwd.get(request.cwd);
            if (currentCwdOwner && currentCwdOwner !== request.clientId) {
                return {
                    success: false,
                    reason: `cwd is controlled by another client: ${request.cwd}`,
                };
            }

            if (request.sessionPath) {
                const currentSessionOwner = lockBySession.get(request.sessionPath);
                if (currentSessionOwner && currentSessionOwner !== request.clientId) {
                    return {
                        success: false,
                        reason: `session is controlled by another client: ${request.sessionPath}`,
                    };
                }
            }

            lockByCwd.set(request.cwd, request.clientId);
            if (request.sessionPath) {
                lockBySession.set(request.sessionPath, request.clientId);
            }

            return { success: true };
        },
        hasControl(clientId: string, cwd: string, sessionPath?: string): boolean {
            if (lockByCwd.get(cwd) !== clientId) {
                return false;
            }

            if (sessionPath && lockBySession.get(sessionPath) !== clientId) {
                return false;
            }

            return true;
        },
        releaseControl(clientId: string, cwd: string, sessionPath?: string): void {
            if (lockByCwd.get(cwd) === clientId) {
                lockByCwd.delete(cwd);
            }

            if (sessionPath && lockBySession.get(sessionPath) === clientId) {
                lockBySession.delete(sessionPath);
            }
        },
        releaseClient(clientId: string): void {
            for (const [cwd, ownerClientId] of lockByCwd.entries()) {
                if (ownerClientId === clientId) {
                    lockByCwd.delete(cwd);
                }
            }

            for (const [sessionPath, ownerClientId] of lockBySession.entries()) {
                if (ownerClientId === clientId) {
                    lockBySession.delete(sessionPath);
                }
            }
        },
        getStats(): ProcessManagerStats {
            return {
                activeProcessCount: entries.size,
                lockedCwdCount: lockByCwd.size,
                lockedSessionCount: lockBySession.size,
            };
        },
        async evictIdleProcesses(): Promise<void> {
            await evictIdleProcessesInternal();
        },
        async stop(): Promise<void> {
            if (evictionTimer) {
                clearInterval(evictionTimer);
            }

            for (const entry of entries.values()) {
                await entry.forwarder.stop();
            }

            entries.clear();
            lockByCwd.clear();
            lockBySession.clear();
        },
    };
}
