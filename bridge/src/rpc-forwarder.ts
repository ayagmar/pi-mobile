import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import readline from "node:readline";

import type { Logger } from "pino";

export interface PiRpcForwarderMessage {
    [key: string]: unknown;
}

export interface PiRpcForwarder {
    setMessageHandler(handler: (payload: PiRpcForwarderMessage) => void): void;
    send(payload: Record<string, unknown>): void;
    stop(): Promise<void>;
}

export interface PiRpcForwarderConfig {
    command: string;
    args: string[];
    cwd: string;
    env?: NodeJS.ProcessEnv;
}

export function createPiRpcForwarder(config: PiRpcForwarderConfig, logger: Logger): PiRpcForwarder {
    let processRef: ChildProcessWithoutNullStreams | undefined;
    let stdoutReader: readline.Interface | undefined;
    let stderrReader: readline.Interface | undefined;
    let messageHandler: (payload: PiRpcForwarderMessage) => void = () => {};

    const cleanup = (): void => {
        stdoutReader?.close();
        stderrReader?.close();
        stdoutReader = undefined;
        stderrReader = undefined;
        processRef = undefined;
    };

    const ensureProcess = (): ChildProcessWithoutNullStreams => {
        if (processRef && !processRef.killed) {
            return processRef;
        }

        const child = spawn(config.command, config.args, {
            cwd: config.cwd,
            env: config.env ?? process.env,
            stdio: "pipe",
        });

        child.on("error", (error) => {
            logger.error({ error }, "pi RPC subprocess error");
        });

        child.on("exit", (code, signal) => {
            logger.info({ code, signal }, "pi RPC subprocess exited");
            cleanup();
        });

        stdoutReader = readline.createInterface({
            input: child.stdout,
            crlfDelay: Infinity,
        });
        stdoutReader.on("line", (line) => {
            const parsedMessage = tryParseJsonObject(line);
            if (!parsedMessage) {
                logger.warn(
                    {
                        line,
                    },
                    "Dropping invalid JSON from pi RPC stdout",
                );
                return;
            }

            messageHandler(parsedMessage);
        });

        stderrReader = readline.createInterface({
            input: child.stderr,
            crlfDelay: Infinity,
        });
        stderrReader.on("line", (line) => {
            logger.warn({ line }, "pi RPC stderr");
        });

        processRef = child;

        logger.info(
            {
                command: config.command,
                args: config.args,
                pid: child.pid,
                cwd: config.cwd,
            },
            "Started pi RPC subprocess",
        );

        return child;
    };

    return {
        setMessageHandler(handler: (payload: PiRpcForwarderMessage) => void): void {
            messageHandler = handler;
        },
        send(payload: Record<string, unknown>): void {
            const child = ensureProcess();

            const serializedPayload = `${JSON.stringify(payload)}\n`;
            const writeOk = child.stdin.write(serializedPayload);

            if (!writeOk) {
                logger.warn("pi RPC stdin backpressure detected");
            }
        },
        async stop(): Promise<void> {
            const child = processRef;
            if (!child) return;

            child.stdin.end();

            if (child.killed) {
                cleanup();
                return;
            }

            await new Promise<void>((resolve) => {
                const timer = setTimeout(() => {
                    child.kill("SIGKILL");
                }, 2_000);

                child.once("exit", () => {
                    clearTimeout(timer);
                    resolve();
                });

                child.kill("SIGTERM");
            });
        },
    };
}

function tryParseJsonObject(value: string): Record<string, unknown> | undefined {
    let parsed: unknown;

    try {
        parsed = JSON.parse(value);
    } catch {
        return undefined;
    }

    if (!isRecord(parsed)) {
        return undefined;
    }

    return parsed;
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}
