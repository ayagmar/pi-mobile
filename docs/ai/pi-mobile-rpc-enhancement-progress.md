# Pi Mobile RPC Enhancement Progress Tracker

Status values: `TODO` | `IN_PROGRESS` | `BLOCKED` | `DONE` | `DE_SCOPED`

> Last updated: 2026-02-15 (All RPC enhancement backlog tasks completed)

---

## Completed milestones

| Area | Status | Notes |
|---|---|---|
| Thinking blocks + collapse | DONE | Implemented in assembler + chat UI |
| Slash commands palette | DONE | `get_commands` + grouped searchable UI |
| Auto compaction/retry notifications | DONE | Events parsed and surfaced |
| Tool UX enhancements | DONE | icons, arguments, diff viewer |
| Bash UI | DONE | execute/abort/history/output/copy |
| Session stats | DONE | stats sheet in chat |
| Model picker | DONE | available models + set model |
| Auto settings toggles | DONE | auto-compaction + auto-retry |
| Image attachments | DONE | picker + thumbnails + base64 payload |
| RPC schema mismatch fixes | DONE | stats/bash/models/set_model/fork fields fixed in controller parser |

---

## Ordered backlog (current)

| Order | Task | Status | Commit | Verification | Notes |
|---|---|---|---|---|---|
| 1 | Protocol conformance tests (stats/bash/models/set_model/fork) | DONE | `1f90b3f` | `ktlintCheck` ✅ `detekt` ✅ `test` ✅ | Added `RpcSessionControllerTest` conformance coverage for canonical + legacy mapping fields |
| 2 | Parse missing events: `message_start/end`, `turn_start/end`, `extension_error` | DONE | `1f57a2a` | `ktlintCheck` ✅ `detekt` ✅ `test` ✅ | Added parser branches + models and event parsing tests for lifecycle and extension errors |
| 3 | Surface lifecycle + extension errors in chat UX | DONE | `09e2b27` | `ktlintCheck` ✅ `detekt` ✅ `test` ✅ | Added non-blocking lifecycle notifications and contextual extension error notifications in chat |
| 4 | Steering/follow-up mode controls (`set_*_mode`) | DONE | `948ace3` | `ktlintCheck` ✅ `detekt` ✅ `test` ✅ | Added RPC commands, session controller wiring, settings UI selectors, and get_state mode sync |
| 5 | Tree navigation spike (`/tree` equivalent feasibility) | DONE | `4472f89` | `ktlintCheck` ✅ `detekt` ✅ `test` ✅ | Added spike doc; decision: RPC-only is insufficient, add read-only bridge session-tree endpoint |
| 6 | Tree navigation MVP | DONE | `360aa4f` | `ktlintCheck` ✅ `detekt` ✅ `test` ✅ `(cd bridge && pnpm run check)` ✅ | Added bridge session-tree endpoint, app bridge request path, and chat tree sheet with fork-from-entry navigation |
| 7 | Keyboard shortcuts/gestures help screen | DONE | `5ca89ce` | `ktlintCheck` ✅ `detekt` ✅ `test` ✅ | Added settings help card documenting chat actions, gestures, and key interactions |
| 8 | README/docs sync | DONE | `5dd4b48` | `ktlintCheck` ✅ `detekt` ✅ `test` ✅ | README refreshed with current UX capabilities and limitations (including image support) |

---

## Command coverage status

### Implemented
- `prompt`, `steer`, `follow_up`, `abort`, `new_session`
- `get_state`, `get_messages`, `switch_session`
- `set_session_name`, `get_fork_messages`, `fork`
- `export_html`, `compact`
- `cycle_model`, `cycle_thinking_level`
- `get_commands`
- `bash`, `abort_bash`
- `get_session_stats`
- `get_available_models`, `set_model`
- `set_auto_compaction`, `set_auto_retry`
- `set_steering_mode`, `set_follow_up_mode`

### Remaining
- None

---

## Event coverage status

### Implemented
- `message_update` (text + thinking)
- `message_start` / `message_end`
- `turn_start` / `turn_end`
- `tool_execution_start/update/end`
- `extension_ui_request`
- `extension_error`
- `agent_start/end`
- `auto_compaction_start/end`
- `auto_retry_start/end`

### Remaining
- None (parser-level)

---

## Feature parity checklist (pi mono TUI)

- [x] Tool calls visible
- [x] Tool output collapse/expand
- [x] Reasoning visibility + collapse/expand
- [x] File edit diff view
- [x] Slash commands discovery/use
- [x] Model control beyond cycling
- [x] Session stats display
- [x] Image attachments
- [x] Tree navigation equivalent (`/tree`)
- [x] Steering/follow-up delivery mode controls
- [x] Lifecycle/extension error event completeness

---

## Verification commands

```bash
./gradlew ktlintCheck
./gradlew detekt
./gradlew test
# if bridge changed:
(cd bridge && pnpm run check)
```

---

## Blockers / risks

| Task | Risk | Mitigation |
|---|---|---|
| Tree navigation | Bridge/tree payload drift across pi session formats | Keep parser defensive and cover with bridge tests/fixtures |
| Lifecycle UX noise | Too many system notifications can clutter chat | Keep subtle + dismissible + rate-limited |
| Protocol regressions | Field names can drift across pi versions | Add parser conformance tests with real payload fixtures |
