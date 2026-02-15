package com.ayagmar.pimobile.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayagmar.pimobile.corenet.ConnectionState
import com.ayagmar.pimobile.corerpc.AssistantTextAssembler
import com.ayagmar.pimobile.corerpc.ExtensionUiRequestEvent
import com.ayagmar.pimobile.corerpc.MessageUpdateEvent
import com.ayagmar.pimobile.corerpc.ToolExecutionEndEvent
import com.ayagmar.pimobile.corerpc.ToolExecutionStartEvent
import com.ayagmar.pimobile.corerpc.ToolExecutionUpdateEvent
import com.ayagmar.pimobile.di.AppServices
import com.ayagmar.pimobile.perf.PerformanceMetrics
import com.ayagmar.pimobile.sessions.ModelInfo
import com.ayagmar.pimobile.sessions.SessionController
import com.ayagmar.pimobile.sessions.SlashCommandInfo
import kotlinx.coroutines.Dispatchers
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

@Suppress("TooManyFunctions")
class ChatViewModel(
    private val sessionController: SessionController,
) : ViewModel() {
    private val assembler = AssistantTextAssembler()
    private val _uiState = MutableStateFlow(ChatUiState(isLoading = true))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        observeConnection()
        observeStreamingState()
        observeEvents()
        loadInitialMessages()
    }

    fun onInputTextChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendPrompt() {
        val message = _uiState.value.inputText.trim()
        if (message.isEmpty()) return

        // Record prompt send for TTFT tracking
        PerformanceMetrics.recordPromptSend()
        hasRecordedFirstToken = false

        viewModelScope.launch {
            _uiState.update { it.copy(inputText = "", errorMessage = null) }
            val result = sessionController.sendPrompt(message)
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

    fun showCommandPalette() {
        _uiState.update { it.copy(isCommandPaletteVisible = true, commandsQuery = "") }
        loadCommands()
    }

    fun hideCommandPalette() {
        _uiState.update { it.copy(isCommandPaletteVisible = false) }
    }

    fun onCommandsQueryChanged(query: String) {
        _uiState.update { it.copy(commandsQuery = query) }
    }

    fun onCommandSelected(command: SlashCommandInfo) {
        val currentText = _uiState.value.inputText
        val newText =
            if (currentText.isBlank()) {
                "/${command.name} "
            } else {
                "$currentText /${command.name} "
            }
        _uiState.update {
            it.copy(
                inputText = newText,
                isCommandPaletteVisible = false,
            )
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
        _uiState.update { state ->
            state.copy(
                timeline =
                    state.timeline.map { item ->
                        if (item is ChatTimelineItem.Tool && item.id == itemId) {
                            item.copy(isCollapsed = !item.isCollapsed)
                        } else {
                            item
                        }
                    },
            )
        }
    }

    private fun observeConnection() {
        viewModelScope.launch {
            sessionController.connectionState.collect { state ->
                _uiState.update { current ->
                    current.copy(connectionState = state)
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

    private fun observeEvents() {
        viewModelScope.launch {
            sessionController.rpcEvents.collect { event ->
                when (event) {
                    is MessageUpdateEvent -> handleMessageUpdate(event)
                    is ToolExecutionStartEvent -> handleToolStart(event)
                    is ToolExecutionUpdateEvent -> handleToolUpdate(event)
                    is ToolExecutionEndEvent -> handleToolEnd(event)
                    is ExtensionUiRequestEvent -> handleExtensionUiRequest(event)
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
        _uiState.update {
            it.copy(
                notifications =
                    it.notifications +
                        ExtensionNotification(
                            message = event.message ?: "",
                            type = event.notifyType ?: "info",
                        ),
            )
        }
    }

    private fun updateExtensionStatus(event: ExtensionUiRequestEvent) {
        val key = event.statusKey ?: "default"
        val text = event.statusText
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
        val lines = event.widgetLines
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
            _uiState.update { it.copy(extensionTitle = title) }
        }
    }

    private fun updateEditorText(event: ExtensionUiRequestEvent) {
        event.text?.let { text ->
            _uiState.update { it.copy(inputText = text) }
        }
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

    private fun loadInitialMessages() {
        viewModelScope.launch(Dispatchers.IO) {
            val messagesResult = sessionController.getMessages()
            val stateResult = sessionController.getState()

            val modelInfo = stateResult.getOrNull()?.data?.let { parseModelInfo(it) }
            val thinkingLevel = stateResult.getOrNull()?.data?.stringField("thinkingLevel")
            val isStreaming = stateResult.getOrNull()?.data?.booleanField("isStreaming") ?: false

            _uiState.update { state ->
                if (messagesResult.isFailure) {
                    state.copy(
                        isLoading = false,
                        errorMessage = messagesResult.exceptionOrNull()?.message,
                        currentModel = modelInfo,
                        thinkingLevel = thinkingLevel,
                        isStreaming = isStreaming,
                    )
                } else {
                    // Record first messages rendered for resume timing
                    PerformanceMetrics.recordFirstMessagesRendered()
                    state.copy(
                        isLoading = false,
                        errorMessage = null,
                        timeline = parseHistoryItems(messagesResult.getOrNull()?.data),
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

        val update = assembler.apply(event) ?: return
        val itemId = "assistant-stream-${update.messageKey}-${update.contentIndex}"

        val nextItem =
            ChatTimelineItem.Assistant(
                id = itemId,
                text = update.text,
                thinking = update.thinking,
                isThinkingComplete = update.isThinkingComplete,
                isStreaming = !update.isFinal,
            )

        upsertTimelineItem(nextItem, preserveThinkingState = true)
    }

    fun toggleThinkingExpansion(itemId: String) {
        _uiState.update { state ->
            val existingIndex = state.timeline.indexOfFirst { it.id == itemId }
            if (existingIndex < 0) return@update state

            val existing = state.timeline[existingIndex]
            if (existing !is ChatTimelineItem.Assistant) return@update state

            val updatedTimeline = state.timeline.toMutableList()
            updatedTimeline[existingIndex] =
                existing.copy(
                    isThinkingExpanded = !existing.isThinkingExpanded,
                )

            state.copy(timeline = updatedTimeline)
        }
    }

    private fun handleToolStart(event: ToolExecutionStartEvent) {
        val nextItem =
            ChatTimelineItem.Tool(
                id = "tool-${event.toolCallId}",
                toolName = event.toolName,
                output = "Running…",
                isCollapsed = true,
                isStreaming = true,
                isError = false,
            )

        upsertTimelineItem(nextItem)
    }

    private fun handleToolUpdate(event: ToolExecutionUpdateEvent) {
        val output = extractToolOutput(event.partialResult)
        val itemId = "tool-${event.toolCallId}"
        val isCollapsed = output.length > TOOL_COLLAPSE_THRESHOLD

        val nextItem =
            ChatTimelineItem.Tool(
                id = itemId,
                toolName = event.toolName,
                output = output,
                isCollapsed = isCollapsed,
                isStreaming = true,
                isError = false,
            )

        upsertTimelineItem(nextItem)
    }

    private fun handleToolEnd(event: ToolExecutionEndEvent) {
        val output = extractToolOutput(event.result)
        val itemId = "tool-${event.toolCallId}"
        val isCollapsed = output.length > TOOL_COLLAPSE_THRESHOLD

        val nextItem =
            ChatTimelineItem.Tool(
                id = itemId,
                toolName = event.toolName,
                output = output,
                isCollapsed = isCollapsed,
                isStreaming = false,
                isError = event.isError,
            )

        upsertTimelineItem(nextItem)
    }

    private fun upsertTimelineItem(
        item: ChatTimelineItem,
        preserveThinkingState: Boolean = false,
    ) {
        _uiState.update { state ->
            val existingIndex = state.timeline.indexOfFirst { existing -> existing.id == item.id }
            val updatedTimeline =
                if (existingIndex >= 0) {
                    state.timeline.toMutableList().also { timeline ->
                        val existing = timeline[existingIndex]
                        timeline[existingIndex] =
                            when {
                                existing is ChatTimelineItem.Tool && item is ChatTimelineItem.Tool -> {
                                    // Preserve user toggled expansion state across streaming updates.
                                    item.copy(isCollapsed = existing.isCollapsed)
                                }
                                existing is ChatTimelineItem.Assistant &&
                                    item is ChatTimelineItem.Assistant &&
                                    preserveThinkingState -> {
                                    // Preserve thinking expansion state and collapse new thinking if long.
                                    val shouldCollapse =
                                        item.thinking != null &&
                                            item.thinking.length > THINKING_COLLAPSE_THRESHOLD &&
                                            !existing.isThinkingExpanded
                                    item.copy(
                                        isThinkingExpanded = existing.isThinkingExpanded && shouldCollapse,
                                    )
                                }
                                else -> item
                            }
                    }
                } else {
                    state.timeline + item
                }

            state.copy(timeline = updatedTimeline)
        }
    }

    companion object {
        private const val TOOL_COLLAPSE_THRESHOLD = 400
        private const val THINKING_COLLAPSE_THRESHOLD = 280
    }
}

data class ChatUiState(
    val isLoading: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val isStreaming: Boolean = false,
    val timeline: List<ChatTimelineItem> = emptyList(),
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
    val commands: List<SlashCommandInfo> = emptyList(),
    val commandsQuery: String = "",
    val isLoadingCommands: Boolean = false,
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
    ) : ChatTimelineItem
}

class ChatViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        check(modelClass == ChatViewModel::class.java) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }

        @Suppress("UNCHECKED_CAST")
        return ChatViewModel(sessionController = AppServices.sessionController()) as T
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
    val model = data?.get("model")?.jsonObject ?: return null
    return ModelInfo(
        id = model.stringField("id") ?: "unknown",
        name = model.stringField("name") ?: "Unknown Model",
        provider = model.stringField("provider") ?: "unknown",
        thinkingLevel = data.stringField("thinkingLevel") ?: "off",
    )
}
