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
    val playable: Boolean
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
    val path = Uri.parse(url).path.orEmpty().lowercase()

    return when {
        path.endsWith(".m3u8") ->
            BrowserCapture(url, "فيديو HLS", true)

        path.endsWith(".mpd") ->
            BrowserCapture(url, "فيديو DASH", true)

        path.endsWith(".mp4") ||
        path.endsWith(".m4v") ||
        path.endsWith(".webm") ->
            BrowserCapture(url, "فيديو مباشر", true)

        path.endsWith(".srt") ||
        path.endsWith(".vtt") ||
        path.endsWith(".ass") ||
        path.endsWith(".ssa") ||
        url.contains("subtitle", true) ||
        url.contains("subtitel", true) ||
        url.contains("/sub/", true) ->
            BrowserCapture(url, "مورد ترجمة محتمل - غير مؤكد", false)

        else -> null
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    onPlayUrl: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val captures = remember { mutableStateListOf<BrowserCapture>() }
    val handler = remember { Handler(Looper.getMainLooper()) }

    var address by remember { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("CinemaStream") }
    var browser by remember { mutableStateOf<WebView?>(null) }

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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
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

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.setSupportMultipleWindows(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false

                    addJavascriptInterface(
                        BrowserBridge { url ->
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
                            }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): android.webkit.WebResourceResponse? {
                            request?.url?.toString()?.let { url ->
                                classifyResource(url)?.let(::addCapture)
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
                                    onPlayUrl(item.url, pageTitle)
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
