package com.ayagmar.pimobile.sessions

import com.ayagmar.pimobile.corenet.ConnectionState
import com.ayagmar.pimobile.corenet.SocketTransport
import com.ayagmar.pimobile.corenet.WebSocketTarget
import com.ayagmar.pimobile.corenet.WebSocketTransport
import com.ayagmar.pimobile.coresessions.SessionGroup
import com.ayagmar.pimobile.coresessions.SessionIndexRemoteDataSource
import com.ayagmar.pimobile.hosts.HostProfileStore
import com.ayagmar.pimobile.hosts.HostTokenStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class BridgeSessionIndexRemoteDataSource(
    private val profileStore: HostProfileStore,
    private val tokenStore: HostTokenStore,
    private val transportFactory: () -> SocketTransport = { WebSocketTransport() },
    private val json: Json = defaultJson,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
) : SessionIndexRemoteDataSource {
    override suspend fun fetch(hostId: String): List<SessionGroup> {
        val hostProfile =
            profileStore.list().firstOrNull { profile -> profile.id == hostId }
                ?: throw IllegalArgumentException("Unknown host id: $hostId")

        val token = tokenStore.getToken(hostId)
        check(!token.isNullOrBlank()) {
            "No token configured for host: ${hostProfile.name}"
        }

        val transport = transportFactory()

        return try {
            transport.connect(
                WebSocketTarget(
                    url = hostProfile.endpoint,
                    headers = mapOf(AUTHORIZATION_HEADER to "Bearer $token"),
                    connectTimeoutMs = connectTimeoutMs,
                ),
            )

            withTimeout(connectTimeoutMs) {
                transport.connectionState.first { state -> state == ConnectionState.CONNECTED }
            }

            transport.send(createListSessionsEnvelope())

            withTimeout(requestTimeoutMs) {
                awaitSessionGroups(transport)
            }
        } finally {
            transport.disconnect()
        }
    }

    private suspend fun awaitSessionGroups(transport: SocketTransport): List<SessionGroup> {
        return transport.inboundMessages
            .mapNotNull { rawMessage ->
                parseSessionsPayload(rawMessage)
            }
            .first()
    }

    private fun parseSessionsPayload(rawMessage: String): List<SessionGroup>? =
        runCatching {
            json.decodeFromString(BridgeEnvelope.serializer(), rawMessage)
        }.getOrNull()
            ?.takeIf { envelope -> envelope.channel == BRIDGE_CHANNEL }
            ?.payload
            ?.let { payload ->
                when (payload["type"]?.jsonPrimitive?.contentOrNull) {
                    BRIDGE_SESSIONS_TYPE -> {
                        val decoded = json.decodeFromJsonElement(BridgeSessionsPayload.serializer(), payload)
                        decoded.groups
                    }

                    BRIDGE_ERROR_TYPE -> {
                        val decoded = json.decodeFromJsonElement(BridgeErrorPayload.serializer(), payload)
                        throw IllegalStateException(decoded.message ?: "Bridge returned an error")
                    }

                    else -> null
                }
            }

    private fun createListSessionsEnvelope(): String {
        val envelope =
            buildJsonObject {
                put("channel", BRIDGE_CHANNEL)
                put(
                    "payload",
                    buildJsonObject {
                        put("type", BRIDGE_LIST_SESSIONS_TYPE)
                    },
                )
            }

        return json.encodeToString(JsonObject.serializer(), envelope)
    }

    @Serializable
    private data class BridgeEnvelope(
        val channel: String,
        val payload: JsonObject,
    )

    @Serializable
    private data class BridgeSessionsPayload(
        val type: String,
        val groups: List<SessionGroup>,
    )

    @Serializable
    private data class BridgeErrorPayload(
        val type: String,
        val code: String? = null,
        val message: String? = null,
    )

    companion object {
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BRIDGE_CHANNEL = "bridge"
        private const val BRIDGE_LIST_SESSIONS_TYPE = "bridge_list_sessions"
        private const val BRIDGE_SESSIONS_TYPE = "bridge_sessions"
        private const val BRIDGE_ERROR_TYPE = "bridge_error"
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
        private const val DEFAULT_REQUEST_TIMEOUT_MS = 10_000L

        val defaultJson: Json =
            Json {
                ignoreUnknownKeys = true
            }
    }
}
