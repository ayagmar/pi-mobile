# Pi Mobile — UI/UX Enhancements Plan

_Last updated: 2026-02-16_

> Planning artifact only. Do **not** commit this file.

## 1) Goal

Define and execute incremental UI/UX improvements that enhance clarity, speed, and trust during daily mobile usage.

## 2) Scope

- Chat readability and hierarchy
- Interaction efficiency (1–2 tap access to common actions)
- Streaming/latency perception improvements
- Session clarity and confidence cues
- Accessibility and visual consistency improvements

## 3) Non-goals

- No protocol-breaking RPC changes unless explicitly approved
- No broad architecture rewrite in this track
- No backend behavior changes outside UX-driven requirements

## 4) Working agreements

- One issue/task per commit where possible
- Conventional Commits
- Do not push from agent
- Keep planning docs out of commits

## 5) Verification loop (mandatory per implemented task)

```bash
./gradlew ktlintCheck
./gradlew detekt
./gradlew test
```

Bridge check only when bridge code/config/tests changed:

```bash
(cd bridge && pnpm run check)
```

Manual smoke:
- `DEFERRED (user-run)` unless user requests agent-run smoke protocol

## 6) Current implementation focus (user-requested)

1. Move global nav (Hosts / Sessions / Chat / Settings) to left-side collapsible control.
2. Reduce extension status strip footprint; place under prompt controls and gate with settings toggle.
3. Improve abort reliability while model is thinking/retrying.
4. Move thinking/loading progress cue into timeline/message area rather than header-only.

## 7) UX-05 quick wins (proposed, awaiting your validation)

Validation sheet: `docs/ai/ui-ux-enhancements-validation.md`

### Candidate changes (low-risk, high-usability)

1. **Rail ergonomics polish**
   - Persist collapse/expand preference.
   - Improve rail spacing/touch targets.
   - Why: reduce accidental taps and repeated toggling.

2. **Streaming focus controls**
   - Keep Abort/Steer/Follow-up visible with clearer priority when streaming.
   - Improve button hierarchy and spacing.
   - Why: faster intervention while model is running.

3. **Status strip compaction**
   - Compact to chips/summary by default, expandable for details.
   - Hide unchanged/noisy status updates.
   - Why: preserve chat vertical space.

4. **Timeline readability + motion smoothing**
   - Subtle spacing reductions where safe.
   - Avoid abrupt jumps when status/progress widgets appear/disappear.
   - Why: smoother reading during long runs.

5. **Jump-to-latest affordance**
   - Add “jump to latest” control when user is reading older messages during streaming.
   - Why: quickly return to live output.

6. **Accessibility cleanups**
   - Ensure key icons/buttons meet touch target and content-description quality.
   - Why: better usability and consistency.

## 8) Delivery approach

1. Capture UX issue as a task in `ui-ux-enhancements-tasks.md`
2. Implement smallest valuable change
3. Run verification loop
4. Update progress file
5. Commit code only (exclude planning docs)
