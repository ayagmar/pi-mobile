# Priority Task List (Mobile UX + Sessions)

_Last updated: 2026-02-16_

## P0 — Must fix now

- [x] **New session must start from selected directory**
  - Owner: Chat/Sessions runtime
  - Scope:
    - Track selected cwd in `SessionsUiState`
    - Use selected cwd in `SessionsViewModel.newSession()` resolution
  - Verification loop:
    - Select cwd in Sessions grouped view
    - Tap **New**
    - Confirm `new_session` starts in selected cwd

- [x] **Replace grouped cwd headers with horizontal cwd chips**
  - Owner: Sessions UI
  - Scope:
    - Show cwd selector as horizontally scrollable chips
    - Chip label uses path tail (last 2 segments) + session count
    - Show full cwd below chips for disambiguation
  - Verification loop:
    - Open grouped mode
    - Ensure chips are scrollable and selectable
    - Ensure sessions list updates for selected cwd

- [x] **Chat keyboard/send jank (black area while keyboard transitions)**
  - Owner: Chat UI
  - Scope:
    - Remove forced keyboard hide on send
    - Stabilize bottom layout with IME-aware padding
  - Verification loop:
    - Send prompt repeatedly with keyboard open
    - Verify no black transient region and no layout jump
  - Note: code changes merged; final visual confirmation required on device

## P1 — Next improvements

- [ ] **Persist preferred cwd per host**
  - Save/restore selected cwd across app restarts

- [ ] **Compose UI tests for grouped cwd chip selector**
  - Chip rendering
  - Selection updates list
  - New session uses selected cwd

- [ ] **Compose UI tests for keyboard + input transitions**
  - Input row stable with IME open/close
  - Streaming controls appearance does not produce blank region
