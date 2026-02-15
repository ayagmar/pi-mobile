@file:Suppress("TooManyFunctions", "MagicNumber", "CyclomaticComplexMethod", "ReturnCount")

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

private const val COLLAPSED_DIFF_LINES = 120
private const val CONTEXT_LINES = 3
private const val MAX_LCS_CELLS = 2_000_000

private val ADDED_BACKGROUND = Color(0xFFE8F5E9)
private val REMOVED_BACKGROUND = Color(0xFFFFEBEE)
private val ADDED_TEXT = Color(0xFF2E7D32)
private val REMOVED_TEXT = Color(0xFFC62828)
private val GUTTER_TEXT = Color(0xFF64748B)
private val COMMENT_TEXT = Color(0xFF6A737D)
private val STRING_TEXT = Color(0xFF0B7285)
private val NUMBER_TEXT = Color(0xFFB45309)
private val KEYWORD_TEXT = Color(0xFF7C3AED)

private val KOTLIN_KEYWORDS =
    setOf(
        "class",
        "data",
        "fun",
        "val",
        "var",
        "if",
        "else",
        "when",
        "for",
        "while",
        "return",
        "object",
        "interface",
        "sealed",
        "private",
        "public",
        "internal",
        "suspend",
        "override",
        "import",
        "package",
        "null",
        "true",
        "false",
    )

private val JS_TS_KEYWORDS =
    setOf(
        "function",
        "const",
        "let",
        "var",
        "if",
        "else",
        "for",
        "while",
        "return",
        "class",
        "interface",
        "type",
        "extends",
        "implements",
        "import",
        "export",
        "from",
        "async",
        "await",
        "null",
        "true",
        "false",
    )

private val JSON_KEYWORDS = setOf("true", "false", "null")

private enum class SyntaxLanguage {
    KOTLIN,
    JAVASCRIPT,
    JSON,
    MARKDOWN,
    PLAIN,
}

private sealed interface LineEdit {
    data class Unchanged(
        val content: String,
    ) : LineEdit

    data class Added(
        val content: String,
    ) : LineEdit

    data class Removed(
        val content: String,
    ) : LineEdit
}

private data class HighlightSpan(
    val start: Int,
    val end: Int,
    val style: SpanStyle,
)

/**
 * Displays a unified diff view for file edits.
 */
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
                    DiffLineItem(
                        line = line,
                        syntaxLanguage = syntaxLanguage,
                    )
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
    val highlighted =
        buildAnnotatedString {
            append(prefix)
            append(" ")
            append(content)
            addStyle(baseStyle, start = 0, end = length)

            val offset = 2
            val spans = computeHighlightSpans(content, syntaxLanguage)
            spans.forEach { span ->
                addStyle(span.style, start = span.start + offset, end = span.end + offset)
            }
        }

    return highlighted
}

private fun computeHighlightSpans(
    content: String,
    language: SyntaxLanguage,
): List<HighlightSpan> {
    val spans = mutableListOf<HighlightSpan>()

    val commentStart =
        when (language) {
            SyntaxLanguage.KOTLIN,
            SyntaxLanguage.JAVASCRIPT,
            -> content.indexOf("//")
            SyntaxLanguage.MARKDOWN ->
                if (content.trimStart().startsWith("#")) {
                    0
                } else {
                    -1
                }
            else -> -1
        }

    if (commentStart >= 0) {
        spans += HighlightSpan(commentStart, content.length, SpanStyle(color = COMMENT_TEXT))
    }

    STRING_REGEX.findAll(content).forEach { match ->
        if (!isInsideComment(match.range.first, commentStart)) {
            spans +=
                HighlightSpan(
                    start = match.range.first,
                    end = match.range.last + 1,
                    style = SpanStyle(color = STRING_TEXT),
                )
        }
    }

    NUMBER_REGEX.findAll(content).forEach { match ->
        if (!isInsideComment(match.range.first, commentStart)) {
            spans +=
                HighlightSpan(
                    start = match.range.first,
                    end = match.range.last + 1,
                    style = SpanStyle(color = NUMBER_TEXT),
                )
        }
    }

    val keywords =
        when (language) {
            SyntaxLanguage.KOTLIN -> KOTLIN_KEYWORDS
            SyntaxLanguage.JAVASCRIPT -> JS_TS_KEYWORDS
            SyntaxLanguage.JSON -> JSON_KEYWORDS
            else -> emptySet()
        }

    if (keywords.isNotEmpty()) {
        KEYWORD_REGEX.findAll(content).forEach { match ->
            if (match.value in keywords && !isInsideComment(match.range.first, commentStart)) {
                spans +=
                    HighlightSpan(
                        start = match.range.first,
                        end = match.range.last + 1,
                        style = SpanStyle(color = KEYWORD_TEXT),
                    )
            }
        }
    }

    return spans
}

