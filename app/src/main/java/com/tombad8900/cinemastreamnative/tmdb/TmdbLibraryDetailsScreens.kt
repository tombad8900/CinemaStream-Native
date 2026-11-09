package com.tombad8900.cinemastreamnative.tmdb

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
fun TmdbPersonDetailsScreen(
    person: TmdbPerson,
    onBack: () -> Unit,
    onMediaClick: (TmdbMedia) -> Unit
) {
    val context = LocalContext.current
    val apiKey = remember { TmdbApiKeyStore.get(context).orEmpty() }

    var details by remember(person.id) { mutableStateOf(person) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(person.id, apiKey) {
        if (apiKey.isBlank()) {
            error = "أضف مفتاح TMDB من الإعدادات."
            loading = false
            return@LaunchedEffect
        }
        try {
            details = TmdbLibraryClient.personDetails(apiKey, person.id)
        } catch (e: Exception) {
            error = e.message ?: "تعذر تحميل بيانات الممثل."
        } finally {
            loading = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        TextButton(onClick = onBack) { Text("رجوع") }

        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = details.photoUrl,
                contentDescription = details.name,
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )
            Text(
                details.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error)
        }

        if (details.biography.isNotBlank()) {
            Text("نبذة", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Text(details.biography, lineHeight = 23.sp)
        }

        if (details.knownFor.isNotEmpty()) {
            Text(
                "الأعمال السينمائية",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            details.knownFor.forEach { media ->
                LibraryWorkRow(media, onMediaClick)
            }
        } else if (!loading && error.isBlank()) {
            Text("لا تتوفر أعمال مسجلة لهذا الممثل حاليًا.")
        }
    }
}

@Composable
fun TmdbCollectionDetailsScreen(
    collection: TmdbCollection,
    onBack: () -> Unit,
    onMediaClick: (TmdbMedia) -> Unit
) {
    val context = LocalContext.current
    val apiKey = remember { TmdbApiKeyStore.get(context).orEmpty() }

    var details by remember(collection.id) { mutableStateOf(collection) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(collection.id, apiKey) {
        if (apiKey.isBlank()) {
            error = "أضف مفتاح TMDB من الإعدادات."
            loading = false
            return@LaunchedEffect
        }
        try {
            details = TmdbLibraryClient.collectionDetails(apiKey, collection.id)
        } catch (e: Exception) {
            error = e.message ?: "تعذر تحميل السلسلة."
        } finally {
            loading = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        TextButton(onClick = onBack) { Text("رجوع") }

        AsyncImage(
            model = details.backdropPath?.let {
                "https://image.tmdb.org/t/p/w780$it"
            } ?: details.posterPath?.let {
                "https://image.tmdb.org/t/p/w500$it"
            },
            contentDescription = details.name,
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )

        Text(
            details.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        if (details.overview.isNotBlank()) {
            Text(details.overview, lineHeight = 23.sp)
        }

        Text(
            "أجزاء السلسلة (${details.movies.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error)
        }

        details.movies.forEachIndexed { index, media ->
            LibraryWorkRow(media, onMediaClick, index + 1)
        }

        if (!loading && details.movies.isEmpty() && error.isBlank()) {
            Text("لا توجد أفلام متاحة لهذه السلسلة.")
        }
    }
}

@Composable
private fun LibraryWorkRow(
    media: TmdbMedia,
    onClick: (TmdbMedia) -> Unit,
    number: Int? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable { onClick(media) }
            .padding(9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = media.posterUrl,
            contentDescription = media.title,
            modifier = Modifier
                .width(68.dp)
                .height(98.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )

        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(number?.let { "$it. " }, media.title).joinToString(""),
                fontWeight = FontWeight.Bold
            )
            Text(
                listOfNotNull(
                    media.year.takeIf { it.isNotBlank() },
                    if (media.mediaType == "tv") "مسلسل" else "فيلم"
                ).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (media.rating > 0) {
                Text(
                    "التقييم ${"%.1f".format(media.rating)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Text("فتح", color = MaterialTheme.colorScheme.primary)
    }
}
