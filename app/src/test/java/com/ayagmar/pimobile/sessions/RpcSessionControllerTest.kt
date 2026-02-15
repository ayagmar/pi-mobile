package com.ayagmar.pimobile.sessions

import com.ayagmar.pimobile.corerpc.AvailableModel
import com.ayagmar.pimobile.corerpc.BashResult
import com.ayagmar.pimobile.corerpc.SessionStats
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class RpcSessionControllerTest {
    @Test
    fun parseSessionStatsMapsCurrentAndLegacyFields() {
        val current =
            invokeParser<SessionStats>(
                functionName = "parseSessionStats",
                data =
                    buildJsonObject {
                        put(
                            "tokens",
                            buildJsonObject {
                                put("input", 101)
                                put("output", 202)
                                put("cacheRead", 5)
                                put("cacheWrite", 6)
                            },
                        )
                        put("cost", 0.75)
                        put("totalMessages", 9)
                        put("userMessages", 4)
                        put("assistantMessages", 5)
                        put("toolResults", 3)
                        put("sessionFile", "/tmp/current.session.jsonl")
                    },
            )
        assertCurrentStats(current)

        val legacy =
            invokeParser<SessionStats>(
                functionName = "parseSessionStats",
                data =
                    buildJsonObject {
                        put("inputTokens", 11)
                        put("outputTokens", 22)
                        put("cacheReadTokens", 1)
                        put("cacheWriteTokens", 2)
                        put("totalCost", 0.33)
                        put("messageCount", 7)
                        put("userMessageCount", 3)
                        put("assistantMessageCount", 4)
                        put("toolResultCount", 2)
                        put("sessionPath", "/tmp/legacy.session.jsonl")
                    },
            )
        assertLegacyStats(legacy)
    }

    @Test
    fun parseBashResultMapsCurrentAndLegacyFields() {
        val current =
            invokeParser<BashResult>(
                functionName = "parseBashResult",
                data =
                    buildJsonObject {
                        put("output", "current output")
                        put("exitCode", 0)
                        put("truncated", true)
                        put("fullOutputPath", "/tmp/current.log")
                    },
            )

        assertEquals("current output", current.output)
        assertEquals(0, current.exitCode)
        assertEquals(true, current.wasTruncated)
        assertEquals("/tmp/current.log", current.fullLogPath)

        val legacy =
            invokeParser<BashResult>(
                functionName = "parseBashResult",
                data =
                    buildJsonObject {
                        put("output", "legacy output")
                        put("exitCode", 1)
                        put("wasTruncated", true)
                        put("fullLogPath", "/tmp/legacy.log")
                    },
            )

        assertEquals("legacy output", legacy.output)
        assertEquals(1, legacy.exitCode)
        assertEquals(true, legacy.wasTruncated)
        assertEquals("/tmp/legacy.log", legacy.fullLogPath)
    }

    @Test
    fun parseAvailableModelsMapsCurrentAndLegacyFields() {
        val models =
            invokeParser<List<AvailableModel>>(
                functionName = "parseAvailableModels",
                data =
                    buildJsonObject {
                        put(
                            "models",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("id", "current-model")
                                        put("name", "Current Model")
                                        put("provider", "openai")
                                        put("contextWindow", 200000)
                                        put("maxTokens", 8192)
                                        put("reasoning", true)
                                        put(
                                            "cost",
                                            buildJsonObject {
                                                put("input", 0.003)
                                                put("output", 0.015)
                                            },
                                        )
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("id", "legacy-model")
                                        put("name", "Legacy Model")
                                        put("provider", "anthropic")
                                        put("contextWindow", 100000)
                                        put("maxOutputTokens", 4096)
                                        put("supportsThinking", false)
                                        put("inputCostPer1k", 0.001)
                                        put("outputCostPer1k", 0.005)
                                    },
                                )
                            },
                        )
                    },
            )

        assertEquals(2, models.size)

        val current = models[0]
        assertEquals("current-model", current.id)
        assertEquals(8192, current.maxOutputTokens)
        assertEquals(true, current.supportsThinking)
        assertEquals(0.003, current.inputCostPer1k)
        assertEquals(0.015, current.outputCostPer1k)

        val legacy = models[1]
        assertEquals("legacy-model", legacy.id)
        assertEquals(4096, legacy.maxOutputTokens)
        assertEquals(false, legacy.supportsThinking)
        assertEquals(0.001, legacy.inputCostPer1k)
        assertEquals(0.005, legacy.outputCostPer1k)
    }

    @Test
    fun parseModelInfoSupportsSetModelDirectPayload() {
        val model =
            invokeParser<ModelInfo>(
                functionName = "parseModelInfo",
                data =
                    buildJsonObject {
                        put("id", "gpt-4.1")
                        put("name", "GPT-4.1")
                        put("provider", "openai")
                    },
            )

        assertEquals("gpt-4.1", model.id)
        assertEquals("GPT-4.1", model.name)
        assertEquals("openai", model.provider)
        assertEquals("off", model.thinkingLevel)
    }

    @Test
    fun parseSessionTreeSnapshotMapsBridgePayload() {
        val tree =
            invokeParser<SessionTreeSnapshot>(
                functionName = "parseSessionTreeSnapshot",
                data =
                    buildJsonObject {
                        put("sessionPath", "/tmp/session-tree.jsonl")
                        put("rootIds", buildJsonArray { add(JsonPrimitive("m1")) })
                        put("currentLeafId", "m3")
                        put(
                            "entries",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("entryId", "m1")
                                        put("entryType", "message")
                                        put("role", "user")
                                        put("preview", "first")
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("entryId", "m2")
                                        put("parentId", "m1")
                                        put("entryType", "message")
                                        put("role", "assistant")
                                        put("timestamp", "2026-02-01T00:00:02.000Z")
                                        put("preview", "second")
                                    },
                                )
                            },
                        )
                    },
            )

        assertEquals("/tmp/session-tree.jsonl", tree.sessionPath)
        assertEquals(listOf("m1"), tree.rootIds)
        assertEquals("m3", tree.currentLeafId)
        assertEquals(2, tree.entries.size)
        assertEquals("m1", tree.entries[0].entryId)
        assertEquals(null, tree.entries[0].parentId)
        assertEquals("m2", tree.entries[1].entryId)
        assertEquals("m1", tree.entries[1].parentId)
    }

    @Test
    fun parseLastAssistantTextHandlesTextAndNull() {
        val withText =
            invokeParser<String?>(
                functionName = "parseLastAssistantText",
                data =
                    buildJsonObject {
                        put("text", "Assistant response")
                    },
            )

        assertEquals("Assistant response", withText)

        val withNull =
            invokeParser<String?>(
                functionName = "parseLastAssistantText",
                data =
                    buildJsonObject {
                        put("text", JsonNull)
                    },
            )

        assertEquals(null, withNull)
    }

    @Test
    fun parseForkableMessagesUsesTextFieldWithPreviewFallback() {
        val messages =
            invokeParser<List<ForkableMessage>>(
                functionName = "parseForkableMessages",
                data =
                    buildJsonObject {
                        put(
                            "messages",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("entryId", "m1")
                                        put("text", "text preview")
                                        put("timestamp", "1730000000")
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("entryId", "m2")
                                        put("preview", "legacy preview")
                                        put("timestamp", "1730000001")
                                    },
                                )
                            },
                        )
                    },
            )

        assertEquals(2, messages.size)
        assertEquals("m1", messages[0].entryId)
        assertEquals("text preview", messages[0].preview)
        assertEquals(1730000000L, messages[0].timestamp)
        assertEquals("m2", messages[1].entryId)
        assertEquals("legacy preview", messages[1].preview)
        assertEquals(1730000001L, messages[1].timestamp)
    }

    private fun assertCurrentStats(current: SessionStats) {
        assertEquals(101L, current.inputTokens)
        assertEquals(202L, current.outputTokens)
        assertEquals(5L, current.cacheReadTokens)
        assertEquals(6L, current.cacheWriteTokens)
        assertEquals(0.75, current.totalCost, 0.0001)
        assertEquals(9, current.messageCount)
        assertEquals(4, current.userMessageCount)
        assertEquals(5, current.assistantMessageCount)
        assertEquals(3, current.toolResultCount)
        assertEquals("/tmp/current.session.jsonl", current.sessionPath)
    }

    private fun assertLegacyStats(legacy: SessionStats) {
        assertEquals(11L, legacy.inputTokens)
        assertEquals(22L, legacy.outputTokens)
        assertEquals(1L, legacy.cacheReadTokens)
        assertEquals(2L, legacy.cacheWriteTokens)
        assertEquals(0.33, legacy.totalCost, 0.0001)
        assertEquals(7, legacy.messageCount)
        assertEquals(3, legacy.userMessageCount)
        assertEquals(4, legacy.assistantMessageCount)
        assertEquals(2, legacy.toolResultCount)
        assertEquals("/tmp/legacy.session.jsonl", legacy.sessionPath)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> invokeParser(
        functionName: String,
        data: JsonObject,
    ): T {
        val method = sessionControllerKtClass.getDeclaredMethod(functionName, JsonObject::class.java)
        method.isAccessible = true
        return method.invoke(null, data) as T
    }

    private companion object {
        val sessionControllerKtClass: Class<*> = Class.forName("com.ayagmar.pimobile.sessions.RpcSessionControllerKt")
    }
}
