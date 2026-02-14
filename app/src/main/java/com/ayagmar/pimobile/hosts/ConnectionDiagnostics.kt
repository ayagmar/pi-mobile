package com.ayagmar.pimobile.hosts

import com.ayagmar.pimobile.corenet.ConnectionState
import com.ayagmar.pimobile.corenet.PiRpcConnection
import com.ayagmar.pimobile.corenet.PiRpcConnectionConfig
import com.ayagmar.pimobile.corenet.WebSocketTarget
import com.ayagmar.pimobile.corerpc.RpcResponse
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Result of connection diagnostics check.
 */
sealed interface DiagnosticsResult {
    val hostProfile: HostProfile

    data class Success(
        override val hostProfile: HostProfile,
        val bridgeVersion: String?,
        val model: String?,
        val cwd: String?,
    ) : DiagnosticsResult

    data class NetworkError(
        override val hostProfile: HostProfile,
        val message: String,
    ) : DiagnosticsResult

    data class AuthError(
        override val hostProfile: HostProfile,
        val message: String,
    ) : DiagnosticsResult

    data class RpcError(
        override val hostProfile: HostProfile,
        val message: String,
    ) : DiagnosticsResult
}

/**
 * Performs connection diagnostics to verify bridge connectivity and auth.
 */
class ConnectionDiagnostics {
    @Suppress("TooGenericExceptionCaught")
    suspend fun testHost(
        hostProfile: HostProfile,
        token: String,
        timeoutMs: Long = 10_000,
    ): DiagnosticsResult {
        val connection = PiRpcConnection()

        return try {
            val response = connectAndRequestState(connection, hostProfile, token, timeoutMs)
            response.toDiagnosticsResult(hostProfile)
        } catch (error: TimeoutCancellationException) {
            DiagnosticsResult.NetworkError(
                hostProfile = hostProfile,
                message = "Connection timed out after ${timeoutMs}ms (${error::class.simpleName})",
            )
        } catch (error: Exception) {
            mapError(hostProfile, error)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun connectAndRequestState(
        connection: PiRpcConnection,
        hostProfile: HostProfile,
        token: String,
        timeoutMs: Long,
    ) = withTimeout(timeoutMs) {
        connection.connect(createConnectionConfig(hostProfile, token))
        connection.connectionState.first { state -> state == ConnectionState.CONNECTED }
        connection.requestState()
    }

    private fun RpcResponse.toDiagnosticsResult(hostProfile: HostProfile): DiagnosticsResult {
        if (!success) {
            return DiagnosticsResult.RpcError(
                hostProfile = hostProfile,
                message = error ?: "Unknown RPC error",
            )
        }

        return DiagnosticsResult.Success(
            hostProfile = hostProfile,
            bridgeVersion = null,
            model = data?.extractModelName(),
            cwd = data?.stringField("cwd"),
        )
    }

    private fun mapError(
        hostProfile: HostProfile,
        error: Exception,
    ): DiagnosticsResult {
        val message = error.message.orEmpty()

        return when {
            message.contains("401", ignoreCase = true) ||
                message.contains("unauthorized", ignoreCase = true) -> {
                DiagnosticsResult.AuthError(
                    hostProfile = hostProfile,
                    message = "Authentication failed: invalid token",
                )
            }

            message.contains("refused", ignoreCase = true) ||
                message.contains("unreachable", ignoreCase = true) -> {
                DiagnosticsResult.NetworkError(
                    hostProfile = hostProfile,
                    message = "Bridge unreachable: $message",
                )
            }

            else -> {
                DiagnosticsResult.NetworkError(
                    hostProfile = hostProfile,
                    message = if (message.isBlank()) "Unknown error" else message,
                )
            }
        }
    }

    private fun createConnectionConfig(
        hostProfile: HostProfile,
        token: String,
    ): PiRpcConnectionConfig {
        val target =
            WebSocketTarget(
                url = hostProfile.endpoint,
                headers = mapOf("Authorization" to "Bearer $token"),
            )

        return PiRpcConnectionConfig(
            target = target,
            cwd = "/tmp",
            sessionPath = null,
        )
    }
}

private fun JsonObject.stringField(fieldName: String): String? {
    return this[fieldName]?.toString()?.trim('"')
}

private fun JsonObject.extractModelName(): String? {
    val modelElement = this["model"] ?: return null
    return modelElement.extractModelName()
}

private fun JsonElement.extractModelName(): String? {
    return when (this) {
        is JsonObject -> stringField("name") ?: stringField("id")
        else -> toString().trim('"')
    }
}
