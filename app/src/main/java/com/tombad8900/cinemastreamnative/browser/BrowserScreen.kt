package com.tombad8900.cinemastreamnative.browser

import com.tombad8900.cinemastreamnative.security.AdBlockStore
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.ui.draw.clipToBounds
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
import java.net.URLDecoder
import android.util.Base64
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
    private val onSubtitle: (String, String, String) -> Unit,
    private val onContext: (String, String) -> Unit
) {
    @JavascriptInterface
    fun subtitle(url: String, pageUrl: String, signature: String) {
        onSubtitle(url, pageUrl, signature)
    }

    @JavascriptInterface
    fun context(pageUrl: String, signature: String) {
        onContext(pageUrl, signature)
    }
}

private fun unwrapWrappedUrl(raw: String): String? {
    val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
    if (!uri.isHierarchical) return null
    val accepted = setOf(
        "url", "file", "src", "source", "link",
        "stream", "video", "media", "data"
    )

    fun valid(value: String): Boolean {
        val parsed = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        return (parsed.scheme.equals("http", true) ||
                parsed.scheme.equals("https", true)) &&
                !parsed.host.isNullOrBlank()
    }

    for (key in uri.queryParameterNames) {
        if (key.lowercase() !in accepted) continue

        val candidate = uri.getQueryParameter(key)?.trim().orEmpty()
        if (candidate.isBlank()) continue
        if (valid(candidate)) return candidate

        // Uri فك طبقة الترميز الأولى بالفعل؛ نفك طبقة إضافية فقط.
        val decoded = runCatching {
            URLDecoder.decode(candidate.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(candidate)

        if (decoded != candidate && valid(decoded)) return decoded

        // دعم Base64 فقط إذا نتج عنه رابط HTTP/HTTPS صالح.
        val compact = candidate.replace('-', '+').replace('_', '/')
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        val base64 = runCatching {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8).trim()
        }.getOrNull()

        if (!base64.isNullOrBlank() && valid(base64)) return base64
    }

    return null
}

private fun classifyResource(url: String): BrowserCapture? {
    val unwrapped = unwrapWrappedUrl(url)
    if (!unwrapped.isNullOrBlank() && unwrapped != url) {
        classifyResource(unwrapped)?.let { return it }
    }

    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    val path = uri.path.orEmpty().lowercase()
    val query = uri.encodedQuery.orEmpty().lowercase()
    val full = url.lowercase()

    if (uri.scheme != "http" && uri.scheme != "https") return null

    val isArabicSub = ARABIC_SUB_REGEX.containsMatchIn(path + "?" + query) || full.contains("عربي")
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
    val clipboardManager = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val captures = remember { mutableStateListOf<BrowserCapture>() }
    val subtitleUrls = remember { mutableStateListOf<String>() }
    val captureSession = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    fun clearCaptureSession() {
        captureSession.incrementAndGet()
        captures.clear()
        subtitleUrls.clear()
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var address by remember { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("CinemaStream") }
    var showSources by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }
    var savedSites by remember { mutableStateOf(loadSavedSites(context)) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isFullScreen by remember { mutableStateOf(false) }
    var fullScreenCustomView by remember { mutableStateOf<android.view.View?>(null) }
    var fullScreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    val playTitle = remember(media, pageTitle) {
        media?.originalTitle?.takeIf { it.isNotBlank() }
            ?: media?.title?.takeIf { it.isNotBlank() }
            ?: pageTitle
    }

    fun addCapture(capture: BrowserCapture, session: Long = captureSession.get()) {
        mainHandler.post {
            if (captureSession.get() != session) return@post

            val index = captures.indexOfFirst { it.url == capture.url }

            if (index < 0) {
                if (captures.size >= 100) captures.removeAt(0)
                captures.add(capture)
            } else {
                val old = captures[index]
                val merged = old.headers.toMutableMap()

                capture.headers.forEach { (name, value) ->
                    if (value.isNotBlank()) {
                        val existing = merged.keys.firstOrNull {
                            it.equals(name, ignoreCase = true)
                        }
                        if (existing == null) {
                            merged[name] = value
                        } else {
                            merged[existing] = value
                        }
                    }
                }

                captures[index] = old.copy(
                    kind = if (capture.playable && !old.playable)
                        capture.kind else old.kind,
                    playable = old.playable || capture.playable,
                    isArabicSubtitle = old.isArabicSubtitle || capture.isArabicSubtitle,
                    headers = merged
                )
            }
        }
    }

    fun addSubtitle(url: String, session: Long = captureSession.get()) {
        mainHandler.post {
            if (captureSession.get() != session) return@post
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

        clearCaptureSession()
        address = url
        showSources = false
        webViewInstance?.loadUrl(url)
    }

    LaunchedEffect(media?.id, season, episode) {
        if (media != null) {
            clearCaptureSession()
            showSources = true
        }
    }

    val currentUrlRef = remember { java.util.concurrent.atomic.AtomicReference("") }
    val captureContextRef = remember { java.util.concurrent.atomic.AtomicReference("") }

    fun acceptCaptureContext(pageUrl: String, signature: String) {
        if (signature.isBlank()) return

        mainHandler.post {
            val currentPage = currentUrlRef.get().ifBlank { address }
            if (!isSameSite(pageUrl, currentPage)) return@post

            val previous = captureContextRef.getAndSet(signature)

            if (previous.isNotBlank() && previous != signature) {
                clearCaptureSession()
                captureContextRef.set(signature)
            }
        }
    }

    fun updateMainPage(url: String) {
        val previousUrl = currentUrlRef.get()
        if (previousUrl.isNotBlank() && previousUrl != url) {
            clearCaptureSession()
            captureContextRef.set("")
        }
        currentUrlRef.set(url)
        address = url
    }
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
            fullScreenCustomView?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
            fullScreenCallback?.onCustomViewHidden()
            fullScreenCustomView = null
            fullScreenCallback = null
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
        if (showSaved) {
            AlertDialog(
                onDismissRequest = { showSaved = false },
                title = { Text("المواقع المحفوظة") },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        item {
                            Column {
                                Button(
                                    onClick = {
                                        val cur = currentUrlRef.get().ifBlank { address }
                                        if ((cur.startsWith("http://") || cur.startsWith("https://")) && savedSites.none { it.second == cur }) {
                                            val name = Uri.parse(cur).host?.removePrefix("www.") ?: cur
                                            val updated = savedSites + (name to cur)
                                            savedSites = updated
                                            saveSavedSites(context, updated)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("حفظ الصفحة الحالية") }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        if (savedSites.isEmpty()) {
                            item { Text("لا توجد مواقع محفوظة بعد. افتح موقعًا ثم اضغط حفظ الصفحة الحالية.") }
                        } else {
                            items(savedSites, key = { it.second }) { site ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        onClick = {
                                            captures.clear()
                                            subtitleUrls.clear()
                                            address = site.second
                                            webViewInstance?.loadUrl(site.second)
                                            showSaved = false
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text(site.first, maxLines = 1) }
                                    TextButton(onClick = {
                                        val updated = savedSites.filter { it.second != site.second }
                                        savedSites = updated
                                        saveSavedSites(context, updated)
                                    }) { Text("حذف") }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showSaved = false }) { Text("إغلاق") } }
            )
        }

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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { showSaved = true }) { Text("المواقع المحفوظة") }
                Button(onClick = { showSources = true }) { Text("المصادر") }
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
                        clearCaptureSession()
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
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().background(Color(0xFF202020))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewInstance = this
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(0xFF202020.toInt())
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
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
                        BrowserBridge(
                            onSubtitle = { subUrl, pageUrl, signature ->
                                if (captureContextRef.get() == signature &&
                                    isSameSite(
                                        pageUrl,
                                        currentUrlRef.get().ifBlank { address }
                                    )
                                ) {
                                    val session = captureSession.get()
                                    addSubtitle(subUrl, session)
                                    addCapture(
                                        BrowserCapture(
                                            subUrl,
                                            "ترجمة عربية 💬",
                                            false,
                                            true
                                        ),
                                        session
                                    )
                                }
                            },
                            onContext = { pageUrl, signature ->
                                acceptCaptureContext(pageUrl, signature)
                            }
                        ),
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
                            val container = android.widget.FrameLayout(ctx).apply {
                                setBackgroundColor(android.graphics.Color.BLACK)
                                isClickable = true
                                addView(view, android.widget.FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                ))
                                addView(android.widget.TextView(ctx).apply {
                                    text = "✕ إغلاق"
                                    textSize = 16f
                                    setTextColor(android.graphics.Color.WHITE)
                                    setBackgroundColor(0x99000000.toInt())
                                    setPadding(40, 28, 40, 28)
                                    setOnClickListener { onHideCustomView() }
                                }, android.widget.FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    android.view.Gravity.TOP or android.view.Gravity.END
                                ))
                            }
                            fullScreenCustomView = container
                            fullScreenCallback = callback
                            decor.addView(container, ViewGroup.LayoutParams(
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
                            if (!url.isNullOrBlank()) updateMainPage(url)
                        }

                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            if (!url.isNullOrBlank()) updateMainPage(url)
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
                            val requestSession = captureSession.get()
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

                                // أرفق Cookies الخاصة بعنوان طلب الفيديو إن لم تكن موجودة.
                                if (headers.keys.none { it.equals("Cookie", true) }) {
                                    val cookies = android.webkit.CookieManager
                                        .getInstance()
                                        .getCookie(url)
                                    if (!cookies.isNullOrBlank()) {
                                        headers["Cookie"] = cookies
                                    }
                                }

                                if (headers.keys.none { it.equals("User-Agent", true) }) {
                                    BROWSER_UA.let {
                                        headers["User-Agent"] = it
                                    }
                                }
                                if (headers.keys.none { it.equals("Referer", true) } &&
                                    address.startsWith("http", true)) {
                                    headers["Referer"] = address
                                }
                                addCapture(item.copy(headers = headers), requestSession)
                                if (!item.playable) addSubtitle(item.url, requestSession)
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

                                    if (window.__csCaptureWatcher) return;
                                    window.__csCaptureWatcher = true;

                                    function contextKey() {
                                        var parts = [location.href];

                                        document.querySelectorAll('video').forEach(function(v) {
                                            parts.push(v.currentSrc || v.src || '');

                                            v.querySelectorAll('source').forEach(function(x) {
                                                parts.push(x.src || x.getAttribute('src') || '');
                                            });
                                        });

                                        document.querySelectorAll(
                                            '[aria-current="true"],[aria-selected="true"],' +
                                            '[data-episode-id],[data-episode],.episode.active,.episodes .active'
                                        ).forEach(function(el) {
                                            parts.push(
                                                el.getAttribute('data-episode-id') ||
                                                el.getAttribute('data-episode') ||
                                                el.getAttribute('aria-label') ||
                                                (el.textContent || '').trim().slice(0, 100)
                                            );
                                        });

                                        return parts.join('|').slice(0, 3000);
                                    }

                                    var lastContext = '';
                                    var timer = 0;

                                    function checkContext() {
                                        clearTimeout(timer);

                                        timer = setTimeout(function() {
                                            var next = contextKey();

                                            if (lastContext && next !== lastContext) {
                                                lastContext = next;
                                                if (window.CinemaCapture) {
                                                    window.CinemaCapture.context(location.href, next);
                                                }
                                            } else if (!lastContext) {
                                                lastContext = next;
                                            }
                                        }, 700);
                                    }

                                    function checkTracks() {
                                        var signature = contextKey();

                                        document.querySelectorAll('video track, track').forEach(function(t) {
                                            var lang = (t.srclang || t.label || '').toLowerCase();
                                            var src = t.src;

                                            if (src && (
                                                lang.includes('ar') ||
                                                lang.includes('arabic') ||
                                                lang.includes('عربي') ||
                                                /[._\\/-=](ar|ara|arabic)[._\\/-=]/i.test(src)
                                            )) {
                                                if (window.CinemaCapture) {
                                                    window.CinemaCapture.subtitle(
                                                        src, location.href, signature
                                                    );
                                                }
                                            }
                                        });
                                    }

                                    // ننتظر استقرار المشغل الأول قبل اعتماد خط الأساس.
                                    setTimeout(function() {
                                        lastContext = contextKey();

                                        if (window.CinemaCapture) {
                                            window.CinemaCapture.context(
                                                location.href, lastContext
                                            );
                                        }

                                        var observer = new MutationObserver(checkContext);
                                        observer.observe(document.documentElement, {
                                            subtree: true,
                                            childList: true,
                                            attributes: true,
                                            attributeFilter: [
                                                'src', 'class', 'aria-current',
                                                'aria-selected', 'data-episode-id',
                                                'data-episode'
                                            ]
                                        });
                                    }, 2200);

                                    document.addEventListener('click', function(e) {
                                        var target = e.target && e.target.closest ?
                                            e.target.closest(
                                                '[data-episode-id],[data-episode],a,button,[role="button"]'
                                            ) : null;

                                        if (target) checkContext();
                                    }, true);

                                    var push = history.pushState;
                                    history.pushState = function() {
                                        var result = push.apply(this, arguments);
                                        checkContext();
                                        return result;
                                    };

                                    var replace = history.replaceState;
                                    history.replaceState = function() {
                                        var result = replace.apply(this, arguments);
                                        checkContext();
                                        return result;
                                    };

                                    window.addEventListener('popstate', checkContext);
                                    window.addEventListener('hashchange', checkContext);

                                    checkTracks();
                                    setInterval(function() {
                                        checkContext();
                                        checkTracks();
                                    }, 1800);
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
        }

        if (captures.isNotEmpty()) {
            val ranked = captures.toList().sortedByDescending { captureScore(it) }
            val bestUrl = ranked.firstOrNull { it.playable }?.url
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
                items(ranked, key = { it.url }) { item ->
                    val isBest = item.playable && item.url == bestUrl

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        color = Color(0xFF2B2B2B),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    (if (isBest) "⭐ " else "") + item.kind,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White
                                )
                                Text(
                                    item.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.LightGray,
                                    maxLines = 2
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (item.playable) {
                                    Button(
                                        onClick = {
                                            onPlayCaptured(
                                                item.url,
                                                playTitle,
                                                item.headers,
                                                subtitleUrls.distinct()
                                            )
                                        },
                                        modifier = Modifier.height(40.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        shape = MaterialTheme.shapes.small,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFFD6AA55),
                                            contentColor = Color(0xFF20180A)
                                        )
                                    ) {
                                        Text("تشغيل", style = MaterialTheme.typography.labelMedium)
                                    }
                                }

                                OutlinedButton(
                                    onClick = {
                                        clipboardManager.setPrimaryClip(
                                            ClipData.newPlainText("CinemaStream URL", item.url)
                                        )
                                    },
                                    modifier = Modifier.height(40.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    shape = MaterialTheme.shapes.small,
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color(0xFFE2C27B)
                                    )
                                ) {
                                    Text("نسخ", style = MaterialTheme.typography.labelMedium)
                                }
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
    if (AD_URL_REGEX.containsMatchIn(u)) sc -= 80
    return sc
}

private fun loadSavedSites(ctx: android.content.Context): List<Pair<String, String>> {
    val raw = ctx.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
        .getString("saved_sites", "[]") ?: "[]"
    return runCatching {
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val u = o.optString("url")
            if (u.isBlank()) null else o.optString("name").ifBlank { u } to u
        }
    }.getOrDefault(emptyList())
}

private fun saveSavedSites(ctx: android.content.Context, sites: List<Pair<String, String>>) {
    val arr = org.json.JSONArray()
    sites.forEach { (n, u) -> arr.put(org.json.JSONObject().put("name", n).put("url", u)) }
    ctx.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
        .edit().putString("saved_sites", arr.toString()).apply()
}

private val AD_URL_REGEX = Regex("(^|[/_.-])(ad|ads|advert|preroll|promo|trailer)([/_.-]|$)")
private val ARABIC_SUB_REGEX = Regex("(^|[^a-z])(ar|ara|arabic)([^a-z]|$)")
