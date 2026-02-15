# Pi Mobile — Final Adjustments Plan (Fresh Audit)

Goal: ship an **ultimate** Pi mobile client by prioritizing quick wins and high-risk fixes first, then heavier parity/architecture work last.

Scope focus from fresh audit: RPC compatibility, Kotlin quality, bridge security/stability, UX parity, performance, and Pi alignment.

> No milestones or estimates.
> Benchmark-specific work is intentionally excluded for now.

---

## 0) Mandatory verification loop (after every task)

```bash
./gradlew ktlintCheck
./gradlew detekt
./gradlew test
(cd bridge && pnpm run check)
```

If any command fails:
1. fix
2. rerun full loop
3. only then mark task done

Manual smoke checklist (UI/protocol tasks):
- connect host, resume session, create new session
- prompt/abort/steer/follow_up still work
- tool cards + reasoning blocks + diffs still render correctly
- extension dialogs still work (`select`, `confirm`, `input`, `editor`)
- new session from Sessions tab creates + navigates correctly
- chat auto-scrolls to latest message during streaming

### C4 — Persistent bridge connection (architectural change)
**Why:** Currently the app connects to bridge on-demand per session. This causes friction when:
- Creating new session (no active connection)
- Switching between sessions quickly
- Background/resume scenarios

**User suggestion:** "Shouldn't we establish connection to bridge when we load the application?"

**Primary files:**
- `app/src/main/java/com/ayagmar/pimobile/sessions/RpcSessionController.kt`
- `app/src/main/java/com/ayagmar/pimobile/di/AppServices.kt`
- `app/src/main/java/com/ayagmar/pimobile/ui/PiMobileApp.kt`

**Acceptance:**
- Bridge connection established on app start (if host configured)
- Multiple sessions can share one bridge connection
- `newSession()`, `resumeSession()` just send commands over existing connection
- Proper lifecycle management (disconnect on app kill, reconnect on network issues)

---

## 1) Critical UX fixes (immediate)

### C1 — Fix "New Session" error message bug
**Why:** Creating new session shows "No active session. Resume a session first" which is confusing/incorrect UX.

**Root cause:** `newSession()` tries to send RPC command without an active bridge connection. The connection is only established during `resumeSession()`.

**Potential fix approaches:**
1. **Quick fix:** Have `newSession()` establish connection first (like `resumeSession` does), then send `new_session` command
2. **Better fix (see C4):** Keep persistent bridge connection alive, so `new_session` just works

**Primary files:**
- `app/src/main/java/com/ayagmar/pimobile/sessions/RpcSessionController.kt`
- `app/src/main/java/com/ayagmar/pimobile/sessions/SessionsViewModel.kt`

**Acceptance:**
- New session creation shows success/loading state, not error
- Auto-navigates to chat with new session active
- Works regardless of whether a session was previously resumed

---

### C2 — Compact chat header (stop blocking streaming view)
**Why:** Top nav takes too much vertical space, blocks view of streaming responses.

**Primary files:**
- `app/src/main/java/com/ayagmar/pimobile/ui/chat/ChatScreen.kt`
- `app/src/main/java/com/ayagmar/pimobile/ui/chat/ChatHeader.kt` (extract if needed)

**Acceptance:**
- Header collapses or uses minimal height during streaming
- Essential controls (abort, model selector) remain accessible
- More screen real estate for actual chat content

---

### C3 — Flatten directory explorer (improve CWD browsing UX)
**Why:** Current tree requires clicking each directory one-by-one to see sessions. User sees long path list with ▶ icons.

**Primary files:**
- `app/src/main/java/com/ayagmar/pimobile/ui/sessions/SessionsScreen.kt`
- `app/src/main/java/com/ayagmar/pimobile/sessions/SessionsViewModel.kt`

**Acceptance:**
- Option to view all sessions flattened with path breadcrumbs
- Or: searchable directory tree with auto-expand on filter
- Faster navigation to deeply nested sessions

---

## 2) Quick wins (first)

### Q1 — Fix image-only prompt mismatch
**Why:** UI enables send with images + empty text, `ChatViewModel.sendPrompt()` currently blocks empty text.

