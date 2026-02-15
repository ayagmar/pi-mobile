const STATS_COMMAND_NAME = "pi-mobile-open-stats";
const WORKFLOW_STATUS_KEY = "pi-mobile-workflow-action";
const OPEN_STATS_ACTION = "open_stats";

interface WorkflowCommandContext {
    ui: {
        setStatus: (key: string, text: string | undefined) => void;
    };
}

function resolveWorkflowAction(args: string): string | undefined {
    const action = args.trim();
    if (!action) {
        return OPEN_STATS_ACTION;
    }

    return action === OPEN_STATS_ACTION ? action : undefined;
}

export default function registerPiMobileWorkflowExtension(pi: {
    registerCommand: (name: string, options: {
        description?: string;
        handler: (args: string, ctx: WorkflowCommandContext) => Promise<void>;
    }) => void;
}): void {
    pi.registerCommand(STATS_COMMAND_NAME, {
        description: "Internal Pi Mobile workflow command",
        handler: async (args, ctx) => {
            const action = resolveWorkflowAction(args);
            if (!action) {
                return;
            }

            const payload = JSON.stringify({ action });
            ctx.ui.setStatus(WORKFLOW_STATUS_KEY, payload);
            ctx.ui.setStatus(WORKFLOW_STATUS_KEY, undefined);
        },
    });
}
