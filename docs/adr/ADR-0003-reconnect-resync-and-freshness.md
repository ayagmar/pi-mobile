# ADR-0003: Recover with resync and protect against cross-device drift

- **Status:** Accepted
- **Date:** 2026-02-18

## Context

Mobile networks are intermittent. Socket reconnect alone is insufficient because client state may diverge from server/runtime state after temporary disconnects or edits from another client.

Risks:

- stale timeline after reconnect
- wrong streaming flags
- user editing on outdated session state

## Decision

Use a two-part consistency strategy:

1. **Reconnect + deterministic resync**
   - transport reconnects with backoff
   - on reconnect, client waits for `bridge_hello`, reapplies cwd/control
   - then fetches fresh `get_state` + `get_messages`
   - emits a resync snapshot consumed by ViewModels

2. **Session freshness monitoring**
   - client polls `bridge_get_session_freshness` for active session
   - bridge returns fingerprint (`mtime`, size, entryCount, lastEntryId, hash tail)
   - on mismatch outside local mutation grace window:
     - show coherency warning
     - prompt user to **Sync now**

## Consequences

### Positive

- Stronger eventual consistency after transient disconnections.
- Better protection against cross-device write conflicts.
- Clear user affordance when stale state is suspected.

### Negative

- Additional control traffic for polling.
- More client-side state/UX complexity (warning + sync paths).

## Alternatives considered

1. **Reconnect without explicit resync**
   - Rejected: stale local state can persist unnoticed.
2. **Manual sync only (no polling)**
   - Rejected: poor UX; users often miss hidden divergence.
3. **Server push freshness invalidation only**
   - Rejected for now: would require broader bridge/runtime event contract changes.
