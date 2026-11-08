package com.tombad8900.cinemastreamnative.tmdb

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun TmdbSettingsScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apiKey by remember {
        mutableStateOf(TmdbApiKeyStore.get(context).orEmpty())
    }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var success by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "إعدادات TMDB",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "أدخل مفتاح API v3 لجلب الأفلام والمسلسلات من قاعدة بيانات TMDB.",
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    message = ""
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("TMDB API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password
                )
            )

            if (message.isNotBlank()) {
                Text(
                    text = message,
                    color = if (success) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (apiKey.isBlank()) {
                            message = "أدخل مفتاح TMDB أولًا."
                            success = false
                        } else {
                            scope.launch {
                                loading = true
                                message = "جارٍ اختبار الاتصال..."
                                success = false

                                try {
                                    TmdbClient.testKey(apiKey.trim())
                                    TmdbApiKeyStore.save(context, apiKey.trim())
                                    message = "تم التحقق من المفتاح وحفظه بشكل مشفّر."
                                    success = true
                                } catch (error: Exception) {
                                    message = error.message
                                        ?: "تعذر الاتصال بـ TMDB."
                                } finally {
                                    loading = false
                                }
                            }
                        }
                    },
                    enabled = !loading && apiKey.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (loading) "جارٍ التحقق..." else "اختبار وحفظ")
                }

                OutlinedButton(
                    onClick = {
                        TmdbApiKeyStore.clear(context)
                        apiKey = ""
                        message = "تم حذف المفتاح المحفوظ."
                        success = true
                    },
                    enabled = !loading
                ) {
                    Text("حذف")
                }
            }
        }
    }
}
