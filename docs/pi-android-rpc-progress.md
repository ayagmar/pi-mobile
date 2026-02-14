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
| 3.4 RPC orchestrator/resync | TODO |  |  |  |
| 4.1 Host profiles + secure token | TODO |  |  |  |
| 4.2 Sessions cache repo | TODO |  |  |  |
| 4.3 Sessions UI grouped by cwd | TODO |  |  |  |
| 4.4 Rename/fork/export/compact actions | TODO |  |  |  |
| 5.1 Streaming chat timeline UI | TODO |  |  |  |
| 5.2 Abort/steer/follow_up controls | TODO |  |  |  |
| 5.3 Model/thinking controls | TODO |  |  |  |
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
