import type { Dirent } from "node:fs";
import fs from "node:fs/promises";
import path from "node:path";

import type { Logger } from "pino";

export interface SessionIndexEntry {
    sessionPath: string;
    cwd: string;
    createdAt: string;
    updatedAt: string;
    displayName?: string;
    firstUserMessagePreview?: string;
    messageCount: number;
    lastModel?: string;
}

export interface SessionIndexGroup {
    cwd: string;
    sessions: SessionIndexEntry[];
}

export interface SessionTreeEntry {
    entryId: string;
    parentId: string | null;
    entryType: string;
    role?: string;
    timestamp?: string;
    preview: string;
    label?: string;
    isBookmarked: boolean;
}

export type SessionTreeFilter = "default" | "no-tools" | "user-only" | "labeled-only";

export interface SessionTreeSnapshot {
    sessionPath: string;
    rootIds: string[];
    currentLeafId?: string;
    entries: SessionTreeEntry[];
}

export interface SessionIndexer {
    listSessions(): Promise<SessionIndexGroup[]>;
    getSessionTree(sessionPath: string, filter?: SessionTreeFilter): Promise<SessionTreeSnapshot>;
}

export interface SessionIndexerOptions {
    sessionsDirectory: string;
    logger: Logger;
}

export function createSessionIndexer(options: SessionIndexerOptions): SessionIndexer {
    const sessionsRoot = path.resolve(options.sessionsDirectory);

    return {
        async listSessions(): Promise<SessionIndexGroup[]> {
            const sessionFiles = await findSessionFiles(sessionsRoot, options.logger);
            const sessions: SessionIndexEntry[] = [];

            for (const sessionFile of sessionFiles) {
                const entry = await parseSessionFile(sessionFile, options.logger);
                if (!entry) continue;
                sessions.push(entry);
            }

            const groups = new Map<string, SessionIndexEntry[]>();
            for (const session of sessions) {
                const byCwd = groups.get(session.cwd) ?? [];
                byCwd.push(session);
                groups.set(session.cwd, byCwd);
            }

            const groupedSessions: SessionIndexGroup[] = [];
            for (const [cwd, groupedEntries] of groups.entries()) {
                groupedEntries.sort((a, b) => compareIsoDesc(a.updatedAt, b.updatedAt));
                groupedSessions.push({ cwd, sessions: groupedEntries });
            }

            groupedSessions.sort((a, b) => a.cwd.localeCompare(b.cwd));

            return groupedSessions;
        },

        async getSessionTree(sessionPath: string, filter?: SessionTreeFilter): Promise<SessionTreeSnapshot> {
            const resolvedSessionPath = resolveSessionPath(sessionPath, sessionsRoot);
            return parseSessionTreeFile(resolvedSessionPath, options.logger, filter);
        },
    };
}

function resolveSessionPath(sessionPath: string, sessionsRoot: string): string {
    const resolvedSessionPath = path.resolve(sessionPath);

    const isWithinSessionsRoot =
        resolvedSessionPath === sessionsRoot ||
        resolvedSessionPath.startsWith(`${sessionsRoot}${path.sep}`);

    if (!isWithinSessionsRoot) {
        throw new Error("Session path is outside configured session directory");
    }

    if (!resolvedSessionPath.endsWith(".jsonl")) {
        throw new Error("Session path must point to a .jsonl file");
    }

    return resolvedSessionPath;
}

async function findSessionFiles(rootDir: string, logger: Logger): Promise<string[]> {
    let directoryEntries: Dirent[];

    try {
        directoryEntries = await fs.readdir(rootDir, { withFileTypes: true });
    } catch (error: unknown) {
        if (isErrorWithCode(error, "ENOENT")) {
            logger.warn({ rootDir }, "Session directory does not exist");
            return [];
        }

        throw error;
    }

    const sessionFiles: string[] = [];

    for (const directoryEntry of directoryEntries) {
        const absolutePath = path.join(rootDir, directoryEntry.name);

        if (directoryEntry.isDirectory()) {
            const nestedFiles = await findSessionFiles(absolutePath, logger);
            sessionFiles.push(...nestedFiles);
            continue;
        }

        if (directoryEntry.isFile() && absolutePath.endsWith(".jsonl")) {
            sessionFiles.push(absolutePath);
        }
    }

    return sessionFiles;
}

