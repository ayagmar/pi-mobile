package com.ayagmar.pimobile.hosts

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

interface HostTokenStore {
    fun hasToken(hostId: String): Boolean

    fun getToken(hostId: String): String?

    fun setToken(
        hostId: String,
        token: String,
    )

    fun clearToken(hostId: String)
}

class KeystoreHostTokenStore(
    context: Context,
) : HostTokenStore {
    private val preferences: SharedPreferences =
        createEncryptedPreferences(
            context = context,
            fileName = TOKENS_PREFS_FILE,
        )

    override fun hasToken(hostId: String): Boolean = preferences.contains(tokenKey(hostId))

    override fun getToken(hostId: String): String? = preferences.getString(tokenKey(hostId), null)

    override fun setToken(
        hostId: String,
        token: String,
    ) {
        preferences.edit().putString(tokenKey(hostId), token).apply()
    }

    override fun clearToken(hostId: String) {
        preferences.edit().remove(tokenKey(hostId)).apply()
    }

    private fun tokenKey(hostId: String): String = "token_$hostId"

    companion object {
        private const val TOKENS_PREFS_FILE = "pi_mobile_host_tokens_secure"

        private fun createEncryptedPreferences(
            context: Context,
            fileName: String,
        ): SharedPreferences {
            val masterKey =
                MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

            return EncryptedSharedPreferences.create(
                context,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
