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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ayagmar.pimobile.R
import com.ayagmar.pimobile.chat.EditDiffInfo
import com.github.difflib.DiffUtils
import com.github.difflib.patch.AbstractDelta
import com.github.difflib.patch.DeltaType
import io.noties.prism4j.AbsVisitor
import io.noties.prism4j.Prism4j
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

private const val DEFAULT_COLLAPSED_DIFF_LINES = 120
private const val DEFAULT_CONTEXT_LINES = 3

@Immutable
data class DiffViewerStyle(
    val collapsedDiffLines: Int = DEFAULT_COLLAPSED_DIFF_LINES,
    val contextLines: Int = DEFAULT_CONTEXT_LINES,
    val gutterWidth: Dp = 44.dp,
    val lineRowHorizontalPadding: Dp = 4.dp,
    val lineRowVerticalPadding: Dp = 2.dp,
    val contentHorizontalPadding: Dp = 8.dp,
    val headerHorizontalPadding: Dp = 12.dp,
    val headerVerticalPadding: Dp = 8.dp,
    val skippedLineHorizontalPadding: Dp = 8.dp,
    val skippedLineVerticalPadding: Dp = 4.dp,
)

@Immutable
data class DiffViewerColors(
    val addedBackground: Color,
    val removedBackground: Color,
    val addedText: Color,
    val removedText: Color,
    val gutterText: Color,
    val commentText: Color,
    val stringText: Color,
    val numberText: Color,
    val keywordText: Color,
)

@Composable
private fun rememberDiffViewerColors(): DiffViewerColors {
    val colors = MaterialTheme.colorScheme
    return remember(colors) {
        DiffViewerColors(
            addedBackground = colors.primaryContainer.copy(alpha = 0.32f),
            removedBackground = colors.errorContainer.copy(alpha = 0.32f),
            addedText = colors.primary,
            removedText = colors.error,
            gutterText = colors.onSurfaceVariant,
            commentText = colors.onSurfaceVariant,
            stringText = colors.tertiary,
            numberText = colors.secondary,
            keywordText = colors.primary,
        )
    }
}

private enum class SyntaxLanguage(
    val prismGrammarName: String?,
) {
    KOTLIN("kotlin"),
    JAVA("java"),
    JAVASCRIPT("javascript"),
    JSON("json"),
    MARKDOWN("markdown"),
    MARKUP("markup"),
    MAKEFILE("makefile"),
    PYTHON("python"),
    GO("go"),
    SWIFT("swift"),
    C("c"),
    CPP("cpp"),
    CSHARP("csharp"),
    CSS("css"),
    SQL("sql"),
    YAML("yaml"),
    PLAIN(null),
}

private enum class HighlightKind {
    COMMENT,
    STRING,
    NUMBER,
    KEYWORD,
}

private data class HighlightSpan(
    val start: Int,
    val end: Int,
    val kind: HighlightKind,
)

private data class DiffPresentationLine(
    val line: DiffLine,
    val highlightSpans: List<HighlightSpan>,
)

@Composable
fun DiffViewer(
    diffInfo: EditDiffInfo,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    style: DiffViewerStyle = DiffViewerStyle(),
) {
    val clipboardManager = LocalClipboardManager.current
    val syntaxLanguage = remember(diffInfo.path) { detectSyntaxLanguage(diffInfo.path) }
    val diffColors = rememberDiffViewerColors()
    val presentationLines by
        produceState(initialValue = emptyList<DiffPresentationLine>(), diffInfo, style.contextLines, syntaxLanguage) {
            value =
                withContext(Dispatchers.Default) {
                    computeDiffPresentationLines(
                        diffInfo = diffInfo,
                        contextLines = style.contextLines,
                        syntaxLanguage = syntaxLanguage,
                    )
                }
        }
    val displayLines =
        if (isCollapsed && presentationLines.size > style.collapsedDiffLines) {
            presentationLines.take(style.collapsedDiffLines)
        } else {
            presentationLines
        }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DiffHeader(
                path = diffInfo.path,
                onCopyPath = { clipboardManager.setText(AnnotatedString(diffInfo.path)) },
                style = style,
            )

            DiffLinesList(
                lines = displayLines,
                style = style,
                colors = diffColors,
            )

            DiffCollapseToggle(
                totalLines = presentationLines.size,
                isCollapsed = isCollapsed,
                style = style,
                onToggleCollapse = onToggleCollapse,
            )
        }
    }
}

