package com.tombad8900.cinemastreamnative.subtitle

import android.content.Context
import java.io.File

data class SubtitleSettings(
    val language: String = "ar",
    val fontSize: Float = 22f,
    val textColor: Int = -1,
    val backgroundColor: Int = 0xCC000000.toInt(),
    val backgroundOpacity: Float = 0.75f,
    val bottomPosition: Float = 0.90f,
    val delayMs: Long = 0L,
    val bold: Boolean = false,
    val autoSelectArabic: Boolean = true,
    val fontName: String? = null,
    val fontPath: String? = null
)

object SubtitlePreferences {

    private const val PREFS_NAME = "cinemastream_subtitle_preferences"

    private const val LANGUAGE = "language"
    private const val FONT_SIZE = "font_size"
    private const val TEXT_COLOR = "text_color"
    private const val BACKGROUND_COLOR = "background_color"
    private const val BACKGROUND_OPACITY = "background_opacity"
    private const val BOTTOM_POSITION = "bottom_position"
    private const val DELAY_MS = "delay_ms"
    private const val BOLD = "bold"
    private const val AUTO_SELECT_ARABIC = "auto_select_arabic"
    private const val FONT_NAME = "font_name"
    private const val FONT_PATH = "font_path"

    fun load(context: Context): SubtitleSettings {
        val prefs = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        val savedPath = prefs.getString(FONT_PATH, null)
        val validPath = savedPath?.takeIf { path ->
            val file = File(path)
            file.isFile &&
                file.parentFile?.canonicalPath ==
                File(context.filesDir, "subtitle_fonts").canonicalPath
        }

        return SubtitleSettings(
            language = prefs.getString(LANGUAGE, "ar") ?: "ar",
            fontSize = prefs.getFloat(FONT_SIZE, 22f).coerceIn(12f, 40f),
            textColor = prefs.getInt(TEXT_COLOR, -1),
            backgroundColor = prefs.getInt(
                BACKGROUND_COLOR,
                0xCC000000.toInt()
            ),
            backgroundOpacity = prefs.getFloat(
                BACKGROUND_OPACITY,
                0.75f
            ).coerceIn(0f, 1f),
            bottomPosition = prefs.getFloat(
                BOTTOM_POSITION,
                0.90f
            ).coerceIn(0.5f, 0.98f),
            delayMs = prefs.getLong(DELAY_MS, 0L)
                .coerceIn(-30000L, 30000L),
            bold = prefs.getBoolean(BOLD, false),
            autoSelectArabic = prefs.getBoolean(
                AUTO_SELECT_ARABIC,
                true
            ),
            fontName = if (validPath != null) {
                prefs.getString(FONT_NAME, null)
            } else null,
            fontPath = validPath
        )
    }

    fun save(
        context: Context,
        settings: SubtitleSettings
    ) {
        val validFont = settings.fontPath?.let { path ->
            val file = File(path)
            file.isFile &&
                file.parentFile?.canonicalPath ==
                File(context.filesDir, "subtitle_fonts").canonicalPath
        } == true

        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putString(LANGUAGE, settings.language)
            .putFloat(FONT_SIZE, settings.fontSize.coerceIn(12f, 40f))
            .putInt(TEXT_COLOR, settings.textColor)
            .putInt(BACKGROUND_COLOR, settings.backgroundColor)
            .putFloat(
                BACKGROUND_OPACITY,
                settings.backgroundOpacity.coerceIn(0f, 1f)
            )
            .putFloat(
                BOTTOM_POSITION,
                settings.bottomPosition.coerceIn(0.5f, 0.98f)
            )
            .putLong(
                DELAY_MS,
                settings.delayMs.coerceIn(-30000L, 30000L)
            )
            .putBoolean(BOLD, settings.bold)
            .putBoolean(AUTO_SELECT_ARABIC, settings.autoSelectArabic)
            .putString(FONT_NAME, if (validFont) settings.fontName else null)
            .putString(FONT_PATH, if (validFont) settings.fontPath else null)
            .apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().apply()
    }
}
