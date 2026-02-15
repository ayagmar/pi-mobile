@file:Suppress("TooManyFunctions")

package com.ayagmar.pimobile.chat

import com.ayagmar.pimobile.corenet.ConnectionState
import com.ayagmar.pimobile.corerpc.AssistantMessageEvent
import com.ayagmar.pimobile.corerpc.AvailableModel
import com.ayagmar.pimobile.corerpc.BashResult
import com.ayagmar.pimobile.corerpc.ImagePayload
import com.ayagmar.pimobile.corerpc.MessageEndEvent
import com.ayagmar.pimobile.corerpc.MessageUpdateEvent
import com.ayagmar.pimobile.corerpc.RpcIncomingMessage
import com.ayagmar.pimobile.corerpc.RpcResponse
import com.ayagmar.pimobile.corerpc.SessionStats
import com.ayagmar.pimobile.coresessions.SessionRecord
import com.ayagmar.pimobile.hosts.HostProfile
import com.ayagmar.pimobile.sessions.ForkableMessage
import com.ayagmar.pimobile.sessions.ModelInfo
import com.ayagmar.pimobile.sessions.SessionController
import com.ayagmar.pimobile.sessions.SessionTreeSnapshot
import com.ayagmar.pimobile.sessions.SlashCommandInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelThinkingExpansionTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun thinkingExpansionStatePersistsAcrossStreamingUpdatesWhenMessageKeyChanges() =
        runTest(dispatcher) {
            val controller = FakeSessionController()
            val viewModel = ChatViewModel(sessionController = controller)
            dispatcher.scheduler.advanceUntilIdle()

            val longThinking = "a".repeat(320)
            controller.emitEvent(
                thinkingUpdate(
                    eventType = "thinking_start",
                    messageTimestamp = null,
                ),
            )
            controller.emitEvent(
                thinkingUpdate(
                    eventType = "thinking_delta",
                    delta = longThinking,
                    messageTimestamp = null,
                ),
            )
            dispatcher.scheduler.advanceUntilIdle()

            val initial = viewModel.singleAssistantItem()
            assertEquals("assistant-stream-active-0", initial.id)
            assertFalse(initial.isThinkingExpanded)

            viewModel.toggleThinkingExpansion(initial.id)
            dispatcher.scheduler.advanceUntilIdle()

            controller.emitEvent(
                thinkingUpdate(
                    eventType = "thinking_delta",
                    delta = " more",
                    messageTimestamp = "1733234567890",
                ),
            )
            dispatcher.scheduler.advanceUntilIdle()

            val migrated = viewModel.assistantItems()
            assertEquals(1, migrated.size)
            val expanded = migrated.single()
            assertEquals("assistant-stream-1733234567890-0", expanded.id)
            assertTrue(expanded.isThinkingExpanded)
            assertEquals(longThinking + " more", expanded.thinking)

            viewModel.toggleThinkingExpansion(expanded.id)
            dispatcher.scheduler.advanceUntilIdle()

            controller.emitEvent(
                thinkingUpdate(
                    eventType = "thinking_delta",
                    delta = " tail",
                    messageTimestamp = "1733234567890",
                ),
            )
            dispatcher.scheduler.advanceUntilIdle()

            val collapsed = viewModel.singleAssistantItem()
            assertFalse(collapsed.isThinkingExpanded)
            assertEquals(longThinking + " more tail", collapsed.thinking)
        }

    @Test
    fun thinkingExpansionStateRemainsStableOnFinalStreamingUpdate() =
        runTest(dispatcher) {
            val controller = FakeSessionController()
            val viewModel = ChatViewModel(sessionController = controller)
            dispatcher.scheduler.advanceUntilIdle()

            val longThinking = "b".repeat(300)
            controller.emitEvent(
                thinkingUpdate(
                    eventType = "thinking_start",
                    messageTimestamp = "1733234567900",
                ),
            )
            controller.emitEvent(
                thinkingUpdate(
                    eventType = "thinking_delta",
                    delta = longThinking,
                    messageTimestamp = "1733234567900",
                ),
            )
            dispatcher.scheduler.advanceUntilIdle()

            val assistantBeforeFinal = viewModel.singleAssistantItem()
            viewModel.toggleThinkingExpansion(assistantBeforeFinal.id)
            dispatcher.scheduler.advanceUntilIdle()

            controller.emitEvent(
                textUpdate(
                    assistantType = "text_start",
                    messageTimestamp = "1733234567900",
                ),
            )
            controller.emitEvent(
                textUpdate(
                    assistantType = "text_delta",
                    delta = "hello",
                    messageTimestamp = "1733234567900",
                ),
            )
            controller.emitEvent(
                textUpdate(
                    assistantType = "text_end",
                    content = "hello world",
                    messageTimestamp = "1733234567900",
                ),
            )
            dispatcher.scheduler.advanceUntilIdle()

            val finalItem = viewModel.singleAssistantItem()
            assertTrue(finalItem.isThinkingExpanded)
            assertEquals("hello world", finalItem.text)
            assertFalse(finalItem.isStreaming)
            assertEquals(longThinking, finalItem.thinking)
        }

    @Test
    fun pendingAssistantDeltaIsFlushedWhenMessageEnds() =
        runTest(dispatcher) {
            val controller = FakeSessionController()
            val viewModel = ChatViewModel(sessionController = controller)
            dispatcher.scheduler.advanceUntilIdle()

            controller.emitEvent(
                textUpdate(
                    assistantType = "text_start",
                    messageTimestamp = "1733234567901",
                ),
            )
            controller.emitEvent(
                textUpdate(
                    assistantType = "text_delta",
                    delta = "Hello",
                    messageTimestamp = "1733234567901",
                ),
            )
            controller.emitEvent(
                textUpdate(
                    assistantType = "text_delta",
                    delta = " world",
                    messageTimestamp = "1733234567901",
                ),
            )
            controller.emitEvent(
                MessageEndEvent(
                    type = "message_end",
                    message =
                        buildJsonObject {
                            put("role", "assistant")
                        },
                ),
            )
            dispatcher.scheduler.advanceUntilIdle()

            val item = viewModel.singleAssistantItem()
            assertEquals("Hello world", item.text)
        }

    private fun ChatViewModel.assistantItems(): List<ChatTimelineItem.Assistant> =
        uiState.value.timeline.filterIsInstance<ChatTimelineItem.Assistant>()

    private fun ChatViewModel.singleAssistantItem(): ChatTimelineItem.Assistant {
        val items = assistantItems()
        assertEquals(1, items.size)
        return items.single()
    }

    private fun thinkingUpdate(
        eventType: String,
        delta: String? = null,
        messageTimestamp: String?,
    ): MessageUpdateEvent =
        MessageUpdateEvent(
            type = "message_update",
            message = messageTimestamp?.let(::messageWithTimestamp),
            assistantMessageEvent =
                AssistantMessageEvent(
                    type = eventType,
                    contentIndex = 0,
                    delta = delta,
                ),
        )

    private fun textUpdate(
        assistantType: String,
        delta: String? = null,
        content: String? = null,
        messageTimestamp: String,
    ): MessageUpdateEvent =
        MessageUpdateEvent(
            type = "message_update",
            message = messageWithTimestamp(messageTimestamp),
            assistantMessageEvent =
                AssistantMessageEvent(
                    type = assistantType,
                    contentIndex = 0,
                    delta = delta,
                    content = content,
                ),
        )

    private fun messageWithTimestamp(timestamp: String): JsonObject =
        buildJsonObject {
            put("timestamp", timestamp)
        }
}

