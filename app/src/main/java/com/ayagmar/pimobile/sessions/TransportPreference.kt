package com.ayagmar.pimobile.sessions

enum class TransportPreference(
    val value: String,
) {
    AUTO("auto"),
    WEBSOCKET("websocket"),
    SSE("sse"),
    ;

    companion object {
        fun fromValue(value: String?): TransportPreference {
            return entries.firstOrNull { preference -> preference.value == value } ?: AUTO
        }
    }
}
