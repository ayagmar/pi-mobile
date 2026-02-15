@file:Suppress("TooManyFunctions")

package com.ayagmar.pimobile.chat

import com.ayagmar.pimobile.corerpc.AssistantMessageEvent
import com.ayagmar.pimobile.corerpc.MessageEndEvent
import com.ayagmar.pimobile.corerpc.MessageUpdateEvent
import com.ayagmar.pimobile.corerpc.ToolExecutionStartEvent
import com.ayagmar.pimobile.sessions.SlashCommandInfo
import com.ayagmar.pimobile.sessions.TreeNavigationResult
import com.ayagmar.pimobile.testutil.FakeSessionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
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
            awaitInitialLoad(viewModel)

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
            awaitInitialLoad(viewModel)

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
            awaitInitialLoad(viewModel)

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

    @Test
    fun slashInputAutoOpensCommandPaletteAndUpdatesQuery() =
        runTest(dispatcher) {
            val controller = FakeSessionController()
            controller.availableCommands =
                listOf(
                    SlashCommandInfo(
                        name = "tree",
                        description = "Show tree",
                        source = "extension",
                        location = null,
                        path = null,
                    ),
                )

            val viewModel = ChatViewModel(sessionController = controller)
            dispatcher.scheduler.advanceUntilIdle()
            awaitInitialLoad(viewModel)

            viewModel.onInputTextChanged("/")
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isCommandPaletteVisible)
            assertTrue(viewModel.uiState.value.isCommandPaletteAutoOpened)
            assertEquals("", viewModel.uiState.value.commandsQuery)
            assertEquals(1, controller.getCommandsCallCount)

            viewModel.onInputTextChanged("/tr")
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isCommandPaletteVisible)
            assertEquals("tr", viewModel.uiState.value.commandsQuery)
            assertEquals(1, controller.getCommandsCallCount)
        }

    @Test
    fun slashPaletteDoesNotAutoOpenForRegularTextContexts() =
        runTest(dispatcher) {
            val controller = FakeSessionController()
            val viewModel = ChatViewModel(sessionController = controller)
            dispatcher.scheduler.advanceUntilIdle()
            awaitInitialLoad(viewModel)

            viewModel.onInputTextChanged("Please inspect /tmp/file.txt")
            dispatcher.scheduler.advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isCommandPaletteVisible)
            assertEquals(0, controller.getCommandsCallCount)

            viewModel.onInputTextChanged("/tmp/file.txt")
            dispatcher.scheduler.advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isCommandPaletteVisible)
            assertEquals(0, controller.getCommandsCallCount)
        }

    @Test
    fun selectingCommandReplacesTrailingSlashToken() =
        runTest(dispatcher) {
            val controller = FakeSessionController()
            val viewModel = ChatViewModel(sessionController = controller)
            dispatcher.scheduler.advanceUntilIdle()
            awaitInitialLoad(viewModel)

            viewModel.onInputTextChanged("/tr")
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.onCommandSelected(
                SlashCommandInfo(
                    name = "tree",
                    description = "Show tree",
                    source = "extension",
                    location = null,
                    path = null,
                ),
            )

            assertEquals("/tree ", viewModel.uiState.value.inputText)
            assertFalse(viewModel.uiState.value.isCommandPaletteVisible)
            assertFalse(viewModel.uiState.value.isCommandPaletteAutoOpened)
        }

    @Test
    fun loadingCommandsAddsBuiltinCommandEntriesWithExplicitSupport() =
        runTest(dispatcher) {
            val controller = FakeSessionController()
            controller.availableCommands =
                listOf(
                    SlashCommandInfo(
                        name = "fix-tests",
                        description = "Fix failing tests",
                        source = "prompt",
                        location = "project",
                        path = "/tmp/fix-tests.md",
                    ),
                )

            val viewModel = ChatViewModel(sessionController = controller)
            dispatcher.scheduler.advanceUntilIdle()
            awaitInitialLoad(viewModel)

            viewModel.showCommandPalette()
            dispatcher.scheduler.advanceUntilIdle()

            val commands = viewModel.uiState.value.commands
            assertTrue(commands.any { it.name == "fix-tests" && it.source == "prompt" })
            assertTrue(
                commands.any {
                    it.name == "settings" &&
                        it.source == ChatViewModel.COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED
                },
            )
            assertTrue(
                commands.any {
                    it.name == "hotkeys" &&
                        it.source == ChatViewModel.COMMAND_SOURCE_BUILTIN_UNSUPPORTED
                },
            )
        }

    @Test
    fun sendingInteractiveBuiltinShowsExplicitMessageWithoutRpcSend() =
        runTest(dispatcher) {
            val controller = FakeSessionController()
            val viewModel = ChatViewModel(sessionController = controller)
            dispatcher.scheduler.advanceUntilIdle()
            awaitInitialLoad(viewModel)

            viewModel.onInputTextChanged("/settings")
            viewModel.sendPrompt()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(0, controller.sendPromptCallCount)
            assertTrue(viewModel.uiState.value.errorMessage?.contains("Settings tab") == true)
        }

    @Test
    fun selectingBridgeBackedBuiltinTreeOpensTreeSheet() =
        runTest(dispatcher) {
            val controller = FakeSessionController()
            val viewModel = ChatViewModel(sessionController = controller)
            dispatcher.scheduler.advanceUntilIdle()
            awaitInitialLoad(viewModel)

            viewModel.onCommandSelected(
                SlashCommandInfo(
                    name = "tree",
                    description = "Open tree",
                    source = ChatViewModel.COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED,
                    location = null,
                    path = null,
                ),
            )
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isTreeSheetVisible)
        }

    @Test
    fun globalCollapseAndExpandAffectToolsAndReasoning() =
        runTest(dispatcher) {
            val controller = FakeSessionController()
            val viewModel = ChatViewModel(sessionController = controller)
            dispatcher.scheduler.advanceUntilIdle()
            awaitInitialLoad(viewModel)

            controller.emitEvent(
                ToolExecutionStartEvent(
                    type = "tool_execution_start",
                    toolCallId = "tool-1",
                    toolName = "bash",
                    args = buildJsonObject { put("command", "echo test") },
                ),
            )
            controller.emitEvent(
                thinkingUpdate(
                    eventType = "thinking_start",
                    messageTimestamp = "1733234567999",
                ),
            )
            controller.emitEvent(
                thinkingUpdate(
                    eventType = "thinking_delta",
                    delta = "x".repeat(250),
                    messageTimestamp = "1733234567999",
                ),
            )
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.expandAllToolAndReasoning()
            dispatcher.scheduler.advanceUntilIdle()

            val expandedTool = viewModel.uiState.value.timeline.filterIsInstance<ChatTimelineItem.Tool>().firstOrNull()
            val expandedAssistant = viewModel.singleAssistantItem()
            expandedTool?.let { tool -> assertFalse(tool.isCollapsed) }
            assertTrue(expandedAssistant.isThinkingExpanded)

            viewModel.collapseAllToolAndReasoning()
            dispatcher.scheduler.advanceUntilIdle()

            val collapsedTool = viewModel.uiState.value.timeline.filterIsInstance<ChatTimelineItem.Tool>().firstOrNull()
            val collapsedAssistant = viewModel.singleAssistantItem()
            collapsedTool?.let { tool -> assertTrue(tool.isCollapsed) }
            assertFalse(collapsedAssistant.isThinkingExpanded)
        }

    @Test
    fun streamingSteerAndFollowUpAreVisibleInPendingQueueInspectorState() =
        runTest(dispatcher) {
            val controller = FakeSessionController()
            val viewModel = ChatViewModel(sessionController = controller)
            dispatcher.scheduler.advanceUntilIdle()
            awaitInitialLoad(viewModel)

            controller.setStreaming(true)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.steer("Narrow scope")
            viewModel.followUp("Generate edge-case tests")
            dispatcher.scheduler.advanceUntilIdle()

            val queueItems = viewModel.uiState.value.pendingQueueItems
            assertEquals(2, queueItems.size)
            assertEquals(PendingQueueType.STEER, queueItems[0].type)
            assertEquals(PendingQueueType.FOLLOW_UP, queueItems[1].type)
        }

    @Test
    fun pendingQueueCanBeRemovedClearedAndResetsWhenStreamingStops() =
        runTest(dispatcher) {
            val controller = FakeSessionController()
            val viewModel = ChatViewModel(sessionController = controller)
            dispatcher.scheduler.advanceUntilIdle()
            awaitInitialLoad(viewModel)

            controller.setStreaming(true)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.steer("First")
            viewModel.followUp("Second")
            dispatcher.scheduler.advanceUntilIdle()

            val firstId = viewModel.uiState.value.pendingQueueItems.first().id
            viewModel.removePendingQueueItem(firstId)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.pendingQueueItems.size)

            viewModel.clearPendingQueueItems()
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(viewModel.uiState.value.pendingQueueItems.isEmpty())

            viewModel.steer("Third")
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.pendingQueueItems.size)

            controller.setStreaming(false)
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(viewModel.uiState.value.pendingQueueItems.isEmpty())
        }

    @Test
    fun initialHistoryLoadsWithWindowAndCanPageOlderMessages() =
        runTest(dispatcher) {
            val controller = FakeSessionController()
            controller.messagesPayload = historyWithUserMessages(count = 260)

            val viewModel = ChatViewModel(sessionController = controller)
            dispatcher.scheduler.advanceUntilIdle()
            awaitInitialLoad(viewModel)

            val initialState = viewModel.uiState.value
            assertEquals(120, initialState.timeline.size)
            assertTrue(initialState.hasOlderMessages)
            assertEquals(140, initialState.hiddenHistoryCount)

            viewModel.loadOlderMessages()
            dispatcher.scheduler.advanceUntilIdle()

            val secondWindow = viewModel.uiState.value
            assertEquals(240, secondWindow.timeline.size)
            assertTrue(secondWindow.hasOlderMessages)
            assertEquals(20, secondWindow.hiddenHistoryCount)

            viewModel.loadOlderMessages()
            dispatcher.scheduler.advanceUntilIdle()

            val fullWindow = viewModel.uiState.value
            assertEquals(260, fullWindow.timeline.size)
            assertFalse(fullWindow.hasOlderMessages)
            assertEquals(0, fullWindow.hiddenHistoryCount)
        }

    @Test
    fun historyLoadingUsesRecentWindowCapForVeryLargeSessions() =
        runTest(dispatcher) {
            val controller = FakeSessionController()
            controller.messagesPayload = historyWithUserMessages(count = 1_500)

            val viewModel = ChatViewModel(sessionController = controller)
            dispatcher.scheduler.advanceUntilIdle()
            awaitInitialLoad(viewModel)

            val initialState = viewModel.uiState.value
            assertEquals(120, initialState.timeline.size)
            assertTrue(initialState.hasOlderMessages)
            assertEquals(1_080, initialState.hiddenHistoryCount)

            repeat(9) {
                viewModel.loadOlderMessages()
                dispatcher.scheduler.advanceUntilIdle()
            }

            val cappedWindowState = viewModel.uiState.value
            assertEquals(1_200, cappedWindowState.timeline.size)
            assertFalse(cappedWindowState.hasOlderMessages)
            assertEquals(0, cappedWindowState.hiddenHistoryCount)
        }

    @Test
    fun jumpAndContinueUsesInPlaceTreeNavigationResult() =
        runTest(dispatcher) {
            val controller = FakeSessionController()
            controller.treeNavigationResult =
                Result.success(
                    TreeNavigationResult(
                        cancelled = false,
                        editorText = "retry this branch",
                        currentLeafId = "entry-42",
                        sessionPath = "/tmp/session.jsonl",
                    ),
                )
            val viewModel = ChatViewModel(sessionController = controller)
            dispatcher.scheduler.advanceUntilIdle()
            awaitInitialLoad(viewModel)

            viewModel.jumpAndContinueFromTreeEntry("entry-42")
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals("entry-42", controller.lastNavigatedEntryId)
            assertEquals("retry this branch", viewModel.uiState.value.inputText)
        }

    private fun ChatViewModel.assistantItems(): List<ChatTimelineItem.Assistant> =
        uiState.value.timeline.filterIsInstance<ChatTimelineItem.Assistant>()

    private fun ChatViewModel.singleAssistantItem(): ChatTimelineItem.Assistant {
        val items = assistantItems()
        assertEquals(1, items.size)
        return items.single()
    }

    private fun awaitInitialLoad(viewModel: ChatViewModel) {
        repeat(INITIAL_LOAD_WAIT_ATTEMPTS) {
            if (!viewModel.uiState.value.isLoading) {
                return
            }
            Thread.sleep(INITIAL_LOAD_WAIT_STEP_MS)
        }

        val state = viewModel.uiState.value
        error(
            "Timed out waiting for initial chat history load: " +
                "isLoading=${state.isLoading}, error=${state.errorMessage}, timeline=${state.timeline.size}",
        )
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

    private fun historyWithUserMessages(count: Int): JsonObject =
        buildJsonObject {
            put(
                "messages",
                buildJsonArray {
                    repeat(count) { index ->
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", "message-$index")
                            },
                        )
                    }
                },
            )
        }

    companion object {
        private const val INITIAL_LOAD_WAIT_ATTEMPTS = 200
        private const val INITIAL_LOAD_WAIT_STEP_MS = 5L
    }
}
