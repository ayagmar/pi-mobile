# Pi Mobile Codebase Guide

This document explains how the Pi Mobile project is organized, how data flows through the system, and where to make changes safely.

## Table of Contents

- [System Overview](#system-overview)
- [Repository Layout](#repository-layout)
- [Module Responsibilities](#module-responsibilities)
- [Key Runtime Flows](#key-runtime-flows)
  - [1) Connect and Resume Session](#1-connect-and-resume-session)
  - [2) Prompt and Streaming Events](#2-prompt-and-streaming-events)
  - [3) Reconnect and Resync](#3-reconnect-and-resync)
  - [4) Session Tree Navigation](#4-session-tree-navigation)
- [Bridge Control Model](#bridge-control-model)
- [State Management in Android](#state-management-in-android)
- [Testing Strategy](#testing-strategy)
- [Common Change Scenarios](#common-change-scenarios)
- [Reference Files](#reference-files)

## System Overview

```text
Android App (Compose)
    │ WebSocket (envelope: { channel, payload })
    ▼
Bridge (Node.js)
    │ stdin/stdout JSON RPC
    ▼
pi --mode rpc
    + internal extensions (pi-mobile-tree, pi-mobile-open-stats)
```

The app never talks directly to a pi process. It talks to the bridge, which:

- handles auth and client identity
- manages one pi subprocess per cwd
- enforces single-client control lock per cwd/session
- forwards RPC events and bridge control messages

## Repository Layout

| Path | Purpose |
|---|---|
| `app/` | Android UI, view models, host/session UX |
| `core-rpc/` | Kotlin RPC command/event models and parser |
| `core-net/` | WebSocket transport, envelope routing, reconnect/resync |
| `core-sessions/` | Session index models, cache, repository logic |
| `bridge/` | Node bridge server, protocol, process manager, extensions |
| `docs/` | Human-facing project docs |
| `docs/ai/` | Planning/progress artifacts |

## Module Responsibilities

### `app/` (Android application)

- Compose screens and overlays
- `ChatViewModel`: chat timeline, command palette, extension dialogs/widgets, tree/stats/model sheets
- `RpcSessionController`: high-level session operations backed by `PiRpcConnection`
- Host management and token storage

### `core-rpc/`

- `RpcCommand` sealed models for outgoing commands
- `RpcIncomingMessage` sealed models for incoming events/responses
- `RpcMessageParser` mapping wire `type` → typed event classes

### `core-net/`

- `WebSocketTransport`: reconnecting socket transport with outbound queue
- `PiRpcConnection`:
  - wraps socket messages in envelope protocol
  - routes bridge vs rpc channels
  - performs handshake (`bridge_hello`, cwd set, control acquire)
  - exposes `rpcEvents`, `bridgeEvents`, and `resyncEvents`

### `core-sessions/`

- Host-scoped session index state and filtering
- merge + cache behavior for remote session lists
- in-memory and file cache implementations

### `bridge/`

- `server.ts`: WebSocket server, token validation, protocol dispatch, health endpoint
- `process-manager.ts`: per-cwd forwarders + control locks
- `rpc-forwarder.ts`: pi subprocess lifecycle/restart/backoff
- `session-indexer.ts`: reads/normalizes session `.jsonl` files
- `extensions/`: internal mobile bridge extensions

## Key Runtime Flows

### 1) Connect and Resume Session

1. App creates `PiRpcConnectionConfig` (`url`, `token`, `cwd`, `clientId`)
2. Bridge returns `bridge_hello`
3. If needed, app sends:
   - `bridge_set_cwd`
   - `bridge_acquire_control`
4. App resyncs via:
   - `get_state`
   - `get_messages`
5. If resuming a specific session path, app sends `switch_session`

### 2) Prompt and Streaming Events

1. User sends prompt from `ChatViewModel`
2. `RpcSessionController.sendPrompt()` sends `prompt`
3. Bridge forwards RPC payload to active cwd process
4. pi emits streaming events (`message_update`, tool events, `agent_end`, etc.)
5. `ChatViewModel` updates timeline and streaming state

### 3) Reconnect and Resync

`WebSocketTransport` auto-reconnects with exponential backoff.

On reconnect, `PiRpcConnection`:

- waits for new `bridge_hello`
- re-acquires cwd/control if needed
- emits `RpcResyncSnapshot` after fresh `get_state + get_messages`

This keeps timeline and streaming flags consistent after network interruptions.

### 4) Session Tree Navigation

Tree flow uses both bridge control and internal extension command:

1. App sends `bridge_navigate_tree { entryId }`
2. Bridge checks internal command availability (`get_commands`)
3. Bridge sends RPC `prompt` with internal command:
   - `/pi-mobile-tree <entryId> <statusKey>`
4. Extension emits `setStatus(statusKey, JSON payload)`
5. Bridge parses payload and replies with `bridge_tree_navigation_result`
6. App updates input text and tree state

## Bridge Control Model

The bridge uses lock ownership to prevent conflicting writers.

- Lock scope: cwd (and optional sessionPath)
- Only lock owner can send RPC traffic for that cwd
- Non-owner receives `bridge_error` (`control_lock_required` or `control_lock_denied`)

This protects session integrity when multiple mobile clients are connected.

## State Management in Android

Primary state owner: `ChatViewModel` (`StateFlow<ChatUiState>`).

Important sub-states:

- connection + streaming state
- timeline (windowed history + realtime updates)
- command palette and slash command metadata
- extension dialogs/notifications/widgets/title
- bash dialog state
- stats/model/tree bottom-sheet state

High-level design:

- transport/network concerns stay in `core-net` + `RpcSessionController`
- rendering concerns stay in Compose screens
- event-to-state logic stays in `ChatViewModel`

## Testing Strategy

### Android

- ViewModel-focused unit tests in `app/src/test/...`
- Covers command filtering, extension workflow handling, timeline behavior, queue semantics

### Bridge

- Vitest suites under `bridge/test/...`
- Covers auth, malformed payloads, control locks, reconnect, tree navigation, health endpoint

### Commands

```bash
# Android quality gates
./gradlew ktlintCheck detekt test

# Bridge quality gates
cd bridge && pnpm run check
```

## Common Change Scenarios

### Add a new RPC command end-to-end

1. Add command model in `core-rpc/RpcCommand.kt`
2. Add encoder mapping in `core-net/RpcCommandEncoding.kt`
3. Add controller method in `RpcSessionController`
4. Call from ViewModel/UI
5. Add tests in app + bridge (if bridge control involved)

### Add a new bridge control message

1. Add message handling in `bridge/src/server.ts`
2. Add payload parser/use site in Android (`PiRpcConnection.requestBridge` caller)
3. Add protocol docs in `docs/bridge-protocol.md`
4. Add tests in `bridge/test/server.test.ts`

### Add a new internal extension workflow

Follow `docs/extensions.md` checklist.

## Reference Files

- `app/src/main/java/com/ayagmar/pimobile/chat/ChatViewModel.kt`
- `app/src/main/java/com/ayagmar/pimobile/sessions/RpcSessionController.kt`
- `core-net/src/main/kotlin/com/ayagmar/pimobile/corenet/PiRpcConnection.kt`
- `core-net/src/main/kotlin/com/ayagmar/pimobile/corenet/WebSocketTransport.kt`
- `core-rpc/src/main/kotlin/com/ayagmar/pimobile/corerpc/RpcCommand.kt`
- `core-rpc/src/main/kotlin/com/ayagmar/pimobile/corerpc/RpcIncomingMessage.kt`
- `bridge/src/server.ts`
- `bridge/src/process-manager.ts`
- `bridge/src/session-indexer.ts`
