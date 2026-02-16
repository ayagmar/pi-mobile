const TREE_COMMAND_NAME = "pi-mobile-tree";
const STATUS_KEY_PREFIX = "pi_mobile_tree_result:";

interface TreeNavigationResultPayload {
    cancelled: boolean;
    editorText: string | null;
    currentLeafId: string | null;
    sessionPath: string | null;
    error?: string;
}

function parseArguments(rawArgs: string): { entryId?: string; statusKey?: string } {
    const trimmed = rawArgs.trim();
    if (!trimmed) return {};

    const parts = trimmed.split(/\s+/);
    return {
        entryId: parts[0],
        statusKey: parts[1],
    };
}

function isInternalStatusKey(statusKey: string | undefined): statusKey is string {
    return typeof statusKey === "string" && statusKey.startsWith(STATUS_KEY_PREFIX);
}

interface TreeNavigationCommandResult {
    cancelled: boolean;
    editorText?: string;
}

interface TreeNavigationCommandContext {
    ui: {
        setStatus: (key: string, text: string | undefined) => void;
        setEditorText: (text: string) => void;
    };
    sessionManager: {
        getLeafId: () => string | null;
        getSessionFile: () => string | undefined;
    };
    waitForIdle: () => Promise<void>;
    navigateTree: (entryId: string, options: { summarize: boolean }) => Promise<TreeNavigationCommandResult>;
}

export default function registerPiMobileTreeExtension(pi: {
    registerCommand: (name: string, options: {
        description?: string;
        handler: (args: string, ctx: TreeNavigationCommandContext) => Promise<void>;
    }) => void;
}): void {
    pi.registerCommand(TREE_COMMAND_NAME, {
        description: "Internal Pi Mobile tree navigation command",
        handler: async (args, ctx) => {
            const { entryId, statusKey } = parseArguments(args);
            if (!entryId || !isInternalStatusKey(statusKey)) {
                return;
            }

            const emitResult = (payload: TreeNavigationResultPayload): void => {
                ctx.ui.setStatus(statusKey, JSON.stringify(payload));
                ctx.ui.setStatus(statusKey, undefined);
            };

            try {
                await ctx.waitForIdle();
                const result = await ctx.navigateTree(entryId, { summarize: false });

                if (!result.cancelled) {
                    ctx.ui.setEditorText(result.editorText ?? "");
                }

                emitResult({
                    cancelled: result.cancelled,
                    editorText: result.editorText ?? null,
                    currentLeafId: ctx.sessionManager.getLeafId() ?? null,
                    sessionPath: ctx.sessionManager.getSessionFile() ?? null,
                });
            } catch (error: unknown) {
                emitResult({
                    cancelled: false,
                    editorText: null,
                    currentLeafId: ctx.sessionManager.getLeafId() ?? null,
                    sessionPath: ctx.sessionManager.getSessionFile() ?? null,
                    error: error instanceof Error ? error.message : "Tree navigation failed",
                });
            }
        },
    });
}
