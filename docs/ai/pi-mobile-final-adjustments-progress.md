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
| 8 | Q4 Global collapse/expand controls | TODO |  |  |  |  |
| 9 | Q5 Wire frame metrics into live chat | TODO |  |  |  |  |
| 10 | Q6 Transport preference setting parity | TODO |  |  |  |  |
| 11 | Q7 Queue inspector UX for pending steer/follow-up | TODO |  |  |  |  |

---

## Stability + security fixes

| Order | Task | Status | Commit message | Commit hash | Verification | Notes |
|---|---|---|---|---|---|---|
| 12 | F1 Bridge event isolation + lock correctness | TODO |  |  |  |  |
| 13 | F2 Reconnect/resync race hardening | TODO |  |  |  |  |
| 14 | F3 Bridge auth + exposure hardening | TODO |  |  |  |  |
| 15 | F4 Android network security tightening | TODO |  |  |  |  |
| 16 | F5 Bridge session index scalability | TODO |  |  |  |  |

---

## Medium maintainability improvements

| Order | Task | Status | Commit message | Commit hash | Verification | Notes |
|---|---|---|---|---|---|---|
| 17 | M1 Replace service locator with explicit DI | TODO |  |  |  |  |
| 18 | M2 Split god classes (complexity-focused, non-rigid) | TODO |  |  |  | Reduce `LargeClass` / `LongMethod` / `TooManyFunctions` signals |
| 19 | M3 Unify streaming/backpressure runtime pipeline | TODO |  |  |  |  |
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

---

## Overall completion

- Backlog tasks: 26
- Backlog done: 7
- Backlog in progress: 0
- Backlog blocked: 0
- Backlog remaining (not done): 19
- Reference completed items (not counted in backlog): 6

---

## Quick checklist

- [ ] Critical UX fixes complete
- [ ] Quick wins complete
- [ ] Stability/security fixes complete
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
