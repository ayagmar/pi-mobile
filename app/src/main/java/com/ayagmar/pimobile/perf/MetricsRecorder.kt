package com.ayagmar.pimobile.perf

/**
 * Interface for recording performance metrics.
 * Abstracts metric recording for testability.
 */
interface MetricsRecorder {
    fun recordAppStart()

    fun recordSessionsVisible()

    fun recordResumeStart()

    fun recordFirstMessagesRendered()

    fun recordPromptSend()

    fun recordFirstToken()

    fun flushTimings(): List<TimingRecord>

    fun getPendingTimings(): List<TimingRecord>

    fun reset()
}

/**
 * Default implementation using system clock and Android logging.
 */
object DefaultMetricsRecorder : MetricsRecorder {
    private var appStartTime: Long = 0
    private var sessionsVisibleTime: Long = 0
    private var resumeStartTime: Long = 0
    private var firstMessageTime: Long = 0
    private var promptSendTime: Long = 0
    private var firstTokenTime: Long = 0

    private val pendingTimings = mutableListOf<TimingRecord>()

    override fun recordAppStart() {
        appStartTime = android.os.SystemClock.elapsedRealtime()
        log("App start recorded")
    }

    override fun recordSessionsVisible() {
        if (appStartTime == 0L) return
        sessionsVisibleTime = android.os.SystemClock.elapsedRealtime()
        val duration = sessionsVisibleTime - appStartTime
        log("Sessions visible: ${duration}ms")
        pendingTimings.add(TimingRecord("startup_to_sessions", duration))
    }

    override fun recordResumeStart() {
        resumeStartTime = android.os.SystemClock.elapsedRealtime()
        log("Resume start recorded")
    }

    override fun recordFirstMessagesRendered() {
        if (resumeStartTime == 0L) return
        firstMessageTime = android.os.SystemClock.elapsedRealtime()
        val duration = firstMessageTime - resumeStartTime
        log("First messages rendered: ${duration}ms")
        pendingTimings.add(TimingRecord("resume_to_messages", duration))
    }

    override fun recordPromptSend() {
        promptSendTime = android.os.SystemClock.elapsedRealtime()
        log("Prompt send recorded")
    }

    override fun recordFirstToken() {
        if (promptSendTime == 0L) return
        firstTokenTime = android.os.SystemClock.elapsedRealtime()
        val duration = firstTokenTime - promptSendTime
        log("First token received: ${duration}ms")
        pendingTimings.add(TimingRecord("prompt_to_first_token", duration))
    }

    override fun flushTimings(): List<TimingRecord> {
        val copy = pendingTimings.toList()
        pendingTimings.clear()
        return copy
    }

    override fun getPendingTimings(): List<TimingRecord> = pendingTimings.toList()

    override fun reset() {
        appStartTime = 0
        sessionsVisibleTime = 0
        resumeStartTime = 0
        firstMessageTime = 0
        promptSendTime = 0
        firstTokenTime = 0
        pendingTimings.clear()
    }

    private fun log(message: String) {
        android.util.Log.d("PerfMetrics", message)
    }
}

/**
 * No-op implementation for testing.
 */
object NoOpMetricsRecorder : MetricsRecorder {
    override fun recordAppStart() = Unit

    override fun recordSessionsVisible() = Unit

    override fun recordResumeStart() = Unit

    override fun recordFirstMessagesRendered() = Unit

    override fun recordPromptSend() = Unit

    override fun recordFirstToken() = Unit

    override fun flushTimings(): List<TimingRecord> = emptyList()

    override fun getPendingTimings(): List<TimingRecord> = emptyList()

    override fun reset() = Unit
}
