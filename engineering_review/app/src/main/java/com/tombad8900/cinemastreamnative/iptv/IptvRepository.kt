package com.tombad8900.cinemastreamnative.iptv

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

data class IptvImportResult(
    val playlistId: Long,
    val playlistName: String,
    val channelCount: Int
)

class IptvRepository(context: Context) {

    private val database = IptvDatabase(context.applicationContext)

    fun getPlaylists(): List<IptvPlaylist> {
        return database.getPlaylists()
    }

    fun getChannels(playlistId: Long): List<IptvChannel> {
        return database.getChannels(playlistId)
    }

    fun deletePlaylist(playlistId: Long) {
        database.deletePlaylist(playlistId)
    }

    fun importM3uText(
        name: String,
        content: String,
        sourceUrl: String = ""
    ): IptvImportResult {
        val entries = M3uParser.parse(content)
        require(entries.isNotEmpty()) {
            "لم يتم العثور على قنوات صالحة في قائمة M3U"
        }

        val playlistName = name.trim().ifBlank { "قائمة IPTV" }
        val playlistId = database.savePlaylist(
            name = playlistName,
            type = "M3U",
            sourceUrl = sourceUrl
        )

        database.addChannels(playlistId, entries.toChannels(playlistId))

        return IptvImportResult(
            playlistId,
            playlistName,
            entries.size
        )
    }

    fun appendM3uText(
        playlistId: Long,
        content: String
    ): Int {
        requirePlaylistExists(playlistId)

        val entries = M3uParser.parse(content)
        require(entries.isNotEmpty()) {
            "لم يتم العثور على قنوات صالحة لإضافتها"
        }

        database.addChannels(playlistId, entries.toChannels(playlistId))
        return entries.size
    }

    fun importM3uFromUrl(
        name: String,
        url: String
    ): IptvImportResult {
        val content = downloadText(url)
        return importM3uText(name, content, url)
    }

    fun createDirectStream(
        name: String,
        url: String
    ): IptvImportResult {
        require(isHttpUrl(url)) { "رابط البث غير صالح" }

        val playlistName = name.trim().ifBlank { "بث مباشر" }
        val playlistId = database.savePlaylist(
            name = playlistName,
            type = "DIRECT",
            sourceUrl = url
        )

        database.addChannels(
            playlistId,
            listOf(
                IptvChannel(
                    playlistId = playlistId,
                    name = playlistName,
                    url = url
                )
            )
        )

        return IptvImportResult(playlistId, playlistName, 1)
    }

    fun addManualChannel(
        playlistId: Long,
        name: String,
        url: String,
        groupName: String = "عام",
        logo: String = ""
    ) {
        requirePlaylistExists(playlistId)
        require(name.isNotBlank()) { "اسم القناة مطلوب" }
        require(isHttpUrl(url)) { "رابط القناة غير صالح" }

        database.addChannels(
            playlistId,
            listOf(
                IptvChannel(
                    playlistId = playlistId,
                    name = name.trim(),
                    url = url.trim(),
                    groupName = groupName.trim().ifBlank { "عام" },
                    logo = logo.trim()
                )
            )
        )
    }

    fun importXtream(
        name: String,
        host: String,
        username: String,
        password: String
    ): IptvImportResult {
        require(username.isNotBlank() && password.isNotBlank()) {
            "اسم المستخدم وكلمة المرور مطلوبان"
        }

        val client = XtreamClient(host, username, password)
        client.testConnection()

        val categories = client.getLiveCategories()
        val categoryNames = categories.associate {
            it.id to it.name
        }

        val streams = client.getLiveStreams()
        require(streams.isNotEmpty()) {
            "تم الاتصال بالخادم، لكن لم يتم العثور على قنوات"
        }

        val playlistName = name.trim().ifBlank { "اشتراك Xtream" }
        val playlistId = database.savePlaylist(
            name = playlistName,
            type = "XTREAM",
            sourceUrl = host.trim().trimEnd('/'),
            usernameEncrypted = SecureCredentialStore.encrypt(username),
            passwordEncrypted = SecureCredentialStore.encrypt(password)
        )

        val channels = streams.map { stream ->
            IptvChannel(
                playlistId = playlistId,
                name = stream.name,
                url = stream.url,
                groupName = categoryNames[stream.categoryId] ?: "عام",
                logo = stream.logo,
                tvgId = stream.epgId
            )
        }

        database.addChannels(playlistId, channels)

        return IptvImportResult(
            playlistId,
            playlistName,
            channels.size
        )
    }

    fun refreshXtream(playlistId: Long): Int {
        val playlist = requirePlaylistExists(playlistId)
        require(playlist.type == "XTREAM") {
            "هذه القائمة ليست اشتراك Xtream"
        }

        val username = SecureCredentialStore.decrypt(
            playlist.usernameEncrypted
        )
        val password = SecureCredentialStore.decrypt(
            playlist.passwordEncrypted
        )

        val client = XtreamClient(
            playlist.sourceUrl,
            username,
            password
        )

        client.testConnection()

        val categoryNames = client.getLiveCategories()
            .associate { it.id to it.name }

        val streams = client.getLiveStreams()
        require(streams.isNotEmpty()) {
            "لم يتم العثور على قنوات في الاشتراك"
        }

        val channels = streams.map { stream ->
            IptvChannel(
                playlistId = playlistId,
                name = stream.name,
                url = stream.url,
                groupName = categoryNames[stream.categoryId] ?: "عام",
                logo = stream.logo,
                tvgId = stream.epgId
            )
        }

        database.replaceChannels(playlistId, channels)
        return channels.size
    }

    fun getXtreamCategories(playlistId: Long): List<XtreamCategory> {
        val playlist = requirePlaylistExists(playlistId)
        require(playlist.type == "XTREAM") {
            "هذه القائمة ليست اشتراك Xtream"
        }

        val client = createXtreamClient(playlist)
        return client.getLiveCategories()
    }

    private fun createXtreamClient(
        playlist: IptvPlaylist
    ): XtreamClient {
        return XtreamClient(
            playlist.sourceUrl,
            SecureCredentialStore.decrypt(playlist.usernameEncrypted),
            SecureCredentialStore.decrypt(playlist.passwordEncrypted)
        )
    }

    private fun requirePlaylistExists(playlistId: Long): IptvPlaylist {
        return database.getPlaylists().firstOrNull {
            it.id == playlistId
        } ?: throw IllegalArgumentException("قائمة IPTV غير موجودة")
    }

    private fun List<M3uEntry>.toChannels(
        playlistId: Long
    ): List<IptvChannel> {
        return map { entry ->
            IptvChannel(
                playlistId = playlistId,
                name = entry.name,
                url = entry.url,
                groupName = entry.groupName,
                logo = entry.logo,
                tvgId = entry.tvgId
            )
        }
    }

    private fun downloadText(url: String): String {
        require(isHttpUrl(url)) { "رابط القائمة غير صالح" }

        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 25000
            connection.setRequestProperty("User-Agent", "CinemaStream/1.0")

            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("تعذر تحميل القائمة: HTTP $code")
            }

            return connection.inputStream.bufferedReader().use {
                it.readText()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun isHttpUrl(value: String): Boolean {
        return value.trim().startsWith("https://", true) ||
            value.trim().startsWith("http://", true)
    }
}
