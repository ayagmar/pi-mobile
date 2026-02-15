import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { describe, expect, it, vi } from "vitest";

import { createLogger } from "../src/logger.js";
import { createSessionIndexer } from "../src/session-indexer.js";

describe("createSessionIndexer", () => {
    it("indexes session metadata and groups results by cwd", async () => {
        const fixturesDirectory = path.resolve(
            path.dirname(fileURLToPath(import.meta.url)),
            "fixtures/sessions",
        );

        const sessionIndexer = createSessionIndexer({
            sessionsDirectory: fixturesDirectory,
            logger: createLogger("silent"),
        });

        const groups = await sessionIndexer.listSessions();

        expect(groups.map((group) => group.cwd)).toEqual([
            "/tmp/project-a",
            "/tmp/project-b",
        ]);

        const projectA = groups.find((group) => group.cwd === "/tmp/project-a");
        if (!projectA) throw new Error("Expected /tmp/project-a group");

        expect(projectA.sessions).toHaveLength(2);
        expect(projectA.sessions[0].displayName).toBe("Feature A work");
        expect(projectA.sessions[0].firstUserMessagePreview).toBe("Implement feature A with tests");
        expect(projectA.sessions[0].messageCount).toBe(2);
        expect(projectA.sessions[0].lastModel).toBe("gpt-5.3-codex");
        expect(projectA.sessions[0].updatedAt).toBe("2026-02-01T00:00:06.000Z");

        const projectB = groups.find((group) => group.cwd === "/tmp/project-b");
        if (!projectB) throw new Error("Expected /tmp/project-b group");

        expect(projectB.sessions).toHaveLength(1);
        expect(projectB.sessions[0].displayName).toBe("Bug B investigation");
        expect(projectB.sessions[0].messageCount).toBe(2);
        expect(projectB.sessions[0].lastModel).toBe("claude-sonnet-4");
    });

    it("builds a session tree snapshot with parent relationships", async () => {
        const fixturesDirectory = path.resolve(
            path.dirname(fileURLToPath(import.meta.url)),
            "fixtures/sessions",
        );

        const sessionIndexer = createSessionIndexer({
            sessionsDirectory: fixturesDirectory,
            logger: createLogger("silent"),
        });

        const tree = await sessionIndexer.getSessionTree(
            path.join(fixturesDirectory, "--tmp-project-a--", "2026-02-01T00-00-00-000Z_a1111111.jsonl"),
        );

        expect(tree.sessionPath).toContain("2026-02-01T00-00-00-000Z_a1111111.jsonl");
        expect(tree.rootIds).toEqual(["m1"]);
        expect(tree.currentLeafId).toBe("i1");
        expect(tree.entries.map((entry) => entry.entryId)).toEqual(["m1", "m2", "i1"]);

        const assistant = tree.entries.find((entry) => entry.entryId === "m2");
        expect(assistant?.parentId).toBe("m1");
        expect(assistant?.role).toBe("assistant");
        expect(assistant?.preview).toContain("Working on it");
        expect(assistant?.isBookmarked).toBe(false);
    });

    it("applies user-only filter for tree snapshots", async () => {
        const fixturesDirectory = path.resolve(
            path.dirname(fileURLToPath(import.meta.url)),
            "fixtures/sessions",
        );

        const sessionIndexer = createSessionIndexer({
            sessionsDirectory: fixturesDirectory,
            logger: createLogger("silent"),
        });

        const tree = await sessionIndexer.getSessionTree(
            path.join(fixturesDirectory, "--tmp-project-a--", "2026-02-01T00-00-00-000Z_a1111111.jsonl"),
            "user-only",
        );

        expect(tree.entries.map((entry) => entry.role)).toEqual(["user"]);
        expect(tree.entries.map((entry) => entry.entryId)).toEqual(["m1"]);
        expect(tree.rootIds).toEqual(["m1"]);
    });

    it("supports all filter and includes entries excluded by default", async () => {
        const tempRoot = await fs.mkdtemp(path.join(os.tmpdir(), "pi-tree-all-filter-"));

        try {
            const projectDir = path.join(tempRoot, "--tmp-project-all-filter--");
            await fs.mkdir(projectDir, { recursive: true });

            const sessionPath = path.join(projectDir, "2026-02-03T00-00-00-000Z_a1111111.jsonl");
            await fs.writeFile(
                sessionPath,
                [
                    JSON.stringify({
                        type: "session",
                        version: 3,
                        id: "a1111111",
                        timestamp: "2026-02-03T00:00:00.000Z",
                        cwd: "/tmp/project-all-filter",
                    }),
                    JSON.stringify({
                        type: "message",
                        id: "m1",
                        parentId: null,
                        timestamp: "2026-02-03T00:00:01.000Z",
                        message: { role: "user", content: "hello" },
                    }),
                    JSON.stringify({
                        type: "label",
                        id: "l1",
                        parentId: "m1",
                        timestamp: "2026-02-03T00:00:02.000Z",
                        targetId: "m1",
                        label: "checkpoint",
                    }),
                    JSON.stringify({
                        type: "custom",
                        id: "c1",
                        parentId: "m1",
                        timestamp: "2026-02-03T00:00:03.000Z",
                        message: { role: "assistant", content: "custom event" },
                    }),
                ].join("\n"),
                "utf-8",
            );

            const sessionIndexer = createSessionIndexer({
                sessionsDirectory: tempRoot,
                logger: createLogger("silent"),
            });

            const defaultTree = await sessionIndexer.getSessionTree(sessionPath, "default");
            expect(defaultTree.entries.map((entry) => entry.entryId)).toEqual(["m1"]);

            const allTree = await sessionIndexer.getSessionTree(sessionPath, "all");
            expect(allTree.entries.map((entry) => entry.entryId)).toEqual(["m1", "l1", "c1"]);
        } finally {
            await fs.rm(tempRoot, { recursive: true, force: true });
        }
    });

    it("attaches labels to target entries and supports labeled-only filter", async () => {
        const tempRoot = await fs.mkdtemp(path.join(os.tmpdir(), "pi-tree-labels-"));
        const projectDir = path.join(tempRoot, "--tmp-project-labeled--");
        await fs.mkdir(projectDir, { recursive: true });

        const sessionPath = path.join(projectDir, "2026-02-03T00-00-00-000Z_l1111111.jsonl");
        await fs.writeFile(
            sessionPath,
            [
                JSON.stringify({
                    type: "session",
                    version: 3,
                    id: "l1111111",
                    timestamp: "2026-02-03T00:00:00.000Z",
                    cwd: "/tmp/project-labeled",
                }),
                JSON.stringify({
                    type: "message",
                    id: "m1",
                    parentId: null,
                    timestamp: "2026-02-03T00:00:01.000Z",
                    message: { role: "user", content: "checkpoint this" },
                }),
                JSON.stringify({
                    type: "label",
                    id: "l1",
                    parentId: "m1",
                    timestamp: "2026-02-03T00:00:02.000Z",
                    targetId: "m1",
                    label: "checkpoint-1",
                }),
            ].join("\n"),
            "utf-8",
        );

        const sessionIndexer = createSessionIndexer({
            sessionsDirectory: tempRoot,
            logger: createLogger("silent"),
        });

        const fullTree = await sessionIndexer.getSessionTree(sessionPath);
        const labeledEntry = fullTree.entries.find((entry) => entry.entryId === "m1");
        expect(labeledEntry?.label).toBe("checkpoint-1");
        expect(labeledEntry?.isBookmarked).toBe(true);

        const labeledOnlyTree = await sessionIndexer.getSessionTree(sessionPath, "labeled-only");
        expect(labeledOnlyTree.entries.map((entry) => entry.entryId)).toEqual(["m1", "l1"]);

        await fs.rm(tempRoot, { recursive: true, force: true });
    });

    it("rejects session tree requests outside configured session directory", async () => {
        const fixturesDirectory = path.resolve(
            path.dirname(fileURLToPath(import.meta.url)),
            "fixtures/sessions",
        );

        const sessionIndexer = createSessionIndexer({
            sessionsDirectory: fixturesDirectory,
            logger: createLogger("silent"),
        });

        await expect(sessionIndexer.getSessionTree("/etc/passwd.jsonl")).rejects.toThrow(
            "Session path is outside configured session directory",
        );
    });

    it("rejects symlinked session paths that resolve outside the session root", async () => {
        const tempRoot = await fs.mkdtemp(path.join(os.tmpdir(), "pi-tree-symlink-"));

        try {
            const sessionsRoot = path.join(tempRoot, "sessions");
            const outsideRoot = path.join(tempRoot, "outside");

            await fs.mkdir(sessionsRoot, { recursive: true });
            await fs.mkdir(outsideRoot, { recursive: true });

            const outsideSessionPath = path.join(outsideRoot, "outside.jsonl");
            await fs.writeFile(
                outsideSessionPath,
                JSON.stringify({
                    type: "session",
                    version: 3,
                    id: "outside",
                    timestamp: "2026-02-03T00:00:00.000Z",
                    cwd: "/tmp/outside",
                }),
                "utf-8",
            );

            const symlinkSessionPath = path.join(sessionsRoot, "linked-outside.jsonl");
            await fs.symlink(outsideSessionPath, symlinkSessionPath);

            const sessionIndexer = createSessionIndexer({
                sessionsDirectory: sessionsRoot,
                logger: createLogger("silent"),
            });

            await expect(sessionIndexer.getSessionTree(symlinkSessionPath)).rejects.toThrow(
                "Session path is outside configured session directory",
            );
        } finally {
            await fs.rm(tempRoot, { recursive: true, force: true });
        }
    });

    it("reuses cached metadata for unchanged session files", async () => {
        const tempRoot = await fs.mkdtemp(path.join(os.tmpdir(), "pi-session-cache-"));
        const projectDir = path.join(tempRoot, "--tmp-project-cache--");
        await fs.mkdir(projectDir, { recursive: true });

        const sessionPath = path.join(projectDir, "2026-02-03T00-00-00-000Z_c1111111.jsonl");
        await fs.writeFile(
            sessionPath,
            [
                JSON.stringify({
                    type: "session",
                    version: 3,
                    id: "c1111111",
                    timestamp: "2026-02-03T00:00:00.000Z",
                    cwd: "/tmp/project-cache",
                }),
                JSON.stringify({
                    type: "session_info",
                    id: "i1",
                    timestamp: "2026-02-03T00:00:01.000Z",
                    name: "Cache Warm",
                }),
                JSON.stringify({
                    type: "message",
                    id: "m1",
                    parentId: null,
                    timestamp: "2026-02-03T00:00:02.000Z",
                    message: { role: "user", content: "hello" },
                }),
            ].join("\n"),
            "utf-8",
        );

        const sessionIndexer = createSessionIndexer({
            sessionsDirectory: tempRoot,
            logger: createLogger("silent"),
        });

        const readFileSpy = vi.spyOn(fs, "readFile");

        try {
            const firstGroups = await sessionIndexer.listSessions();
            expect(firstGroups[0]?.sessions[0]?.displayName).toBe("Cache Warm");
            expect(readFileSpy.mock.calls.length).toBeGreaterThan(0);

            readFileSpy.mockClear();

            const secondGroups = await sessionIndexer.listSessions();
            expect(secondGroups[0]?.sessions[0]?.displayName).toBe("Cache Warm");
            expect(readFileSpy).not.toHaveBeenCalled();

            await fs.appendFile(
                sessionPath,
                `\n${JSON.stringify({
                    type: "session_info",
                    id: "i2",
                    timestamp: "2026-02-03T00:00:03.000Z",
                    name: "Cache Updated",
                })}`,
                "utf-8",
            );

            readFileSpy.mockClear();

            const thirdGroups = await sessionIndexer.listSessions();
            expect(thirdGroups[0]?.sessions[0]?.displayName).toBe("Cache Updated");
            expect(readFileSpy).toHaveBeenCalled();
        } finally {
            readFileSpy.mockRestore();
            await fs.rm(tempRoot, { recursive: true, force: true });
        }
    });

    it("returns an empty list if session directory does not exist", async () => {
        const sessionIndexer = createSessionIndexer({
            sessionsDirectory: "/tmp/path-does-not-exist-for-tests",
            logger: createLogger("silent"),
        });

        await expect(sessionIndexer.listSessions()).resolves.toEqual([]);
    });
});
