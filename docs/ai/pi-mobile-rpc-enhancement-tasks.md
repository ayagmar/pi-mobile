# Pi Mobile RPC Enhancement Tasks

Detailed implementation plan for achieving full pi mono TUI feature parity in pi-mobile Android client.

> **Goal:** Bridge the gap between current implementation and full pi mono TUI capabilities.

---

## Phase 1 — Core UX Parity (Critical)

### Task 1.1 — Reasoning/Thinking Block Display
**Priority:** CRITICAL  
**Complexity:** Medium  
**Files to modify:**
- `core-rpc/src/main/kotlin/.../AssistantTextAssembler.kt`
- `core-rpc/src/main/kotlin/.../RpcIncomingMessage.kt`
- `app/src/main/java/.../chat/ChatViewModel.kt`
- `app/src/main/java/.../chat/ChatUiState.kt`
- `app/src/main/java/.../ui/chat/ChatScreen.kt`

**Background:**
The pi mono TUI shows reasoning/thinking blocks with `Ctrl+T` toggle. The RPC protocol emits `thinking_delta` events alongside `text_delta`. Currently pi-mobile ignores thinking content entirely.

**Deliverables:**

1. **Extend RPC event models:**
   - Add `thinking_start`, `thinking_delta`, `thinking_end` to `AssistantMessageEvent`
   - Parse `type: "thinking"` content blocks in `MessageUpdateEvent`

2. **Enhance text assembler:**
   - Track thinking content separately from assistant text
   - Key by `(messageKey, contentIndex)` with type discriminator
   - Add `thinking: String?` and `isThinkingComplete: Boolean` to assembly result

3. **Update UI state:**
   - Add `showThinking: Boolean` toggle to `ChatUiState`
   - Add `thinkingText: String?` to `ChatTimelineItem.Assistant`
   - Persist user toggle preference across streaming updates

4. **Compose UI:**
   - Show thinking block inline or collapsible below assistant text
   - Distinct visual styling (muted color, italic, background)
   - Toggle button per assistant message (▼/▶)
   - Honor 280-char collapse threshold for thinking too

**Acceptance Criteria:**
- [ ] `thinking_delta` events parsed and assembled correctly
- [ ] Thinking content displays distinct from assistant text
- [ ] Toggle expands/collapses thinking per message
- [ ] State survives configuration changes
- [ ] Long thinking blocks (>280 chars) collapsed by default
- [ ] No duplicate thinking content on reassembly

**Verification:**
```bash
./gradlew :core-rpc:test --tests "*AssistantTextAssemblerTest"
./gradlew :app:assembleDebug
# Manual: Send prompt to thinking-capable model (claude-opus with high thinking)
# Verify thinking blocks appear and toggle works
```

---

### Task 1.2 — Slash Commands Palette
**Priority:** CRITICAL  
**Complexity:** Medium  
**Files to modify:**
- `core-rpc/src/main/kotlin/.../RpcCommand.kt`
- `app/src/main/java/.../sessions/SessionController.kt`
- `app/src/main/java/.../sessions/RpcSessionController.kt`
- `app/src/main/java/.../chat/ChatViewModel.kt`
- `app/src/main/java/.../ui/chat/ChatScreen.kt`

**Background:**
Pi supports commands like `/skill:name`, `/template`, extension commands. The `get_commands` RPC returns available commands. Currently no UI to discover or invoke them.

**Deliverables:**

1. **RPC command support:**
   - Add `GetCommandsCommand` to `RpcCommand.kt`
   - Add `SlashCommand` data class (name, description, source, location)
   - Parse command list from `get_commands` response

2. **Session controller:**
   - Add `suspend fun getCommands(): Result<List<SlashCommand>>`
   - Implement in `RpcSessionController` with caching
   - Refresh on session resume

3. **Command palette UI:**
   - Floating command palette (similar to VS Code Cmd+Shift+P)
   - Triggered by `/` in input field or dedicated button
   - Fuzzy search by name/description
   - Group by source (extension/prompt/skill)
   - Show command description in subtitle

4. **Command invocation:**
   - Selecting command inserts `/command` into input
   - Some commands expand templates inline
   - Extension commands execute immediately on send

**Acceptance Criteria:**
- [ ] `get_commands` returns and parses correctly
- [ ] Command palette opens on `/` type or button tap
- [ ] Fuzzy search filters commands in real-time
- [ ] Commands grouped by source visually
- [ ] Selecting command populates input field
- [ ] Palette dismissible with Escape/back gesture
- [ ] Empty state when no commands match

