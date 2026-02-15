@file:Suppress("TooManyFunctions")

package com.ayagmar.pimobile.ui.settings

import android.content.SharedPreferences
import com.ayagmar.pimobile.corenet.ConnectionState
import com.ayagmar.pimobile.corerpc.AvailableModel
import com.ayagmar.pimobile.corerpc.BashResult
import com.ayagmar.pimobile.corerpc.ImagePayload
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
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

class SettingsViewModelTest {
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
    fun setSteeringModeUpdatesStateOnSuccess() =
        runTest(dispatcher) {
            val controller = FakeSessionController()
            val viewModel = createViewModel(controller)

            dispatcher.scheduler.advanceUntilIdle()
            viewModel.setSteeringMode(SettingsViewModel.MODE_ONE_AT_A_TIME)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(SettingsViewModel.MODE_ONE_AT_A_TIME, viewModel.uiState.steeringMode)
            assertFalse(viewModel.uiState.isUpdatingSteeringMode)
            assertTrue(controller.lastSteeringMode == SettingsViewModel.MODE_ONE_AT_A_TIME)
        }

    @Test
    fun setFollowUpModeRollsBackOnFailure() =
        runTest(dispatcher) {
            val controller =
                FakeSessionController().apply {
                    followUpModeResult = Result.failure(IllegalStateException("rpc failed"))
                }
            val viewModel = createViewModel(controller)

            dispatcher.scheduler.advanceUntilIdle()
            viewModel.setFollowUpMode(SettingsViewModel.MODE_ONE_AT_A_TIME)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(SettingsViewModel.MODE_ALL, viewModel.uiState.followUpMode)
            assertFalse(viewModel.uiState.isUpdatingFollowUpMode)
            assertEquals("rpc failed", viewModel.uiState.errorMessage)
            assertEquals(SettingsViewModel.MODE_ONE_AT_A_TIME, controller.lastFollowUpMode)
        }

    private fun createViewModel(controller: FakeSessionController): SettingsViewModel {
        return SettingsViewModel(
            sessionController = controller,
            sharedPreferences = InMemorySharedPreferences(),
            appVersionOverride = "test",
        )
    }
}

private class FakeSessionController : SessionController {
    override val rpcEvents: SharedFlow<RpcIncomingMessage> = MutableSharedFlow()
    override val connectionState: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val isStreaming: StateFlow<Boolean> = MutableStateFlow(false)

    var steeringModeResult: Result<Unit> = Result.success(Unit)
    var followUpModeResult: Result<Unit> = Result.success(Unit)
    var lastSteeringMode: String? = null
    var lastFollowUpMode: String? = null

    override suspend fun resume(
        hostProfile: HostProfile,
        token: String,
        session: SessionRecord,
    ): Result<String?> = Result.success(null)

    override suspend fun getMessages(): Result<RpcResponse> =
        Result.success(RpcResponse(type = "response", command = "get_messages", success = true))

    override suspend fun getState(): Result<RpcResponse> =
        Result.success(RpcResponse(type = "response", command = "get_state", success = true))

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

    override suspend fun setSteeringMode(mode: String): Result<Unit> {
        lastSteeringMode = mode
        return steeringModeResult
    }

    override suspend fun setFollowUpMode(mode: String): Result<Unit> {
        lastFollowUpMode = mode
        return followUpModeResult
    }
}

private class InMemorySharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(
        key: String?,
        defValue: String?,
    ): String? = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = (values[key] as? MutableSet<String>) ?: defValues

    override fun getInt(
        key: String?,
        defValue: Int,
    ): Int = values[key] as? Int ?: defValue

    override fun getLong(
        key: String?,
        defValue: Long,
    ): Long = values[key] as? Long ?: defValue

    override fun getFloat(
        key: String?,
        defValue: Float,
    ): Float = values[key] as? Float ?: defValue

    override fun getBoolean(
        key: String?,
        defValue: Boolean,
    ): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor(values)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        // no-op for tests
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        // no-op for tests
    }

    private class Editor(
        private val values: MutableMap<String, Any?>,
    ) : SharedPreferences.Editor {
        private val staged = mutableMapOf<String, Any?>()
        private var clearAll = false

        override fun putString(
            key: String?,
            value: String?,
        ): SharedPreferences.Editor = apply { staged[key.orEmpty()] = value }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply { staged[key.orEmpty()] = values }

        override fun putInt(
            key: String?,
            value: Int,
        ): SharedPreferences.Editor = apply { staged[key.orEmpty()] = value }

        override fun putLong(
            key: String?,
            value: Long,
        ): SharedPreferences.Editor = apply { staged[key.orEmpty()] = value }

        override fun putFloat(
            key: String?,
            value: Float,
        ): SharedPreferences.Editor = apply { staged[key.orEmpty()] = value }

        override fun putBoolean(
            key: String?,
            value: Boolean,
        ): SharedPreferences.Editor = apply { staged[key.orEmpty()] = value }

        override fun remove(key: String?): SharedPreferences.Editor = apply { staged[key.orEmpty()] = null }

        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) {
                values.clear()
            }
            staged.forEach { (key, value) ->
                if (value == null) {
                    values.remove(key)
                } else {
                    values[key] = value
                }
            }
        }
    }
}
