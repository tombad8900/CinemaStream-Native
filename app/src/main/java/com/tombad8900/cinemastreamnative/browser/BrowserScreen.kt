package com.tombad8900.cinemastreamnative.browser

import com.tombad8900.cinemastreamnative.security.AdBlockStore
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
import android.app.Activity
import android.webkit.JsResult
import android.webkit.JsPromptResult

private data class BrowserCapture(
    val url: String,
    val kind: String,
    val playable: Boolean,
    val isArabicSubtitle: Boolean = false,
    val headers: Map<String, String> = emptyMap()
)


private val blockedAdHosts = setOf(
    "doubleclick.net", "googlesyndication.com",
    "googleadservices.com", "adservice.google.com",
    "adnxs.com", "taboola.com", "outbrain.com",
    "popads.net", "popcash.net", "propellerads.com",
    "exoclick.com", "juicyads.com", "trafficjunky.com",
    "adsterra.com", "adform.net", "criteo.com",
    "amazon-adsystem.com", "pubmatic.com", "rubiconproject.com", "openx.net", "adsrvr.org", "revcontent.com", "mgid.com"
)

private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

private fun isSameSite(a: String, b: String): Boolean {
    fun site(u: String): String {
        val h = runCatching { Uri.parse(u).host?.lowercase() }.getOrNull().orEmpty()
        return h.split('.').takeLast(2).joinToString(".")
    }
    val x = site(a)
    return x.isNotBlank() && x == site(b)
}

