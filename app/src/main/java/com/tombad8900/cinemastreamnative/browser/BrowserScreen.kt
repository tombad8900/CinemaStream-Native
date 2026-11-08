package com.tombad8900.cinemastreamnative.browser

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.net.Uri

private data class BrowserCapture(
    val url: String,
    val kind: String,
    val playable: Boolean,
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

    val subtitleHint = listOf(
        ".srt", ".vtt", ".ass", ".ssa", ".ttml", ".dfxp",
        "subtitle", "subtitles", "subtitel", "captions",
        "closedcaption", "texttrack", "/sub/"
    ).any { it in path || it in query || it in full }

    if (subtitleHint) {
        return BrowserCapture(url, "ترجمة / مسار نصي", false)
    }

    val videoHint = listOf(
        ".m3u8", ".mpd", ".mp4", ".m4v", ".webm", ".mov",
        ".mkv", ".ts", ".m4s", ".cmfv", ".cmfa"
    ).any { path.endsWith(it) }

    val streamingHint = listOf(
        "manifest", "playlist", "master", "videoplayback",
        "mime=video", "type=video", "format=m3u8",
        "hls", "dash", "googlevideo"
    ).any { it in full }

    val mediaTypeHint = listOf(
        "video/", "application/vnd.apple.mpegurl",
        "application/x-mpegurl", "application/dash+xml"
    ).any { it in full }

    if (!videoHint && !streamingHint && !mediaTypeHint) return null

    val segment = listOf(".ts", ".m4s", ".cmfv", ".cmfa").any {
        path.endsWith(it)
    }

    if (segment) {
        return BrowserCapture(url, "جزء من بث فيديو - ليس رابط تشغيل مستقل", false)
    }

    val kind = when {
        ".m3u8" in path || "mpegurl" in full -> "فيديو HLS"
        ".mpd" in path || "dash+xml" in full -> "فيديو DASH"
        else -> "رابط فيديو مرشح للتشغيل"
    }

    return BrowserCapture(url, kind, true)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    onPlayUrl: (String, String) -> Unit,
    onPlayCaptured: (String, String, Map<String, String>, List<String>) -> Unit,
    sources: List<com.tombad8900.cinemastreamnative.sources.StreamSource>,
    media: com.tombad8900.cinemastreamnative.tmdb.TmdbMedia?,
    season: Int,
    episode: Int,
    sourcePickerRequest: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val captures = remember { mutableStateListOf<BrowserCapture>() }
    val subtitleUrls = remember { mutableStateListOf<String>() }
    val handler = remember { Handler(Looper.getMainLooper()) }

    var address by remember { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("CinemaStream") }
    var browser by remember { mutableStateOf<WebView?>(null) }

    var showSources by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(media?.id, season, episode) {
        if (media != null) {
            captures.clear()
            showSources = true
        }
    }

    androidx.compose.runtime.LaunchedEffect(sourcePickerRequest) {
        if (sourcePickerRequest > 0 && media != null) {
            showSources = true
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
        browser?.loadUrl(url)
    }

    fun addSubtitle(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        handler.post {
            if (subtitleUrls.size < 50 && url !in subtitleUrls) {
                subtitleUrls.add(url)
            }
        }
    }

    fun addCapture(item: BrowserCapture) {
        if (!item.url.startsWith("http://") &&
            !item.url.startsWith("https://")) return

        handler.post {
            if (captures.none { it.url == item.url }) {
                if (captures.size >= 100) captures.removeAt(0)
                captures.add(item)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().background(Color(0xFF202020))) {

        if (showSources) {
            val availableSources = sources.filter {
                if (media?.mediaType.equals("tv", ignoreCase = true)) {
                    it.tv.isNotBlank()
                } else {
                    it.movie.isNotBlank()
                }
            }

            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showSources = false },
                title = { Text("مصادر التشغيل") },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        if (media == null) {
                            item { Text("اختر فيلمًا أو حلقة أولًا.") }
                        } else if (availableSources.isEmpty()) {
                            item {
                                Text("لا توجد مصادر مفعّلة مناسبة لهذا العمل. تحقق من ملف المصادر المستورد.")
                            }
                        } else {
                            items(availableSources, key = { it.id }) { source ->
                                androidx.compose.material3.OutlinedButton(
                                    onClick = { openSource(source) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(source.name)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showSources = false }
                    ) {
                        Text("إغلاق")
                    }
                }
            )
        }

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
                placeholder = { Text("أدخل رابط المصدر") }
            )

            Button(
                onClick = {
                    val value = address.trim()
                    if (value.isNotBlank()) {
                        val url = if (
                            value.startsWith("http://") ||
                            value.startsWith("https://")
                        ) value else "https://$value"

                        captures.clear()
                        browser?.loadUrl(url)
                    }
                },
                modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically)
            ) {
                Text("فتح")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = { if (browser?.canGoBack() == true) browser?.goBack() }) {
                Text("رجوع")
            }
            TextButton(onClick = { if (browser?.canGoForward() == true) browser?.goForward() }) {
                Text("تقدم")
            }
            TextButton(onClick = { browser?.reload() }) {
                Text("تحديث")
            }
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = {
                WebView(context).apply {
                    browser = this

                    // اختبار تشخيصي مؤقت لمشكلة الرسم
                    setBackgroundColor(android.graphics.Color.WHITE)
                    setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.setSupportMultipleWindows(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false

                    addJavascriptInterface(
                        BrowserBridge { url ->
                            addSubtitle(url)
                            addCapture(
                                BrowserCapture(
                                    url,
                                    "ترجمة مرتبطة بمشغل الصفحة",
                                    false
                                )
                            )
                        },
                        "CinemaCapture"
                    )

                    webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(
                            view: WebView?,
                            title: String?
                        ) {
                            if (!title.isNullOrBlank()) pageTitle = title
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: android.graphics.Bitmap?
                        ) {
                            if (!url.isNullOrBlank()) {
                                address = url
                                captures.clear()
                                subtitleUrls.clear()
                            }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): android.webkit.WebResourceResponse? {
                            request?.let { req ->
                                val url = req.url.toString()
                                classifyResource(url)?.let { item ->
                                    val allowed = setOf(
                                        "user-agent", "referer", "origin",
                                        "cookie", "authorization"
                                    )
                                    val headers = req.requestHeaders
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
                            }
                            return null
                        }

                        override fun onPageFinished(
                            view: WebView?,
                            url: String?
                        ) {
                            view?.evaluateJavascript(
                                """
                                (function() {
                                  function scan() {
                                    document.querySelectorAll('video track[src]').forEach(function(t) {
                                      if (window.CinemaCapture) {
                                        window.CinemaCapture.subtitle(t.src);
                                      }
                                    });
                                  }
                                  scan();
                                  if (!window.__cinemaTrackObserver && document.documentElement) {
                                    window.__cinemaTrackObserver = new MutationObserver(scan);
                                    window.__cinemaTrackObserver.observe(
                                      document.documentElement,
                                      {childList:true, subtree:true, attributes:true, attributeFilter:['src']}
                                    );
                                  }
                                })();
                                """.trimIndent(),
                                null
                            )
                        }
                    }
                }
            },
            update = {}
        )

        if (captures.isNotEmpty()) {
            Text(
                "الروابط التي رصدها الكاشف",
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
                            Text(item.kind, style = MaterialTheme.typography.labelMedium)
                            Text(
                                item.url,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2
                            )
                        }

                        if (item.playable) {
                            TextButton(
                                onClick = {
                                    onPlayCaptured(
                                        item.url,
                                        pageTitle,
                                        item.headers,
                                        subtitleUrls.distinct()
                                    )
                                }
                            ) {
                                Text("تشغيل")
                            }
                        }
                    }
                }
            }
        }
    }
}
