package com.ayagmar.pimobile.corenet

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals

class WebSocketTransportIntegrationTest {
    @Test
    fun `connect streams inbound and outbound websocket messages`() =
        runBlocking {
            val server = MockWebServer()
            val serverReceivedMessages = Channel<String>(Channel.UNLIMITED)

            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            webSocket.send("server-hello")
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            serverReceivedMessages.trySend(text)
                        }
                    },
                ),
            )
            server.start()

            val transport = WebSocketTransport(client = OkHttpClient())
            val inboundMessages = Channel<String>(Channel.UNLIMITED)
            val collectorJob =
                launch {
                    transport.inboundMessages.collect { message ->
                        inboundMessages.send(message)
                    }
                }

            try {
                transport.connect(targetFor(server))

                awaitState(transport, ConnectionState.CONNECTED)
                assertEquals("server-hello", withTimeout(TIMEOUT_MS) { inboundMessages.receive() })

                transport.send("client-hello")
                assertEquals("client-hello", withTimeout(TIMEOUT_MS) { serverReceivedMessages.receive() })

                transport.disconnect()
                assertEquals(ConnectionState.DISCONNECTED, transport.connectionState.value)
            } finally {
                collectorJob.cancel()
                transport.disconnect()
                server.shutdown()
            }
        }

    @Test
    fun `reconnect flushes queued outbound message after socket drop`() =
        runBlocking {
            val server = MockWebServer()
            val firstSocket = CompletableDeferred<WebSocket>()
            val secondConnectionReceived = Channel<String>(Channel.UNLIMITED)

            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            firstSocket.complete(webSocket)
                            webSocket.send("server-first")
                        }
                    },
                ),
            )
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            webSocket.send("server-second")
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            secondConnectionReceived.trySend(text)
                        }
                    },
                ),
            )
            server.start()

            val transport = WebSocketTransport(client = OkHttpClient())
            val inboundMessages = Channel<String>(Channel.UNLIMITED)
            val collectorJob =
                launch {
                    transport.inboundMessages.collect { message ->
                        inboundMessages.send(message)
                    }
                }

            try {
                transport.connect(targetFor(server))

                awaitState(transport, ConnectionState.CONNECTED)
                assertEquals("server-first", withTimeout(TIMEOUT_MS) { inboundMessages.receive() })

                firstSocket.await().close(1012, "bridge restart")
                awaitState(transport, ConnectionState.RECONNECTING)

                transport.send("queued-during-reconnect")

                assertEquals("server-second", withTimeout(TIMEOUT_MS) { inboundMessages.receive() })
                awaitState(transport, ConnectionState.CONNECTED)
                assertEquals("queued-during-reconnect", withTimeout(TIMEOUT_MS) { secondConnectionReceived.receive() })
            } finally {
                collectorJob.cancel()
                transport.disconnect()
                server.shutdown()
            }
        }

    private fun targetFor(server: MockWebServer): WebSocketTarget =
        WebSocketTarget(
            url = server.url("/ws").toString(),
            reconnectInitialDelayMs = 25,
            reconnectMaxDelayMs = 50,
        )

    private suspend fun awaitState(
        transport: WebSocketTransport,
        expected: ConnectionState,
    ) {
        withTimeout(TIMEOUT_MS) {
            transport.connectionState.first { state -> state == expected }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
    }
}
