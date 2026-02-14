# Pi Mobile (Android) — Refined Plan (RPC Client via Tailscale)

## 0) Product goal
Build a **performant, robust native Android app** (Kotlin + Compose) that lets you use pi sessions running on your laptop from anywhere (bed, outside, etc.) via **Tailscale**, **without SSH/SFTP**.

Primary success criteria:
- Smooth long chats with streaming output (no visible jank)
- Safe/resilient remote connection over tailnet
- Fast session browsing and resume across multiple projects/cwds
- Clear extension path for missing capabilities

---

## 1) Facts from pi docs (non-negotiable constraints)

### 1.1 RPC transport model
From `docs/rpc.md`:
- RPC is **JSON lines over stdin/stdout** of `pi --mode rpc`
- It is **not** a native TCP/WebSocket protocol

Implication:
- Android cannot connect directly to RPC over network.
- You need a **laptop-side bridge** process:
  - Android ↔ (Tailscale) ↔ Bridge ↔ pi RPC stdin/stdout

### 1.2 Sessions and cwd
From `docs/session.md`, `docs/sdk.md`:
- Session files include a `cwd` in header and are stored under `~/.pi/agent/sessions/--<path>--/...jsonl`
- RPC has `switch_session`, but tools are created with process cwd context

Implication:
- Multi-project correctness should be handled by **one pi process per cwd** (managed by bridge).

### 1.3 Session discovery
From `docs/rpc.md`:
- No RPC command exists to list all session files globally.

Implication:
- Bridge should read session files locally and expose a bridge API (best approach).

### 1.4 Extension UI in RPC mode
From `docs/rpc.md` and `docs/extensions.md`:
- `extension_ui_request` / `extension_ui_response` must be supported for dialog flows (`select`, `confirm`, `input`, `editor`)
- Fire-and-forget UI methods (`notify`, `setStatus`, `setWidget`, `setTitle`, `set_editor_text`) are also emitted

Implication:
- Android client must handle extension UI protocol end-to-end.

---

## 2) Architecture decision

## 2.1 Connection topology
- **Laptop** runs:
  - `pi-bridge` service (WebSocket server)
  - one or more `pi --mode rpc` subprocesses
- **Phone** runs Android app with WebSocket transport
- Network is Tailscale-only, no router forwarding

## 2.2 Bridge protocol (explicit)
Use an **envelope protocol** over WS to avoid collisions between bridge-control operations and raw pi RPC messages.

Example envelope:
```json
{ "channel": "bridge", "payload": { "type": "bridge_list_sessions" } }
{ "channel": "rpc", "payload": { "type": "prompt", "message": "hi" } }
```

Responses/events:
```json
{ "channel": "bridge", "payload": { "type": "bridge_sessions", "sessions": [] } }
{ "channel": "rpc", "payload": { "type": "message_update", ... } }
```

Why explicit envelope:
- Prevent ambiguous parsing
- Keep protocol extensible
- Easier debugging and telemetry

## 2.3 Process management
Bridge owns a `PiProcessManager` keyed by cwd:
- `getOrStart(cwd)`
- idle TTL eviction
- crash restart policy
- single writer lock per cwd/session

## 2.4 Session indexing
Bridge scans `~/.pi/agent/sessions/` and returns:
- `sessionPath`, `cwd`, `createdAt`, `updatedAt`
- `displayName` (latest `session_info.name`)
- `firstUserMessagePreview`
- optional `messageCount`, `lastModel`

Android caches this index per host and refreshes incrementally.

---

## 3) UX scope (v1)

1. **Hosts**: add/edit host (tailscale host/ip, port, token, TLS toggle)
2. **Sessions**: grouped by cwd, searchable, resume/new/rename/fork/export actions
3. **Chat**:
   - streaming text/tool timeline
   - abort/steer/follow_up
   - compact/export/fork
   - model + thinking cycling
4. **Extension UI**: dialog requests + fire-and-forget requests
5. **Settings**: defaults and diagnostics

---

## 4) Performance-first requirements (explicit budgets)

These are required, not optional:

### 4.1 Latency budgets
- Cold app start to visible cached sessions: **< 1.5s** target, **< 2.5s** max (mid-range device)
- Resume session to first rendered messages: **< 1.0s** target for cached metadata
- Prompt send to first token (healthy LAN/tailnet): **< 1.2s** target

### 4.2 Rendering budgets
- Chat streaming should keep main thread frame time mostly under 16ms
- No sustained jank while streaming > 5 minutes
- Tool outputs default collapsed when large

