package com.tombad8900.cinemastreamnative.subtitle

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import org.json.JSONObject

data class SubdlSubtitle(
    val name: String,
    val language: String,
    val format: String,
    val releaseName: String,
    val season: Int?,
    val episode: Int?,
    val hearingImpaired: Boolean,
    val size: Long,
    val downloadUrl: String
)

object SubdlClient {

    private const val SEARCH_URL =
        "https://api.subdl.com/api/v1/subtitles"

    private const val DOWNLOAD_HOST =
        "https://dl.subdl.com"

    private const val MAX_DOWNLOAD_SIZE =
        12L * 1024L * 1024L

    private val supportedFormats =
        setOf("srt", "vtt", "ttml", "xml", "ass", "ssa")

    fun search(
        apiKey: String,
        title: String,
        type: String? = null,
        language: String = "AR",
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        tmdbId: Int? = null,
        imdbId: String? = null
    ): List<SubdlSubtitle> {

        require(apiKey.isNotBlank()) {
            "أدخل مفتاح SubDL أولاً"
        }

        require(title.isNotBlank()) {
            "أدخل اسم الفيلم أو المسلسل"
        }

        require(type == null || type == "movie" || type == "tv") {
            "نوع المحتوى غير صالح"
        }

        val params = linkedMapOf(
            "api_key" to apiKey.trim(),
            "film_name" to title.trim(),
            "languages" to language.uppercase(),
            "subs_per_page" to "30",
            "unpack" to "1",
            "client" to "custom_integration"
        )

        if (!type.isNullOrBlank()) {
            params["type"] = type
        }

        if (tmdbId != null && tmdbId > 0) {
            params["tmdb_id"] = tmdbId.toString()
        }
        if (!imdbId.isNullOrBlank()) {
            params["imdb_id"] = imdbId.trim()
        }
        if (year != null) params["year"] = year.toString()

        if (type == "tv") {
            if (season != null) {
                params["season_number"] = season.toString()
            }
            if (episode != null) {
                params["episode_number"] = episode.toString()
            }
        }

        val query = params.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }

        val connection = URL("$SEARCH_URL?$query")
            .openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("Accept", "application/json")

            val status = connection.responseCode

            if (status !in 200..299) {
                throw IllegalStateException(
                    "فشل البحث في SubDL (HTTP $status)"
                )
            }

            val response = connection.inputStream
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

            val json = JSONObject(response)

            if (!json.optBoolean("status", false)) {
                throw IllegalStateException(
                    json.optString("error", "تعذر البحث في SubDL")
                )
            }

            val subtitles = json.optJSONArray("subtitles")
                ?: return emptyList()

            val results = mutableListOf<SubdlSubtitle>()

            for (i in 0 until subtitles.length()) {
                val parent = subtitles.optJSONObject(i) ?: continue
                val files = parent.optJSONArray("unpack_files")
                    ?: continue

                for (j in 0 until files.length()) {
                    val item = files.optJSONObject(j) ?: continue

                    val format = item.optString("format")
                        .lowercase()
                        .removePrefix(".")

                    if (format !in supportedFormats) continue

                    val path = item.optString("url")
                    val safeUrl = when {
                        path.startsWith("/subtitle/") ->
                            "$DOWNLOAD_HOST$path"

                        path.startsWith("$DOWNLOAD_HOST/subtitle/") ->
                            path

                        else -> continue
                    }

                    results.add(
                        SubdlSubtitle(
                            name = item.optString("name", "subtitle.$format"),
                            language = item.optString("language", ""),
                            format = format,
                            releaseName = item.optString("release_name", ""),
                            season = if (item.has("season"))
                                item.optInt("season") else null,
                            episode = if (item.has("episode"))
                                item.optInt("episode") else null,
                            hearingImpaired = item.optBoolean("hi", false),
                            size = item.optLong("size", 0L),
                            downloadUrl = safeUrl
                        )
                    )
                }
            }

            return results.distinctBy { it.downloadUrl }

        } finally {
            connection.disconnect()
        }
    }

    fun download(
        context: Context,
        subtitle: SubdlSubtitle
    ): File {

        require(subtitle.format in supportedFormats) {
            "صيغة الترجمة غير مدعومة"
        }

        val url = URL(subtitle.downloadUrl)

        require(
            url.protocol == "https" &&
            url.host == "dl.subdl.com" &&
            url.path.startsWith("/subtitle/")
        ) {
            "رابط التنزيل غير صالح"
        }

        val connection = url.openConnection() as HttpURLConnection

        val directory = File(context.cacheDir, "subtitle_downloads")

        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("تعذر إنشاء مجلد التنزيل")
        }

        val destination = File(
            directory,
            "${UUID.randomUUID()}.${subtitle.format}"
        )

        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 25000
            connection.instanceFollowRedirects = true

            val status = connection.responseCode

            if (status !in 200..299) {
                throw IllegalStateException(
                    "فشل تنزيل الترجمة (HTTP $status)"
                )
            }

            val finalUrl = connection.url
            require(
                finalUrl.protocol == "https" &&
                finalUrl.host.equals("dl.subdl.com", ignoreCase = true) &&
                finalUrl.path.startsWith("/subtitle/")
            ) {
                "تم رفض تحويل رابط التنزيل إلى وجهة غير موثوقة"
            }

            val contentLength = connection.contentLengthLong

            if (contentLength > MAX_DOWNLOAD_SIZE) {
                throw IllegalArgumentException(
                    "حجم ملف الترجمة أكبر من 12 ميجابايت"
                )
            }

            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L

                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break

                        total += count

                        if (total > MAX_DOWNLOAD_SIZE) {
                            throw IllegalArgumentException(
                                "حجم ملف الترجمة أكبر من 12 ميجابايت"
                            )
                        }

                        output.write(buffer, 0, count)
                    }
                }
            }

            if (destination.length() == 0L) {
                throw IllegalStateException("الملف الذي تم تنزيله فارغ")
            }

            return destination

        } catch (error: Exception) {
            destination.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")
}
