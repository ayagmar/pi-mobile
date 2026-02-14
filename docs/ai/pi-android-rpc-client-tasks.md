# Pi Mobile (Android) — Execution Task List (One-Shot Agent Friendly)

This task list is optimized for a long-running coding agent (Codex-style): explicit order, hard gates, verification loops, and progress tracking.

> Rule: **Never start the next task unless current task verification is green.**

---

## 0) Operating contract

## 0.1 Scope reminder
Build a native Android client that connects over Tailscale to a laptop bridge for `pi --mode rpc`, with excellent long-chat performance and robust reconnect/session behavior.

## 0.2 Mandatory loop after every task

Run in order:
1. `./gradlew ktlintCheck`
2. `./gradlew detekt`
3. `./gradlew test`
4. If bridge changed: `cd bridge && pnpm run check`
5. Task-specific smoke test (manual or scripted)

If any step fails:
- Fix
- Re-run full loop

## 0.3 Commit policy
After green loop:
1. Read `/home/ayagmar/.agents/skills/commit/SKILL.md`
2. Create one Conventional Commit for the task
3. Do not push

## 0.4 Progress tracker (must be updated each task)
Maintain statuses: `TODO`, `IN_PROGRESS`, `BLOCKED`, `DONE`.

Suggested tracker file: `docs/pi-android-rpc-progress.md`
For each task include:
- status
- commit hash
- verification result
- notes/blockers

---

## Phase 1 — Foundations and architecture lock

### Task 1.1 — Bootstrap Android app + modules
**Goal:** Buildable Android baseline with modular structure.

Deliverables:
- `app/`
- `core-rpc/`
- `core-net/`
- `core-sessions/`
- Compose + navigation with placeholder screens

Acceptance:
- `./gradlew :app:assembleDebug` succeeds
- app launches with placeholders

Commit:
- `chore(app): bootstrap android modular project`

---

### Task 1.2 — Add quality gates (ktlint + detekt + CI)
**Goal:** Enforce quality from day 1.

Deliverables:
- `.editorconfig`, `detekt.yml`
- ktlint + detekt configured
- `.github/workflows/ci.yml` with checks

Acceptance:
- local checks pass
- CI config valid

Commit:
- `chore(quality): configure ktlint detekt and ci`

---

### Task 1.3 — Spike: validate critical RPC/cwd assumptions
**Goal:** Confirm behavior before deeper build.

Checks:
- Validate `pi --mode rpc` JSONL behavior with a tiny local script
- Validate `switch_session` + tool cwd behavior across different project paths
- Record results in `docs/spikes/rpc-cwd-assumptions.md`

Acceptance:
- spike doc has reproducible commands + outcomes
- architecture assumptions confirmed (or revised)

Commit:
- `docs(spike): validate rpc and cwd behavior`

---

## Phase 2 — Bridge service (laptop)

### Task 2.1 — Create bridge project skeleton
**Goal:** Node/TS service scaffold with tests and lint.

Deliverables:
- `bridge/` project with TypeScript
- scripts: `dev`, `start`, `check`, `test`
- base config and logging

Acceptance:
- `cd bridge && pnpm run check` passes

Commit:
- `feat(bridge): bootstrap typescript service`

---

### Task 2.2 — Implement WS envelope protocol + auth
**Goal:** Explicit protocol for bridge and RPC channels.

Protocol:
- `channel: "bridge" | "rpc"`
- token auth required at connect time

Deliverables:
- protocol types and validation
- auth middleware
- error responses for malformed payloads

Acceptance:
- invalid auth rejected
- valid auth accepted
- malformed payload handled safely

Commit:
- `feat(bridge): add websocket envelope protocol and auth`

---

### Task 2.3 — Implement pi RPC subprocess forwarding
**Goal:** Bridge raw RPC payloads to/from pi process.

Deliverables:
- spawn `pi --mode rpc`
- write JSON line to stdin
- read stdout lines and forward via WS `channel: rpc`
- stderr logging isolation

Acceptance:
- E2E: send `get_state`, receive valid response

Commit:
- `feat(bridge): forward pi rpc over websocket`

---

### Task 2.4 — Multi-cwd process manager + locking
**Goal:** Correct multi-project behavior and corruption safety.

Deliverables:
- process manager keyed by cwd
- single controller lock per cwd/session
- idle eviction policy

