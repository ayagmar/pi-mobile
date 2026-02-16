export type BridgeChannel = "bridge" | "rpc";

export interface BridgeEnvelope {
    channel: BridgeChannel;
    payload: Record<string, unknown>;
}

interface ParseSuccess {
    success: true;
    envelope: BridgeEnvelope;
}

interface ParseFailure {
    success: false;
    error: string;
}

export type ParseEnvelopeResult = ParseSuccess | ParseFailure;

export function parseBridgeEnvelope(raw: string): ParseEnvelopeResult {
    let parsed: unknown;

    try {
        parsed = JSON.parse(raw);
    } catch (error: unknown) {
        return {
            success: false,
            error: `Invalid JSON: ${toErrorMessage(error)}`,
        };
    }

    if (!isRecord(parsed)) {
        return {
            success: false,
            error: "Envelope must be a JSON object",
        };
    }

    if (parsed.channel !== "bridge" && parsed.channel !== "rpc") {
        return {
            success: false,
            error: "Envelope channel must be one of: bridge, rpc",
        };
    }

    if (!isRecord(parsed.payload)) {
        return {
            success: false,
            error: "Envelope payload must be a JSON object",
        };
    }

    return {
        success: true,
        envelope: {
            channel: parsed.channel,
            payload: parsed.payload,
        },
    };
}

export function createBridgeEnvelope(payload: Record<string, unknown>): BridgeEnvelope {
    return {
        channel: "bridge",
        payload,
    };
}

export function createRpcEnvelope(payload: Record<string, unknown>): BridgeEnvelope {
    return {
        channel: "rpc",
        payload,
    };
}

export function createBridgeErrorEnvelope(code: string, message: string): BridgeEnvelope {
    return createBridgeEnvelope({
        type: "bridge_error",
        code,
        message,
    });
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function toErrorMessage(error: unknown): string {
    if (error instanceof Error) {
        return error.message;
    }

    return "Unknown parse error";
}