private class FakeSessionController : SessionController {
    private val events = MutableSharedFlow<RpcIncomingMessage>(extraBufferCapacity = 16)

    override val rpcEvents: SharedFlow<RpcIncomingMessage> = events
    override val connectionState: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val isStreaming: StateFlow<Boolean> = MutableStateFlow(false)

    suspend fun emitEvent(event: RpcIncomingMessage) {
        events.emit(event)
    }

    override suspend fun resume(
        hostProfile: HostProfile,
        token: String,
        session: SessionRecord,
    ): Result<String?> = Result.success(null)

    override suspend fun getMessages(): Result<RpcResponse> =
        Result.success(
            RpcResponse(
                type = "response",
                command = "get_messages",
                success = true,
            ),
        )

    override suspend fun getState(): Result<RpcResponse> =
        Result.success(
            RpcResponse(
                type = "response",
                command = "get_state",
                success = true,
            ),
        )

    override suspend fun sendPrompt(
        message: String,
        images: List<ImagePayload>,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun abort(): Result<Unit> = Result.success(Unit)

    override suspend fun steer(message: String): Result<Unit> = Result.success(Unit)

    override suspend fun followUp(message: String): Result<Unit> = Result.success(Unit)

    override suspend fun renameSession(name: String): Result<String?> = Result.success(null)

    override suspend fun compactSession(): Result<String?> = Result.success(null)

    override suspend fun exportSession(): Result<String> = Result.success("/tmp/export.html")

    override suspend fun forkSessionFromEntryId(entryId: String): Result<String?> = Result.success(null)

    override suspend fun getForkMessages(): Result<List<ForkableMessage>> = Result.success(emptyList())

    override suspend fun getSessionTree(sessionPath: String?): Result<SessionTreeSnapshot> =
        Result.failure(IllegalStateException("Not used"))

    override suspend fun cycleModel(): Result<ModelInfo?> = Result.success(null)

    override suspend fun cycleThinkingLevel(): Result<String?> = Result.success(null)

    override suspend fun setThinkingLevel(level: String): Result<String?> = Result.success(level)

    override suspend fun getLastAssistantText(): Result<String?> = Result.success(null)

    override suspend fun abortRetry(): Result<Unit> = Result.success(Unit)

    override suspend fun sendExtensionUiResponse(
        requestId: String,
        value: String?,
        confirmed: Boolean?,
        cancelled: Boolean?,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun newSession(): Result<Unit> = Result.success(Unit)

    override suspend fun getCommands(): Result<List<SlashCommandInfo>> = Result.success(emptyList())

    override suspend fun executeBash(
        command: String,
        timeoutMs: Int?,
    ): Result<BashResult> =
        Result.success(
            BashResult(
                output = "",
                exitCode = 0,
                wasTruncated = false,
            ),
        )

    override suspend fun abortBash(): Result<Unit> = Result.success(Unit)

    override suspend fun getSessionStats(): Result<SessionStats> =
        Result.success(
            SessionStats(
                inputTokens = 0,
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
        )

    override suspend fun getAvailableModels(): Result<List<AvailableModel>> = Result.success(emptyList())

    override suspend fun setModel(
        provider: String,
        modelId: String,
    ): Result<ModelInfo?> = Result.success(null)

    override suspend fun setAutoCompaction(enabled: Boolean): Result<Unit> = Result.success(Unit)

    override suspend fun setAutoRetry(enabled: Boolean): Result<Unit> = Result.success(Unit)

    override suspend fun setSteeringMode(mode: String): Result<Unit> = Result.success(Unit)

    override suspend fun setFollowUpMode(mode: String): Result<Unit> = Result.success(Unit)
}
