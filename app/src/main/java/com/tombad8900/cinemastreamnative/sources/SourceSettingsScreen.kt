package com.tombad8900.cinemastreamnative.sources

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SourcePanel = Color(0xFF172235)
private val SourceGold = Color(0xFFFFC857)
private val SourceMuted = Color(0xFF9BA9BC)

@Composable
fun SourceSettingsScreen() {
    val context = LocalContext.current
    var sources by remember { mutableStateOf(SourceStore.load(context)) }
    var message by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val json = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalArgumentException("تعذرت قراءة الملف")
                val count = SourceStore.importJson(context, json)
                sources = SourceStore.load(context)
                message = "تم استيراد $count مصدر بنجاح"
            } catch (e: Exception) {
                message = e.message ?: "تعذر استيراد الملف"
            }
        }
    }

    fun updateSource(updated: StreamSource) {
        sources = sources.map { if (it.id == updated.id) updated else it }
        SourceStore.save(context, sources)
    }

    val filteredSources = remember(sources, query) {
        sources.sortedBy { it.priority }.filter {
            query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true)
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("إدارة مصادر التشغيل", color = SourceGold, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            "استورد ملف JSON يحتوي على data.servers. سيتم استيراد المصادر فقط دون تغيير المفضلة أو سجل المشاهدة أو إعدادات التطبيق.",
            color = SourceMuted, fontSize = 12.sp, lineHeight = 18.sp
        )
        Button(
            onClick = { picker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
        ) { Text("استيراد مصادر من ملف JSON") }

        if (message.isNotBlank()) Text(message, color = SourceGold, fontSize = 12.sp)
        Text("المصادر المحفوظة: ${sources.size} · الظاهرة: ${filteredSources.size}",
            color = Color.White, fontWeight = FontWeight.Bold)

        if (sources.isNotEmpty()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("بحث عن مصدر") }, placeholder = { Text("الاسم أو المعرّف") }
            )
        }

        if (filteredSources.isEmpty()) {
            Text(
                if (sources.isEmpty()) "لا توجد مصادر بعد. استورد ملف JSON لإضافتها."
                else "لا توجد مصادر تطابق البحث.",
                color = SourceMuted, fontSize = 12.sp
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 310.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredSources, key = { it.id }) { source ->
                    Surface(Modifier.fillMaxWidth(), color = SourcePanel,
                        shape = RoundedCornerShape(13.dp)) {
                        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(source.name, color = Color.White, fontWeight = FontWeight.Bold)
                                    Text("المعرّف: ${source.id} · الأولوية: ${source.priority}",
                                        color = SourceMuted, fontSize = 10.sp)
                                }
                                TextButton(onClick = {
                                    sources = sources.filterNot { it.id == source.id }
                                    SourceStore.save(context, sources)
                                }) { Text("حذف", color = Color(0xFFFF7777)) }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("تفعيل المصدر", color = Color.White, fontSize = 12.sp)
                                Switch(source.enabled, onCheckedChange = { updateSource(source.copy(enabled = it)) })
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("إخفاء المصدر من الاختيار", color = Color.White, fontSize = 12.sp)
                                Switch(source.hidden, onCheckedChange = { updateSource(source.copy(hidden = it)) })
                            }
                            if (source.notes.isNotBlank()) {
                                Text(source.notes, color = SourceMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
