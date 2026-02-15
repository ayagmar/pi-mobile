@file:Suppress("TooManyFunctions", "MagicNumber")

package com.ayagmar.pimobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ayagmar.pimobile.chat.EditDiffInfo
import com.github.difflib.DiffUtils
import com.github.difflib.patch.AbstractDelta
import com.github.difflib.patch.DeltaType

private const val COLLAPSED_DIFF_LINES = 120
private const val CONTEXT_LINES = 3

private val ADDED_BACKGROUND = Color(0xFFE8F5E9)
private val REMOVED_BACKGROUND = Color(0xFFFFEBEE)
private val ADDED_TEXT = Color(0xFF2E7D32)
private val REMOVED_TEXT = Color(0xFFC62828)
private val GUTTER_TEXT = Color(0xFF64748B)
private val COMMENT_TEXT = Color(0xFF6A737D)
private val STRING_TEXT = Color(0xFF0B7285)
private val NUMBER_TEXT = Color(0xFFB45309)

private enum class SyntaxLanguage {
    CODE,
    MARKDOWN,
    PLAIN,
}

private data class HighlightSpan(
    val start: Int,
    val end: Int,
    val style: SpanStyle,
)

@Composable
fun DiffViewer(
    diffInfo: EditDiffInfo,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    val syntaxLanguage = remember(diffInfo.path) { detectSyntaxLanguage(diffInfo.path) }
    val diffLines = remember(diffInfo) { computeDiffLines(diffInfo) }
    val displayLines =
        if (isCollapsed && diffLines.size > COLLAPSED_DIFF_LINES) {
            diffLines.take(COLLAPSED_DIFF_LINES)
        } else {
            diffLines
        }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DiffHeader(
                path = diffInfo.path,
                onCopyPath = { clipboardManager.setText(AnnotatedString(diffInfo.path)) },
            )

            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
            ) {
                items(displayLines) { line ->
                    DiffLineItem(line = line, syntaxLanguage = syntaxLanguage)
                }
            }

            if (diffLines.size > COLLAPSED_DIFF_LINES) {
                TextButton(
                    onClick = onToggleCollapse,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    val buttonText =
                        if (isCollapsed) {
                            "Expand (${diffLines.size - COLLAPSED_DIFF_LINES} more lines)"
                        } else {
                            "Collapse"
                        }
                    Text(buttonText)
                }
            }
        }
    }
}

@Composable
private fun DiffHeader(
    path: String,
    onCopyPath: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = path,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCopyPath) {
            Text("Copy")
        }
    }
}

