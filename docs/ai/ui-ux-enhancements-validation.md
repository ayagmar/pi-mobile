# Pi Mobile — UX Quick Wins Validation Sheet

_Last updated: 2026-02-16_

> Planning artifact only. Do **not** commit this file.

Purpose: get your approval on **what will change** and **why** before implementation.

## How to validate

For each item, choose one:
- `APPROVE`
- `APPROVE_WITH_CHANGES`
- `SKIP`

---

## UX-05A — Rail ergonomics polish

- **Current pain**: left rail is useful but can still feel heavy/awkward in repeated use.
- **Planned change**:
  1. Persist expanded/collapsed state.
  2. Tune spacing and hit areas for faster tab switching.
- **Why**: reduce friction and accidental taps.
- **Risk**: very low (layout only).
- **Your decision**: `APPROVED`
- **Notes**:

## UX-05B — Streaming action hierarchy polish

- **Current pain**: intervention actions during streaming can still feel crowded.
- **Planned change**:
  1. Prioritize Abort visually.
  2. Improve Steer/Follow-up affordances and spacing.
- **Why**: faster control when model is running.
- **Risk**: low (no protocol behavior changes).
- **Your decision**: `APPROVE`
- **Notes**:

## UX-05C — Extension status strip compaction v2

- **Current pain**: even moved under prompt, status can still consume space.
- **Planned change**:
  1. Default compact summary row.
  2. Expand for full details.
  3. De-noise repeated unchanged statuses.
- **Why**: preserve more room for chat timeline.
- **Risk**: low (presentation-layer only).
- **Your decision**: `APPROVE`
- **Notes**:

## UX-05D — Timeline smoothing + spacing pass

- **Current pain**: occasional visual jumps while widgets/progress appear/disappear.
- **Planned change**:
  1. Small spacing harmonization.
  2. Smoother show/hide transitions for helper UI.
- **Why**: reduce perceived jitter and improve readability.
- **Risk**: low/medium (needs manual feel check).
- **Your decision**: `DEENY`
- **Notes**: can do it later, i dont understand this

## UX-05E — Jump-to-latest while streaming

- **Current pain**: when reading older messages during stream, getting back to live tail is slower.
- **Planned change**:
  1. Show "Jump to latest" button when user is away from bottom during active run.
- **Why**: one-tap return to live output.
- **Risk**: low.
- **Your decision**: `APPROVE`
- **Notes**:

## UX-05F — Accessibility cleanups

- **Current pain**: some secondary controls may be small/less explicit.
- **Planned change**:
  1. Touch-target audit for key actions.
  2. Content description pass for icons.
- **Why**: improve reliability and usability.
- **Risk**: very low.
- **Your decision**: `APPROVE`
- **Notes**:

---

## Verification plan (per approved item)

```bash
./gradlew ktlintCheck
./gradlew detekt
./gradlew test
```

Bridge check only if bridge files changed:

```bash
(cd bridge && pnpm run check)
```

Manual smoke:
- `DEFERRED (user-run)`
