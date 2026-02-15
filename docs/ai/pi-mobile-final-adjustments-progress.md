# Pi Mobile — Final Adjustments Progress Tracker

Status values: `TODO` | `IN_PROGRESS` | `BLOCKED` | `DONE`

> Tracker paired with: `docs/ai/pi-mobile-final-adjustments-plan.md`
> Benchmark-specific tracking intentionally removed for now.

---

## Mandatory verification loop (after every task)

```bash
./gradlew ktlintCheck
./gradlew detekt
./gradlew test
(cd bridge && pnpm run check)
```

---

## Already completed (reference)

| Task | Commit message | Commit hash | Notes |
|---|---|---|---|
| Move New Session to Sessions tab | refactor: move New Session to Sessions tab + add chat auto-scroll | 88d1324 | Button now in Sessions header, navigates to Chat |
| Chat auto-scroll | refactor: move New Session to Sessions tab + add chat auto-scroll | 88d1324 | Auto-scrolls to bottom when new messages arrive |
| Fix ANSI escape codes in chat | fix(chat): strip ANSI codes and fix tree layout overflow | 61061b2 | Strips terminal color codes from status messages |
| Tree layout overflow fix | fix(chat): strip ANSI codes and fix tree layout overflow | 61061b2 | Uses LazyRow for filter chips |
| Navigate back to Sessions fix | fix(nav): allow returning to Sessions after resume | 2cc0480 | Fixed backstack and Channel navigation |
| Chat reload on connection | fix(chat): reload messages when connection becomes active | e8ac20f | Auto-loads messages when CONNECTED |

---

## Critical UX fixes (immediate)

| Order | Task | Status | Commit message | Commit hash | Verification | Notes |
|---|---|---|---|---|---|---|
| 1 | C1 Fix "New Session" error message bug | DONE | fix(sessions): connect before new_session + navigate cleanly |  | ktlint✅ detekt✅ test✅ bridge✅ | Finalized via C4 connection architecture (no more forced resume hack) |
| 2 | C4 Persistent bridge connection (architectural fix for C1) | DONE | feat(sessions): persist/reuse bridge connection across new/resume |  | ktlint✅ detekt✅ test✅ bridge✅ | Added `ensureConnected`, warmup on host/session load, and activity teardown disconnect |
| 3 | C2 Compact chat header | DONE | feat(chat): compact header during streaming + keep model access |  | ktlint✅ detekt✅ test✅ bridge✅ | Streaming mode now hides non-essential header actions and uses compact model/thinking controls |
| 4 | C3 Flatten directory explorer | DONE | fix(sessions): make New Session work + add flat view toggle | e81d27f |  | "All" / "Tree" toggle implemented |

---

## Quick wins

| Order | Task | Status | Commit message | Commit hash | Verification | Notes |
|---|---|---|---|---|---|---|
| 5 | Q1 Fix image-only prompt mismatch | DONE | fix(chat): allow image-only prompt flow and guard failed image encoding |  | ktlint✅ detekt✅ test✅ bridge✅ | ChatViewModel now allows empty text when image payloads exist |
| 6 | Q2 Add full tree filters (`all` included) | DONE | feat(tree): add all filter end-to-end (bridge + app) |  | ktlint✅ detekt✅ test✅ bridge✅ | Added `all` in bridge validator/indexer + chat tree filter chips |
| 7 | Q3 Command palette built-in parity layer | DONE | feat(chat): add built-in command support states in palette |  | ktlint✅ detekt✅ test✅ bridge✅ | Built-ins now appear as supported/bridge-backed/unsupported with explicit behavior |
| 8 | Q4 Global collapse/expand controls | DONE | feat(chat): add global collapse/expand for tools and reasoning |  | ktlint✅ detekt✅ test✅ bridge✅ | Added one-tap header controls with view-model actions for tools/reasoning expansion |
| 9 | Q5 Wire frame metrics into live chat | DONE | feat(perf): enable streaming frame-jank logging in chat screen |  | ktlint✅ detekt✅ test✅ bridge✅ | Hooked `StreamingFrameMetrics` into ChatScreen with per-jank log output |
| 10 | Q6 Transport preference setting parity | DONE | feat(settings): add transport preference parity with websocket fallback |  | ktlint✅ detekt✅ test✅ bridge✅ | Added `auto`/`websocket`/`sse` preference UI, persistence, and runtime fallback to websocket with explicit notes |
| 11 | Q7 Queue inspector UX for pending steer/follow-up | DONE | feat(chat): add streaming queue inspector for steer/follow-up |  | ktlint✅ detekt✅ test✅ bridge✅ | Added pending queue inspector card during streaming with per-item remove/clear actions and delivery-mode visibility |

---

## Stability + security fixes