@Composable
private fun DiffLineItem(
    line: DiffLine,
    syntaxLanguage: SyntaxLanguage,
) {
    if (line.type == DiffLineType.SKIPPED) {
        Text(
            text = line.content,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        )
        return
    }

    val backgroundColor =
        when (line.type) {
            DiffLineType.ADDED -> ADDED_BACKGROUND
            DiffLineType.REMOVED -> REMOVED_BACKGROUND
            DiffLineType.CONTEXT,
            DiffLineType.SKIPPED,
            -> Color.Transparent
        }

    val contentColor =
        when (line.type) {
            DiffLineType.ADDED -> ADDED_TEXT
            DiffLineType.REMOVED -> REMOVED_TEXT
            DiffLineType.CONTEXT,
            DiffLineType.SKIPPED,
            -> MaterialTheme.colorScheme.onSurface
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(backgroundColor)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        LineNumberCell(number = line.oldLineNumber)
        LineNumberCell(number = line.newLineNumber)

        SelectionContainer {
            Text(
                text = buildHighlightedDiffLine(line, syntaxLanguage, contentColor),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LineNumberCell(number: Int?) {
    Text(
        text = number?.toString().orEmpty(),
        style = MaterialTheme.typography.bodySmall,
        color = GUTTER_TEXT,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.End,
        modifier = Modifier.width(44.dp).padding(end = 6.dp),
    )
}

private fun buildHighlightedDiffLine(
    line: DiffLine,
    syntaxLanguage: SyntaxLanguage,
    baseContentColor: Color,
): AnnotatedString {
    val prefix =
        when (line.type) {
            DiffLineType.ADDED -> "+"
            DiffLineType.REMOVED -> "-"
            DiffLineType.CONTEXT -> " "
            DiffLineType.SKIPPED -> " "
        }

    val content = line.content
    val baseStyle = SpanStyle(color = baseContentColor, fontFamily = FontFamily.Monospace)

    return buildAnnotatedString {
        append(prefix)
        append(" ")
        append(content)
        addStyle(baseStyle, start = 0, end = length)

        val offset = 2
        computeHighlightSpans(content, syntaxLanguage).forEach { span ->
            addStyle(span.style, start = span.start + offset, end = span.end + offset)
        }
    }
}

private fun computeHighlightSpans(
    content: String,
    language: SyntaxLanguage,
): List<HighlightSpan> {
    val spans = mutableListOf<HighlightSpan>()
    val commentRange = commentRange(content, language)

    commentRange?.let { range ->
        spans +=
            HighlightSpan(
                start = range.first,
                end = range.last + 1,
                style = SpanStyle(color = COMMENT_TEXT),
            )
    }

    STRING_REGEX.findAll(content).forEach { match ->
        if (!isInComment(match.range.first, commentRange)) {
            spans +=
                HighlightSpan(
                    start = match.range.first,
                    end = match.range.last + 1,
                    style = SpanStyle(color = STRING_TEXT),
                )
        }
    }

    NUMBER_REGEX.findAll(content).forEach { match ->
        if (!isInComment(match.range.first, commentRange)) {
            spans +=
                HighlightSpan(
                    start = match.range.first,
                    end = match.range.last + 1,
                    style = SpanStyle(color = NUMBER_TEXT),
                )
        }
    }

    return spans
}

private fun commentRange(
    content: String,
    language: SyntaxLanguage,
): IntRange? {
    return when (language) {
        SyntaxLanguage.CODE -> {
            val start = content.indexOf("//")
            if (start >= 0) start until content.length else null
        }
        SyntaxLanguage.MARKDOWN -> {
            if (content.trimStart().startsWith("#")) {
                0 until content.length
            } else {
                null
            }
        }
        SyntaxLanguage.PLAIN -> null
    }
}

private fun isInComment(
    index: Int,
    commentRange: IntRange?,
): Boolean {
    return commentRange != null && index in commentRange
}

private fun detectSyntaxLanguage(path: String): SyntaxLanguage {
    val extension = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (extension) {
        "kt", "kts", "java", "ts", "tsx", "js", "jsx", "go", "py", "rb", "swift", "cs", "cpp", "c" -> {
            SyntaxLanguage.CODE
        }
        "md", "markdown" -> SyntaxLanguage.MARKDOWN
        else -> SyntaxLanguage.PLAIN
    }
}

/**
 * Represents a single line in a diff.
 */
data class DiffLine(
    val type: DiffLineType,
    val content: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
)

enum class DiffLineType {
    ADDED,
    REMOVED,
    CONTEXT,
    SKIPPED,
}

internal fun computeDiffLines(diffInfo: EditDiffInfo): List<DiffLine> {
    val oldLines = splitLines(diffInfo.oldString)
    val newLines = splitLines(diffInfo.newString)
    val completeDiff = buildCompleteDiff(oldLines = oldLines, newLines = newLines)
    return collapseToContextHunks(completeDiff, contextLines = CONTEXT_LINES)
}

private fun splitLines(text: String): List<String> {
    return if (text.isEmpty()) {
        emptyList()
    } else {
        text.split('\n')
    }
}

private data class DiffCursor(
    var oldIndex: Int = 0,
    var newIndex: Int = 0,
)

private fun buildCompleteDiff(
    oldLines: List<String>,
    newLines: List<String>,
): List<DiffLine> {
    val deltas = sortedDeltas(oldLines, newLines)
    val diffLines = mutableListOf<DiffLine>()
    val cursor = DiffCursor()

    deltas.forEach { delta ->
        appendContextUntil(
            lines = diffLines,
            oldLines = oldLines,
            targetOldIndex = delta.source.position,
            cursor = cursor,
        )
        appendDeltaLines(lines = diffLines, delta = delta, cursor = cursor)
    }

    appendRemainingLines(lines = diffLines, oldLines = oldLines, newLines = newLines, cursor = cursor)

    return diffLines
}

private fun sortedDeltas(
    oldLines: List<String>,
    newLines: List<String>,
): List<AbstractDelta<String>> {
    return DiffUtils
        .diff(oldLines, newLines)
        .deltas
        .sortedWith(compareBy<AbstractDelta<String>> { it.source.position }.thenBy { it.target.position })
}

private fun appendContextUntil(
    lines: MutableList<DiffLine>,
    oldLines: List<String>,
    targetOldIndex: Int,
    cursor: DiffCursor,
) {
    while (cursor.oldIndex < targetOldIndex) {
        lines +=
            DiffLine(
                type = DiffLineType.CONTEXT,
                content = oldLines[cursor.oldIndex],
                oldLineNumber = cursor.oldIndex + 1,
                newLineNumber = cursor.newIndex + 1,
            )
        cursor.oldIndex += 1
        cursor.newIndex += 1
    }
}

private fun appendDeltaLines(
    lines: MutableList<DiffLine>,
    delta: AbstractDelta<String>,
    cursor: DiffCursor,
) {
    when (delta.type) {
        DeltaType.INSERT -> appendAddedLines(lines, delta.target.lines, cursor)
        DeltaType.DELETE -> appendRemovedLines(lines, delta.source.lines, cursor)
        DeltaType.CHANGE -> {
            appendRemovedLines(lines, delta.source.lines, cursor)
            appendAddedLines(lines, delta.target.lines, cursor)
        }
        DeltaType.EQUAL,
        null,
        -> Unit
    }
}

private fun appendRemovedLines(
    lines: MutableList<DiffLine>,
    sourceLines: List<String>,
    cursor: DiffCursor,
) {
    sourceLines.forEach { content ->
        lines +=
            DiffLine(
                type = DiffLineType.REMOVED,
                content = content,
                oldLineNumber = cursor.oldIndex + 1,
            )
        cursor.oldIndex += 1
    }
}

private fun appendAddedLines(
    lines: MutableList<DiffLine>,
    targetLines: List<String>,
    cursor: DiffCursor,
) {
    targetLines.forEach { content ->
        lines +=
            DiffLine(
                type = DiffLineType.ADDED,
                content = content,
                newLineNumber = cursor.newIndex + 1,
            )
        cursor.newIndex += 1
    }
}

private fun appendRemainingLines(
    lines: MutableList<DiffLine>,
    oldLines: List<String>,
    newLines: List<String>,
    cursor: DiffCursor,
) {
    while (cursor.oldIndex < oldLines.size && cursor.newIndex < newLines.size) {
        lines +=
            DiffLine(
                type = DiffLineType.CONTEXT,
                content = oldLines[cursor.oldIndex],
                oldLineNumber = cursor.oldIndex + 1,
                newLineNumber = cursor.newIndex + 1,
            )
        cursor.oldIndex += 1
        cursor.newIndex += 1
    }

    appendRemovedLines(lines, oldLines.drop(cursor.oldIndex), cursor)
    appendAddedLines(lines, newLines.drop(cursor.newIndex), cursor)
}

private fun collapseToContextHunks(
    lines: List<DiffLine>,
    contextLines: Int,
): List<DiffLine> {
    if (lines.isEmpty()) return emptyList()

    val changedIndexes =
        lines.indices.filter { index ->
            lines[index].type == DiffLineType.ADDED || lines[index].type == DiffLineType.REMOVED
        }

    val hasChanges = changedIndexes.isNotEmpty()
    return if (hasChanges) {
        buildCollapsedHunks(lines = lines, changedIndexes = changedIndexes, contextLines = contextLines)
    } else {
        lines
    }
}

private fun buildCollapsedHunks(
    lines: List<DiffLine>,
    changedIndexes: List<Int>,
    contextLines: Int,
): List<DiffLine> {
    val mergedRanges = mutableListOf<IntRange>()
    changedIndexes.forEach { changedIndex ->
        val start = maxOf(0, changedIndex - contextLines)
        val end = minOf(lines.lastIndex, changedIndex + contextLines)

        val previous = mergedRanges.lastOrNull()
        if (previous == null || start > previous.last + 1) {
            mergedRanges += start..end
        } else {
            mergedRanges[mergedRanges.lastIndex] = previous.first..maxOf(previous.last, end)
        }
    }

    return materializeCollapsedRanges(lines = lines, mergedRanges = mergedRanges)
}

private fun materializeCollapsedRanges(
    lines: List<DiffLine>,
    mergedRanges: List<IntRange>,
): List<DiffLine> {
    val result = mutableListOf<DiffLine>()
    var nextStart = 0

    mergedRanges.forEach { range ->
        if (range.first > nextStart) {
            result += skippedLine(range.first - nextStart)
        }

        for (index in range) {
            result += lines[index]
        }

        nextStart = range.last + 1
    }

    if (nextStart <= lines.lastIndex) {
        result += skippedLine(lines.size - nextStart)
    }

    return result
}

private fun skippedLine(count: Int): DiffLine {
    return DiffLine(
        type = DiffLineType.SKIPPED,
        content = "… $count unchanged lines …",
    )
}

private val STRING_REGEX = Regex("\"([^\\\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'")
private val NUMBER_REGEX = Regex("\\b\\d+(?:\\.\\d+)?\\b")