**Verification:**
```bash
./gradlew :core-rpc:test
./gradlew :app:assembleDebug
# Manual: Open chat, type `/`, verify palette opens
# Verify skills, prompts, extension commands appear
```

---

### Task 1.3 — Auto-Compaction/Retry Event Handling
**Priority:** HIGH  
**Complexity:** Low  
**Files to modify:**
- `core-rpc/src/main/kotlin/.../RpcIncomingMessage.kt`
- `core-rpc/src/main/kotlin/.../RpcMessageParser.kt`
- `app/src/main/java/.../chat/ChatViewModel.kt`
- `app/src/main/java/.../chat/ChatUiState.kt`
- `app/src/main/java/.../ui/chat/ChatScreen.kt`

**Background:**
Pi emits `auto_compaction_start/end` and `auto_retry_start/end` events. Users should see when compaction or retry is happening.

**Deliverables:**

1. **Event models:**
   - Add `AutoCompactionStartEvent`, `AutoCompactionEndEvent`
   - Add `AutoRetryStartEvent`, `AutoRetryEndEvent`
   - Include all fields: reason, attempt, maxAttempts, delayMs, errorMessage

2. **Parser updates:**
   - Register new event types in `RpcMessageParser`

3. **UI notifications:**
   - Show subtle banner during compaction: "Compacting context..."
   - Show banner during retry: "Retrying (2/3) in 2s..."
   - Green success: "Context compacted" or "Retry successful"
   - Red error: "Compaction failed" or "Max retries exceeded"
   - Auto-dismiss after 3 seconds

**Acceptance Criteria:**
- [ ] All four event types parsed correctly
- [ ] Compaction banner shows with reason (threshold/overflow)
- [ ] Retry banner shows attempt count and countdown
- [ ] Success/error states displayed appropriately
- [ ] Banners don't block interaction
- [ ] Multiple simultaneous events queued gracefully

**Verification:**
```bash
./gradlew :core-rpc:test --tests "*RpcMessageParserTest"
./gradlew :app:assembleDebug
# Manual: Trigger long conversation to force compaction
# Or temporarily lower compaction threshold in pi settings
```

---

## Phase 2 — Enhanced Tool Display

### Task 2.1 — File Edit Diff View
**Priority:** HIGH  
**Complexity:** High  
**Files to modify:**
- `app/src/main/java/.../chat/ChatViewModel.kt`
- `app/src/main/java/.../chat/ChatUiState.kt`
- `app/src/main/java/.../ui/chat/ChatScreen.kt`
- `app/src/main/java/.../ui/chat/DiffViewer.kt` (new)

**Background:**
When pi uses the `edit` tool, it modifies files. The TUI shows a nice diff. Currently pi-mobile only shows raw tool output.

**Deliverables:**

1. **Edit tool detection:**
   - Detect when `toolName == "edit"`
   - Parse `arguments` for `path`, `oldString`, `newString`
   - Parse result for success/failure

2. **Diff generation:**
   - Compute unified diff from oldString/newString
   - Support line-based diff for large files
   - Show line numbers
   - Syntax highlight based on file extension

3. **Compose diff viewer:**
   - Side-by-side or inline diff toggle
   - Red for deletions, green for additions
   - Context lines (3 before/after changes)
   - Copy file path button
   - Expand/collapse for large diffs

4. **Integration:**
   - Replace generic tool card for edit operations
   - Maintain collapse/expand behavior

**Acceptance Criteria:**
- [ ] Edit tool calls render as diff view
- [ ] Line numbers shown
- [ ] Syntax highlighting active
- [ ] Side-by-side and inline modes available
- [ ] Large diffs (>50 lines) collapsed by default
- [ ] Copy path functionality works
- [ ] Failed edits show error state

**Verification:**
```bash
./gradlew :app:assembleDebug
# Manual: Ask pi to "edit src/main.kt to add a comment"
# Verify diff shows with proper highlighting
```

---

### Task 2.2 — Bash Tool Execution UI
**Priority:** MEDIUM  
**Complexity:** Medium  
**Files to modify:**
- `core-rpc/src/main/kotlin/.../RpcCommand.kt`
- `app/src/main/java/.../sessions/SessionController.kt`
- `app/src/main/java/.../sessions/RpcSessionController.kt`
- `app/src/main/java/.../ui/chat/ChatScreen.kt`

