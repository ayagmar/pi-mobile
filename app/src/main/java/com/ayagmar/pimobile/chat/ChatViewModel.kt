package com.ayagmar.pimobile.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayagmar.pimobile.corenet.ConnectionState
import com.ayagmar.pimobile.corerpc.AgentEndEvent
import com.ayagmar.pimobile.corerpc.AssistantTextAssembler
import com.ayagmar.pimobile.corerpc.AssistantTextUpdate
import com.ayagmar.pimobile.corerpc.AutoCompactionEndEvent
import com.ayagmar.pimobile.corerpc.AutoCompactionStartEvent
import com.ayagmar.pimobile.corerpc.AutoRetryEndEvent
import com.ayagmar.pimobile.corerpc.AutoRetryStartEvent
import com.ayagmar.pimobile.corerpc.AvailableModel
import com.ayagmar.pimobile.corerpc.ExtensionErrorEvent
import com.ayagmar.pimobile.corerpc.ExtensionUiRequestEvent
import com.ayagmar.pimobile.corerpc.MessageEndEvent
import com.ayagmar.pimobile.corerpc.MessageStartEvent
import com.ayagmar.pimobile.corerpc.MessageUpdateEvent
import com.ayagmar.pimobile.corerpc.RpcResponse
import com.ayagmar.pimobile.corerpc.SessionStats
import com.ayagmar.pimobile.corerpc.ToolExecutionEndEvent
import com.ayagmar.pimobile.corerpc.ToolExecutionStartEvent
import com.ayagmar.pimobile.corerpc.ToolExecutionUpdateEvent
import com.ayagmar.pimobile.corerpc.TurnEndEvent
import com.ayagmar.pimobile.corerpc.TurnStartEvent
import com.ayagmar.pimobile.corerpc.UiUpdateThrottler
import com.ayagmar.pimobile.perf.PerformanceMetrics
import com.ayagmar.pimobile.sessions.ModelInfo
import com.ayagmar.pimobile.sessions.SessionController
import com.ayagmar.pimobile.sessions.SessionTreeSnapshot
import com.ayagmar.pimobile.sessions.SlashCommandInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

private const val HISTORY_WINDOW_MAX_ITEMS = 1_200

