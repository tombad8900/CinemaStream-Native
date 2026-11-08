package com.tombad8900.cinemastreamnative.subtitle

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.MimeTypes
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

data class ImportedSubtitle(
    val file: File,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val extension: String
)

object SubtitleFileManager {

    fun applyDelay(imported: ImportedSubtitle, delayMs: Long): ImportedSubtitle {
        if (delayMs == 0L) return imported

        val ext = imported.extension.lowercase()
        if (ext !in setOf("srt", "vtt", "ass", "ssa", "ttml", "xml")) {
            return imported
        }

        val content = imported.file.readText(Charsets.UTF_8)

        fun shiftClock(
            hours: String,
            minutes: String,
            seconds: String,
            fraction: String,
            separator: String
        ): String {
            val precision = fraction.length
            val unit = when (precision) {
                2 -> 10L
                3 -> 1L
                else -> return "$hours:$minutes:$seconds$separator$fraction"
            }

            val original =
                hours.toLong() * 3_600_000L +
                minutes.toLong() * 60_000L +
                seconds.toLong() * 1_000L +
                fraction.toLong() * unit

            val shifted = (original + delayMs).coerceAtLeast(0L)
            val h = shifted / 3_600_000L
            val m = (shifted / 60_000L) % 60
            val sec = (shifted / 1_000L) % 60
            val part = (shifted % 1_000L) / unit

            return "%02d:%02d:%02d%s%0${precision}d".format(
                h, m, sec, separator, part
            )
        }

        val adjusted = when (ext) {
            "srt", "vtt" -> {
                val pattern = Regex(
                    """(?<!\d)(\d{1,3}):([0-5]\d):([0-5]\d)([,.])(\d{3})(?!\d)"""
                )
                pattern.replace(content) {
                    shiftClock(
                        it.groupValues[1],
                        it.groupValues[2],
                        it.groupValues[3],
                        it.groupValues[5],
                        it.groupValues[4]
                    )
                }
            }

            "ass", "ssa" -> {
                val pattern = Regex(
                    """(?<!\d)(\d{1,2}):([0-5]\d):([0-5]\d)\.(\d{2})(?!\d)"""
                )
                pattern.replace(content) {
                    shiftClock(
                        it.groupValues[1],
                        it.groupValues[2],
                        it.groupValues[3],
                        it.groupValues[4],
                        "."
                    )
                }
            }

            else -> {
                val pattern = Regex(
                    """(?<=["'])(\d{1,3}):([0-5]\d):([0-5]\d)(?:\.(\d{1,3}))?(?=["'])"""
                )
                pattern.replace(content) {
                    shiftClock(
                        it.groupValues[1],
                        it.groupValues[2],
                        it.groupValues[3],
                        it.groupValues[4].padEnd(3, '0'),
                        "."
                    )
                }
            }
        }

        if (adjusted == content) return imported

        val output = File(
            imported.file.parentFile,
            "delayed_${UUID.randomUUID()}.$ext"
        )
        output.writeText(adjusted, Charsets.UTF_8)

        return imported.copy(
            file = output,
            uri = Uri.fromFile(output)
        )
    }


    private const val MAX_FILE_SIZE = 12L * 1024L * 1024L

    private val supportedTypes = mapOf(
        "srt" to MimeTypes.APPLICATION_SUBRIP,
        "vtt" to MimeTypes.TEXT_VTT,
        "ttml" to MimeTypes.APPLICATION_TTML,
        "xml" to MimeTypes.APPLICATION_TTML,
        "ass" to MimeTypes.TEXT_SSA,
        "ssa" to MimeTypes.TEXT_SSA
    )

    fun supportedExtensions(): Array<String> =
        supportedTypes.keys.toTypedArray()

