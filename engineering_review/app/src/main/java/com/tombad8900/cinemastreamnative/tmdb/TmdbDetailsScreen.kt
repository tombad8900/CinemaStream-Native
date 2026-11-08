package com.tombad8900.cinemastreamnative.tmdb

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import androidx.compose.foundation.horizontalScroll

@Composable
fun TmdbDetailsScreen(
    media: TmdbMedia,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val apiKey = remember { TmdbApiKeyStore.get(context).orEmpty() }

    var details by remember(media.id, media.mediaType) {
        mutableStateOf<TmdbDetails?>(null)
    }
    var episodes by remember(media.id) {
        mutableStateOf<List<TmdbEpisode>>(emptyList())
    }
    var selectedSeason by remember(media.id) { mutableIntStateOf(-1) }
    var selectedEpisode by remember(media.id) { mutableIntStateOf(-1) }
    var loading by remember { mutableStateOf(true) }
    var episodesLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }

    LaunchedEffect(media.id, media.mediaType, apiKey) {
        if (apiKey.isBlank()) {
            error = "أضف مفتاح TMDB من الإعدادات أولًا."
            loading = false
        } else {
            loading = true
            error = ""
            try {
                val result = TmdbClient.fetchDetails(apiKey, media)
                details = result
                selectedSeason = result.seasons.firstOrNull { it.number > 0 }?.number
                    ?: result.seasons.firstOrNull()?.number ?: -1
            } catch (e: Exception) {
                error = e.message ?: "تعذر تحميل تفاصيل العمل."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(media.id, selectedSeason, apiKey) {
        if (media.mediaType == "tv" && selectedSeason >= 0 && apiKey.isNotBlank()) {
            episodesLoading = true
            selectedEpisode = -1
            try {
                episodes = TmdbClient.fetchSeasonEpisodes(
                    apiKey, media.id, selectedSeason
                )
            } catch (e: Exception) {
                episodes = emptyList()
                error = e.message ?: "تعذر تحميل الحلقات."
            } finally {
                episodesLoading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = onBack) {
            Text("رجوع")
        }

        details?.let { info ->
            AsyncImage(
                model = info.backdropPath?.let {
                    "https://image.tmdb.org/t/p/w780$it"
                } ?: media.posterUrl,
                contentDescription = info.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(205.dp),
                contentScale = ContentScale.Crop
            )

            Text(
                info.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                buildString {
                    append(if (media.mediaType == "movie") "فيلم" else "مسلسل")
                    if (info.year.isNotBlank()) append(" • ${info.year}")
                    append(" • ★ ${"%.1f".format(info.rating)}")
                    info.runtime?.let { append(" • $it دقيقة") }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )

            if (info.genres.isNotEmpty()) {
                Text(info.genres.joinToString(" • "))
            }

            Text(
                info.overview.ifBlank { "لا يوجد وصف متاح لهذا العمل." },
                style = MaterialTheme.typography.bodyMedium
            )

            if (media.mediaType == "tv") {
                HorizontalDivider()
                Text(
                    "المواسم والحلقات",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                if (info.seasons.isEmpty()) {
                    Text("لا توجد بيانات مواسم متاحة.")
                } else {
                    Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                        info.seasons.forEach { season ->
                            FilterChip(
                                selected = selectedSeason == season.number,
                                onClick = { selectedSeason = season.number },
                                label = { Text(season.name, maxLines = 1) }
                            )
                        }
                    }
                }

                if (episodesLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                episodes.forEach { episode ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedEpisode = episode.number
                                notice = ""
                            },
                        color = if (selectedEpisode == episode.number)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AsyncImage(
                                model = episode.stillUrl,
                                contentDescription = episode.name,
                                modifier = Modifier
                                    .width(112.dp)
                                    .height(72.dp),
                                contentScale = ContentScale.Crop
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "الحلقة ${episode.number}: ${episode.name}",
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    episode.overview.ifBlank { "لا يوجد وصف للحلقة." },
                                    maxLines = 3,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "★ ${"%.1f".format(episode.rating)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                if (selectedEpisode >= 0) {
                    Button(
                        onClick = {
                            notice = "تم اختيار الحلقة $selectedEpisode. أضف مصدر تشغيل لبدء المشاهدة."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("تشغيل الحلقة $selectedEpisode")
                    }
                }
            } else {
                Button(
                    onClick = {
                        notice = "أضف مصدر تشغيل لهذا الفيلم لبدء المشاهدة."
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("تشغيل الفيلم")
                }
            }
        }

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error)
        }

        if (notice.isNotBlank()) {
            Text(notice, color = MaterialTheme.colorScheme.secondary)
        }
    }
}
