package com.ayagmar.pimobile.perf

/**
 * Performance metrics tracker for key user journeys.
 *
 * Tracks:
 * - Cold app start to visible cached sessions
 * - Resume session to first rendered messages
 * - Prompt send to first token (TTFT)
 *
 * Delegates to [MetricsRecorder] for actual implementation.
 */
object PerformanceMetrics : MetricsRecorder by DefaultMetricsRecorder

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
