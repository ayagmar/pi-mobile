# Pi Android RPC — Progress Tracker

Status values: `TODO` | `IN_PROGRESS` | `BLOCKED` | `DONE`

| Task | Status | Commit | Verification | Notes |
|---|---|---|---|---|
| 1.1 Bootstrap Android app + modules | DONE | e9f80a2 | ✅ ktlintCheck, detekt, test, :app:assembleDebug | Bootstrapped Android app + modular core-rpc/core-net/core-sessions with Compose navigation placeholders. |
| 1.2 Quality gates + CI | DONE | bd8a0a0 | ✅ ktlintCheck, detekt, test | Added .editorconfig, detekt.yml, root quality plugin config, and GitHub Actions CI workflow. |
| 1.3 RPC/cwd spike validation | DONE | 2817cf5 | ✅ ktlintCheck, detekt, test | Added reproducible spike doc validating JSONL interleaving/id-correlation and switch_session vs cwd behavior. |
| 2.1 Bridge skeleton | DONE | HEAD | ✅ ktlintCheck, detekt, test, bridge check | Bootstrapped TypeScript bridge with scripts (dev/start/check/test), config/logging, HTTP health + WS skeleton, and Vitest/ESLint setup. |
| 2.2 WS envelope + auth | TODO |  |  |  |
| 2.3 RPC forwarding | TODO |  |  |  |
| 2.4 Multi-cwd process manager | TODO |  |  |  |
| 2.5 Session indexing API | TODO |  |  |  |
| 2.6 Bridge resilience | TODO |  |  |  |
| 3.1 RPC models/parser | TODO |  |  |  |
| 3.2 Streaming assembler/throttle | TODO |  |  |  |
| 3.3 WebSocket transport | TODO |  |  |  |
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
