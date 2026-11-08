package com.tombad8900.cinemastreamnative.tmdb

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage

@Composable
fun TmdbDiscoverScreen(onMediaClick: (TmdbMedia) -> Unit) {
    val context = LocalContext.current
    val apiKey = remember { TmdbApiKeyStore.get(context).orEmpty() }

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var submittedQuery by remember { mutableStateOf("") }
    var searchTrigger by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<TmdbMedia>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("ابحث عن فيلم أو مسلسل") }

    LaunchedEffect(searchTrigger, filter) {
        if (searchTrigger == 0) return@LaunchedEffect

        if (apiKey.isBlank()) {
            message = "أضف مفتاح TMDB من الإعدادات أولًا."
            results = emptyList()
        } else {
            loading = true
            message = ""
            try {
                results = TmdbClient.search(apiKey, submittedQuery, filter)
                message = if (results.isEmpty()) "لا توجد نتائج." else ""
            } catch (e: Exception) {
                results = emptyList()
                message = e.message ?: "تعذر تنفيذ البحث."
            } finally {
                loading = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("استكشاف TMDB", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("اسم الفيلم أو المسلسل") },
            singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "الكل", "movie" to "أفلام", "tv" to "مسلسلات")
                .forEach { (value, label) ->
                    FilterChip(
                        selected = filter == value,
                        onClick = { filter = value },
                        label = { Text(label) }
                    )
                }
        }

        Button(
            onClick = {
                if (query.isBlank()) {
                    message = "اكتب اسمًا للبحث."
                    results = emptyList()
                } else {
                    submittedQuery = query.trim()
                    searchTrigger++
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (loading) "جارٍ البحث..." else "بحث")
        }

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (message.isNotBlank()) {
            Text(message, color = MaterialTheme.colorScheme.secondary)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(results, key = { "${it.mediaType}_${it.id}" }) { media ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onMediaClick(media) },
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AsyncImage(
                        model = media.posterUrl,
                        contentDescription = media.title,
                        modifier = Modifier.width(92.dp).height(132.dp),
                        contentScale = ContentScale.Crop
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            media.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${if (media.mediaType == "movie") "فيلم" else "مسلسل"} • ${media.year}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "التقييم: ${"%.1f".format(media.rating)} / 10",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            media.overview.ifBlank { "لا يوجد وصف متاح." },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
