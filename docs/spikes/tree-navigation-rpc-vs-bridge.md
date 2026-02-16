# Tree navigation spike: RPC-only vs bridge endpoint

Date: 2026-02-15

## Goal

Decide whether `/tree`-like branch navigation can be implemented with current RPC payloads only, or if we need a bridge extension endpoint.

---

## What tree MVP needs

For a minimal tree navigator we need:

1. Stable node id per message/entry
2. Parent relation (`parentId`) to rebuild branches
3. Basic metadata for display (role/type/timestamp/preview)
4. Current session context (which file/tree we are inspecting)

---

## RPC payload audit

### `get_messages`

Observed against a real `pi --mode rpc` process (fixture session loaded via `switch_session`):

- Response contains linear message objects only (`role`, `content`, provider/model on assistant)
- No entry id
- No parent id
- No branch metadata

Sample first message shape:

```json
{
  "role": "user",
  "content": "Implement feature A with tests"
}
```

### `get_fork_messages`

Observed shape:

- Contains forkable entries
- Fields currently: `entryId`, `text`
- Still no parent relation / graph topology

Sample:

```json
{
  "entryId": "m1",
  "text": "Implement feature A with tests"
}
```

### Conclusion on RPC-only

**No-go** for full tree navigation.

Current RPC gives enough to fork from a selected entry, but not enough to reconstruct branch structure (no parent graph from `get_messages` and no topology in `get_fork_messages`).

---

## Bridge feasibility

Bridge/session JSONL already stores graph fields (`id`, `parentId`, `timestamp`, `type`, nested message role/content), so the bridge can provide a read-only normalized tree payload.

This is consistent with existing bridge responsibilities (e.g., `bridge_list_sessions`).

---

## Decision

✅ **Use bridge extension API** for tree metadata.

- Keep RPC commands for actions (`fork`, `switch_session`, etc.)
- Add bridge read endpoint for tree structure

---

## Proposed bridge contract (implementation target)

### Request

```json
{
  "channel": "bridge",
  "payload": {
    "type": "bridge_get_session_tree",
    "sessionPath": "/abs/path/to/session.jsonl"
  }
}
```

`sessionPath` can default to currently controlled session if omitted.

### Success response

```json
{
  "channel": "bridge",
  "payload": {
    "type": "bridge_session_tree",
    "sessionPath": "/abs/path/to/session.jsonl",
    "rootIds": ["m1"],
    "currentLeafId": "m42",
    "entries": [
      {
        "entryId": "m1",
        "parentId": null,
        "entryType": "message",
        "role": "user",
        "timestamp": "2026-02-01T00:00:01.000Z",
        "preview": "Implement feature A with tests"
      }
    ]
  }
}
```

### Error response

Reuse existing bridge error envelope:

```json
{
  "channel": "bridge",
  "payload": {
    "type": "bridge_error",
    "code": "session_tree_failed",
    "message": "..."
  }
}
```

---

## Notes for MVP phase (next task)

- Build read-only tree UI from `entries` graph
- Use existing RPC `fork(entryId)` to continue from selected node
- Keep rendering simple (list/tree with indentation and branch markers), optimize later
