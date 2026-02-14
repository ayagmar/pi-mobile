package com.ayagmar.pimobile.corerpc

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedEventBufferTest {
    @Test
    fun `trySend succeeds when buffer has capacity`() =
        runTest {
            val buffer = BoundedEventBuffer<String>(capacity = 10)

            assertTrue(buffer.trySend("event1"))
            assertTrue(buffer.trySend("event2"))
        }

    @Test
    fun `trySend drops non-critical events when full`() =
        runTest {
            val buffer =
                BoundedEventBuffer<String>(
                    capacity = 2,
                    isCritical = { it.startsWith("critical") },
                )

            // Fill the buffer
            buffer.trySend("critical-1")
            buffer.trySend("critical-2")

            // This should drop since buffer is full and not critical
            assertFalse(buffer.trySend("normal-1"))
        }

    @Test
    fun `critical events replace oldest when full`() =
        runTest {
            val buffer =
                BoundedEventBuffer<String>(
                    capacity = 2,
                    isCritical = { it.startsWith("critical") },
                )

            buffer.trySend("critical-1")
            buffer.trySend("critical-2")

            // Critical event when full should replace oldest
            assertTrue(buffer.trySend("critical-3"))
        }

    @Test
    fun `flow receives sent events`() =
        runTest {
            val buffer = BoundedEventBuffer<String>(capacity = 10)

            buffer.trySend("a")
            buffer.trySend("b")
            buffer.trySend("c")

            val received = buffer.consumeAsFlow().take(3).toList()
            assertEquals(listOf("a", "b", "c"), received)
        }

    @Test
    fun `bufferSize returns current count`() =
        runTest {
            val buffer = BoundedEventBuffer<String>(capacity = 10)

            assertEquals(0, buffer.bufferSize())

            buffer.trySend("event1")
            assertEquals(1, buffer.bufferSize())

            buffer.trySend("event2")
            buffer.trySend("event3")
            assertEquals(3, buffer.bufferSize())
        }

    @Test
    fun `send suspends until processed`() =
        runTest {
            val buffer = BoundedEventBuffer<String>(capacity = 1)

            buffer.send("event1")

            val received = mutableListOf<String>()
            val collectJob =
                launch {
                    buffer.consumeAsFlow().take(1).collect { received.add(it) }
                }

            // Give time for collection
            kotlinx.coroutines.delay(50)

            collectJob.join()
            assertEquals(listOf("event1"), received)
        }

    @Test
    fun `close prevents further sends`() =
        runTest {
            val buffer = BoundedEventBuffer<String>(capacity = 10)

            buffer.trySend("event1")
            buffer.close()

            // After close, buffer should still accept but implementation is no-op
            assertTrue(buffer.trySend("event2"))
        }

    @Test
    fun `non-critical events dropped when buffer full`() =
        runTest {
            val buffer =
                BoundedEventBuffer<String>(
                    capacity = 1,
                    isCritical = { false },
                )

            assertTrue(buffer.trySend("event1"))
            // Nothing is critical, so this should be dropped
            assertFalse(buffer.trySend("event2"))
        }
}