private fun isBlockedAdUrl(rawUrl: String, pageUrl: String): Boolean {
    val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase().orEmpty()
    if (scheme == "about" || scheme == "blob" || scheme == "data") return false
    if (scheme != "http" && scheme != "https") return true
    val host = uri.host?.lowercase().orEmpty()
    if (host.isBlank()) return false
    if (blockedAdHosts.any { host == it || host.endsWith(".$it") }) return true
    if (AdBlockStore.isBlocked(host)) return true
    val pageHost = runCatching { Uri.parse(pageUrl).host?.lowercase() }.getOrNull()
    if (!pageHost.isNullOrBlank() && host == pageHost) return false
    val path = (uri.path.orEmpty() + "?" + uri.encodedQuery.orEmpty()).lowercase()
    return listOf("/adserver/", "/popunder/", "/clickunder/", "/popads").any { it in path }
}

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

    val isArabicSub = Regex("(^|[^a-z])(ar|ara|arabic)([^a-z]|$)").containsMatchIn(path + "?" + query) || full.contains("عربي")
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
    val adBlockContext = LocalContext.current
    remember(adBlockContext) { AdBlockStore.load(adBlockContext) }

    val context = LocalContext.current
    val captures = remember { mutableStateListOf<BrowserCapture>() }
    val subtitleUrls = remember { mutableStateListOf<String>() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var address by remember { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("CinemaStream") }
    var showSources by remember { mutableStateOf(false) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isFullScreen by remember { mutableStateOf(false) }
    var fullScreenCustomView by remember { mutableStateOf<android.view.View?>(null) }
    var fullScreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    val playTitle = remember(media, pageTitle) {
        media?.originalTitle?.takeIf { it.isNotBlank() }
            ?: media?.title?.takeIf { it.isNotBlank() }
            ?: pageTitle
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

    val currentUrlRef = remember { java.util.concurrent.atomic.AtomicReference("") }
    var canGoBackState by remember { mutableStateOf(false) }
    var webViewKey by remember { mutableStateOf(0) }

    androidx.activity.compose.BackHandler(enabled = isFullScreen || canGoBackState) {
        if (isFullScreen) {
            fullScreenCustomView?.let { custom -> (custom.parent as? ViewGroup)?.removeView(custom) }
            fullScreenCustomView = null
            fullScreenCallback?.onCustomViewHidden()
            fullScreenCallback = null
            isFullScreen = false
        } else {
            webViewInstance?.goBack()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.let { it.stopLoading(); it.destroy() }
            webViewInstance = null
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

        key(webViewKey) {
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
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        setSupportMultipleWindows(true)
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
                            if (!isUserGesture || resultMsg == null) return false
                            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                            val popup = WebView(ctx)
                            popup.settings.javaScriptEnabled = true
                            popup.settings.domStorageEnabled = true
                            popup.settings.javaScriptCanOpenWindowsAutomatically = false
                            popup.settings.userAgentString = view?.settings?.userAgentString.orEmpty()

                            var handled = false
                            fun route(target: String) {
                                if (handled) return
                                handled = true
                                if (!isBlockedAdUrl(target, currentUrlRef.get().ifBlank { address }) && isSameSite(target, currentUrlRef.get().ifBlank { address })) {
                                    mainHandler.post {
                                        address = target
                                        webViewInstance?.loadUrl(target)
                                    }
                                }
                                popup.post {
                                    runCatching {
                                        popup.stopLoading()
                                        popup.destroy()
                                    }
                                }
                            }

                            popup.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val target = request?.url?.toString() ?: return true
                                    route(target)
                                    return true
                                }

                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: android.graphics.Bitmap?
                                ) {
                                    if (!url.isNullOrBlank() &&
                                        (url.startsWith("https://") || url.startsWith("http://"))) {
                                        route(url)
                                    }
                                }
                            }

                            transport.webView = popup
                            resultMsg.sendToTarget()
                            return true
                        }

                        override fun onPermissionRequest(request: PermissionRequest?) {
                            request?.deny()
                        }

                        override fun onJsAlert(
                            view: WebView?, url: String?, message: String?, result: JsResult?
                        ): Boolean {
                            result?.confirm()
                            return true
                        }

                        override fun onJsConfirm(
                            view: WebView?, url: String?, message: String?, result: JsResult?
                        ): Boolean {
                            result?.cancel()
                            return true
                        }

                        override fun onJsPrompt(
                            view: WebView?, url: String?, message: String?,
                            defaultValue: String?, result: JsPromptResult?
                        ): Boolean {
                            result?.cancel()
                            return true
                        }

                        override fun onShowCustomView(
                            view: android.view.View?, callback: CustomViewCallback?
                        ) {
                            if (view == null || fullScreenCustomView != null) {
                                callback?.onCustomViewHidden()
                                return
                            }
                            val activity = ctx as? Activity ?: run { callback?.onCustomViewHidden(); return }
                            val decor = activity.window.decorView as? ViewGroup ?: run { callback?.onCustomViewHidden(); return }
                            fullScreenCustomView = view
                            fullScreenCallback = callback
                            decor.addView(view, ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            ))
                            isFullScreen = true
                        }

                        override fun onHideCustomView() {
                            fullScreenCustomView?.let { custom ->
                                (custom.parent as? ViewGroup)?.removeView(custom)
                            }
                            fullScreenCustomView = null
                            fullScreenCallback?.onCustomViewHidden()
                            fullScreenCallback = null
                            isFullScreen = false
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            if (!url.isNullOrBlank()) {
                                currentUrlRef.set(url)
                                address = url
                            }
                        }

                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            canGoBackState = view?.canGoBack() == true
                        }

                        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                            mainHandler.post {
                                webViewInstance?.let { runCatching { (it.parent as? ViewGroup)?.removeView(it); it.destroy() } }
                                webViewInstance = null
                                webViewKey += 1
                            }
                            return true
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val target = request?.url?.toString() ?: return true
                            return isBlockedAdUrl(target, currentUrlRef.get().ifBlank { address })
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            req: WebResourceRequest?
                        ): WebResourceResponse? {
                            val url = req?.url?.toString() ?: return null
                            if (isBlockedAdUrl(url, currentUrlRef.get().ifBlank { address })) {
                                return WebResourceResponse(
                                    "text/plain", "UTF-8", 204, "No Content",
                                    emptyMap<String, String>(),
                                    ByteArrayInputStream(ByteArray(0))
                                )
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
                                    BROWSER_UA.let {
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
                                            if (src && (lang.includes('ar') || lang.includes('arabic') || lang.includes('عربي') || /[._\/=-](ar|ara|arabic)[._\/=-]/i.test(src))) {
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
        }

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
                items(captures.sortedByDescending { captureScore(it) }, key = { it.url }) { item ->
                    val isBest = item.playable && item.url == captures.filter { it.playable }.maxByOrNull { captureScore(it) }?.url
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text((if (isBest) "⭐ " else "") + item.kind, style = MaterialTheme.typography.labelMedium, color = Color.White)
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

private fun captureScore(c: BrowserCapture): Int {
    if (!c.playable) return -100
    val u = c.url.lowercase()
    var sc = 0
    if ("master" in u) sc += 50
    if (".m3u8" in u) sc += 40 else if (".mpd" in u) sc += 30 else if (".mp4" in u) sc += 20
    if (Regex("(^|[/_.-])(ad|ads|advert|preroll|promo|trailer)([/_.-]|$)").containsMatchIn(u)) sc -= 80
    return sc
}
