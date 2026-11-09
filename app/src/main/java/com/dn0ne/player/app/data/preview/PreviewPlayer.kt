package com.dn0ne.player.app.data.preview

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.dn0ne.player.app.data.remote.youtube.YoutubeProvider
import com.dn0ne.player.app.domain.youtube.YoutubeSearchItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PreviewState(
    val videoId: String? = null,
    val title: String? = null,
    val uploader: String? = null,
    val thumbnailUrl: String? = null,
    val isResolving: Boolean = false,
    val isBuffering: Boolean = false,
    val isPlaying: Boolean = false,
    val position: Long = 0L,
    val duration: Long = 0L,
    val error: String? = null,
)

/**
 * One independent ExoPlayer for YouTube previews.
 *
 * It deliberately does not touch `PlaybackService` or its `MediaSession`: the preview has
 * no queue and no media notification. Only one preview exists at a time, and the main
 * player and the preview pause each other.
 *
 * Stream URLs are resolved lazily on the first tap and cached by [YoutubeProvider] for ten
 * minutes. Nothing is prefetched.
 */
class PreviewPlayer(
    context: Context,
    private val youtubeProvider: YoutubeProvider,
    private val playbackLink: PlaybackLink,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    private var currentItem: YoutubeSearchItem? = null
    private var resolveJob: Job? = null
    private var retriedAfter403 = false

    private val exoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setLooper(Looper.getMainLooper())
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )

            addListener(
                object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _state.update { it.copy(isPlaying = isPlaying) }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        _state.update {
                            it.copy(
                                isBuffering = playbackState == Player.STATE_BUFFERING,
                                duration = duration.coerceAtLeast(0L),
                            )
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        handlePlaybackError(error)
                    }
                }
            )
        }

    /** Plays [item], or pauses/resumes it when it is already the current preview. */
    fun toggle(item: YoutubeSearchItem) {
        if (currentItem?.id == item.id) {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
            } else {
                exoPlayer.play()
            }
            return
        }

        startPreview(item)
    }

    /** Pauses the preview; used when the main player starts. */
    fun pause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        }
    }

    fun stop() {
        resolveJob?.cancel()
        resolveJob = null
        currentItem = null
        retriedAfter403 = false
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _state.value = PreviewState()
    }

    private fun startPreview(item: YoutubeSearchItem) {
        // Starting a preview always pauses the main player.
        playbackLink.pauseMain()

        resolveJob?.cancel()
        currentItem = item
        retriedAfter403 = false

        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        _state.value = PreviewState(
            videoId = item.id,
            title = item.title,
            uploader = item.uploader,
            thumbnailUrl = item.thumbnailUrl,
            isResolving = true,
        )

        resolveJob = scope.launch {
            youtubeProvider.resolveAudioStream(item.id)
                .onSuccess { stream -> playResolvedStream(item, stream.url) }
                .onFailure { throwable -> fail(throwable.message ?: throwable::class.java.simpleName) }
        }
    }

    private fun playResolvedStream(item: YoutubeSearchItem, url: String) {
        if (currentItem?.id != item.id) return

        _state.update {
            it.copy(
                isResolving = false,
                isBuffering = true,
                error = null,
            )
        }

        try {
            exoPlayer.setMediaItem(MediaItem.fromUri(url))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } catch (exception: RuntimeException) {
            fail(exception.message ?: exception::class.java.simpleName)
        }
    }

    private fun handlePlaybackError(error: PlaybackException) {
        val item = currentItem ?: return

        if (error.isForbidden() && !retriedAfter403) {
            retriedAfter403 = true
            _state.update {
                it.copy(
                    isResolving = true,
                    isBuffering = false,
                    isPlaying = false,
                    error = null,
                )
            }

            resolveJob?.cancel()
            resolveJob = scope.launch {
                // The cached URL was rejected (403); force a fresh resolution once.
                youtubeProvider.resolveAudioStream(item.id, forceRefresh = true)
                    .onSuccess { stream -> playResolvedStream(item, stream.url) }
                    .onFailure { throwable ->
                        fail(throwable.message ?: throwable::class.java.simpleName)
                    }
            }
            return
        }

        fail(error.message ?: error::class.java.simpleName)
    }

    private fun fail(message: String) {
        _state.update {
            it.copy(
                isResolving = false,
                isBuffering = false,
                isPlaying = false,
                error = message,
            )
        }
    }

    private fun PlaybackException.isForbidden(): Boolean {
        if (errorCode != PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) return false
        val text = buildString {
            append(message.orEmpty())
            cause?.message?.let { append(' ').append(it) }
        }
        return text.contains("403") || text.contains("Forbidden", ignoreCase = true)
    }
}
