package com.ayagmar.pimobile.hosts

import com.ayagmar.pimobile.corenet.ConnectionState
import com.ayagmar.pimobile.corenet.PiRpcConnection
import com.ayagmar.pimobile.corenet.PiRpcConnectionConfig
import com.ayagmar.pimobile.corenet.WebSocketTarget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

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
    /**
     * Tests connection to a host by attempting:
     * 1. WebSocket connection (bridge reachable)
     * 2. Request state via RPC (auth valid)
     * 3. Receive response (RPC working)
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    suspend fun testHost(
        hostProfile: HostProfile,
        token: String,
        timeoutMs: Long = 10000,
    ): DiagnosticsResult {
        val connection = PiRpcConnection()

        return try {
            val config = createConnectionConfig(hostProfile, token)

            // Step 1 & 2: Connect (includes auth handshake)
            withTimeout(timeoutMs) {
                connection.connect(config)
                connection.connectionState.first { it == ConnectionState.CONNECTED }
            }

            // Step 3: Request state via RPC
            val response =
                withTimeout(timeoutMs) {
                    connection.requestState()
                }

            connection.disconnect()

            if (response.success) {
                val data = response.data
                DiagnosticsResult.Success(
                    hostProfile = hostProfile,
                    bridgeVersion = data?.get("version")?.toString(),
                    model = data?.get("model")?.toString(),
                    cwd = data?.get("cwd")?.toString(),
                )
            } else {
                DiagnosticsResult.RpcError(
                    hostProfile = hostProfile,
                    message = response.error ?: "Unknown RPC error",
                )
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            connection.disconnect()
            DiagnosticsResult.NetworkError(
                hostProfile = hostProfile,
                message = "Connection timed out after ${timeoutMs}ms",
            )
        } catch (e: Exception) {
            connection.disconnect()
            when {
                e.message?.contains("401", ignoreCase = true) == true ||
                    e.message?.contains("Unauthorized", ignoreCase = true) == true -> {
                    DiagnosticsResult.AuthError(
                        hostProfile = hostProfile,
                        message = "Authentication failed: invalid token",
                    )
                }
                e.message?.contains("refused", ignoreCase = true) == true ||
                    e.message?.contains("unreachable", ignoreCase = true) == true -> {
                    DiagnosticsResult.NetworkError(
                        hostProfile = hostProfile,
                        message = "Bridge unreachable: ${e.message}",
                    )
                }
                else -> {
                    DiagnosticsResult.NetworkError(
                        hostProfile = hostProfile,
                        message = e.message ?: "Unknown error",
                    )
                }
            }
        }
    }

    private fun createConnectionConfig(
        hostProfile: HostProfile,
        token: String,
    ): PiRpcConnectionConfig {
        // The bridge uses the Authorization header for auth
        val target =
            WebSocketTarget(
                url = hostProfile.endpoint,
                headers = mapOf("Authorization" to "Bearer $token"),
            )
        // Dummy cwd for diagnostics
        return PiRpcConnectionConfig(
            target = target,
            cwd = "/tmp",
            sessionPath = null,
        )
    }
}
