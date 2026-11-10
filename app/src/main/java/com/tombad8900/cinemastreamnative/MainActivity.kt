package com.tombad8900.cinemastreamnative
import com.tombad8900.cinemastreamnative.sources.SourceSettingsScreen

import com.tombad8900.cinemastreamnative.tmdb.TmdbDiscoverScreen

import com.tombad8900.cinemastreamnative.tmdb.TmdbHomeScreen
import com.tombad8900.cinemastreamnative.tmdb.TmdbMedia
import com.tombad8900.cinemastreamnative.tmdb.TmdbDetailsScreen
import com.tombad8900.cinemastreamnative.tmdb.TmdbPerson
import com.tombad8900.cinemastreamnative.tmdb.TmdbCollection
import com.tombad8900.cinemastreamnative.tmdb.TmdbLibraryBrowseScreen
import com.tombad8900.cinemastreamnative.tmdb.TmdbPersonDetailsScreen
import com.tombad8900.cinemastreamnative.tmdb.TmdbCollectionDetailsScreen

import com.tombad8900.cinemastreamnative.tmdb.TmdbSettingsScreen

import com.tombad8900.cinemastreamnative.subtitle.SubtitleSettingsScreen
import com.tombad8900.cinemastreamnative.subtitle.SubtitleProviderSettingsScreen
import com.tombad8900.cinemastreamnative.security.AdBlockSettingsScreen

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler

private val Gold = Color(0xFFE6B96F)
private val Muted = Color(0xFF9BA5B5)
private val Background = Color(0xFF080B12)
private val Panel = Color(0xFF151B29)

private data class MediaItem(
    val title: String,
    val type: String,
    val year: String,
    val description: String
)

private data class Channel(
    val name: String,
    val url: String,
    val group: String
)

private val catalog = listOf(
    MediaItem("Interstellar", "فيلم", "2014", "رحلة خيال علمي عبر الزمن والفضاء"),
    MediaItem("Inception", "فيلم", "2010", "عالم الأحلام والأفكار"),
    MediaItem("The Dark Knight", "فيلم", "2008", "مواجهة استثنائية في مدينة غوثام"),
    MediaItem("Dune: Part Two", "فيلم", "2024", "ملحمة خيال علمي"),
    MediaItem("Breaking Bad", "مسلسل", "2008", "دراما وجريمة"),
    MediaItem("Dark", "مسلسل", "2017", "غموض وسفر عبر الزمن"),
    MediaItem("The Last of Us", "مسلسل", "2023", "دراما ومغامرة")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val playWithMetadata: (String, String, Map<String, String>, List<String>) -> Unit =
                { url, title, headers, subtitleUrls ->
                    val playerIntent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_URL, url)
                        putExtra(PlayerActivity.EXTRA_TITLE, title)

                        val catalogItem = catalog.firstOrNull {
                            it.title.equals(title, ignoreCase = true)
                        }
                        val metadata = PlaybackMetadata(
                            title = title,
                            year = catalogItem?.year?.toIntOrNull(),
                            contentType = when (catalogItem?.type) {
                                "مسلسل" -> "tv"
                                "فيلم" -> "movie"
                                else -> "unknown"
                            }
                        )
                        putExtra(PlayerActivity.EXTRA_PLAYBACK_METADATA, metadata.toJson())

                        if (headers.isNotEmpty()) {
                            putExtra(PlayerActivity.EXTRA_HEADERS, Bundle().apply {
                                headers.forEach { (key, value) -> putString(key, value) }
                            })
                        }
                        putStringArrayListExtra(
                            PlayerActivity.EXTRA_SUBTITLE_URLS,
                            ArrayList(subtitleUrls.distinct())
                        )
                    }
                    startActivity(playerIntent)
                }

            CinemaStreamApp(
                onPlayUrl = { url, title ->
                    playWithMetadata(url, title, emptyMap(), emptyList())
                },
                onPlayCaptured = playWithMetadata
            )
        }
    }
}

