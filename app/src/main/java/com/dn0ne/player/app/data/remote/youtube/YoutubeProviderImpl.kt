package com.dn0ne.player.app.data.remote.youtube

import android.net.Uri
import android.util.Log
import com.dn0ne.player.app.data.download.DownloadErrorClassifier
import com.dn0ne.player.app.data.download.DownloadRateLimitedException
import com.dn0ne.player.app.data.download.YtDlpEngine
import com.dn0ne.player.app.domain.youtube.PlaylistEntry
import com.dn0ne.player.app.domain.youtube.PlaylistResult
import com.dn0ne.player.app.domain.youtube.VideoInfo
import com.dn0ne.player.app.domain.youtube.YoutubeInfo
import com.dn0ne.player.app.domain.youtube.YoutubeSearchItem
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Search + metadata provider backed by yt-dlp.
 *
 * Ported from Seal's `DownloadUtil.getPlaylistOrVideoInfo` / `fetchVideoInfoFromUrl`,
 * with cookies, proxies, IPv4 forcing and format sorting removed.
 */
class YoutubeProviderImpl(
    private val engine: YtDlpEngine,
    httpClient: HttpClient,
) : YoutubeProvider {
    init {
        NewPipeRuntime.initialize(httpClient)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val cacheMutex = Mutex()
    private val searchCache = mutableMapOf<String, CachedSearch>()

    private val streamCacheMutex = Mutex()
    private val streamCache = mutableMapOf<String, AudioStream>()

    /** Epoch millis until which fetches fail fast after a rate limit / bot check. */
    @Volatile
    private var rateLimitCooldownUntil = 0L

    override fun beginRateLimitCooldown() {
        rateLimitCooldownUntil = maxOf(
            rateLimitCooldownUntil,
            System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MILLIS,
        )
    }

    override suspend fun search(query: String, limit: Int): Result<List<YoutubeSearchItem>> =
        withContext(Dispatchers.IO) {
            val normalizedQuery = query.trim()
            if (normalizedQuery.isEmpty()) {
                return@withContext Result.success(emptyList())
            }

            val safeLimit = limit.coerceIn(1, 50)
            val cacheKey = "$safeLimit:$normalizedQuery"
            val now = System.currentTimeMillis()

            val cached = cacheMutex.withLock {
                searchCache[cacheKey]
                    ?.takeIf { now - it.timestamp < SEARCH_CACHE_TTL_MILLIS }
                    ?.items
            }
            if (cached != null) {
                return@withContext Result.success(cached)
            }

            withRateLimitGuard {
                runCatchingCancellable {
                    val fastItems = try {
                        val extractor = ServiceList.YouTube
                            .getSearchExtractor(normalizedQuery, listOf("videos"), "")
                        extractor.fetchPage()
                        extractor.initialPage.items
                            .asSequence()
                            .filterIsInstance<StreamInfoItem>()
                            .mapNotNull { item -> runCatching { item.toYoutubeSearchItem() }.getOrNull() }
                            .take(safeLimit)
                            .toList()
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (fastFailure: Exception) {
                            Log.w(
                                TAG,
                                "Fast search failed; using yt-dlp fallback: ${fastFailure.message ?: fastFailure::class.java.simpleName}",
                                fastFailure,
                            )

                            val request = YoutubeDLRequest("ytsearch$safeLimit:$normalizedQuery").apply {
                                addOption("--flat-playlist")
                                addOption("--dump-single-json")
                                addOption("-R", "1")
                                addOption("--socket-timeout", "5")
                            }

                            engine.awaitReady()
                            val response = engine.execute(request)
                            val playlistResult = json.decodeFromString<PlaylistResult>(response.out)
                            playlistResult.entries.orEmpty().mapNotNull { it.toSearchItem() }
                    }

                    cacheSearchResults(cacheKey, fastItems)
                    fastItems
                }
            }
        }

    private suspend fun cacheSearchResults(
        cacheKey: String,
        items: List<YoutubeSearchItem>,
    ) {
        cacheMutex.withLock {
            val cutoff = System.currentTimeMillis() - SEARCH_CACHE_TTL_MILLIS
            searchCache.entries.removeAll { it.value.timestamp < cutoff }
            searchCache[cacheKey] = CachedSearch(
                timestamp = System.currentTimeMillis(),
                items = items,
            )
        }
    }

    override suspend fun fetchVideoInfo(
        url: String,
        playlistIndex: Int?,
        processId: String?,
    ): Result<VideoInfo> = withContext(Dispatchers.IO) {
        withRateLimitGuard {
            runCatchingCancellable {
                val request = YoutubeDLRequest(url).apply {
                    addOption("-R", "1")
                    addOption("--socket-timeout", "5")
                    if (playlistIndex != null) {
                        addOption("--playlist-items", playlistIndex.toString())
                        addOption("--dump-json")
                    } else {
                        addOption("--dump-single-json")
                    }
                    addOption("--no-playlist")
                }

                val response = engine.execute(request, processId)
                json.decodeFromString<VideoInfo>(response.out)
            }
        }
    }

    override suspend fun fetchPlaylistOrVideoInfo(url: String): Result<YoutubeInfo> =
        withContext(Dispatchers.IO) {
            withRateLimitGuard {
                runCatchingCancellable {
                    val request = YoutubeDLRequest(url).apply {
                        addOption("--flat-playlist")
                        addOption("--dump-single-json")
                        addOption("-R", "1")
                        addOption("--socket-timeout", "5")
                    }

                    val response = engine.execute(request)
                    val playlistResult = json.decodeFromString<PlaylistResult>(response.out)
                    if (playlistResult.type != "playlist") {
                        json.decodeFromString<VideoInfo>(response.out)
                    } else {
                        playlistResult
                    }
                }
            }
        }

    override suspend fun fetchPlaylistInfo(url: String): Result<PlaylistResult> =
        fetchPlaylistOrVideoInfo(url).mapCatching { info ->
            when (info) {
                is PlaylistResult -> info
                is VideoInfo -> PlaylistResult(
                    uploader = info.uploader,
                    channel = info.channel,
                    title = info.title,
                    type = "video",
                    webpageUrl = info.webpageUrl,
                    originalUrl = info.originalUrl,
                    extractorKey = info.extractorKey,
                    entries = listOf(
                        PlaylistEntry(
                            type = info.type,
                            id = info.id,
                            url = info.webpageUrl ?: info.originalUrl,
                            title = info.title,
                            duration = info.duration,
                            uploader = info.uploader,
                            channel = info.channel,
                        )
                    )
                )
            }
        }

    override suspend fun resolveAudioStream(
        videoId: String,
        forceRefresh: Boolean,
    ): Result<AudioStream> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = streamCacheMutex.withLock {
                streamCache[videoId]
                    ?.takeIf { now - it.timestamp < STREAM_CACHE_TTL_MILLIS }
            }
            if (cached != null) {
                logPreviewFormat(cached)
                return@withContext Result.success(cached)
            }
        }

        withRateLimitGuard {
            runCatchingCancellable {
                val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId").apply {
                    addOption(
                        "-f",
                        "bestaudio[ext=m4a][protocol^=http][protocol!*=dash]/" +
                                "bestaudio[protocol^=http][protocol!*=dash]/bestaudio",
                    )
                    addOption("--print", "YT_DEBUG_FORMAT\t%(protocol)s\t%(ext)s")
                    addOption("-g")
                    addOption("--no-playlist")
                    addOption("-R", "1")
                    addOption("--socket-timeout", "5")
                }

                val response = engine.execute(request)
                val url = response.out
                    .lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("http") }
                    ?: error("yt-dlp did not return an audio stream URL")

                val formatFields = response.out.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith(PREVIEW_FORMAT_PREFIX) }
                    ?.removePrefix(PREVIEW_FORMAT_PREFIX)
                    ?.split('\t')
                val protocol = formatFields?.getOrNull(0)?.takeIf { it.isNotBlank() }
                val extension = formatFields?.getOrNull(1)?.takeIf { it.isNotBlank() }

                val stream = AudioStream(
                    videoId = videoId,
                    url = url,
                    timestamp = System.currentTimeMillis(),
                    protocol = protocol,
                    extension = extension,
                )
                logPreviewFormat(stream)

                streamCacheMutex.withLock {
                    val cutoff = System.currentTimeMillis() - STREAM_CACHE_TTL_MILLIS
                    streamCache.entries.removeAll { it.value.timestamp < cutoff }
                    streamCache[videoId] = stream
                }

                stream
            }
        }
    }

    /**
     * Fails fast while the cooldown is active, then converts a fresh rate-limit failure
     * into [DownloadRateLimitedException] so callers only have to handle one error type.
     */
    private inline fun <T> withRateLimitGuard(block: () -> Result<T>): Result<T> {
        if (System.currentTimeMillis() < rateLimitCooldownUntil) {
            return Result.failure(DownloadRateLimitedException(RATE_LIMIT_MESSAGE))
        }

        return block().mapRateLimitFailure()
    }

    private fun <T> Result<T>.mapRateLimitFailure(): Result<T> {
        val throwable = exceptionOrNull() ?: return this
        if (!DownloadErrorClassifier.isRateLimited(throwable.message)) return this

        beginRateLimitCooldown()
        return Result.failure(
            DownloadRateLimitedException(
                message = throwable.message ?: RATE_LIMIT_MESSAGE,
                cause = throwable,
            )
        )
    }

    private data class CachedSearch(
        val timestamp: Long,
        val items: List<YoutubeSearchItem>,
    )

    private fun logPreviewFormat(stream: AudioStream) {
        Log.d(
            TAG,
            "Preview format selected: videoId=${stream.videoId} protocol=${stream.protocol ?: "unknown"} ext=${stream.extension ?: "unknown"}",
        )
    }

    private companion object {
        const val TAG = "YT_DEBUG"
        const val PREVIEW_FORMAT_PREFIX = "YT_DEBUG_FORMAT\t"
        const val SEARCH_CACHE_TTL_MILLIS = 5L * 60L * 1000L
        const val STREAM_CACHE_TTL_MILLIS = 10L * 60L * 1000L
        const val RATE_LIMIT_COOLDOWN_MILLIS = 60L * 1000L
        const val RATE_LIMIT_MESSAGE = "Too many requests"
    }
}

