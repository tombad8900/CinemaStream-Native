package com.tombad8900.cinemastreamnative.subtitle

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class WebsiteSubtitleResult(
    val id: String,
    val name: String,
    val language: String,
    val format: String,
    val release: String
)

object SubtitlesWebsiteClient {
    private const val API = "https://subtitles.website/strapi/api"
    private const val FILES = "https://subtitles.website/strapi"

    fun search(
        query: String,
        language: String = "ar",
        imdbId: String? = null
    ): List<WebsiteSubtitleResult> {
        require(query.isNotBlank() || !imdbId.isNullOrBlank()) {
            "أدخل اسم العمل أو معرّف IMDb"
        }

        val params = mutableListOf<String>()
        if (!imdbId.isNullOrBlank()) {
            params.add("imdb=${enc(imdbId)}")
        } else {
            params.add("title=${enc(query)}")
        }
        params.add("language=${enc(language)}")

        val json = getJson("$API/search/subtitles?${params.joinToString("&")}")
        val data = json.optJSONArray("data") ?: return emptyList()
        val results = mutableListOf<WebsiteSubtitleResult>()

        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val lang = item.optJSONObject("language")
            val ext = item.optString("format", "srt").lowercase()
            if (ext !in listOf("srt", "vtt", "ass", "ssa")) continue

            val id = item.optString("id")
            if (id.isBlank()) continue
            results.add(
                WebsiteSubtitleResult(
                    id = id,
                    name = item.optString("film_title", query),
                    language = lang?.optString("name", language) ?: language,
                    format = ext,
                    release = item.optString("release", "")
                )
            )
        }
        return results
    }

    fun download(context: Context, result: WebsiteSubtitleResult): File {
        require(result.id.matches(Regex("[A-Za-z0-9_-]{5,80}"))) {
            "معرّف الترجمة غير صالح"
        }
        val json = getJson("$API/search/download/${result.id}")
        val path = json.optString("download_url")
        require(path.isNotBlank()) { "لم يُرجع المصدر رابط التنزيل" }

        val address = if (path.startsWith("https://")) path
                      else FILES + "/" + path.trimStart('/')
        val uri = URL(address)
        require(uri.protocol == "https" &&
                (uri.host == "subtitles.website" ||
                 uri.host.endsWith(".subtitles.website"))) {
            "وجهة التنزيل غير موثوقة"
        }

        val connection = (uri.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
        }
        try {
            require(connection.responseCode in 200..299) {
                "فشل تنزيل الترجمة"
            }
            val finalUrl = URL(connection.url.toString())
            require(finalUrl.protocol == "https" &&
                    (finalUrl.host == "subtitles.website" ||
                     finalUrl.host.endsWith(".subtitles.website"))) {
                "وجهة التنزيل النهائية غير موثوقة"
            }

            val file = File.createTempFile(
                "website_subtitle_", ".${result.format}", context.cacheDir
            )
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

    private fun getJson(address: String): JSONObject {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "CinemaStreamNative/1.0")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream
                         else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("Subtitles.website HTTP $code: ${text.take(200)}")
            }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(value: String) =
        URLEncoder.encode(value, "UTF-8")
}
