package com.tombad8900.cinemastreamnative.tmdb

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

private val LibraryGold = Color(0xFFE6B96E)

@Composable
fun TmdbLibraryBrowseScreen(
    onPersonClick: (TmdbPerson) -> Unit,
    onCollectionClick: (TmdbCollection) -> Unit
) {
    val context = LocalContext.current
    val apiKey = remember { TmdbApiKeyStore.get(context).orEmpty() }
    val scope = rememberCoroutineScope()

    var section by remember { mutableStateOf("people") }
    var query by remember { mutableStateOf("") }
    var people by remember { mutableStateOf<List<TmdbPerson>>(emptyList()) }
    var collections by remember { mutableStateOf<List<TmdbCollection>>(emptyList()) }
    var searchingPeople by remember { mutableStateOf<List<TmdbPerson>?>(null) }
    var searchingCollections by remember { mutableStateOf<List<TmdbCollection>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var searchLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(apiKey) {
        if (apiKey.isBlank()) {
            error = "أضف مفتاح TMDB من الإعدادات أولًا."
            return@LaunchedEffect
        }

        loading = true
        error = ""
        try {
            people = TmdbLibraryClient.popularPeople(apiKey)

            val seeds = listOf(
                "Harry Potter",
                "The Lord of the Rings",
                "The Maze Runner",
                "The Hunger Games",
                "Jurassic Park",
                "Fast and Furious",
                "Mission Impossible"
            )

            collections = seeds.flatMap { name ->
                TmdbLibraryClient.searchCollections(apiKey, name)
            }.distinctBy { it.id }
        } catch (e: Exception) {
            error = e.message ?: "تعذر تحميل بيانات المكتبة."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(query, section, apiKey) {
        searchingPeople = null
        searchingCollections = null

        if (query.isBlank() || apiKey.isBlank()) {
            searchLoading = false
            return@LaunchedEffect
        }

        error = ""
        searchLoading = true

        try {
            kotlinx.coroutines.delay(450)
            if (section == "people") {
                searchingPeople = TmdbLibraryClient.searchPeople(apiKey, query)
            } else {
                searchingCollections = TmdbLibraryClient.searchCollections(apiKey, query)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "تعذر البحث. تحقق من الاتصال ومفتاح TMDB."
        } finally {
            searchLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = section == "people",
                onClick = {
                    section = "people"
                    query = ""
                },
                label = { Text("الممثلون") }
            )
            FilterChip(
                selected = section == "collections",
                onClick = {
                    section = "collections"
                    query = ""
                },
                label = { Text("سلاسل الأفلام") }
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            label = {
                Text(if (section == "people") "ابحث عن ممثل أو ممثلة" else "ابحث عن سلسلة أفلام")
            }
        )

        if (loading || searchLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error)
        }

        if (section == "people") {
            val results = searchingPeople

            if (query.isNotBlank()) {
                if (results != null && results.isNotEmpty()) {
                    LibraryPersonSection("نتائج البحث", results, onPersonClick)
                } else if (results != null) {
                    Text("لم نعثر على ممثل بهذا الاسم.")
                }
            } else {
                Text(
                    "التصنيف تقريبي حسب لغات أبرز الأعمال المسجلة في TMDB، وليس حسب جنسية الممثل.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )

                val hollywood = people.filter { "en" in it.knownForLanguages }
                val bollywood = people.filter { "hi" in it.knownForLanguages }

                LibraryPersonSection("هوليوود — أعمال إنجليزية", hollywood, onPersonClick)
                LibraryPersonSection("بوليوود — أعمال هندية", bollywood, onPersonClick)
            }
        } else {
            val results = searchingCollections
            val items = if (query.isNotBlank()) results else collections

            if (items != null && items.isNotEmpty()) {
                LibraryCollectionSection(
                    if (query.isNotBlank()) "نتائج البحث" else "سلاسل أفلام شهيرة",
                    items,
                    onCollectionClick
                )
            } else if (query.isNotBlank() && items != null) {
                Text("لم نعثر على سلسلة بهذا الاسم.")
            }
        }
    }
}

@Composable
private fun LibraryPersonSection(
    title: String,
    people: List<TmdbPerson>,
    onClick: (TmdbPerson) -> Unit
) {
    if (people.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(
            title,
            color = LibraryGold,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            people.forEach { person ->
                Column(
                    modifier = Modifier
                        .width(112.dp)
                        .clickable { onClick(person) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AsyncImage(
                        model = person.photoUrl,
                        contentDescription = person.name,
                        modifier = Modifier
                            .size(108.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop
                    )
                    Text(
                        person.name,
                        modifier = Modifier.padding(top = 7.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryCollectionSection(
    title: String,
    collections: List<TmdbCollection>,
    onClick: (TmdbCollection) -> Unit
) {
    if (collections.isEmpty()) {
        Text(
            "لا توجد سلاسل مطابقة حاليًا.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(
            title,
            color = LibraryGold,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        collections.forEach { collection ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onClick(collection) }
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(9.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = collection.posterPath?.let {
                        "https://image.tmdb.org/t/p/w342$it"
                    },
                    contentDescription = collection.name,
                    modifier = Modifier
                        .width(76.dp)
                        .height(108.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        collection.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    if (collection.overview.isNotBlank()) {
                        Text(
                            collection.overview,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "فتح السلسلة",
                        color = LibraryGold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
