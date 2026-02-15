# Pi Mobile RPC Enhancement Tasks (Ordered Iteration Plan)

Updated plan after major parity work landed.

> Rule: execute in order. Do not start next task until current task is green (`ktlintCheck`, `detekt`, `test`, and bridge `pnpm run check` if touched).

---

## 0) Current state snapshot

Already completed:
- Thinking blocks + collapse/expand
- Slash commands palette
- Auto-compaction/auto-retry notifications
- Edit diff viewer
- Bash dialog (run + abort + history)
- Tool argument display + tool icons
- Session stats sheet
- Model picker + set model
- Auto-compaction/auto-retry settings toggles
- Image attachment support

Remaining gaps for fuller pi mono TUI parity:
- None from this plan (all items completed)

Completed in this iteration:
- Task 1.1 — `1f90b3f`
- Task 1.2 — `1f57a2a`
- Task 2.1 — `09e2b27`
- Task 2.2 — `948ace3`
- Task 3.1 — `4472f89`
- Task 3.2 — `360aa4f`
- Task 4.1 — `5ca89ce`
- Task 4.2 — `5dd4b48`

---

## 1) Protocol conformance hardening (P0)

### Task 1.1 — Lock RPC parser/mapper behavior with tests
**Priority:** CRITICAL  
**Goal:** Prevent regressions in RPC field mapping.

Scope:
- Add tests for:
  - `get_session_stats` nested shape (`tokens`, `cost`, `totalMessages`)
  - `bash` fields (`truncated`, `fullOutputPath`)
  - `get_available_models` fields (`reasoning`, `maxTokens`, `cost.*`)
  - `set_model` direct model payload
  - `get_fork_messages` using `text`
- Keep backward-compatible fallback coverage for legacy field names.

Files:
- `app/src/test/.../sessions/RpcSessionController*Test.kt` (create if missing)
- optionally `core-rpc/src/test/.../RpcMessageParserTest.kt`

Acceptance:
- All mapping tests pass and fail if field names regress.

---

### Task 1.2 — Add parser support for missing lifecycle events
**Priority:** HIGH

Scope:
- Add models + parser branches for:
  - `message_start`
  - `message_end`
  - `turn_start`
  - `turn_end`
  - `extension_error`

Files:
- `core-rpc/src/main/kotlin/.../RpcIncomingMessage.kt`
- `core-rpc/src/main/kotlin/.../RpcMessageParser.kt`
- `core-rpc/src/test/kotlin/.../RpcMessageParserTest.kt`

Acceptance:
- Event parsing tests added and passing.

---

## 2) Chat UX completeness (P1)

### Task 2.1 — Surface lifecycle and extension errors in chat
**Priority:** HIGH

Scope:
- Show subtle system notifications for:
  - message/turn boundaries (optional minimal indicators)
  - extension runtime errors (`extension_error`)
- Keep non-intrusive UX (no modal interruption).

Files:
- `app/src/main/java/.../chat/ChatViewModel.kt`
- `app/src/main/java/.../ui/chat/ChatScreen.kt`

Acceptance:
- Extension errors visible to user with context (extension path/event/error).
- No crashes on unknown lifecycle event payloads.

---

### Task 2.2 — Steering/follow-up mode controls
**Priority:** HIGH

Scope:
- Implement RPC commands/UI for:
  - `set_steering_mode` (`all` | `one-at-a-time`)
  - `set_follow_up_mode` (`all` | `one-at-a-time`)
- Expose in settings (or chat settings panel).
- Read current mode from `get_state` and reflect in UI.

Files:
- `core-rpc/src/main/kotlin/.../RpcCommand.kt`
- `app/src/main/java/.../sessions/SessionController.kt`
- `app/src/main/java/.../sessions/RpcSessionController.kt`
- `app/src/main/java/.../ui/settings/SettingsViewModel.kt`
- `app/src/main/java/.../ui/settings/SettingsScreen.kt`

Acceptance:
- User can change both modes and values persist for active session.

---

## 3) Tree navigation track (P2)

### Task 3.1 — Technical spike for `/tree` equivalent
**Priority:** MEDIUM

Scope:
- Verify whether current RPC payloads expose enough branch metadata.
- If insufficient, define bridge extension API (read-only session tree endpoint).
- Write design doc with chosen approach and payload schema.

Deliverable:
- `docs/spikes/tree-navigation-rpc-vs-bridge.md`

Acceptance:
- Clear go/no-go decision and implementation contract.

---

### Task 3.2 — Implement minimal tree view (MVP)
**Priority:** MEDIUM

Scope:
- Basic branch-aware navigation screen:
  - current path
  - branch points
  - jump-to-entry
- No fancy rendering needed for MVP; correctness first.

Acceptance:
- User can navigate history branches and continue from selected point.

---

## 4) Documentation and polish (P3)

### Task 4.1 — Keyboard shortcuts / gestures help screen
**Priority:** LOW

Scope:
- Add in-app help card/page documenting chat actions and gestures.

Acceptance:
- Accessible from settings and up to date with current UI.

---

### Task 4.2 — README/docs sync with implemented features
**Priority:** LOW

Scope:
- Update stale README limitations (image support now exists).
- Document command palette, thinking blocks, bash dialog, stats, model picker.

Files:
- `README.md`
- `docs/testing.md` if needed

Acceptance:
- No known stale statements in docs.

---

## 5) Verification loop (mandatory after each task)

```bash
./gradlew ktlintCheck
./gradlew detekt
./gradlew test
# if bridge changed:
(cd bridge && pnpm run check)
```

---

## Ordered execution queue (next)

All tasks in this plan are complete.

Next recommended step: define a new backlog for post-parity polish/performance.
