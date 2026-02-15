const STATS_COMMAND_NAME = "pi-mobile-open-stats";
const WORKFLOW_STATUS_KEY = "pi-mobile-workflow-action";
const OPEN_STATS_ACTION = "open_stats";

interface WorkflowCommandContext {
    ui: {
        setStatus: (key: string, text: string | undefined) => void;
    };
}

export default function registerPiMobileWorkflowExtension(pi: {
    registerCommand: (name: string, options: {
        description?: string;
        handler: (args: string, ctx: WorkflowCommandContext) => Promise<void>;
    }) => void;
}): void {
    pi.registerCommand(STATS_COMMAND_NAME, {
        description: "Internal Pi Mobile workflow command",
        handler: async (_args, ctx) => {
            const payload = JSON.stringify({ action: OPEN_STATS_ACTION });
            ctx.ui.setStatus(WORKFLOW_STATUS_KEY, payload);
            ctx.ui.setStatus(WORKFLOW_STATUS_KEY, undefined);
        },
    });
}