| Order | Task | Status | Commit message | Commit hash | Verification | Notes |
|---|---|---|---|---|---|---|
| 12 | F1 Bridge event isolation + lock correctness | DONE | fix(bridge): isolate rpc events to control owner per cwd |  | ktlint✅ detekt✅ test✅ bridge✅ | RPC forwarder events now require active control ownership before fan-out; added tests for shared-cwd isolation and post-release send rejection |
| 13 | F2 Reconnect/resync race hardening | DONE | fix(core-net): harden reconnect resync epochs and pending requests |  | ktlint✅ detekt✅ test✅ bridge✅ | Added reconnect epoch gating, cancelled pending request responses on reconnect/disconnect, and synced streaming flag from resync snapshots |
| 14 | F3 Bridge auth + exposure hardening | DONE | fix(bridge): harden token auth and exposure defaults |  | ktlint✅ detekt✅ test✅ bridge✅ | Added constant-time token hash compare, health endpoint exposure toggle, non-loopback bind warnings, and README security guidance |
| 15 | F4 Android network security tightening | DONE | fix(android): tighten cleartext policy to tailscale hostnames |  | ktlint✅ detekt✅ test✅ bridge✅ | Scoped cleartext to `localhost` + `*.ts.net`, set `usesCleartextTraffic=false`, and documented MagicDNS/Tailnet assumptions |
| 16 | F5 Bridge session index scalability | DONE | perf(bridge): cache session metadata by stat signature |  | ktlint✅ detekt✅ test✅ bridge✅ | Added session metadata cache keyed by file mtime/size to avoid repeated full file reads for unchanged session indexes |

---

## Medium maintainability improvements

| Order | Task | Status | Commit message | Commit hash | Verification | Notes |
|---|---|---|---|---|---|---|
| 17 | M1 Replace service locator with explicit DI | DONE | refactor(di): replace app service locator with explicit graph |  | ktlint✅ detekt✅ test✅ bridge✅ | Introduced AppGraph dependency container and removed global `AppServices` singleton usage from routes/viewmodel factories |
| 18 | M2 Split god classes (complexity-focused, non-rigid) | DONE | refactor(chat): extract overlay and command palette components |  | ktlint✅ detekt✅ test✅ bridge✅ | Extracted extension dialogs, notifications, and command palette from `ChatScreen.kt` into dedicated `ChatOverlays.kt` and tightened DI wiring split from M1 |
| 19 | M3 Unify streaming/backpressure runtime pipeline | DONE | refactor(core-rpc): remove unused backpressure pipeline abstractions |  | ktlint✅ detekt✅ test✅ bridge✅ | Removed unused `BackpressureEventProcessor`, `StreamingBufferManager`, `BoundedEventBuffer` and their tests to keep a single runtime path based on `AssistantTextAssembler` + `UiUpdateThrottler` |
| 20 | M4 Tighten static analysis rules/suppressions | TODO |  |  |  |  |

---

## Theming + Design System (after architecture cleanup)

| Order | Task | Status | Commit message | Commit hash | Verification | Notes |
|---|---|---|---|---|---|---|
| 21 | T1 Centralized theme architecture (PiMobileTheme) | TODO |  |  |  | Light/dark mode, color schemes |
| 22 | T2 Component design system | TODO |  |  |  | Reusable components, spacing tokens |

---

## Heavy hitters (last)

| Order | Task | Status | Commit message | Commit hash | Verification | Notes |
|---|---|---|---|---|---|---|
| 23 | H1 True `/tree` parity (in-place navigate) | TODO |  |  |  |  |
| 24 | H2 Session parsing alignment with Pi internals | TODO |  |  |  |  |
| 25 | H3 Incremental session history loading strategy | TODO |  |  |  |  |
| 26 | H4 Extension-ize selected hardcoded workflows | TODO |  |  |  |  |

---

## Verification template (paste per completed task)

```text
ktlintCheck: ✅/❌
detekt: ✅/❌
test: ✅/❌
bridge check: ✅/❌
manual smoke: ✅/❌
```

---

## Running log

### Entry template

```text
Date:
Task:
Status change:
Commit:
Verification:
Notes/blockers:
```

### 2026-02-15

```text
Task: C4 (and C1 finalization)
Status change: C4 TODO -> DONE, C1 IN_PROGRESS -> DONE
Commit: pending
Verification:
- ktlintCheck: ✅
- detekt: ✅
- test: ✅
- bridge check: ✅
- manual smoke: ⏳ pending on device
Notes/blockers:
- Added SessionController.ensureConnected()/disconnect() and RpcSessionController connection reuse by host+cwd.
- SessionsViewModel now warms bridge connection after host/session load and reuses it for newSession/resume.
- MainActivity now triggers sessionController.disconnect() on app finish.
```

