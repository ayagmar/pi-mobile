# Performance Baseline

This document defines the performance metrics, measurement methodology, and baseline targets for the Pi Mobile app.

## Performance Budgets

### Latency Budgets

| Metric | Target | Max | Status |
|--------|--------|-----|--------|
| Cold app start to visible cached sessions | < 1.5s | < 2.5s | 🔄 Measuring |
| Resume session to first rendered messages | < 1.0s | - | 🔄 Measuring |
| Prompt send to first token (healthy LAN) | < 1.2s | - | 🔄 Measuring |

### Rendering Budgets

| Metric | Target | Status |
|--------|--------|--------|
| Main thread frame time | < 16ms (60fps) | 🔄 Measuring |
| No sustained jank (>5min streaming) | 0 critical drops | 🔄 Measuring |
| Large tool output default-collapsed | > 400 chars | ✅ Implemented |

### Memory Budgets

| Metric | Target | Status |
|--------|--------|--------|
| Streaming buffer per message | < 50KB | ✅ Implemented |
| Tracked assistant message buffers | 8 | ✅ Implemented |
| Event buffer capacity (RPC / bridge) | 256 / 128 events | ✅ Implemented |

## Measurement Infrastructure

### PerformanceMetrics

Tracks key user journey timings:

- `startup_to_sessions`: App start to sessions list visible
- `resume_to_messages`: Session resume to chat history rendered
- `prompt_to_first_token`: Prompt send to first assistant token

Usage:
```kotlin
// Automatic tracking in MainActivity, SessionsViewModel, ChatViewModel
PerformanceMetrics.recordAppStart()
PerformanceMetrics.recordSessionsVisible()
PerformanceMetrics.recordResumeStart()
PerformanceMetrics.recordFirstMessagesRendered()
PerformanceMetrics.recordPromptSend()
PerformanceMetrics.recordFirstToken()
```

### FrameMetrics

Monitors UI rendering performance during streaming:

```kotlin
// Automatically tracks during streaming
StreamingFrameMetrics(isStreaming = isStreaming) { droppedFrame ->
    Log.w("Jank", "Dropped ${droppedFrame.expectedFrames} frames")
}
```

Jank severity levels:
- **Medium**: 33-50ms (1 dropped frame at 60fps)
- **High**: 50-100ms (2-3 dropped frames)
- **Critical**: >100ms (6+ dropped frames)

## Running Benchmarks

### Startup Benchmark

Measures cold start performance with and without baseline profiles:

```bash
# Run on connected device
./gradlew :benchmark:connectedBenchmarkAndroidTest

# Run with baseline profile
./gradlew :benchmark:connectedCheck -P android.testInstrumentationRunnerArguments.class=StartupBenchmark
```

### Baseline Profile Generation

Generate a new baseline profile:

```bash
# Run on emulator or device with API 33+
./gradlew :benchmark:pixel7Api34GenerateBaselineProfile

# Or use the generic task
./gradlew :benchmark:connectedGenerateBaselineProfile
```

Copy the generated profile to `app/src/main/baseline-prof.txt`.

## Profiling

### Memory Profiling

Monitor memory usage during long streaming sessions:

```kotlin
val memoryUsage = bufferManager.estimatedMemoryUsage()
Log.d("Memory", "Streaming buffers: ${memoryUsage} bytes")
```

### Backpressure Monitoring

Check for event backpressure:

```kotlin
val droppedCount = buffer.droppedCount()
if (buffer.isBackpressuring()) {
    Log.w("Backpressure", "Dropped $droppedCount events")
}
```

## Current Baseline (v1.0)

*To be populated with actual measurements*

### Device: Pixel 7 (API 34)

| Metric | Compilation: None | Compilation: Baseline Profile |
|--------|------------------|------------------------------|
| Cold startup | TBD ms | TBD ms |
| Resume to messages | TBD ms | TBD ms |
| First token latency | TBD ms | TBD ms |

### Device: Mid-range (API 30)

| Metric | Compilation: None | Compilation: Baseline Profile |
|--------|------------------|------------------------------|
| Cold startup | TBD ms | TBD ms |
| Resume to messages | TBD ms | TBD ms |
| First token latency | TBD ms | TBD ms |

## Optimization Checklist

- [x] Bounded event buffer (128 events)
- [x] Streaming text buffer limits (50KB per message)
- [x] Segment compaction for long streams
- [x] LRU eviction for old messages
- [x] Frame metrics tracking
- [x] Startup timing instrumentation
- [ ] Baseline profile generation
- [ ] Release build optimization verification
- [ ] Stress test: 10+ minute streaming
- [ ] Memory leak verification

## Known Issues

*None recorded*

## Tools

- Android Studio Profiler: CPU, Memory, Energy
- Macrobenchmark library: Startup metrics
- Logcat filtering: `tag:PerfMetrics|tag:FrameMetrics`