**Background:**
The `bash` RPC command lets clients execute shell commands. Currently not exposed in UI.

**Deliverables:**

1. **RPC support:**
   - Add `BashCommand` with `command`, `timeoutMs`
   - Add `AbortBashCommand`
   - Parse `BashResult` from response

2. **Session controller:**
   - Add `suspend fun executeBash(command: String): Result<BashResult>`
   - Add `suspend fun abortBash(): Result<Unit>`

3. **UI integration:**
   - "Run Bash" button in chat overflow menu
   - Dialog with command input
   - Streaming output display (like tool execution)
   - Cancel button for long-running commands
   - Exit code display (green 0, red non-zero)
   - Truncation indicator with full log path

**Acceptance Criteria:**
- [ ] Bash dialog opens from overflow menu
- [ ] Command executes and streams output
- [ ] Cancel button aborts running command
- [ ] Exit code displayed
- [ ] Truncated output indicated with path
- [ ] Error state on non-zero exit
- [ ] Command history (last 10) in dropdown

**Verification:**
```bash
./gradlew :app:assembleDebug
# Manual: Open bash dialog, run "ls -la", verify output
# Test cancel with "sleep 10"
```

---

### Task 2.3 — Enhanced Tool Argument Display
**Priority:** MEDIUM  
**Complexity:** Low  
**Files to modify:**
- `app/src/main/java/.../ui/chat/ChatScreen.kt`

**Background:**
Currently tool cards show only tool name and output. Arguments are hidden.

**Deliverables:**

1. **Argument display:**
   - Show collapsed arguments section
   - Tap to expand and view JSON arguments
   - Pretty-print with syntax highlighting
   - Copy arguments JSON button

2. **Tool iconography:**
   - Distinct icons per tool (read, write, edit, bash, grep, find, ls)
   - Color coding by category (read=blue, write=green, edit=yellow, bash=purple)

**Acceptance Criteria:**
- [ ] Arguments section collapsible on each tool card
- [ ] Pretty-printed JSON display
- [ ] Tool-specific icons shown
- [ ] Consistent color coding
- [ ] Copy functionality works

**Verification:**
```bash
./gradlew :app:assembleDebug
# Manual: Trigger any tool call, verify arguments visible
```

---

## Phase 3 — Session Management Enhancements

### Task 3.1 — Session Stats Display
**Priority:** MEDIUM  
**Complexity:** Low  
**Files to modify:**
- `core-rpc/src/main/kotlin/.../RpcCommand.kt`
- `app/src/main/java/.../sessions/SessionController.kt`
- `app/src/main/java/.../sessions/RpcSessionController.kt`
- `app/src/main/java/.../ui/chat/ChatScreen.kt`

**Background:**
`get_session_stats` returns token usage, cache stats, and cost. Currently not displayed.

**Deliverables:**

1. **RPC support:**
   - Add `GetSessionStatsCommand`
   - Parse `SessionStats` response (input/output/cache tokens, cost)

2. **UI display:**
   - Stats button in chat header (bar chart icon)
   - Bottom sheet with detailed stats:
     - Total tokens (input/output/cache read/cache write)
     - Estimated cost
     - Message counts (user/assistant/tool)
     - Session file path (copyable)
   - Real-time updates during streaming

**Acceptance Criteria:**
- [ ] Stats fetch successfully
- [ ] All token types displayed
- [ ] Cost shown with 4 decimal precision
- [ ] Updates during streaming
- [ ] Copy path works
- [ ] Empty state for new sessions

**Verification:**
```bash
./gradlew :app:assembleDebug
# Manual: Open chat, tap stats icon, verify numbers match pi TUI
```

---

### Task 3.2 — Model Picker (Beyond Cycling)
**Priority:** MEDIUM  
**Complexity:** Medium  
**Files to modify:**
- `core-rpc/src/main/kotlin/.../RpcCommand.kt`
- `app/src/main/java/.../sessions/SessionController.kt`
- `app/src/main/java/.../sessions/RpcSessionController.kt`
- `app/src/main/java/.../chat/ChatViewModel.kt`
- `app/src/main/java/.../ui/chat/ChatScreen.kt`

**Background:**
Currently only `cycle_model` is supported. Users should be able to pick specific models.

**Deliverables:**

