package com.tombad8900.cinemastreamnative.subtitle

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tombad8900.cinemastreamnative.PlaybackMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SubtitleSearchItem(
    val provider: String,
    val name: String,
    val language: String,
    val format: String,
    val release: String,
    val payload: Any
)

private data class SubtitleProviderOutcome(
    val provider: String,
    val results: List<SubtitleSearchItem>,
    val error: String? = null
)

private object SubtitleSearchEngine {

    suspend fun search(
        context: Context,
        title: String,
        language: String,
        metadata: PlaybackMetadata
    ): List<SubtitleProviderOutcome> = coroutineScope {

        val tasks = listOf(
            async(Dispatchers.IO) {
                runCatching {
                    val key = SubtitleApiKeyStore.get(context, "subdl")
                        ?: error("لم يُضف مفتاح SubDL")

                    val type = when (metadata.contentType) {
                "movie" -> "movie"
                "tv" -> "tv"
                else -> null
            }

                    val results = SubdlClient.search(
                        apiKey = key,
                        title = title,
                        type = type,
                        language = language.uppercase(),
                        year = metadata.year,
                        season = metadata.seasonNumber,
                        episode = metadata.episodeNumber,
                        tmdbId = metadata.tmdbId,
                        imdbId = metadata.imdbId
                    )

                    SubtitleProviderOutcome(
                        "SubDL",
                        results.map {
                            SubtitleSearchItem(
                                "SubDL",
                                it.name,
                                it.language,
                                it.format,
                                it.releaseName,
                                it
                            )
                        }
                    )
                }.getOrElse {
                    SubtitleProviderOutcome("SubDL", emptyList(), it.message)
                }
            },

            async(Dispatchers.IO) {
                runCatching {
                    val results = SubtitlesWebsiteClient.search(
                query = if (metadata.isEpisode)
                    "$title S${metadata.seasonNumber.toString().padStart(2, '0')}E${metadata.episodeNumber.toString().padStart(2, '0')}"
                else title,
                language = language,
                imdbId = if (metadata.isEpisode) null else metadata.imdbId
            )

                    SubtitleProviderOutcome(
                        "Subtitles.website",
                        results.map {
                            SubtitleSearchItem(
                                "Subtitles.website",
                                it.name,
                                it.language,
                                it.format,
                                it.release,
                                it
                            )
                        }
                    )
                }.getOrElse {
                    SubtitleProviderOutcome(
                        "Subtitles.website",
                        emptyList(),
                        it.message
                    )
                }
            },

            async(Dispatchers.IO) {
                runCatching {
                    val results = OpenSubtitlesClient.search(
                        context = context,
                        query = title,
                        language = language,
                        imdbId = metadata.imdbId,
                        type = when {
                            metadata.isEpisode -> "episode"
                            metadata.contentType == "movie" -> "movie"
                            else -> null
                        },
                        year = metadata.year,
                        season = metadata.seasonNumber,
                        episode = metadata.episodeNumber
                    )

                    SubtitleProviderOutcome(
                        "OpenSubtitles",
                        results.map {
                            SubtitleSearchItem(
                                "OpenSubtitles",
                                it.name,
                                it.language,
                                it.format,
                                it.release,
                                it
                            )
                        }
                    )
                }.getOrElse {
                    SubtitleProviderOutcome(
                        "OpenSubtitles",
                        emptyList(),
                        it.message
                    )
                }
            }
        )

        tasks.awaitAll()
    }

    suspend fun download(
        context: Context,
        item: SubtitleSearchItem
    ): ImportedSubtitle = withContext(Dispatchers.IO) {

        val downloaded = when (val data = item.payload) {
            is SubdlSubtitle ->
                SubdlClient.download(context, data)

            is OpenSubtitleResult ->
                OpenSubtitlesClient.download(context, data)

            is WebsiteSubtitleResult ->
                SubtitlesWebsiteClient.download(context, data)

            else -> error("نوع الترجمة غير معروف")
        }

        try {
            SubtitleFileManager.importDownloadedFile(context, downloaded)
        } finally {
            downloaded.delete()
        }
    }
}

