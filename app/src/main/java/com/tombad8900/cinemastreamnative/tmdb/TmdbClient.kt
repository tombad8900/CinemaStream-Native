package com.tombad8900.cinemastreamnative.tmdb

import android.content.Context

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class TmdbMedia(
    val id: Int,
    val title: String,
    val originalTitle: String = "",
    val mediaType: String,
    val year: String,
    val overview: String,
    val posterPath: String?,
    val rating: Double,
    val popularity: Double
) {
    val posterUrl: String?
        get() = posterPath?.let {
            "https://image.tmdb.org/t/p/w342$it"
        }
}

data class TmdbCompanySection(
    val name: String,
    val items: List<TmdbMedia>
)

data class TmdbHomeData(
    val trending: List<TmdbMedia>,
    val popular: List<TmdbMedia>,
    val topRated: List<TmdbMedia>,
    val companies: List<TmdbCompanySection> = emptyList()
)


data class TmdbSeason(
    val number: Int,
    val name: String,
    val episodeCount: Int
)

data class TmdbEpisode(
    val number: Int,
    val name: String,
    val overview: String,
    val stillPath: String?,
    val rating: Double
) {
    val stillUrl: String?
        get() = stillPath?.let { "https://image.tmdb.org/t/p/w342$it" }
}

data class TmdbDetails(
    val title: String,
    val overview: String,
    val year: String,
    val rating: Double,
    val backdropPath: String?,
    val genres: List<String>,
    val runtime: Int?,
    val seasons: List<TmdbSeason>
)

object TmdbClient {

    private const val HOME_CACHE_TTL = 30 * 60 * 1000L
    private val homeCache = ConcurrentHashMap<String, Pair<Long, TmdbHomeData>>()


    private const val HOME_PREFS = "tmdb_home_cache"

    private fun diskKey(apiKey: String, filter: String) =
        "home_${apiKey.hashCode()}_${filter.lowercase()}"

    fun cachedHome(context: Context, apiKey: String, filter: String): TmdbHomeData? {
        if (apiKey.isBlank()) return null
        val key = "${apiKey.hashCode()}:${filter.lowercase()}"
        homeCache[key]?.let { return it.second }

        val raw = context.getSharedPreferences(HOME_PREFS, Context.MODE_PRIVATE)
            .getString(diskKey(apiKey, filter), null) ?: return null
        val result = runCatching { decodeHome(JSONObject(raw)) }.getOrNull() ?: return null
        homeCache[key] = System.currentTimeMillis() to result
        return result
    }

    private fun encodeMedia(items: List<TmdbMedia>): org.json.JSONArray {
        val array = org.json.JSONArray()
        items.forEach { media ->
            array.put(JSONObject().apply {
                put("id", media.id)
                put("title", media.title)
                put("type", media.mediaType)
                put("year", media.year)
                put("overview", media.overview)
                put("poster", media.posterPath ?: JSONObject.NULL)
                put("rating", media.rating)
                put("popularity", media.popularity)
            })
        }
        return array
    }