private fun StreamInfoItem.toYoutubeSearchItem(): YoutubeSearchItem? {
    val itemUrl = url?.takeIf { it.isNotBlank() } ?: return null
    val videoId = youtubeVideoId(itemUrl) ?: return null
    val canonicalUrl = "https://www.youtube.com/watch?v=$videoId"

    return YoutubeSearchItem(
        id = videoId,
        title = name?.takeIf { it.isNotBlank() } ?: videoId,
        uploader = uploaderName?.takeIf { it.isNotBlank() },
        durationSeconds = duration
            .takeIf { it > 0L && it <= Int.MAX_VALUE.toLong() }
            ?.toInt(),
        thumbnailUrl = thumbnails.firstOrNull()?.url?.takeIf { it.isNotBlank() }
            ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
        url = itemUrl.ifBlank { canonicalUrl },
    )
}

private fun youtubeVideoId(url: String): String? {
    val uri = Uri.parse(url)
    uri.getQueryParameter("v")?.takeIf { it.isNotBlank() }?.let { return it }

    val segments = uri.pathSegments
    val route = segments.indexOfFirst { it in setOf("shorts", "embed", "live", "v") }
    if (route >= 0) return segments.getOrNull(route + 1)?.takeIf { it.isNotBlank() }

    return if (uri.host.equals("youtu.be", ignoreCase = true)) {
        segments.firstOrNull()?.takeIf { it.isNotBlank() }
    } else {
        null
    }
}

/** Like [runCatching], but never swallows coroutine cancellation. */
private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }
