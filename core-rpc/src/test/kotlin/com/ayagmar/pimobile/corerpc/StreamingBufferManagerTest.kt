package com.ayagmar.pimobile.corerpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamingBufferManagerTest {
    @Test
    fun `append accumulates text`() {
        val manager = StreamingBufferManager()

        assertEquals("Hello", manager.append("msg1", 0, "Hello"))
        assertEquals("Hello World", manager.append("msg1", 0, " World"))
        assertEquals("Hello World!", manager.append("msg1", 0, "!"))
    }

    @Test
    fun `multiple content indices are tracked separately`() {
        val manager = StreamingBufferManager()

        manager.append("msg1", 0, "Text 0")
        manager.append("msg1", 1, "Text 1")

        assertEquals("Text 0", manager.snapshot("msg1", 0))
        assertEquals("Text 1", manager.snapshot("msg1", 1))
    }

    @Test
    fun `finalize sets final text`() {
        val manager = StreamingBufferManager()

        manager.append("msg1", 0, "Partial")
        val final = manager.finalize("msg1", 0, "Final Text")

        assertEquals("Final Text", final)
        assertEquals("Final Text", manager.snapshot("msg1", 0))
    }

    @Test
    fun `content is truncated when exceeding max length`() {
        val maxLength = 20
        val manager = StreamingBufferManager(maxContentLength = maxLength)

        val longText = "A".repeat(50)
        val result = manager.append("msg1", 0, longText)

        assertEquals(maxLength, result.length)
        assertTrue(result.all { it == 'A' })
    }

    @Test
    fun `truncation keeps tail of content`() {
        val manager = StreamingBufferManager(maxContentLength = 10)

        manager.append("msg1", 0, "012345") // 6 chars
        val result = manager.append("msg1", 0, "ABCDEF") // Adding 6 more, total 12, should keep last 10

        assertEquals(10, result.length)
        // "012345" + "ABCDEF" = "012345ABCDEF", last 10 = "2345ABCDEF"
        assertEquals("2345ABCDEF", result)
    }

    @Test
    fun `clearMessage removes specific message`() {
        val manager = StreamingBufferManager()

        manager.append("msg1", 0, "Text 1")
        manager.append("msg2", 0, "Text 2")

        manager.clearMessage("msg1")

        assertNull(manager.snapshot("msg1", 0))
        assertEquals("Text 2", manager.snapshot("msg2", 0))
    }

    @Test
    fun `clearAll removes all buffers`() {
        val manager = StreamingBufferManager()

        manager.append("msg1", 0, "Text 1")
        manager.append("msg2", 0, "Text 2")

        manager.clearAll()

        assertNull(manager.snapshot("msg1", 0))
        assertNull(manager.snapshot("msg2", 0))
        assertEquals(0, manager.activeBufferCount())
    }

    @Test
    fun `oldest buffers are evicted when exceeding max tracked`() {
        val maxTracked = 3
        val manager = StreamingBufferManager(maxTrackedMessages = maxTracked)

        manager.append("msg1", 0, "A")
        manager.append("msg2", 0, "B")
        manager.append("msg3", 0, "C")
        manager.append("msg4", 0, "D") // Should evict msg1

        assertNull(manager.snapshot("msg1", 0))
        assertEquals("B", manager.snapshot("msg2", 0))
        assertEquals("C", manager.snapshot("msg3", 0))
        assertEquals("D", manager.snapshot("msg4", 0))
    }

    @Test
    fun `estimatedMemoryUsage returns positive value`() {
        val manager = StreamingBufferManager()

        manager.append("msg1", 0, "Hello World")

        val usage = manager.estimatedMemoryUsage()
        assertTrue(usage > 0)
    }

    @Test
    fun `activeBufferCount tracks correctly`() {
        val manager = StreamingBufferManager()

        assertEquals(0, manager.activeBufferCount())

        manager.append("msg1", 0, "A")
        assertEquals(1, manager.activeBufferCount())

        manager.append("msg1", 1, "B")
        assertEquals(2, manager.activeBufferCount())

        manager.clearMessage("msg1")
        assertEquals(0, manager.activeBufferCount())
    }

    @Test
    fun `finalize with null uses empty string`() {
        val manager = StreamingBufferManager()

        manager.append("msg1", 0, "Partial")
        val result = manager.finalize("msg1", 0, null)

        assertEquals("", result)
    }

    @Test
    fun `finalize truncates final text if too long`() {
        val maxLength = 10
        val manager = StreamingBufferManager(maxContentLength = maxLength)

        val longText = "A".repeat(100)
        val result = manager.finalize("msg1", 0, longText)

        assertEquals(maxLength, result.length)
    }

    @Test
    fun `append after finalize does nothing`() {
        val manager = StreamingBufferManager()

        manager.append("msg1", 0, "Before")
        manager.finalize("msg1", 0, "Final")
        val afterFinalize = manager.append("msg1", 0, "After")

        assertEquals("Final", afterFinalize)
    }

    @Test
    fun `handles many small appends efficiently`() {
        val manager = StreamingBufferManager(compactionThreshold = 10)

        repeat(100) {
            manager.append("msg1", 0, "X")
        }

        val result = manager.snapshot("msg1", 0)
        assertNotNull(result)
        assertEquals(100, result.length)
    }
}
