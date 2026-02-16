package com.ayagmar.pimobile.chat

private val ANSI_ESCAPE_REGEX = Regex("""\x1B\[[0-9;]*[A-Za-z]|\x1B\].*?\x07""")

/**
 * Strips ANSI escape sequences from a string.
 * Handles SGR codes like `\e[38;2;r;g;bm` and OSC sequences.
 */
fun String.stripAnsi(): String = ANSI_ESCAPE_REGEX.replace(this, "")