async function parseSessionFile(sessionPath: string, logger: Logger): Promise<SessionIndexEntry | undefined> {
    let fileContent: string;

    try {
        fileContent = await fs.readFile(sessionPath, "utf-8");
    } catch (error: unknown) {
        logger.warn({ sessionPath, error }, "Failed to read session file");
        return undefined;
    }

    const lines = fileContent
        .split("\n")
        .map((line) => line.trim())
        .filter((line) => line.length > 0);

    if (lines.length === 0) {
        return undefined;
    }

    const header = tryParseJson(lines[0]);
    if (!header || header.type !== "session" || typeof header.cwd !== "string") {
        logger.warn({ sessionPath }, "Skipping invalid session header");
        return undefined;
    }

    const fileStats = await fs.stat(sessionPath);

    const createdAt = getValidIsoTimestamp(header.timestamp) ?? fileStats.birthtime.toISOString();
    let updatedAt = getValidIsoTimestamp(header.timestamp) ?? fileStats.mtime.toISOString();
    let displayName: string | undefined;
    let firstUserMessagePreview: string | undefined;
    let messageCount = 0;
    let lastModel: string | undefined;

    for (const line of lines.slice(1)) {
        const entry = tryParseJson(line);
        if (!entry) continue;

        const entryTimestamp = getValidIsoTimestamp(entry.timestamp);
        if (entryTimestamp && compareIsoDesc(entryTimestamp, updatedAt) < 0) {
            updatedAt = entryTimestamp;
        }

        if (entry.type === "session_info" && typeof entry.name === "string") {
            displayName = entry.name;
            continue;
        }

        if (entry.type !== "message" || !isRecord(entry.message)) {
            continue;
        }

        messageCount += 1;

        const role = entry.message.role;
        if (!firstUserMessagePreview && role === "user") {
            firstUserMessagePreview = extractUserPreview(entry.message.content);
        }

        if (role === "assistant" && typeof entry.message.model === "string") {
            lastModel = entry.message.model;
        }
    }

    return {
        sessionPath,
        cwd: header.cwd,
        createdAt,
        updatedAt,
        displayName,
        firstUserMessagePreview,
        messageCount,
        lastModel,
    };
}

async function parseSessionTreeFile(
    sessionPath: string,
    logger: Logger,
    filter: SessionTreeFilter = "default",
): Promise<SessionTreeSnapshot> {
    let fileContent: string;

    try {
        fileContent = await fs.readFile(sessionPath, "utf-8");
    } catch (error: unknown) {
        logger.warn({ sessionPath, error }, "Failed to read session tree file");
        throw new Error(`Failed to read session file: ${sessionPath}`);
    }

    const lines = fileContent
        .split("\n")
        .map((line) => line.trim())
        .filter((line) => line.length > 0);

    if (lines.length === 0) {
        throw new Error("Session file is empty");
    }

    const header = tryParseJson(lines[0]);
    if (!header || header.type !== "session") {
        throw new Error("Invalid session header");
    }

    const rawEntries = lines.slice(1).map(tryParseJson).filter((entry): entry is Record<string, unknown> => !!entry);
    const labelsByTargetId = collectLabelsByTargetId(rawEntries);

    const entries: SessionTreeEntry[] = [];

    for (const entry of rawEntries) {
        const entryId = typeof entry.id === "string" ? entry.id : undefined;
        if (!entryId) continue;

        const parentId = typeof entry.parentId === "string" ? entry.parentId : null;
        const entryType = typeof entry.type === "string" ? entry.type : "unknown";
        const timestamp = getValidIsoTimestamp(entry.timestamp);

        const messageRecord = isRecord(entry.message) ? entry.message : undefined;
        const role = typeof messageRecord?.role === "string" ? messageRecord.role : undefined;
        const preview = extractEntryPreview(entry, messageRecord);
        const label = labelsByTargetId.get(entryId);

        entries.push({
            entryId,
            parentId,
            entryType,
            role,
            timestamp,
            preview,
            label,
            isBookmarked: label !== undefined,
        });
    }

    const currentLeafId = entries.length > 0 ? entries[entries.length - 1].entryId : undefined;
    const filteredEntries = applyTreeFilter(entries, filter);
    const filteredEntryIds = new Set(filteredEntries.map((entry) => entry.entryId));
    const rootIds = filteredEntries
        .filter((entry) => entry.parentId === null || !filteredEntryIds.has(entry.parentId))
        .map((entry) => entry.entryId);

    return {
        sessionPath,
        rootIds,
        currentLeafId,
        entries: filteredEntries,
    };
}

