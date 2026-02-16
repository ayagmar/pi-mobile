package com.ayagmar.pimobile.corenet

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import kotlin.math.min

class WebSocketTransport(
    client: OkHttpClient? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val inboundBufferCapacity: Int = DEFAULT_INBOUND_BUFFER_CAPACITY,
) : SocketTransport {
    private val client: OkHttpClient = client ?: createDefaultClient()
    private val lifecycleMutex = Mutex()
    private val outboundQueue = Channel<String>(DEFAULT_OUTBOUND_BUFFER_CAPACITY)
    private val inbound = MutableSharedFlow<String>(extraBufferCapacity = inboundBufferCapacity)
    private val state = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val logger = Logger.getLogger(WebSocketTransport::class.java.name)
    private val inboundReceivedCount = AtomicLong(0)
    private val inboundDroppedCount = AtomicLong(0)
    private val inboundBackpressureReconnectCount = AtomicLong(0)

    private var activeConnection: ActiveConnection? = null
    private var connectionJob: Job? = null
    private var target: WebSocketTarget? = null
    private var explicitDisconnect = false

    init {
        require(inboundBufferCapacity > 0) { "inboundBufferCapacity must be greater than 0" }
    }

    override val inboundMessages: Flow<String> = inbound.asSharedFlow()
    override val connectionState = state.asStateFlow()

    override suspend fun connect(target: WebSocketTarget) {
        lifecycleMutex.withLock {
            this.target = target
            explicitDisconnect = false

            if (connectionJob?.isActive == true) {
                activeConnection?.socket?.cancel()
                return
            }

            connectionJob =
                scope.launch {
                    runConnectionLoop()
                }
        }
    }

    override suspend fun reconnect() {
        lifecycleMutex.withLock {
            if (target == null) {
                return
            }

            explicitDisconnect = false
            activeConnection?.socket?.cancel()

            if (connectionJob?.isActive != true) {
                connectionJob =
                    scope.launch {
                        runConnectionLoop()
                    }
            }
        }
    }

    override suspend fun disconnect() {
        val jobToCancel: Job?
        lifecycleMutex.withLock {
            explicitDisconnect = true
            target = null
            activeConnection?.socket?.close(NORMAL_CLOSE_CODE, CLIENT_DISCONNECT_REASON)
            activeConnection?.socket?.cancel()
            activeConnection = null
            jobToCancel = connectionJob
            connectionJob = null
        }

        jobToCancel?.cancel()
        jobToCancel?.join()
        clearOutboundQueue()
        state.value = ConnectionState.DISCONNECTED
        logInboundDiagnostics(reason = "disconnect")
    }

    override suspend fun send(message: String) {
        check(outboundQueue.trySend(message).isSuccess) {
            "Outbound queue is full"
        }
    }

    private suspend fun runConnectionLoop() {
        var reconnectAttempt = 0

        try {
            while (true) {
                val currentTarget = lifecycleMutex.withLock { target } ?: return
                val connectionStep = executeConnectionStep(currentTarget, reconnectAttempt)

                if (!connectionStep.keepRunning) {
                    return
                }

                reconnectAttempt = connectionStep.nextReconnectAttempt
                delay(connectionStep.delayMs)
            }
        } finally {
            activeConnection = null
            state.value = ConnectionState.DISCONNECTED
        }
    }

    private suspend fun executeConnectionStep(
        currentTarget: WebSocketTarget,
        reconnectAttempt: Int,
    ): ConnectionStep {
        state.value =
            if (reconnectAttempt == 0) {
                ConnectionState.CONNECTING
            } else {
                ConnectionState.RECONNECTING
            }

        val openedConnection = openConnection(currentTarget)

        return if (openedConnection == null) {
            val nextReconnectAttempt = reconnectAttempt + 1
            ConnectionStep(
                keepRunning = true,
                nextReconnectAttempt = nextReconnectAttempt,
                delayMs = reconnectDelay(currentTarget, nextReconnectAttempt),
            )
        } else {
            val shouldReconnect = consumeConnection(openedConnection)
            val nextReconnectAttempt =
                if (shouldReconnect) {
                    reconnectAttempt + 1
                } else {
                    reconnectAttempt
                }

            ConnectionStep(
                keepRunning = shouldReconnect,
                nextReconnectAttempt = nextReconnectAttempt,
                delayMs = reconnectDelay(currentTarget, nextReconnectAttempt),
            )
        }
    }

    private suspend fun consumeConnection(openedConnection: ActiveConnection): Boolean {
        activeConnection = openedConnection
        state.value = ConnectionState.CONNECTED

        val senderJob =
            scope.launch {
                forwardOutboundMessages(openedConnection)
            }

        openedConnection.closed.await()
        senderJob.cancel()
        senderJob.join()
        activeConnection = null

        return lifecycleMutex.withLock { !explicitDisconnect && target != null }
    }

    private suspend fun openConnection(target: WebSocketTarget): ActiveConnection? {
        val opened = CompletableDeferred<WebSocket>()
        val closed = CompletableDeferred<Unit>()

        val receivedAtConnectionStart = inboundReceivedCount.get()
        val droppedAtConnectionStart = inboundDroppedCount.get()
        val reconnectsAtConnectionStart = inboundBackpressureReconnectCount.get()

        val listener =
            object : WebSocketListener() {
                private var backpressureRecoveryTriggered = false

                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {
                    opened.complete(webSocket)
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    emitInboundOrRecover(
                        webSocket = webSocket,
                        payload = text,
                        markBackpressureTriggered = {
                            backpressureRecoveryTriggered = true
                        },
                        isBackpressureTriggered = { backpressureRecoveryTriggered },
                    )
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    bytes: ByteString,
                ) {
                    emitInboundOrRecover(
                        webSocket = webSocket,
                        payload = bytes.utf8(),
                        markBackpressureTriggered = {
                            backpressureRecoveryTriggered = true
                        },
                        isBackpressureTriggered = { backpressureRecoveryTriggered },
                    )
                }

                override fun onClosing(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    logInboundDiagnostics(
                        reason = "socket_closed:$code:$reason",
                        receivedAtStart = receivedAtConnectionStart,
                        droppedAtStart = droppedAtConnectionStart,
                        reconnectsAtStart = reconnectsAtConnectionStart,
                    )
                    closed.complete(Unit)
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    logInboundDiagnostics(
                        reason = "socket_failure:${t.message ?: "unknown"}",
                        receivedAtStart = receivedAtConnectionStart,
                        droppedAtStart = droppedAtConnectionStart,
                        reconnectsAtStart = reconnectsAtConnectionStart,
                    )
                    if (!opened.isCompleted) {
                        opened.completeExceptionally(t)
                    }
                    if (!closed.isCompleted) {
                        closed.complete(Unit)
                    }
                }
            }

        val request = target.toRequest()
        val socket = client.newWebSocket(request, listener)

        return try {
            withTimeout(target.connectTimeoutMs) {
                opened.await()
            }
            ActiveConnection(socket = socket, closed = closed)
        } catch (cancellationException: CancellationException) {
            socket.cancel()
            throw cancellationException
        } catch (_: Throwable) {
            socket.cancel()
            null
        }
    }

    private suspend fun forwardOutboundMessages(connection: ActiveConnection) {
        while (true) {
            val queuedMessage =
                select<String?> {
                    connection.closed.onAwait {
                        null
                    }
                    outboundQueue.onReceive { message ->
                        message
                    }
                } ?: return

            val sent = connection.socket.send(queuedMessage)
            if (!sent) {
                outboundQueue.trySend(queuedMessage)
                return
            }
        }
    }

    private fun clearOutboundQueue() {
        while (outboundQueue.tryReceive().isSuccess) {
            // drain stale unsent messages on explicit disconnect
        }
    }

    private fun emitInboundOrRecover(
        webSocket: WebSocket,
        payload: String,
        markBackpressureTriggered: () -> Unit,
        isBackpressureTriggered: () -> Boolean,
    ) {
        inboundReceivedCount.incrementAndGet()

        if (inbound.tryEmit(payload)) {
            return
        }

        inboundDroppedCount.incrementAndGet()

        if (!isBackpressureTriggered()) {
            markBackpressureTriggered()
            inboundBackpressureReconnectCount.incrementAndGet()
            logger.warning("Inbound buffer saturated; restarting websocket to force deterministic resync")
            val closed = webSocket.close(BACKPRESSURE_CLOSE_CODE, BACKPRESSURE_CLOSE_REASON)
            if (!closed) {
                webSocket.cancel()
            }
        }
    }

    private fun logInboundDiagnostics(
        reason: String,
        receivedAtStart: Long = 0,
        droppedAtStart: Long = 0,
        reconnectsAtStart: Long = 0,
    ) {
        val received = inboundReceivedCount.get() - receivedAtStart
        val dropped = inboundDroppedCount.get() - droppedAtStart
        val reconnects = inboundBackpressureReconnectCount.get() - reconnectsAtStart
        logger.info(
            "ws_inbound_diagnostics reason=$reason " +
                "received=$received dropped=$dropped backpressureReconnects=$reconnects",
        )
    }

    private data class ActiveConnection(
        val socket: WebSocket,
        val closed: CompletableDeferred<Unit>,
    )

    private data class ConnectionStep(
        val keepRunning: Boolean,
        val nextReconnectAttempt: Int,
        val delayMs: Long,
    )

    companion object {
        private const val CLIENT_DISCONNECT_REASON = "client disconnect"
        private const val NORMAL_CLOSE_CODE = 1000
        private const val BACKPRESSURE_CLOSE_CODE = 1013
        private const val BACKPRESSURE_CLOSE_REASON = "inbound backpressure"
        private const val DEFAULT_INBOUND_BUFFER_CAPACITY = 1024
        private const val DEFAULT_OUTBOUND_BUFFER_CAPACITY = 256

        private fun createDefaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(NO_TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(NO_TIMEOUT, TimeUnit.SECONDS)
                .build()
        }

        private const val PING_INTERVAL_SECONDS = 30L
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val NO_TIMEOUT = 0L
    }
}

private fun reconnectDelay(
    target: WebSocketTarget,
    attempt: Int,
): Long {
    if (attempt <= 0) {
        return target.reconnectInitialDelayMs
    }

    var delayMs = target.reconnectInitialDelayMs
    repeat(attempt - 1) {
        delayMs = min(delayMs * 2, target.reconnectMaxDelayMs)
    }
    return min(delayMs, target.reconnectMaxDelayMs)
}

data class WebSocketTarget(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val connectTimeoutMs: Long = 10_000,
    val reconnectInitialDelayMs: Long = 250,
    val reconnectMaxDelayMs: Long = 5_000,
) {
    fun toRequest(): Request {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) ->
            builder.addHeader(name, value)
        }
        return builder.build()
    }
}