@Suppress("TooManyFunctions", "LargeClass")
class ChatViewModel(
    private val sessionController: SessionController,
    private val imageEncoder: ImageEncoder? = null,
) : ViewModel() {
    private val assembler = AssistantTextAssembler()
    private val _uiState = MutableStateFlow(ChatUiState(isLoading = true))
    private val assistantUpdateThrottler = UiUpdateThrottler<AssistantTextUpdate>(ASSISTANT_UPDATE_THROTTLE_MS)
    private val toolUpdateThrottlers = mutableMapOf<String, UiUpdateThrottler<ToolExecutionUpdateEvent>>()
    private val toolUpdateFlushJobs = mutableMapOf<String, Job>()
    private var assistantUpdateFlushJob: Job? = null
    private var fullTimeline: List<ChatTimelineItem> = emptyList()
    private var visibleTimelineSize: Int = 0
    private var historyWindowMessages: List<JsonObject> = emptyList()
    private var historyWindowAbsoluteOffset: Int = 0
    private var historyParsedStartIndex: Int = 0
    private val pendingLocalUserIds = ArrayDeque<String>()

    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        observeConnection()
        observeStreamingState()
        observeEvents()
        loadInitialMessages()
    }

    fun onInputTextChanged(text: String) {
        val slashQuery = extractSlashCommandQuery(text)
        var shouldLoadCommands = false

        _uiState.update { state ->
            if (slashQuery != null) {
                shouldLoadCommands = state.commands.isEmpty() && !state.isLoadingCommands
                state.copy(
                    inputText = text,
                    isCommandPaletteVisible = true,
                    commandsQuery = slashQuery,
                    isCommandPaletteAutoOpened = true,
                )
            } else {
                state.copy(
                    inputText = text,
                    isCommandPaletteVisible =
                        if (state.isCommandPaletteAutoOpened) {
                            false
                        } else {
                            state.isCommandPaletteVisible
                        },
                    commandsQuery = if (state.isCommandPaletteAutoOpened) "" else state.commandsQuery,
                    isCommandPaletteAutoOpened = false,
                )
            }
        }

        if (shouldLoadCommands) {
            loadCommands()
        }
    }

    fun sendPrompt() {
        val currentState = _uiState.value
        val message = currentState.inputText.trim()
        val pendingImages = currentState.pendingImages
        if (message.isEmpty() && pendingImages.isEmpty()) return

        val builtinCommand = message.extractBuiltinCommand()
        if (builtinCommand != null) {
            handleNonRpcBuiltinCommand(builtinCommand)
            return
        }

        // Record prompt send for TTFT tracking
        recordMetricsSafely { PerformanceMetrics.recordPromptSend() }
        hasRecordedFirstToken = false

        val optimisticUserId = "$LOCAL_USER_ITEM_PREFIX${UUID.randomUUID()}"
        upsertTimelineItem(
            ChatTimelineItem.User(
                id = optimisticUserId,
                text = message,
                imageCount = pendingImages.size,
                imageUris = pendingImages.map { it.uri },
            ),
        )
        pendingLocalUserIds.addLast(optimisticUserId)

        viewModelScope.launch {
            val imagePayloads =
                withContext(Dispatchers.Default) {
                    pendingImages.mapNotNull { pending ->
                        imageEncoder?.encodeToPayload(pending)
                    }
                }

            if (message.isEmpty() && imagePayloads.isEmpty()) {
                discardPendingLocalUserItem(optimisticUserId)
                _uiState.update {
                    it.copy(errorMessage = "Unable to attach image. Please try again.")
                }
                return@launch
            }

            _uiState.update { it.copy(inputText = "", pendingImages = emptyList(), errorMessage = null) }
            val result = sessionController.sendPrompt(message, imagePayloads)
            if (result.isFailure) {
                discardPendingLocalUserItem(optimisticUserId)
                _uiState.update {
                    it.copy(
                        inputText = currentState.inputText,
                        pendingImages = currentState.pendingImages,
                        errorMessage = result.exceptionOrNull()?.message,
                    )
                }
            }
        }
    }

    fun abort() {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            val result = sessionController.abort()
            if (result.isFailure) {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    fun steer(message: String) {
        val trimmedMessage = message.trim()
        if (trimmedMessage.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            val queueItemId = maybeTrackStreamingQueueItem(PendingQueueType.STEER, trimmedMessage)
            val result = sessionController.steer(trimmedMessage)
            if (result.isFailure) {
                queueItemId?.let(::removePendingQueueItem)
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    fun followUp(message: String) {
        val trimmedMessage = message.trim()
        if (trimmedMessage.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            val queueItemId = maybeTrackStreamingQueueItem(PendingQueueType.FOLLOW_UP, trimmedMessage)
            val result = sessionController.followUp(trimmedMessage)
            if (result.isFailure) {
                queueItemId?.let(::removePendingQueueItem)
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    fun cycleModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            val result = sessionController.cycleModel()
            if (result.isFailure) {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            } else {
                result.getOrNull()?.let { modelInfo ->
                    _uiState.update { it.copy(currentModel = modelInfo) }
                }
            }
        }
    }

    fun cycleThinkingLevel() {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            val result = sessionController.cycleThinkingLevel()
            if (result.isFailure) {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            } else {
                result.getOrNull()?.let { level ->
                    _uiState.update { it.copy(thinkingLevel = level) }
                }
            }
        }
    }

    fun setThinkingLevel(level: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            val result = sessionController.setThinkingLevel(level)
            if (result.isFailure) {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            } else {
                _uiState.update { it.copy(thinkingLevel = result.getOrNull() ?: level) }
            }
        }
    }

    fun abortRetry() {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            val result = sessionController.abortRetry()
            if (result.isFailure) {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    fun showCommandPalette() {
        _uiState.update {
            it.copy(
                isCommandPaletteVisible = true,
                commandsQuery = "",
                isCommandPaletteAutoOpened = false,
            )
        }
        loadCommands()
    }

    fun hideCommandPalette() {
        _uiState.update {
            it.copy(
                isCommandPaletteVisible = false,
                isCommandPaletteAutoOpened = false,
            )
        }
    }

    fun onCommandsQueryChanged(query: String) {
        _uiState.update { it.copy(commandsQuery = query) }
    }

    fun onCommandSelected(command: SlashCommandInfo) {
        when (command.source) {
            COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED,
            COMMAND_SOURCE_BUILTIN_UNSUPPORTED,
            -> handleNonRpcBuiltinCommand(command.name)

            else -> {
                val currentText = _uiState.value.inputText
                val newText = replaceTrailingSlashToken(currentText, command.name)
                _uiState.update {
                    it.copy(
                        inputText = newText,
                        isCommandPaletteVisible = false,
                        isCommandPaletteAutoOpened = false,
                    )
                }
            }
        }
    }

    private fun extractSlashCommandQuery(input: String): String? {
        val trimmed = input.trim()
        return trimmed
            .takeIf { token -> token.isNotEmpty() && token.none(Char::isWhitespace) }
            ?.let { token ->
                SLASH_COMMAND_TOKEN_REGEX.matchEntire(token)?.groupValues?.get(1)
            }
    }

    private fun replaceTrailingSlashToken(
        input: String,
        commandName: String,
    ): String {
        val trimmedInput = input.trimEnd()
        val trailingTokenStart = trimmedInput.lastIndexOfAny(charArrayOf(' ', '\n', '\t')).let { it + 1 }
        val trailingToken = trimmedInput.substring(trailingTokenStart)
        val canReplaceToken = SLASH_COMMAND_TOKEN_REGEX.matches(trailingToken)

        return if (canReplaceToken) {
            trimmedInput.substring(0, trailingTokenStart) + "/$commandName "
        } else if (trimmedInput.isEmpty()) {
            "/$commandName "
        } else {
            "$trimmedInput /$commandName "
        }
    }

    private fun handleNonRpcBuiltinCommand(commandName: String) {
        val normalized = commandName.lowercase()

        _uiState.update {
            it.copy(
                isCommandPaletteVisible = false,
                isCommandPaletteAutoOpened = false,
                commandsQuery = "",
            )
        }

        when (normalized) {
            BUILTIN_TREE_COMMAND -> showTreeSheet()
            BUILTIN_STATS_COMMAND -> {
                invokeInternalWorkflowCommand(INTERNAL_STATS_WORKFLOW_COMMAND) {
                    showStatsSheet()
                }
            }

            BUILTIN_SETTINGS_COMMAND -> {
                _uiState.update {
                    it.copy(errorMessage = "Use the Settings tab for /settings on mobile")
                }
            }

            BUILTIN_HOTKEYS_COMMAND -> {
                _uiState.update {
                    it.copy(errorMessage = "/hotkeys is not supported on mobile yet")
                }
            }

            else -> {
                _uiState.update {
                    it.copy(errorMessage = "/$normalized is interactive-only and unavailable via RPC prompt")
                }
            }
        }
    }

    private fun invokeInternalWorkflowCommand(
        commandName: String,
        onFailure: (() -> Unit)? = null,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }

            val commandsResult = sessionController.getCommands()
            val isCommandAvailable =
                commandsResult.getOrNull()
                    ?.any { command -> command.name.equals(commandName, ignoreCase = true) } == true

            if (!isCommandAvailable) {
                val message =
                    commandsResult.exceptionOrNull()?.message
                        ?: "Workflow command /$commandName is unavailable in this runtime"
                handleWorkflowCommandFailure(message, onFailure)
                return@launch
            }

            val result = sessionController.sendPrompt(message = "/$commandName")
            if (result.isFailure) {
                handleWorkflowCommandFailure(result.exceptionOrNull()?.message, onFailure)
            }
        }
    }

    private fun handleWorkflowCommandFailure(
        message: String?,
        onFailure: (() -> Unit)? = null,
    ) {
        if (onFailure != null) {
            onFailure()
            return
        }

        _uiState.update { it.copy(errorMessage = message ?: "Failed to run workflow command") }
    }

    private fun loadCommands() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingCommands = true) }
            val result = sessionController.getCommands()
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        commands = mergeRpcCommandsWithBuiltins(result.getOrNull().orEmpty()),
                        isLoadingCommands = false,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        commands = mergeRpcCommandsWithBuiltins(emptyList()),
                        isLoadingCommands = false,
                        errorMessage = result.exceptionOrNull()?.message,
                    )
                }
            }
        }
    }

    private fun mergeRpcCommandsWithBuiltins(rpcCommands: List<SlashCommandInfo>): List<SlashCommandInfo> {
        val visibleRpcCommands =
            rpcCommands.filterNot { command -> command.name.lowercase() in INTERNAL_HIDDEN_COMMAND_NAMES }
        if (visibleRpcCommands.isEmpty()) {
            return BUILTIN_COMMANDS
        }

        val knownNames = visibleRpcCommands.map { it.name.lowercase() }.toSet()
        val missingBuiltins = BUILTIN_COMMANDS.filterNot { it.name.lowercase() in knownNames }
        return visibleRpcCommands + missingBuiltins
    }

    private fun String.extractBuiltinCommand(): String? {
        val commandName =
            trim().substringBefore(' ')
                .takeIf { token -> token.startsWith('/') }
                ?.removePrefix("/")
                ?.trim()
                ?.lowercase()
                .orEmpty()

        return commandName.takeIf { name -> name.isNotBlank() && BUILTIN_COMMAND_NAMES.contains(name) }
    }

    fun toggleToolExpansion(itemId: String) {
        updateTimelineState { state ->
            ChatTimelineReducer.toggleToolExpansion(state, itemId)
        }
    }

    fun toggleDiffExpansion(itemId: String) {
        updateTimelineState { state ->
            ChatTimelineReducer.toggleDiffExpansion(state, itemId)
        }
    }

    private fun observeConnection() {
        viewModelScope.launch {
            sessionController.connectionState.collect { state ->
                val previousState = _uiState.value.connectionState
                val timelineEmpty = _uiState.value.timeline.isEmpty()
                _uiState.update { current ->
                    current.copy(connectionState = state)
                }
                // Reload messages when connection becomes active and timeline is empty
                if (state == ConnectionState.CONNECTED && previousState != ConnectionState.CONNECTED && timelineEmpty) {
                    loadInitialMessages()
                }
            }
        }
    }

    private fun observeStreamingState() {
        viewModelScope.launch {
            sessionController.isStreaming.collect { isStreaming ->
                _uiState.update { current ->
                    current.copy(
                        isStreaming = isStreaming,
                        pendingQueueItems = if (isStreaming) current.pendingQueueItems else emptyList(),
                    )
                }
            }
        }
    }

    private fun maybeTrackStreamingQueueItem(
        type: PendingQueueType,
        message: String,
    ): String? {
        val state = _uiState.value
        if (!state.isStreaming) return null

        val mode =
            when (type) {
                PendingQueueType.STEER -> state.steeringMode
                PendingQueueType.FOLLOW_UP -> state.followUpMode
            }

        val itemId = UUID.randomUUID().toString()
        val queueItem =
            PendingQueueItem(
                id = itemId,
                type = type,
                message = message,
                mode = mode,
            )

        _uiState.update { current ->
            current.copy(
                pendingQueueItems =
                    (current.pendingQueueItems + queueItem)
                        .takeLast(MAX_PENDING_QUEUE_ITEMS),
            )
        }

        return itemId
    }

    private inline fun recordMetricsSafely(record: () -> Unit) {
        runCatching(record)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun observeEvents() {
        // Observe session changes and reload timeline
        viewModelScope.launch {
            sessionController.sessionChanged.collect {
                // Reset state for new session
                hasRecordedFirstToken = false
                resetStreamingUpdateState()
                fullTimeline = emptyList()
                visibleTimelineSize = 0
                pendingLocalUserIds.clear()
                resetHistoryWindow()
                loadInitialMessages()
            }
        }

        viewModelScope.launch {
            sessionController.rpcEvents.collect { event ->
                when (event) {
                    is MessageUpdateEvent -> handleMessageUpdate(event)
                    is MessageStartEvent -> handleMessageStart()
                    is MessageEndEvent -> {
                        flushPendingAssistantUpdate(force = true)
                        handleMessageEnd(event)
                    }
                    is TurnStartEvent -> handleTurnStart()
                    is TurnEndEvent -> {
                        flushAllPendingStreamUpdates(force = true)
                        handleTurnEnd()
                    }
                    is ToolExecutionStartEvent -> {
                        flushPendingToolUpdate(event.toolCallId, force = true)
                        handleToolStart(event)
                    }
                    is ToolExecutionUpdateEvent -> handleToolUpdate(event)
                    is ToolExecutionEndEvent -> {
                        flushPendingToolUpdate(event.toolCallId, force = true)
                        handleToolEnd(event)
                        clearToolUpdateThrottle(event.toolCallId)
                    }
                    is ExtensionUiRequestEvent -> handleExtensionUiRequest(event)
                    is ExtensionErrorEvent -> {
                        flushAllPendingStreamUpdates(force = true)
                        handleExtensionError(event)
                    }
                    is AutoCompactionStartEvent -> handleCompactionStart(event)
                    is AutoCompactionEndEvent -> handleCompactionEnd(event)
                    is AutoRetryStartEvent -> handleRetryStart(event)
                    is AutoRetryEndEvent -> handleRetryEnd(event)
                    is AgentEndEvent -> flushAllPendingStreamUpdates(force = true)
                    else -> Unit
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun handleExtensionUiRequest(event: ExtensionUiRequestEvent) {
        when (event.method) {
            "select" -> showSelectDialog(event)
            "confirm" -> showConfirmDialog(event)
            "input" -> showInputDialog(event)
            "editor" -> showEditorDialog(event)
            "notify" -> addNotification(event)
            "setStatus" -> updateExtensionStatus(event)
            "setWidget" -> updateExtensionWidget(event)
            "setTitle" -> updateExtensionTitle(event)
            "set_editor_text" -> updateEditorText(event)
            else -> Unit
        }
    }

    private fun showSelectDialog(event: ExtensionUiRequestEvent) {
        _uiState.update {
            it.copy(
                activeExtensionRequest =
                    ExtensionUiRequest.Select(
                        requestId = event.id,
                        title = event.title ?: "Select",
                        options = event.options ?: emptyList(),
                    ),
            )
        }
    }

    private fun showConfirmDialog(event: ExtensionUiRequestEvent) {
        _uiState.update {
            it.copy(
                activeExtensionRequest =
                    ExtensionUiRequest.Confirm(
                        requestId = event.id,
                        title = event.title ?: "Confirm",
                        message = event.message ?: "",
                    ),
            )
        }
    }

    private fun showInputDialog(event: ExtensionUiRequestEvent) {
        _uiState.update {
            it.copy(
                activeExtensionRequest =
                    ExtensionUiRequest.Input(
                        requestId = event.id,
                        title = event.title ?: "Input",
                        placeholder = event.placeholder,
                    ),
            )
        }
    }

    private fun showEditorDialog(event: ExtensionUiRequestEvent) {
        _uiState.update {
            it.copy(
                activeExtensionRequest =
                    ExtensionUiRequest.Editor(
                        requestId = event.id,
                        title = event.title ?: "Editor",
                        prefill = event.prefill ?: "",
                    ),
            )
        }
    }

    private fun addNotification(event: ExtensionUiRequestEvent) {
        appendNotification(
            message = event.message.orEmpty().stripAnsi(),
            type = event.notifyType ?: "info",
        )
    }

    private fun updateExtensionStatus(event: ExtensionUiRequestEvent) {
        val key = event.statusKey ?: "default"
        val text = event.statusText?.stripAnsi()

        if (key == INTERNAL_WORKFLOW_STATUS_KEY) {
            if (text != null) {
                handleInternalWorkflowStatus(text)
            }
            return
        }

        // Ignore non-workflow status messages to avoid UI clutter/noise.
    }

    private fun handleInternalWorkflowStatus(payloadText: String) {
        val action =
            runCatching {
                Json.parseToJsonElement(payloadText).jsonObject.stringField("action")
            }.getOrNull()

        when (action) {
            INTERNAL_WORKFLOW_ACTION_OPEN_STATS -> showStatsSheet()
            else -> Unit
        }
    }

    private fun updateExtensionWidget(event: ExtensionUiRequestEvent) {
        val key = event.widgetKey ?: "default"
        val lines = event.widgetLines?.map { it.stripAnsi() }
        _uiState.update { state ->
            val newWidgets = state.extensionWidgets.toMutableMap()
            if (lines == null) {
                newWidgets.remove(key)
            } else {
                newWidgets[key] =
                    ExtensionWidget(
                        lines = lines,
                        placement = event.widgetPlacement ?: "aboveEditor",
                    )
            }
            state.copy(extensionWidgets = newWidgets)
        }
    }

    private fun updateExtensionTitle(event: ExtensionUiRequestEvent) {
        event.title?.let { title ->
            _uiState.update { it.copy(extensionTitle = title.stripAnsi()) }
        }
    }

    private fun updateEditorText(event: ExtensionUiRequestEvent) {
        event.text?.let { text ->
            _uiState.update { it.copy(inputText = text) }
        }
    }

    private fun handleMessageStart() {
        // Silently track message start - no UI notification to reduce spam
    }

    private fun handleMessageEnd(event: MessageEndEvent) {
        val message = event.message
        val role = message?.stringField("role") ?: "assistant"

        // Add user messages to timeline
        if (role == "user" && message != null) {
            val content = message["content"]
            val text = extractUserText(content)
            val imageCount = extractUserImageCount(content)
            val entryId = message.stringField("entryId") ?: UUID.randomUUID().toString()
            val userItem =
                ChatTimelineItem.User(
                    id = "user-$entryId",
                    text = text,
                    imageCount = imageCount,
                )
            replacePendingUserItemOrUpsert(userItem)
        }
    }

    private fun handleTurnStart() {
        // Silently track turn start - no UI notification to reduce spam
    }

    private fun handleTurnEnd() {
        // Silently track turn end - no UI notification to reduce spam
    }

    private fun handleExtensionError(event: ExtensionErrorEvent) {
        val extension = firstNonBlank(event.extensionPath, event.path, "unknown-extension")
        val sourceEvent = firstNonBlank(event.event, event.extensionEvent, "unknown-event")
        val error = firstNonBlank(event.error, event.message, "Unknown extension error")
        addSystemNotification("Extension error [$extension:$sourceEvent] $error", "error")
    }

    private fun handleCompactionStart(event: AutoCompactionStartEvent) {
        val message =
            when (event.reason) {
                "threshold" -> "Compacting context (approaching limit)..."
                "overflow" -> "Compacting context (overflow recovery)..."
                else -> "Compacting context..."
            }
        addSystemNotification(message, "info")
    }

    private fun handleCompactionEnd(event: AutoCompactionEndEvent) {
        val message =
            when {
                event.aborted -> "Compaction aborted"
                event.willRetry -> "Compaction complete, retrying..."
                else -> "Context compacted successfully"
            }
        val type = if (event.aborted) "warning" else "info"
        addSystemNotification(message, type)
    }

    @Suppress("MagicNumber")
    private fun handleRetryStart(event: AutoRetryStartEvent) {
        _uiState.update { it.copy(isRetrying = true) }
        val message = "Retrying (${event.attempt}/${event.maxAttempts}) in ${event.delayMs / 1000}s..."
        addSystemNotification(message, "warning")
    }

    private fun handleRetryEnd(event: AutoRetryEndEvent) {
        _uiState.update { it.copy(isRetrying = false) }
        val message =
            if (event.success) {
                "Retry successful (attempt ${event.attempt})"
            } else {
                "Max retries exceeded: ${event.finalError ?: "Unknown error"}"
            }
        val type = if (event.success) "info" else "error"
        addSystemNotification(message, type)
    }

    private fun addSystemNotification(
        message: String,
        type: String,
    ) {
        appendNotification(message = message, type = type)
    }

    private fun appendNotification(
        message: String,
        type: String,
    ) {
        _uiState.update { state ->
            val nextNotifications =
                (state.notifications + ExtensionNotification(message = message, type = type))
                    .takeLast(MAX_NOTIFICATIONS)
            state.copy(notifications = nextNotifications)
        }
    }

    private fun firstNonBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
    }

    fun sendExtensionUiResponse(
        requestId: String,
        value: String? = null,
        confirmed: Boolean? = null,
        cancelled: Boolean = false,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(activeExtensionRequest = null) }
            val result =
                sessionController.sendExtensionUiResponse(
                    requestId = requestId,
                    value = value,
                    confirmed = confirmed,
                    cancelled = cancelled,
                )
            if (result.isFailure) {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    fun dismissExtensionRequest() {
        _uiState.value.activeExtensionRequest?.let { request ->
            sendExtensionUiResponse(
                requestId = request.requestId,
                cancelled = true,
            )
        }
    }

    fun clearNotification(index: Int) {
        _uiState.update { state ->
            val newNotifications = state.notifications.toMutableList()
            if (index in newNotifications.indices) {
                newNotifications.removeAt(index)
            }
            state.copy(notifications = newNotifications)
        }
    }

    fun removePendingQueueItem(itemId: String) {
        _uiState.update { state ->
            state.copy(
                pendingQueueItems =
                    state.pendingQueueItems.filterNot { item ->
                        item.id == itemId
                    },
            )
        }
    }

    fun clearPendingQueueItems() {
        _uiState.update { it.copy(pendingQueueItems = emptyList()) }
    }

    fun loadOlderMessages() {
        when {
            visibleTimelineSize < fullTimeline.size -> {
                visibleTimelineSize = minOf(visibleTimelineSize + TIMELINE_PAGE_SIZE, fullTimeline.size)
                publishVisibleTimeline()
            }

            historyParsedStartIndex > 0 && historyWindowMessages.isNotEmpty() -> {
                loadOlderHistoryChunk()
            }
        }
    }

    private fun loadOlderHistoryChunk() {
        val nextStartIndex = (historyParsedStartIndex - TIMELINE_PAGE_SIZE).coerceAtLeast(0)
        val olderHistoryItems =
            parseHistoryItems(
                messages = historyWindowMessages,
                absoluteIndexOffset = historyWindowAbsoluteOffset,
                startIndex = nextStartIndex,
                endExclusive = historyParsedStartIndex,
            )

        historyParsedStartIndex = nextStartIndex

        if (olderHistoryItems.isEmpty()) {
            publishVisibleTimeline()
        } else {
            val existingHistoryItems = fullTimeline.filter { item -> item.id.startsWith(HISTORY_ITEM_PREFIX) }
            val mergedHistory = olderHistoryItems + existingHistoryItems
            val mergedTimeline = mergeHistoryWithRealtimeTimeline(mergedHistory)

            fullTimeline =
                ChatTimelineReducer.limitTimeline(
                    timeline = mergedTimeline,
                    maxTimelineItems = MAX_TIMELINE_ITEMS,
                )
            visibleTimelineSize = minOf(visibleTimelineSize + olderHistoryItems.size, fullTimeline.size)
            publishVisibleTimeline()
        }
    }

    private fun loadInitialMessages() {
        initialLoadJob?.cancel()
        initialLoadJob =
            viewModelScope.launch(Dispatchers.IO) {
                val messagesResult = sessionController.getMessages()
                val stateResult = sessionController.getState()

                if (messagesResult.isSuccess) {
                    recordMetricsSafely { PerformanceMetrics.recordFirstMessagesRendered() }
                }

                val stateData = stateResult.getOrNull()?.data
                val metadata =
                    InitialLoadMetadata(
                        modelInfo = stateData?.let { parseModelInfo(it) },
                        thinkingLevel = stateData?.stringField("thinkingLevel"),
                        isStreaming = stateData?.booleanField("isStreaming") ?: false,
                        steeringMode = stateData.deliveryModeField("steeringMode", "steering_mode"),
                        followUpMode = stateData.deliveryModeField("followUpMode", "follow_up_mode"),
                    )

                _uiState.update { state ->
                    if (messagesResult.isFailure) {
                        buildInitialLoadFailureState(
                            state = state,
                            messagesResult = messagesResult,
                            metadata = metadata,
                        )
                    } else {
                        buildInitialLoadSuccessState(
                            state = state,
                            messagesData = messagesResult.getOrNull()?.data,
                            metadata = metadata,
                        )
                    }
                }
            }
    }

    private fun buildInitialLoadFailureState(
        state: ChatUiState,
        messagesResult: Result<RpcResponse>,
        metadata: InitialLoadMetadata,
    ): ChatUiState {
        fullTimeline = emptyList()
        visibleTimelineSize = 0
        pendingLocalUserIds.clear()
        resetHistoryWindow()

        return state.copy(
            isLoading = false,
            errorMessage = messagesResult.exceptionOrNull()?.message,
            timeline = emptyList(),
            hasOlderMessages = false,
            hiddenHistoryCount = 0,
            currentModel = metadata.modelInfo,
            thinkingLevel = metadata.thinkingLevel,
            isStreaming = metadata.isStreaming,
            steeringMode = metadata.steeringMode,
            followUpMode = metadata.followUpMode,
        )
    }

    private fun buildInitialLoadSuccessState(
        state: ChatUiState,
        messagesData: JsonObject?,
        metadata: InitialLoadMetadata,
    ): ChatUiState {
        val historyWindow = extractHistoryMessageWindow(messagesData)
        historyWindowMessages = historyWindow.messages
        historyWindowAbsoluteOffset = historyWindow.absoluteOffset
        historyParsedStartIndex = (historyWindowMessages.size - INITIAL_TIMELINE_SIZE).coerceAtLeast(0)

        val historyTimeline =
            parseHistoryItems(
                messages = historyWindowMessages,
                absoluteIndexOffset = historyWindowAbsoluteOffset,
                startIndex = historyParsedStartIndex,
            )

        val mergedTimeline =
            if (state.isLoading) {
                mergeHistoryWithRealtimeTimeline(historyTimeline)
            } else {
                historyTimeline
            }

        setInitialTimeline(mergedTimeline)

        return state.copy(
            isLoading = false,
            errorMessage = null,
            timeline = visibleTimeline(),
            hasOlderMessages = hasOlderMessages(),
            hiddenHistoryCount = hiddenHistoryCount(),
            currentModel = metadata.modelInfo,
            thinkingLevel = metadata.thinkingLevel,
            isStreaming = metadata.isStreaming,
            steeringMode = metadata.steeringMode,
            followUpMode = metadata.followUpMode,
        )
    }

    private fun resetHistoryWindow() {
        historyWindowMessages = emptyList()
        historyWindowAbsoluteOffset = 0
        historyParsedStartIndex = 0
    }

    private var hasRecordedFirstToken = false
    private var initialLoadJob: Job? = null

    private fun handleMessageUpdate(event: MessageUpdateEvent) {
        // Record first token received for TTFT tracking
        if (!hasRecordedFirstToken) {
            recordMetricsSafely { PerformanceMetrics.recordFirstToken() }
            hasRecordedFirstToken = true
        }

        val assistantEventType = event.assistantMessageEvent?.type
        when (assistantEventType) {
            "error" -> {
                flushPendingAssistantUpdate(force = true)
                val assistantEvent = event.assistantMessageEvent
                val reason =
                    assistantEvent?.partial?.stringField("reason")
                        ?: event.message?.stringField("stopReason")
                val message = if (reason.isNullOrBlank()) "Assistant run failed" else "Assistant run failed ($reason)"
                addSystemNotification(message, "error")
            }

            "done" -> flushPendingAssistantUpdate(force = true)

            else -> {
                val update = assembler.apply(event)
                if (update != null) {
                    val isHighFrequencyDelta =
                        assistantEventType == "text_delta" ||
                            assistantEventType == "thinking_delta"

                    if (isHighFrequencyDelta) {
                        assistantUpdateThrottler.offer(update)?.let(::applyAssistantUpdate)
                            ?: scheduleAssistantUpdateFlush()
                    } else {
                        flushPendingAssistantUpdate(force = true)
                        applyAssistantUpdate(update)
                    }
                }
            }
        }
    }

    private fun applyAssistantUpdate(update: AssistantTextUpdate) {
        val itemId = "assistant-stream-${update.messageKey}-${update.contentIndex}"
        val nextItem =
            ChatTimelineItem.Assistant(
                id = itemId,
                text = update.text,
                thinking = update.thinking,
                isThinkingComplete = update.isThinkingComplete,
                isStreaming = !update.isFinal,
            )

        upsertTimelineItem(nextItem)
    }

    fun toggleThinkingExpansion(itemId: String) {
        updateTimelineState { state ->
            ChatTimelineReducer.toggleThinkingExpansion(state, itemId)
        }
    }

    fun toggleToolArgumentsExpansion(itemId: String) {
        _uiState.update { state ->
            ChatTimelineReducer.toggleToolArgumentsExpansion(state, itemId)
        }
    }

    // Bash dialog functions
    fun showBashDialog() {
        _uiState.update {
            it.copy(
                isBashDialogVisible = true,
                bashCommand = "",
                bashOutput = "",
                bashExitCode = null,
                isBashExecuting = false,
                bashWasTruncated = false,
                bashFullLogPath = null,
            )
        }
    }

    fun hideBashDialog() {
        _uiState.update { it.copy(isBashDialogVisible = false) }
    }

    fun onBashCommandChanged(command: String) {
        _uiState.update { it.copy(bashCommand = command) }
    }

    fun executeBash() {
        val command = _uiState.value.bashCommand.trim()
        if (command.isEmpty()) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBashExecuting = true,
                    bashOutput = "Executing...\n",
                    bashExitCode = null,
                    bashWasTruncated = false,
                    bashFullLogPath = null,
                )
            }

            val result = sessionController.executeBash(command)

            _uiState.update { state ->
                if (result.isSuccess) {
                    val bashResult = result.getOrNull()!!
                    // Add to history if not already present
                    val newHistory =
                        if (command in state.bashHistory) {
                            state.bashHistory
                        } else {
                            (listOf(command) + state.bashHistory).take(BASH_HISTORY_SIZE)
                        }
                    state.copy(
                        isBashExecuting = false,
                        bashOutput = bashResult.output,
                        bashExitCode = bashResult.exitCode,
                        bashWasTruncated = bashResult.wasTruncated,
                        bashFullLogPath = bashResult.fullLogPath,
                        bashHistory = newHistory,
                    )
                } else {
                    state.copy(
                        isBashExecuting = false,
                        bashOutput = "Error: ${result.exceptionOrNull()?.message ?: "Unknown error"}",
                        bashExitCode = -1,
                    )
                }
            }
        }
    }

    fun abortBash() {
        viewModelScope.launch {
            val result = sessionController.abortBash()
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        isBashExecuting = false,
                        bashOutput = it.bashOutput + "\n--- Aborted ---",
                    )
                }
            }
        }
    }

    fun selectBashHistoryItem(command: String) {
        _uiState.update { it.copy(bashCommand = command) }
    }

    // Session stats functions
    fun showStatsSheet() {
        _uiState.update { it.copy(isStatsSheetVisible = true) }
        loadSessionStats()
    }

    fun hideStatsSheet() {
        _uiState.update { it.copy(isStatsSheetVisible = false) }
    }

    fun refreshSessionStats() {
        loadSessionStats()
    }

    private fun loadSessionStats() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingStats = true) }
            val result = sessionController.getSessionStats()
            _uiState.update { state ->
                if (result.isSuccess) {
                    state.copy(
                        sessionStats = result.getOrNull(),
                        isLoadingStats = false,
                    )
                } else {
                    state.copy(
                        isLoadingStats = false,
                        errorMessage = result.exceptionOrNull()?.message,
                    )
                }
            }
        }
    }

    // Model picker functions
    fun showModelPicker() {
        _uiState.update { it.copy(isModelPickerVisible = true, modelsQuery = "") }
        loadAvailableModels()
    }

    fun hideModelPicker() {
        _uiState.update { it.copy(isModelPickerVisible = false) }
    }

    fun onModelsQueryChanged(query: String) {
        _uiState.update { it.copy(modelsQuery = query) }
    }

    fun selectModel(model: AvailableModel) {
        viewModelScope.launch {
            _uiState.update { it.copy(isModelPickerVisible = false) }
            val result = sessionController.setModel(model.provider, model.id)
            if (result.isSuccess) {
                result.getOrNull()?.let { modelInfo ->
                    _uiState.update { it.copy(currentModel = modelInfo) }
                }
            } else {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    private fun loadAvailableModels() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingModels = true) }
            val result = sessionController.getAvailableModels()
            _uiState.update { state ->
                if (result.isSuccess) {
                    state.copy(
                        availableModels = result.getOrNull() ?: emptyList(),
                        isLoadingModels = false,
                    )
                } else {
                    state.copy(
                        isLoadingModels = false,
                        errorMessage = result.exceptionOrNull()?.message,
                    )
                }
            }
        }
    }

    fun showTreeSheet() {
        _uiState.update { it.copy(isTreeSheetVisible = true) }
        loadSessionTree()
    }

    fun hideTreeSheet() {
        _uiState.update { it.copy(isTreeSheetVisible = false) }
    }

    fun setTreeFilter(filter: String) {
        _uiState.update { it.copy(treeFilter = filter) }
        if (_uiState.value.isTreeSheetVisible) {
            loadSessionTree()
        }
    }

    fun forkFromTreeEntry(entryId: String) {
        viewModelScope.launch {
            val result = sessionController.forkSessionFromEntryId(entryId)
            if (result.isSuccess) {
                hideTreeSheet()
            } else {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    fun jumpAndContinueFromTreeEntry(entryId: String) {
        viewModelScope.launch {
            val result = sessionController.navigateTreeToEntry(entryId)
            if (result.isSuccess) {
                val navigation = result.getOrNull() ?: return@launch
                if (navigation.cancelled) {
                    _uiState.update {
                        it.copy(
                            isTreeSheetVisible = false,
                            errorMessage = "Tree navigation was cancelled",
                        )
                    }
                    return@launch
                }

                _uiState.update { state ->
                    val updatedTree =
                        state.sessionTree?.let { snapshot ->
                            if (navigation.sessionPath == null || navigation.sessionPath == snapshot.sessionPath) {
                                snapshot.copy(currentLeafId = navigation.currentLeafId)
                            } else {
                                snapshot
                            }
                        }

                    state.copy(
                        isTreeSheetVisible = false,
                        inputText = navigation.editorText.orEmpty(),
                        sessionTree = updatedTree,
                    )
                }
                loadInitialMessages()
            } else {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    private fun loadSessionTree() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingTree = true) }

            val stateResult = sessionController.getState()
            if (stateResult.isFailure) {
                _uiState.update {
                    it.copy(
                        isLoadingTree = false,
                        treeErrorMessage = stateResult.exceptionOrNull()?.message ?: "Failed to load session state",
                    )
                }
                return@launch
            }

            val sessionPath = stateResult.getOrNull()?.data?.stringField("sessionFile")
            if (sessionPath.isNullOrBlank()) {
                _uiState.update {
                    it.copy(
                        isLoadingTree = false,
                        treeErrorMessage = "No active session path available",
                    )
                }
                return@launch
            }

            val filter = _uiState.value.treeFilter
            val result = sessionController.getSessionTree(sessionPath = sessionPath, filter = filter)
            _uiState.update { state ->
                if (result.isSuccess) {
                    state.copy(
                        sessionTree = result.getOrNull(),
                        isLoadingTree = false,
                        treeErrorMessage = null,
                    )
                } else {
                    state.copy(
                        isLoadingTree = false,
                        treeErrorMessage = result.exceptionOrNull()?.message ?: "Failed to load session tree",
                    )
                }
            }
        }
    }

    private fun handleToolStart(event: ToolExecutionStartEvent) {
        val arguments = extractToolArguments(event.args)
        val editDiff = if (event.toolName == "edit") extractEditDiff(event.args) else null

        val nextItem =
            ChatTimelineItem.Tool(
                id = "tool-${event.toolCallId}",
                toolName = event.toolName,
                output = "Running…",
                isCollapsed = true,
                isStreaming = true,
                isError = false,
                arguments = arguments,
                editDiff = editDiff,
            )

        upsertTimelineItem(nextItem)
    }

    private fun handleToolUpdate(event: ToolExecutionUpdateEvent) {
        val throttler =
            toolUpdateThrottlers.getOrPut(event.toolCallId) {
                UiUpdateThrottler(TOOL_UPDATE_THROTTLE_MS)
            }

        throttler.offer(event)?.let(::applyToolUpdate)
            ?: scheduleToolUpdateFlush(event.toolCallId)
    }

    private fun applyToolUpdate(event: ToolExecutionUpdateEvent) {
        val output = extractToolOutput(event.partialResult)
        val itemId = "tool-${event.toolCallId}"
        val isCollapsed = output.length > TOOL_COLLAPSE_THRESHOLD
        val existingTool = _uiState.value.timeline.find { it.id == itemId } as? ChatTimelineItem.Tool

        val nextItem =
            ChatTimelineItem.Tool(
                id = itemId,
                toolName = event.toolName,
                output = output,
                isCollapsed = isCollapsed,
                isStreaming = true,
                isError = false,
                arguments = existingTool?.arguments ?: emptyMap(),
                editDiff = existingTool?.editDiff,
            )

        upsertTimelineItem(nextItem)
    }

    private fun handleToolEnd(event: ToolExecutionEndEvent) {
        val output = extractToolOutput(event.result)
        val itemId = "tool-${event.toolCallId}"
        val isCollapsed = output.length > TOOL_COLLAPSE_THRESHOLD
        val existingTool = _uiState.value.timeline.find { it.id == itemId } as? ChatTimelineItem.Tool

        val nextItem =
            ChatTimelineItem.Tool(
                id = itemId,
                toolName = event.toolName,
                output = output,
                isCollapsed = isCollapsed,
                isStreaming = false,
                isError = event.isError,
                arguments = existingTool?.arguments ?: emptyMap(),
                editDiff = existingTool?.editDiff,
            )

        upsertTimelineItem(nextItem)
    }

    private fun scheduleAssistantUpdateFlush() {
        if (assistantUpdateFlushJob?.isActive == true) return
        assistantUpdateFlushJob =
            viewModelScope.launch {
                delay(ASSISTANT_UPDATE_THROTTLE_MS)
                flushPendingAssistantUpdate(force = true)
            }
    }

    private fun flushPendingAssistantUpdate(force: Boolean) {
        val update =
            if (force) {
                assistantUpdateThrottler.flushPending()
            } else {
                assistantUpdateThrottler.drainReady()
            }

        if (update != null) {
            applyAssistantUpdate(update)
        }

        if (!assistantUpdateThrottler.hasPending()) {
            assistantUpdateFlushJob?.cancel()
            assistantUpdateFlushJob = null
        }
    }

    private fun scheduleToolUpdateFlush(toolCallId: String) {
        val existingJob = toolUpdateFlushJobs[toolCallId]
        if (existingJob?.isActive == true) return

        toolUpdateFlushJobs[toolCallId] =
            viewModelScope.launch {
                delay(TOOL_UPDATE_THROTTLE_MS)
                flushPendingToolUpdate(toolCallId = toolCallId, force = true)
            }
    }

    private fun flushPendingToolUpdate(
        toolCallId: String,
        force: Boolean,
    ) {
        val throttler = toolUpdateThrottlers[toolCallId] ?: return
        val update =
            if (force) {
                throttler.flushPending()
            } else {
                throttler.drainReady()
            }

        if (update != null) {
            applyToolUpdate(update)
        }

        if (!throttler.hasPending()) {
            toolUpdateFlushJobs.remove(toolCallId)?.cancel()
        }
    }

    private fun clearToolUpdateThrottle(toolCallId: String) {
        toolUpdateFlushJobs.remove(toolCallId)?.cancel()
        toolUpdateThrottlers.remove(toolCallId)
    }

    private fun flushAllPendingStreamUpdates(force: Boolean) {
        flushPendingAssistantUpdate(force = force)
        toolUpdateThrottlers.keys.toList().forEach { toolCallId ->
            flushPendingToolUpdate(toolCallId = toolCallId, force = force)
        }
    }

    private fun resetStreamingUpdateState() {
        assistantUpdateFlushJob?.cancel()
        assistantUpdateFlushJob = null
        assistantUpdateThrottler.reset()

        toolUpdateFlushJobs.values.forEach { it.cancel() }
        toolUpdateFlushJobs.clear()
        toolUpdateThrottlers.values.forEach { throttler -> throttler.reset() }
        toolUpdateThrottlers.clear()
    }

    private fun upsertTimelineItem(item: ChatTimelineItem) {
        val timelineState = ChatUiState(timeline = fullTimeline)
        fullTimeline =
            ChatTimelineReducer.upsertTimelineItem(
                state = timelineState,
                item = item,
                maxTimelineItems = MAX_TIMELINE_ITEMS,
            ).timeline

        if (visibleTimelineSize == 0) {
            visibleTimelineSize = minOf(fullTimeline.size, INITIAL_TIMELINE_SIZE)
        }

        publishVisibleTimeline()
    }

    private fun replacePendingUserItemOrUpsert(userItem: ChatTimelineItem.User) {
        val pendingIndex = consumeNextPendingLocalUserIndex() ?: findMatchingPendingUserIndex(userItem)

        if (pendingIndex == null) {
            upsertTimelineItem(userItem)
            return
        }

        val pendingItem = fullTimeline[pendingIndex] as ChatTimelineItem.User
        val mergedUserItem =
            userItem.copy(
                imageCount = maxOf(userItem.imageCount, pendingItem.imageCount),
                imageUris = userItem.imageUris.ifEmpty { pendingItem.imageUris },
            )

        fullTimeline =
            fullTimeline.toMutableList().also { timeline ->
                timeline[pendingIndex] = mergedUserItem
            }

        publishVisibleTimeline()
    }

    private fun consumeNextPendingLocalUserIndex(): Int? {
        while (pendingLocalUserIds.isNotEmpty()) {
            val pendingId = pendingLocalUserIds.removeFirst()
            val index = fullTimeline.indexOfFirst { it.id == pendingId }
            if (index >= 0) {
                return index
            }
        }

        return null
    }

    private fun findMatchingPendingUserIndex(userItem: ChatTimelineItem.User): Int? {
        val fallbackIndex =
            fullTimeline.indexOfLast { item ->
                item is ChatTimelineItem.User &&
                    item.id.startsWith(LOCAL_USER_ITEM_PREFIX) &&
                    item.text == userItem.text &&
                    item.imageCount >= userItem.imageCount
            }

        if (fallbackIndex < 0) {
            return null
        }

        val pendingItemId = fullTimeline[fallbackIndex].id
        pendingLocalUserIds.remove(pendingItemId)
        return fallbackIndex
    }

    private fun discardPendingLocalUserItem(itemId: String) {
        pendingLocalUserIds.remove(itemId)
        removeTimelineItemById(itemId)
    }

    private fun removeTimelineItemById(itemId: String) {
        val existingIndex = fullTimeline.indexOfFirst { it.id == itemId }
        if (existingIndex < 0) return

        fullTimeline =
            fullTimeline.toMutableList().also { timeline ->
                timeline.removeAt(existingIndex)
            }

        if (visibleTimelineSize > fullTimeline.size) {
            visibleTimelineSize = fullTimeline.size
        }

        publishVisibleTimeline()
    }

    private fun setInitialTimeline(history: List<ChatTimelineItem>) {
        fullTimeline =
            ChatTimelineReducer.limitTimeline(
                timeline = history,
                maxTimelineItems = MAX_TIMELINE_ITEMS,
            )
        visibleTimelineSize = minOf(fullTimeline.size, INITIAL_TIMELINE_SIZE)
    }

    private fun mergeHistoryWithRealtimeTimeline(history: List<ChatTimelineItem>): List<ChatTimelineItem> {
        val realtimeItems = fullTimeline.filterNot { item -> item.id.startsWith(HISTORY_ITEM_PREFIX) }
        return if (realtimeItems.isEmpty()) {
            history
        } else {
            history + realtimeItems
        }
    }

    private fun updateTimelineState(transform: (ChatUiState) -> ChatUiState) {
        val timelineState = ChatUiState(timeline = fullTimeline)
        fullTimeline = transform(timelineState).timeline
        publishVisibleTimeline()
    }

    private fun publishVisibleTimeline() {
        val visible = visibleTimeline()
        val activeToolIds =
            fullTimeline
                .filterIsInstance<ChatTimelineItem.Tool>()
                .mapTo(mutableSetOf()) { tool -> tool.id }

        _uiState.update { state ->
            state.copy(
                timeline = visible,
                hasOlderMessages = hasOlderMessages(),
                hiddenHistoryCount = hiddenHistoryCount(),
                expandedToolArguments = state.expandedToolArguments.intersect(activeToolIds),
            )
        }
    }

    private fun visibleTimeline(): List<ChatTimelineItem> {
        if (fullTimeline.isEmpty()) {
            return emptyList()
        }

        val visibleCount = visibleTimelineSize.coerceIn(0, fullTimeline.size)
        return fullTimeline.takeLast(visibleCount)
    }

    private fun hasOlderMessages(): Boolean {
        return historyParsedStartIndex > 0 || fullTimeline.size > visibleTimelineSize
    }

    private fun hiddenHistoryCount(): Int {
        val hiddenLoadedItems = (fullTimeline.size - visibleTimelineSize).coerceAtLeast(0)
        return hiddenLoadedItems + historyParsedStartIndex
    }

    fun addImage(pendingImage: PendingImage) {
        if (pendingImage.sizeBytes > ImageEncoder.MAX_IMAGE_SIZE_BYTES) {
            _uiState.update { it.copy(errorMessage = "Image too large (max 5MB)") }
            return
        }
        _uiState.update { state ->
            state.copy(pendingImages = state.pendingImages + pendingImage)
        }
    }

    fun removeImage(index: Int) {
        _uiState.update { state ->
            state.copy(
                pendingImages = state.pendingImages.filterIndexed { i, _ -> i != index },
            )
        }
    }

    override fun onCleared() {
        initialLoadJob?.cancel()
        resetStreamingUpdateState()
        pendingLocalUserIds.clear()
        super.onCleared()
    }

    companion object {
        const val TREE_FILTER_DEFAULT = "default"
        const val TREE_FILTER_ALL = "all"
        const val TREE_FILTER_NO_TOOLS = "no-tools"
        const val TREE_FILTER_USER_ONLY = "user-only"
        const val TREE_FILTER_LABELED_ONLY = "labeled-only"

        const val COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED = "builtin-bridge-backed"
        const val COMMAND_SOURCE_BUILTIN_UNSUPPORTED = "builtin-unsupported"

        const val DELIVERY_MODE_ALL = "all"
        const val DELIVERY_MODE_ONE_AT_A_TIME = "one-at-a-time"

        private const val BUILTIN_SETTINGS_COMMAND = "settings"
        private const val BUILTIN_TREE_COMMAND = "tree"
        private const val BUILTIN_STATS_COMMAND = "stats"
        private const val BUILTIN_HOTKEYS_COMMAND = "hotkeys"

        private const val INTERNAL_TREE_NAVIGATION_COMMAND = "pi-mobile-tree"
        private const val INTERNAL_STATS_WORKFLOW_COMMAND = "pi-mobile-open-stats"
        private const val INTERNAL_WORKFLOW_STATUS_KEY = "pi-mobile-workflow-action"
        private const val INTERNAL_WORKFLOW_ACTION_OPEN_STATS = "open_stats"

        private val BUILTIN_COMMANDS =
            listOf(
                SlashCommandInfo(
                    name = BUILTIN_SETTINGS_COMMAND,
                    description = "Open mobile settings UI (interactive-only in TUI)",
                    source = COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED,
                    location = null,
                    path = null,
                ),
                SlashCommandInfo(
                    name = BUILTIN_TREE_COMMAND,
                    description = "Open session tree sheet (interactive-only in TUI)",
                    source = COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED,
                    location = null,
                    path = null,
                ),
                SlashCommandInfo(
                    name = BUILTIN_STATS_COMMAND,
                    description = "Open session stats sheet (interactive-only in TUI)",
                    source = COMMAND_SOURCE_BUILTIN_BRIDGE_BACKED,
                    location = null,
                    path = null,
                ),
                SlashCommandInfo(
                    name = BUILTIN_HOTKEYS_COMMAND,
                    description = "Not available on mobile yet",
                    source = COMMAND_SOURCE_BUILTIN_UNSUPPORTED,
                    location = null,
                    path = null,
                ),
            )

        private val BUILTIN_COMMAND_NAMES = BUILTIN_COMMANDS.map { it.name }.toSet()
        private val INTERNAL_HIDDEN_COMMAND_NAMES =
            setOf(
                INTERNAL_TREE_NAVIGATION_COMMAND,
                INTERNAL_STATS_WORKFLOW_COMMAND,
            )

        private val SLASH_COMMAND_TOKEN_REGEX = Regex("^/([a-zA-Z0-9:_-]*)$")

        private const val HISTORY_ITEM_PREFIX = "history-"
        private const val LOCAL_USER_ITEM_PREFIX = "local-user-"
        private const val ASSISTANT_UPDATE_THROTTLE_MS = 80L
        private const val TOOL_UPDATE_THROTTLE_MS = 100L
        private const val TOOL_COLLAPSE_THRESHOLD = 400
        private const val MAX_TIMELINE_ITEMS = HISTORY_WINDOW_MAX_ITEMS
        private const val INITIAL_TIMELINE_SIZE = 120
        private const val TIMELINE_PAGE_SIZE = 120
        private const val BASH_HISTORY_SIZE = 10
        private const val MAX_NOTIFICATIONS = 6
        private const val MAX_PENDING_QUEUE_ITEMS = 20
    }
}

data class ChatUiState(
    val isLoading: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val isStreaming: Boolean = false,
    val isRetrying: Boolean = false,
    val timeline: List<ChatTimelineItem> = emptyList(),
    val hasOlderMessages: Boolean = false,
    val hiddenHistoryCount: Int = 0,
    val inputText: String = "",
    val errorMessage: String? = null,
    val currentModel: ModelInfo? = null,
    val thinkingLevel: String? = null,
    val activeExtensionRequest: ExtensionUiRequest? = null,
    val notifications: List<ExtensionNotification> = emptyList(),
    val extensionWidgets: Map<String, ExtensionWidget> = emptyMap(),
    val extensionTitle: String? = null,
    val isCommandPaletteVisible: Boolean = false,
    val isCommandPaletteAutoOpened: Boolean = false,
    val commands: List<SlashCommandInfo> = emptyList(),
    val commandsQuery: String = "",
    val isLoadingCommands: Boolean = false,
    val steeringMode: String = ChatViewModel.DELIVERY_MODE_ALL,
    val followUpMode: String = ChatViewModel.DELIVERY_MODE_ALL,
    val pendingQueueItems: List<PendingQueueItem> = emptyList(),
    // Bash dialog state
    val isBashDialogVisible: Boolean = false,
    val bashCommand: String = "",
    val bashOutput: String = "",
    val bashExitCode: Int? = null,
    val isBashExecuting: Boolean = false,
    val bashWasTruncated: Boolean = false,
    val bashFullLogPath: String? = null,
    val bashHistory: List<String> = emptyList(),
    // Tool argument expansion state (per tool ID)
    val expandedToolArguments: Set<String> = emptySet(),
    // Session stats state
    val isStatsSheetVisible: Boolean = false,
    val sessionStats: SessionStats? = null,
    val isLoadingStats: Boolean = false,
    // Model picker state
    val isModelPickerVisible: Boolean = false,
    val availableModels: List<AvailableModel> = emptyList(),
    val modelsQuery: String = "",
    val isLoadingModels: Boolean = false,
    // Session tree state
    val isTreeSheetVisible: Boolean = false,
    val treeFilter: String = ChatViewModel.TREE_FILTER_DEFAULT,
    val sessionTree: SessionTreeSnapshot? = null,
    val isLoadingTree: Boolean = false,
    val treeErrorMessage: String? = null,
    // Image attachments
    val pendingImages: List<PendingImage> = emptyList(),
)

data class PendingImage(
    val uri: String,
    val mimeType: String,
    val sizeBytes: Long,
    val displayName: String?,
)

data class PendingQueueItem(
    val id: String,
    val type: PendingQueueType,
    val message: String,
    val mode: String,
)

enum class PendingQueueType {
    STEER,
    FOLLOW_UP,
}

data class ExtensionNotification(
    val message: String,
    val type: String,
)

data class ExtensionWidget(
    val lines: List<String>,
    val placement: String,
)

sealed interface ExtensionUiRequest {
    val requestId: String

    data class Select(
        override val requestId: String,
        val title: String,
        val options: List<String>,
    ) : ExtensionUiRequest

    data class Confirm(
        override val requestId: String,
        val title: String,
        val message: String,
    ) : ExtensionUiRequest

    data class Input(
        override val requestId: String,
        val title: String,
        val placeholder: String?,
    ) : ExtensionUiRequest

    data class Editor(
        override val requestId: String,
        val title: String,
        val prefill: String,
    ) : ExtensionUiRequest
}

sealed interface ChatTimelineItem {
    val id: String

    data class User(
        override val id: String,
        val text: String,
        val imageCount: Int = 0,
        val imageUris: List<String> = emptyList(),
    ) : ChatTimelineItem

    data class Assistant(
        override val id: String,
        val text: String,
        val thinking: String? = null,
        val isThinkingExpanded: Boolean = false,
        val isThinkingComplete: Boolean = false,
        val isStreaming: Boolean,
    ) : ChatTimelineItem

    data class Tool(
        override val id: String,
        val toolName: String,
        val output: String,
        val isCollapsed: Boolean,
        val isStreaming: Boolean,
        val isError: Boolean,
        val arguments: Map<String, String> = emptyMap(),
        val editDiff: EditDiffInfo? = null,
        val isDiffExpanded: Boolean = false,
    ) : ChatTimelineItem
}

/**
 * Information about a file edit for diff display.
 */
data class EditDiffInfo(
    val path: String,
    val oldString: String,
    val newString: String,
)

class ChatViewModelFactory(
    private val sessionController: SessionController,
    private val imageEncoder: ImageEncoder? = null,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        check(modelClass == ChatViewModel::class.java) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }

        @Suppress("UNCHECKED_CAST")
        return ChatViewModel(
            sessionController = sessionController,
            imageEncoder = imageEncoder,
        ) as T
    }
}