1. **RPC support:**
   - Add `GetAvailableModelsCommand`
   - Add `SetModelCommand` with provider and modelId
   - Parse full `Model` object (id, name, provider, contextWindow, cost)

2. **Model picker UI:**
   - Replace cycle button with model chip (tap to open picker)
   - Full-screen bottom sheet with:
     - Search by name/provider
     - Group by provider
     - Show context window and cost
     - Thinking capability indicator
     - Currently selected highlight
   - Filter by scoped models only (if configured)

3. **Quick switch:**
   - Keep cycle for rapid switching between favorites
   - Long-press model chip for picker

**Acceptance Criteria:**
- [ ] Available models list fetched
- [ ] Picker shows all model details
- [ ] Search filters in real-time
- [ ] Selection changes model immediately
- [ ] Scoped models filter works
- [ ] Cycle still works for quick switches

**Verification:**
```bash
./gradlew :app:assembleDebug
# Manual: Long-press model chip, select different model
# Verify prompt uses new model
```

---

### Task 3.3 — Tree Navigation (/tree equivalent)
**Priority:** LOW  
**Complexity:** High  
**Files to modify:**
- `core-rpc/src/main/kotlin/.../RpcCommand.kt` (may need new commands)
- `app/src/main/java/.../sessions/SessionController.kt`
- `app/src/main/java/.../ui/tree/` (new package)

**Background:**
Pi's `/tree` lets users navigate conversation history and branch. Complex UI.

**Deliverables:**

1. **Research:**
   - Verify if RPC supports tree navigation
   - Check `get_messages` for parentId relationships
   - May need new bridge endpoints to read JSONL directly

2. **Tree visualization:**
   - Vertical timeline with branches
   - Current path highlighted
   - Tap to jump to any message
   - Branch indicators for fork points
   - Label support (show user-added labels)

3. **Navigation actions:**
   - Jump to point in history
   - Create branch from any point
   - View alternative branches

**Acceptance Criteria:**
- [ ] Tree structure parsed from messages
- [ ] Visual branch representation
- [ ] Tap to navigate history
- [ ] Current position clearly indicated
- [ ] Labels displayed if present

**Note:** This may require extending bridge to expose tree structure if not available via RPC.

---

## Phase 4 — Power User Features

### Task 4.1 — Auto-Compaction Toggle
**Priority:** LOW  
**Complexity:** Low  
**Files to modify:**
- `core-rpc/src/main/kotlin/.../RpcCommand.kt`
- `app/src/main/java/.../sessions/SessionController.kt`
- `app/src/main/java/.../settings/SettingsScreen.kt`

**Background:**
`set_auto_compaction` enables/disables automatic compaction.

**Deliverables:**

1. **RPC support:**
   - Add `SetAutoCompactionCommand(enabled: Boolean)`
   - Add `SetAutoRetryCommand(enabled: Boolean)`

2. **Settings UI:**
   - Toggle in settings screen
   - "Auto-compact context" switch
   - "Auto-retry on errors" switch
   - Persist preference locally

**Acceptance Criteria:**
- [ ] Toggles send correct RPC commands
- [ ] State persists across sessions
- [ ] Visual feedback on change

---

### Task 4.2 — Image Attachment Support
**Priority:** LOW  
**Complexity:** High  
**Files to modify:**
- `app/src/main/java/.../chat/ChatViewModel.kt`
- `app/src/main/java/.../ui/chat/ChatScreen.kt`
- `app/src/main/java/.../ui/chat/ImagePicker.kt` (new)

**Background:**
Pi TUI supports Ctrl+V image paste. Mobile should support camera/gallery.

**Deliverables:**

1. **Image handling:**
   - Photo picker integration
   - Camera capture option
   - Base64 encoding
   - Size limit warning (>5MB)

2. **UI:**
   - Attachment button in input row
   - Thumbnail preview of attached images
   - Remove attachment option
   - Multiple images support

3. **RPC integration:**
   - Include images in `PromptCommand`
   - Support `ImagePayload` with mime type detection

**Acceptance Criteria:**
- [ ] Photo picker opens
- [ ] Camera capture works
- [ ] Images display as thumbnails
- [ ] Base64 encoding correct
- [ ] Model receives images
- [ ] Size limits enforced

---

## Phase 5 — Quality & Polish

### Task 5.1 — Message Start/End Event Handling
**Priority:** MEDIUM  
**Complexity:** Low  
**Files to modify:**
- `core-rpc/src/main/kotlin/.../RpcIncomingMessage.kt`
- `core-rpc/src/main/kotlin/.../RpcMessageParser.kt`

