package com.tombad8900.cinemastreamnative.subtitle

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SubtitleProviderSettingsScreen() {
    val context = LocalContext.current

    var subdlKey by remember {
        mutableStateOf(SubtitleApiKeyStore.get(context, "subdl").orEmpty())
    }

    var openSubtitlesKey by remember {
        mutableStateOf(SubtitleApiKeyStore.get(context, "opensubtitles").orEmpty())
    }

    var message by remember { mutableStateOf("") }

    val gold = Color(0xFFE6B96F)
    val panel = Color(0xFF151B29)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = panel,
        shape = RoundedCornerShape(15.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("مصادر الترجمة عبر الإنترنت", color = gold)

            Text("SubDL", color = Color.White)

            OutlinedTextField(
                value = subdlKey,
                onValueChange = { subdlKey = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("مفتاح API") },
                visualTransformation = PasswordVisualTransformation()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        runCatching {
                            SubtitleApiKeyStore.save(context, "subdl", subdlKey)
                        }.onSuccess {
                            message = "تم حفظ مفتاح SubDL مشفّرًا"
                        }.onFailure {
                            message = "تعذر حفظ مفتاح SubDL"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = gold)
                ) {
                    Text("حفظ المفتاح", color = Color.Black)
                }

                TextButton(
                    onClick = {
                        SubtitleApiKeyStore.clear(context, "subdl")
                        subdlKey = ""
                        message = "تم حذف مفتاح SubDL"
                    }
                ) {
                    Text("حذف", color = gold)
                }
            }

            HorizontalDivider(color = Color.DarkGray)

            Text("OpenSubtitles", color = Color.White)

            OutlinedTextField(
                value = openSubtitlesKey,
                onValueChange = { openSubtitlesKey = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("مفتاح التطبيق API Key") },
                visualTransformation = PasswordVisualTransformation()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        runCatching {
                            SubtitleApiKeyStore.save(
                                context,
                                "opensubtitles",
                                openSubtitlesKey
                            )
                        }.onSuccess {
                            message = "تم حفظ مفتاح OpenSubtitles مشفّرًا"
                        }.onFailure {
                            message = "تعذر حفظ مفتاح OpenSubtitles"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = gold)
                ) {
                    Text("حفظ المفتاح", color = Color.Black)
                }

                TextButton(
                    onClick = {
                        SubtitleApiKeyStore.clear(context, "opensubtitles")
                        openSubtitlesKey = ""
                        message = "تم حذف مفتاح OpenSubtitles"
                    }
                ) {
                    Text("حذف", color = gold)
                }
            }

            Text(
                "تُحفظ المفاتيح مشفّرة على الجهاز، ولا تُضاف إلى ملفات المشروع.",
                color = Color.LightGray,
                style = MaterialTheme.typography.bodySmall
            )

            if (message.isNotBlank()) {
                Text(message, color = gold, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