**Primary files:**
- `app/src/main/java/com/ayagmar/pimobile/chat/ChatViewModel.kt`
- `app/src/main/java/com/ayagmar/pimobile/ui/chat/ChatScreen.kt`

**Acceptance:**
- image-only send path is consistent and no dead click state

---

### Q2 — Add full tree filter set (`all` included)
**Why:** bridge currently accepts only `default`, `no-tools`, `user-only`, `labeled-only`.

**Primary files:**
- `bridge/src/session-indexer.ts`
- `bridge/src/server.ts`
- `app/src/main/java/com/ayagmar/pimobile/chat/ChatViewModel.kt`
- `app/src/main/java/com/ayagmar/pimobile/ui/chat/ChatScreen.kt`

**Acceptance:**
- filters include `all` and behave correctly end-to-end

---

### Q3 — Command palette parity layer for built-ins
**Why (Pi RPC doc):** `get_commands` excludes interactive built-ins (`/settings`, `/hotkeys`, etc.).

**Primary files:**
- `app/src/main/java/com/ayagmar/pimobile/chat/ChatViewModel.kt`
- `app/src/main/java/com/ayagmar/pimobile/ui/chat/ChatScreen.kt`

**Acceptance:**
- built-ins show as supported/bridge-backed/unsupported with explicit UX (no silent no-op)

---

### Q4 — Add global collapse/expand controls
**Why:** per-item collapse exists; missing global action parity.

**Primary files:**
- `app/src/main/java/com/ayagmar/pimobile/chat/ChatViewModel.kt`
- `app/src/main/java/com/ayagmar/pimobile/ui/chat/ChatScreen.kt`

**Acceptance:**
- one-tap collapse/expand all for tools and reasoning

---

### Q5 — Wire FrameMetrics into live chat
**Why:** metrics utility exists but is not active in chat rendering flow.

**Primary files:**
- `app/src/main/java/com/ayagmar/pimobile/ui/chat/ChatScreen.kt`
- `app/src/main/java/com/ayagmar/pimobile/perf/FrameMetrics.kt`

**Acceptance:**
- frame/jank logs produced during streaming sessions

---

### Q6 — Transport preference setting parity (`sse` / `websocket` / `auto`)
**Why (settings doc):** transport is a first-class setting in Pi.

**Primary files:**
- `app/src/main/java/com/ayagmar/pimobile/ui/settings/SettingsViewModel.kt`
- `app/src/main/java/com/ayagmar/pimobile/ui/settings/SettingsScreen.kt`
- `app/src/main/java/com/ayagmar/pimobile/sessions/SessionController.kt`
- `app/src/main/java/com/ayagmar/pimobile/sessions/RpcSessionController.kt`

**Acceptance:**
- setting visible, persisted, and reflected in runtime behavior (with clear fallback notes)

---

### Q7 — Queue inspector UX for pending steer/follow-up
**Why:** queue behavior exists but users cannot inspect/manage pending items clearly.

**Primary files:**
- `app/src/main/java/com/ayagmar/pimobile/chat/ChatViewModel.kt`
- `app/src/main/java/com/ayagmar/pimobile/ui/chat/ChatScreen.kt`
- `app/src/main/java/com/ayagmar/pimobile/ui/settings/SettingsScreen.kt` (if mode hints/actions added)

**Acceptance:**
- pending queue is visible while streaming
- queued items can be cancelled/cleared as protocol allows
- UX reflects steering/follow-up mode behavior (`all` vs `one-at-a-time`)

---

## 3) Stability + security fixes

### F1 — Bridge event isolation and lock correctness
**Why:** outbound RPC events are currently fanned out by `cwd`; tighten to active controller/session ownership.

**Primary files:**
- `bridge/src/server.ts`
- `bridge/src/process-manager.ts`
- `bridge/test/server.test.ts`
- `bridge/test/process-manager.test.ts`

**Acceptance:**
- no event leakage across same-cwd clients
- lock enforcement applies consistently for send + receive paths

---

