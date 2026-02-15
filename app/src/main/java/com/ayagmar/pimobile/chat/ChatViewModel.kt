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
import com.ayagmar.pimobile.corerpc.SessionStats
import com.ayagmar.pimobile.corerpc.ToolExecutionEndEvent
import com.ayagmar.pimobile.corerpc.ToolExecutionStartEvent
import com.ayagmar.pimobile.corerpc.ToolExecutionUpdateEvent
import com.ayagmar.pimobile.corerpc.TurnEndEvent
import com.ayagmar.pimobile.corerpc.TurnStartEvent
import com.ayagmar.pimobile.corerpc.UiUpdateThrottler
import com.ayagmar.pimobile.di.AppServices
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    private val recentLifecycleNotificationTimestamps = ArrayDeque<Long>()
    private var lastLifecycleNotificationMessage: String? = null
    private var lastLifecycleNotificationTimestampMs: Long = 0L
    private var fullTimeline: List<ChatTimelineItem> = emptyList()
    private var visibleTimelineSize: Int = 0

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

        // Record prompt send for TTFT tracking
        PerformanceMetrics.recordPromptSend()
        hasRecordedFirstToken = false

        viewModelScope.launch {
            val imagePayloads =
                pendingImages.mapNotNull { pending ->
                    imageEncoder?.encodeToPayload(pending)
                }

            if (message.isEmpty() && imagePayloads.isEmpty()) {
                _uiState.update {
                    it.copy(errorMessage = "Unable to attach image. Please try again.")
                }
                return@launch
            }

            _uiState.update { it.copy(inputText = "", pendingImages = emptyList(), errorMessage = null) }
            val result = sessionController.sendPrompt(message, imagePayloads)
            if (result.isFailure) {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
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
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            val result = sessionController.steer(message)
            if (result.isFailure) {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    fun followUp(message: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            val result = sessionController.followUp(message)
            if (result.isFailure) {
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

    fun fetchLastAssistantText(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            val result = sessionController.getLastAssistantText()
            if (result.isFailure) {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
                onResult(null)
            } else {
                onResult(result.getOrNull())
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

    private fun loadCommands() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingCommands = true) }
            val result = sessionController.getCommands()
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        commands = result.getOrNull() ?: emptyList(),
                        isLoadingCommands = false,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoadingCommands = false,
                        errorMessage = result.exceptionOrNull()?.message,
                    )
                }
            }
        }
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
                    current.copy(isStreaming = isStreaming)
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun observeEvents() {
        viewModelScope.launch {
            sessionController.rpcEvents.collect { event ->
                when (event) {
                    is MessageUpdateEvent -> handleMessageUpdate(event)
                    is MessageStartEvent -> handleMessageStart(event)
                    is MessageEndEvent -> {
                        flushPendingAssistantUpdate(force = true)
                        handleMessageEnd(event)
                    }
                    is TurnStartEvent -> handleTurnStart()
                    is TurnEndEvent -> {
                        flushAllPendingStreamUpdates(force = true)
                        handleTurnEnd(event)
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
        _uiState.update { state ->
            val newStatuses = state.extensionStatuses.toMutableMap()
            if (text == null) {
                newStatuses.remove(key)
            } else {
                newStatuses[key] = text
            }
            state.copy(extensionStatuses = newStatuses)
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

    private fun handleMessageStart(event: MessageStartEvent) {
        val role = event.message?.stringField("role") ?: "assistant"
        addLifecycleNotification("$role message started")
    }

    private fun handleMessageEnd(event: MessageEndEvent) {
        val role = event.message?.stringField("role") ?: "assistant"
        addLifecycleNotification("$role message completed")
    }

    private fun handleTurnStart() {
        addLifecycleNotification("Turn started")
    }

    private fun handleTurnEnd(event: TurnEndEvent) {
        val toolResultCount = event.toolResults?.size ?: 0
        val summary = if (toolResultCount > 0) "Turn completed ($toolResultCount tool results)" else "Turn completed"
        addLifecycleNotification(summary)
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

    private fun addLifecycleNotification(message: String) {
        val now = System.currentTimeMillis()

        trimLifecycleNotificationWindow(now)

        val shouldDropAsDuplicate =
            lastLifecycleNotificationMessage == message &&
                now - lastLifecycleNotificationTimestampMs < LIFECYCLE_DUPLICATE_WINDOW_MS

        val shouldDropAsBurst = recentLifecycleNotificationTimestamps.size >= MAX_LIFECYCLE_NOTIFICATIONS_PER_WINDOW

        if (shouldDropAsDuplicate || shouldDropAsBurst) {
            return
        }

        recentLifecycleNotificationTimestamps.addLast(now)
        lastLifecycleNotificationMessage = message
        lastLifecycleNotificationTimestampMs = now
        addSystemNotification(message, "info")
    }

    private fun trimLifecycleNotificationWindow(now: Long) {
        while (recentLifecycleNotificationTimestamps.isNotEmpty()) {
            val oldest = recentLifecycleNotificationTimestamps.first()
            if (now - oldest <= LIFECYCLE_NOTIFICATION_WINDOW_MS) {
                return
            }
            recentLifecycleNotificationTimestamps.removeFirst()
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

    fun loadOlderMessages() {
        if (visibleTimelineSize >= fullTimeline.size) {
            return
        }

        visibleTimelineSize = minOf(visibleTimelineSize + TIMELINE_PAGE_SIZE, fullTimeline.size)
        publishVisibleTimeline()
    }

    private fun loadInitialMessages() {
        viewModelScope.launch(Dispatchers.IO) {
            val messagesResult = sessionController.getMessages()
            val stateResult = sessionController.getState()

            val modelInfo = stateResult.getOrNull()?.data?.let { parseModelInfo(it) }
            val thinkingLevel = stateResult.getOrNull()?.data?.stringField("thinkingLevel")
            val isStreaming = stateResult.getOrNull()?.data?.booleanField("isStreaming") ?: false

            _uiState.update { state ->
                if (messagesResult.isFailure) {
                    fullTimeline = emptyList()
                    visibleTimelineSize = 0
                    state.copy(
                        isLoading = false,
                        errorMessage = messagesResult.exceptionOrNull()?.message,
                        timeline = emptyList(),
                        hasOlderMessages = false,
                        hiddenHistoryCount = 0,
                        currentModel = modelInfo,
                        thinkingLevel = thinkingLevel,
                        isStreaming = isStreaming,
                    )
                } else {
                    // Record first messages rendered for resume timing
                    PerformanceMetrics.recordFirstMessagesRendered()
                    val historyTimeline = parseHistoryItems(messagesResult.getOrNull()?.data)
                    val mergedTimeline =
                        if (state.isLoading) {
                            mergeHistoryWithRealtimeTimeline(historyTimeline)
                        } else {
                            historyTimeline
                        }
                    setInitialTimeline(mergedTimeline)
                    state.copy(
                        isLoading = false,
                        errorMessage = null,
                        timeline = visibleTimeline(),
                        hasOlderMessages = hasOlderMessages(),
                        hiddenHistoryCount = hiddenHistoryCount(),
                        currentModel = modelInfo,
                        thinkingLevel = thinkingLevel,
                        isStreaming = isStreaming,
                    )
                }
            }
        }
    }

    private var hasRecordedFirstToken = false

    private fun handleMessageUpdate(event: MessageUpdateEvent) {
        // Record first token received for TTFT tracking
        if (!hasRecordedFirstToken) {
            PerformanceMetrics.recordFirstToken()
            hasRecordedFirstToken = true
        }

        val assistantEventType = event.assistantMessageEvent?.type
        if (assistantEventType == "done" || assistantEventType == "error") {
            flushPendingAssistantUpdate(force = true)
            return
        }

        val update = assembler.apply(event) ?: return
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
                loadInitialMessages()
            } else {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    fun jumpAndContinueFromTreeEntry(entryId: String) {
        viewModelScope.launch {
            val result = sessionController.forkSessionFromEntryId(entryId)
            if (result.isSuccess) {
                val editorText = result.getOrNull()
                _uiState.update { state ->
                    state.copy(
                        isTreeSheetVisible = false,
                        inputText = editorText.orEmpty(),
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

            val stateResponse = sessionController.getState().getOrNull()
            val sessionPath = stateResponse?.data?.stringField("sessionFile")

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
        _uiState.update { state ->
            state.copy(
                timeline = visibleTimeline(),
                hasOlderMessages = hasOlderMessages(),
                hiddenHistoryCount = hiddenHistoryCount(),
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
        return fullTimeline.size > visibleTimelineSize
    }

    private fun hiddenHistoryCount(): Int {
        return (fullTimeline.size - visibleTimelineSize).coerceAtLeast(0)
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

    fun clearImages() {
        _uiState.update { it.copy(pendingImages = emptyList()) }
    }

    override fun onCleared() {
        assistantUpdateFlushJob?.cancel()
        toolUpdateFlushJobs.values.forEach { it.cancel() }
        toolUpdateFlushJobs.clear()
        toolUpdateThrottlers.clear()
        super.onCleared()
    }

    companion object {
        const val TREE_FILTER_DEFAULT = "default"
        const val TREE_FILTER_NO_TOOLS = "no-tools"
        const val TREE_FILTER_USER_ONLY = "user-only"
        const val TREE_FILTER_LABELED_ONLY = "labeled-only"

        private val SLASH_COMMAND_TOKEN_REGEX = Regex("^/([a-zA-Z0-9:_-]*)$")

        private const val HISTORY_ITEM_PREFIX = "history-"
        private const val ASSISTANT_UPDATE_THROTTLE_MS = 40L
        private const val TOOL_UPDATE_THROTTLE_MS = 50L
        private const val TOOL_COLLAPSE_THRESHOLD = 400
        private const val MAX_TIMELINE_ITEMS = 1_200
        private const val INITIAL_TIMELINE_SIZE = 120
        private const val TIMELINE_PAGE_SIZE = 120
        private const val BASH_HISTORY_SIZE = 10
        private const val MAX_NOTIFICATIONS = 6
        private const val LIFECYCLE_NOTIFICATION_WINDOW_MS = 5_000L
        private const val LIFECYCLE_DUPLICATE_WINDOW_MS = 1_200L
        private const val MAX_LIFECYCLE_NOTIFICATIONS_PER_WINDOW = 4
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
    val extensionStatuses: Map<String, String> = emptyMap(),
    val extensionWidgets: Map<String, ExtensionWidget> = emptyMap(),
    val extensionTitle: String? = null,
    val isCommandPaletteVisible: Boolean = false,
    val isCommandPaletteAutoOpened: Boolean = false,
    val commands: List<SlashCommandInfo> = emptyList(),
    val commandsQuery: String = "",
    val isLoadingCommands: Boolean = false,
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
    private val imageEncoder: ImageEncoder? = null,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        check(modelClass == ChatViewModel::class.java) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }

        @Suppress("UNCHECKED_CAST")
        return ChatViewModel(
            sessionController = AppServices.sessionController(),
            imageEncoder = imageEncoder,
        ) as T
    }
}

private fun parseHistoryItems(data: JsonObject?): List<ChatTimelineItem> {
    val messages = runCatching { data?.get("messages")?.jsonArray }.getOrNull() ?: JsonArray(emptyList())

    return messages.mapIndexedNotNull { index, messageElement ->
        val message = messageElement.jsonObject
        when (message.stringField("role")) {
            "user" -> {
                val text = extractUserText(message["content"])
                ChatTimelineItem.User(id = "history-user-$index", text = text)
            }

            "assistant" -> {
                val text = extractAssistantText(message["content"])
                val thinking = extractAssistantThinking(message["content"])
                ChatTimelineItem.Assistant(
                    id = "history-assistant-$index",
                    text = text,
                    thinking = thinking,
                    isThinkingComplete = thinking != null,
                    isStreaming = false,
                )
            }

            "toolResult" -> {
                val output = extractToolOutput(message)
                ChatTimelineItem.Tool(
                    id = "history-tool-$index",
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
