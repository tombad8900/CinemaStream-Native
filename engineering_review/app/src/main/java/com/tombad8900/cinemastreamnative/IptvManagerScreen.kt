package com.tombad8900.cinemastreamnative

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.tombad8900.cinemastreamnative.iptv.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val IptvGold = Color(0xFFE6B96F)
private val IptvMuted = Color(0xFF9BA5B5)
private val IptvPanel = Color(0xFF151B29)

@Composable
fun IptvManagerScreen(
    onPlayUrl: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { IptvRepository(context) }
    val scope = rememberCoroutineScope()

    var playlists by remember { mutableStateOf<List<IptvPlaylist>>(emptyList()) }
    var channels by remember { mutableStateOf<List<IptvChannel>>(emptyList()) }
    var selectedPlaylist by remember { mutableStateOf<IptvPlaylist?>(null) }
    var selectedGroup by remember { mutableStateOf("الكل") }
    var search by remember { mutableStateOf("") }
    var gridMode by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var dialogType by remember { mutableStateOf("") }

    var playlistName by remember { mutableStateOf("") }
    var playlistUrl by remember { mutableStateOf("") }
    var playlistText by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var channelName by remember { mutableStateOf("") }
    var channelUrl by remember { mutableStateOf("") }

    suspend fun reloadData() {
        val data = withContext(Dispatchers.IO) {
            repository.getPlaylists()
        }
        playlists = data

        val current = selectedPlaylist?.let { selected ->
            data.firstOrNull { it.id == selected.id }
        }

        selectedPlaylist = current
        channels = if (current != null) {
            withContext(Dispatchers.IO) {
                repository.getChannels(current.id)
            }
        } else {
            emptyList()
        }

        if (current == null) selectedGroup = "الكل"
    }

    fun executeTask(action: () -> String) {
        if (loading) return
        scope.launch {
            loading = true
            message = ""
            val result = withContext(Dispatchers.IO) {
                runCatching { action() }
            }
            loading = false

            result.onSuccess {
                message = it
                runCatching { reloadData() }
                    .onFailure { error ->
                        message = error.message ?: "تعذر تحديث البيانات"
                    }
            }.onFailure {
                message = it.message ?: "حدث خطأ أثناء تنفيذ العملية"
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { reloadData() }
            .onFailure { message = it.message ?: "تعذر تحميل القوائم" }
    }

    val groups = remember(channels) {
        listOf("الكل") + channels.map { it.groupName }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    val filteredChannels = remember(channels, selectedGroup, search) {
        channels.filter { channel ->
            (selectedGroup == "الكل" || channel.groupName == selectedGroup) &&
                (search.isBlank() ||
                    channel.name.contains(search, ignoreCase = true))
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            IptvActionButton("M3U نص", Modifier.weight(1f)) {
                playlistName = ""
                playlistText = ""
                dialogType = "m3u_text"
            }
            IptvActionButton("M3U رابط", Modifier.weight(1f)) {
                playlistName = ""
                playlistUrl = ""
                dialogType = "m3u_url"
            }
            IptvActionButton("Xtream", Modifier.weight(1f)) {
                playlistName = ""
                host = ""
                username = ""
                password = ""
                dialogType = "xtream"
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            OutlinedButton(
                onClick = {
                    playlistName = ""
                    playlistUrl = ""
                    dialogType = "direct"
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) { Text("رابط بث مباشر") }

            OutlinedButton(
                onClick = {
                    channelName = ""
                    channelUrl = ""
                    dialogType = "manual"
                },
                modifier = Modifier.weight(1f),
                enabled = selectedPlaylist != null,
                shape = RoundedCornerShape(12.dp)
            ) { Text("إضافة قناة") }
        }

        if (message.isNotBlank()) {
            Text(message, color = IptvGold, fontSize = 12.sp)
        }

        if (loading) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth(),
                color = IptvGold
            )
        }

        if (selectedPlaylist == null) {
            Text(
                "قوائم IPTV المحفوظة (${playlists.size})",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )

            if (playlists.isEmpty()) {
                Surface(
                    Modifier.fillMaxWidth(),
                    color = IptvPanel,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text("لا توجد قوائم حتى الآن", color = IptvGold)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "أضف قائمة M3U أو اشتراك Xtream للبدء.",
                            color = IptvMuted,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(playlists, key = { it.id }) { playlist ->
                        Surface(
                            Modifier.fillMaxWidth(),
                            color = IptvPanel,
                            shape = RoundedCornerShape(15.dp)
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            val loaded = withContext(Dispatchers.IO) {
                                                repository.getChannels(playlist.id)
                                            }
                                            selectedPlaylist = playlist
                                            channels = loaded
                                            selectedGroup = "الكل"
                                            search = ""
                                            message = ""
                                        }
                                    }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        playlist.name,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "${playlist.type} • ${repositoryCountLabel(playlist.id, channels, playlists)}",
                                        color = IptvMuted,
                                        fontSize = 11.sp
                                    )
                                }
                                Text("فتح  ›", color = IptvGold)
                            }
                        }
                    }
                }
            }
        } else {
            val current = selectedPlaylist!!

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    current.name,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    selectedPlaylist = null
                    channels = emptyList()
                    search = ""
                    selectedGroup = "الكل"
                    message = ""
                }) {
                    Text("القوائم", color = IptvGold)
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (current.type == "XTREAM") {
                    OutlinedButton(
                        onClick = {
                            executeTask {
                                val count = repository.refreshXtream(current.id)
                                "تم تحديث $count قناة"
                            }
                        },
                        enabled = !loading,
                        modifier = Modifier.weight(1f)
                    ) { Text("تحديث") }
                }

                OutlinedButton(
                    onClick = {
                        channelName = ""
                        channelUrl = ""
                        dialogType = "manual"
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("إضافة قناة") }

                OutlinedButton(
                    onClick = {
                        executeTask {
                            repository.deletePlaylist(current.id)
                            selectedPlaylist = null
                            channels = emptyList()
                            "تم حذف القائمة"
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.weight(1f)
                ) { Text("حذف القائمة") }
            }

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("ابحث عن قناة...") },
                shape = RoundedCornerShape(13.dp)
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(groups) { group ->
                    FilterChip(
                        selected = selectedGroup == group,
                        onClick = { selectedGroup = group },
                        label = { Text(group) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF30291F),
                            selectedLabelColor = IptvGold
                        )
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "القنوات: ${filteredChannels.size}",
                    color = IptvMuted,
                    fontSize = 12.sp
                )
                TextButton(onClick = { gridMode = !gridMode }) {
                    Text(
                        if (gridMode) "عرض قائمة" else "عرض شبكة",
                        color = IptvGold
                    )
                }
            }

            if (filteredChannels.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("لا توجد قنوات مطابقة", color = IptvMuted)
                }
            } else if (gridMode) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 145.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredChannels, key = { it.id }) { channel ->
                        ChannelTile(channel) {
                            onPlayUrl(channel.url, channel.name)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(filteredChannels, key = { it.id }) { channel ->
                        ChannelTile(channel, compact = true) {
                            onPlayUrl(channel.url, channel.name)
                        }
                    }
                }
            }
        }
    }

    if (dialogType.isNotBlank()) {
        val title = when (dialogType) {
            "m3u_text" -> "استيراد قائمة M3U"
            "m3u_url" -> "استيراد M3U من رابط"
            "xtream" -> "إضافة اشتراك Xtream"
            "direct" -> "إضافة رابط بث مباشر"
            else -> "إضافة قناة إلى القائمة"
        }

        AlertDialog(
            onDismissRequest = { if (!loading) dialogType = "" },
            title = { Text(title) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    if (dialogType != "manual") {
                        OutlinedTextField(
                            value = playlistName,
                            onValueChange = { playlistName = it },
                            label = { Text("اسم القائمة") },
                            singleLine = true
                        )
                    }

                    when (dialogType) {
                        "m3u_text" -> OutlinedTextField(
                            value = playlistText,
                            onValueChange = { playlistText = it },
                            modifier = Modifier.fillMaxWidth().height(170.dp),
                            label = { Text("محتوى M3U") },
                            placeholder = { Text("#EXTM3U\n#EXTINF:-1,اسم القناة\nhttps://...") }
                        )

                        "m3u_url", "direct" -> OutlinedTextField(
                            value = playlistUrl,
                            onValueChange = { playlistUrl = it },
                            label = { Text("رابط HTTP أو HTTPS") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                        )

                        "xtream" -> {
                            OutlinedTextField(
                                value = host,
                                onValueChange = { host = it },
                                label = { Text("عنوان الخادم Host") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                            )
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = { Text("اسم المستخدم") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("كلمة المرور") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation()
                            )
                        }

                        "manual" -> {
                            OutlinedTextField(
                                value = channelName,
                                onValueChange = { channelName = it },
                                label = { Text("اسم القناة") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = channelUrl,
                                onValueChange = { channelUrl = it },
                                label = { Text("رابط القناة") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !loading,
                    onClick = {
                        val type = dialogType
                        dialogType = ""
                        executeTask {
                            when (type) {
                                "m3u_text" -> {
                                    val result = repository.importM3uText(
                                        playlistName,
                                        playlistText
                                    )
                                    "تم استيراد ${result.channelCount} قناة"
                                }

                                "m3u_url" -> {
                                    val result = repository.importM3uFromUrl(
                                        playlistName,
                                        playlistUrl
                                    )
                                    "تم تحميل ${result.channelCount} قناة"
                                }

                                "direct" -> {
                                    val result = repository.createDirectStream(
                                        playlistName,
                                        playlistUrl
                                    )
                                    "تمت إضافة رابط البث"
                                }

                                "xtream" -> {
                                    val result = repository.importXtream(
                                        playlistName,
                                        host,
                                        username,
                                        password
                                    )
                                    "تم الاتصال وإضافة ${result.channelCount} قناة"
                                }

                                "manual" -> {
                                    val target = selectedPlaylist
                                        ?: throw IllegalStateException("اختر قائمة أولًا")
                                    repository.addManualChannel(
                                        target.id,
                                        channelName,
                                        channelUrl
                                    )
                                    "تمت إضافة القناة"
                                }

                                else -> "لم يتم تنفيذ العملية"
                            }
                        }
                    }
                ) { Text("تنفيذ") }
            },
            dismissButton = {
                TextButton(
                    onClick = { dialogType = "" },
                    enabled = !loading
                ) { Text("إلغاء") }
            }
        )
    }
}

@Composable
private fun IptvActionButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(43.dp),
        contentPadding = PaddingValues(horizontal = 5.dp),
        shape = RoundedCornerShape(11.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF30291F),
            contentColor = IptvGold
        )
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ChannelTile(
    channel: IptvChannel,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = IptvPanel,
        shape = RoundedCornerShape(13.dp)
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(if (compact) 38.dp else 42.dp)
                    .background(
                        Color(0xFF30291F),
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("▶", color = IptvGold, fontSize = 15.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    channel.name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 2
                )
                Text(
                    channel.groupName,
                    color = IptvGold,
                    fontSize = 10.sp
                )
            }
        }
    }
}

private fun repositoryCountLabel(
    playlistId: Long,
    currentChannels: List<IptvChannel>,
    playlists: List<IptvPlaylist>
): String {
    return if (playlists.any { it.id == playlistId }) {
        if (currentChannels.isNotEmpty()) {
            "${currentChannels.size} قناة محملة"
        } else {
            "اضغط لعرض القنوات"
        }
    } else {
        ""
    }
}
