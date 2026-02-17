# Pi Mobile — UI/UX Enhancements Tasks Tracker

_Status values_: `TODO` | `IN_PROGRESS` | `BLOCKED` | `DONE` | `DESCOPED`

> Planning artifact only. Do **not** commit this file.

Linked plan: `docs/ai/ui-ux-enhancements-plan.md`
Linked progress: `docs/ai/ui-ux-enhancements-progress.md`
Validation sheet: `docs/ai/ui-ux-enhancements-validation.md`

## Mandatory verification loop

```bash
./gradlew ktlintCheck
./gradlew detekt
./gradlew test
```

Bridge check only when bridge code/config/tests changed:

```bash
(cd bridge && pnpm run check)
```

## Task table

| Order | ID | Title | Status | Priority | Commit | Report |
|---|---|---|---|---|---|---|
| 1 | UX-01 | Move global nav to collapsible left rail | DONE | P1 | `1a3a2b7 feat(ui): add side nav, inline run progress, and status toggle` | in-code |
| 2 | UX-02 | Move extension status below prompt + visibility toggle | DONE | P1 | `1a3a2b7 feat(ui): add side nav, inline run progress, and status toggle` | in-code |
| 3 | UX-03 | Make abort resilient during thinking/retry transitions | DONE | P0 | `1a3a2b7 feat(ui): add side nav, inline run progress, and status toggle` | in-code |
| 4 | UX-04 | Show live thinking/loading inline in timeline area | DONE | P1 | `1a3a2b7 feat(ui): add side nav, inline run progress, and status toggle` | in-code |
| 5 | UX-05A | Rail ergonomics polish (persist + spacing) | DONE | P2 | `1847bae feat(ui): implement approved UX quick wins` | `ui-ux-enhancements-validation.md` |
| 6 | UX-05B | Streaming action hierarchy polish | DONE | P1 | `1847bae feat(ui): implement approved UX quick wins` | `ui-ux-enhancements-validation.md` |
| 7 | UX-05C | Extension status strip compaction v2 | DONE | P1 | `1847bae feat(ui): implement approved UX quick wins` | `ui-ux-enhancements-validation.md` |
| 8 | UX-05D | Timeline smoothing + spacing pass | DESCOPED | P2 | - | denied in `ui-ux-enhancements-validation.md` |
| 9 | UX-05E | Jump-to-latest affordance while streaming | DONE | P1 | `1847bae feat(ui): implement approved UX quick wins` | `ui-ux-enhancements-validation.md` |
| 10 | UX-05F | Accessibility quick audit/fixes | DONE | P1 | `1847bae feat(ui): implement approved UX quick wins` | `ui-ux-enhancements-validation.md` |

## Reporting rule per task

For each completed task, record:
- Root cause summary
- Files changed
- Tests updated
- Verification results (ktlint/detekt/test/bridge)
- Manual smoke status (`DEFERRED (user-run)` if applicable)
- Commit hash + message
- Follow-ups/risks
