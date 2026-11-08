package com.tombad8900.cinemastreamnative.subtitle

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class OpenSubtitleResult(
    val fileId: Long,
    val name: String,
    val language: String,
    val format: String,
    val release: String
)

object OpenSubtitlesClient {
    private const val BASE = "https://api.opensubtitles.com/api/v1"
    private const val USER_AGENT = "CinemaStreamNative v1.0"

    fun search(
        context: Context,
        query: String,
        language: String = "ar",
        imdbId: String? = null,
        type: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null
    ): List<OpenSubtitleResult> {
        val key = SubtitleApiKeyStore.get(context, "opensubtitles")
            ?: throw IllegalStateException("أضف مفتاح OpenSubtitles من الإعدادات")

        val params = linkedMapOf(
            "languages" to language,
            "order_by" to "download_count",
            "order_direction" to "desc"
        )
        if (type == "episode" && !imdbId.isNullOrBlank()) {
            params["parent_imdb_id"] = imdbId
        } else if (!imdbId.isNullOrBlank()) {
            params["imdb_id"] = imdbId
        } else {
            params["query"] = query
        }

        if (!type.isNullOrBlank() && type != "unknown") {
            params["type"] = type
        }
        if (year != null) params["year"] = year.toString()
        if (season != null) params["season_number"] = season.toString()
        if (episode != null) params["episode_number"] = episode.toString()

        val url = "$BASE/subtitles?" + params.entries.joinToString("&") {
            "${enc(it.key)}=${enc(it.value)}"
        }
        val json = request(url, key)
        val data = json.optJSONArray("data") ?: return emptyList()
        val results = mutableListOf<OpenSubtitleResult>()

        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val attrs = item.optJSONObject("attributes") ?: continue
            val files = attrs.optJSONArray("files") ?: continue
            val release = attrs.optString("release", "")
            val languageName = attrs.optString("language", language)
            for (j in 0 until files.length()) {
                val file = files.optJSONObject(j) ?: continue
                val id = file.optLong("file_id", 0L)
                if (id <= 0L) continue
                val name = file.optString("file_name", release.ifBlank { "Subtitle" })
                val ext = name.substringAfterLast('.', "srt").lowercase()
                if (ext !in listOf("srt", "vtt", "ass", "ssa", "ttml", "xml")) continue
                results.add(
                    OpenSubtitleResult(id, name, languageName, ext, release)
                )
            }
        }
        return results
    }

    fun download(context: Context, result: OpenSubtitleResult): File {
        val key = SubtitleApiKeyStore.get(context, "opensubtitles")
            ?: throw IllegalStateException("مفتاح OpenSubtitles غير محفوظ")

        val body = JSONObject().put("file_id", result.fileId).toString()
        val response = request(
            "$BASE/download",
            key,
            method = "POST",
            body = body
        )
        val link = response.optString("link")
        require(link.startsWith("https://")) { "رابط التنزيل غير صالح" }

        val uri = URL(link)
        require(uri.host == "dl.opensubtitles.com" ||
                uri.host.endsWith(".opensubtitles.com")) {
            "مصدر التنزيل غير موثوق"
        }

        return downloadFile(link, context, result.format)
    }

    private fun request(
        address: String,
        key: String,
        method: String = "GET",
        body: String? = null
    ): JSONObject {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Api-Key", key)
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) connection.outputStream.use {
                it.write(body.toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream
                         else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("OpenSubtitles HTTP $code: ${text.take(250)}")
            }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadFile(address: String, context: Context, ext: String): File {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
        }
        try {
            require(connection.responseCode in 200..299) { "فشل تنزيل الترجمة" }
            val finalUrl = URL(connection.url.toString())
            require(finalUrl.protocol == "https" &&
                    (finalUrl.host == "dl.opensubtitles.com" ||
                     finalUrl.host.endsWith(".opensubtitles.com"))) {
                "وجهة التنزيل غير موثوقة"
            }
            val file = File.createTempFile("opensub_", ".$ext", context.cacheDir)
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 12 * 1024 * 1024) {
                            "حجم الترجمة أكبر من الحد المسموح"
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            return file
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(value: String) =
        URLEncoder.encode(value, "UTF-8")
}
