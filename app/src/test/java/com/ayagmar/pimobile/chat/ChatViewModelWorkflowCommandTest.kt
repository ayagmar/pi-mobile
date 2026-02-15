package com.ayagmar.pimobile.chat

import com.ayagmar.pimobile.corerpc.ExtensionUiRequestEvent
import com.ayagmar.pimobile.sessions.SlashCommandInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelWorkflowCommandTest {
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
    fun loadingCommandsHidesInternalBridgeWorkflowCommands() =
        runTest(dispatcher) {
            val controller = FakeSessionController()
            controller.availableCommands =
                listOf(
                    SlashCommandInfo(
                        name = "pi-mobile-tree",
                        description = "Internal",
                        source = "extension",
                        location = null,
                        path = null,
                    ),
                    SlashCommandInfo(
                        name = "pi-mobile-open-stats",
                        description = "Internal",
                        source = "extension",
                        location = null,
                        path = null,
                    ),
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

            val commandNames = viewModel.uiState.value.commands.map { it.name }
            assertTrue(commandNames.contains("fix-tests"))
            assertFalse(commandNames.contains("pi-mobile-tree"))
            assertFalse(commandNames.contains("pi-mobile-open-stats"))
        }

    @Test
    fun selectingBridgeBackedBuiltinStatsInvokesInternalWorkflowCommand() =
        runTest(dispatcher) {
            val controller = FakeSessionController()
            controller.availableCommands =
                listOf(
                    SlashCommandInfo(
                        name = "pi-mobile-open-stats",
                        description = "Internal",
                        source = "extension",
                        location = null,
                        path = null,
                    ),
                )

            val viewModel = ChatViewModel(sessionController = controller)
            dispatcher.scheduler.advanceUntilIdle()
            awaitInitialLoad(viewModel)

            viewModel.onCommandSelected(
                SlashCommandInfo(
                    name = "stats",
                    description = "Open stats",
                    source = ChatViewModel.COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED,
                    location = null,
                    path = null,
                ),
            )
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, controller.getCommandsCallCount)
            assertEquals(1, controller.sendPromptCallCount)
            assertEquals("/pi-mobile-open-stats", controller.lastPromptMessage)
            assertFalse(viewModel.uiState.value.isStatsSheetVisible)
        }

    @Test
    fun selectingBridgeBackedBuiltinStatsFallsBackWhenInternalCommandUnavailable() =
        runTest(dispatcher) {
            val controller = FakeSessionController()
            val viewModel = ChatViewModel(sessionController = controller)
            dispatcher.scheduler.advanceUntilIdle()
            awaitInitialLoad(viewModel)

            viewModel.onCommandSelected(
                SlashCommandInfo(
                    name = "stats",
                    description = "Open stats",
                    source = ChatViewModel.COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED,
                    location = null,
                    path = null,
                ),
            )
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, controller.getCommandsCallCount)
            assertEquals(0, controller.sendPromptCallCount)
            assertTrue(viewModel.uiState.value.isStatsSheetVisible)
        }

    @Test
    fun internalWorkflowStatusActionCanOpenStatsSheet() =
        runTest(dispatcher) {
            val controller = FakeSessionController()
            val viewModel = ChatViewModel(sessionController = controller)
            dispatcher.scheduler.advanceUntilIdle()
            awaitInitialLoad(viewModel)

            controller.emitEvent(
                ExtensionUiRequestEvent(
                    type = "extension_ui_request",
                    id = "req-1",
                    method = "setStatus",
                    statusKey = "pi-mobile-workflow-action",
                    statusText = "{\"action\":\"open_stats\"}",
                ),
            )
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isStatsSheetVisible)
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

    private companion object {
        private const val INITIAL_LOAD_WAIT_ATTEMPTS = 200
        private const val INITIAL_LOAD_WAIT_STEP_MS = 5L
    }
}
