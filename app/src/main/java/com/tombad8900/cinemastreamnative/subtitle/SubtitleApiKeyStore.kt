package com.tombad8900.cinemastreamnative.subtitle

import android.content.Context
import com.tombad8900.cinemastreamnative.iptv.SecureCredentialStore

object SubtitleApiKeyStore {

    private const val PREFS_NAME = "cinemastream_subtitle_api_keys"

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(context: Context, provider: String, apiKey: String) {
        val key = apiKey.trim()
        val prefs = preferences(context)

        if (key.isEmpty()) {
            prefs.edit().remove(provider).apply()
            return
        }

        val encrypted = SecureCredentialStore.encrypt(key)
        prefs.edit().putString(provider, encrypted).apply()
    }

    fun get(context: Context, provider: String): String? {
        val encrypted = preferences(context).getString(provider, null)
            ?: return null

        return runCatching {
            SecureCredentialStore.decrypt(encrypted)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun clear(context: Context, provider: String) {
        preferences(context).edit().remove(provider).apply()
    }
}