### 2026-02-15

```text
Task: C2
Status change: TODO -> DONE
Commit: pending
Verification:
- ktlintCheck: ✅
- detekt: ✅
- test: ✅
- bridge check: ✅
- manual smoke: ⏳ pending on device
Notes/blockers:
- Chat header now enters compact mode while streaming.
- Non-essential actions (stats/copy/bash) are hidden during streaming to free vertical space.
- Model selector remains directly accessible in compact mode.
```

### 2026-02-15

```text
Task: Q1
Status change: TODO -> DONE
Commit: pending
Verification:
- ktlintCheck: ✅
- detekt: ✅
- test: ✅
- bridge check: ✅
- manual smoke: ⏳ pending on device
Notes/blockers:
- ChatViewModel.sendPrompt() now allows image-only prompts.
- Added guard for image-encoding failures to avoid sending empty prompt with no payload.
```

### 2026-02-15

```text
Task: Q2
Status change: TODO -> DONE
Commit: pending
Verification:
- ktlintCheck: ✅
- detekt: ✅
- test: ✅
- bridge check: ✅
- manual smoke: ⏳ pending on device
Notes/blockers:
- Added `all` to bridge tree filter whitelist and session-indexer filter type.
- `all` now returns full tree entries (including label/custom entries).
- Added app-side tree filter option chip for `all`.
```

### 2026-02-15

```text
Task: Q3
Status change: TODO -> DONE
Commit: pending
Verification:
- ktlintCheck: ✅
- detekt: ✅
- test: ✅
- bridge check: ✅
- manual smoke: ⏳ pending on device
Notes/blockers:
- Command palette now labels commands as supported / bridge-backed / unsupported.
- Added explicit built-in entries for interactive TUI commands omitted by RPC get_commands.
- Selecting or sending interactive-only built-ins now shows explicit mobile UX instead of silent no-op.
```

### 2026-02-15

```text
Task: Q4
Status change: TODO -> DONE
Commit: pending
Verification:
- ktlintCheck: ✅
- detekt: ✅
- test: ✅
- bridge check: ✅
- manual smoke: ⏳ pending on device
Notes/blockers:
- Added global "Collapse all" / "Expand all" controls for tools and reasoning.
- Hooked controls to new ChatViewModel actions for timeline-wide expansion state updates.
- Added coverage for global expand/collapse behavior in ChatViewModel tests.
```

### 2026-02-15

```text
Task: Q5
Status change: TODO -> DONE
Commit: pending
Verification:
- ktlintCheck: ✅
- detekt: ✅
- test: ✅
- bridge check: ✅
- manual smoke: ⏳ pending on device
Notes/blockers:
- Activated StreamingFrameMetrics in ChatScreen when streaming is active.
- Added jank logs with severity/frame-time/dropped-frame estimate for live chat rendering.
```

### 2026-02-15

```text
Task: Q6
Status change: TODO -> DONE
Commit: pending
Verification:
- ktlintCheck: ✅
- detekt: ✅
- test: ✅
- bridge check: ✅
- manual smoke: ⏳ pending on device
Notes/blockers:
- Added transport preference setting (`auto` / `websocket` / `sse`) in Settings with persistence.
- SessionController now exposes transport preference APIs and RpcSessionController applies runtime websocket fallback.
- Added clear effective transport + fallback note in UI when SSE is requested.
```

### 2026-02-15

```text
Task: Q7
Status change: TODO -> DONE
Commit: pending
Verification:
- ktlintCheck: ✅
- detekt: ✅
- test: ✅
- bridge check: ✅
- manual smoke: ⏳ pending on device
Notes/blockers:
- Added streaming queue inspector in chat for steer/follow-up submissions.
- Queue inspector shows delivery modes (`all` / `one-at-a-time`) and supports remove/clear actions.
- Queue state auto-resets when streaming ends and is covered by ChatViewModel tests.
```

### 2026-02-15

```text
Task: F1
Status change: TODO -> DONE
Commit: pending
Verification:
- ktlintCheck: ✅
- detekt: ✅
- test: ✅
- bridge check: ✅
- manual smoke: ⏳ pending on device
Notes/blockers:
- Tightened bridge RPC event fan-out so only the client that currently holds control for a cwd receives process events.
- Added server tests proving no same-cwd RPC leakage to non-controlling clients.
- Added regression test ensuring RPC send is rejected once control is released.
```

### 2026-02-15

```text
Task: F2
Status change: TODO -> DONE
Commit: pending
Verification:
- ktlintCheck: ✅
- detekt: ✅
- test: ✅
- bridge check: ✅
- manual smoke: ⏳ pending on device
Notes/blockers:
- Added lifecycle epoch gating around reconnect synchronization to prevent stale resync snapshots from applying after lifecycle changes.
- Pending RPC request deferred responses are now cancelled on reconnect/disconnect transitions to avoid stale waits.
- RpcSessionController now consumes resync snapshots and refreshes streaming flag from authoritative state.
```

