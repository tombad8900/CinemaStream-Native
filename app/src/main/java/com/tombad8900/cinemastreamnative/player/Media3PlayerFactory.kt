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
        subtitleUrl: String? = null
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

        if (!subtitleUrl.isNullOrBlank()) {
            val subtitlePath = subtitleUrl
                .substringBefore("?")
                .lowercase()

            val subtitleMime = when {
                subtitlePath.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
                subtitlePath.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                subtitlePath.endsWith(".ttml") -> MimeTypes.APPLICATION_TTML
                subtitlePath.endsWith(".ass") ||
                    subtitlePath.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                else -> null
            }

            if (subtitleMime != null) {
                val subtitle = MediaItem.SubtitleConfiguration.Builder(
                    Uri.parse(subtitleUrl)
                )
                    .setMimeType(subtitleMime)
                    .setLanguage("ar")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()

                mediaItemBuilder.setSubtitleConfigurations(listOf(subtitle))
            }
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