    private fun decodeMedia(array: org.json.JSONArray?): List<TmdbMedia> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val type = item.optString("type")
                if (type != "movie" && type != "tv") continue
                val poster = item.opt("poster")
                add(TmdbMedia(
                    id = item.optInt("id"),
                    title = item.optString("title"),
                    mediaType = type,
                    year = item.optString("year"),
                    overview = item.optString("overview"),
                    posterPath = if (poster == null || poster === JSONObject.NULL) null else poster.toString(),
                    rating = item.optDouble("rating"),
                    popularity = item.optDouble("popularity")
                ))
            }
        }
    }

    private fun encodeHome(data: TmdbHomeData): JSONObject {
        val companies = org.json.JSONArray()
        data.companies.forEach { company ->
            companies.put(JSONObject().apply {
                put("name", company.name)
                put("items", encodeMedia(company.items))
            })
        }
        return JSONObject().apply {
            put("trending", encodeMedia(data.trending))
            put("popular", encodeMedia(data.popular))
            put("top", encodeMedia(data.topRated))
            put("companies", companies)
        }
    }

    private fun decodeHome(json: JSONObject): TmdbHomeData {
        val array = json.optJSONArray("companies")
        val companies = buildList {
            if (array != null) for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(TmdbCompanySection(
                    item.optString("name"),
                    decodeMedia(item.optJSONArray("items"))
                ))
            }
        }
        return TmdbHomeData(
            trending = decodeMedia(json.optJSONArray("trending")),
            popular = decodeMedia(json.optJSONArray("popular")),
            topRated = decodeMedia(json.optJSONArray("top")),
            companies = companies
        )
    }

    private const val BASE_URL = "https://api.themoviedb.org/3"

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun request(
        path: String,
        apiKey: String,
        extraParams: Map<String, String> = emptyMap()
    ): JSONObject {
        val params = mutableListOf(
            "api_key=${encode(apiKey)}",
            "language=ar-SA"
        )

        extraParams.forEach { (key, value) ->
            params.add("${encode(key)}=${encode(value)}")
        }

        val connection = URL(
            "$BASE_URL$path?${params.joinToString("&")}"
        ).openConnection() as HttpURLConnection

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

            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (status !in 200..299) {
                val message = runCatching {
                    JSONObject(body).optString("status_message")
                }.getOrNull().orEmpty()

                throw Exception(
                    message.ifBlank { "فشل الاتصال بـ TMDB ($status)" }
                )
            }

            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResults(
        json: JSONObject,
        forcedType: String? = null
    ): List<TmdbMedia> {
        val results = json.optJSONArray("results") ?: return emptyList()
        val items = mutableListOf<TmdbMedia>()

        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            val type = forcedType
                ?: item.optString("media_type").takeIf {
                    it == "movie" || it == "tv"
                }
                ?: continue

            val title = if (type == "movie") {
                item.optString("title")
            } else {
                item.optString("name")
            }
            val originalTitle = if (type == "movie") {
                item.optString("original_title").ifBlank { title }
            } else {
                item.optString("original_name").ifBlank { title }
            }

            if (title.isBlank()) continue

            val date = if (type == "movie") {
                item.optString("release_date")
            } else {
                item.optString("first_air_date")
            }

            items.add(
                TmdbMedia(
                    id = item.optInt("id"),
                    title = title,
                    originalTitle = originalTitle,
                    mediaType = type,
                    year = date.take(4),
                    overview = item.optString("overview"),
                    posterPath = item.optString("poster_path")
                        .takeIf { it.isNotBlank() && it != "null" },
                    rating = item.optDouble("vote_average", 0.0),
                    popularity = item.optDouble("popularity", 0.0)
                )
            )
        }

        return items
    }

    private fun fetchList(
        apiKey: String,
        path: String,
        forcedType: String? = null
    ): List<TmdbMedia> {
        return parseResults(request(path, apiKey), forcedType)
    }

    private fun fetchCombined(
        apiKey: String,
        moviePath: String,
        tvPath: String,
        filter: String,
        sortByRating: Boolean = false
    ): List<TmdbMedia> {
        val items = when (filter) {
            "movie" -> fetchList(apiKey, moviePath, "movie")
            "tv" -> fetchList(apiKey, tvPath, "tv")
            else -> fetchList(apiKey, moviePath, "movie") +
                fetchList(apiKey, tvPath, "tv")
        }

        return if (sortByRating) {
            items.sortedByDescending { it.rating }
        } else {
            items.sortedByDescending { it.popularity }
        }
    }

    suspend fun testKey(apiKey: String): Boolean =
        withContext(Dispatchers.IO) {
            request("/configuration", apiKey)
            true
        }


    private fun fetchCompanySection(
        apiKey: String,
        companyName: String,
        filter: String
    ): TmdbCompanySection {
        val search = request(
            "/search/company",
            apiKey,
            mapOf("query" to companyName)
        )
        val results = search.optJSONArray("results")
        var companyId = 0

        if (results != null) {
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val name = item.optString("name")
                if (name.equals(companyName, ignoreCase = true)) {
                    companyId = item.optInt("id")
                    break
                }
            }
        }

        if (companyId == 0) {
            return TmdbCompanySection(companyName, emptyList())
        }

        val movies = if (filter != "tv") {
            parseResults(
                request(
                    "/discover/movie",
                    apiKey,
                    mapOf(
                        "with_companies" to companyId.toString(),
                        "sort_by" to "primary_release_date.desc",
                        "include_adult" to "false"
                    )
                ),
                "movie"
            )
        } else emptyList()

        val shows = if (filter != "movie") {
            parseResults(
                request(
                    "/discover/tv",
                    apiKey,
                    mapOf(
                        "with_companies" to companyId.toString(),
                        "sort_by" to "first_air_date.desc",
                        "include_adult" to "false"
                    )
                ),
                "tv"
            )
        } else emptyList()

        return TmdbCompanySection(
            companyName,
            (movies + shows).sortedByDescending { it.year }.take(15)
        )
    }

    private fun fetchCompanySections(
        apiKey: String,
        filter: String
    ): List<TmdbCompanySection> {
        val names = listOf(
            "Netflix",
            "Walt Disney Pictures",
            "Warner Bros. Pictures",
            "Universal Pictures"
        )

        return names.mapNotNull { name ->
            runCatching {
                fetchCompanySection(apiKey, name, filter)
            }.getOrNull()?.takeIf { it.items.isNotEmpty() }
        }
    }

    suspend fun fetchHome(
        context: Context,
        apiKey: String,
        filter: String = "all"
    ): TmdbHomeData = withContext(Dispatchers.IO) {
        val type = filter.lowercase().let {
            if (it == "movie" || it == "tv") it else "all"
        }
        val cacheKey = "${apiKey.hashCode()}:$type"

        val trendingPath = when (type) {
            "movie" -> "/trending/movie/week"
            "tv" -> "/trending/tv/week"
            else -> "/trending/all/week"
        }

        val result = coroutineScope {
            val trendingType = if (type == "all") null else type
            val trendingJob = async { fetchList(apiKey, trendingPath, trendingType) }
            val popularJob = async {
                fetchCombined(apiKey, "/movie/popular", "/tv/popular", type)
            }
            val topRatedJob = async {
                fetchCombined(
                    apiKey, "/movie/top_rated", "/tv/top_rated",
                    type, sortByRating = true
                )
            }
            val companiesJob = async { fetchCompanySections(apiKey, type) }

            TmdbHomeData(
                trending = trendingJob.await(),
                popular = popularJob.await(),
                topRated = topRatedJob.await(),
                companies = companiesJob.await()
            )
        }

        homeCache[cacheKey] = System.currentTimeMillis() to result
        runCatching {
            context.getSharedPreferences(HOME_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(diskKey(apiKey, type), encodeHome(result).toString())
                .apply()
        }
        result
    }


    suspend fun fetchDetails(
        apiKey: String,
        media: TmdbMedia
    ): TmdbDetails = withContext(Dispatchers.IO) {
        val type = if (media.mediaType == "tv") "tv" else "movie"
        val json = request("/$type/${media.id}", apiKey)

        val genresJson = json.optJSONArray("genres")
        val genres = buildList {
            if (genresJson != null) {
                for (i in 0 until genresJson.length()) {
                    val name = genresJson.optJSONObject(i)?.optString("name").orEmpty()
                    if (name.isNotBlank()) add(name)
                }
            }
        }

        val seasonsJson = json.optJSONArray("seasons")
        val seasons = buildList {
            if (seasonsJson != null) {
                for (i in 0 until seasonsJson.length()) {
                    val item = seasonsJson.optJSONObject(i) ?: continue
                    val number = item.optInt("season_number", -1)
                    if (number >= 0) {
                        add(
                            TmdbSeason(
                                number = number,
                                name = item.optString("name", "الموسم $number"),
                                episodeCount = item.optInt("episode_count")
                            )
                        )
                    }
                }
            }
        }

        val date = if (type == "movie") {
            json.optString("release_date")
        } else {
            json.optString("first_air_date")
        }

        val runtime = if (type == "movie") {
            json.optInt("runtime").takeIf { it > 0 }
        } else {
            json.optJSONArray("episode_run_time")
                ?.optInt(0, 0)?.takeIf { it > 0 }
        }

        TmdbDetails(
            title = json.optString(
                if (type == "movie") "title" else "name",
                media.title
            ),
            overview = json.optString("overview").ifBlank { media.overview },
            year = date.take(4).ifBlank { media.year },
            rating = json.optDouble("vote_average", media.rating),
            backdropPath = json.optString("backdrop_path")
                .takeIf { it.isNotBlank() && it != "null" },
            genres = genres,
            runtime = runtime,
            seasons = seasons
        )
    }

    suspend fun fetchSeasonEpisodes(
        apiKey: String,
        tvId: Int,
        seasonNumber: Int
    ): List<TmdbEpisode> = withContext(Dispatchers.IO) {
        val json = request("/tv/$tvId/season/$seasonNumber", apiKey)
        val array = json.optJSONArray("episodes") ?: return@withContext emptyList()

        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    TmdbEpisode(
                        number = item.optInt("episode_number"),
                        name = item.optString("name").ifBlank { "الحلقة ${i + 1}" },
                        overview = item.optString("overview"),
                        stillPath = item.optString("still_path")
                            .takeIf { it.isNotBlank() && it != "null" },
                        rating = item.optDouble("vote_average", 0.0)
                    )
                )
            }
        }
    }

    suspend fun search(
        apiKey: String,
        query: String,
        filter: String = "all"
    ): List<TmdbMedia> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val type = filter.lowercase().let {
            if (it == "movie" || it == "tv") it else "all"
        }

        val path = when (type) {
            "movie" -> "/search/movie"
            "tv" -> "/search/tv"
            else -> "/search/multi"
        }

        parseResults(
            request(path, apiKey, mapOf("query" to query)),
            if (type == "all") null else type
        )
    }
}