Acceptance:
- switching between two cwds uses correct process and tool context
- concurrent control attempts are safely rejected

Commit:
- `feat(bridge): manage per-cwd pi processes with locking`

---

### Task 2.5 — Bridge session indexing API
**Goal:** Replace missing RPC list-sessions capability.

Deliverables:
- `bridge_list_sessions`
- parser for session header + latest `session_info` + preview
- grouped output by cwd
- tests with JSONL fixtures

Acceptance:
- returns expected metadata from fixture and real local sessions

Commit:
- `feat(bridge): add session indexing api from jsonl files`

---

### Task 2.6 — Bridge resilience: reconnect and health
**Goal:** Robust behavior on disconnect/crash.

Deliverables:
- health checks
- restart policy for crashed pi subprocess
- reconnect-safe state model

Acceptance:
- forced disconnect and reconnect recovers cleanly

Commit:
- `feat(bridge): add resilience and health management`

---

## Phase 3 — Android transport and protocol core

### Task 3.1 — Implement core RPC models/parser
**Goal:** Typed parse of responses/events from rpc docs.

Deliverables:
- command models (prompt/abort/steer/follow_up/etc.)
- response/event sealed hierarchies
- parser with `ignoreUnknownKeys`

Acceptance:
- tests for response success/failure, message_update, tool events, extension_ui_request

Commit:
- `feat(rpc): add protocol models and parser`

---

### Task 3.2 — Streaming assembler + throttling primitive
**Goal:** Efficient long-stream reconstruction without UI flood.

Deliverables:
- assistant text assembler by message/content index
- throttle/coalescing utility for UI update cadence

Acceptance:
- deterministic reconstruction tests
- throttle tests for bursty deltas

Commit:
- `feat(rpc): add streaming assembler and throttling`

---

### Task 3.3 — WebSocket client transport (`core-net`)
**Goal:** Android WS transport with reconnect lifecycle.

Deliverables:
- connect/disconnect/reconnect
- `Flow<String>` inbound stream
- outbound send queue
- clear connection states

Acceptance:
- integration test with fake WS server

Commit:
- `feat(net): add websocket transport with reconnect support`

---

### Task 3.4 — Android RPC connection orchestrator
**Goal:** Bridge WS + parser + command dispatch in one stable layer.

Deliverables:
- `PiRpcConnection` service
- command send API
- event stream API
- resync helpers (`get_state`, `get_messages`)

Acceptance:
- reconnect triggers deterministic resync successfully

Commit:
- `feat(net): implement rpc connection orchestration`

---

## Phase 4 — Sessions UX and cache

### Task 4.1 — Host profiles and secure token storage
**Goal:** Manage multiple laptop hosts safely.

Deliverables:
- host profile CRUD UI + persistence
- token storage via Keystore-backed mechanism

Acceptance:
- profiles survive restart
- tokens never stored plaintext in raw prefs

Commit:
- `feat(hosts): add host profile management and secure token storage`

---

### Task 4.2 — Session repository + cache (`core-sessions`)
**Goal:** Fast list load and incremental refresh.

Deliverables:
- cached index by host
- background refresh and merge
- search/filter support

Acceptance:
- list appears immediately from cache after restart
- refresh updates changed sessions only

Commit:
- `perf(sessions): implement cached indexed session repository`

---

### Task 4.3 — Sessions screen grouped by cwd
**Goal:** Primary navigation UX.

Deliverables:
- grouped/collapsible cwd sections
- search UI
- resume action wiring

Acceptance:
- resume works across multiple cwds via bridge selection

Commit:
- `feat(ui): add grouped sessions browser by cwd`

---

### Task 4.4 — Session actions: rename/fork/export/compact entry points
**Goal:** Full session management surface from UI.

Deliverables:
- rename (`set_session_name`)
- fork (`get_fork_messages` + `fork`)
- export (`export_html`)
- compact (`compact`)

Acceptance:
- each action works E2E and updates UI state/index correctly

Commit:
- `feat(sessions): add rename fork export and compact actions`

---

## Phase 5 — Chat screen and controls

### Task 5.1 — Streaming chat timeline UI
**Goal:** Smooth rendering of user/assistant/tool content.

Deliverables:
- chat list with stable keys
- assistant streaming text rendering
- tool blocks with collapse/expand

Acceptance:
- long response stream remains responsive

