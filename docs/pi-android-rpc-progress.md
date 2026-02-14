# Pi Android RPC — Progress Tracker

Status values: `TODO` | `IN_PROGRESS` | `BLOCKED` | `DONE`

| Task | Status | Commit | Verification | Notes |
|---|---|---|---|---|
| 1.1 Bootstrap Android app + modules | DONE | e9f80a2 | ✅ ktlintCheck, detekt, test, :app:assembleDebug | Bootstrapped Android app + modular core-rpc/core-net/core-sessions with Compose navigation placeholders. |
| 1.2 Quality gates + CI | DONE | bd8a0a0 | ✅ ktlintCheck, detekt, test | Added .editorconfig, detekt.yml, root quality plugin config, and GitHub Actions CI workflow. |
| 1.3 RPC/cwd spike validation | DONE | 2817cf5 | ✅ ktlintCheck, detekt, test | Added reproducible spike doc validating JSONL interleaving/id-correlation and switch_session vs cwd behavior. |
| 2.1 Bridge skeleton | DONE | 0624eb8 | ✅ ktlintCheck, detekt, test, bridge check | Bootstrapped TypeScript bridge with scripts (dev/start/check/test), config/logging, HTTP health + WS skeleton, and Vitest/ESLint setup. |
| 2.2 WS envelope + auth | DONE | 2c3c269 | ✅ ktlintCheck, detekt, test, bridge check | Added auth-token handshake validation, envelope parser/validation, and safe bridge_error responses for malformed/unsupported payloads. |
| 2.3 RPC forwarding | DONE | dc89183 | ✅ ktlintCheck, detekt, test, bridge check | Added pi subprocess forwarder (stdin/stdout JSONL + stderr logging isolation), rpc channel forwarding, and E2E bridge get_state websocket check. |
| 2.4 Multi-cwd process manager | DONE | eff1bdf | ✅ ktlintCheck, detekt, test, bridge check | Added per-cwd process manager with control locks, server control APIs (set cwd/acquire/release), and idle TTL eviction with tests for lock rejection and cwd routing. |
| 2.5 Session indexing API | DONE | 6538df2 | ✅ ktlintCheck, detekt, test, bridge check | Added bridge_list_sessions API backed by JSONL session indexer (header/session_info/preview/messageCount/lastModel), fixture tests, and local ~/.pi session smoke run. |
| 2.6 Bridge resilience | DONE | b39cec9 | ✅ ktlintCheck, detekt, test, bridge check | Added enriched /health status, RPC forwarder crash auto-restart/backoff, reconnect grace model with resumable clientId, and forced reconnect smoke verification. |
| 3.1 RPC models/parser | DONE | 95b0489 | ✅ ktlintCheck, detekt, test | Added serializable RPC command + inbound response/event models and Json parser (ignoreUnknownKeys) with tests for response states, message_update, tool events, and extension_ui_request. |
| 3.2 Streaming assembler/throttle | DONE | 62f16bd | ✅ ktlintCheck, detekt, test; smoke: :core-rpc:test --tests "*AssistantTextAssemblerTest" --tests "*UiUpdateThrottlerTest" | Added assistant text stream assembler keyed by message/content index, capped message-buffer tracking, and a coalescing UI update throttler with deterministic unit coverage. |
| 3.3 WebSocket transport | DONE | 2b57157 | ✅ ktlintCheck, detekt, test; integration: :core-net:test (MockWebServer reconnect scenario) | Added OkHttp-based WebSocket transport with connect/disconnect/reconnect lifecycle, inbound Flow stream, outbound queue replay on reconnect, explicit connection states, and integration coverage. |
| 3.4 RPC orchestrator/resync | DONE | aa5f6af | ✅ ktlintCheck, detekt, test; integration: :core-net:test --tests "*PiRpcConnectionTest" | Added `PiRpcConnection` orchestrator with bridge/rpc envelope routing, typed RPC event stream, command dispatch, request-response helpers (`get_state`, `get_messages`), and automatic reconnect resync path validated in tests. |
| 4.1 Host profiles + secure token | DONE | 74db836 | ✅ ktlintCheck, detekt, test | Added host profile CRUD flow (Compose hosts screen + editor dialog + persistence via SharedPreferences), plus Keystore-backed token storage via EncryptedSharedPreferences (security-crypto). |
| 4.2 Sessions cache repo | DONE | 7e6e72f | ✅ ktlintCheck, detekt, test; smoke: :core-sessions:test --tests "*SessionIndexRepositoryTest" | Implemented `core-sessions` cached index repository per host with file/in-memory cache stores, background refresh + merge semantics, and query filtering support with deterministic repository tests. |
| 4.3 Sessions UI grouped by cwd | DONE | 2a3389e | ✅ ktlintCheck, detekt, test; smoke: :app:assembleDebug | Added sessions browser UI grouped/collapsible by cwd with host selection and search, wired bridge-backed session fetch via `bridge_list_sessions`, and implemented resume action wiring that reconnects with selected cwd/session and issues `switch_session`. |
| 4.4 Rename/fork/export/compact actions | DONE | f7957fc | ✅ ktlintCheck, detekt, test; smoke: :app:assembleDebug | Added active-session action entry points (Rename/Fork/Export/Compact) in sessions UI, implemented RPC commands (`set_session_name`, `get_fork_messages`+`fork`, `export_html`, `compact`) with response handling, and refreshed session index state after mutating actions. |
| 5.1 Streaming chat timeline UI | DONE | b2fac50 | ✅ ktlintCheck, detekt, test; smoke: :app:assembleDebug | Added chat timeline screen wired to shared active session connection, including history bootstrap via `get_messages`, live assistant streaming text assembly from `message_update`, and tool execution cards with collapse/expand behavior for large outputs. |
| 5.2 Abort/steer/follow_up controls | DONE | d0545cf | ✅ ktlintCheck, detekt, test, bridge check | Added prompt controls (sendPrompt, abort, steer, followUp), streaming state tracking via AgentStart/End events, and UI with input field, abort button (red), steer/follow-up dialogs. |
| 5.3 Model/thinking controls | DONE | [COMMIT_HASH] | ✅ ktlintCheck, detekt, test, bridge check | Added cycle_model and cycle_thinking_level commands with ModelInfo data class. UI shows current model/thinking level with cycle buttons. State survives reconnect via getState on load. |
| 5.4 Extension UI protocol support | TODO |  |  |  |
| 6.1 Backpressure + bounded buffers | TODO |  |  |  |
| 6.2 Instrumentation + perf baseline | TODO |  |  |  |
| 6.3 Baseline profile + release tuning | TODO |  |  |  |
| 7.1 Optional extension scaffold | TODO |  |  |  |
| 8.1 Setup + troubleshooting docs | TODO |  |  |  |
| 8.2 Final acceptance report | TODO |  |  |  |

## Per-task verification command set

```bash
./gradlew ktlintCheck
./gradlew detekt
./gradlew test
# if bridge changed:
(cd bridge && pnpm run check)
```