private fun isInsideComment(
    index: Int,
    commentStart: Int,
): Boolean = commentStart >= 0 && index >= commentStart

private fun detectSyntaxLanguage(path: String): SyntaxLanguage {
    val extension = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (extension) {
        "kt", "kts", "java" -> SyntaxLanguage.KOTLIN
        "ts", "tsx", "js", "jsx" -> SyntaxLanguage.JAVASCRIPT
        "json", "jsonl" -> SyntaxLanguage.JSON
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
    val edits = computeLineEdits(oldLines = oldLines, newLines = newLines)
    val numberedDiff = toNumberedDiffLines(edits)
    return collapseToContextHunks(numberedDiff, contextLines = CONTEXT_LINES)
}

private fun splitLines(text: String): List<String> {
    if (text.isEmpty()) {
        return emptyList()
    }
    return text.split('\n')
}

private fun computeLineEdits(
    oldLines: List<String>,
    newLines: List<String>,
): List<LineEdit> {
    if (oldLines.isEmpty() && newLines.isEmpty()) {
        return emptyList()
    }

    val lcsCells = oldLines.size * newLines.size
    if (lcsCells > MAX_LCS_CELLS) {
        return computeFallbackEdits(oldLines, newLines)
    }

    val lcs = Array(oldLines.size + 1) { IntArray(newLines.size + 1) }

    for (oldIndex in oldLines.size - 1 downTo 0) {
        for (newIndex in newLines.size - 1 downTo 0) {
            lcs[oldIndex][newIndex] =
                if (oldLines[oldIndex] == newLines[newIndex]) {
                    lcs[oldIndex + 1][newIndex + 1] + 1
                } else {
                    maxOf(lcs[oldIndex + 1][newIndex], lcs[oldIndex][newIndex + 1])
                }
        }
    }

    val edits = mutableListOf<LineEdit>()
    var oldIndex = 0
    var newIndex = 0

    while (oldIndex < oldLines.size && newIndex < newLines.size) {
        when {
            oldLines[oldIndex] == newLines[newIndex] -> {
                edits += LineEdit.Unchanged(oldLines[oldIndex])
                oldIndex += 1
                newIndex += 1
            }
            lcs[oldIndex + 1][newIndex] >= lcs[oldIndex][newIndex + 1] -> {
                edits += LineEdit.Removed(oldLines[oldIndex])
                oldIndex += 1
            }
            else -> {
                edits += LineEdit.Added(newLines[newIndex])
                newIndex += 1
            }
        }
    }

    while (oldIndex < oldLines.size) {
        edits += LineEdit.Removed(oldLines[oldIndex])
        oldIndex += 1
    }

    while (newIndex < newLines.size) {
        edits += LineEdit.Added(newLines[newIndex])
        newIndex += 1
    }

    return edits
}

private fun computeFallbackEdits(
    oldLines: List<String>,
    newLines: List<String>,
): List<LineEdit> {
    val prefixLength = findCommonPrefixLength(oldLines, newLines)
    val suffixLength = findCommonSuffixLength(oldLines, newLines, prefixLength)

    val edits = mutableListOf<LineEdit>()
    for (index in 0 until prefixLength) {
        edits += LineEdit.Unchanged(oldLines[index])
    }

    val oldChangedEnd = oldLines.size - suffixLength
    val newChangedEnd = newLines.size - suffixLength

    for (index in prefixLength until oldChangedEnd) {
        edits += LineEdit.Removed(oldLines[index])
    }

    for (index in prefixLength until newChangedEnd) {
        edits += LineEdit.Added(newLines[index])
    }

    for (index in oldChangedEnd until oldLines.size) {
        edits += LineEdit.Unchanged(oldLines[index])
    }

    return edits
}

private fun findCommonPrefixLength(
    oldLines: List<String>,
    newLines: List<String>,
): Int {
    var index = 0
    while (index < oldLines.size && index < newLines.size && oldLines[index] == newLines[index]) {
        index += 1
    }
    return index
}

private fun findCommonSuffixLength(
    oldLines: List<String>,
    newLines: List<String>,
    prefixLength: Int,
): Int {
    var offset = 0
    while (
        offset < oldLines.size - prefixLength &&
        offset < newLines.size - prefixLength &&
        oldLines[oldLines.size - 1 - offset] == newLines[newLines.size - 1 - offset]
    ) {
        offset += 1
    }
    return offset
}

private fun toNumberedDiffLines(edits: List<LineEdit>): List<DiffLine> {
    val diffLines = mutableListOf<DiffLine>()
    var oldLineNumber = 1
    var newLineNumber = 1

    edits.forEach { edit ->
        when (edit) {
            is LineEdit.Unchanged -> {
                diffLines +=
                    DiffLine(
                        type = DiffLineType.CONTEXT,
                        content = edit.content,
                        oldLineNumber = oldLineNumber,
                        newLineNumber = newLineNumber,
                    )
                oldLineNumber += 1
                newLineNumber += 1
            }
            is LineEdit.Removed -> {
                diffLines +=
                    DiffLine(
                        type = DiffLineType.REMOVED,
                        content = edit.content,
                        oldLineNumber = oldLineNumber,
                    )
                oldLineNumber += 1
            }
            is LineEdit.Added -> {
                diffLines +=
                    DiffLine(
                        type = DiffLineType.ADDED,
                        content = edit.content,
                        newLineNumber = newLineNumber,
                    )
                newLineNumber += 1
            }
        }
    }

    return diffLines
}

private fun collapseToContextHunks(
    lines: List<DiffLine>,
    contextLines: Int,
): List<DiffLine> {
    if (lines.isEmpty()) {
        return emptyList()
    }

    val changedIndexes =
        lines.indices.filter { index ->
            val type = lines[index].type
            type == DiffLineType.ADDED || type == DiffLineType.REMOVED
        }

    if (changedIndexes.isEmpty()) {
        return lines
    }

    val ranges = mutableListOf<IntRange>()
    changedIndexes.forEach { index ->
        val start = maxOf(0, index - contextLines)
        val end = minOf(lines.lastIndex, index + contextLines)

        val previous = ranges.lastOrNull()
        if (previous == null || start > previous.last + 1) {
            ranges += start..end
        } else {
            ranges[ranges.lastIndex] = previous.first..maxOf(previous.last, end)
        }
    }

    val result = mutableListOf<DiffLine>()
    var currentIndex = 0

    ranges.forEach { range ->
        if (range.first > currentIndex) {
            val skippedCount = range.first - currentIndex
            result +=
                DiffLine(
                    type = DiffLineType.SKIPPED,
                    content = "… $skippedCount unchanged lines …",
                )
        }

        for (lineIndex in range) {
            result += lines[lineIndex]
        }

        currentIndex = range.last + 1
    }

    if (currentIndex <= lines.lastIndex) {
        val skippedCount = lines.size - currentIndex
        result +=
            DiffLine(
                type = DiffLineType.SKIPPED,
                content = "… $skippedCount unchanged lines …",
            )
    }

    return result
}

private val STRING_REGEX = Regex("\"([^\\\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'")
private val NUMBER_REGEX = Regex("\\b\\d+(?:\\.\\d+)?\\b")
private val KEYWORD_REGEX = Regex("\\b[A-Za-z_][A-Za-z0-9_]*\\b")
