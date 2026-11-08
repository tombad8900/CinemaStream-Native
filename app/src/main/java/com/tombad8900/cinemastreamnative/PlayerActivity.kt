package com.tombad8900.cinemastreamnative

import com.tombad8900.cinemastreamnative.subtitle.SubtitleSearchScreen

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.graphics.Typeface
import android.util.TypedValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.tombad8900.cinemastreamnative.player.Media3PlayerFactory
import com.tombad8900.cinemastreamnative.subtitle.SubtitleFileManager
import com.tombad8900.cinemastreamnative.subtitle.ImportedSubtitle
import com.tombad8900.cinemastreamnative.subtitle.SubtitlePreferences
import com.tombad8900.cinemastreamnative.subtitle.SubtitleSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val PlayerGold = Color(0xFFE6B96F)
private val PlayerPanel = Color(0xE6151B29)
private const val SEEK_STEP_MS = 10_000L
private const val KEY_POSITION = "cinemastream.player.POSITION"

class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "cinemastream.player.URL"
        const val EXTRA_TITLE = "cinemastream.player.TITLE"
        const val EXTRA_SUBTITLE_URL = "cinemastream.player.SUBTITLE_URL"
        const val EXTRA_SUBTITLE_URLS = "cinemastream.player.SUBTITLE_URLS"
        const val EXTRA_HEADERS = "cinemastream.player.HEADERS"
        const val EXTRA_PLAYBACK_METADATA = "cinemastream.player.PLAYBACK_METADATA"
    }

    private var exoPlayer: ExoPlayer? = null
    private val status = mutableStateOf("جارٍ تجهيز التشغيل")
    private val playbackError = mutableStateOf<String?>(null)
    private val isFullscreen = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)?.trim()
        if (url.isNullOrBlank()) {
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val playbackMetadata = PlaybackMetadata.inferEpisode(
            PlaybackMetadata.fromJson(intent.getStringExtra(EXTRA_PLAYBACK_METADATA))
                ?: PlaybackMetadata(title = title, contentType = "unknown"),
            "$title $url"
        )
        val subtitleUrl = intent.getStringExtra(EXTRA_SUBTITLE_URL)
        val subtitleUrls = intent.getStringArrayListExtra(EXTRA_SUBTITLE_URLS)?.toList()
            .orEmpty()
        val headersBundle = intent.getBundleExtra(EXTRA_HEADERS)
        val headers = headersBundle?.keySet()
            ?.associateWith { headersBundle.getString(it).orEmpty() }
            ?: emptyMap()

        val player = Media3PlayerFactory.create(
            context = this,
            url = url,
            headers = headers,
            subtitleUrl = subtitleUrl,
            subtitleUrls = subtitleUrls
        )
        exoPlayer = player

        val savedPosition = savedInstanceState?.getLong(KEY_POSITION) ?: 0L
        if (savedPosition > 0L) player.seekTo(savedPosition)

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                status.value = when (playbackState) {
                    Player.STATE_BUFFERING -> "جارٍ تحميل البث..."
                    Player.STATE_READY -> "المشغل جاهز"
                    Player.STATE_ENDED -> "انتهى التشغيل"
                    else -> "تم إيقاف التشغيل"
                }
                if (playbackState == Player.STATE_READY) {
                    playbackError.value = null
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError.value = error.message ?: "خطأ غير معروف"
                status.value = "تعذر تشغيل المصدر"
            }
        })

        setContent {
            var subtitleSettings by remember {
                mutableStateOf(SubtitlePreferences.load(this@PlayerActivity))
            }

            val subtitlePicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    lifecycleScope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                SubtitleFileManager.importFromUri(this@PlayerActivity, uri)
                            }
                        }

                        result.onSuccess { imported ->
                            if (addExternalSubtitle(player, imported, subtitleSettings.language, subtitleSettings.delayMs)) {
                                status.value = "تمت إضافة الترجمة: ${imported.displayName}"
                            } else {
                                status.value = "تم حفظ الملف، لكن إضافة ترجمة خارجية للبث المباشر غير متاحة حاليًا"
                            }
                        }.onFailure { error ->
                            status.value = error.message ?: "تعذر استيراد الترجمة"
                        }
                    }
                }
            }

            var selectedDialog by remember { mutableStateOf<String?>(null) }
            var resizeMode by remember {
                mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
            }
            var controlsVisible by remember { mutableStateOf(true) }
            var interactionTick by remember { mutableIntStateOf(0) }
            var locked by remember { mutableStateOf(false) }
            var currentPosition by remember { mutableFloatStateOf(0f) }
            var duration by remember { mutableFloatStateOf(1f) }
            var isScrubbing by remember { mutableStateOf(false) }
            var isPlaying by remember { mutableStateOf(player.isPlaying) }
            var isBuffering by remember {
                mutableStateOf(player.playbackState == Player.STATE_BUFFERING)
            }
            var isLive by remember { mutableStateOf(player.isCurrentMediaItemLive) }
            var seekFeedback by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
            var feedbackCounter by remember { mutableIntStateOf(0) }

            val fullscreen by isFullscreen

            val showControls: () -> Unit = {
                controlsVisible = true
                interactionTick++
            }

            DisposableEffect(player) {
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        isBuffering = playbackState == Player.STATE_BUFFERING
                    }

                    override fun onTimelineChanged(
                        timeline: androidx.media3.common.Timeline,
                        reason: Int
                    ) {
                        isLive = player.isCurrentMediaItemLive
                    }

                    override fun onMediaItemTransition(
                        mediaItem: androidx.media3.common.MediaItem?,
                        reason: Int
                    ) {
                        isLive = player.isCurrentMediaItemLive
                    }
                }
                player.addListener(listener)
                onDispose { player.removeListener(listener) }
            }

            LaunchedEffect(player) {
                while (true) {
                    val total = player.duration
                    duration = if (total > 0 && total != C.TIME_UNSET) {
                        total.toFloat()
                    } else {
                        1f
                    }

                    isLive = player.isCurrentMediaItemLive

                    if (!isScrubbing && controlsVisible) {
                        currentPosition = player.currentPosition
                            .coerceAtLeast(0L)
                            .toFloat()
                    }
                    delay(300)
                }
            }

            LaunchedEffect(controlsVisible, selectedDialog, interactionTick, isPlaying) {
                if (controlsVisible && selectedDialog == null && isPlaying) {
                    delay(4500)
                    controlsVisible = false
                }
            }

            LaunchedEffect(seekFeedback) {
                if (seekFeedback != null) {
                    delay(700)
                    seekFeedback = null
                }
            }

            BackHandler(enabled = fullscreen) {
                setFullscreen(false)
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = PlayerGold,
                    background = Color.Black,
                    surface = PlayerPanel
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            PlayerView(context).apply {
                                this.player = player
                                useController = false
                                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                                keepScreenOn = true
                            }
                        },
                        update = {
                            it.player = player
                            it.resizeMode = resizeMode
                            applySubtitleStyle(it, subtitleSettings)
                        }
                    )

                    // طبقة اللمس فوق الفيديو فقط، وأزرار التحكم مرسومة فوقها.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(locked) {
                                detectTapGestures(
                                    onTap = {
                                        if (controlsVisible) {
                                            controlsVisible = false
                                        } else {
                                            showControls()
                                        }
                                    },
                                    onDoubleTap = { offset ->
                                        if (!locked && !isLive) {
                                            val forward = offset.x > size.width / 2f
                                            seekBy(
                                                player,
                                                if (forward) SEEK_STEP_MS else -SEEK_STEP_MS
                                            )
                                            feedbackCounter++
                                            seekFeedback = forward to feedbackCounter
                                            showControls()
                                        }
                                    }
                                )
                            }
                    )

                    if (isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = PlayerGold
                        )
                    }

                    seekFeedback?.let { (forward, _) ->
                        Text(
                            text = if (forward) "10 ↷" else "↶ 10",
                            color = Color.White,
                            fontSize = 18.sp,
                            modifier = Modifier
                                .align(
                                    if (forward) Alignment.CenterEnd
                                    else Alignment.CenterStart
                                )
                                .padding(horizontal = 48.dp)
                                .background(PlayerPanel, RoundedCornerShape(50))
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }

                    if (controlsVisible) {
                        if (locked) {
                            TextButton(
                                onClick = {
                                    locked = false
                                    showControls()
                                },
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(12.dp)
                                    .background(PlayerPanel, RoundedCornerShape(12.dp))
                            ) {
                                Text("فك القفل", color = PlayerGold, fontSize = 12.sp)
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                // الشريط العلوي
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(PlayerPanel, RoundedCornerShape(12.dp))
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = title.ifBlank { "CinemaStream" },
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f)
                                    )

                                    TextButton(onClick = {
                                        locked = true
                                        showControls()
                                    }) {
                                        Text("قفل اللمس", color = Color.White, fontSize = 12.sp)
                                    }

                                    TextButton(onClick = {
                                        showControls()
                                        setFullscreen(!isFullscreen.value)
                                    }) {
                                        Text(
                                            text = if (fullscreen) "⛶ خروج" else "⛶ ملء الشاشة",
                                            color = PlayerGold,
                                            fontSize = 12.sp
                                        )
                                    }

                                    TextButton(onClick = { finish() }) {
                                        Text("إغلاق", color = Color.White, fontSize = 12.sp)
                                    }
                                }

                                if (!isPlaying && !isBuffering) {
                                    Button(
                                        onClick = {
                                            showControls()
                                            player.play()
                                        },
                                        modifier = Modifier.align(Alignment.CenterHorizontally),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = PlayerPanel
                                        )
                                    ) {
                                        Text("▶ تشغيل", color = PlayerGold)
                                    }
                                }

                                // أدوات التحكم السفلية
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(PlayerPanel, RoundedCornerShape(14.dp))
                                        .padding(horizontal = 10.dp, vertical = 7.dp)
                                ) {
                                    CompositionLocalProvider(
                                        LocalLayoutDirection provides LayoutDirection.Ltr
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (isLive) {
                                                Text(
                                                    "● مباشر",
                                                    color = Color(0xFFFF8A80),
                                                    fontSize = 12.sp,
                                                    modifier = Modifier.padding(vertical = 8.dp)
                                                )
                                            } else {
                                                Text(
                                                    formatPlayerTime(currentPosition.toLong()),
                                                    color = Color.White,
                                                    fontSize = 10.sp
                                                )

                                                Slider(
                                                    value = currentPosition.coerceIn(0f, duration),
                                                    onValueChange = {
                                                        currentPosition = it
                                                        isScrubbing = true
                                                        showControls()
                                                    },
                                                    onValueChangeFinished = {
                                                        player.seekTo(currentPosition.toLong())
                                                        isScrubbing = false
                                                        showControls()
                                                    },
                                                    valueRange = 0f..duration.coerceAtLeast(1f),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(35.dp)
                                                        .padding(horizontal = 5.dp)
                                                )

                                                Text(
                                                    formatPlayerTime(duration.toLong()),
                                                    color = Color.White,
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (!isLive) {
                                            TextButton(onClick = {
                                                showControls()
                                                seekBy(player, -SEEK_STEP_MS)
                                            }) {
                                                Text("↶ 10", color = Color.White, fontSize = 12.sp)
                                            }
                                        }

                                        TextButton(onClick = {
                                            showControls()
                                            if (player.isPlaying) player.pause() else player.play()
                                        }) {
                                            Text(
                                                if (isPlaying) "Ⅱ إيقاف" else "▶ تشغيل",
                                                color = PlayerGold,
                                                fontSize = 12.sp
                                            )
                                        }

                                        if (!isLive) {
                                            TextButton(onClick = {
                                                showControls()
                                                seekBy(player, SEEK_STEP_MS)
                                            }) {
                                                Text("10 ↷", color = Color.White, fontSize = 12.sp)
                                            }
                                        }

                                        TextButton(onClick = {
                                            showControls()
                                            selectedDialog = "audio"
                                        }) {
                                            Text("الصوت", color = Color.White, fontSize = 12.sp)
                                        }

                                        TextButton(onClick = {
                                            showControls()
                                            selectedDialog = "text"
                                        }) {
                                            Text("الترجمة", color = Color.White, fontSize = 12.sp)
                                        }

                                        TextButton(onClick = {
                                            showControls()
                                            selectedDialog = "video"
                                        }) {
                                            Text("الجودة", color = Color.White, fontSize = 12.sp)
                                        }

                                        TextButton(onClick = {
                                            showControls()
                                            selectedDialog = "speed"
                                        }) {
                                            Text(
                                                "السرعة ${formatSpeed(player.playbackParameters.speed)}",
                                                color = Color.White,
                                                fontSize = 12.sp
                                            )
                                        }

                                        TextButton(onClick = {
                                            showControls()
                                            resizeMode = when (resizeMode) {
                                                AspectRatioFrameLayout.RESIZE_MODE_FIT ->
                                                    AspectRatioFrameLayout.RESIZE_MODE_FILL
                                                AspectRatioFrameLayout.RESIZE_MODE_FILL ->
                                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                                else ->
                                                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                                            }
                                        }) {
                                            Text(
                                                "الصورة: " + when (resizeMode) {
                                                    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "تمديد"
                                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "تقريب"
                                                    else -> "ملاءمة"
                                                },
                                                color = PlayerGold,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }

                                    Text(
                                        text = status.value,
                                        color = Color.LightGray,
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(start = 5.dp, top = 2.dp)
                                    )

                                    playbackError.value?.let { error ->
                                        Text(
                                            text = error,
                                            color = Color(0xFFFF8A80),
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(5.dp)
                                        )

                                        TextButton(onClick = {
                                            showControls()
                                            playbackError.value = null
                                            status.value = "جارٍ إعادة المحاولة..."
                                            player.prepare()
                                            player.play()
                                        }) {
                                            Text("إعادة المحاولة", color = PlayerGold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    when (selectedDialog) {
                        "audio" -> TrackSelectionDialog(
                            player = player,
                            trackType = C.TRACK_TYPE_AUDIO,
                            title = "مسارات الصوت",
                            onDismiss = {
                                selectedDialog = null
                                showControls()
                            }
                        )

                        "text" -> TrackSelectionDialog(
                            player = player,
                            trackType = C.TRACK_TYPE_TEXT,
                            title = "مسارات الترجمة",
                            allowOff = true,
                            onImportSubtitle = {
                                subtitlePicker.launch(arrayOf("*/*"))
                            },
                                    onSearchOnline = { selectedDialog = "online_subtitles" },
                            onDismiss = {
                                selectedDialog = null
                                showControls()
                            }
                        )

                        "online_subtitles" -> SubtitleSearchScreen(
                                metadata = playbackMetadata,
                                preferredLanguage = subtitleSettings.language,
                                onSubtitleReady = { imported, language ->
                                    if (addExternalSubtitle(player, imported, language, subtitleSettings.delayMs)) {
                                        status.value = "تمت إضافة الترجمة: ${imported.displayName}"
                                        selectedDialog = null
                                        showControls()
                                    } else {
                                        status.value = "تعذرت إضافة الترجمة لهذا البث"
                                    }
                                },
                                onDismiss = {
                                    selectedDialog = "text"
                                    showControls()
                                }
                            )

                            "video" -> TrackSelectionDialog(
                            player = player,
                            trackType = C.TRACK_TYPE_VIDEO,
                            title = "جودة الفيديو",
                            allowAuto = true,
                            onDismiss = {
                                selectedDialog = null
                                showControls()
                            }
                        )

                        "speed" -> SpeedSelectionDialog(
                            player = player,
                            onDismiss = {
                                selectedDialog = null
                                showControls()
                            }
                        )
                    }
                }
            }
        }
    }

    private fun setFullscreen(enabled: Boolean) {
        isFullscreen.value = enabled
        requestedOrientation = if (enabled) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        setImmersiveMode(enabled)
    }

    private fun setImmersiveMode(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                if (enabled) {
                    controller.hide(
                        WindowInsets.Type.statusBars() or
                            WindowInsets.Type.navigationBars()
                    )
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(
                        WindowInsets.Type.statusBars() or
                            WindowInsets.Type.navigationBars()
                    )
                }
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (enabled) {
                (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        exoPlayer?.let {
            outState.putLong(KEY_POSITION, it.currentPosition.coerceAtLeast(0L))
        }
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        setImmersiveMode(false)
        releasePlayer()
        super.onDestroy()
    }

    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun addExternalSubtitle(
        player: ExoPlayer,
        imported: ImportedSubtitle,
        language: String,
        delayMs: Long
    ): Boolean {
        if (player.isCurrentMediaItemLive) return false

        val currentItem = player.currentMediaItem ?: return false
        val localConfiguration = currentItem.localConfiguration ?: return false
        val currentIndex = player.currentMediaItemIndex
        val currentPosition = player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = player.playWhenReady

        val adjustedSubtitle = SubtitleFileManager.applyDelay(
            imported,
            delayMs
        )

        val subtitleConfiguration =
            MediaItem.SubtitleConfiguration.Builder(adjustedSubtitle.uri)
                .setMimeType(imported.mimeType)
                .setLanguage(language)
                .setLabel(imported.displayName)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()

        val updatedItem = currentItem.buildUpon()
            .setSubtitleConfigurations(
                localConfiguration.subtitleConfigurations + subtitleConfiguration
            )
            .build()

        player.replaceMediaItem(currentIndex, updatedItem)
        player.seekTo(currentIndex, currentPosition)

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setPreferredTextLanguage(language)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()

        player.playWhenReady = wasPlaying
        return true
    }
}

@OptIn(UnstableApi::class)
private fun applySubtitleStyle(
    playerView: PlayerView,
    settings: SubtitleSettings
) {
    val subtitleView = playerView.subtitleView ?: return

    subtitleView.setApplyEmbeddedStyles(false)
    subtitleView.setApplyEmbeddedFontSizes(false)

    val alpha = (settings.backgroundOpacity.coerceIn(0f, 1f) * 255f).toInt()
    val background = (settings.backgroundColor and 0x00FFFFFF) or (alpha shl 24)

    val baseTypeface = settings.fontPath?.let { path ->
        runCatching { Typeface.createFromFile(path) }.getOrNull()
    } ?: Typeface.DEFAULT

    val typeface = Typeface.create(
        baseTypeface,
        if (settings.bold) Typeface.BOLD else Typeface.NORMAL
    )

    subtitleView.setStyle(
        CaptionStyleCompat(
            settings.textColor,
            background,
            android.graphics.Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            android.graphics.Color.BLACK,
            typeface
        )
    )

    subtitleView.setFixedTextSize(
        TypedValue.COMPLEX_UNIT_SP,
        settings.fontSize
    )

    subtitleView.setBottomPaddingFraction(
        (1f - settings.bottomPosition).coerceIn(0.02f, 0.35f)
    )
}

private fun seekBy(player: Player, deltaMs: Long) {
    val target = player.currentPosition + deltaMs
    val total = player.duration
    player.seekTo(
        if (total > 0 && total != C.TIME_UNSET) {
            target.coerceIn(0L, total)
        } else {
            target.coerceAtLeast(0L)
        }
    )
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

private fun formatPlayerTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun languageName(code: String): String {
    val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale("ar"))
    return if (name.isBlank() || name == "und") code else name
}

@Composable
private fun TrackSelectionDialog(
    player: Player,
    trackType: Int,
    title: String,
    allowOff: Boolean = false,
    allowAuto: Boolean = false,
    onImportSubtitle: (() -> Unit)? = null,
    onSearchOnline: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val groups = player.currentTracks.groups.filter { it.type == trackType }
    val isDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(trackType)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PlayerPanel,
        title = { Text(title, color = PlayerGold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 350.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (trackType == C.TRACK_TYPE_TEXT && onSearchOnline != null) {
                        TextButton(onClick = onSearchOnline) {
                            Text("البحث عن ترجمة عبر الإنترنت", color = PlayerGold)
                        }
                    }
                    if (trackType == C.TRACK_TYPE_TEXT && onImportSubtitle != null) {
                    TextButton(onClick = onImportSubtitle) {
                        Text("استيراد ترجمة من الهاتف", color = PlayerGold)
                    }
                }

                if (allowAuto) {
                    TextButton(onClick = {
                        player.trackSelectionParameters =
                            player.trackSelectionParameters
                                .buildUpon()
                                .clearOverridesOfType(trackType)
                                .setTrackTypeDisabled(trackType, false)
                                .build()
                        onDismiss()
                    }) {
                        Text("تلقائي", color = PlayerGold)
                    }
                }

                if (allowOff) {
                    TextButton(onClick = {
                        player.trackSelectionParameters =
                            player.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(trackType, true)
                                .build()
                        onDismiss()
                    }) {
                        Text(
                            text = (if (isDisabled) "✓ " else "") + "إيقاف الترجمة",
                            color = if (isDisabled) PlayerGold else Color.White
                        )
                    }
                }

                var optionCount = 0

                groups.forEach { group ->
                    for (index in 0 until group.length) {
                        if (!group.isTrackSupported(index)) continue
                        optionCount++

                        val format = group.getTrackFormat(index)
                        val label = when {
                            trackType == C.TRACK_TYPE_VIDEO -> buildString {
                                if (format.height > 0) append("${format.height}p")
                                else append("فيديو")
                                if (format.bitrate > 0) {
                                    append(" • ${format.bitrate / 1000} kbps")
                                }
                            }
                            !format.label.isNullOrBlank() -> format.label!!
                            !format.language.isNullOrBlank() ->
                                languageName(format.language!!)
                            else -> "مسار $optionCount"
                        }

                        val selected = group.isTrackSelected(index) && !isDisabled

                        TextButton(onClick = {
                            player.trackSelectionParameters =
                                player.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(trackType, false)
                                    .setOverrideForType(
                                        TrackSelectionOverride(group.mediaTrackGroup, index)
                                    )
                                    .build()
                            onDismiss()
                        }) {
                            Text(
                                text = (if (selected) "✓ " else "") + label,
                                color = if (selected) PlayerGold else Color.White
                            )
                        }
                    }
                }

                if (optionCount == 0) {
                    Text(
                        text = "لا توجد مسارات متاحة في هذا المصدر",
                        color = Color.LightGray,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("إغلاق", color = PlayerGold)
            }
        }
    )
}

@Composable
private fun SpeedSelectionDialog(
    player: Player,
    onDismiss: () -> Unit
) {
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PlayerPanel,
        title = { Text("سرعة التشغيل", color = PlayerGold) },
        text = {
            Column {
                speeds.forEach { speed ->
                    val current = player.playbackParameters.speed == speed
                    TextButton(onClick = {
                        player.setPlaybackParameters(PlaybackParameters(speed))
                        onDismiss()
                    }) {
                        Text(
                            text = (if (current) "✓ " else "") +
                                if (speed == 1f) "1.0x (عادي)" else "${speed}x",
                            color = if (current) PlayerGold else Color.White
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("إغلاق", color = PlayerGold)
            }
        }
    )
}