private data class InitialLoadMetadata(
    val modelInfo: ModelInfo?,
    val thinkingLevel: String?,
    val isStreaming: Boolean,
    val steeringMode: String,
    val followUpMode: String,
)

private data class HistoryMessageWindow(
    val messages: List<JsonObject>,
    val absoluteOffset: Int,
)

private fun extractHistoryMessageWindow(data: JsonObject?): HistoryMessageWindow {
    val rawMessages = runCatching { data?.get("messages")?.jsonArray }.getOrNull() ?: JsonArray(emptyList())
    val startIndex = (rawMessages.size - HISTORY_WINDOW_MAX_ITEMS).coerceAtLeast(0)

    val messages =
        rawMessages
            .drop(startIndex)
            .mapNotNull { messageElement ->
                runCatching { messageElement.jsonObject }.getOrNull()
            }

    return HistoryMessageWindow(
        messages = messages,
        absoluteOffset = startIndex,
    )
}

private fun parseHistoryItems(
    messages: List<JsonObject>,
    absoluteIndexOffset: Int,
    startIndex: Int = 0,
    endExclusive: Int = messages.size,
): List<ChatTimelineItem> {
    if (messages.isEmpty()) {
        return emptyList()
    }

    val boundedStart = startIndex.coerceIn(0, messages.size)
    val boundedEnd = endExclusive.coerceIn(boundedStart, messages.size)

    return (boundedStart until boundedEnd).mapNotNull { index ->
        val message = messages[index]
        val absoluteIndex = absoluteIndexOffset + index

        when (message.stringField("role")) {
            "user" -> {
                val content = message["content"]
                val text = extractUserText(content)
                val imageCount = extractUserImageCount(content)
                ChatTimelineItem.User(
                    id = "history-user-$absoluteIndex",
                    text = text,
                    imageCount = imageCount,
                )
            }

            "assistant" -> {
                val text = extractAssistantText(message["content"])
                val thinking = extractAssistantThinking(message["content"])
                ChatTimelineItem.Assistant(
                    id = "history-assistant-$absoluteIndex",
                    text = text,
                    thinking = thinking,
                    isThinkingComplete = thinking != null,
                    isStreaming = false,
                )
            }

            "toolResult" -> {
                val output = extractToolOutput(message)
                ChatTimelineItem.Tool(
                    id = "history-tool-$absoluteIndex",
                    toolName = message.stringField("toolName") ?: "tool",
                    output = output,
                    isCollapsed = output.length > 400,
                    isStreaming = false,
                    isError = message.booleanField("isError") ?: false,
                    arguments = emptyMap(),
                    editDiff = null,
                )
            }

            else -> null
        }
    }
}