Commit:
- `feat(chat): implement streaming timeline ui`

---

### Task 5.2 — Prompt controls: abort, steer, follow_up
**Goal:** Full message queue behavior parity.

Deliverables:
- input + send
- abort button
- steer/follow-up actions during streaming

Acceptance:
- no protocol errors for streaming queue operations

Commit:
- `feat(chat): add abort steer and follow-up controls`

---

### Task 5.3 — Model/thinking controls
**Goal:** Missing parity item made explicit.

Deliverables:
- cycle model (`cycle_model`)
- cycle thinking (`cycle_thinking_level`)
- visible current values

Acceptance:
- controls update state and survive reconnect resync

Commit:
- `feat(chat): add model and thinking controls`

---

### Task 5.4 — Extension UI protocol support
**Goal:** Support extension-driven dialogs and notifications.

Deliverables:
- handle `extension_ui_request` methods:
  - `select`, `confirm`, `input`, `editor`
  - `notify`, `setStatus`, `setWidget`, `setTitle`, `set_editor_text`
- send matching `extension_ui_response` by id

Acceptance:
- dialog requests unblock agent flow
- fire-and-forget UI requests render non-blocking indicators

Commit:
- `feat(extensions): implement rpc extension ui protocol`

---

## Phase 6 — Performance hardening

### Task 6.1 — Backpressure + bounded buffers
**Goal:** Prevent memory and UI blowups.

Deliverables:
- bounded streaming buffers
- coalescing policy for high-frequency updates
- large tool output default-collapsed behavior

Acceptance:
- stress run (10+ minute stream) without memory growth issues

Commit:
- `perf(chat): add backpressure and bounded buffering`

---

### Task 6.2 — Performance instrumentation + benchmarks
**Goal:** Make performance measurable.

Deliverables:
- baseline metrics script/checklist
- startup/resume/first-token timing logs
- macrobenchmark skeleton (if available in current setup)

Acceptance:
- metrics recorded in `docs/perf-baseline.md`

Commit:
- `perf(app): add instrumentation and baseline metrics`

---

### Task 6.3 — Baseline Profile + release tuning
**Goal:** Improve real-device performance.

Deliverables:
- baseline profile setup
- release build optimization verification

Acceptance:
- `./gradlew :app:assembleRelease` succeeds

Commit:
- `perf(app): add baseline profile and release optimizations`

---

## Phase 7 — Extension workspace (optional but prepared)

### Task 7.1 — Add repo-local extension scaffold from template
**Goal:** Ready path for missing functionality via pi extensions.

Source template:
- `/home/ayagmar/Projects/Personal/pi-extension-template/`

Deliverables:
- `extensions/pi-mobile-ext/`
- customized constants/package name
- sample command/hook

Acceptance:
- `cd extensions/pi-mobile-ext && pnpm run check` passes
- loads with `pi -e ./extensions/pi-mobile-ext/src/index.ts`

Commit:
- `chore(extensions): scaffold pi-mobile extension workspace`

---

## Phase 8 — Documentation and final acceptance

### Task 8.1 — Setup and operations README
**Goal:** Reproducible setup for future you.

Include:
- bridge setup on laptop
- tailscale requirements
- token setup
- Android host config
- troubleshooting matrix

Acceptance:
- fresh setup dry run follows docs successfully

Commit:
- `docs: add end-to-end setup and troubleshooting`

---

### Task 8.2 — Final acceptance report
**Goal:** Explicitly prove Definition of Done.

Deliverables:
- `docs/final-acceptance.md`
- checklist for:
  - connectivity
  - chat controls
  - session flows
  - extension UI protocol
  - reconnect robustness
  - performance budgets
  - quality gates

Acceptance:
- all checklist items marked pass with evidence

Commit:
- `docs: add final acceptance report`

---

## Final Definition of Done (execution checklist)

All required:

1. Android ↔ bridge works over Tailscale with token auth
2. Chat streaming stable for long sessions
3. Abort/steer/follow_up behave correctly
4. Sessions list grouped by cwd with reliable resume across cwds
5. Rename/fork/export/compact work
6. Model + thinking controls work
7. Extension UI request/response fully supported
8. Reconnect and resync are robust
9. Performance budgets met and documented
10. Quality gates green (`ktlintCheck`, `detekt`, `test`, bridge `pnpm run check`)
11. Setup + acceptance docs complete
