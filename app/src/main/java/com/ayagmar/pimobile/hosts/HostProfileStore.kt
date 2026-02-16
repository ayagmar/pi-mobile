package com.ayagmar.pimobile.hosts

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

interface HostProfileStore {
    fun list(): List<HostProfile>

    fun upsert(profile: HostProfile)

    fun delete(hostId: String)
}

class SharedPreferencesHostProfileStore(
    context: Context,
) : HostProfileStore {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PROFILES_PREFS_FILE, Context.MODE_PRIVATE)

    override fun list(): List<HostProfile> {
        val raw = preferences.getString(PROFILES_KEY, null) ?: return emptyList()
        return decodeProfiles(raw)
    }

    override fun upsert(profile: HostProfile) {
        val existing = list().toMutableList()
        val index = existing.indexOfFirst { candidate -> candidate.id == profile.id }
        if (index >= 0) {
            existing[index] = profile
        } else {
            existing += profile
        }

        persist(existing)
    }

    override fun delete(hostId: String) {
        val updated = list().filterNot { profile -> profile.id == hostId }
        persist(updated)
    }

    private fun persist(profiles: List<HostProfile>) {
        preferences.edit().putString(PROFILES_KEY, encodeProfiles(profiles)).apply()
    }

    private fun encodeProfiles(profiles: List<HostProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("host", profile.host)
                    .put("port", profile.port)
                    .put("useTls", profile.useTls),
            )
        }
        return array.toString()
    }

    private fun decodeProfiles(raw: String): List<HostProfile> {
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length())
                .mapNotNull { index ->
                    array.optJSONObject(index)?.toHostProfileOrNull()
                }
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.toHostProfileOrNull(): HostProfile? {
        val id = optString("id")
        val name = optString("name")
        val host = optString("host")
        val port = optInt("port", INVALID_PORT)

        val hasRequiredValues = id.isNotBlank() && name.isNotBlank() && host.isNotBlank()
        val hasValidPort = port in HostDraft.MIN_PORT..HostDraft.MAX_PORT
        if (!hasRequiredValues || !hasValidPort) {
            return null
        }

        return HostProfile(
            id = id,
            name = name,
            host = host,
            port = port,
            useTls = optBoolean("useTls", false),
        )
    }

    companion object {
        private const val PROFILES_PREFS_FILE = "pi_mobile_hosts"
        private const val PROFILES_KEY = "profiles"
        private const val INVALID_PORT = -1
    }
}
