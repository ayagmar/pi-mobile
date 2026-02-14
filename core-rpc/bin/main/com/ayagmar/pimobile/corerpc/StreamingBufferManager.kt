package com.ayagmar.pimobile.corerpc

import java.util.concurrent.ConcurrentHashMap

/**
 * Manages streaming text buffers with memory bounds and coalescing.
 *
 * This class provides:
 * - Per-message content size limits
 * - Automatic buffer compaction for long streams
 * - Coalescing of rapid updates to reduce GC pressure
 */
class StreamingBufferManager(
    private val maxContentLength: Int = DEFAULT_MAX_CONTENT_LENGTH,
    private val maxTrackedMessages: Int = DEFAULT_MAX_TRACKED_MESSAGES,
    private val compactionThreshold: Int = DEFAULT_COMPACTION_THRESHOLD,
) {
    private val buffers = ConcurrentHashMap<String, MessageBuffer>()

    /**
     * Appends text to a message buffer. Returns the current full text.
     * If the buffer exceeds maxContentLength, older content is truncated.
     */
    fun append(
        messageId: String,
        contentIndex: Int,
        delta: String,
    ): String {
        val buffer = getOrCreateBuffer(messageId, contentIndex)
        return buffer.append(delta)
    }

    /**
     * Sets the final text for a message buffer.
     */
    fun finalize(
        messageId: String,
        contentIndex: Int,
        finalText: String?,
    ): String {
        val buffer = getOrCreateBuffer(messageId, contentIndex)
        return buffer.finalize(finalText)
    }

    /**
     * Gets the current text for a message without modifying it.
     */
    fun snapshot(
        messageId: String,
        contentIndex: Int = 0,
    ): String? = buffers[makeKey(messageId, contentIndex)]?.snapshot()

    /**
     * Clears a specific message buffer.
     */
    fun clearMessage(messageId: String) {
        buffers.keys.removeIf { it.startsWith("$messageId:") }
    }

    /**
     * Clears all buffers.
     */
    fun clearAll() {
        buffers.clear()
    }

    /**
     * Returns approximate memory usage in bytes.
     */
    fun estimatedMemoryUsage(): Long = buffers.values.sumOf { it.estimatedSize() }

    /**
     * Returns the number of active message buffers.
     */
    fun activeBufferCount(): Int = buffers.size

    private fun getOrCreateBuffer(
        messageId: String,
        contentIndex: Int,
    ): MessageBuffer {
        ensureCapacity()
        val key = makeKey(messageId, contentIndex)
        return buffers.computeIfAbsent(key) {
            MessageBuffer(maxContentLength, compactionThreshold)
        }
    }

    private fun ensureCapacity() {
        if (buffers.size >= maxTrackedMessages) {
            // Remove oldest entries (simple LRU eviction)
            val keysToRemove = buffers.keys.take(buffers.size - maxTrackedMessages + 1)
            keysToRemove.forEach { buffers.remove(it) }
        }
    }

    private fun makeKey(
        messageId: String,
        contentIndex: Int,
    ): String = "$messageId:$contentIndex"

    private class MessageBuffer(
        private val maxLength: Int,
        private val compactionThreshold: Int,
    ) {
        private val segments = ArrayDeque<String>()
        private var totalLength = 0
        private var isFinalized = false

        @Synchronized
        fun append(delta: String): String {
            if (isFinalized) return buildString()

            segments.addLast(delta)
            totalLength += delta.length

            // Compact if we have too many segments
            if (segments.size >= compactionThreshold) {
                compact()
            }

            // Truncate if exceeding max length (keep tail)
            if (totalLength > maxLength) {
                truncateToMax()
            }

            return buildString()
        }

        @Synchronized
        fun finalize(finalText: String?): String {
            isFinalized = true
            segments.clear()
            totalLength = 0

            val resolved = finalText ?: ""
            if (resolved.length <= maxLength) {
                segments.addLast(resolved)
                totalLength = resolved.length
            } else {
                // Keep only the tail
                val tail = resolved.takeLast(maxLength)
                segments.addLast(tail)
                totalLength = tail.length
            }

            return buildString()
        }

        @Synchronized
        fun snapshot(): String = buildString()

        @Synchronized
        fun estimatedSize(): Long {
            // Rough estimate: each segment has overhead + content
            return segments.sumOf { it.length * BYTES_PER_CHAR + SEGMENT_OVERHEAD } + BUFFER_OVERHEAD
        }

        private fun compact() {
            val combined = buildString()
            segments.clear()
            segments.addLast(combined)
            totalLength = combined.length
        }

        private fun truncateToMax() {
            val current = buildString()

            // Keep the tail (most recent content)
            val truncated = current.takeLast(maxLength)

            segments.clear()
            if (truncated.isNotEmpty()) {
                segments.addLast(truncated)
            }
            totalLength = truncated.length
        }

        private fun buildString(): String = segments.joinToString("")
    }

    companion object {
        const val DEFAULT_MAX_CONTENT_LENGTH = 50_000 // ~10k tokens
        const val DEFAULT_MAX_TRACKED_MESSAGES = 16
        const val DEFAULT_COMPACTION_THRESHOLD = 32

        // Memory estimation constants
        private const val BYTES_PER_CHAR = 2L // UTF-16
        private const val SEGMENT_OVERHEAD = 40L // Object overhead estimate
        private const val BUFFER_OVERHEAD = 100L // Map/tracking overhead
    }
}