    fun importFromUri(
        context: Context,
        sourceUri: Uri
    ): ImportedSubtitle {

        val resolver = context.contentResolver

        val originalName = resolver.query(
            sourceUri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)
            } else null
        } ?: "subtitle.srt"

        val extension = originalName
            .substringAfterLast('.', "")
            .lowercase()

        val mimeType = supportedTypes[extension]
            ?: throw IllegalArgumentException("صيغة ملف الترجمة غير مدعومة")

        val bytes = resolver.openInputStream(sourceUri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L

            while (true) {
                val count = input.read(buffer)
                if (count == -1) break

                total += count
                if (total > MAX_FILE_SIZE) {
                    throw IllegalArgumentException(
                        "حجم ملف الترجمة أكبر من الحد المسموح (12 ميجابايت)"
                    )
                }

                output.write(buffer, 0, count)
            }

            output.toByteArray()
        } ?: throw IllegalArgumentException("تعذر قراءة ملف الترجمة")

        if (bytes.isEmpty()) {
            throw IllegalArgumentException("ملف الترجمة فارغ")
        }

        val content = decodeText(bytes)
        validateContent(content, extension)

        val directory = File(context.filesDir, "subtitles")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("تعذر إنشاء مجلد الترجمة")
        }

        val safeExtension = if (extension == "xml") "ttml" else extension
        val destination = File(
            directory,
            "${UUID.randomUUID()}.$safeExtension"
        )

        try {
            destination.writeText(content, StandardCharsets.UTF_8)
        } catch (error: Exception) {
            destination.delete()
            throw error
        }

        return ImportedSubtitle(
            file = destination,
            uri = Uri.fromFile(destination),
            displayName = originalName,
            mimeType = mimeType,
            extension = safeExtension
        )
    }


    fun importDownloadedFile(
        context: Context,
        sourceFile: File
    ): ImportedSubtitle {
        require(sourceFile.isFile) { "ملف الترجمة المنزّل غير موجود" }
        require(sourceFile.length() in 1..MAX_FILE_SIZE) {
            "حجم ملف الترجمة غير صالح أو أكبر من 12 ميجابايت"
        }

        val originalName = sourceFile.name
        val extension = originalName.substringAfterLast('.', "").lowercase()
        val mimeType = supportedTypes[extension]
            ?: throw IllegalArgumentException("صيغة ملف الترجمة غير مدعومة")

        val bytes = sourceFile.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L

            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                total += count
                require(total <= MAX_FILE_SIZE) {
                    "حجم ملف الترجمة أكبر من الحد المسموح (12 ميجابايت)"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }

        require(bytes.isNotEmpty()) { "ملف الترجمة فارغ" }

        val content = decodeText(bytes)
        validateContent(content, extension)

        val directory = File(context.filesDir, "subtitles")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("تعذر إنشاء مجلد الترجمة")
        }

        val safeExtension = if (extension == "xml") "ttml" else extension
        val destination = File(directory, "${UUID.randomUUID()}.$safeExtension")

        try {
            destination.writeText(content, StandardCharsets.UTF_8)
        } catch (error: Exception) {
            destination.delete()
            throw error
        }

        return ImportedSubtitle(
            file = destination,
            uri = Uri.fromFile(destination),
            displayName = originalName,
            mimeType = mimeType,
            extension = safeExtension
        )
    }

    private fun decodeText(bytes: ByteArray): String {
        if (
            bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }

        if (
            bytes.size >= 2 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xFE.toByte()
        ) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }

        if (
            bytes.size >= 2 &&
            bytes[0] == 0xFE.toByte() &&
            bytes[1] == 0xFF.toByte()
        ) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }

        val utf8Decoder = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        return try {
            utf8Decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            CharsetWindows1256.decode(bytes)
        }
    }

    private fun validateContent(
        content: String,
        extension: String
    ) {
        val text = content.trim()

        if (text.isEmpty() || text.contains('\u0000')) {
            throw IllegalArgumentException("محتوى ملف الترجمة غير صالح")
        }

        val valid = when (extension) {
            "srt" -> Regex(
                """\d{1,2}:\d{2}:\d{2}[,.]\d{3}\s*-->"""
            ).containsMatchIn(text)

            "vtt" -> text.startsWith("WEBVTT") ||
                Regex("""\d{2}:\d{2}[.:]\d{3}\s*-->""")
                    .containsMatchIn(text)

            "ass", "ssa" ->
                text.contains("[Script Info]", ignoreCase = true) ||
                text.contains("Dialogue:", ignoreCase = true)

            "ttml", "xml" ->
                text.contains("<tt", ignoreCase = true)

            else -> false
        }

        if (!valid) {
            throw IllegalArgumentException(
                "محتوى الملف لا يتطابق مع صيغة الترجمة المختارة"
            )
        }
    }

    private object CharsetWindows1256 {
        fun decode(bytes: ByteArray): String =
            String(bytes, java.nio.charset.Charset.forName("windows-1256"))
    }
}
