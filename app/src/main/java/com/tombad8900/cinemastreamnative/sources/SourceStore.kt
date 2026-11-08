package com.tombad8900.cinemastreamnative.sources

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class StreamSource(
    val id: String,
    val name: String,
    val movie: String,
    val tv: String,
    val enabled: Boolean = true,
    val hidden: Boolean = false,
    val priority: Int = 99,
    val notes: String = ""
)

object SourceStore {
    private const val PREFS = "cinemastream_sources"
    private const val KEY = "servers"

    fun load(context: Context): List<StreamSource> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return try {
            parse(JSONArray(raw))
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, sources: List<StreamSource>) {
        val array = JSONArray()
        sources.forEach { source ->
            array.put(JSONObject().apply {
                put("id", source.id)
                put("name", source.name)
                put("movie", source.movie)
                put("tv", source.tv)
                put("enabled", source.enabled)
                put("hidden", source.hidden)
                put("priority", source.priority)
                put("notes", source.notes)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).apply()
    }

    fun importJson(context: Context, json: String): Int {
        val root = JSONObject(json)
        val data = root.optJSONObject("data") ?: root
        val servers = data.optJSONArray("servers")
            ?: throw IllegalArgumentException("ملف JSON لا يحتوي على data.servers")

        val imported = parse(servers)
        if (imported.isEmpty()) {
            throw IllegalArgumentException("لم يتم العثور على مصادر صالحة في الملف")
        }

        val current = load(context).associateBy { it.id }.toMutableMap()
        imported.forEach { current[it.id] = it }

        save(context, current.values.sortedBy { it.priority })
        return imported.size
    }

    private fun parse(array: JSONArray): List<StreamSource> {
        val result = mutableListOf<StreamSource>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            val name = item.optString("name").trim()
            val movie = item.optString("movie").trim()
            val tv = item.optString("tv").trim()

            if (id.isBlank() || name.isBlank() ||
                (movie.isBlank() && tv.isBlank())) continue

            result.add(
                StreamSource(
                    id = id,
                    name = name,
                    movie = movie,
                    tv = tv,
                    enabled = item.optBoolean("enabled", true),
                    hidden = item.optBoolean("hidden", false),
                    priority = item.optInt("priority", 99),
                    notes = item.optString("notes", "")
                )
            )
        }
        return result.distinctBy { it.id }
    }
}
