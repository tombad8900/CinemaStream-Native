package com.tombad8900.cinemastreamnative.tmdb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class TmdbPerson(
    val id: Int,
    val name: String,
    val biography: String,
    val profilePath: String?,
    val knownFor: List<TmdbMedia>,
    val knownForLanguages: List<String> = emptyList()
) {
    val photoUrl: String?
        get() = profilePath?.let {
            "https://image.tmdb.org/t/p/w342$it"
        }
}

data class TmdbCollection(
    val id: Int,
    val name: String,
    val overview: String,
    val posterPath: String?,
    val backdropPath: String?,
    val movies: List<TmdbMedia>
)

data class TmdbMovieCredits(
    val cast: List<TmdbPerson>,
    val similar: List<TmdbMedia>,
    val recommendations: List<TmdbMedia>
)

object TmdbLibraryClient {

    private const val BASE_URL = "https://api.themoviedb.org/3"

    private fun request(
        path: String,
        apiKey: String,
        params: Map<String, String> = emptyMap()
    ): JSONObject {
        val query = mutableListOf(
            "api_key=${java.net.URLEncoder.encode(apiKey, "UTF-8")}",
            "language=ar-SA"
        )

        params.forEach { (key, value) ->
            query.add(
                "${java.net.URLEncoder.encode(key, "UTF-8")}=" +
                    java.net.URLEncoder.encode(value, "UTF-8")
            )
        }

        val connection = java.net.URL(
            "$BASE_URL$path?${query.joinToString("&")}"
        ).openConnection() as java.net.HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream?.bufferedReader()?.use {
                it.readText()
            }.orEmpty()

            if (status !in 200..299) {
                throw Exception(
                    runCatching {
                        JSONObject(body).optString("status_message")
                    }.getOrNull().orEmpty().ifBlank {
                        "فشل الاتصال بـ TMDB ($status)"
                    }
                )
            }

            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseMovies(json: JSONObject): List<TmdbMedia> {
        val results = json.optJSONArray("results") ?: return emptyList()

        return buildList {
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val title = item.optString("title")
                    .ifBlank { item.optString("name") }

                if (title.isBlank()) continue

                val date = item.optString("release_date")
                    .ifBlank { item.optString("first_air_date") }

                add(
                    TmdbMedia(
                        id = item.optInt("id"),
                        title = title,
                        originalTitle = item.optString("original_title")
                            .ifBlank { item.optString("original_name", title) },
                        mediaType = "movie",
                        year = date.take(4),
                        overview = item.optString("overview"),
                        posterPath = item.optString("poster_path")
                            .takeIf { it.isNotBlank() && it != "null" },
                        rating = item.optDouble("vote_average"),
                        popularity = item.optDouble("popularity"),
                        backdropPath = item.optString("backdrop_path")
                            .takeIf { it.isNotBlank() && it != "null" }
                    )
                )
            }
        }
    }