### 4.3 Memory budgets
- No unbounded string accumulation in UI layer
- Streaming buffers bounded and compacted
- Long chat (>= 2k messages including tool events) should avoid OOM and GC thrash

### 4.4 Throughput/backpressure
- WS inbound processing must not block UI thread
- Throttled rendering (e.g., 20–40ms update interval)
- Drop/coalesce non-critical transient updates when overwhelmed

### 4.5 Reconnect/resync behavior
After disconnect/reconnect:
- restore active cwd/session context
- call `get_state` and `get_messages` to resync
- show clear degraded/recovering indicators

---

## 5) Security model

- Tailnet transport is encrypted/authenticated by Tailscale
- Bridge binds to Tailscale interface/address only
- Bridge requires auth token (bearer or handshake token)
- Token stored securely on Android (Keystore-backed)
- If using `ws://` over tailnet, configure Android network security policy explicitly

---

## 6) Delivery strategy for one-shot long-running agent

Execution is phase-based with hard gates:

1. **Phase A:** Bridge + basic chat E2E
2. **Phase B:** Session indexing + resume across cwd
3. **Phase C:** Full chat controls + extension UI protocol
4. **Phase D:** Performance hardening + reconnect robustness
5. **Phase E:** Docs + final acceptance

### Rule
Do not start next phase until:
- code quality loop passes
- phase acceptance checks pass
- task tracker updated

---

## 7) Verification loops (explicit)

## 7.1 Per-task verification loop
After each task:
1. `./gradlew ktlintCheck`
2. `./gradlew detekt`
3. `./gradlew test`
4. Bridge checks (`pnpm run check`) when bridge changed
5. Targeted manual smoke test for the task

If any fails: fix and rerun full loop.

## 7.2 Per-phase gate
At end of each phase:
- End-to-end scripted walkthrough passes
- No open critical bugs in that phase scope
- Task list statuses updated to `DONE`

## 7.3 Weekly/perf gate (for long-running execution)
- Run stress scenario: long streaming + big tool output + session switching
- Record metrics and regressions
- Block progression if perf worsens materially

---

## 8) Extension strategy (repo-local)
If a missing capability should live inside pi runtime (not Android/bridge), add extension packages in this repo:

- Create `extensions/` directory
- Bootstrap from:
  - `/home/ayagmar/Projects/Personal/pi-extension-template/`
- Keep extension quality gate:
  - `pnpm run check`
- Install locally:
```bash
pi install /absolute/path/to/extensions/<package>
```
- Reload in running pi with `/reload`

Use extensions for:
- custom commands/hooks
- guardrails
- metadata enrichment that is better at agent side

---

## 9) Risks and mitigations

- **Risk:** bridge protocol drift vs pi RPC
  - Mitigation: keep `channel: rpc` payload pass-through unchanged and tested with fixtures
- **Risk:** session corruption from concurrent writers
  - Mitigation: single controlling client lock per cwd/session
- **Risk:** lag in long streams
  - Mitigation: throttled rendering + bounded buffers + macrobenchmark checks
- **Risk:** reconnect inconsistency
  - Mitigation: deterministic resync (`get_state`, `get_messages`) on reconnect

---

## 10) Final Definition of Done (explicit and measurable)
All must be true:

1. **Connectivity**
   - Android connects to laptop bridge over Tailscale reliably
   - Auth token required and validated

2. **Core chat**
   - `prompt`, `abort`, `steer`, `follow_up` operate correctly
   - Streaming text/tool events render smoothly in long runs

3. **Sessions**
   - Sessions listed from `~/.pi/agent/sessions/`, grouped by cwd
   - Resume works across different cwds via correct process selection
   - Rename/fork/export/compact flows work and reflect in UI/index

4. **Extension protocol**
   - `extension_ui_request` dialog methods handled with proper response IDs
   - Fire-and-forget UI methods represented without blocking

5. **Robustness**
   - Bridge survives transient disconnects and can recover/resync
   - No session corruption under reconnect and repeated resume/switch tests

6. **Performance**
   - Meets latency and streaming smoothness budgets in section 4
   - No major memory leaks/jank under stress scenarios

7. **Quality gates**
   - Android: `ktlintCheck`, `detekt`, `test` green
   - Bridge/extensions: `pnpm run check` green

8. **Documentation**
   - README includes complete setup (tailscale, bridge run, token, troubleshooting)
   - Task checklist and acceptance report completed
