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

---

## 1) Quick wins (first)

### Q0 — Restore green baseline quality gate
**Why:** current fresh run shows detekt failure (`PiMobileApp.kt` long method).

**Primary files:**
- `app/src/main/java/com/ayagmar/pimobile/ui/PiMobileApp.kt`

**Acceptance:**
- verification loop fully green

---

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

## 2) Stability + security fixes

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

## 3) Medium maintainability improvements

### M1 — Replace service locator with explicit DI
**Why:** `AppServices` singleton hides dependencies and complicates tests.

**Primary files:**
- `app/src/main/java/com/ayagmar/pimobile/di/AppServices.kt`
- route/viewmodel factories and call sites

**Acceptance:**
- explicit dependency graph, no mutable global service locator usage

---

### M2 — Split large classes
**Targets:**
- `ChatViewModel`
- `ChatScreen`
- `RpcSessionController`

**Acceptance:**
- smaller focused components, lower suppression pressure, easier tests

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
- fewer broad suppressions, all checks green

---

## 4) Heavy hitters (last)

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

1. Q0 green quality gate baseline
2. Q1 image-only send fix
3. Q2 full tree filters (`all`)
4. Q3 command palette built-in parity layer
5. Q4 global collapse/expand controls
6. Q5 live frame metrics wiring
7. Q6 transport preference setting parity
8. F1 bridge event isolation + lock correctness
9. F2 reconnect/resync hardening
10. F3 bridge auth/exposure hardening
11. F4 Android network security tightening
12. F5 bridge session index scalability
13. M1 replace service locator with DI
14. M2 split large classes
15. M3 unify streaming/backpressure runtime pipeline
16. M4 tighten static analysis rules
17. H1 true `/tree` parity
18. H2 session parsing alignment with Pi internals
19. H3 incremental history loading strategy
20. H4 extension-ize selected workflows

---

## Definition of done

- [ ] Quick wins complete
- [ ] Stability/security fixes complete
- [ ] Maintainability improvements complete
- [ ] Heavy hitters complete or explicitly documented as protocol-limited
- [ ] Final verification loop green
