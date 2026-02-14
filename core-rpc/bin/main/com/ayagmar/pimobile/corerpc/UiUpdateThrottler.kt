package com.ayagmar.pimobile.corerpc

/**
 * Emits updates at a fixed cadence while coalescing intermediate values.
 */
class UiUpdateThrottler<T>(
    private val minIntervalMs: Long,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private var lastEmissionAtMs: Long? = null
    private var pending: T? = null

    init {
        require(minIntervalMs >= 0) { "minIntervalMs must be >= 0" }
    }

    fun offer(value: T): T? {
        return if (canEmitNow()) {
            recordEmission()
            pending = null
            value
        } else {
            pending = value
            null
        }
    }

    fun drainReady(): T? {
        val pendingValue = pending
        val shouldEmit = pendingValue != null && canEmitNow()
        if (!shouldEmit) {
            return null
        }

        pending = null
        recordEmission()
        return pendingValue
    }

    fun flushPending(): T? {
        val pendingValue = pending ?: return null
        pending = null
        recordEmission()
        return pendingValue
    }

    fun hasPending(): Boolean = pending != null

    private fun canEmitNow(): Boolean {
        val lastEmission = lastEmissionAtMs ?: return true
        return nowMs() - lastEmission >= minIntervalMs
    }

    private fun recordEmission() {
        lastEmissionAtMs = nowMs()
    }
}
