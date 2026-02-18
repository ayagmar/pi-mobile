# ADR-0001: Use a bridge between Android and pi RPC

- **Status:** Accepted
- **Date:** 2026-02-18

## Context

Pi runs in RPC mode over stdin/stdout JSON messages. Android clients cannot reliably or safely connect to that local process interface directly over the network.

We need remote mobile access over Tailscale with:

- token authentication
- connection lifecycle management
- support for project cwd context
- support for session indexing (not exposed by core RPC)
- room for mobile-specific control messages and workflows

## Decision

Introduce a Node.js bridge as the network-facing endpoint.

- Android connects via WebSocket (`/ws`) to the bridge.
- Messages use an envelope with channels:
  - `bridge` for control-plane operations
  - `rpc` for forwarded pi RPC payloads
- The bridge validates auth token and routes payloads to cwd-specific pi subprocesses.

## Consequences

### Positive

- Clean separation of mobile control plane from raw RPC stream.
- Central place for auth, process lifecycle, and protocol validation.
- Enables features unavailable in raw RPC (session list/tree/freshness from JSONL files).
- Keeps Android code simpler and transport-focused.

### Negative

- Adds an extra process and protocol surface to maintain.
- Requires bridge configuration (`BRIDGE_*`) and operational troubleshooting.

## Alternatives considered

1. **Direct mobile ↔ pi RPC connection**
   - Rejected: no robust remote RPC transport/auth contract for this use case.
2. **Embed bridge logic in app-side proxy only**
   - Rejected: still needs trusted server-side process and filesystem access.
3. **HTTP-only API instead of envelope channels**
   - Rejected: weaker fit for streaming RPC events and bidirectional control.
