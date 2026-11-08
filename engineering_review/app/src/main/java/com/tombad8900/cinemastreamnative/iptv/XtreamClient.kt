package com.tombad8900.cinemastreamnative.iptv

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class XtreamCategory(
    val id: String,
    val name: String
)

data class XtreamStream(
    val id: String,
    val name: String,
    val logo: String,
    val categoryId: String,
    val epgId: String,
    val url: String
)

class XtreamClient(
    host: String,
    private val username: String,
    private val password: String
) {
    private val baseUrl: String

    init {
        val normalized = host.trim().trimEnd('/')
        require(
            normalized.startsWith("https://", true) ||
                normalized.startsWith("http://", true)
        ) { "عنوان الخادم يجب أن يبدأ بـ http أو https" }

        baseUrl = normalized
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun request(action: String? = null): String {
        val query = buildString {
            append("username=").append(encode(username))
            append("&password="REDACTED"&action=").append(encode(action))
            }
        }

        val connection = URL("$baseUrl/player_api.php?$query")
            .openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "CinemaStream/1.0")

            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                throw IllegalStateException("خادم IPTV أعاد رمز HTTP: $code")
            }

            return body
        } finally {
            connection.disconnect()
        }
    }

    fun testConnection(): Boolean {
        val response = request()
        val json = org.json.JSONObject(response)
        val info = json.optJSONObject("user_info")
            ?: throw IllegalStateException("استجابة الخادم غير معروفة")

        if (info.optString("auth") != "1") {
            throw IllegalStateException("بيانات الاشتراك غير صحيحة أو غير مفعلة")
        }

        return true
    }

    fun getLiveCategories(): List<XtreamCategory> {
        val array = JSONArray(request("get_live_categories"))
        val result = mutableListOf<XtreamCategory>()

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("category_id")
            val name = item.optString("category_name")

            if (id.isNotBlank() && name.isNotBlank()) {
                result.add(XtreamCategory(id, name))
            }
        }

        return result
    }

    fun getLiveStreams(categoryId: String? = null): List<XtreamStream> {
        val action = if (categoryId.isNullOrBlank()) {
            "get_live_streams"
        } else {
            "get_live_streams&category_id=${encode(categoryId)}"
        }

        val response = if (categoryId.isNullOrBlank()) {
            request("get_live_streams")
        } else {
            requestWithCategory(categoryId)
        }

        val array = JSONArray(response)
        val result = mutableListOf<XtreamStream>()

        val encodedUser = encodePath(username)
        val encodedPassword = encodePath(password)

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("stream_id")
            val name = item.optString("name", "قناة")

            if (id.isBlank()) continue

            val extension = item.optString("container_extension")
                .ifBlank { "ts" }

            val streamUrl =
                "$baseUrl/live/$encodedUser/$encodedPassword/$id.$extension"

            result.add(
                XtreamStream(
                    id = id,
                    name = name,
                    logo = item.optString("stream_icon"),
                    categoryId = item.optString("category_id"),
                    epgId = item.optString("epg_channel_id"),
                    url = streamUrl
                )
            )
        }

        return result
    }

    private fun requestWithCategory(categoryId: String): String {
        val query = buildString {
            append("username=").append(encode(username))
            append("&password="REDACTED"&action=get_live_streams")
            append("&category_id=").append(encode(categoryId))
        }

        val connection = URL("$baseUrl/player_api.php?$query")
            .openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "CinemaStream/1.0")

            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                throw IllegalStateException("خادم IPTV أعاد رمز HTTP: $code")
            }

            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun encodePath(value: String): String =
        encode(value).replace("+", "%20")
}