private fun extractUserText(content: JsonElement?): String {
    return when (content) {
        null -> ""
        is JsonObject -> content.stringField("text").orEmpty()
        else -> {
            runCatching {
                when (content) {
                    is kotlinx.serialization.json.JsonPrimitive -> content.contentOrNull.orEmpty()
                    else -> {
                        content.jsonArray
                            .mapNotNull { block ->
                                block.jsonObject.takeIf { it.stringField("type") == "text" }?.stringField("text")
                            }.joinToString("\n")
                    }
                }
            }.getOrDefault("")
        }
    }
}

private fun extractUserImageCount(content: JsonElement?): Int {
    return runCatching {
        when (content) {
            null -> 0
            is kotlinx.serialization.json.JsonPrimitive -> 0
            is JsonObject -> {
                val type = content.stringField("type")?.lowercase().orEmpty()
                if ("image" in type) 1 else 0
            }
            else -> {
                content.jsonArray.count { block ->
                    val blockObject = runCatching { block.jsonObject }.getOrNull() ?: return@count false
                    val type = blockObject.stringField("type")?.lowercase().orEmpty()
                    type.contains("image") ||
                        blockObject["image"] != null ||
                        blockObject["imageUrl"] != null ||
                        blockObject["image_url"] != null
                }
            }
        }
    }.getOrDefault(0)
}

