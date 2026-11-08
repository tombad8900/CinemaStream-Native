package com.tombad8900.cinemastreamnative

import org.json.JSONObject

data class PlaybackMetadata(
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val contentType: String = "movie",
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val sourceTitle: String? = null
) {

    val isEpisode: Boolean
        get() = contentType == "tv" &&
            seasonNumber != null && episodeNumber != null

    fun searchQueries(): List<String> {
        val names = listOfNotNull(
            originalTitle?.takeIf { it.isNotBlank() },
            title.takeIf { it.isNotBlank() }
        ).distinct()

        if (names.isEmpty()) return emptyList()

        if (isEpisode) {
            val season = seasonNumber!!
            val episode = episodeNumber!!
            val seasonText = season.toString().padStart(2, '0')
            val episodeText = episode.toString().padStart(2, '0')

            return names.flatMap { name ->
                listOf(
                    "$name S${seasonText}E${episodeText}",
                    "$name ${season}x${episodeText}",
                    "$name Season $season Episode $episode"
                )
            }.distinct()
        }

        return names.map { name ->
            if (year != null) "$name $year" else name
        }.distinct()
    }

    fun toJson(): String {
        val json = JSONObject()
        json.put("title", title)
        json.put("originalTitle", originalTitle)
        json.put("year", year)
        json.put("tmdbId", tmdbId)
        json.put("imdbId", imdbId)
        json.put("contentType", contentType)
        json.put("seasonNumber", seasonNumber)
        json.put("episodeNumber", episodeNumber)
        json.put("episodeTitle", episodeTitle)
        json.put("sourceTitle", sourceTitle)
        return json.toString()
    }

    companion object {
        fun fromJson(value: String?): PlaybackMetadata? {
            if (value.isNullOrBlank()) return null

            return runCatching {
                val json = JSONObject(value)

                PlaybackMetadata(
                    title = json.optString("title").takeIf { it.isNotBlank() }
                        ?: return null,
                    originalTitle = json.optString("originalTitle")
                        .takeIf { it.isNotBlank() && it != "null" },
                    year = json.optionalInt("year"),
                    tmdbId = json.optionalInt("tmdbId"),
                    imdbId = json.optString("imdbId")
                        .takeIf { it.isNotBlank() && it != "null" },
                    contentType = json.optString("contentType", "movie"),
                    seasonNumber = json.optionalInt("seasonNumber"),
                    episodeNumber = json.optionalInt("episodeNumber"),
                    episodeTitle = json.optString("episodeTitle")
                        .takeIf { it.isNotBlank() && it != "null" },
                    sourceTitle = json.optString("sourceTitle")
                        .takeIf { it.isNotBlank() && it != "null" }
                )
            }.getOrNull()
        }

        private fun JSONObject.optionalInt(key: String): Int? {
            if (!has(key) || isNull(key)) return null
            val value = optInt(key, -1)
            return value.takeIf { it >= 0 }
        }

        fun inferEpisode(
            metadata: PlaybackMetadata,
            text: String
        ): PlaybackMetadata {
            if (metadata.isEpisode || metadata.contentType != "tv") {
                return metadata
            }

            val patterns = listOf(
                Regex("""(?i)\bS(\d{1,2})\s*E(\d{1,3})\b"""),
                Regex("""(?i)\b(\d{1,2})\s*x\s*(\d{1,3})\b"""),
                Regex("""(?i)\bSeason[\s._-]*(\d{1,2})[\s._-]*Episode[\s._-]*(\d{1,3})\b""")
            )

            for (pattern in patterns) {
                val match = pattern.find(text) ?: continue
                val season = match.groupValues[1].toIntOrNull() ?: continue
                val episode = match.groupValues[2].toIntOrNull() ?: continue

                return metadata.copy(
                    seasonNumber = season,
                    episodeNumber = episode
                )
            }

            return metadata
        }
    }
}
