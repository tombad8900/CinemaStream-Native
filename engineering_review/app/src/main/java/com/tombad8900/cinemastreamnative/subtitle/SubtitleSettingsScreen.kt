package com.tombad8900.cinemastreamnative.subtitle

import android.graphics.Typeface
import android.view.Gravity
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private val SubtitleGold = Color(0xFFE6B96F)
private val SubtitlePanel = Color(0xFF151B29)
private val SubtitleMuted = Color(0xFF9AA4B5)

@Composable
fun SubtitleSettingsScreen() {
    val context = LocalContext.current

    var settings by remember {
        mutableStateOf(SubtitlePreferences.load(context))
    }

    var message by remember { mutableStateOf("") }

    fun updateSettings(updated: SubtitleSettings) {
        settings = updated
        SubtitlePreferences.save(context, updated)
    }

    val fontPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val imported = SubtitleFontManager.importFromUri(context, uri)

                updateSettings(
                    settings.copy(
                        fontName = imported.name,
                        fontPath = imported.file.absolutePath
                    )
                )

                message = "تم استيراد الخط بنجاح"
            } catch (error: Exception) {
                message = error.message ?: "تعذر استيراد الخط"
            }
        }
    }

    val previewTypeface = remember(settings.fontPath, settings.bold) {
        val base = settings.fontPath?.let {
            runCatching { Typeface.createFromFile(it) }.getOrNull()
        } ?: Typeface.DEFAULT

        Typeface.create(
            base,
            if (settings.bold) Typeface.BOLD else Typeface.NORMAL
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        Text(
            "تخصيص مظهر الترجمة",
            color = SubtitleGold,
            fontSize = 16.sp
        )

        Spacer(Modifier.height(12.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SubtitlePanel,
            shape = RoundedCornerShape(15.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("نوع الخط", color = Color.White)

                Spacer(Modifier.height(8.dp))

                Text(
                    settings.fontName ?: "خط النظام الافتراضي",
                    color = SubtitleMuted,
                    fontSize = 12.sp
                )

                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            fontPicker.launch(arrayOf("*/*"))
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SubtitleGold
                        )
                    ) {
                        Text("اختيار خط من الهاتف", color = Color.Black)
                    }

                    TextButton(
                        onClick = {
                            updateSettings(
                                settings.copy(
                                    fontName = null,
                                    fontPath = null
                                )
                            )
                            message = "تم الرجوع إلى خط النظام"
                        }
                    ) {
                        Text("الافتراضي", color = SubtitleGold)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text("معاينة الخط", color = SubtitleMuted, fontSize = 12.sp)

                Spacer(Modifier.height(7.dp))

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(75.dp)
                        .background(
                            Color(settings.backgroundColor).copy(
                                alpha = settings.backgroundOpacity
                            ),
                            RoundedCornerShape(10.dp)
                        ),
                    factory = { ctx ->
                        TextView(ctx).apply {
                            gravity = Gravity.CENTER
                            text = "هذه معاينة للترجمة العربية"
                            setPadding(8, 8, 8, 8)
                        }
                    },
                    update = { view ->
                        view.text = "هذه معاينة للترجمة العربية"
                        view.typeface = previewTypeface
                        view.textSize = settings.fontSize
                        view.setTextColor(settings.textColor)
                    }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SubtitlePanel,
            shape = RoundedCornerShape(15.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("حجم الخط: ${settings.fontSize.toInt()}", color = Color.White)

                Slider(
                    value = settings.fontSize,
                    onValueChange = {
                        updateSettings(settings.copy(fontSize = it))
                    },
                    valueRange = 12f..40f
                )

                HorizontalDivider(color = Color.DarkGray)

                Text("لون النص", color = Color.White)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "أبيض" to -1,
                        "أصفر" to 0xFFFFFF00.toInt(),
                        "سماوي" to 0xFF00E5FF.toInt(),
                        "أخضر" to 0xFF76FF03.toInt()
                    ).forEach { (name, color) ->
                        TextButton(
                            onClick = {
                                updateSettings(settings.copy(textColor = color))
                            }
                        ) {
                            Text(
                                name,
                                color = Color(color)
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color.DarkGray)

                Text(
                    "شفافية خلفية الترجمة: ${(settings.backgroundOpacity * 100).toInt()}%",
                    color = Color.White
                )

                Slider(
                    value = settings.backgroundOpacity,
                    onValueChange = {
                        updateSettings(settings.copy(backgroundOpacity = it))
                    },
                    valueRange = 0f..1f
                )

                HorizontalDivider(color = Color.DarkGray)

                Text(
                    "موضع الترجمة: ${(settings.bottomPosition * 100).toInt()}%",
                    color = Color.White
                )

                Slider(
                    value = settings.bottomPosition,
                    onValueChange = {
                        updateSettings(settings.copy(bottomPosition = it))
                    },
                    valueRange = 0.65f..0.98f
                )

                HorizontalDivider(color = Color.DarkGray)

                Text(
                    "تأخير الترجمة: ${settings.delayMs} ms",
                    color = Color.White
                )

                Slider(
                    value = settings.delayMs.toFloat(),
                    onValueChange = {
                        updateSettings(settings.copy(delayMs = it.toLong()))
                    },
                    valueRange = -5000f..5000f,
                    steps = 99
                )

                HorizontalDivider(color = Color.DarkGray)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("خط عريض", color = Color.White)
                    Switch(
                        checked = settings.bold,
                        onCheckedChange = {
                            updateSettings(settings.copy(bold = it))
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("تفضيل العربية تلقائيًا", color = Color.White)
                    Switch(
                        checked = settings.autoSelectArabic,
                        onCheckedChange = {
                            updateSettings(settings.copy(autoSelectArabic = it))
                        }
                    )
                }

                HorizontalDivider(color = Color.DarkGray)

                Text("لغة الترجمة المفضلة", color = Color.White)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ar" to "العربية", "en" to "الإنجليزية").forEach { (code, name) ->
                        TextButton(
                            onClick = {
                                updateSettings(settings.copy(language = code))
                            }
                        ) {
                            Text(
                                (if (settings.language == code) "✓ " else "") + name,
                                color = SubtitleGold
                            )
                        }
                    }
                }
            }
        }

        if (message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(message, color = SubtitleGold, fontSize = 12.sp)
        }

        Spacer(Modifier.height(10.dp))

        TextButton(
            onClick = {
                SubtitlePreferences.reset(context)
                settings = SubtitleSettings()
                message = "تمت إعادة إعدادات الترجمة إلى الوضع الافتراضي"
            }
        ) {
            Text("إعادة ضبط إعدادات الترجمة", color = SubtitleGold)
        }
    }
}