private fun extractAssistantText(content: JsonElement?): String {
    val contentArray = runCatching { content?.jsonArray }.getOrNull() ?: return ""
    return contentArray
        .mapNotNull { block ->
            val blockObject = block.jsonObject
            if (blockObject.stringField("type") == "text") {
                blockObject.stringField("text")
            } else {
                null
            }
        }.joinToString("\n")
}

private fun extractAssistantThinking(content: JsonElement?): String? {
    val contentArray = runCatching { content?.jsonArray }.getOrNull() ?: return null
    val thinkingBlocks =
        contentArray
            .mapNotNull { block ->
                val blockObject = block.jsonObject
                if (blockObject.stringField("type") == "thinking") {
                    blockObject.stringField("thinking")
                } else {
                    null
                }
            }
    return thinkingBlocks.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

private fun extractToolOutput(source: JsonObject?): String {
    return source?.let { jsonSource ->
        val fromContent =
            runCatching {
                jsonSource["content"]?.jsonArray
                    ?.mapNotNull { block ->
                        val blockObject = block.jsonObject
                        if (blockObject.stringField("type") == "text") {
                            blockObject.stringField("text")
                        } else {
                            null
                        }
                    }?.joinToString("\n")
            }.getOrNull()

        fromContent?.takeIf { it.isNotBlank() } ?: jsonSource.stringField("output").orEmpty()
    }.orEmpty()
}

private fun JsonObject.stringField(fieldName: String): String? {
    return this[fieldName]?.jsonPrimitive?.contentOrNull
}

private fun JsonObject.booleanField(fieldName: String): Boolean? {
    return this[fieldName]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
}

private fun JsonObject?.deliveryModeField(
    camelCaseKey: String,
    snakeCaseKey: String,
): String {
    val value =
        this?.get(camelCaseKey)?.jsonPrimitive?.contentOrNull
            ?: this?.get(snakeCaseKey)?.jsonPrimitive?.contentOrNull

    return value?.takeIf {
        it == ChatViewModel.DELIVERY_MODE_ALL || it == ChatViewModel.DELIVERY_MODE_ONE_AT_A_TIME
    } ?: ChatViewModel.DELIVERY_MODE_ALL
}

private fun parseModelInfo(data: JsonObject?): ModelInfo? {
    val model = data?.get("model") as? JsonObject ?: return null
    return ModelInfo(
        id = model.stringField("id") ?: "unknown",
        name = model.stringField("name") ?: "Unknown Model",
        provider = model.stringField("provider") ?: "unknown",
        thinkingLevel = data.stringField("thinkingLevel") ?: "off",
    )
}

/**
 * Extracts tool arguments from JSON object as a map of string keys to string values.
 * Only extracts primitive string arguments for display purposes.
 */
private fun extractToolArguments(args: JsonObject?): Map<String, String> {
    if (args == null) return emptyMap()
    return args
        .mapNotNull { (key, value) ->
            when {
                value is kotlinx.serialization.json.JsonPrimitive &&
                    value.isString -> key to value.content
                else -> null
            }
        }.toMap()
}

/**
 * Extracts edit tool diff information from arguments.
 * Returns null if not an edit tool or required fields are missing.
 */
@Suppress("ReturnCount")
private fun extractEditDiff(args: JsonObject?): EditDiffInfo? {
    if (args == null) return null
    val path = args.stringField("path") ?: return null
    val oldString = args.stringField("oldString") ?: return null
    val newString = args.stringField("newString") ?: return null
    return EditDiffInfo(
        path = path,
        oldString = oldString,
        newString = newString,
    )
}
