package com.tombad8900.cinemastreamnative.tmdb

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun TmdbHomeScreen(onMediaClick: (TmdbMedia) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val apiKey = remember { TmdbApiKeyStore.get(context).orEmpty() }

    var filter by remember { mutableStateOf("all") }
    var data by remember(apiKey, filter) { mutableStateOf(TmdbClient.cachedHome(context, apiKey, filter)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(apiKey, filter) {
        if (apiKey.isBlank()) {
            data = null
            error = "أضف مفتاح TMDB من الإعدادات أولًا."
        } else {
            loading = data == null
            error = ""
            try {
                data = TmdbClient.fetchHome(context, apiKey, filter)
            } catch (e: Exception) {
                if (data == null) error = e.message ?: "تعذر تحميل المحتوى."
            } finally {
                loading = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "مكتبتك السينمائية",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
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

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error)
        }

        data?.let { result ->
            TmdbMediaSection("الأكثر رواجًا", result.trending, onMediaClick)
            TmdbMediaSection("الأكثر شعبية", result.popular, onMediaClick)
            TmdbMediaSection("الأعلى تقييمًا", result.topRated, onMediaClick)

            result.companies.forEach { company ->
                TmdbMediaSection("أحدث أعمال ${company.name}", company.items, onMediaClick)
            }
        }
    }
}

@Composable
private fun TmdbMediaSection(
    heading: String,
    items: List<TmdbMedia>,
    onMediaClick: (TmdbMedia) -> Unit
) {
    if (items.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            heading,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items.take(20).forEach { media ->
                Column(
                    modifier = Modifier.width(132.dp).clickable { onMediaClick(media) },
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    AsyncImage(
                        model = media.posterUrl,
                        contentDescription = media.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp),
                        contentScale = ContentScale.Crop
                    )
                    Text(
                        media.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2
                    )
                    Text(
                        "${media.year}  •  ★ ${"%.1f".format(media.rating)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}