function collectLabelsByTargetId(entries: Record<string, unknown>[]): Map<string, string> {
    const labelsByTargetId = new Map<string, string>();

    for (const entry of entries) {
        if (entry.type !== "label") continue;
        if (typeof entry.targetId !== "string") continue;

        if (typeof entry.label === "string") {
            const normalizedLabel = normalizePreview(entry.label);
            if (normalizedLabel) {
                labelsByTargetId.set(entry.targetId, normalizedLabel);
                continue;
            }
        }

        labelsByTargetId.delete(entry.targetId);
    }

    return labelsByTargetId;
}

function applyTreeFilter(
    entries: SessionTreeEntry[],
    filter: SessionTreeFilter,
): SessionTreeEntry[] {
    switch (filter) {
        case "default":
            return entries.filter((entry) => entry.entryType !== "label" && entry.entryType !== "custom");
        case "no-tools":
            return entries.filter((entry) => entry.role !== "toolResult");
        case "user-only":
            return entries.filter((entry) => entry.role === "user");
        case "labeled-only":
            return entries.filter((entry) => entry.isBookmarked || entry.entryType === "label");
        default:
            return entries;
    }
}

function extractEntryPreview(
    entry: Record<string, unknown>,
    messageRecord?: Record<string, unknown>,
): string {
    if (entry.type === "session_info" && typeof entry.name === "string") {
        return normalizePreview(entry.name) ?? "session info";
    }

    if (entry.type === "label" && typeof entry.label === "string") {
        return normalizePreview(`[${entry.label}]`) ?? "label";
    }

    if (entry.type === "branch_summary" && typeof entry.summary === "string") {
        return normalizePreview(entry.summary) ?? "branch summary";
    }

    if (messageRecord) {
        const fromContent = extractUserPreview(messageRecord.content);
        if (fromContent) return fromContent;

        if (typeof messageRecord.content === "string") {
            return normalizePreview(messageRecord.content) ?? "message";
        }
    }

    return "entry";
}

function extractUserPreview(content: unknown): string | undefined {
    if (typeof content === "string") {
        return normalizePreview(content);
    }

    if (!Array.isArray(content)) return undefined;

    for (const item of content) {
        if (!isRecord(item)) continue;

        if (item.type === "text" && typeof item.text === "string") {
            return normalizePreview(item.text);
        }
    }

    return undefined;
}

function normalizePreview(value: string): string | undefined {
    const compact = value.replace(/\s+/g, " ").trim();
    if (!compact) return undefined;

    const maxLength = 140;
    if (compact.length <= maxLength) return compact;

    return `${compact.slice(0, maxLength - 1)}…`;
}

function tryParseJson(value: string): Record<string, unknown> | undefined {
    let parsed: unknown;

    try {
        parsed = JSON.parse(value);
    } catch {
        return undefined;
    }

    if (!isRecord(parsed)) return undefined;

    return parsed;
}

function getValidIsoTimestamp(value: unknown): string | undefined {
    if (typeof value !== "string") return undefined;

    const timestamp = Date.parse(value);
    if (Number.isNaN(timestamp)) return undefined;

    return new Date(timestamp).toISOString();
}

function compareIsoDesc(a: string, b: string): number {
    if (a === b) return 0;

    return a > b ? -1 : 1;
}

function isErrorWithCode(error: unknown, code: string): boolean {
    return isRecord(error) && error.code === code;
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}
