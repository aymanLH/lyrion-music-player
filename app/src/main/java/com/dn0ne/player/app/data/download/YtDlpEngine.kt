package com.dn0ne.player.app.data.download

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the youtubedl-android native engine lifecycle.
 *
 * Initialization starts as soon as this singleton is created (at app start) and runs on
 * [Dispatchers.IO]. Every operation awaits [awaitReady] first, so callers never need to
 * care whether the python/ffmpeg binaries have finished unpacking.
 */
interface YtDlpEngine {
    val isReady: StateFlow<Boolean>

    suspend fun awaitReady()

    suspend fun execute(
        request: YoutubeDLRequest,
        processId: String? = null,
        onProgress: ((progress: Float, etaInSeconds: Long, line: String) -> Unit)? = null,
    ): YoutubeDLResponse

    /** Returns true when a process with [processId] was running and has been killed. */
    fun cancel(processId: String): Boolean

    suspend fun version(): String?

    suspend fun update(channel: YoutubeDL.UpdateChannel): YoutubeDL.UpdateStatus?
}

class YtDlpEngineImpl(
    context: Context,
) : YtDlpEngine {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ready = CompletableDeferred<Unit>()

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    init {
        scope.launch {
            initialize()
        }
    }

    private suspend fun initialize() {
        try {
            YoutubeDL.init(appContext)
            FFmpeg.init(appContext)
            _isReady.value = true
            ready.complete(Unit)
            Log.i(TAG, "yt-dlp and ffmpeg initialized (version ${YoutubeDL.getInstance().version(appContext)})")
        } catch (throwable: Throwable) {
            Log.e(TAG, "Failed to initialize yt-dlp/ffmpeg", throwable)
            ready.completeExceptionally(throwable)
        }
    }

    override suspend fun awaitReady() {
        ready.await()
    }

    override suspend fun execute(
        request: YoutubeDLRequest,
        processId: String?,
        onProgress: ((Float, Long, String) -> Unit)?,
    ): YoutubeDLResponse {
        awaitReady()
        return withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().execute(request, processId, onProgress)
        }
    }

    override fun cancel(processId: String): Boolean =
        YoutubeDL.destroyProcessById(processId)

    override suspend fun version(): String? {
        awaitReady()
        return withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().version(appContext)
        }
    }

    override suspend fun update(channel: YoutubeDL.UpdateChannel): YoutubeDL.UpdateStatus? {
        awaitReady()
        return withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().updateYoutubeDL(appContext, channel)
        }
    }

    private companion object {
        const val TAG = "YtDlpEngine"
    }
}
