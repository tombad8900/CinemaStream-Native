package com.tombad8900.cinemastreamnative.security

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

object AdBlockStore {
    private const val PREFS = "personal_adblock_preferences"
    private const val RULES = "rules"
    private const val ENABLED = "enabled"

    @Volatile private var cachedRules: Set<String> = emptySet()
    @Volatile private var cachedEnabled: Boolean = true

    fun load(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cachedRules = prefs.getStringSet(RULES, emptySet())?.toSet() ?: emptySet()
        cachedEnabled = prefs.getBoolean(ENABLED, true)
    }

    fun getRules(context: Context): Set<String> {
        load(context)
        return cachedRules
    }

    fun isEnabled(context: Context): Boolean {
        load(context)
        return cachedEnabled
    }

    fun isBlocked(host: String): Boolean {
        if (!cachedEnabled) return false
        val value = host.lowercase(Locale.ROOT).trimEnd('.')
        return value.isNotBlank() && cachedRules.any {
            value == it || value.endsWith(".$it")
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED, enabled).apply()
        cachedEnabled = enabled
    }

    fun setRules(context: Context, rules: Set<String>) {
        val clean = rules.mapNotNull { normalize(it) }.toSet()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(RULES, clean).apply()
        cachedRules = clean
    }

    fun parse(content: String): Set<String> {
        val result = linkedSetOf<String>()

        content.lineSequence().forEach { original ->
            val line = original.trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith("!")) {
                return@forEach
            }

            line.split(Regex("[\\s,;]+"))
                .filter { it.isNotBlank() }
                .forEach { token ->
                    normalize(token)?.let { result.add(it) }
                }
        }

        return result
    }

    private fun normalize(raw: String): String? {
        var token = raw.trim().trim('"', '\'')

        if (token.isBlank()) return null

        if (token.startsWith("||")) {
            token = token.removePrefix("||").substringBefore('^').substringBefore('/')
        } else if (token.contains("://")) {
            token = runCatching { Uri.parse(token).host }.getOrNull() ?: return null
        } else {
            token = token.substringBefore('$').substringBefore('/')
            token = token.removePrefix("|").removeSuffix("|")
        }

        token = token.trim().trim('.').lowercase(Locale.ROOT)

        if (token.length > 253 || !token.contains('.')) return null
        if (token.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) return null

        val domainPattern =
            Regex("(?i)^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")

        return token.takeIf { domainPattern.matches(it) }
    }
}

@Composable
fun AdBlockSettingsScreen() {
    val context = LocalContext.current
    var rules by remember { mutableStateOf(AdBlockStore.getRules(context)) }
    var enabled by remember { mutableStateOf(AdBlockStore.isEnabled(context)) }
    var input by remember { mutableStateOf("") }
    var editingRule by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    fun refreshRules(updated: Set<String>) {
        AdBlockStore.setRules(context, updated)
        rules = AdBlockStore.getRules(context)
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("تعذر قراءة الملف")
                if (bytes.size > 5 * 1024 * 1024) {
                    throw IllegalArgumentException("حجم الملف أكبر من 5 ميجابايت")
                }
                val imported = AdBlockStore.parse(bytes.toString(Charsets.UTF_8))
                if (imported.isEmpty()) {
                    throw IllegalArgumentException("لم يتم العثور على نطاقات صالحة")
                }
                refreshRules(rules + imported)
                Toast.makeText(context, "تم استيراد ${imported.size} نطاق", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, it.message ?: "تعذر استيراد الملف", Toast.LENGTH_LONG).show()
            }
        }
    }

    val filteredRules = remember(rules, query) {
        rules.sorted().filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("مانع الإعلانات الشخصي", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("تفعيل القائمة الشخصية")
                Text("تُضاف قواعدك إلى مانع الإعلانات الحالي", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                AdBlockStore.setEnabled(context, it)
            })
        }
        OutlinedTextField(
            value = input, onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("نطاق أو رابط إعلاني") },
            placeholder = { Text("example.com") }, singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val newRule = AdBlockStore.parse(input).firstOrNull()
                if (newRule == null) {
                    Toast.makeText(context, "أدخل نطاقًا صالحًا", Toast.LENGTH_SHORT).show()
                } else {
                    val updated = rules.toMutableSet()
                    editingRule?.let { updated.remove(it) }
                    updated.add(newRule)
                    refreshRules(updated)
                    input = ""
                    editingRule = null
                }
            }) { Text(if (editingRule == null) "إضافة" else "حفظ التعديل") }

            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("استيراد ملف") }
        }

        Text("النطاقات: ${rules.size} · نتائج البحث: ${filteredRules.size}", fontWeight = FontWeight.Bold)
        if (rules.isNotEmpty()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("بحث في النطاقات") },
                placeholder = { Text("اكتب اسم النطاق") }, singleLine = true
            )
        }

        if (filteredRules.isEmpty()) {
            Text(
                if (rules.isEmpty()) "لم تتم إضافة نطاقات بعد."
                else "لا توجد نطاقات تطابق البحث.",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(filteredRules, key = { it }) { rule ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(rule, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = {
                            input = rule
                            editingRule = rule
                        }) { Text("تعديل") }
                        TextButton(onClick = {
                            refreshRules(rules - rule)
                            if (editingRule == rule) {
                                editingRule = null
                                input = ""
                            }
                        }) { Text("حذف") }
                    }
                }
            }
        }
        Text(
            "يدعم TXT وCSV وHOSTS وقواعد AdBlock الأساسية. تُحفظ أسماء النطاقات فقط، ولا تُحفظ رموز التتبع أو بيانات الروابط.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
