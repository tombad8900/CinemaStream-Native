package com.tombad8900.cinemastreamnative.tmdb

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private val HomeGold = Color(0xFFE6B96E)
private val HomeOnGold = Color(0xFF2A1745)

private val TmdbMedia.backdropUrl: String?
    get() = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" }

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
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "الكل", "movie" to "أفلام", "tv" to "مسلسلات").forEach { (value, label) ->
                FilterChip(
                    selected = filter == value,
                    onClick = { filter = value },
                    label = { Text(label) }
                )
            }
        }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)

        data?.let { r ->
            val hero = r.trending.firstOrNull { it.backdropPath != null } ?: r.trending.firstOrNull()
            if (hero != null) HeroBanner(hero, onMediaClick)
            TmdbMediaSection("الأكثر رواجًا اليوم", r.trending, onMediaClick)
            TmdbMediaSection("أفلام: الأعلى مشاهدة", r.popular.filter { it.mediaType == "movie" }, onMediaClick)
            TmdbMediaSection("مسلسلات: الأعلى مشاهدة", r.popular.filter { it.mediaType == "tv" }, onMediaClick)
            TmdbMediaSection("أفلام: الأعلى تقييمًا", r.topRated.filter { it.mediaType == "movie" }, onMediaClick)
            TmdbMediaSection("مسلسلات: الأعلى تقييمًا", r.topRated.filter { it.mediaType == "tv" }, onMediaClick)
            r.extras.forEach { TmdbMediaSection(it.name, it.items, onMediaClick) }
            r.companies.forEach { TmdbMediaSection("أحدث أعمال ${it.name}", it.items, onMediaClick) }
        }
    }
}

@Composable
private fun HeroBanner(media: TmdbMedia, onMediaClick: (TmdbMedia) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable { onMediaClick(media) }
    ) {
        AsyncImage(
            model = media.backdropUrl ?: media.posterUrl,
            contentDescription = media.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("الأكثر رواجًا", color = HomeGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(
                media.title,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("★ ${"%.1f".format(media.rating)}  •  ${media.year}", color = Color.White, fontSize = 12.sp)
                Button(
                    onClick = { onMediaClick(media) },
                    colors = ButtonDefaults.buttonColors(containerColor = HomeGold, contentColor = HomeOnGold),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                ) { Text("التفاصيل") }
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
        Text(heading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items.take(20).forEach { media ->
                Column(
                    modifier = Modifier.width(132.dp).clickable { onMediaClick(media) },
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        AsyncImage(
                            model = media.posterUrl,
                            contentDescription = media.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        if (media.rating > 0) {
                            Text(
                                "★ ${"%.1f".format(media.rating)}",
                                color = HomeGold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(6.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xB3000000))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        media.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        media.year,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}