**Background:**
`message_start` and `message_end` events exist but aren't parsed.

**Deliverables:**

1. **Event models:**
   - Add `MessageStartEvent`, `MessageEndEvent`
   - Include complete message in `MessageEndEvent`

2. **Parser:**
   - Register new types

3. **Verification:**
   - Events parsed correctly in tests

**Acceptance Criteria:**
- [ ] Both event types parsed
- [ ] Complete message available in `message_end`

---

### Task 5.2 — Turn Start/End Event Handling
**Priority:** LOW  
**Complexity:** Low  
**Files to modify:**
- Same as Task 5.1

**Background:**
`turn_start`/`turn_end` events mark complete assistant+tool cycles.

**Deliverables:**

1. **Event models:**
   - Add `TurnStartEvent`, `TurnEndEvent`
   - Include assistant message and tool results in `TurnEndEvent`

2. **Potential uses:**
   - Turn-based animations
   - Final confirmation of completed turns
   - Analytics

**Acceptance Criteria:**
- [ ] Events parsed and available

---

### Task 5.3 — Keyboard Shortcuts Help
**Priority:** LOW  
**Complexity:** Low  
**Files to modify:**
- `app/src/main/java/.../ui/settings/SettingsScreen.kt` or new screen

**Background:**
`/hotkeys` in pi shows shortcuts. Mobile equivalent needed.

**Deliverables:**

1. **Shortcuts screen:**
   - Accessible from settings
   - List all gestures/shortcuts:
     - Send: Enter
     - New line: Shift+Enter
     - Abort: Escape gesture
     - Steer: Menu option
     - etc.

**Acceptance Criteria:**
- [ ] All actions documented
- [ ] Searchable

---

## Appendix — RPC Protocol Reference

### Commands to Implement

| Command | Status | Priority |
|---------|--------|----------|
| `prompt` | ✅ DONE | - |
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
| `get_commands` | ⬜ TODO | CRITICAL |
| `get_available_models` | ⬜ TODO | MEDIUM |
| `set_model` | ⬜ TODO | MEDIUM |
| `get_session_stats` | ⬜ TODO | MEDIUM |
| `bash` | ⬜ TODO | MEDIUM |
| `abort_bash` | ⬜ TODO | MEDIUM |
| `set_auto_compaction` | ⬜ TODO | LOW |
| `set_auto_retry` | ⬜ TODO | LOW |
| `set_steering_mode` | ⬜ TODO | LOW |
| `set_follow_up_mode` | ⬜ TODO | LOW |

### Events to Handle

| Event | Status | Priority |
|-------|--------|----------|
| `message_update` | ✅ DONE | - |
| `tool_execution_start` | ✅ DONE | - |
| `tool_execution_update` | ✅ DONE | - |
| `tool_execution_end` | ✅ DONE | - |
| `extension_ui_request` | ✅ DONE | - |
| `agent_start` | ✅ DONE | - |
| `agent_end` | ✅ DONE | - |
| `message_start` | ⬜ TODO | LOW |
| `message_end` | ⬜ TODO | LOW |
| `turn_start` | ⬜ TODO | LOW |
| `turn_end` | ⬜ TODO | LOW |
| `auto_compaction_start` | ⬜ TODO | HIGH |
| `auto_compaction_end` | ⬜ TODO | HIGH |
| `auto_retry_start` | ⬜ TODO | HIGH |
| `auto_retry_end` | ⬜ TODO | HIGH |
| `extension_error` | ⬜ TODO | MEDIUM |

---

## Implementation Order Recommendation

### Sprint 1 (Core Parity)
1. Task 1.1 — Thinking blocks
2. Task 1.2 — Slash commands
3. Task 1.3 — Auto-compaction/retry events

### Sprint 2 (Tool Enhancements)
4. Task 2.3 — Tool argument display
5. Task 2.1 — Edit diff view (basic)
6. Task 2.2 — Bash execution

### Sprint 3 (Session Management)
7. Task 3.1 — Session stats
8. Task 3.2 — Model picker

### Sprint 4 (Polish)
9. Task 5.1, 5.2 — Event handling
10. Task 4.1 — Settings toggles
11. Task 5.3 — Shortcuts help

### Backlog
- Task 3.3 — Tree navigation (requires research)
- Task 4.2 — Image attachments (complex)
