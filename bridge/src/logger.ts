import pino from "pino";
import type { LevelWithSilent, Logger } from "pino";

export function createLogger(level: LevelWithSilent): Logger {
    return pino({
        level,
    });
}
