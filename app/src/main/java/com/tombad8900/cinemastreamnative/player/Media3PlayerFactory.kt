package com.tombad8900.cinemastreamnative.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

object Media3PlayerFactory {

    fun create(
        context: Context,
        url: String,
        headers: Map<String, String> = emptyMap(),
        subtitleUrl: String? = null,
        subtitleUrls: List<String> = emptyList()
    ): ExoPlayer {

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)
            .setDefaultRequestProperties(headers)

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredTextLanguage("ar")
            )
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15000,
                50000,
                1500,
                3000
            )
            .build()

        val cleanUrl = url.substringBefore("?").lowercase()

        val mimeType = when {
            cleanUrl.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            cleanUrl.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            cleanUrl.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
            else -> null
        }

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(url))

        if (mimeType != null) {
            mediaItemBuilder.setMimeType(mimeType)
        }

        val subtitleCandidates = (listOfNotNull(subtitleUrl) + subtitleUrls)
            .distinct()
            .mapNotNull { subtitle ->
                val path = Uri.parse(subtitle).path.orEmpty().lowercase()
                val mime = when {
                    path.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
                    path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                    path.endsWith(".ttml") || path.endsWith(".dfxp") ->
                        MimeTypes.APPLICATION_TTML
                    path.endsWith(".ass") || path.endsWith(".ssa") ->
                        MimeTypes.TEXT_SSA
                    else -> null
                }
                if (mime == null) null else {
                    val language = if (
                        subtitle.contains("arabic", true) ||
                        subtitle.contains("/ar/", true) ||
                        subtitle.contains("lang=ar", true) ||
                        subtitle.contains("language=ar", true)
                    ) "ar" else "und"
                    Triple(subtitle, mime, language)
                }
            }

        if (subtitleCandidates.isNotEmpty()) {
            val defaultIndex = subtitleCandidates.indexOfFirst { it.third == "ar" }
                .let { if (it >= 0) it else 0 }

            val configurations = subtitleCandidates.mapIndexed { index, item ->
                val (url, mime, language) = item
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                    .setMimeType(mime)
                    .setLanguage(language)
                    .setLabel("ترجمة ${index + 1}")
                    .setSelectionFlags(
                        if (index == defaultIndex) C.SELECTION_FLAG_DEFAULT else 0
                    )
                    .build()
            }
            mediaItemBuilder.setSubtitleConfigurations(configurations)
        }

        val mediaItem = mediaItemBuilder.build()

        return ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(httpFactory)
            )
            .build()
            .apply {
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }
    }
}
