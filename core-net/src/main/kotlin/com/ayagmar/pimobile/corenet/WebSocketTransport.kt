package com.ayagmar.pimobile.corenet

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
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
import kotlin.math.min

class WebSocketTransport(
    private val client: OkHttpClient = OkHttpClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val lifecycleMutex = Mutex()
    private val outboundQueue = Channel<String>(Channel.UNLIMITED)
    private val inbound =
        MutableSharedFlow<String>(
            extraBufferCapacity = DEFAULT_INBOUND_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val state = MutableStateFlow(ConnectionState.DISCONNECTED)

    private var activeConnection: ActiveConnection? = null
    private var connectionJob: Job? = null
    private var target: WebSocketTarget? = null
    private var explicitDisconnect = false

    val inboundMessages: Flow<String> = inbound.asSharedFlow()
    val connectionState = state.asStateFlow()

    suspend fun connect(target: WebSocketTarget) {
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

    suspend fun reconnect() {
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

    suspend fun disconnect() {
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
        state.value = ConnectionState.DISCONNECTED
    }

    suspend fun send(message: String) {
        outboundQueue.send(message)
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

        val listener =
            object : WebSocketListener() {
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
                    inbound.tryEmit(text)
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    bytes: ByteString,
                ) {
                    inbound.tryEmit(bytes.utf8())
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
                    closed.complete(Unit)
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
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
        private const val DEFAULT_INBOUND_BUFFER_CAPACITY = 256
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
