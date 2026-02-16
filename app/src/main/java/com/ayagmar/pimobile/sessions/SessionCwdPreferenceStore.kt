package com.ayagmar.pimobile.sessions

import android.content.Context
import android.content.SharedPreferences

interface SessionCwdPreferenceStore {
    fun getPreferredCwd(hostId: String): String?

    fun setPreferredCwd(
        hostId: String,
        cwd: String,
    )

    fun clearPreferredCwd(hostId: String)
}

object NoOpSessionCwdPreferenceStore : SessionCwdPreferenceStore {
    override fun getPreferredCwd(hostId: String): String? = null

    override fun setPreferredCwd(
        hostId: String,
        cwd: String,
    ) = Unit

    override fun clearPreferredCwd(hostId: String) = Unit
}

class InMemorySessionCwdPreferenceStore : SessionCwdPreferenceStore {
    private val valuesByHostId = linkedMapOf<String, String>()

    override fun getPreferredCwd(hostId: String): String? = valuesByHostId[hostId]

    override fun setPreferredCwd(
        hostId: String,
        cwd: String,
    ) {
        valuesByHostId[hostId] = cwd
    }

    override fun clearPreferredCwd(hostId: String) {
        valuesByHostId.remove(hostId)
    }
}

class SharedPreferencesSessionCwdPreferenceStore(
    context: Context,
) : SessionCwdPreferenceStore {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getPreferredCwd(hostId: String): String? {
        return preferences.getString(cwdKey(hostId), null)
    }

    override fun setPreferredCwd(
        hostId: String,
        cwd: String,
    ) {
        preferences.edit().putString(cwdKey(hostId), cwd).apply()
    }

    override fun clearPreferredCwd(hostId: String) {
        preferences.edit().remove(cwdKey(hostId)).apply()
    }

    private fun cwdKey(hostId: String): String = "cwd_$hostId"

    companion object {
        private const val PREFS_NAME = "pi_mobile_session_cwd_preferences"
    }
}
