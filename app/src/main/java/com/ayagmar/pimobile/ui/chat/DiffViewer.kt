@file:Suppress("TooManyFunctions", "MagicNumber")

package com.ayagmar.pimobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.ayagmar.pimobile.chat.EditDiffInfo

private const val COLLAPSED_DIFF_LINES = 50
private const val CONTEXT_LINES = 3

// Diff colors
private val ADDED_BACKGROUND = Color(0xFFE8F5E9) // Light green
private val REMOVED_BACKGROUND = Color(0xFFFFEBEE) // Light red
private val ADDED_TEXT = Color(0xFF2E7D32) // Dark green
private val REMOVED_TEXT = Color(0xFFC62828) // Dark red

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
    val diffLines = remember(diffInfo) { computeDiff(diffInfo) }
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
            // Header with file path and copy button
            DiffHeader(
                path = diffInfo.path,
                onCopyPath = { clipboardManager.setText(AnnotatedString(diffInfo.path)) },
            )

            // Diff content
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
            ) {
                items(displayLines) { line ->
                    DiffLineItem(line = line)
                }
            }

            // Expand/collapse button for large diffs
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
private fun DiffLineItem(line: DiffLine) {
    val backgroundColor =
        when (line.type) {
            DiffLineType.ADDED -> ADDED_BACKGROUND
            DiffLineType.REMOVED -> REMOVED_BACKGROUND
            DiffLineType.CONTEXT -> Color.Transparent
        }

    val textColor =
        when (line.type) {
            DiffLineType.ADDED -> ADDED_TEXT
            DiffLineType.REMOVED -> REMOVED_TEXT
            DiffLineType.CONTEXT -> MaterialTheme.colorScheme.onSurface
        }

    val prefix =
        when (line.type) {
            DiffLineType.ADDED -> "+"
            DiffLineType.REMOVED -> "-"
            DiffLineType.CONTEXT -> " "
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(backgroundColor)
                .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        SelectionContainer {
            Text(
                text =
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = textColor, fontFamily = FontFamily.Monospace)) {
                            append("$prefix${line.content}")
                        }
                    },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
}

/**
 * Computes a simple line-based diff between old and new strings.
 * Returns a list of DiffLine objects representing the unified diff.
 */
private fun computeDiff(diffInfo: EditDiffInfo): List<DiffLine> {
    val oldLines = diffInfo.oldString.lines()
    val newLines = diffInfo.newString.lines()

    // Simple diff: find common prefix and suffix, mark middle as changed
    val commonPrefixLength = findCommonPrefixLength(oldLines, newLines)
    val commonSuffixLength = findCommonSuffixLength(oldLines, newLines, commonPrefixLength)

    val result = mutableListOf<DiffLine>()

    // Add context lines before changes
    val contextStart = maxOf(0, commonPrefixLength - CONTEXT_LINES)
    for (i in contextStart until commonPrefixLength) {
        result.add(
            DiffLine(
                type = DiffLineType.CONTEXT,
                content = oldLines[i],
                oldLineNumber = i + 1,
                newLineNumber = i + 1,
            ),
        )
    }

    // Add removed lines
    val removedEnd = oldLines.size - commonSuffixLength
    for (i in commonPrefixLength until removedEnd) {
        result.add(
            DiffLine(
                type = DiffLineType.REMOVED,
                content = oldLines[i],
                oldLineNumber = i + 1,
            ),
        )
    }

    // Add added lines
    val addedEnd = newLines.size - commonSuffixLength
    for (i in commonPrefixLength until addedEnd) {
        result.add(
            DiffLine(
                type = DiffLineType.ADDED,
                content = newLines[i],
                newLineNumber = i + 1,
            ),
        )
    }

    // Add context lines after changes
    val contextEnd = minOf(oldLines.size, commonPrefixLength + (oldLines.size - commonSuffixLength) + CONTEXT_LINES)
    for (i in oldLines.size - commonSuffixLength until contextEnd) {
        result.add(
            DiffLine(
                type = DiffLineType.CONTEXT,
                content = oldLines[i],
                oldLineNumber = i + 1,
                newLineNumber = i + 1,
            ),
        )
    }

    return result
}

private fun findCommonPrefixLength(
    oldLines: List<String>,
    newLines: List<String>,
): Int {
    var i = 0
    while (i < oldLines.size && i < newLines.size && oldLines[i] == newLines[i]) {
        i++
    }
    return i
}

private fun findCommonSuffixLength(
    oldLines: List<String>,
    newLines: List<String>,
    prefixLength: Int,
): Int {
    var i = 0
    while (i < oldLines.size - prefixLength &&
        i < newLines.size - prefixLength &&
        oldLines[oldLines.size - 1 - i] == newLines[newLines.size - 1 - i]
    ) {
        i++
    }
    return i
}