### 2026-02-15

```text
Task: F3
Status change: TODO -> DONE
Commit: pending
Verification:
- ktlintCheck: ✅
- detekt: ✅
- test: ✅
- bridge check: ✅
- manual smoke: ⏳ pending on device
Notes/blockers:
- Replaced direct token string equality with constant-time hash digest comparison.
- Added explicit `BRIDGE_ENABLE_HEALTH_ENDPOINT` policy with tests for disabled `/health` behavior.
- Added non-loopback host exposure warnings and documented hardened bridge configuration in README.
```

### 2026-02-15

```text
Task: F4
Status change: TODO -> DONE
Commit: pending
Verification:
- ktlintCheck: ✅
- detekt: ✅
- test: ✅
- bridge check: ✅
- manual smoke: ⏳ pending on device
Notes/blockers:
- Tightened debug/release network security configs to disable global cleartext and allow only `localhost` + `*.ts.net`.
- Explicitly set `usesCleartextTraffic=false` in AndroidManifest.
- Updated README connect/security guidance to prefer Tailnet MagicDNS hostnames and document scoped cleartext assumptions.
```

### 2026-02-15

```text
Task: F5
Status change: TODO -> DONE
Commit: pending
Verification:
- ktlintCheck: ✅
- detekt: ✅
- test: ✅
- bridge check: ✅
- manual smoke: ⏳ pending on device
Notes/blockers:
- Added session metadata cache in session indexer using file stat signatures (mtime/size).
- Unchanged session files now skip re-read/re-parse during repeated `bridge_list_sessions` calls.
- Added regression test proving cached reads are reused and invalidated when a session file changes.
```

### 2026-02-15

```text
Task: M1
Status change: TODO -> DONE
Commit: pending
Verification:
- ktlintCheck: ✅
- detekt: ✅
- test: ✅
- bridge check: ✅
- manual smoke: ⏳ pending on device
Notes/blockers:
- Added `AppGraph` as explicit dependency container built in MainActivity and passed into the app root.
- Removed `AppServices` singleton and migrated Chat/Settings/Sessions/Hosts routes + viewmodel factories to explicit dependencies.
- MainActivity lifecycle teardown now disconnects via graph-owned SessionController instance.
```

### 2026-02-15

```text
Task: M2
Status change: TODO -> DONE
Commit: pending
Verification:
- ktlintCheck: ✅
- detekt: ✅
- test: ✅
- bridge check: ✅
- manual smoke: ⏳ pending on device
Notes/blockers:
- Extracted extension dialogs, notifications, and command palette rendering from `ChatScreen.kt` into new `ChatOverlays.kt`.
- Reduced `ChatScreen.kt` responsibilities toward timeline/layout concerns while preserving behavior.
- Continued DI cleanup from M1 by keeping route/factory wiring explicit and test-safe.
```

### 2026-02-15

```text
Task: M3
Status change: TODO -> DONE
Commit: pending
Verification:
- ktlintCheck: ✅
- detekt: ✅
- test: ✅
- bridge check: ✅
- manual smoke: ⏳ pending on device
Notes/blockers:
- Removed `BackpressureEventProcessor`, `StreamingBufferManager`, and `BoundedEventBuffer` from `core-rpc` because they were not used by app runtime.
- Removed corresponding isolated tests to avoid maintaining dead abstractions.
- Runtime streaming path now clearly centers on `AssistantTextAssembler` and `UiUpdateThrottler`.
```

---

## Overall completion

- Backlog tasks: 26
- Backlog done: 19
- Backlog in progress: 0
- Backlog blocked: 0
- Backlog remaining (not done): 7
- Reference completed items (not counted in backlog): 6

---

## Quick checklist

- [x] Critical UX fixes complete
- [x] Quick wins complete
- [x] Stability/security fixes complete
- [ ] Maintainability improvements complete
- [ ] Theming + Design System complete
- [ ] Heavy hitters complete (or documented protocol limits)
- [ ] Final green run (`ktlintCheck`, `detekt`, `test`, bridge check)

---

## UX Issues from User Feedback (for reference)

1. **"No active session" on New Session** — Error message shows when creating new session, should show success
2. **Top nav blocks streaming view** — Header too tall, obscures content during streaming
3. **Directory explorer pain** — Have to click ▶ on each CWD individually to find sessions
4. **Auto-scroll works** — ✅ Fixed in commit 88d1324
5. **ANSI codes stripped** — ✅ Fixed in commit 61061b2
6. **Navigate back to Sessions** — ✅ Fixed in commit 2cc0480
