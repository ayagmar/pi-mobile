package com.ayagmar.pimobile.perf

import android.os.SystemClock
import android.util.Log

/**
 * Performance metrics tracker for key user journeys.
 *
 * Tracks:
 * - Cold app start to visible cached sessions
 * - Resume session to first rendered messages
 * - Prompt send to first token (TTFT)
 */
object PerformanceMetrics {
    private const val TAG = "PerfMetrics"

    private var appStartTime: Long = 0
    private var sessionsVisibleTime: Long = 0
    private var resumeStartTime: Long = 0
    private var firstMessageTime: Long = 0
    private var promptSendTime: Long = 0
    private var firstTokenTime: Long = 0

    private val pendingTimings = mutableListOf<TimingRecord>()

    /**
     * Records when the app process started.
     * Call this as early as possible in Application.onCreate() or MainActivity.
     */
    fun recordAppStart() {
        appStartTime = SystemClock.elapsedRealtime()
        log("App start recorded")
    }

    /**
     * Records when sessions list becomes visible with cached data.
     */
    fun recordSessionsVisible() {
        if (appStartTime == 0L) return
        sessionsVisibleTime = SystemClock.elapsedRealtime()
        val duration = sessionsVisibleTime - appStartTime
        log("Sessions visible: ${duration}ms")
        pendingTimings.add(TimingRecord("startup_to_sessions", duration))
    }

    /**
     * Records when session resume action starts.
     */
    fun recordResumeStart() {
        resumeStartTime = SystemClock.elapsedRealtime()
        log("Resume start recorded")
    }

    /**
     * Records when first messages are rendered after resume.
     */
    fun recordFirstMessagesRendered() {
        if (resumeStartTime == 0L) return
        firstMessageTime = SystemClock.elapsedRealtime()
        val duration = firstMessageTime - resumeStartTime
        log("First messages rendered: ${duration}ms")
        pendingTimings.add(TimingRecord("resume_to_messages", duration))
    }

    /**
     * Records when a prompt is sent.
     */
    fun recordPromptSend() {
        promptSendTime = SystemClock.elapsedRealtime()
        log("Prompt send recorded")
    }

    /**
     * Records when first token is received.
     */
    fun recordFirstToken() {
        if (promptSendTime == 0L) return
        firstTokenTime = SystemClock.elapsedRealtime()
        val duration = firstTokenTime - promptSendTime
        log("First token received: ${duration}ms")
        pendingTimings.add(TimingRecord("prompt_to_first_token", duration))
    }

    /**
     * Returns all pending timings and clears them.
     */
    fun flushTimings(): List<TimingRecord> {
        val copy = pendingTimings.toList()
        pendingTimings.clear()
        return copy
    }

    /**
     * Returns current pending timings without clearing.
     */
    fun getPendingTimings(): List<TimingRecord> = pendingTimings.toList()

    /**
     * Resets all timing state.
     */
    fun reset() {
        appStartTime = 0
        sessionsVisibleTime = 0
        resumeStartTime = 0
        firstMessageTime = 0
        promptSendTime = 0
        firstTokenTime = 0
        pendingTimings.clear()
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }
}

/**
 * A single timing measurement.
 */
data class TimingRecord(
    val metric: String,
    val durationMs: Long,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Performance budget thresholds.
 */
object PerformanceBudgets {
    // Target and max values in milliseconds
    const val STARTUP_TO_SESSIONS_TARGET = 1500L
    const val STARTUP_TO_SESSIONS_MAX = 2500L

    const val RESUME_TO_MESSAGES_TARGET = 1000L

    const val PROMPT_TO_FIRST_TOKEN_TARGET = 1200L

    /**
     * Checks if a timing meets its budget.
     */
    fun checkBudget(
        metric: String,
        durationMs: Long,
    ): BudgetResult =
        when (metric) {
            "startup_to_sessions" ->
                BudgetResult(
                    metric = metric,
                    durationMs = durationMs,
                    targetMs = STARTUP_TO_SESSIONS_TARGET,
                    maxMs = STARTUP_TO_SESSIONS_MAX,
                    passed = durationMs <= STARTUP_TO_SESSIONS_MAX,
                )
            "resume_to_messages" ->
                BudgetResult(
                    metric = metric,
                    durationMs = durationMs,
                    targetMs = RESUME_TO_MESSAGES_TARGET,
                    maxMs = null,
                    passed = durationMs <= RESUME_TO_MESSAGES_TARGET * 2,
                )
            "prompt_to_first_token" ->
                BudgetResult(
                    metric = metric,
                    durationMs = durationMs,
                    targetMs = PROMPT_TO_FIRST_TOKEN_TARGET,
                    maxMs = null,
                    passed = durationMs <= PROMPT_TO_FIRST_TOKEN_TARGET * 2,
                )
            else ->
                BudgetResult(
                    metric = metric,
                    durationMs = durationMs,
                    targetMs = null,
                    maxMs = null,
                    passed = true,
                )
        }
}

/**
 * Result of a budget check.
 */
data class BudgetResult(
    val metric: String,
    val durationMs: Long,
    val targetMs: Long?,
    val maxMs: Long?,
    val passed: Boolean,
) {
    fun toLogMessage(): String {
        val status = if (passed) "✓ PASS" else "✗ FAIL"
        val target = targetMs?.let { " (target: ${it}ms)" } ?: ""
        val max = maxMs?.let { ", max: ${it}ms" } ?: ""
        return "$status $metric: ${durationMs}ms$target$max"
    }
}
