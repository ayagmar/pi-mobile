import path from "node:path";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

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
    });

    it("returns an empty list if session directory does not exist", async () => {
        const sessionIndexer = createSessionIndexer({
            sessionsDirectory: "/tmp/path-does-not-exist-for-tests",
            logger: createLogger("silent"),
        });

        await expect(sessionIndexer.listSessions()).resolves.toEqual([]);
    });
});
