# Pi Mobile RPC Enhancement Progress Tracker

Status values: `TODO` | `IN_PROGRESS` | `BLOCKED` | `DONE` | `DE_SCOPED`

> Last updated: 2026-02-15

---

## Phase 1 — Core UX Parity (Critical)

| Task | Status | Commit | Verification | Notes |
|------|--------|--------|--------------|-------|
| **1.1** Reasoning/Thinking Block Display | `DONE` | a5b5611 | ✅ ktlintCheck, detekt, test, :app:assembleDebug | Parse `thinking_delta` events, display with toggle |
| **1.2** Slash Commands Palette | `DONE` | 51da6e4 | ✅ ktlintCheck, detekt, test, :app:assembleDebug | Implement `get_commands`, add command palette UI |
| **1.3** Auto-Compaction/Retry Event Handling | `DONE` | b7affd1 | ✅ ktlintCheck, detekt, test, :app:assembleDebug | Show banners for compaction/retry events |

### Phase 1 Completion Criteria
- [x] Thinking blocks visible and toggleable
- [x] Command palette functional with search
- [x] Compaction/retry events show notifications

---

## Phase 2 — Enhanced Tool Display

| Task | Status | Commit | Verification | Notes |
|------|--------|--------|--------------|-------|
| **2.1** File Edit Diff View | `DONE` | 653cfe6 | ✅ ktlintCheck, detekt, test, :app:assembleDebug | Show unified diff for edit tool calls |
| **2.2** Bash Tool Execution UI | `DONE` | ce76ea5 | ✅ ktlintCheck, detekt, test, :app:assembleDebug | Full bash dialog with history, abort, exit code |
| **2.3** Enhanced Tool Argument Display | `DONE` | ce76ea5 | ✅ ktlintCheck, detekt, test, :app:assembleDebug | Tool icons, collapsible arguments, copy functionality |

### Phase 2 Completion Criteria
- [x] Edit operations show diffs with syntax highlight
- [x] Bash commands executable from UI
- [x] Tool arguments visible and copyable

---

## Phase 3 — Session Management Enhancements

| Task | Status | Commit | Verification | Notes |
|------|--------|--------|--------------|-------|
| **3.1** Session Stats Display | `DONE` | 5afd90e | ✅ ktlintCheck, detekt, test, :app:assembleDebug | Token counts, cost, message counts in sheet |
| **3.2** Model Picker (Beyond Cycling) | `DONE` | 5afd90e | ✅ ktlintCheck, detekt, test, :app:assembleDebug | Full model list with search, long-press to open |
| **3.3** Tree Navigation (/tree equivalent) | `TODO` | - | - | Visual conversation tree navigation |

### Phase 3 Completion Criteria
- [x] Stats visible in bottom sheet
- [x] Model picker with all capabilities
- [ ] Tree view for history navigation

---

## Phase 4 — Power User Features

| Task | Status | Commit | Verification | Notes |
|------|--------|--------|--------------|-------|
| **4.1** Auto-Compaction Toggle | `DONE` | 6c2153d | ✅ ktlintCheck, detekt, test, :app:assembleDebug | Settings toggles with SharedPreferences persistence |
| **4.2** Image Attachment Support | `DONE` | 932628a | ✅ ktlintCheck, detekt, test, :app:assembleDebug | Photo picker, thumbnails, base64 encoding, size limit |

### Phase 4 Completion Criteria
- [x] Auto-compaction/retry toggles work
- [x] Images can be attached to prompts

---

## Phase 5 — Quality & Polish

| Task | Status | Commit | Verification | Notes |
|------|--------|--------|--------------|-------|
| **5.1** Message Start/End Event Handling | `TODO` | - | - | Parse message lifecycle events |
| **5.2** Turn Start/End Event Handling | `TODO` | - | - | Parse turn lifecycle events |
| **5.3** Keyboard Shortcuts Help | `TODO` | - | - | Document all gestures/actions |

### Phase 5 Completion Criteria
- [ ] All lifecycle events parsed
- [ ] Shortcuts documented in UI

---

## Commands Implementation Status