@Composable
private fun DiffLinesList(
    lines: List<DiffPresentationLine>,
    style: DiffViewerStyle,
    colors: DiffViewerColors,
) {
    LazyColumn(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = style.contentHorizontalPadding),
    ) {
        items(lines) { presentationLine ->
            DiffLineItem(
                presentationLine = presentationLine,
                style = style,
                colors = colors,
            )
        }
    }
}

@Composable
private fun DiffCollapseToggle(
    totalLines: Int,
    isCollapsed: Boolean,
    style: DiffViewerStyle,
    onToggleCollapse: () -> Unit,
) {
    if (totalLines <= style.collapsedDiffLines) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(
            onClick = onToggleCollapse,
        ) {
            val remainingLines = totalLines - style.collapsedDiffLines
            val buttonText =
                if (isCollapsed) {
                    pluralStringResource(
                        id = R.plurals.diff_viewer_expand_more_lines,
                        count = remainingLines,
                        remainingLines,
                    )
                } else {
                    stringResource(id = R.string.diff_viewer_collapse)
                }
            Text(buttonText)
        }
    }
}

@Composable
private fun DiffHeader(
    path: String,
    onCopyPath: () -> Unit,
    style: DiffViewerStyle,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(
                    horizontal = style.headerHorizontalPadding,
                    vertical = style.headerVerticalPadding,
                ),
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
            Text(stringResource(id = R.string.diff_viewer_copy))
        }
    }
}

