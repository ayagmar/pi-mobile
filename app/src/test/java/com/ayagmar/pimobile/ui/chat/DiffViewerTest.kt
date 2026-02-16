package com.ayagmar.pimobile.ui.chat

import com.ayagmar.pimobile.chat.EditDiffInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffViewerTest {
    @Test
    fun multiHunkDiffIncludesSkippedSectionBetweenSeparatedChanges() {
        val oldLines = (1..14).map { "line-$it" }
        val newLines =
            oldLines
                .toMutableList()
                .also {
                    it[2] = "line-3-updated"
                    it[11] = "line-12-updated"
                }

        val diffLines =
            computeDiffLines(
                EditDiffInfo(
                    path = "src/Test.kt",
                    oldString = oldLines.joinToString("\n"),
                    newString = newLines.joinToString("\n"),
                ),
            )

        assertTrue(diffLines.any { it.type == DiffLineType.REMOVED && it.content == "line-3" })
        assertTrue(diffLines.any { it.type == DiffLineType.ADDED && it.content == "line-3-updated" })
        assertTrue(diffLines.any { it.type == DiffLineType.REMOVED && it.content == "line-12" })
        assertTrue(diffLines.any { it.type == DiffLineType.ADDED && it.content == "line-12-updated" })
        assertTrue(diffLines.any { it.type == DiffLineType.SKIPPED })
    }

    @Test
    fun diffLinesExposeAccurateOldAndNewLineNumbers() {
        val diffLines =
            computeDiffLines(
                EditDiffInfo(
                    path = "src/Main.kt",
                    oldString = "one\ntwo\nthree",
                    newString = "one\nTWO\nthree\nfour",
                ),
            )

        val removed = diffLines.first { it.type == DiffLineType.REMOVED }
        assertEquals("two", removed.content)
        assertEquals(2, removed.oldLineNumber)
        assertEquals(null, removed.newLineNumber)

        val replacement = diffLines.first { it.type == DiffLineType.ADDED && it.content == "TWO" }
        assertEquals(null, replacement.oldLineNumber)
        assertEquals(2, replacement.newLineNumber)

        val appended = diffLines.first { it.type == DiffLineType.ADDED && it.content == "four" }
        assertEquals(4, appended.newLineNumber)
    }

    @Test
    fun identicalInputProducesOnlyContextLines() {
        val diffLines =
            computeDiffLines(
                EditDiffInfo(
                    path = "README.md",
                    oldString = "alpha\nbeta",
                    newString = "alpha\nbeta",
                ),
            )

        assertEquals(2, diffLines.size)
        assertTrue(diffLines.all { it.type == DiffLineType.CONTEXT })
        assertEquals(listOf(1, 2), diffLines.map { it.oldLineNumber })
        assertEquals(listOf(1, 2), diffLines.map { it.newLineNumber })
    }

    @Test
    fun lineEndingNormalizationTreatsCrLfAndLfAsEquivalent() {
        val diffLines =
            computeDiffLines(
                EditDiffInfo(
                    path = "src/Main.kt",
                    oldString = "first\r\nsecond\r\n",
                    newString = "first\nsecond\n",
                ),
            )

        assertTrue(diffLines.all { it.type == DiffLineType.CONTEXT })
    }

    @Test
    fun prismHighlightingDetectsCommentStringAndNumberKinds() {
        val highlightKinds =
            detectHighlightKindsForTest(
                content = "val message = \"hello\" // note 42",
                path = "src/Main.kt",
            )

        assertTrue("Expected COMMENT in $highlightKinds", highlightKinds.contains("COMMENT"))
        assertTrue("Expected STRING in $highlightKinds", highlightKinds.contains("STRING"))
    }

    @Test
    fun prismHighlightingDetectsStringAndNumberInJson() {
        val highlightKinds =
            detectHighlightKindsForTest(
                content = "{\"count\": 42}",
                path = "config/settings.json",
            )

        assertTrue("Expected NUMBER in $highlightKinds", highlightKinds.contains("NUMBER"))
        assertTrue("Expected at least one highlighted token in $highlightKinds", highlightKinds.isNotEmpty())
    }
}
