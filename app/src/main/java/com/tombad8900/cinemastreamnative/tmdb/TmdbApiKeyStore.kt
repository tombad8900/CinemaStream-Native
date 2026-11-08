package com.tombad8900.cinemastreamnative.tmdb

import android.content.Context
import com.tombad8900.cinemastreamnative.iptv.SecureCredentialStore

object TmdbApiKeyStore {
    private const val PREFS = "cinemastream_tmdb_credentials"
    private const val KEY = "api_key_v3"

    fun get(context: Context): String? {
        val encrypted = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null

        return runCatching {
            SecureCredentialStore.decrypt(encrypted)
        }.getOrNull()
    }

    fun save(context: Context, value: String) {
        require(value.isNotBlank()) { "مفتاح TMDB فارغ" }

        val encrypted = SecureCredentialStore.encrypt(value.trim())

        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, encrypted)
            .apply()
    }

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply()
    }
}
