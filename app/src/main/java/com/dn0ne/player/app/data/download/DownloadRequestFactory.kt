package com.dn0ne.player.app.data.download

import com.dn0ne.player.app.domain.download.DownloadAudioFormat
import com.dn0ne.player.app.domain.download.DownloadAudioQuality
import com.dn0ne.player.app.domain.download.DownloadPreferences
import com.dn0ne.player.app.domain.download.DownloadType
import com.dn0ne.player.app.domain.youtube.VideoInfo
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/**
 * Builds the audio-only yt-dlp request.
 *
 * Ported from Seal's `DownloadUtil.addOptionsForAudioDownloads`, restricted to the
 * settings Lotus exposes. `-x` always extracts audio; `--audio-format` is only used for
 * the M4A/MP3 conversion options, never for the default Opus path.
 */
object DownloadRequestFactory {
    const val DEFAULT_OUTPUT_TEMPLATE = "%(title).200B.%(ext)s"

    fun build(
        videoInfo: VideoInfo,
        taskUrl: String,
        type: DownloadType,
        preferences: DownloadPreferences,
        outputDir: File,
    ): YoutubeDLRequest {
        val downloadUrl = when (type) {
            is DownloadType.Playlist -> taskUrl
            DownloadType.Url -> videoInfo.originalUrl
                ?: videoInfo.webpageUrl
                ?: taskUrl
        }

        return YoutubeDLRequest(downloadUrl).apply {
            addOption("--no-mtime")
            addOption("--newline")
            addOption("-R", "1")
            addOption("--socket-timeout", "5")

            if (type is DownloadType.Playlist) {
                addOption("--playlist-items", type.index.toString())
            } else {
                addOption("--no-playlist")
            }

            addOption("-x")
            addOption("-f", "bestaudio/best")
            applyFormatSorter(preferences)
            addOption("-P", outputDir.absolutePath)
            addOption("-o", preferences.outputTemplate.ifBlank { DEFAULT_OUTPUT_TEMPLATE })

            if (preferences.embedMetadata) {
                addOption("--embed-metadata")
                addOption("--parse-metadata", "%(release_year,upload_date)s:%(meta_date)s")
                if (type is DownloadType.Playlist) {
                    addOption("--parse-metadata", "%(album,playlist,title)s:%(meta_album)s")
                    addOption("--parse-metadata", "%(track_number,playlist_index)d:%(meta_track)s")
                } else {
                    addOption("--parse-metadata", "%(album,title)s:%(meta_album)s")
                }
            }

            if (preferences.embedThumbnail) {
                addOption("--embed-thumbnail")
                addOption("--convert-thumbnails", "jpg")
            }

            // Lets us recover the final file path after audio extraction without guessing.
            // `--print` implies `--quiet`, which would hide progress lines from the parser,
            // so `--no-quiet` must come afterwards.
            addOption("--print", "after_move:filepath")
            addOption("--no-quiet")
        }
    }

    private fun YoutubeDLRequest.applyFormatSorter(preferences: DownloadPreferences) {
        val quality = when (preferences.audioQuality) {
            DownloadAudioQuality.HIGH -> "abr~192"
            DownloadAudioQuality.MEDIUM -> "abr~128"
            DownloadAudioQuality.LOW -> "abr~64"
        }

        when (preferences.audioFormat) {
            DownloadAudioFormat.OPUS -> {
                // Opus is extracted as-is; no lossy re-encode.
                addOption("-S", "acodec:opus,$quality")
            }

            DownloadAudioFormat.M4A -> {
                addOption("--audio-format", "m4a")
                addOption("-S", "acodec:aac,$quality")
            }

            DownloadAudioFormat.MP3 -> {
                addOption("--audio-format", "mp3")
                addOption("-S", quality)
            }

            DownloadAudioFormat.ORIGINAL -> {
                addOption("-S", quality)
            }
        }
    }
}
