package com.dn0ne.player.app.data.remote.youtube

import com.dn0ne.player.app.domain.youtube.PlaylistEntry
import com.dn0ne.player.app.domain.youtube.PlaylistResult
import com.dn0ne.player.app.domain.youtube.VideoInfo
import com.dn0ne.player.app.domain.youtube.YoutubeInfo
import com.dn0ne.player.app.domain.youtube.YoutubeSearchItem

/** Resolved direct audio URL used by the preview player. */
data class AudioStream(
    val videoId: String,
    val url: String,
    val timestamp: Long,
    val protocol: String? = null,
    val extension: String? = null,
)

interface YoutubeProvider {
    /**
     * Runs `ytsearch<limit>:<query>` with a flat playlist dump and returns the entries.
     * Results are cached in memory for five minutes.
     */
    suspend fun search(query: String, limit: Int = 10): Result<List<YoutubeSearchItem>>

    suspend fun fetchVideoInfo(
        url: String,
        playlistIndex: Int? = null,
        processId: String? = null,
    ): Result<VideoInfo>

    /** `--flat-playlist --dump-single-json`; returns either a playlist or a single video. */
    suspend fun fetchPlaylistOrVideoInfo(url: String): Result<YoutubeInfo>

    /** Same as [fetchPlaylistOrVideoInfo] but always shaped as a [PlaylistResult]. */
    suspend fun fetchPlaylistInfo(url: String): Result<PlaylistResult>

    /**
     * Resolves a direct audio stream URL with `-f bestaudio -g --no-playlist`.
     * m4a is preferred because ExoPlayer plays it reliably on every supported device.
     * The result is cached for ten minutes; pass [forceRefresh] after a 403.
     */
    suspend fun resolveAudioStream(
        videoId: String,
        forceRefresh: Boolean = false,
    ): Result<AudioStream>

    /**
     * Starts the short, global fetch cooldown. Called when YouTube answers with a 429 or a
     * bot check, so the next queued tasks fail fast instead of hammering YouTube.
     */
    fun beginRateLimitCooldown()
}

/** Converts a flat playlist entry into a search hit using the image CDN thumbnail. */
fun PlaylistEntry.toSearchItem(): YoutubeSearchItem? {
    val videoId = id?.takeIf { it.isNotBlank() } ?: return null
    return YoutubeSearchItem(
        id = videoId,
        title = title ?: videoId,
        uploader = uploader ?: channel,
        durationSeconds = duration?.toInt(),
        thumbnailUrl = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
        url = "https://www.youtube.com/watch?v=$videoId",
    )
}
