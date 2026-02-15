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

## Quick wins

| Order | Task | Status | Commit message | Commit hash | Verification | Notes |
|---|---|---|---|---|---|---|
| 1 | Q0 Restore green baseline quality gate | TODO |  |  |  | Current detekt failure: `PiMobileApp.kt` long method |
| 2 | Q1 Fix image-only prompt mismatch | TODO |  |  |  |  |
| 3 | Q2 Add full tree filters (`all` included) | TODO |  |  |  |  |
| 4 | Q3 Command palette built-in parity layer | TODO |  |  |  |  |
| 5 | Q4 Global collapse/expand controls | TODO |  |  |  |  |
| 6 | Q5 Wire frame metrics into live chat | TODO |  |  |  |  |
| 7 | Q6 Transport preference setting parity | TODO |  |  |  |  |

---

## Stability + security fixes

| Order | Task | Status | Commit message | Commit hash | Verification | Notes |
|---|---|---|---|---|---|---|
| 8 | F1 Bridge event isolation + lock correctness | TODO |  |  |  |  |
| 9 | F2 Reconnect/resync race hardening | TODO |  |  |  |  |
| 10 | F3 Bridge auth + exposure hardening | TODO |  |  |  |  |
| 11 | F4 Android network security tightening | TODO |  |  |  |  |
| 12 | F5 Bridge session index scalability | TODO |  |  |  |  |

---

## Medium maintainability improvements

| Order | Task | Status | Commit message | Commit hash | Verification | Notes |
|---|---|---|---|---|---|---|
| 13 | M1 Replace service locator with explicit DI | TODO |  |  |  |  |
| 14 | M2 Split large classes into focused components | TODO |  |  |  |  |
| 15 | M3 Unify streaming/backpressure runtime pipeline | TODO |  |  |  |  |
| 16 | M4 Tighten static analysis rules/suppressions | TODO |  |  |  |  |

---

## Heavy hitters (last)

| Order | Task | Status | Commit message | Commit hash | Verification | Notes |
|---|---|---|---|---|---|---|
| 17 | H1 True `/tree` parity (in-place navigate) | TODO |  |  |  |  |
| 18 | H2 Session parsing alignment with Pi internals | TODO |  |  |  |  |
| 19 | H3 Incremental session history loading strategy | TODO |  |  |  |  |
| 20 | H4 Extension-ize selected hardcoded workflows | TODO |  |  |  |  |

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

- Total tasks: 20
- Done: 0
- In progress: 0
- Blocked: 0
- Remaining: 20

---

## Quick checklist

- [ ] Quick wins complete
- [ ] Stability/security fixes complete
- [ ] Maintainability improvements complete
- [ ] Heavy hitters complete (or documented protocol limits)
- [ ] Final green run (`ktlintCheck`, `detekt`, `test`, bridge check)
