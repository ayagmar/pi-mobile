package com.ayagmar.pimobile.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class AnsiStripTest {
    @Test
    fun stripsSgrColorCodes() {
        val input = "\u001B[38;2;128;128;128mCodex\u001B[39m \u001B[38;2;128;128;128m5h\u001B[39m"
        assertEquals("Codex 5h", input.stripAnsi())
    }

    @Test
    fun stripsSimpleSgrCodes() {
        val input = "\u001B[1mbold\u001B[0m normal"
        assertEquals("bold normal", input.stripAnsi())
    }

    @Test
    fun strips256ColorCodes() {
        val input = "\u001B[38;5;196mred\u001B[0m"
        assertEquals("red", input.stripAnsi())
    }

    @Test
    fun returnsPlainTextUnchanged() {
        val input = "no escape codes here"
        assertEquals("no escape codes here", input.stripAnsi())
    }

    @Test
    fun handlesEmptyString() {
        assertEquals("", "".stripAnsi())
    }

    @Test
    fun stripsMixedCodesAndPreservesContent() {
        val input = "\u001B[38;2;204;102;102m00% left\u001B[39m \u001B[38;2;102;102;102m·\u001B[39m"
        assertEquals("00% left ·", input.stripAnsi())
    }
}
