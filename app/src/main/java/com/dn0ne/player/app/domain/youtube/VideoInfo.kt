package com.dn0ne.player.app.domain.youtube

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Trimmed port of Seal's `util/VideoInfo.kt`.
 *
 * Cookies, proxies, requested download bookkeeping and subtitle/chapter structures are
 * intentionally omitted; only the fields needed for search, metadata, preview and audio
 * downloads are kept.
 */
sealed interface YoutubeInfo

@Serializable
data class VideoInfo(
    val id: String = "",
    val title: String = "",
    val formats: List<Format>? = emptyList(),
    val thumbnail: String? = null,
    val description: String? = null,
    val uploader: String? = null,
    @SerialName("uploader_id") val uploaderId: String? = null,
    val duration: Double? = null,
    @SerialName("view_count") val viewCount: Long? = null,
    @SerialName("webpage_url") val webpageUrl: String? = null,
    val tags: List<String>? = emptyList(),
    @SerialName("live_status") val liveStatus: String? = null,
    val channel: String? = null,
    @SerialName("upload_date") val uploadDate: String? = null,
    val availability: String? = null,
    @SerialName("original_url") val originalUrl: String? = null,
    val extractor: String? = null,
    @SerialName("extractor_key") val extractorKey: String = "",
    val playlist: String? = null,
    @SerialName("playlist_index") val playlistIndex: Int? = null,
    @SerialName("display_id") val displayId: String? = null,
    val fulltitle: String? = null,
    @SerialName("duration_string") val durationString: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val format: String? = null,
    @SerialName("format_id") val formatId: String? = null,
    val ext: String = "",
    val protocol: String? = null,
    @SerialName("format_note") val formatNote: String? = null,
    @SerialName("filesize_approx") val fileSizeApprox: Double? = null,
    @SerialName("filesize") val fileSize: Double? = null,
    val tbr: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
    val resolution: String? = null,
    val fps: Double? = null,
    val vcodec: String? = null,
    val vbr: Double? = null,
    val acodec: String? = null,
    val abr: Double? = null,
    val asr: Int? = null,
    @SerialName("requested_downloads") val requestedDownloads: List<RequestedDownload>? = null,
    @SerialName("requested_formats") val requestedFormats: List<Format>? = null,
    val filename: String? = null,
    @SerialName("_type") val type: String? = null,
) : YoutubeInfo

@Serializable
data class Format(
    @SerialName("format_id") val formatId: String? = null,
    @SerialName("format_note") val formatNote: String? = null,
    val ext: String? = null,
    @SerialName("acodec") val acodec: String? = null,
    @SerialName("vcodec") val vcodec: String? = null,
    val url: String? = null,
    val width: Double? = null,
    val height: Double? = null,
    val fps: Double? = null,
    @SerialName("audio_ext") val audioExt: String? = null,
    @SerialName("video_ext") val videoExt: String? = null,
    val format: String? = null,
    val resolution: String? = null,
    val vbr: Double? = null,
    val abr: Double? = null,
    val tbr: Double? = null,
    @SerialName("filesize") val fileSize: Double? = null,
    @SerialName("filesize_approx") val fileSizeApprox: Double? = null,
) {
    fun isAudioOnly(): Boolean = vcodec == null || vcodec == "none"

    fun containsAudio(): Boolean = acodec != null && acodec != "none"
}

@Serializable
data class RequestedDownload(
    @SerialName("requested_formats") val requestedFormats: List<Format>? = emptyList(),
    @SerialName("format_id") val formatId: String? = null,
    @SerialName("format_note") val formatNote: String? = null,
    val ext: String? = null,
    @SerialName("acodec") val acodec: String? = null,
    @SerialName("vcodec") val vcodec: String? = null,
    val url: String? = null,
    val width: Double? = null,
    val height: Double? = null,
    val fps: Double? = null,
    @SerialName("audio_ext") val audioExt: String? = null,
    @SerialName("video_ext") val videoExt: String? = null,
    val format: String? = null,
    val resolution: String? = null,
    val vbr: Double? = null,
    val abr: Double? = null,
    val tbr: Double? = null,
    @SerialName("filesize") val fileSize: Double? = null,
    @SerialName("filesize_approx") val fileSizeApprox: Double? = null,
    val filename: String? = null,
)

@Serializable
data class PlaylistResult(
    val uploader: String? = null,
    val availability: String? = null,
    val channel: String? = null,
    val title: String? = null,
    val description: String? = null,
    @SerialName("_type") val type: String? = null,
    val entries: List<PlaylistEntry>? = emptyList(),
    @SerialName("webpage_url") val webpageUrl: String? = null,
    @SerialName("original_url") val originalUrl: String? = null,
    @SerialName("extractor_key") val extractorKey: String? = null,
) : YoutubeInfo

@Serializable
data class Thumbnail(
    val url: String,
    val height: Double = .0,
    val width: Double = .0,
)

@Serializable
data class PlaylistEntry(
    @SerialName("_type") val type: String? = null,
    @SerialName("ie_key") val ieKey: String? = null,
    val id: String? = null,
    val url: String? = null,
    val title: String? = null,
    val duration: Double? = .0,
    val uploader: String? = null,
    val channel: String? = null,
    val thumbnails: List<Thumbnail>? = emptyList(),
)

/** UI-friendly search hit, built from a flat [PlaylistEntry]. */
data class YoutubeSearchItem(
    val id: String,
    val title: String,
    val uploader: String?,
    val durationSeconds: Int?,
    val thumbnailUrl: String?,
    val url: String,
)
