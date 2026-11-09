package com.tombad8900.cinemastreamnative

import android.content.Context
import com.tombad8900.cinemastreamnative.tmdb.TmdbMedia
import org.json.JSONArray
import org.json.JSONObject

object WatchLaterStore {
    private const val PREFS = "cinema_watch_later"
    private const val KEY = "items"

    fun load(context: Context): List<TmdbMedia> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(TmdbMedia(
                    id = item.optInt("id"),
                    title = item.optString("title"),
                    originalTitle = item.optString("originalTitle"),
                    mediaType = item.optString("mediaType", "movie"),
                    year = item.optString("year"),
                    overview = item.optString("overview"),
                    posterPath = item.optString("posterPath").takeIf { it.isNotBlank() && it != "null" },
                    rating = item.optDouble("rating", 0.0),
                    popularity = item.optDouble("popularity", 0.0),
                    backdropPath = item.optString("backdropPath").takeIf { it.isNotBlank() && it != "null" }
                ))
            }
        }
    }.getOrDefault(emptyList())

    fun save(context: Context, items: List<TmdbMedia>) {
        val array = JSONArray()
        items.distinctBy { "${it.mediaType}:${it.id}" }.forEach { media ->
            array.put(JSONObject().apply {
                put("id", media.id)
                put("title", media.title)
                put("originalTitle", media.originalTitle)
                put("mediaType", media.mediaType)
                put("year", media.year)
                put("overview", media.overview)
                put("posterPath", media.posterPath)
                put("rating", media.rating)
                put("popularity", media.popularity)
                put("backdropPath", media.backdropPath)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).apply()
    }
}
