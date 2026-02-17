package com.ayagmar.pimobile.ui.chat

import com.ayagmar.pimobile.corerpc.SessionStats
import com.ayagmar.pimobile.sessions.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatScreenContextUsageLabelTest {
    @Test
    fun usesApproximateLabelWhenOnlyCumulativeTokenStatsExist() {
        val label =
            formatContextLabel(
                stats =
                    SessionStats(
                        inputTokens = 2700,
                        outputTokens = 0,
                        cacheReadTokens = 0,
                        cacheWriteTokens = 0,
                        totalCost = 0.0,
                        messageCount = 0,
                        userMessageCount = 0,
                        assistantMessageCount = 0,
                        toolResultCount = 0,
                        sessionPath = null,
                    ),
                currentModel = modelWithContextWindow(128_000),
            )

        assertEquals("Ctx ~2.7K/128.0K", label)
    }

    @Test
    fun usesExplicitContextFieldsWhenProvidedAndKeepsMinimalBadges() {
        val label =
            formatContextLabel(
                stats =
                    SessionStats(
                        inputTokens = 50000,
                        outputTokens = 10000,
                        cacheReadTokens = 0,
                        cacheWriteTokens = 0,
                        totalCost = 0.45,
                        messageCount = 0,
                        userMessageCount = 0,
                        assistantMessageCount = 0,
                        toolResultCount = 0,
                        sessionPath = null,
                        compactionCount = 2,
                        contextUsedTokens = 3072,
                        contextWindowTokens = 128000,
                        contextUsagePercent = 2,
                    ),
                currentModel = modelWithContextWindow(200_000),
            )

        assertEquals("Ctx 2% · 3.1K/128.0K · C2 · $0.450", label)
    }

    @Test
    fun usesPercentOnlyWhenOnlyPercentIsKnown() {
        val label =
            formatContextLabel(
                stats =
                    SessionStats(
                        inputTokens = 90000,
                        outputTokens = 20000,
                        cacheReadTokens = 0,
                        cacheWriteTokens = 0,
                        totalCost = 0.0,
                        messageCount = 0,
                        userMessageCount = 0,
                        assistantMessageCount = 0,
                        toolResultCount = 0,
                        sessionPath = null,
                        contextUsagePercent = 90,
                    ),
                currentModel = modelWithContextWindow(128_000),
            )

        assertEquals("Ctx 90%", label)
    }

    @Test
    fun usesExplicitWindowAsApproximateFallbackWhenUsedTokensMissing() {
        val label =
            formatContextLabel(
                stats =
                    SessionStats(
                        inputTokens = 3200,
                        outputTokens = 0,
                        cacheReadTokens = 0,
                        cacheWriteTokens = 0,
                        totalCost = 0.0,
                        messageCount = 0,
                        userMessageCount = 0,
                        assistantMessageCount = 0,
                        toolResultCount = 0,
                        sessionPath = null,
                        contextWindowTokens = 64000,
                    ),
                currentModel = null,
            )

        assertEquals("Ctx ~3.2K/64.0K", label)
    }

    private fun modelWithContextWindow(window: Int): ModelInfo {
        return ModelInfo(
            id = "m1",
            name = "Model",
            provider = "test",
            thinkingLevel = "off",
            contextWindow = window,
        )
    }

    private fun formatContextLabel(
        stats: SessionStats?,
        currentModel: ModelInfo?,
    ): String {
        val method =
            Class.forName(CHAT_SCREEN_FILE_CLASS)
                .getDeclaredMethod("formatContextUsageLabel", SessionStats::class.java, ModelInfo::class.java)
        method.isAccessible = true
        return method.invoke(null, stats, currentModel) as String
    }

    companion object {
        private const val CHAT_SCREEN_FILE_CLASS = "com.ayagmar.pimobile.ui.chat.ChatScreenKt"
    }
}