@Composable
private fun DiffLineItem(
    presentationLine: DiffPresentationLine,
    style: DiffViewerStyle,
    colors: DiffViewerColors,
) {
    val line = presentationLine.line

    if (line.type == DiffLineType.SKIPPED) {
        SkippedDiffLine(line = line, style = style)
        return
    }

    val backgroundColor =
        when (line.type) {
            DiffLineType.ADDED -> colors.addedBackground
            DiffLineType.REMOVED -> colors.removedBackground
            DiffLineType.CONTEXT,
            DiffLineType.SKIPPED,
            -> Color.Transparent
        }

    val contentColor =
        when (line.type) {
            DiffLineType.ADDED -> colors.addedText
            DiffLineType.REMOVED -> colors.removedText
            DiffLineType.CONTEXT,
            DiffLineType.SKIPPED,
            -> MaterialTheme.colorScheme.onSurface
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(backgroundColor)
                .padding(
                    horizontal = style.lineRowHorizontalPadding,
                    vertical = style.lineRowVerticalPadding,
                ),
        verticalAlignment = Alignment.Top,
    ) {
        LineNumberCell(number = line.oldLineNumber, style = style, colors = colors)
        LineNumberCell(number = line.newLineNumber, style = style, colors = colors)

        SelectionContainer {
            Text(
                text =
                    buildHighlightedDiffLine(
                        line = line,
                        baseContentColor = contentColor,
                        colors = colors,
                        highlightSpans = presentationLine.highlightSpans,
                    ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SkippedDiffLine(
    line: DiffLine,
    style: DiffViewerStyle,
) {
    val hiddenLines = line.hiddenUnchangedCount ?: 0
    val skippedLabel =
        pluralStringResource(
            id = R.plurals.diff_viewer_hidden_unchanged_lines,
            count = hiddenLines,
            hiddenLines,
        )
    Text(
        text = skippedLabel,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = style.skippedLineHorizontalPadding,
                    vertical = style.skippedLineVerticalPadding,
                ),
    )
}

@Composable
private fun LineNumberCell(
    number: Int?,
    style: DiffViewerStyle,
    colors: DiffViewerColors,
) {
    Text(
        text = number?.toString().orEmpty(),
        style = MaterialTheme.typography.bodySmall,
        color = colors.gutterText,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.End,
        modifier = Modifier.width(style.gutterWidth).padding(end = 6.dp),
    )
}

private fun buildHighlightedDiffLine(
    line: DiffLine,
    baseContentColor: Color,
    colors: DiffViewerColors,
    highlightSpans: List<HighlightSpan>,
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
        highlightSpans.forEach { span ->
            addStyle(
                style = highlightKindStyle(span.kind, colors),
                start = span.start + offset,
                end = span.end + offset,
            )
        }
    }
}

private fun computeDiffPresentationLines(
    diffInfo: EditDiffInfo,
    contextLines: Int,
    syntaxLanguage: SyntaxLanguage,
): List<DiffPresentationLine> {
    val diffLines = computeDiffLines(diffInfo = diffInfo, contextLines = contextLines)
    return diffLines.map { line ->
        val spans =
            if (line.type == DiffLineType.SKIPPED || line.content.isEmpty()) {
                emptyList()
            } else {
                computeHighlightSpans(content = line.content, language = syntaxLanguage)
            }
        DiffPresentationLine(
            line = line,
            highlightSpans = spans,
        )
    }
}

private fun computeHighlightSpans(
    content: String,
    language: SyntaxLanguage,
): List<HighlightSpan> {
    return PrismDiffHighlighter.highlight(
        content = content,
        language = language,
    )
}

private object PrismDiffHighlighter {
    private const val MAX_CACHE_ENTRIES = 256

    private val prism4j by lazy {
        Prism4j(DiffPrism4jGrammarLocator())
    }

    private val cache =
        object : LinkedHashMap<String, List<HighlightSpan>>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<HighlightSpan>>?): Boolean {
                return size > MAX_CACHE_ENTRIES
            }
        }

    fun highlight(
        content: String,
        language: SyntaxLanguage,
    ): List<HighlightSpan> {
        val grammarName = language.prismGrammarName ?: return emptyList()
        val cacheKey = "$grammarName\u0000$content"
        val cached = synchronized(cache) { cache[cacheKey] }

        val spans =
            cached ?: computeUncached(content = content, grammarName = grammarName).also { computed ->
                synchronized(cache) {
                    cache[cacheKey] = computed
                }
            }

        return spans
    }

    private fun computeUncached(
        content: String,
        grammarName: String,
    ): List<HighlightSpan> {
        val grammar = prism4j.grammar(grammarName) ?: return emptyList()
        return runCatching {
            val visitor = PrismHighlightVisitor()
            visitor.visit(prism4j.tokenize(content, grammar))
            visitor.spans
        }.getOrDefault(emptyList())
    }
}

private class PrismHighlightVisitor : AbsVisitor() {
    private val mutableSpans = mutableListOf<HighlightSpan>()
    private var cursor = 0

    val spans: List<HighlightSpan>
        get() = mutableSpans

    override fun visitText(text: Prism4j.Text) {
        cursor += text.literal().length
    }

    override fun visitSyntax(syntax: Prism4j.Syntax) {
        val start = cursor
        visit(syntax.children())
        val end = cursor

        if (end <= start) {
            return
        }

        tokenKind(
            tokenType = syntax.type(),
            alias = syntax.alias(),
        )?.let { kind ->
            mutableSpans +=
                HighlightSpan(
                    start = start,
                    end = end,
                    kind = kind,
                )
        }
    }
}

private fun tokenKind(
    tokenType: String?,
    alias: String?,
): HighlightKind? {
    val tokenDescriptor = listOfNotNull(tokenType, alias).joinToString(separator = " ").lowercase()

    return when {
        tokenDescriptor.containsAny(COMMENT_TOKEN_MARKERS) -> HighlightKind.COMMENT
        tokenDescriptor.containsAny(STRING_TOKEN_MARKERS) -> HighlightKind.STRING
        tokenDescriptor.containsAny(NUMBER_TOKEN_MARKERS) -> HighlightKind.NUMBER
        tokenDescriptor.containsAny(KEYWORD_TOKEN_MARKERS) -> HighlightKind.KEYWORD
        else -> null
    }
}

private fun highlightKindStyle(
    kind: HighlightKind,
    colors: DiffViewerColors,
): SpanStyle {
    return when (kind) {
        HighlightKind.COMMENT -> SpanStyle(color = colors.commentText)
        HighlightKind.STRING -> SpanStyle(color = colors.stringText)
        HighlightKind.NUMBER -> SpanStyle(color = colors.numberText)
        HighlightKind.KEYWORD -> SpanStyle(color = colors.keywordText)
    }
}

private fun String.containsAny(markers: Set<String>): Boolean {
    return markers.any { marker -> contains(marker) }
}

internal fun detectHighlightKindsForTest(
    content: String,
    path: String,
): Set<String> {
    val language = detectSyntaxLanguage(path)
    return computeHighlightSpans(content = content, language = language)
        .map { span -> span.kind.name }
        .toSet()
}

private fun detectSyntaxLanguage(path: String): SyntaxLanguage {
    val lowerPath = path.lowercase()
    if (lowerPath.endsWith("makefile")) {
        return SyntaxLanguage.MAKEFILE
    }

    val extension = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return EXTENSION_LANGUAGE_MAP[extension] ?: SyntaxLanguage.PLAIN
}

/**
 * Represents a single line in a diff.
 */
data class DiffLine(
    val type: DiffLineType,
    val content: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
    val hiddenUnchangedCount: Int? = null,
)

enum class DiffLineType {
    ADDED,
    REMOVED,
    CONTEXT,
    SKIPPED,
}

internal fun computeDiffLines(
    diffInfo: EditDiffInfo,
    contextLines: Int = DEFAULT_CONTEXT_LINES,
): List<DiffLine> {
    val oldLines = splitLines(diffInfo.oldString)
    val newLines = splitLines(diffInfo.newString)
    val completeDiff = buildCompleteDiff(oldLines = oldLines, newLines = newLines)
    return collapseToContextHunks(completeDiff, contextLines = contextLines)
}

private fun splitLines(text: String): List<String> {
    val normalizedText = normalizeLineEndings(text)
    return if (normalizedText.isEmpty()) {
        emptyList()
    } else {
        normalizedText.split('\n', ignoreCase = false, limit = Int.MAX_VALUE)
    }
}

private fun normalizeLineEndings(text: String): String {
    return text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
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

    while (cursor.oldIndex < oldLines.size) {
        lines +=
            DiffLine(
                type = DiffLineType.REMOVED,
                content = oldLines[cursor.oldIndex],
                oldLineNumber = cursor.oldIndex + 1,
            )
        cursor.oldIndex += 1
    }

    while (cursor.newIndex < newLines.size) {
        lines +=
            DiffLine(
                type = DiffLineType.ADDED,
                content = newLines[cursor.newIndex],
                newLineNumber = cursor.newIndex + 1,
            )
        cursor.newIndex += 1
    }
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
        content = "",
        hiddenUnchangedCount = count,
    )
}

