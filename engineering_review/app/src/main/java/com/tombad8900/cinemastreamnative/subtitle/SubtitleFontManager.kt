package com.tombad8900.cinemastreamnative.subtitle

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

data class ImportedSubtitleFont(
    val file: File,
    val name: String,
    val typeface: Typeface
)

object SubtitleFontManager {

    private const val MAX_FONT_SIZE = 15L * 1024L * 1024L

    fun importFromUri(
        context: Context,
        uri: Uri
    ): ImportedSubtitleFont {

        val resolver = context.contentResolver

        val originalName = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "custom-font.ttf"

        val extension = originalName
            .substringAfterLast('.', "")
            .lowercase()

        require(extension == "ttf" || extension == "otf") {
            "صيغة الخط غير مدعومة. اختر ملف TTF أو OTF."
        }

        val directory = File(context.filesDir, "subtitle_fonts")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("تعذر إنشاء مجلد الخطوط")
        }

        val destination = File(
            directory,
            "${UUID.randomUUID()}.$extension"
        )

        try {
            val input = resolver.openInputStream(uri)
                ?: throw IllegalArgumentException("تعذر قراءة ملف الخط")

            input.use { source ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L

                    while (true) {
                        val count = source.read(buffer)
                        if (count == -1) break

                        total += count
                        require(total <= MAX_FONT_SIZE) {
                            "حجم ملف الخط أكبر من 15 ميجابايت"
                        }

                        output.write(buffer, 0, count)
                    }
                }
            }

            require(destination.length() > 0L) {
                "ملف الخط فارغ"
            }

            val typeface = Typeface.createFromFile(destination)

            return ImportedSubtitleFont(
                file = destination,
                name = originalName,
                typeface = typeface
            )
        } catch (error: Exception) {
            destination.delete()
            throw IllegalArgumentException(
                "تعذر تحميل الخط. تأكد من سلامة ملف TTF أو OTF.",
                error
            )
        }
    }

    fun deleteFont(path: String?) {
        if (path.isNullOrBlank()) return

        val file = File(path)
        if (file.name.endsWith(".ttf", true) ||
            file.name.endsWith(".otf", true)
        ) {
            file.delete()
        }
    }
}