### F2 — Reconnect/resync hardening
**Why:** ensure deterministic recovery under network flaps and reconnect races.

**Primary files:**
- `core-net/src/main/kotlin/com/ayagmar/pimobile/corenet/PiRpcConnection.kt`
- `app/src/main/java/com/ayagmar/pimobile/sessions/RpcSessionController.kt`
- tests in `core-net` / `app`

**Acceptance:**
- no stale/duplicate state application after reconnect
- no stuck streaming flag after recovery

---

### F3 — Bridge auth + exposure hardening
**Why:** improve defensive posture without changing core architecture.

**Primary files:**
- `bridge/src/server.ts`
- `bridge/src/config.ts`
- `README.md`

**Acceptance:**
- auth compare hardened
- unsafe bind choices clearly warned/documented
- health endpoint exposure policy explicit

---

### F4 — Android network security tightening
**Why:** app currently permits cleartext globally.

**Primary files:**
- `app/src/main/res/xml/network_security_config.xml`
- `app/src/main/AndroidManifest.xml`
- `README.md`

**Acceptance:**
- cleartext policy narrowed/documented for tailscale-only usage assumptions

---

### F5 — Bridge session index scalability
**Why:** recursive full reads of all JSONL files do not scale.

**Primary files:**
- `bridge/src/session-indexer.ts`
- `bridge/src/server.ts`
- `bridge/test/session-indexer.test.ts`

**Acceptance:**
- measurable reduction in repeated full-scan overhead on large session stores

---

## 4) Medium maintainability improvements

### M1 — Replace service locator with explicit DI
**Why:** `AppServices` singleton hides dependencies and complicates tests.

**Primary files:**
- `app/src/main/java/com/ayagmar/pimobile/di/AppServices.kt`
- route/viewmodel factories and call sites

**Acceptance:**
- explicit dependency graph, no mutable global service locator usage

---

### M2 — Split god classes (architecture hygiene)
**Targets:**
- `ChatViewModel` (~2000+ lines) → extract: message handling, UI state management, command processing
- `ChatScreen.kt` (~2600+ lines) → extract: timeline, header, input, dialogs into separate files
- `RpcSessionController` (~1000+ lines) → extract: connection mgmt, RPC routing, lifecycle

**Acceptance (non-rigid):**
- class sizes and responsibilities are substantially reduced (no hard fixed LOC cap)
- detekt complexity signals improve (e.g. `LargeClass`, `LongMethod`, `TooManyFunctions` count reduced)
- suppressions are reduced or narrowed with justification
- all existing tests pass
- clear public API boundaries documented

---

### M3 — Unify streaming/backpressure pipeline
**Why:** `BackpressureEventProcessor` / `StreamingBufferManager` / `BoundedEventBuffer` are not integrated in runtime flow.

**Primary files:**
- `core-rpc/src/main/kotlin/com/ayagmar/pimobile/corerpc/BackpressureEventProcessor.kt`
- `core-rpc/src/main/kotlin/com/ayagmar/pimobile/corerpc/StreamingBufferManager.kt`
- `core-rpc/src/main/kotlin/com/ayagmar/pimobile/corerpc/BoundedEventBuffer.kt`
- app runtime integration points

**Acceptance:**
- one coherent runtime path (integrated or removed dead abstractions)

---

### M4 — Tighten static analysis rules
**Why:** keep architecture drift in check.

**Primary files:**
- `detekt.yml`
- affected Kotlin sources

**Acceptance:**
- fewer broad suppressions
- complexity-oriented rules enforced pragmatically (without blocking healthy modularization)
- all checks green

---

## 5) Theming + Design System (after architecture cleanup)

### T1 — Centralized theme architecture (PiMobileTheme)
**Why:** Colors are scattered and hardcoded; no dark/light mode support.

**Primary files:**
- `app/src/main/java/com/ayagmar/pimobile/ui/theme/` (create)
- `app/src/main/java/com/ayagmar/pimobile/ui/PiMobileApp.kt`
- all screen files for color replacement

