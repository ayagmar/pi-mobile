package com.ayagmar.pimobile.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class CwdLabelFormatterTest {
    @Test
    fun formatCwdTailUsesLastTwoSegments() {
        val label = formatCwdTail("/home/ayagmar/Projects/pi-mobile")

        assertEquals("Projects/pi-mobile", label)
    }

    @Test
    fun formatCwdTailHandlesRoot() {
        val label = formatCwdTail("/")

        assertEquals("/", label)
    }

    @Test
    fun formatCwdTailHandlesBlankValues() {
        val label = formatCwdTail("  ")

        assertEquals("(unknown)", label)
    }
}