| Command | Status | Notes |
|---------|--------|-------|
| `prompt` | ✅ DONE | With images support (structure only) |
| `steer` | ✅ DONE | - |
| `follow_up` | ✅ DONE | - |
| `abort` | ✅ DONE | - |
| `get_state` | ✅ DONE | - |
| `get_messages` | ✅ DONE | - |
| `switch_session` | ✅ DONE | - |
| `set_session_name` | ✅ DONE | - |
| `get_fork_messages` | ✅ DONE | - |
| `fork` | ✅ DONE | - |
| `export_html` | ✅ DONE | - |
| `compact` | ✅ DONE | - |
| `cycle_model` | ✅ DONE | - |
| `cycle_thinking_level` | ✅ DONE | - |
| `new_session` | ✅ DONE | - |
| `extension_ui_response` | ✅ DONE | - |
| `get_commands` | ✅ DONE | For slash commands palette |
| `get_available_models` | ✅ DONE | For model picker |
| `set_model` | ✅ DONE | For model picker |
| `get_session_stats` | ✅ DONE | For stats display |
| `bash` | ✅ DONE | For bash execution |
| `abort_bash` | ✅ DONE | For bash cancellation |
| `set_auto_compaction` | ✅ DONE | For settings toggle |
| `set_auto_retry` | ✅ DONE | For settings toggle |
| `set_steering_mode` | ⬜ TODO | Low priority |
| `set_follow_up_mode` | ⬜ TODO | Low priority |

---

## Events Implementation Status

| Event | Status | Notes |
|-------|--------|-------|
| `message_update` | ✅ DONE | text_delta, thinking_delta handled |
| `tool_execution_start` | ✅ DONE | - |
| `tool_execution_update` | ✅ DONE | - |
| `tool_execution_end` | ✅ DONE | - |
| `extension_ui_request` | ✅ DONE | All dialog methods |
| `agent_start` | ✅ DONE | - |
| `agent_end` | ✅ DONE | - |
| `thinking_delta` | ✅ DONE | **Now implemented** |
| `auto_compaction_start` | ✅ DONE | **Now implemented** |
| `auto_compaction_end` | ✅ DONE | **Now implemented** |
| `auto_retry_start` | ✅ DONE | **Now implemented** |
| `auto_retry_end` | ✅ DONE | **Now implemented** |
| `message_start` | ⬜ TODO | Low priority |
| `message_end` | ⬜ TODO | Low priority |
| `turn_start` | ⬜ TODO | Low priority |
| `turn_end` | ⬜ TODO | Low priority |
| `extension_error` | ⬜ TODO | Medium priority |

---

## Feature Parity Checklist

### Critical (Must Have)
- [ ] Thinking block display
- [ ] Slash commands palette
- [ ] Auto-compaction/retry notifications

### High Priority (Should Have)
- [ ] File edit diff view
- [ ] Session stats display
- [ ] Tool argument display
- [ ] Bash execution UI

### Medium Priority (Nice to Have)
- [ ] Model picker (vs cycling only)
- [ ] Image attachments
- [ ] Tree navigation
- [ ] Settings toggles

### Low Priority (Polish)
- [ ] Message/turn lifecycle events
- [ ] Keyboard shortcuts help
- [ ] Advanced tool output formatting

---

## Per-Task Verification Commands

```bash
# Run all quality checks
./gradlew ktlintCheck
./gradlew detekt
./gradlew test

# If bridge modified:
cd bridge && pnpm run check

# Module-specific tests
./gradlew :core-rpc:test
./gradlew :core-net:test
./gradlew :core-sessions:test

# UI tests
./gradlew :app:connectedCheck

# Assembly
./gradlew :app:assembleDebug
```

---

## Blockers & Dependencies

| Task | Blocked By | Resolution |
|------|------------|------------|
| 3.3 Tree Navigation | RPC protocol gap | Research if `get_messages` parentIds sufficient |
| 4.2 Image Attachments | None | High complexity, defer to later sprint |

---

## Sprint Planning

### Current Sprint: None assigned

### Recommended Next Sprint: Phase 1 (Core Parity)
**Focus:** Tasks 1.1, 1.2, 1.3
**Goal:** Achieve feature parity for thinking blocks and commands

### Upcoming Sprints
- Sprint 2: Phase 2 (Tool Enhancements)
- Sprint 3: Phase 3 (Session Management)
- Sprint 4: Phase 4-5 (Power Features + Polish)

---

## Notes

- Thinking block support is the biggest UX gap vs pi mono TUI
- Slash commands will unlock full extension ecosystem
- Tree navigation may require bridge enhancements
- Image attachments complex due to base64 encoding + size limits
