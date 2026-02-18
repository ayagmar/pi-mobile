# Architecture Decision Records (ADRs)

This directory tracks durable architecture decisions for Pi Mobile.

## ADR Index

| ADR | Title | Status | Date |
|---|---|---|---|
| [ADR-0001](ADR-0001-bridge-required.md) | Use a bridge between Android and pi RPC | Accepted | 2026-02-18 |
| [ADR-0002](ADR-0002-cwd-process-and-locking.md) | Isolate runtime by cwd and enforce control locks | Accepted | 2026-02-18 |
| [ADR-0003](ADR-0003-reconnect-resync-and-freshness.md) | Recover with resync and protect against cross-device drift | Accepted | 2026-02-18 |

## Notes

- ADRs document **why** decisions were made, not just how code works.
- If a decision changes materially, add a new ADR that supersedes the old one.
