package com.ayagmar.pimobile.corerpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiUpdateThrottlerTest {
    @Test
    fun `coalesces burst updates until cadence window elapses`() {
        val clock = FakeClock()
        val throttler = UiUpdateThrottler<String>(minIntervalMs = 50, nowMs = clock::now)

        assertEquals("a", throttler.offer("a"))

        clock.advanceBy(10)
        assertNull(throttler.offer("b"))
        clock.advanceBy(10)
        assertNull(throttler.offer("c"))
        assertTrue(throttler.hasPending())

        clock.advanceBy(29)
        assertNull(throttler.drainReady())

        clock.advanceBy(1)
        assertEquals("c", throttler.drainReady())
        assertFalse(throttler.hasPending())
    }

    @Test
    fun `flush emits the latest pending update immediately`() {
        val clock = FakeClock()
        val throttler = UiUpdateThrottler<String>(minIntervalMs = 100, nowMs = clock::now)

        assertEquals("a", throttler.offer("a"))
        clock.advanceBy(10)
        assertNull(throttler.offer("b"))
        clock.advanceBy(10)
        assertNull(throttler.offer("c"))

        assertEquals("c", throttler.flushPending())
        assertNull(throttler.flushPending())
        assertFalse(throttler.hasPending())

        clock.advanceBy(10)
        assertNull(throttler.offer("d"))
    }

    private class FakeClock {
        private var currentMs: Long = 0

        fun now(): Long = currentMs

        fun advanceBy(deltaMs: Long) {
            currentMs += deltaMs
        }
    }
}
