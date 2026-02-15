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
| 1 | C1 Fix "New Session" error message bug | DONE (partial) | fix(sessions): make New Session work + add flat view toggle | e81d27f | Quick fix implemented; needs C4 for proper architecture |
| 2 | C2 Compact chat header | TODO |  |  |  | Blocks streaming view, needs collapse/minimal mode |
| 3 | C3 Flatten directory explorer | DONE | fix(sessions): make New Session work + add flat view toggle | e81d27f | "All" / "Tree" toggle implemented |
| 4 | C4 Persistent bridge connection | TODO |  |  |  | Architectural change; establish connection on app start |

---

## Quick wins

| Order | Task | Status | Commit message | Commit hash | Verification | Notes |
|---|---|---|---|---|---|---|
| 4 | Q1 Fix image-only prompt mismatch | TODO |  |  |  |  |
| 5 | Q2 Add full tree filters (`all` included) | TODO |  |  |  |  |
| 6 | Q3 Command palette built-in parity layer | TODO |  |  |  |  |
| 7 | Q4 Global collapse/expand controls | TODO |  |  |  |  |
| 8 | Q5 Wire frame metrics into live chat | TODO |  |  |  |  |
| 9 | Q6 Transport preference setting parity | TODO |  |  |  |  |
| 10 | Q7 Queue inspector UX for pending steer/follow-up | TODO |  |  |  |  |

---

## Stability + security fixes

| Order | Task | Status | Commit message | Commit hash | Verification | Notes |
|---|---|---|---|---|---|---|
| 11 | F1 Bridge event isolation + lock correctness | TODO |  |  |  |  |
| 12 | F2 Reconnect/resync race hardening | TODO |  |  |  |  |
| 13 | F3 Bridge auth + exposure hardening | TODO |  |  |  |  |
| 14 | F4 Android network security tightening | TODO |  |  |  |  |
| 15 | F5 Bridge session index scalability | TODO |  |  |  |  |

---

## Medium maintainability improvements

| Order | Task | Status | Commit message | Commit hash | Verification | Notes |
|---|---|---|---|---|---|---|
| 16 | M1 Replace service locator with explicit DI | TODO |  |  |  |  |
| 17 | M2 Split god classes (complexity-focused, non-rigid) | TODO |  |  |  | Reduce `LargeClass` / `LongMethod` / `TooManyFunctions` signals |
| 18 | M3 Unify streaming/backpressure runtime pipeline | TODO |  |  |  |  |
| 19 | M4 Tighten static analysis rules/suppressions | TODO |  |  |  |  |

---

## Theming + Design System (after architecture cleanup)

| Order | Task | Status | Commit message | Commit hash | Verification | Notes |
|---|---|---|---|---|---|---|
| 20 | T1 Centralized theme architecture (PiMobileTheme) | TODO |  |  |  | Light/dark mode, color schemes |
| 21 | T2 Component design system | TODO |  |  |  | Reusable components, spacing tokens |

---

## Heavy hitters (last)

| Order | Task | Status | Commit message | Commit hash | Verification | Notes |
|---|---|---|---|---|---|---|
| 22 | H1 True `/tree` parity (in-place navigate) | TODO |  |  |  |  |
| 23 | H2 Session parsing alignment with Pi internals | TODO |  |  |  |  |
| 24 | H3 Incremental session history loading strategy | TODO |  |  |  |  |
| 25 | H4 Extension-ize selected hardcoded workflows | TODO |  |  |  |  |

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

---

## Overall completion

- Backlog tasks: 25
- Backlog done: 0
- Backlog in progress: 0
- Backlog blocked: 0
- Backlog remaining: 25
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
