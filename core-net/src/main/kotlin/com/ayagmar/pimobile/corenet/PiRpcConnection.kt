package com.ayagmar.pimobile.corenet

import com.ayagmar.pimobile.corerpc.GetMessagesCommand
import com.ayagmar.pimobile.corerpc.GetStateCommand
import com.ayagmar.pimobile.corerpc.RpcCommand
import com.ayagmar.pimobile.corerpc.RpcIncomingMessage
import com.ayagmar.pimobile.corerpc.RpcMessageParser
import com.ayagmar.pimobile.corerpc.RpcResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Suppress("TooManyFunctions")
class PiRpcConnection(
    private val transport: SocketTransport = WebSocketTransport(),
    private val parser: RpcMessageParser = RpcMessageParser(),
    private val json: Json = defaultJson,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val lifecycleMutex = Mutex()
    private val reconnectSyncMutex = Mutex()
    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<RpcResponse>>()
    private val bridgeChannels = ConcurrentHashMap<String, Channel<BridgeMessage>>()

    private val _rpcEvents =
        MutableSharedFlow<RpcIncomingMessage>(
            extraBufferCapacity = DEFAULT_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val _bridgeEvents =
        MutableSharedFlow<BridgeMessage>(
            extraBufferCapacity = DEFAULT_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val _resyncEvents = MutableSharedFlow<RpcResyncSnapshot>(replay = 1, extraBufferCapacity = 1)

    private var inboundJob: Job? = null
    private var connectionMonitorJob: Job? = null
    private var activeConfig: PiRpcConnectionConfig? = null

    @Volatile
    private var lifecycleEpoch: Long = 0

    val rpcEvents: SharedFlow<RpcIncomingMessage> = _rpcEvents
    val bridgeEvents: SharedFlow<BridgeMessage> = _bridgeEvents
    val resyncEvents: SharedFlow<RpcResyncSnapshot> = _resyncEvents
    val connectionState: StateFlow<ConnectionState> = transport.connectionState

    suspend fun connect(config: PiRpcConnectionConfig) {
        val resolvedConfig = config.resolveClientId()
        val connectionEpoch =
            lifecycleMutex.withLock {
                activeConfig = resolvedConfig
                lifecycleEpoch += 1
                startBackgroundJobs()
                lifecycleEpoch
            }

        val helloChannel = bridgeChannel(bridgeChannels, BRIDGE_HELLO_TYPE)

        transport.connect(resolvedConfig.targetWithClientId())
        withTimeout(resolvedConfig.connectTimeoutMs) {
            connectionState.first { state -> state == ConnectionState.CONNECTED }
        }

        val hello =
            withTimeout(resolvedConfig.requestTimeoutMs) {
                helloChannel.receive()
            }
        val resumed = hello.payload.booleanField("resumed") ?: false
        val helloCwd = hello.payload.stringField("cwd")

        if (!resumed || helloCwd != resolvedConfig.cwd) {
            ensureBridgeControl(
                transport = transport,
                json = json,
                channels = bridgeChannels,
                config = resolvedConfig,
            )
        }

        resyncIfActive(connectionEpoch)
    }

    suspend fun disconnect() {
        val configToRelease =
            lifecycleMutex.withLock {
                val currentConfig = activeConfig
                activeConfig = null
                lifecycleEpoch += 1
                inboundJob?.cancel()
                connectionMonitorJob?.cancel()
                inboundJob = null
                connectionMonitorJob = null
                currentConfig
            }

        sendBridgeReleaseControlBestEffort(configToRelease)
        cancelPendingResponses()

        bridgeChannels.values.forEach { channel ->
            channel.close()
        }
        bridgeChannels.clear()

        transport.disconnect()
    }

    suspend fun sendCommand(command: RpcCommand) {
        val payload = encodeRpcCommand(json = json, command = command)
        val envelope = encodeEnvelope(json = json, channel = RPC_CHANNEL, payload = payload)
        transport.send(envelope)
    }

    suspend fun requestBridge(
        payload: JsonObject,
        expectedType: String,
    ): BridgeMessage {
        val config = activeConfig ?: error("Connection is not active")

        val expectedChannel = bridgeChannel(bridgeChannels, expectedType)
        val errorChannel = bridgeChannel(bridgeChannels, BRIDGE_ERROR_TYPE)

        transport.send(
            encodeEnvelope(
                json = json,
                channel = BRIDGE_CHANNEL,
                payload = payload,
            ),
        )

        return withTimeout(config.requestTimeoutMs) {
            select {
                expectedChannel.onReceive { message ->
                    message
                }
                errorChannel.onReceive { message ->
                    throw IllegalStateException(parseBridgeErrorMessage(message))
                }
            }
        }
    }

    suspend fun requestState(): RpcResponse {
        return requestResponse(GetStateCommand(id = requestIdFactory()))
    }

    suspend fun requestMessages(): RpcResponse {
        return requestResponse(GetMessagesCommand(id = requestIdFactory()))
    }

    suspend fun resync(): RpcResyncSnapshot {
        val snapshot = buildResyncSnapshot()
        _resyncEvents.emit(snapshot)
        return snapshot
    }

    private suspend fun startBackgroundJobs() {
        if (inboundJob == null) {
            inboundJob =
                scope.launch {
                    transport.inboundMessages.collect { raw ->
                        routeInboundEnvelope(raw)
                    }
                }
        }

        if (connectionMonitorJob == null) {
            connectionMonitorJob =
                scope.launch {
                    var previousState = connectionState.value
                    connectionState.collect { currentState ->
                        if (
                            currentState == ConnectionState.RECONNECTING ||
                            currentState == ConnectionState.DISCONNECTED
                        ) {
                            cancelPendingResponses()
                        }

                        if (
                            previousState == ConnectionState.RECONNECTING &&
                            currentState == ConnectionState.CONNECTED
                        ) {
                            val reconnectEpoch = lifecycleEpoch
                            runCatching {
                                synchronizeAfterReconnect(reconnectEpoch)
                            }
                        }
                        previousState = currentState
                    }
                }
        }
    }

    private suspend fun routeInboundEnvelope(raw: String) {
        val envelope = parseEnvelope(raw = raw, json = json) ?: return

        when (envelope.channel) {
            RPC_CHANNEL -> {
                val rpcMessage =
                    runCatching {
                        parser.parse(envelope.payload.toString())
                    }.getOrNull()
                        ?: return

                _rpcEvents.emit(rpcMessage)

                if (rpcMessage is RpcResponse) {
                    val responseId = rpcMessage.id
                    if (responseId != null) {
                        pendingResponses.remove(responseId)?.complete(rpcMessage)
                    }
                }
            }

            BRIDGE_CHANNEL -> {
                val bridgeMessage =
                    BridgeMessage(
                        type = envelope.payload.stringField("type") ?: UNKNOWN_BRIDGE_TYPE,
                        payload = envelope.payload,
                    )
                _bridgeEvents.emit(bridgeMessage)
                bridgeChannels[bridgeMessage.type]?.trySend(bridgeMessage)
            }
        }
    }

    private suspend fun synchronizeAfterReconnect(expectedEpoch: Long) {
        reconnectSyncMutex.withLock {
            val config =
                if (isEpochActive(expectedEpoch)) {
                    activeConfig
                } else {
                    null
                }

            if (config != null) {
                val helloChannel = bridgeChannel(bridgeChannels, BRIDGE_HELLO_TYPE)
                val hello =
                    withTimeout(config.requestTimeoutMs) {
                        helloChannel.receive()
                    }
                val resumed = hello.payload.booleanField("resumed") ?: false
                val helloCwd = hello.payload.stringField("cwd")

                if (isEpochActive(expectedEpoch)) {
                    if (!resumed || helloCwd != config.cwd) {
                        ensureBridgeControl(
                            transport = transport,
                            json = json,
                            channels = bridgeChannels,
                            config = config,
                        )
                    }

                    resyncIfActive(expectedEpoch)
                }
            }
        }
    }

    private suspend fun buildResyncSnapshot(): RpcResyncSnapshot {
        val stateResponse = requestState()
        val messagesResponse = requestMessages()
        return RpcResyncSnapshot(
            stateResponse = stateResponse,
            messagesResponse = messagesResponse,
        )
    }

    private suspend fun resyncIfActive(expectedEpoch: Long): RpcResyncSnapshot? {
        if (isEpochActive(expectedEpoch)) {
            val snapshot = buildResyncSnapshot()
            if (isEpochActive(expectedEpoch)) {
                _resyncEvents.emit(snapshot)
                return snapshot
            }
        }

        return null
    }

    private fun isEpochActive(expectedEpoch: Long): Boolean {
        return lifecycleEpoch == expectedEpoch && activeConfig != null
    }

    private fun cancelPendingResponses() {
        pendingResponses.values.forEach { deferred ->
            deferred.cancel()
        }
        pendingResponses.clear()
    }

    private suspend fun requestResponse(command: RpcCommand): RpcResponse {
        val commandId = requireNotNull(command.id) { "RPC command id is required for request/response operations" }
        val responseDeferred = CompletableDeferred<RpcResponse>()
        pendingResponses[commandId] = responseDeferred

        return try {
            sendCommand(command)

            val timeoutMs = activeConfig?.requestTimeoutMs ?: DEFAULT_REQUEST_TIMEOUT_MS
            withTimeout(timeoutMs) {
                responseDeferred.await()
            }
        } finally {
            pendingResponses.remove(commandId)
        }
    }

    private suspend fun sendBridgeReleaseControlBestEffort(config: PiRpcConnectionConfig?) {
        val activeConfig = config ?: return

        if (connectionState.value == ConnectionState.DISCONNECTED) {
            return
        }

        runCatching {
            transport.send(
                encodeEnvelope(
                    json = json,
                    channel = BRIDGE_CHANNEL,
                    payload =
                        buildJsonObject {
                            put("type", "bridge_release_control")
                            put("cwd", activeConfig.cwd)
                            activeConfig.sessionPath?.let { path ->
                                put("sessionPath", path)
                            }
                        },
                ),
            )
        }
    }

    companion object {
        private const val BRIDGE_CHANNEL = "bridge"
        private const val RPC_CHANNEL = "rpc"
        private const val UNKNOWN_BRIDGE_TYPE = "unknown"
        private const val BRIDGE_HELLO_TYPE = "bridge_hello"
        private const val DEFAULT_BUFFER_CAPACITY = 128
        private const val DEFAULT_REQUEST_TIMEOUT_MS = 10_000L

        val defaultJson: Json =
            Json {
                ignoreUnknownKeys = true
            }
    }
}

data class PiRpcConnectionConfig(
    val target: WebSocketTarget,
    val cwd: String,
    val sessionPath: String? = null,
    val clientId: String? = null,
    val connectTimeoutMs: Long = 10_000,
    val requestTimeoutMs: Long = 10_000,
) {
    fun resolveClientId(): PiRpcConnectionConfig {
        if (!clientId.isNullOrBlank()) return this
        return copy(clientId = UUID.randomUUID().toString())
    }

    fun targetWithClientId(): WebSocketTarget {
        val currentClientId = requireNotNull(clientId) { "clientId must be resolved before building target URL" }
        return target.copy(url = appendClientId(target.url, currentClientId))
    }
}

data class BridgeMessage(
    val type: String,
    val payload: JsonObject,
)

data class RpcResyncSnapshot(
    val stateResponse: RpcResponse,
    val messagesResponse: RpcResponse,
)

private suspend fun ensureBridgeControl(
    transport: SocketTransport,
    json: Json,
    channels: ConcurrentHashMap<String, Channel<BridgeMessage>>,
    config: PiRpcConnectionConfig,
) {
    val errorChannel = bridgeChannel(channels, BRIDGE_ERROR_TYPE)
    val cwdSetChannel = bridgeChannel(channels, BRIDGE_CWD_SET_TYPE)
    val controlAcquiredChannel = bridgeChannel(channels, BRIDGE_CONTROL_ACQUIRED_TYPE)

    transport.send(
        encodeEnvelope(
            json = json,
            channel = BRIDGE_CHANNEL,
            payload =
                buildJsonObject {
                    put("type", "bridge_set_cwd")
                    put("cwd", config.cwd)
                },
        ),
    )

    withTimeout(config.requestTimeoutMs) {
        select<Unit> {
            cwdSetChannel.onReceive {
                Unit
            }
            errorChannel.onReceive { message ->
                throw IllegalStateException(parseBridgeErrorMessage(message))
            }
        }
    }

    transport.send(
        encodeEnvelope(
            json = json,
            channel = BRIDGE_CHANNEL,
            payload =
                buildJsonObject {
                    put("type", "bridge_acquire_control")
                    put("cwd", config.cwd)
                    config.sessionPath?.let { path ->
                        put("sessionPath", path)
                    }
                },
        ),
    )

    withTimeout(config.requestTimeoutMs) {
        select<Unit> {
            controlAcquiredChannel.onReceive {
                Unit
            }
            errorChannel.onReceive { message ->
                throw IllegalStateException(parseBridgeErrorMessage(message))
            }
        }
    }
}

private fun parseBridgeErrorMessage(message: BridgeMessage): String {
    val details = message.payload.stringField("message") ?: "Bridge operation failed"
    val code = message.payload.stringField("code")
    return if (code == null) details else "$code: $details"
}

private fun parseEnvelope(
    raw: String,
    json: Json,
): EnvelopeMessage? {
    val objectElement = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
    if (objectElement != null) {
        val channel = objectElement.stringField("channel")
        val payload = objectElement["payload"]?.jsonObject
        if (channel != null && payload != null) {
            return EnvelopeMessage(
                channel = channel,
                payload = payload,
            )
        }
    }

    return null
}

private fun encodeEnvelope(
    json: Json,
    channel: String,
    payload: JsonObject,
): String {
    val envelope =
        buildJsonObject {
            put("channel", channel)
            put("payload", payload)
        }

    return json.encodeToString(envelope)
}

private fun appendClientId(
    url: String,
    clientId: String,
): String {
    if ("clientId=" in url) {
        return url
    }

    val separator = if ("?" in url) "&" else "?"
    return "$url${separator}clientId=$clientId"
}

private fun JsonObject.stringField(name: String): String? {
    val primitive = this[name]?.jsonPrimitive ?: return null
    return primitive.contentOrNull
}

private fun JsonObject.booleanField(name: String): Boolean? {
    val primitive = this[name]?.jsonPrimitive ?: return null
    return primitive.booleanOrNull
}

private fun bridgeChannel(
    channels: ConcurrentHashMap<String, Channel<BridgeMessage>>,
    type: String,
): Channel<BridgeMessage> {
    return channels.computeIfAbsent(type) {
        Channel(BRIDGE_CHANNEL_BUFFER_CAPACITY)
    }
}

private data class EnvelopeMessage(
    val channel: String,
    val payload: JsonObject,
)

private const val BRIDGE_CHANNEL = "bridge"
private const val BRIDGE_ERROR_TYPE = "bridge_error"
private const val BRIDGE_CWD_SET_TYPE = "bridge_cwd_set"
private const val BRIDGE_CONTROL_ACQUIRED_TYPE = "bridge_control_acquired"
private const val BRIDGE_CHANNEL_BUFFER_CAPACITY = 16
