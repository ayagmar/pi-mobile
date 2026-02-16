package com.ayagmar.pimobile.perf

import android.util.Log
import android.view.Choreographer
import android.view.Choreographer.FrameCallback
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

// Threshold for logging scroll performance
private const val SCROLL_LOG_THRESHOLD_MS = 100L

/**
 * Frame metrics tracker for detecting UI jank during streaming.
 *
 * Monitors frame time and reports dropped frames.
 */
class FrameMetrics private constructor() {
    private val choreographer = Choreographer.getInstance()
    private var frameCallback: FrameCallback? = null
    private var lastFrameTime = 0L
    private var isTracking = false

    private val droppedFrames = mutableListOf<DroppedFrameRecord>()
    private var onJankDetected: ((DroppedFrameRecord) -> Unit)? = null

    /**
     * Starts tracking frame metrics.
     */
    fun startTracking(onJank: ((DroppedFrameRecord) -> Unit)? = null) {
        if (isTracking) return
        isTracking = true
        onJankDetected = onJank
        lastFrameTime = System.nanoTime()

        val callback =
            object : FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (!isTracking) return

                    val frameTimeMs = (frameTimeNanos - lastFrameTime) / NANOS_PER_MILLIS
                    lastFrameTime = frameTimeNanos

                    // Detect jank based on thresholds
                    when {
                        frameTimeMs > JANK_THRESHOLD_CRITICAL -> {
                            recordDroppedFrame(frameTimeMs, FrameSeverity.CRITICAL)
                        }
                        frameTimeMs > JANK_THRESHOLD_HIGH -> {
                            recordDroppedFrame(frameTimeMs, FrameSeverity.HIGH)
                        }
                        frameTimeMs > JANK_THRESHOLD_MEDIUM -> {
                            recordDroppedFrame(frameTimeMs, FrameSeverity.MEDIUM)
                        }
                    }

                    // Schedule next frame
                    if (isTracking) {
                        choreographer.postFrameCallback(this)
                    }
                }
            }

        frameCallback = callback
        choreographer.postFrameCallback(callback)
    }

    /**
     * Stops tracking frame metrics.
     */
    fun stopTracking() {
        isTracking = false
        frameCallback?.let { choreographer.removeFrameCallback(it) }
        frameCallback = null
    }

    /**
     * Returns all recorded dropped frames and clears them.
     */
    fun flushDroppedFrames(): List<DroppedFrameRecord> {
        val copy = droppedFrames.toList()
        droppedFrames.clear()
        return copy
    }

    /**
     * Returns current dropped frames without clearing.
     */
    fun getDroppedFrames(): List<DroppedFrameRecord> = droppedFrames.toList()

    private fun recordDroppedFrame(
        frameTimeMs: Long,
        severity: FrameSeverity,
    ) {
        val record =
            DroppedFrameRecord(
                frameTimeMs = frameTimeMs,
                severity = severity,
                expectedFrames =
                    when (severity) {
                        FrameSeverity.MEDIUM -> MEDIUM_DROPPED_FRAMES
                        FrameSeverity.HIGH -> HIGH_DROPPED_FRAMES
                        FrameSeverity.CRITICAL -> CRITICAL_DROPPED_FRAMES
                    },
            )
        droppedFrames.add(record)
        onJankDetected?.invoke(record)

        if (severity == FrameSeverity.CRITICAL) {
            Log.w(TAG, "Critical jank detected: ${frameTimeMs}ms")
        }
    }

    companion object {
        private const val TAG = "FrameMetrics"

        // Jank detection thresholds in milliseconds
        private const val NANOS_PER_MILLIS = 1_000_000L
        private const val JANK_THRESHOLD_MEDIUM = 33L // ~30fps
        private const val JANK_THRESHOLD_HIGH = 50L
        private const val JANK_THRESHOLD_CRITICAL = 100L

        // Dropped frame estimates
        private const val MEDIUM_DROPPED_FRAMES = 2
        private const val HIGH_DROPPED_FRAMES = 3
        private const val CRITICAL_DROPPED_FRAMES = 6

        @Volatile
        private var instance: FrameMetrics? = null

        fun getInstance(): FrameMetrics {
            return instance ?: synchronized(this) {
                instance ?: FrameMetrics().also { instance = it }
            }
        }
    }
}

/**
 * Severity levels for dropped frames.
 */
enum class FrameSeverity {
    MEDIUM, // 33-50ms (dropped 1 frame at 60fps)
    HIGH, // 50-100ms (dropped 2-3 frames)
    CRITICAL, // >100ms (dropped 6+ frames)
}

/**
 * Record of a dropped frame event.
 */
data class DroppedFrameRecord(
    val frameTimeMs: Long,
    val severity: FrameSeverity,
    val expectedFrames: Int,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Composable that tracks frame metrics during streaming.
 */
@Composable
fun StreamingFrameMetrics(
    isStreaming: Boolean,
    onJankDetected: ((DroppedFrameRecord) -> Unit)? = null,
) {
    DisposableEffect(isStreaming) {
        val frameMetrics = FrameMetrics.getInstance()

        if (isStreaming) {
            frameMetrics.startTracking(onJankDetected)
        } else {
            frameMetrics.stopTracking()
        }

        onDispose {
            frameMetrics.stopTracking()
        }
    }
}

/**
 * Tracks scroll performance in a LazyList.
 */
@Composable
fun TrackScrollPerformance(
    listState: LazyListState,
    onJankDetected: ((DroppedFrameRecord) -> Unit)? = null,
) {
    var isScrolling by remember { mutableLongStateOf(0L) }

    DisposableEffect(listState.isScrollInProgress) {
        val frameMetrics = FrameMetrics.getInstance()

        if (listState.isScrollInProgress) {
            isScrolling = System.currentTimeMillis()
            frameMetrics.startTracking(onJankDetected)
        } else {
            val scrollDuration = System.currentTimeMillis() - isScrolling
            // Only log if scroll was substantial
            if (scrollDuration > SCROLL_LOG_THRESHOLD_MS) {
                val droppedFrames = frameMetrics.flushDroppedFrames()
                if (droppedFrames.isNotEmpty()) {
                    val totalDropped = droppedFrames.sumOf { it.expectedFrames }
                    Log.d(
                        "ScrollPerf",
                        "Scroll duration: ${scrollDuration}ms, " +
                            "dropped frames: $totalDropped",
                    )
                }
            }
            frameMetrics.stopTracking()
        }

        onDispose {
            frameMetrics.stopTracking()
        }
    }
}
