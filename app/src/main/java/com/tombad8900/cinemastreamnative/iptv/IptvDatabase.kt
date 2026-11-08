package com.tombad8900.cinemastreamnative.iptv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class IptvPlaylist(
    val id: Long,
    val name: String,
    val type: String,
    val sourceUrl: String,
    val usernameEncrypted: String,
    val passwordEncrypted: String
)

data class IptvChannel(
    val id: Long = 0,
    val playlistId: Long,
    val name: String,
    val url: String,
    val groupName: String = "عام",
    val logo: String = "",
    val tvgId: String = ""
)

class IptvDatabase(context: Context) :
    SQLiteOpenHelper(context, "cinemastream_iptv.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE playlists (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                source_url TEXT NOT NULL DEFAULT '',
                username_encrypted TEXT NOT NULL DEFAULT '',
                password_encrypted TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE channels (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                playlist_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                url TEXT NOT NULL,
                group_name TEXT NOT NULL DEFAULT 'عام',
                logo TEXT NOT NULL DEFAULT '',
                tvg_id TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(playlist_id) REFERENCES playlists(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            "CREATE INDEX index_channels_playlist ON channels(playlist_id)"
        )
        db.execSQL(
            "CREATE INDEX index_channels_group ON channels(playlist_id, group_name)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    }

    fun savePlaylist(
        name: String,
        type: String,
        sourceUrl: String = "",
        usernameEncrypted: String = "",
        passwordEncrypted: String = ""
    ): Long {
        val values = ContentValues().apply {
            put("name", name)
            put("type", type)
            put("source_url", sourceUrl)
            put("username_encrypted", usernameEncrypted)
            put("password_encrypted", passwordEncrypted)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("playlists", null, values)
    }

    fun updatePlaylistCredentials(
        playlistId: Long,
        name: String,
        sourceUrl: String,
        usernameEncrypted: String,
        passwordEncrypted: String
    ) {
        val values = ContentValues().apply {
            put("name", name)
            put("source_url", sourceUrl)
            put("username_encrypted", usernameEncrypted)
            put("password_encrypted", passwordEncrypted)
        }
        writableDatabase.update(
            "playlists", values, "id = ?", arrayOf(playlistId.toString())
        )
    }

    fun getPlaylists(): List<IptvPlaylist> {
        val result = mutableListOf<IptvPlaylist>()
        readableDatabase.query(
            "playlists", null, null, null, null, null, "created_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    IptvPlaylist(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                        sourceUrl = cursor.getString(cursor.getColumnIndexOrThrow("source_url")),
                        usernameEncrypted = cursor.getString(cursor.getColumnIndexOrThrow("username_encrypted")),
                        passwordEncrypted = cursor.getString(cursor.getColumnIndexOrThrow("password_encrypted"))
                    )
                )
            }
        }
        return result
    }

    fun addChannels(playlistId: Long, channels: List<IptvChannel>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            channels.forEach { channel ->
                val values = ContentValues().apply {
                    put("playlist_id", playlistId)
                    put("name", channel.name)
                    put("url", channel.url)
                    put("group_name", channel.groupName)
                    put("logo", channel.logo)
                    put("tvg_id", channel.tvgId)
                }
                db.insertOrThrow("channels", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun replaceChannels(playlistId: Long, channels: List<IptvChannel>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("channels", "playlist_id = ?", arrayOf(playlistId.toString()))
            channels.forEach { channel ->
                val values = ContentValues().apply {
                    put("playlist_id", playlistId)
                    put("name", channel.name)
                    put("url", channel.url)
                    put("group_name", channel.groupName)
                    put("logo", channel.logo)
                    put("tvg_id", channel.tvgId)
                }
                db.insertOrThrow("channels", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getChannels(playlistId: Long): List<IptvChannel> {
        val result = mutableListOf<IptvChannel>()
        readableDatabase.query(
            "channels",
            null,
            "playlist_id = ?",
            arrayOf(playlistId.toString()),
            null,
            null,
            "group_name COLLATE NOCASE, name COLLATE NOCASE"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    IptvChannel(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        playlistId = cursor.getLong(cursor.getColumnIndexOrThrow("playlist_id")),
                        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        url = cursor.getString(cursor.getColumnIndexOrThrow("url")),
                        groupName = cursor.getString(cursor.getColumnIndexOrThrow("group_name")),
                        logo = cursor.getString(cursor.getColumnIndexOrThrow("logo")),
                        tvgId = cursor.getString(cursor.getColumnIndexOrThrow("tvg_id"))
                    )
                )
            }
        }
        return result
    }

    fun deletePlaylist(playlistId: Long) {
        writableDatabase.delete(
            "playlists", "id = ?", arrayOf(playlistId.toString())
        )
    }
}
