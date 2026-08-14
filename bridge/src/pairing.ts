import { execFileSync } from "node:child_process";

import type { BridgeConfig } from "./config.js";

export const PAIRING_PAYLOAD_TYPE = "pi-mobile-host";
export const PAIRING_PAYLOAD_VERSION = 2;

export interface HostPairingPayload {
    type: typeof PAIRING_PAYLOAD_TYPE;
    version: typeof PAIRING_PAYLOAD_VERSION;
    name: string;
    host: string;
    port: number;
    useTls: boolean;
    token: string;
    shareOrigin?: string;
}

export function createHostPairingPayload(config: BridgeConfig, hostOverride?: string): HostPairingPayload {
    const host = hostOverride?.trim() || pairingHost(config.host);
    const name = host.split(".")[0] || "Pi bridge";

    return {
        type: PAIRING_PAYLOAD_TYPE,
        version: PAIRING_PAYLOAD_VERSION,
        name,
        host,
        port: config.port,
        useTls: Boolean(config.tlsCertFile && config.tlsKeyFile),
        token: config.authToken,
        ...(config.shareOrigin ? { shareOrigin: config.shareOrigin } : {}),
    };
}

function pairingHost(configuredHost: string): string {
    if (configuredHost.endsWith(".ts.net")) return configuredHost;

    const tailscaleDnsName = readTailscaleDnsName();
    if (tailscaleDnsName) return tailscaleDnsName;

    if (configuredHost !== "0.0.0.0" && configuredHost !== "127.0.0.1" && configuredHost !== "::") {
        return configuredHost;
    }

    throw new Error(
        "Could not determine a reachable bridge hostname. Run pnpm pair -- --host <tailscale-hostname>.",
    );
}

function readTailscaleDnsName(): string | null {
    try {
        const raw = execFileSync("tailscale", ["status", "--json"], {
            encoding: "utf8",
            stdio: ["ignore", "pipe", "ignore"],
        });
        const status = JSON.parse(raw) as { Self?: { DNSName?: unknown } };
        const dnsName = status.Self?.DNSName;
        return typeof dnsName === "string" ? dnsName.replace(/\.$/, "") : null;
    } catch {
        return null;
    }
}