**Acceptance:**
- `PiMobileTheme` with `lightColorScheme()` and `darkColorScheme()`
- all hardcoded colors replaced with theme references
- settings toggle for light/dark/system-default
- color roles documented (primary, secondary, tertiary, surface, etc.)

---

### T2 — Component design system
**Why:** Inconsistent card styles, button sizes, spacing across screens.

**Primary files:**
- `app/src/main/java/com/ayagmar/pimobile/ui/components/` (create)
- `app/src/main/java/com/ayagmar/pimobile/ui/chat/ChatScreen.kt`
- `app/src/main/java/com/ayagmar/pimobile/ui/sessions/SessionsScreen.kt`
- `app/src/main/java/com/ayagmar/pimobile/ui/settings/SettingsScreen.kt`

**Acceptance:**
- reusable `PiCard`, `PiButton`, `PiTextField`, `PiTopBar` components
- consistent spacing tokens (4.dp, 8.dp, 16.dp, 24.dp)
- typography scale defined and applied

---

## 6) Heavy hitters (last)

### H1 — True `/tree` parity (in-place navigate, not fork fallback)
**Why:** current Jump+Continue calls fork semantics.

**Primary files:**
- `app/src/main/java/com/ayagmar/pimobile/chat/ChatViewModel.kt`
- `app/src/main/java/com/ayagmar/pimobile/sessions/RpcSessionController.kt`
- `bridge/src/server.ts` (if bridge route needed)

**Implementation path order:**
1. bridge-first
2. if RPC gap remains: add minimal companion extension or SDK-backed bridge capability

**Acceptance:**
- navigation semantics match `/tree` behavior (in-place leaf changes + editor behavior)

---

### H2 — Session parsing alignment with Pi internals
**Why:** hand-rolled JSONL parsing is brittle as session schema evolves.

**Primary files:**
- `bridge/src/session-indexer.ts`
- `bridge/src/server.ts`
- bridge tests

**Acceptance:**
- parser resiliency improved (or SDK-backed replacement), with compatibility tests

---

### H3 — Incremental session history loading strategy
**Why:** `get_messages` full-load + full parse remains expensive for huge histories.

**Primary files:**
- `app/src/main/java/com/ayagmar/pimobile/chat/ChatViewModel.kt`
- `app/src/main/java/com/ayagmar/pimobile/sessions/RpcSessionController.kt`
- bridge additions if needed

**Acceptance:**
- large-session resume remains responsive and memory-stable

---

### H4 — Extension-ize selected hardcoded workflows
**Why:** reduce app-side hardcoding where Pi extension model is a better fit.

**Candidates:**
- session naming/bookmark workflows
- share/export helpers
- command bundles

**Acceptance:**
- at least one workflow moved to extension-driven path with docs

---

## Ordered execution queue (strict)

1. C1 Fix "New Session" error message bug
2. C2 Compact chat header (stop blocking streaming view)
3. C3 Flatten directory explorer (improve CWD browsing UX)
4. Q1 image-only send fix
5. Q2 full tree filters (`all`)
6. Q3 command palette built-in parity layer
7. Q4 global collapse/expand controls
8. Q5 live frame metrics wiring
9. Q6 transport preference setting parity
10. Q7 queue inspector UX for pending steer/follow-up
11. F1 bridge event isolation + lock correctness
12. F2 reconnect/resync hardening
13. F3 bridge auth/exposure hardening
14. F4 Android network security tightening
15. F5 bridge session index scalability
16. M1 replace service locator with DI
17. M2 split god classes (architecture hygiene)
18. M3 unify streaming/backpressure runtime pipeline
19. M4 tighten static analysis rules
20. T1 Centralized theme architecture (PiMobileTheme)
21. T2 Component design system
22. H1 true `/tree` parity
23. H2 session parsing alignment with Pi internals
24. H3 incremental history loading strategy
25. H4 extension-ize selected workflows

---

## Definition of done

- [ ] Critical UX fixes complete
- [ ] Quick wins complete
- [ ] Stability/security fixes complete
- [ ] Maintainability improvements complete
- [ ] Theming + Design System complete
- [ ] Heavy hitters complete or explicitly documented as protocol-limited
- [ ] Final verification loop green
