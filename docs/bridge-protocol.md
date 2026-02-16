# Bridge Protocol Reference

This document describes the WebSocket protocol between the Android client and the Pi Mobile bridge.

## Table of Contents

- [Transport and Endpoint](#transport-and-endpoint)
- [Authentication](#authentication)
- [Envelope Format](#envelope-format)
- [Connection Handshake](#connection-handshake)
- [Bridge Channel Messages](#bridge-channel-messages)
- [RPC Channel Messages](#rpc-channel-messages)
- [Errors](#errors)
- [Health Endpoint](#health-endpoint)
- [Practical Message Sequence](#practical-message-sequence)
- [Reference Files](#reference-files)

## Transport and Endpoint

- Protocol: WebSocket
- Endpoint: `ws://<host>:<port>/ws`
- Optional reconnect identity: `?clientId=<uuid>`

All messages are JSON envelopes with one of two channels:

- `bridge` (control plane)
- `rpc` (pi RPC payloads)

## Authentication

A valid bridge token is required.

Supported headers:

- `Authorization: Bearer <token>`
- `x-bridge-token: <token>`

Notes:

- token in query string is not accepted
- invalid token -> HTTP 401 on websocket upgrade

## Envelope Format

```json
{
  "channel": "bridge",
  "payload": {
    "type": "bridge_ping"
  }
}
```

```json
{
  "channel": "rpc",
  "payload": {
    "id": "req-1",
    "type": "get_state"
  }
}
```

Validation rules:

- envelope must be JSON object
- `channel` must be `bridge` or `rpc`
- `payload` must be JSON object

## Connection Handshake

After WebSocket connect, bridge sends:

```json
{
  "channel": "bridge",
  "payload": {
    "type": "bridge_hello",
    "clientId": "...",
    "resumed": false,
    "cwd": null,
    "reconnectGraceMs": 30000,
    "message": "Bridge skeleton is running"
  }
}
```

If reconnecting with same `clientId`, `resumed` may be `true` and previous `cwd` is restored.

## Bridge Channel Messages

### Request → Response map

| Request `payload.type` | Response `payload.type` | Notes |
|---|---|---|
| `bridge_ping` | `bridge_pong` | Liveness check |
| `bridge_list_sessions` | `bridge_sessions` | Returns grouped session metadata |
| `bridge_get_session_tree` | `bridge_session_tree` | Requires `sessionPath`; supports filter |
| `bridge_navigate_tree` | `bridge_tree_navigation_result` | Requires control lock; uses internal extension command |
| `bridge_set_cwd` | `bridge_cwd_set` | Sets active cwd context for client |
| `bridge_acquire_control` | `bridge_control_acquired` | Acquires write lock for cwd/session |
| `bridge_release_control` | `bridge_control_released` | Releases held lock |

### `bridge_get_session_tree` filters

Allowed values:

- `default`
- `all`
- `no-tools`
- `user-only`
- `labeled-only`

Unknown filter -> `bridge_error` (`invalid_tree_filter`).

### `bridge_navigate_tree`

Request payload:

```json
{
  "type": "bridge_navigate_tree",
  "entryId": "entry-42"
}
```

Response payload:

```json
{
  "type": "bridge_tree_navigation_result",
  "cancelled": false,
  "editorText": "retry from here",
  "currentLeafId": "entry-42",
  "sessionPath": "/.../session.jsonl"
}
```

## RPC Channel Messages

`rpc` channel forwards pi RPC commands/events.

### Preconditions for sending RPC payloads

Client must have:

1. cwd context (`bridge_set_cwd`)
2. control lock (`bridge_acquire_control`)

Otherwise bridge returns `bridge_error` with code `control_lock_required`.

### Forwarding behavior

- Request payload is forwarded to cwd-specific pi subprocess stdin
- pi stdout events are wrapped as `{ channel: "rpc", payload: ... }`
- events are delivered only to the controlling client for that cwd

## Errors

Bridge errors always use:

```json
{
  "channel": "bridge",
  "payload": {
    "type": "bridge_error",
    "code": "error_code",
    "message": "Human readable message"
  }
}
```

Common codes:

- `malformed_envelope`
- `unsupported_bridge_message`
- `missing_cwd_context`
- `invalid_cwd`
- `invalid_session_path`
- `invalid_tree_filter`
- `invalid_tree_entry_id`
- `control_lock_required`
- `control_lock_denied`
- `rpc_forward_failed`
- `tree_navigation_failed`
- `session_index_failed`
- `session_tree_failed`

## Health Endpoint

Optional HTTP endpoint:

- `GET /health`
- enabled by `BRIDGE_ENABLE_HEALTH_ENDPOINT=true`

Response includes:

- uptime
- process manager stats
- connected/reconnectable client counts

When disabled, `/health` returns 404.

## Practical Message Sequence

Minimal sequence for a typical RPC session:

1. Connect websocket with auth token
2. Receive `bridge_hello`
3. Send `bridge_set_cwd`
4. Send `bridge_acquire_control`
5. Send `rpc` command payloads (`get_state`, `prompt`, ...)
6. Receive `rpc` events and `response` payloads
7. Optionally send `bridge_release_control`

## Reference Files

- `bridge/src/protocol.ts`
- `bridge/src/server.ts`
- `bridge/src/process-manager.ts`
- `core-net/src/main/kotlin/com/ayagmar/pimobile/corenet/PiRpcConnection.kt`
- `bridge/test/server.test.ts`