private val EXTENSION_LANGUAGE_MAP =
    mapOf(
        "kt" to SyntaxLanguage.KOTLIN,
        "kts" to SyntaxLanguage.KOTLIN,
        "java" to SyntaxLanguage.JAVA,
        "js" to SyntaxLanguage.JAVASCRIPT,
        "jsx" to SyntaxLanguage.JAVASCRIPT,
        "ts" to SyntaxLanguage.JAVASCRIPT,
        "tsx" to SyntaxLanguage.JAVASCRIPT,
        "json" to SyntaxLanguage.JSON,
        "jsonl" to SyntaxLanguage.JSON,
        "md" to SyntaxLanguage.MARKDOWN,
        "markdown" to SyntaxLanguage.MARKDOWN,
        "html" to SyntaxLanguage.MARKUP,
        "xml" to SyntaxLanguage.MARKUP,
        "svg" to SyntaxLanguage.MARKUP,
        "py" to SyntaxLanguage.PYTHON,
        "go" to SyntaxLanguage.GO,
        "swift" to SyntaxLanguage.SWIFT,
        "cs" to SyntaxLanguage.CSHARP,
        "cpp" to SyntaxLanguage.CPP,
        "cc" to SyntaxLanguage.CPP,
        "cxx" to SyntaxLanguage.CPP,
        "c" to SyntaxLanguage.C,
        "h" to SyntaxLanguage.C,
        "css" to SyntaxLanguage.CSS,
        "scss" to SyntaxLanguage.CSS,
        "sass" to SyntaxLanguage.CSS,
        "sql" to SyntaxLanguage.SQL,
        "yml" to SyntaxLanguage.YAML,
        "yaml" to SyntaxLanguage.YAML,
    )

private val COMMENT_TOKEN_MARKERS = setOf("comment", "prolog", "doctype", "cdata")
private val STRING_TOKEN_MARKERS = setOf("string", "char", "attr-value", "url")
private val NUMBER_TOKEN_MARKERS = setOf("number", "boolean", "constant")
private val KEYWORD_TOKEN_MARKERS = setOf("keyword", "operator", "important", "atrule")