@Composable
fun SubtitleSearchScreen(
    metadata: PlaybackMetadata,
    preferredLanguage: String = "ar",
    onSubtitleReady: (ImportedSubtitle, String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf(metadata.title) }
    var language by remember { mutableStateOf(preferredLanguage) }
    var outcomes by remember { mutableStateOf<List<SubtitleProviderOutcome>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    val gold = Color(0xFFE6B96F)
    val panel = Color(0xFF151B29)
    val allResults = outcomes.flatMap { it.results }.distinctBy {
            val key = it.release.ifBlank { it.name }
                .lowercase()
                .replace(Regex("[^\\p{L}\\p{N}]"), "")
            "${it.language.lowercase()}|${it.format.lowercase()}|$key"
        }

    fun search() {
        if (title.isBlank() || loading || downloading) return

        loading = true
        status = "جارٍ البحث في المصادر..."
        outcomes = emptyList()

        scope.launch {
            val result = runCatching {
                SubtitleSearchEngine.search(
                    context,
                    title.trim(),
                    language,
                    metadata
                )
            }

            result.onSuccess {
                outcomes = it
                val count = it.sumOf { provider -> provider.results.size }
                status = if (count > 0) {
                    "تم العثور على $count نتيجة"
                } else {
                    "لم يتم العثور على نتائج"
                }
            }.onFailure {
                status = it.message ?: "تعذر تنفيذ البحث"
            }

            loading = false
        }
    }

    LaunchedEffect(Unit) {
        search()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = panel
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "البحث عن ترجمة",
                        color = gold,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    TextButton(onClick = onDismiss) {
                        Text("إغلاق", color = gold)
                    }
                }

                Text(
                    metadata.title +
                        if (metadata.isEpisode) {
                            " · الموسم ${metadata.seasonNumber} · الحلقة ${metadata.episodeNumber}"
                        } else "",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("اسم الفيلم أو المسلسل") }
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("لغة الترجمة", color = Color.White)

                    FilterChip(
                        selected = language == "ar",
                        onClick = { language = "ar" },
                        label = { Text("العربية") }
                    )

                    FilterChip(
                        selected = language == "en",
                        onClick = { language = "en" },
                        label = { Text("English") }
                    )

                    Spacer(Modifier.weight(1f))

                    Button(
                        onClick = { search() },
                        enabled = !loading && !downloading && title.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = gold
                        )
                    ) {
                        Text("بحث", color = Color.Black)
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (loading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = gold
                    )
                }

                Text(
                    status,
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(8.dp))

                outcomes.forEach { outcome ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            outcome.provider,
                            color = gold,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            outcome.error ?: "${outcome.results.size} نتيجة",
                            color = if (outcome.error == null)
                                Color.LightGray else Color(0xFFFF8A80),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                HorizontalDivider(color = Color.DarkGray)

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    items(allResults) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF20293A)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Text(
                                    item.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    "${item.provider} · ${item.language} · ${item.format.uppercase()}",
                                    color = gold,
                                    style = MaterialTheme.typography.bodySmall
                                )

                                if (item.release.isNotBlank()) {
                                    Text(
                                        item.release,
                                        color = Color.LightGray,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                TextButton(
                                    enabled = !downloading,
                                    onClick = {
                                        downloading = true
                                        status = "جارٍ تنزيل الترجمة والتحقق منها..."

                                        scope.launch {
                                            runCatching {
                                                SubtitleSearchEngine.download(
                                                    context,
                                                    item
                                                )
                                            }.onSuccess { imported ->
                                                status = "تم تجهيز الترجمة"
                                                onSubtitleReady(imported, language)
                                            }.onFailure {
                                                status = it.message
                                                    ?: "فشل تنزيل الترجمة أو التحقق منها"
                                            }

                                            downloading = false
                                        }
                                    }
                                ) {
                                    Text("تنزيل واستخدام", color = gold)
                                }
                            }
                        }
                    }
                }

                if (downloading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = gold,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("جارٍ تجهيز الترجمة...", color = Color.White)
                    }
                }
            }
        }
    }
}