    private fun parsePeople(json: JSONObject): List<TmdbPerson> {
        val results = json.optJSONArray("results") ?: return emptyList()

        return buildList {
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val name = item.optString("name")
                if (name.isBlank()) continue

                val known = item.optJSONArray("known_for")
                val languages = buildList {
                    if (known != null) {
                        for (j in 0 until known.length()) {
                            val work = known.optJSONObject(j) ?: continue
                            val language = work.optString("original_language")
                            if (language.isNotBlank()) add(language)
                        }
                    }
                }
                val works = buildList {
                    if (known != null) {
                        for (j in 0 until known.length()) {
                            val work = known.optJSONObject(j) ?: continue
                            val type = work.optString("media_type")
                            if (type != "movie" && type != "tv") continue

                            val title = if (type == "movie") {
                                work.optString("title")
                            } else {
                                work.optString("name")
                            }
                            if (title.isBlank()) continue

                            val date = if (type == "movie") {
                                work.optString("release_date")
                            } else {
                                work.optString("first_air_date")
                            }

                            add(
                                TmdbMedia(
                                    id = work.optInt("id"),
                                    title = title,
                                    originalTitle = title,
                                    mediaType = type,
                                    year = date.take(4),
                                    overview = work.optString("overview"),
                                    posterPath = work.optString("poster_path")
                                        .takeIf { it.isNotBlank() && it != "null" },
                                    rating = work.optDouble("vote_average"),
                                    popularity = work.optDouble("popularity"),
                                    backdropPath = null
                                )
                            )
                        }
                    }
                }

                add(
                    TmdbPerson(
                        id = item.optInt("id"),
                        name = name,
                        biography = "",
                        profilePath = item.optString("profile_path")
                            .takeIf { it.isNotBlank() && it != "null" },
                        knownFor = works,
                        knownForLanguages = languages
                    )
                )
            }
        }
    }

    suspend fun popularPeople(
        apiKey: String,
        page: Int = 1
    ): List<TmdbPerson> = withContext(Dispatchers.IO) {
        parsePeople(
            request(
                "/person/popular",
                apiKey,
                mapOf("page" to page.toString())
            )
        )
    }

    suspend fun searchPeople(
        apiKey: String,
        query: String
    ): List<TmdbPerson> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        parsePeople(
            request(
                "/search/person",
                apiKey,
                mapOf("query" to query)
            )
        )
    }

    suspend fun personDetails(
        apiKey: String,
        personId: Int
    ): TmdbPerson = withContext(Dispatchers.IO) {
        val json = request("/person/$personId", apiKey)
        val credits = request("/person/$personId/movie_credits", apiKey)
        val castJson = JSONObject().put(
            "results",
            credits.optJSONArray("cast")
        )

        val works = parseMovies(castJson).distinctBy { it.id }

        TmdbPerson(
            id = json.optInt("id"),
            name = json.optString("name"),
            biography = json.optString("biography"),
            profilePath = json.optString("profile_path")
                .takeIf { it.isNotBlank() && it != "null" },
            knownFor = works
        )
    }

    suspend fun searchCollections(
        apiKey: String,
        query: String
    ): List<TmdbCollection> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val json = request(
            "/search/collection",
            apiKey,
            mapOf("query" to query)
        )

        val results = json.optJSONArray("results") ?: return@withContext emptyList()

        buildList {
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                add(
                    TmdbCollection(
                        id = item.optInt("id"),
                        name = item.optString("name"),
                        overview = item.optString("overview"),
                        posterPath = item.optString("poster_path")
                            .takeIf { it.isNotBlank() && it != "null" },
                        backdropPath = item.optString("backdrop_path")
                            .takeIf { it.isNotBlank() && it != "null" },
                        movies = emptyList()
                    )
                )
            }
        }
    }

    suspend fun collectionDetails(
        apiKey: String,
        collectionId: Int
    ): TmdbCollection = withContext(Dispatchers.IO) {
        val json = request("/collection/$collectionId", apiKey)
        val partsJson = json.optJSONObject("parts")
        val parts = parseMovies(
            JSONObject().put(
                "results",
                json.optJSONArray("parts")
            )
        ).sortedWith(
            compareBy<TmdbMedia> { it.year.ifBlank { "9999" } }
                .thenBy { it.title }
        )

        TmdbCollection(
            id = json.optInt("id"),
            name = json.optString("name"),
            overview = json.optString("overview"),
            posterPath = json.optString("poster_path")
                .takeIf { it.isNotBlank() && it != "null" },
            backdropPath = json.optString("backdrop_path")
                .takeIf { it.isNotBlank() && it != "null" },
            movies = parts
        )
    }

    suspend fun movieCredits(
        apiKey: String,
        movieId: Int
    ): TmdbMovieCredits = withContext(Dispatchers.IO) {
        val credits = request("/movie/$movieId/credits", apiKey)
        val castArray = credits.optJSONArray("cast")
        val cast = buildList {
            if (castArray != null) {
                for (i in 0 until minOf(castArray.length(), 20)) {
                    val person = castArray.optJSONObject(i) ?: continue
                    val name = person.optString("name")
                    if (name.isBlank()) continue

                    add(
                        TmdbPerson(
                            id = person.optInt("id"),
                            name = name,
                            biography = "",
                            profilePath = person.optString("profile_path")
                                .takeIf { it.isNotBlank() && it != "null" },
                            knownFor = emptyList()
                        )
                    )
                }
            }
        }

        TmdbMovieCredits(
            cast = cast,
            similar = parseMovies(request("/movie/$movieId/similar", apiKey)),
            recommendations = parseMovies(
                request("/movie/$movieId/recommendations", apiKey)
            )
        )
    }
}
