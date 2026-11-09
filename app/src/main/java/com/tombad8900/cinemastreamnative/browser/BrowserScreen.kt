package com.tombad8900.cinemastreamnative.browser

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

private data class BrowserCapture(
    val url: String,
    val kind: String,
    val playable: Boolean,
    val isArabicSubtitle: Boolean = false,
    val headers: Map<String, String> = emptyMap()
)

private class BrowserBridge(
    private val onSubtitle: (String) -> Unit
) {
    @JavascriptInterface
    fun subtitle(url: String) {
        onSubtitle(url)
    }
}

private fun classifyResource(url: String): BrowserCapture? {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    val path = uri.path.orEmpty().lowercase()
    val query = uri.encodedQuery.orEmpty().lowercase()
    val full = url.lowercase()

    if (uri.scheme != "http" && uri.scheme != "https") return null

    val isArabicSub = listOf("ar", "arabic", "عربي", "ara").any { hint -> hint in path || hint in query || hint in full }
    val subtitleHint = listOf(
        ".srt", ".vtt", ".ass", ".ssa", ".ttml", ".dfxp",
        "subtitle", "subtitles", "captions", "texttrack"
    ).any { hint -> hint in path || hint in query || hint in full }

    if (subtitleHint) {
        val label = if (isArabicSub) "ترجمة عربية 💬 (مكتشفة)" else "ترجمة جديدة"
        return BrowserCapture(url, label, false, isArabicSub)
    }

    val videoExtensions = listOf(".m3u8", ".mpd", ".mp4", ".mkv", ".webm")
    val hasVideoExt = videoExtensions.any { ext -> path.endsWith(ext) || query.contains(ext) }

    val videoKeywords = listOf(
        "master.m3u8", "index.m3u8", "playlist.m3u8", "manifest",
        "hls", "dash", "stream", "video", "embed", "player"
    )
    val hasVideoKeyword = videoKeywords.any { kw -> kw in path || kw in query || kw in full }
    val isSegment = path.endsWith(".ts") || query.contains(".ts") || path.endsWith(".m4s") || query.contains(".m4s")

    val isMediaLike = !isSegment && (hasVideoExt || (hasVideoKeyword && (full.contains(".m3u8") || full.contains(".mpd") || full.contains(".mp4"))))

    if (isMediaLike) {
        val label = when {
            full.contains(".m3u8") || full.contains("hls") -> "بث HLS (m3u8)"
            full.contains(".mpd") || full.contains("dash") -> "بث DASH (mpd)"
            full.contains(".mp4") -> "فيديو MP4"
            else -> "فيديو مكتشف"
        }
        return BrowserCapture(url, label, true)
    }

    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    onPlayUrl: (String, String) -> Unit,
    onPlayCaptured: (
        streamUrl: String,
        title: String,
        headers: Map<String, String>,
        subtitles: List<String>
    ) -> Unit,
    sources: List<com.tombad8900.cinemastreamnative.sources.StreamSource> = emptyList(),
    media: com.tombad8900.cinemastreamnative.tmdb.TmdbMedia? = null,
    season: Int = 1,
    episode: Int = 1,
    sourcePickerRequest: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val captures = remember { mutableStateListOf<BrowserCapture>() }
    val subtitleUrls = remember { mutableStateListOf<String>() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var address by remember { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("CinemaStream") }
    var showSources by remember { mutableStateOf(false) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isFullScreen by remember { mutableStateOf(false) }

    val playTitle = remember(media, pageTitle) {
        media?.originalTitle?.takeIf { it.isNotBlank() }
            ?: media?.title?.takeIf { it.isNotBlank() }
            ?: pageTitle
    }

    val adDomains = remember {
        listOf(
            "popads", "popcash", "adsterra", "doubleclick",
            "googlesyndication", "exoclick", "bet365", "juicyads",
            "ad-delivery", "onclickads", "propellerads", "adtrafficquality",
            "ad-shield", "popunder", "banner", "redirect", "hokihoki", "daftar"
        )
    }

    fun addCapture(capture: BrowserCapture) {
        mainHandler.post {
            if (captures.none { it.url == capture.url }) {
                if (captures.size >= 100) captures.removeAt(0)
                captures.add(capture)
            }
        }
    }

    fun addSubtitle(url: String) {
        mainHandler.post {
            if (subtitleUrls.size < 50 && url !in subtitleUrls) {
                subtitleUrls.add(0, url)
            }
        }
    }

    fun openSource(source: com.tombad8900.cinemastreamnative.sources.StreamSource) {
        val selected = media ?: return
        val isTv = selected.mediaType.equals("tv", ignoreCase = true)
        var url = if (isTv) source.tv else source.movie
        if (url.isBlank()) return

        val id = selected.id.toString()
        url = url.replace("{tmdb_id}", id, ignoreCase = true)
            .replace("{tmdb}", id, ignoreCase = true)
            .replace("{id}", id, ignoreCase = true)

        if (isTv) {
            if (season < 0 || episode < 0) return
            url = url.replace("{season}", season.toString(), ignoreCase = true)
                .replace("{s}", season.toString(), ignoreCase = true)
                .replace("{episode}", episode.toString(), ignoreCase = true)
                .replace("{e}", episode.toString(), ignoreCase = true)
        }

        captures.clear()
        subtitleUrls.clear()
        address = url
        showSources = false
        webViewInstance?.loadUrl(url)
    }

    LaunchedEffect(media?.id, season, episode) {
        if (media != null) {
            captures.clear()
            subtitleUrls.clear()
            showSources = true
        }
    }

    LaunchedEffect(sourcePickerRequest) {
        if (sourcePickerRequest > 0 && media != null) {
            showSources = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF202020))
            .padding(12.dp)
    ) {
        if (showSources) {
            val availableSources = sources.filter {
                if (media?.mediaType.equals("tv", ignoreCase = true)) {
                    it.tv.isNotBlank()
                } else {
                    it.movie.isNotBlank()
                }
            }

            AlertDialog(
                onDismissRequest = { showSources = false },
                title = { Text("اختر مصدر البث") },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        if (media == null) {
                            item { Text("لا يوجد محتوى محدد حالياً.") }
                        } else if (availableSources.isEmpty()) {
                            item {
                                Text("لا تتوفر مصادر لهذه الفئة حالياً. يمكنك فتح الإعدادات وإضافة مصادر جديدة.")
                            }
                        } else {
                            items(availableSources, key = { it.id }) { source ->
                                OutlinedButton(
                                    onClick = {
                                        openSource(source)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(source.name)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSources = false }) {
                        Text("إغلاق")
                    }
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(playTitle, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
            Button(onClick = { showSources = true }) {
                Text("المصادر")
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black,
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedBorderColor = Color(0xFFB8860B),
                    unfocusedBorderColor = Color.DarkGray,
                    cursorColor = Color.Black,
                    focusedPlaceholderColor = Color.DarkGray,
                    unfocusedPlaceholderColor = Color.DarkGray
                ),
                placeholder = { Text("رابط الصفحة") }
            )

            Button(
                onClick = {
                    val value = address.trim()
                    if (value.isNotBlank()) {
                        val url = if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
                        captures.clear()
                        subtitleUrls.clear()
                        webViewInstance?.loadUrl(url)
                    }
                },
                modifier = Modifier.align(Alignment.CenterVertically)
            ) {
                Text("انتقال")
            }
        }

        Spacer(Modifier.height(8.dp))

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewInstance = this
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(android.graphics.Color.WHITE)

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        userAgentString =
                            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    }

                    addJavascriptInterface(
                        BrowserBridge { subUrl ->
                            addSubtitle(subUrl)
                            addCapture(BrowserCapture(subUrl, "ترجمة عربية 💬", false, true))
                        },
                        "CinemaCapture"
                    )

                    webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            title?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
                        }

                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?
                        ): Boolean {
                            return false
                        }

                        override fun onPermissionRequest(request: PermissionRequest?) {
                            request?.deny()
                        }

                        override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
                            isFullScreen = true
                        }

                        override fun onHideCustomView() {
                            isFullScreen = false
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val reqUrl = request?.url?.toString()?.lowercase() ?: return false
                            if (adDomains.any { reqUrl.contains(it) }) {
                                return true
                            }
                            return false
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            req: WebResourceRequest?
                        ): WebResourceResponse? {
                            val url = req?.url?.toString() ?: return null
                            val lowerUrl = url.lowercase()

                            if (adDomains.any { lowerUrl.contains(it) }) {
                                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                            }

                            classifyResource(url)?.let { item ->
                                val allowed = setOf(
                                    "user-agent", "referer", "origin",
                                    "cookie", "authorization"
                                )
                                val headers = (req.requestHeaders ?: emptyMap())
                                    .filterKeys { it.lowercase() in allowed }
                                    .toMutableMap()

                                if (headers.keys.none { it.equals("User-Agent", true) }) {
                                    view?.settings?.userAgentString?.let {
                                        headers["User-Agent"] = it
                                    }
                                }
                                if (headers.keys.none { it.equals("Referer", true) } &&
                                    address.startsWith("http", true)) {
                                    headers["Referer"] = address
                                }
                                addCapture(item.copy(headers = headers))
                                if (!item.playable) addSubtitle(url)
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(
                                """
                                (function() {
                                    window.open = function() { return null; };
                                    window.alert = function() { return true; };
                                    window.confirm = function() { return true; };
                                    window.prompt = function() { return null; };
                                    if (window.Notification) {
                                        window.Notification.requestPermission = function() {
                                            return Promise.resolve('denied');
                                        };
                                    }
                                    function checkTracks() {
                                        document.querySelectorAll('video track, track').forEach(function(t) {
                                            var lang = (t.srclang || t.label || '').toLowerCase();
                                            var src = t.src;
                                            if (src && (lang.includes('ar') || lang.includes('arabic') || lang.includes('عربي') || src.includes('ar'))) {
                                                if (window.CinemaCapture) {
                                                    window.CinemaCapture.subtitle(src);
                                                }
                                            }
                                        });
                                    }
                                    checkTracks();
                                    setInterval(checkTracks, 1500);
                                })();
                                """.trimIndent(),
                                null
                            )
                        }
                    }

                    if (address.isNotBlank()) {
                        loadUrl(address)
                    }
                }
            },
            update = {}
        )

        if (captures.isNotEmpty()) {
            Text(
                "الروابط والترجمات العربية المكتشفة (انقر للتشغيل بالمشغل الأصلي 🎬)",
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                color = MaterialTheme.colorScheme.primary
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 150.dp)
            ) {
                items(captures, key = { it.url }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.kind, style = MaterialTheme.typography.labelMedium, color = Color.White)
                            Text(
                                item.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray,
                                maxLines = 2
                            )
                        }

                        if (item.playable) {
                            TextButton(
                                onClick = {
                                    onPlayCaptured(
                                        item.url,
                                        playTitle,
                                        item.headers,
                                        subtitleUrls.distinct()
                                    )
                                }
                            ) {
                                Text("تشغيل بمشغل التطبيق")
                            }
                        }
                    }
                }
            }
        }
    }
}
