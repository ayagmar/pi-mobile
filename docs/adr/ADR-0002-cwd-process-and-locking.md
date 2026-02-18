# ADR-0002: Isolate runtime by cwd and enforce control locks

- **Status:** Accepted
- **Date:** 2026-02-18

## Context

Pi operations are cwd-sensitive (tools and repository context depend on working directory). Multiple mobile clients may connect concurrently, and uncoordinated writes could corrupt user intent and session continuity.

We need:

- project isolation
- predictable process reuse
- safe concurrent-client behavior

## Decision

Adopt a **one pi subprocess per cwd** model in the bridge process manager, and enforce **control locks**.

- Runtime isolation:
  - each cwd maps to a dedicated forwarder/process
  - process is lazily started and idled out by TTL
- Control model:
  - client must `bridge_set_cwd` then `bridge_acquire_control`
  - only lock owner can send `rpc` payloads for that cwd/session
  - non-owners get bridge errors (`control_lock_required` / `control_lock_denied`)
- Reconnect behavior:
  - lock ownership is retained for a grace period (`BRIDGE_RECONNECT_GRACE_MS`)

## Consequences

### Positive

- Prevents concurrent conflicting writers for the same working context.
- Preserves cwd semantics and avoids cross-project contamination.
- Enables fast resume by reusing warm subprocesses.

### Negative

- More bridge-side state management (locks, ttl, reconnect ownership).
- Users can observe lock contention when multiple clients target the same cwd/session.

## Alternatives considered

1. **Single shared pi process for all cwd values**
   - Rejected: high risk of context leakage and tool misexecution.
2. **No lock model (last write wins)**
   - Rejected: unsafe for session integrity.
3. **Lock only at session level, not cwd**
   - Rejected: cwd-level conflicts still possible across operations.
