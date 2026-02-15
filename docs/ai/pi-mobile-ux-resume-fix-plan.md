# Pi Mobile UX and Resume Fix Plan

## Issues Identified from Screenshot and Logs

### 1. Duplicate User Messages (HIGH PRIORITY)
**Problem:** User messages appear twice - once from optimistic insertion in `sendPrompt()` and again from `MessageEndEvent`.

**Fix:** Remove optimistic insertion, only add user messages from `MessageEndEvent`. This ensures single source of truth.

### 2. Resume Not Working After First Resume (HIGH PRIORITY)
**Problem:** When user switches sessions, the `switch_session` command succeeds but ChatViewModel doesn't reload the timeline.

**Root Cause:** `RpcSessionController.resume()` returns success, but there's no mechanism to notify ChatViewModel that the session changed. The response goes to `sendAndAwaitResponse()` but isn't broadcast to observers.

**Fix Options:**
- Option A: Have SessionController emit a "sessionChanged" event that ChatViewModel observes
- Option B: Have ChatViewModel poll for session path changes
- Option C: Use SharedFlow to broadcast switch_session success

**Chosen:** Option A - Add `sessionChanged` SharedFlow to SessionController that emits when session successfully switches.

### 3. UI Clutter (MEDIUM PRIORITY)
**Problems:**
- Too many buttons in top bar (Tree, stats, copy, export)
- Collapse/expand all buttons add more clutter
- Weird status text at bottom ("3 pkgs • ... weekly • 1 update...")
- Model selector and thinking dropdown take too much space

**Fix:**
- Move Tree, stats, copy, export to overflow menu or bottom sheet
- Remove collapse/expand all from main UI (keep in overflow menu)
- Fix or remove the bottom status text
- Simplify model/thinking display

### 4. Message Alignment Already Working
**Status:** User messages ARE on the right ("You" cards), assistant on left. This is correct.

## Implementation Order

1. Fix duplicate messages (remove optimistic insertion)
2. Fix resume by adding session change notification
3. Clean up UI clutter

## Architecture for Resume Fix

```kotlin
// SessionController interface
interface SessionController {
    // ... existing methods ...
    
    // New: Observable session changes
    val sessionChanged: SharedFlow<String?> // emits new session path or null
}

// RpcSessionController implementation
override suspend fun resume(...): Result<String?> {
    // ... existing logic ...
    
    if (success) {
        _sessionChanged.emit(newSessionPath)
    }
}

// ChatViewModel
init {
    viewModelScope.launch {
        sessionController.sessionChanged.collect { newPath -
            loadInitialMessages() // Reload timeline
        }
    }
}
```
