# Custom Extensions (Pi Mobile)

Pi Mobile uses **internal pi extensions** to provide mobile-specific workflows that are not available through standard RPC commands.

These extensions are loaded by the bridge when it starts `pi --mode rpc`.

## Table of Contents

- [Overview](#overview)
- [Where Extensions Live](#where-extensions-live)
- [Runtime Loading](#runtime-loading)
- [Extension 1: `pi-mobile-tree`](#extension-1-pi-mobile-tree)
- [Extension 2: `pi-mobile-open-stats`](#extension-2-pi-mobile-open-stats)
- [Android Client Integration](#android-client-integration)
- [Extension UI Method Support](#extension-ui-method-support)
- [How to Add a New Internal Extension](#how-to-add-a-new-internal-extension)
- [Troubleshooting](#troubleshooting)
- [Reference Files](#reference-files)

## Overview

Our custom extensions are intentionally **internal plumbing** between:

- the Node bridge (`bridge/`)
- the pi runtime
- the Android client (`app/`)

They are used to:

1. enable in-place tree navigation with structured results
2. trigger mobile-only workflow actions (currently: open stats sheet)

These commands should not appear as user-facing commands in the mobile command palette.

## Where Extensions Live

- `bridge/src/extensions/pi-mobile-tree.ts`
- `bridge/src/extensions/pi-mobile-workflows.ts`

## Runtime Loading

The bridge injects both extensions into every pi RPC subprocess:

- `--extension bridge/src/extensions/pi-mobile-tree.ts`
- `--extension bridge/src/extensions/pi-mobile-workflows.ts`

Implemented in:

- `bridge/src/server.ts` (`createPiRpcForwarder(...args)`)

## Extension 1: `pi-mobile-tree`

**Command name:** `pi-mobile-tree`  
**Purpose:** perform tree navigation from the bridge and return a structured status payload.

### Arguments

`/<command> <entryId> <statusKey>`

- `entryId`: required target tree entry ID
- `statusKey`: required, must start with `pi_mobile_tree_result:`

If arguments are invalid, command exits without side effects.

### Behavior

1. `waitForIdle()`
2. `navigateTree(entryId, { summarize: false })`
3. If navigation is not cancelled, update editor text via `ctx.ui.setEditorText(...)`
4. Emit result via `ctx.ui.setStatus(statusKey, JSON.stringify(payload))`
5. Immediately clear status key via `ctx.ui.setStatus(statusKey, undefined)`

### Result Payload Shape

```json
{
  "cancelled": false,
  "editorText": "retry this branch",
  "currentLeafId": "entry-42",
  "sessionPath": "/home/user/.pi/agent/sessions/...jsonl",
  "error": "optional"
}
```

If an exception occurs, `error` is set and the bridge treats navigation as failed.

## Extension 2: `pi-mobile-open-stats`

**Command name:** `pi-mobile-open-stats`  
**Purpose:** emit a workflow action to open the stats sheet in the Android app.

### Behavior

- Accepts optional action argument
- Default action: `open_stats`
- Rejects unknown actions silently

When accepted, it emits:

- status key: `pi-mobile-workflow-action`
- status text: `{"action":"open_stats"}`

Then clears the status key immediately.

## Android Client Integration

The Android client treats these as internal bridge mechanisms.

### Internal command constants

Defined in `ChatViewModel`:

- `pi-mobile-tree`
- `pi-mobile-open-stats`

They are hidden from visible slash-command results by filtering internal names.

### Builtin command mapping

| Mobile command | Behavior |
|---|---|
| `/tree` | Opens mobile tree sheet directly |
| `/stats` | Attempts internal `/pi-mobile-open-stats`, falls back to local sheet if unavailable |
| `/settings` | Opens Settings tab guidance message |
| `/hotkeys` | Explicitly marked unsupported on mobile |

### Workflow status handling

`ChatViewModel` listens for `extension_ui_request` with:

- `method = setStatus`
- `statusKey = pi-mobile-workflow-action`

If payload action is `open_stats`, it opens the stats sheet.

Non-workflow status keys are currently ignored to avoid UI noise.

## Extension UI Method Support

Pi Mobile currently handles these `extension_ui_request` methods:

| Method | Client behavior |
|---|---|
| `select` | Shows select dialog |
| `confirm` | Shows yes/no dialog |
| `input` | Shows text input dialog |
| `editor` | Shows multiline editor dialog |
| `notify` | Shows transient notification |
| `setStatus` | Handles internal workflow key (`pi-mobile-workflow-action`) |
| `setWidget` | Updates extension widgets above/below editor |
| `setTitle` | Updates chat title |
| `set_editor_text` | Replaces prompt editor text |

Related model types:

- `core-rpc/.../ExtensionUiRequestEvent`
- `core-rpc/.../ExtensionErrorEvent`

## How to Add a New Internal Extension

Use this checklist for safe integration:

1. **Create extension file** in `bridge/src/extensions/`
2. **Register command(s)** with explicit internal names (prefix with `pi-mobile-`)
3. **Load extension in bridge** (`bridge/src/server.ts` forwarder args)
4. **Define status key contract** if extension communicates via `setStatus`
5. **Hide internal commands** in `ChatViewModel.INTERNAL_HIDDEN_COMMAND_NAMES`
6. **Wire client handling** (event parsing + UI updates + fallback behavior)
7. **Add tests**
   - bridge behavior (`bridge/test/server.test.ts`)
   - viewmodel behavior (`app/src/test/...`)
8. **Document payload schemas** in this file

## Troubleshooting

### `/stats` does nothing

Check:

- `get_commands` includes `pi-mobile-open-stats`
- extension loaded by bridge subprocess args
- `setStatus` event payload action is exactly `open_stats`

### Tree navigation returns `tree_navigation_failed`

Check:

- `get_commands` includes `pi-mobile-tree`
- emitted status key starts with `pi_mobile_tree_result:`
- extension returns valid JSON payload in `statusText`

### Internal commands visible in command palette

Check `ChatViewModel.INTERNAL_HIDDEN_COMMAND_NAMES` contains:

- `pi-mobile-tree`
- `pi-mobile-open-stats`

## Reference Files

- `bridge/src/extensions/pi-mobile-tree.ts`
- `bridge/src/extensions/pi-mobile-workflows.ts`
- `bridge/src/server.ts`
- `app/src/main/java/com/ayagmar/pimobile/chat/ChatViewModel.kt`
- `app/src/main/java/com/ayagmar/pimobile/ui/chat/ChatOverlays.kt`
- `bridge/test/server.test.ts`
- `app/src/test/java/com/ayagmar/pimobile/chat/ChatViewModelWorkflowCommandTest.kt`