@Composable
private fun CinemaStreamApp(
    onPlayUrl: (String, String) -> Unit,
    onPlayCaptured: (String, String, Map<String, String>, List<String>) -> Unit
) {
    val tabs = listOf("الرئيسية", "استكشاف", "مكتبتي", "IPTV", "المتصفح", "الإعدادات")
    val symbols = listOf("⌂", "⌕", "♡", "▣", "◎", "⚙")

    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedMedia by remember { mutableStateOf<TmdbMedia?>(null) }
    var selectedPerson by remember { mutableStateOf<TmdbPerson?>(null) }
    var selectedCollection by remember { mutableStateOf<TmdbCollection?>(null) }
    var visitedTabs by remember { mutableStateOf(setOf(0)) }

    LaunchedEffect(selectedTab) {
        visitedTabs = visitedTabs + selectedTab
    }

    val homeScrollState = rememberScrollState()
    val libraryScrollState = rememberScrollState()
    val settingsScrollState = rememberScrollState()

    val appContext = androidx.compose.ui.platform.LocalContext.current

    var browserSources by remember {
        mutableStateOf<List<com.tombad8900.cinemastreamnative.sources.StreamSource>>(emptyList())
    }
    var browserMedia by remember { mutableStateOf<TmdbMedia?>(null) }
    var browserSeason by remember { mutableIntStateOf(-1) }
    var browserEpisode by remember { mutableIntStateOf(-1) }
    var sourcePickerRequest by remember { mutableIntStateOf(0) }

    BackHandler(
        enabled = selectedMedia != null ||
            selectedPerson != null ||
            selectedCollection != null ||
            selectedTab != 0
    ) {
        when {
            selectedMedia != null -> selectedMedia = null
            selectedPerson != null -> selectedPerson = null
            selectedCollection != null -> selectedCollection = null
            else -> selectedTab = 0
        }
    }

    val favorites = remember { mutableStateListOf<MediaItem>() }
    val watched = remember { mutableStateListOf<MediaItem>() }
    val watchLater = remember {
        mutableStateListOf<TmdbMedia>().apply {
            addAll(WatchLaterStore.load(appContext))
        }
    }


    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Gold,
                background = Background,
                surface = Panel,
                onBackground = Color.White,
                onSurface = Color.White
            )
        ) {
            Scaffold(
                containerColor = Background,
                bottomBar = {
                    NavigationBar(containerColor = Color(0xFF101521)) {
                        tabs.forEachIndexed { index, label ->
                            NavigationBarItem(
                                selected = selectedTab == index,
                                onClick = {
                                    selectedMedia = null
                                    selectedTab = index
                                },
                                icon = { Text(symbols[index], fontSize = 20.sp) },
                                label = { Text(label, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Gold,
                                    selectedTextColor = Gold,
                                    unselectedIconColor = Muted,
                                    unselectedTextColor = Muted,
                                    indicatorColor = Color(0xFF30291F)
                                )
                            )
                        }
                    }
                }
            ) { padding ->

                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .clipToBounds()
                ) {

                    if (0 in visitedTabs) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = if (selectedTab == 0 && selectedMedia == null) 1f else 0f }
                                .zIndex(if (selectedTab == 0 && selectedMedia == null) 1f else 0f)
                                .verticalScroll(homeScrollState)
                                .padding(horizontal = 18.dp)
                        ) {
                            Text("C I N E M A S T R E A M", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            Text(tabs[0], color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(18.dp))
                            HomeScreen(
                                favorites.size,
                                watched.size,
                                onExplore = { selectedTab = 1 },
                                onLibrary = { selectedTab = 2 },
                                onPlayUrl = onPlayUrl,
                                onMediaClick = { selectedMedia = it }
                            )
                            Spacer(Modifier.height(28.dp))
                        }
                    }

                    if (1 in visitedTabs) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = if (selectedTab == 1 && selectedMedia == null) 1f else 0f }
                                .zIndex(if (selectedTab == 1 && selectedMedia == null) 1f else 0f)
                                .padding(horizontal = 18.dp)
                        ) {
                            Text("C I N E M A S T R E A M", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            Text(tabs[1], color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(18.dp))
                            DiscoverScreen(favorites, watched, onMediaClick = { selectedMedia = it })
                        }
                    }

                    if (2 in visitedTabs) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = if (selectedTab == 2 && selectedMedia == null && selectedPerson == null && selectedCollection == null) 1f else 0f }
                                .zIndex(if (selectedTab == 2 && selectedMedia == null && selectedPerson == null && selectedCollection == null) 1f else 0f)
                                .verticalScroll(libraryScrollState)
                                .padding(horizontal = 18.dp)
                        ) {
                            Text("C I N E M A S T R E A M", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            Text(tabs[2], color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(18.dp))
                            LibraryScreen(favorites, watched, watchLater) {
                                WatchLaterStore.save(appContext, watchLater)
                            }

                            Spacer(Modifier.height(22.dp))

                            TmdbLibraryBrowseScreen(
                                onPersonClick = { selectedPerson = it },
                                onCollectionClick = { selectedCollection = it }
                            )
                            Spacer(Modifier.height(28.dp))
                        }
                    }

                    if (3 in visitedTabs) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = if (selectedTab == 3) 1f else 0f }
                                .zIndex(if (selectedTab == 3) 1f else 0f)
                                .padding(horizontal = 18.dp)
                        ) {
                            Text("C I N E M A S T R E A M", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            Text(tabs[3], color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(14.dp))
                            IptvManagerScreen(
                                onPlayUrl = onPlayUrl,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (selectedTab == 4 && selectedMedia == null) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 18.dp)
                    ) {
                            Text("C I N E M A S T R E A M", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            Text(tabs[4], color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(18.dp))
                            com.tombad8900.cinemastreamnative.browser.BrowserScreen(
                                onPlayUrl = onPlayUrl,
                                onPlayCaptured = onPlayCaptured,
                                sources = browserSources,
                                media = browserMedia,
                                season = browserSeason,
                                episode = browserEpisode,
                                sourcePickerRequest = sourcePickerRequest,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (5 in visitedTabs) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = if (selectedTab == 5) 1f else 0f }
                                .zIndex(if (selectedTab == 5) 1f else 0f)
                                .verticalScroll(settingsScrollState)
                                .padding(horizontal = 18.dp)
                        ) {
                            Text("C I N E M A S T R E A M", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            Text(tabs[5], color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(18.dp))
                            SettingsScreen()
                            Spacer(Modifier.height(28.dp))
                        }
                    }

                    if (selectedMedia != null) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .background(Background)
                                .zIndex(10f)
                                .padding(horizontal = 18.dp)
                        ) {
                            Text("C I N E M A S T R E A M", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            Text(tabs[selectedTab], color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(18.dp))
                            val currentMedia = selectedMedia!!
                            val isInWatchLater = watchLater.any {
                                it.id == currentMedia.id && it.mediaType == currentMedia.mediaType
                            }
                            Button(
                                onClick = {
                                    if (isInWatchLater) {
                                        watchLater.removeAll {
                                            it.id == currentMedia.id && it.mediaType == currentMedia.mediaType
                                        }
                                    } else {
                                        watchLater.add(currentMedia)
                                    }
                                    WatchLaterStore.save(appContext, watchLater)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Gold),
                                shape = RoundedCornerShape(13.dp)
                            ) {
                                Text(
                                    if (isInWatchLater) "إزالة من مشاهدة لاحقًا" else "إضافة إلى مشاهدة لاحقًا",
                                    color = Color.Black
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            TmdbDetailsScreen(
                                media = selectedMedia!!,
                                onBack = { selectedMedia = null },
                                onPlay = { season, episode ->
                                    browserSources = com.tombad8900.cinemastreamnative.sources.SourceStore
                                        .load(appContext)
                                        .filter { it.enabled && !it.hidden }
                                        .sortedBy { it.priority }

                                    browserMedia = selectedMedia
                                    browserSeason = season
                                    browserEpisode = episode
                                    selectedMedia = null
                                    selectedTab = 4
                                }
                            )
                        }
                    }

                    if (selectedPerson != null) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .background(Background)
                                .zIndex(10f)
                                .padding(horizontal = 18.dp)
                        ) {
                            Text(
                                "C I N E M A S T R E A M",
                                color = Gold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(10.dp))
                            TmdbPersonDetailsScreen(
                                person = selectedPerson!!,
                                onBack = { selectedPerson = null },
                                onMediaClick = {
                                    selectedPerson = null
                                    selectedMedia = it
                                }
                            )
                        }
                    }

                    if (selectedCollection != null) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .background(Background)
                                .zIndex(10f)
                                .padding(horizontal = 18.dp)
                        ) {
                            Text(
                                "C I N E M A S T R E A M",
                                color = Gold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(10.dp))
                            TmdbCollectionDetailsScreen(
                                collection = selectedCollection!!,
                                onBack = { selectedCollection = null },
                                onMediaClick = {
                                    selectedCollection = null
                                    selectedMedia = it
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun HomeScreen(
    favoriteCount: Int,
    watchedCount: Int,
    onExplore: () -> Unit,
    onLibrary: () -> Unit,
    onPlayUrl: (String, String) -> Unit,
    onMediaClick: (TmdbMedia) -> Unit
) {
    TmdbHomeScreen(onMediaClick)
    return

    Box(
        Modifier
            .fillMaxWidth()
            .height(225.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF49351F), Color(0xFF202638), Color(0xFF101521))
                )
            )
            .padding(22.dp)
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("YOUR CINEMA", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(
                    "حكايتك القادمة\nتبدأ من هنا",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 37.sp
                )
                Text("عالمك السينمائي في مكان واحد", color = Muted, fontSize = 13.sp)
            }
            Button(
                onClick = onExplore,
                colors = ButtonDefaults.buttonColors(containerColor = Gold),
                shape = RoundedCornerShape(13.dp)
            ) {
                Text("اكتشف مكتبتك", color = Color(0xFF17120B), fontWeight = FontWeight.Bold)
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    SectionTitle("مساحتك السينمائية")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard("قائمتي", favoriteCount.toString(), Modifier.weight(1f), onLibrary)
        StatCard("شاهدتها", watchedCount.toString(), Modifier.weight(1f), onLibrary)
    }
    Spacer(Modifier.height(22.dp))
    SectionTitle("ابدأ من هنا")
    ActionCard("استكشاف الأفلام والمسلسلات", "ابحث في المكتبة واكتشف أعمالًا جديدة", onExplore)
    ActionCard("قنوات IPTV", "أضف قائمة M3U الخاصة بك", onExplore = {})
    DirectPlaybackCard(onPlayUrl)
}


@Composable
private fun DirectPlaybackCard(onPlayUrl: (String, String) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    var videoUrl by remember { mutableStateOf("") }
    var videoTitle by remember { mutableStateOf("") }

    ActionCard(
        "تشغيل رابط مباشر",
        "شغّل روابط MP4 وM3U8 وMPD بالمشغل الأصلي",
        onExplore = { showDialog = true }
    )

    if (showDialog) {
        val cleanUrl = videoUrl.trim()
        val validUrl = cleanUrl.startsWith("https://", true) ||
            cleanUrl.startsWith("http://", true)

        AlertDialog(
            onDismissRequest = { showDialog = false },
            containerColor = Panel,
            title = { Text("تشغيل رابط مباشر", color = Gold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = videoUrl,
                        onValueChange = { videoUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("رابط الفيديو") },
                        placeholder = { Text("https://example.com/video.m3u8") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = videoTitle,
                        onValueChange = { videoTitle = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("اسم الفيديو (اختياري)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = validUrl,
                    onClick = {
                        onPlayUrl(cleanUrl, videoTitle.trim())
                        showDialog = false
                    }
                ) {
                    Text("تشغيل", color = Gold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("إلغاء", color = Muted)
                }
            }
        )
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = Panel,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(17.dp)) {
            Text(value, color = Gold, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text(title, color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ActionCard(title: String, description: String, onExplore: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable(onClick = onExplore),
        color = Panel,
        shape = RoundedCornerShape(17.dp)
    ) {
        Column(Modifier.padding(17.dp)) {
            Text(title, color = Gold, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(description, color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun DiscoverScreen(
    favorites: MutableList<MediaItem>,
    watched: MutableList<MediaItem>,
    onMediaClick: (TmdbMedia) -> Unit
) {
    TmdbDiscoverScreen(onMediaClick)
    return

    var query by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("الكل") }
    val types = listOf("الكل", "فيلم", "مسلسل")
    val results = catalog.filter {
        (selectedType == "الكل" || it.type == selectedType) &&
            (query.isBlank() || it.title.contains(query, ignoreCase = true) ||
                it.description.contains(query, ignoreCase = true))
    }

    Text("ابحث عن فيلم أو مسلسل", color = Muted, fontSize = 14.sp)
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("اكتب اسم العمل...", color = Muted) },
        singleLine = true,
        shape = RoundedCornerShape(15.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Gold,
            unfocusedBorderColor = Color(0xFF343D4D),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White
        )
    )
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        types.forEach { type ->
            FilterChip(
                selected = selectedType == type,
                onClick = { selectedType = type },
                label = { Text(type) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF49351F),
                    selectedLabelColor = Gold
                )
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Text("${results.size} نتائج", color = Muted, fontSize = 12.sp)
    Spacer(Modifier.height(8.dp))
    results.forEach { item ->
        MediaCard(item, favorites, watched)
    }
    if (results.isEmpty()) {
        Text("لا توجد نتائج مطابقة.", color = Muted)
    }
}

@Composable
private fun MediaCard(
    item: MediaItem,
    favorites: MutableList<MediaItem>,
    watched: MutableList<MediaItem>
) {
    val isFavorite = favorites.contains(item)
    val isWatched = watched.contains(item)
    Surface(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        color = Panel,
        shape = RoundedCornerShape(17.dp)
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("${item.type} • ${item.year}", color = Gold, fontSize = 12.sp)
                }
                Text("▶", color = Gold, fontSize = 22.sp)
            }
            Spacer(Modifier.height(7.dp))
            Text(item.description, color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (isFavorite) favorites.remove(item) else favorites.add(item)
                    }
                ) {
                    Text(if (isFavorite) "إزالة من قائمتي" else "＋ قائمتي", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = {
                        if (isWatched) watched.remove(item) else watched.add(item)
                    }
                ) {
                    Text(if (isWatched) "إلغاء المشاهدة" else "تمت المشاهدة", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    favorites: MutableList<MediaItem>,
    watched: MutableList<MediaItem>,
    watchLater: MutableList<TmdbMedia>,
    onWatchLaterChange: () -> Unit
) {
    var section by remember { mutableStateOf("قائمتي") }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("قائمتي", "شاهدتها", "مشاهدة لاحقًا").forEach {
            FilterChip(
                selected = section == it,
                onClick = { section = it },
                label = { Text(it) }
            )
        }
    }

    Spacer(Modifier.height(15.dp))

    if (section == "مشاهدة لاحقًا") {
        if (watchLater.isEmpty()) {
            Surface(
                Modifier.fillMaxWidth(),
                color = Panel,
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(22.dp)) {
                    Text("قائمة مشاهدة لاحقًا فارغة", color = Gold, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("أضف الأفلام والمسلسلات من صفحة تفاصيل العمل.", color = Muted, fontSize = 13.sp)
                }
            }
        } else {
            watchLater.toList().forEach { media ->
                Surface(
                    Modifier.fillMaxWidth().padding(bottom = 9.dp),
                    color = Panel,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(media.title, color = Color.White, fontWeight = FontWeight.Bold)
                            Text(
                                "${if (media.mediaType == "tv") "مسلسل" else "فيلم"} • ${media.year}",
                                color = Muted,
                                fontSize = 12.sp
                            )
                        }
                        TextButton(onClick = {
                            watchLater.removeAll {
                                it.id == media.id && it.mediaType == media.mediaType
                            }
                            onWatchLaterChange()
                        }) {
                            Text("إزالة", color = Gold)
                        }
                    }
                }
            }
        }
    } else {
        val items = if (section == "قائمتي") favorites.toList() else watched.toList()
        if (items.isEmpty()) {
            Surface(Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(22.dp)) {
                    Text("مكتبتك تبدأ من هنا", color = Gold, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("أضف الأعمال إلى قائمتك من قسم الاستكشاف.", color = Muted, fontSize = 13.sp)
                }
            }
        } else {
            items.forEach { item -> MediaCard(item, favorites, watched) }
        }
    }
}
@Composable
private fun IptvScreen(channels: MutableList<Channel>) {
    var playlistText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("الصق محتوى قائمة M3U النصي لاستيراد القنوات.") }

    SectionTitle("استيراد قائمة M3U")
    Text(
        "الإصدار الحالي يستورد النص الذي تلصقه هنا. جلب الروابط من الإنترنت وربط دليل البرامج سيأتيان في مرحلة لاحقة.",
        color = Muted,
        fontSize = 13.sp,
        lineHeight = 20.sp
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = playlistText,
        onValueChange = { playlistText = it },
        modifier = Modifier.fillMaxWidth().height(170.dp),
        placeholder = { Text("#EXTM3U\n#EXTINF:-1,اسم القناة\nhttps://...") },
        shape = RoundedCornerShape(15.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Gold,
            unfocusedBorderColor = Color(0xFF343D4D),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White
        )
    )
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = {
            val lines = playlistText.lines()
            val imported = mutableListOf<Channel>()
            var name = "قناة"
            var group = "عام"
            lines.forEach { raw ->
                val line = raw.trim()
                when {
                    line.startsWith("#EXTINF", true) -> {
                        name = line.substringAfterLast(",").trim().ifBlank { "قناة" }
                        group = Regex("""group-title="([^"]*)"""")
                            .find(line)?.groupValues?.get(1) ?: "عام"
                    }
                    line.startsWith("http://", true) || line.startsWith("https://", true) -> {
                        imported.add(Channel(name, line, group))
                    }
                }
            }
            channels.clear()
            channels.addAll(imported)
            message = if (imported.isEmpty()) {
                "لم يتم العثور على قنوات. تحقق من تنسيق M3U."
            } else {
                "تم استيراد ${imported.size} قناة."
            }
        },
        colors = ButtonDefaults.buttonColors(containerColor = Gold),
        shape = RoundedCornerShape(13.dp)
    ) {
        Text("استيراد القنوات", color = Color(0xFF17120B), fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(10.dp))
    Text(message, color = Gold, fontSize = 13.sp)
    Spacer(Modifier.height(18.dp))
    SectionTitle("القنوات (${channels.size})")
    channels.forEach { channel ->
        Surface(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            color = Panel,
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(channel.name, color = Color.White, fontWeight = FontWeight.Bold)
                Text(channel.group, color = Gold, fontSize = 11.sp)
                Text(channel.url, color = Muted, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
    var sourcesExpanded by remember { mutableStateOf(false) }
    var subtitleExpanded by remember { mutableStateOf(false) }
    var providersExpanded by remember { mutableStateOf(false) }
    var adBlockExpanded by remember { mutableStateOf(false) }
    var tmdbExpanded by remember { mutableStateOf(false) }
    var aboutExpanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        SettingsSection("مصادر التشغيل", "إدارة واستيراد مصادر الفيديو", sourcesExpanded,
            { sourcesExpanded = !sourcesExpanded }) { SourceSettingsScreen() }

        SettingsSection("إعدادات الترجمة", "الخط والحجم واللون والموضع والتوقيت",
            subtitleExpanded, { subtitleExpanded = !subtitleExpanded }) {
            SubtitleSettingsScreen()
        }

        SettingsSection("مزوّدو الترجمة", "مفاتيح SubDL وOpenSubtitles",
            providersExpanded, { providersExpanded = !providersExpanded }) {
            SubtitleProviderSettingsScreen()
        }

        SettingsSection("حظر الإعلانات", "قواعدك الشخصية واستيراد النطاقات",
            adBlockExpanded, { adBlockExpanded = !adBlockExpanded }) {
            AdBlockSettingsScreen()
        }

        SettingsSection("إعدادات TMDB", "مفتاح API والاتصال بالخدمة",
            tmdbExpanded, { tmdbExpanded = !tmdbExpanded }) {
            TmdbSettingsScreen()
        }

        SettingsSection("حول التطبيق", "معلومات CinemaStream Native",
            aboutExpanded, { aboutExpanded = !aboutExpanded }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("CinemaStream Native", color = Gold, fontWeight = FontWeight.Bold)
                Text("الإصدار 0.1.0", color = Color.White, fontSize = 13.sp)
                Text("تطبيق أصلي لنظام Android.", color = Muted, fontSize = 12.sp)
                Text("المظهر الحالي: داكن", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Panel,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = Gold, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(description, color = Muted, fontSize = 11.sp)
                }
                Text(if (expanded) "−" else "+", color = Gold, fontSize = 24.sp,
                    fontWeight = FontWeight.Bold)
            }
            if (expanded) {
                Spacer(Modifier.height(13.dp))
                content()
            }
        }
    }
}

