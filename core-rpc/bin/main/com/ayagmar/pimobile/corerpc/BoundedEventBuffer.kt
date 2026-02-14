package com.ayagmar.pimobile.corerpc

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque

/**
 * Bounded buffer for RPC events with backpressure handling.
 *
 * When the buffer reaches capacity, non-critical events are dropped to prevent
 * memory exhaustion during high-frequency streaming scenarios.
 */
class BoundedEventBuffer<T>(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val isCritical: (T) -> Boolean = { true },
) {
    private val buffer = ArrayDeque<T>(capacity)
    private val mutex = Mutex()

    /**
     * Attempts to send an event to the buffer.
     * Returns true if sent, false if dropped due to backpressure.
     */
    suspend fun trySend(event: T): Boolean =
        mutex.withLock {
            if (buffer.size < capacity) {
                buffer.addLast(event)
                true
            } else {
                // Buffer is full, drop non-critical events
                if (!isCritical(event)) {
                    false
                } else {
                    // Critical event: remove oldest and add this one
                    buffer.removeFirst()
                    buffer.addLast(event)
                    true
                }
            }
        }

    /**
     * Suspends until the event can be sent.
     * For critical events only.
     */
    suspend fun send(event: T) {
        trySend(event)
    }

    /**
     * Consumes events as a Flow.
     */
    fun consumeAsFlow(): Flow<T> =
        flow {
            while (true) {
                val event =
                    mutex.withLock {
                        if (buffer.isNotEmpty()) buffer.removeFirst() else null
                    }
                if (event != null) {
                    emit(event)
                } else {
                    kotlinx.coroutines.delay(POLL_DELAY_MS)
                }
            }
        }

    /**
     * Returns the number of events currently buffered.
     */
    suspend fun bufferSize(): Int = mutex.withLock { buffer.size }

    /**
     * Closes the buffer. No more events can be sent.
     */
    fun close() {
        // No-op for this implementation
    }

    companion object {
        const val DEFAULT_CAPACITY = 128
        private const val POLL_DELAY_MS = 10L
    }
}
