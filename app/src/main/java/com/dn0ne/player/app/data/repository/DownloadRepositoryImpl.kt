package com.dn0ne.player.app.data.repository

import android.content.Context
import android.util.Log
import com.dn0ne.player.DownloadService
import com.dn0ne.player.R
import com.dn0ne.player.app.data.download.DownloadErrorClassifier
import com.dn0ne.player.app.data.download.DownloadErrorKind
import com.dn0ne.player.app.data.download.DownloadRateLimitedException
import com.dn0ne.player.app.data.download.DownloadRequestFactory
import com.dn0ne.player.app.data.download.MediaStorePublisher
import com.dn0ne.player.app.data.download.PublishedAudio
import com.dn0ne.player.app.data.download.YtDlpEngine
import com.dn0ne.player.app.data.OpusTagEditor
import com.dn0ne.player.app.data.local.history.DownloadHistoryEntry
import com.dn0ne.player.app.data.local.history.DownloadHistoryStatus
import com.dn0ne.player.app.data.notification.DownloadNotifications
import com.dn0ne.player.app.data.remote.youtube.YoutubeProvider
import com.dn0ne.player.app.domain.download.DownloadAudioFormat
import com.dn0ne.player.app.domain.download.DownloadPreferences
import com.dn0ne.player.app.domain.download.DownloadState
import com.dn0ne.player.app.domain.download.DownloadTask
import com.dn0ne.player.app.domain.download.DownloadTaskState
import com.dn0ne.player.app.domain.download.DownloadType
import com.dn0ne.player.app.domain.download.DownloadViewState
import com.dn0ne.player.app.domain.download.RestartableAction
import com.dn0ne.player.app.domain.metadata.Metadata
import com.dn0ne.player.app.domain.youtube.PlaylistResult
import com.dn0ne.player.app.domain.youtube.VideoInfo
import com.dn0ne.player.app.domain.youtube.YoutubeSearchItem
import com.dn0ne.player.app.presentation.components.snackbar.SnackbarController
import com.dn0ne.player.app.presentation.components.snackbar.SnackbarEvent
import com.dn0ne.player.core.data.Settings
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Port of Seal's `DownloaderV2Impl`.
 *
 * Differences from Seal:
 *  - backed by a `StateFlow` instead of Compose's `SnapshotStateMap` so the queue lives in
 *    the data layer,
 *  - persisted with SharedPreferences + kotlinx.serialization instead of MMKV,
 *  - no custom commands, aria2c, cookies, subtitles or video options.
 *
 * Contract:
 *  - at most [MAX_CONCURRENCY] tasks fetch info or download at the same time,
 *  - `Idle -> FetchingInfo -> ReadyWithInfo -> Running -> Completed | Canceled | Error`,
 *  - restored unfinished tasks become `Canceled` and are never auto-started,
 *  - a cancel must never be overwritten by a late success/error callback.
 */
class DownloadRepositoryImpl(
    context: Context,
    private val engine: YtDlpEngine,
    private val youtubeProvider: YoutubeProvider,
    private val mediaStorePublisher: MediaStorePublisher,
    private val notifications: DownloadNotifications,
    private val historyRepository: DownloadHistoryRepository,
    private val settings: Settings,
) : DownloadRepository {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduleMutex = Mutex()
    private val dispatchTrigger = Channel<Unit>(Channel.CONFLATED)

    private val _tasks = MutableStateFlow<Map<DownloadTask, DownloadTaskState>>(emptyMap())
    override val tasks: StateFlow<Map<DownloadTask, DownloadTaskState>> = _tasks.asStateFlow()

    private val queuePreferences =
        appContext.getSharedPreferences(QUEUE_PREFERENCES, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        allowStructuredMapKeys = true
        encodeDefaults = true
    }

    init {
        scope.launch {
            // Read before writing: restore first, then start persisting changes.
            restoreFromBackup()

            _tasks
                .map { map ->
                    // Completed tasks are not resumable, so they are not persisted.
                    map.filterValues { it.downloadState !is DownloadState.Completed }
                }
                .distinctUntilChanged()
                .collectLatest { map ->
                    // Progress ticks update the map often; coalesce them into one write.
                    delay(PERSIST_DEBOUNCE_MILLIS)
                    persist(map)
                }
        }

        scope.launch {
            for (unit in dispatchTrigger) {
                runCatching { schedule() }
                    .onFailure { Log.e(TAG, "Failed to schedule downloads", it) }
            }
        }

        // Bring up the dataSync foreground service whenever work becomes active.
        // Restored tasks are Canceled, so nothing starts at app launch.
        scope.launch {
            _tasks
                .map { map ->
                    map.values.any { state ->
                        state.downloadState is DownloadState.Running ||
                                state.downloadState is DownloadState.FetchingInfo
                    }
                }
                .distinctUntilChanged()
                .collect { isActive ->
                    if (isActive) {
                        runCatching { DownloadService.start(appContext) }
                            .onFailure { Log.e(TAG, "Failed to start download service", it) }
                    }
                }
        }
    }

    // region Queue API

    override fun enqueue(task: DownloadTask) {
        enqueue(
            task = task,
            state = DownloadTaskState(
                downloadState = DownloadState.Idle,
                viewState = DownloadViewState(url = task.url, title = task.url),
            )
        )
    }

    override fun enqueue(task: DownloadTask, state: DownloadTaskState) {
        _tasks.update { map ->
            val existing = map[task]?.downloadState
            val canReplace = existing == null ||
                    existing is DownloadState.Completed ||
                    existing is DownloadState.Canceled ||
                    existing is DownloadState.Error

            if (canReplace) map + (task to state) else map
        }
        triggerDispatch()
    }

    override fun enqueue(taskWithState: DownloadTaskFactory.TaskWithState) {
        enqueue(taskWithState.task, taskWithState.state)
    }

    override fun enqueueAll(taskWithStates: List<DownloadTaskFactory.TaskWithState>) {
        taskWithStates.forEach { enqueue(it) }
    }

    override fun enqueueSearchItem(item: YoutubeSearchItem) {
        enqueue(DownloadTaskFactory.createForSearchItem(item, currentPreferences()))
    }

    override fun enqueueUrl(url: String) {
        enqueue(DownloadTaskFactory.createForUrl(url, currentPreferences()))
    }

    override fun enqueuePlaylist(
        playlistUrl: String,
        selectedIndices: List<Int>,
        playlistResult: PlaylistResult,
    ): List<DownloadTask> {
        val taskWithStates = DownloadTaskFactory.createWithPlaylistResult(
            playlistUrl = playlistUrl,
            indexList = selectedIndices,
            playlistResult = playlistResult,
            preferences = currentPreferences(),
        )
        enqueueAll(taskWithStates)
        return taskWithStates.map { it.task }
    }

    override fun cancel(task: DownloadTask): Boolean = task.cancelImpl()

    override fun cancel(taskId: String): Boolean =
        _tasks.value.keys.firstOrNull { it.id == taskId }?.let { cancel(it) } ?: false

    override fun restart(task: DownloadTask) {
        task.restartImpl()
    }

    override fun remove(task: DownloadTask): Boolean {
        // Note: the caller is responsible for stopping the task before removing it.
        cancel(task)
        val removed = _tasks.value.containsKey(task)
        if (removed) {
            _tasks.update { it - task }
        }
        triggerDispatch()
        return removed
    }

    override fun countRunning(): Int = _tasks.value.values.count { state ->
        state.downloadState is DownloadState.Running ||
                state.downloadState is DownloadState.FetchingInfo
    }

    override fun hasRunningTasks(): Boolean = countRunning() > 0

    override fun currentPreferences(): DownloadPreferences = DownloadPreferences(
        audioFormat = settings.downloadFormat.value,
        audioQuality = settings.downloadQuality.value,
        embedMetadata = settings.embedMetadata.value,
        embedThumbnail = settings.embedThumbnail.value,
        outputTemplate = DownloadRequestFactory.DEFAULT_OUTPUT_TEMPLATE,
    )

    // endregion

    // region Scheduling

    /** Fills the free slots, prioritizing ready downloads over info fetching. */
    private suspend fun schedule() {
        scheduleMutex.withLock {
            while (countRunning() < MAX_CONCURRENCY) {
                val next = _tasks.value.entries
                    .filter { (_, state) ->
                        state.downloadState == DownloadState.Idle ||
                                state.downloadState == DownloadState.ReadyWithInfo
                    }
                    .minWithOrNull(
                        compareBy<Map.Entry<DownloadTask, DownloadTaskState>> {
                            it.value.downloadState
                        }.thenBy { it.key.timeCreated }
                    )
                    ?: return@withLock

                when (next.value.downloadState) {
                    DownloadState.Idle -> next.key.fetchInfo()
                    DownloadState.ReadyWithInfo -> next.key.download()
                    else -> return@withLock
                }
            }
        }
    }

    private fun triggerDispatch() {
        dispatchTrigger.trySend(Unit)
    }

    private fun DownloadTask.fetchInfo() {
        check(_tasks.value[this]?.downloadState == DownloadState.Idle)
        val task = this
        val playlistIndex = (type as? DownloadType.Playlist)?.index

        val job = scope.launch(start = CoroutineStart.LAZY) {
            youtubeProvider.fetchVideoInfo(
                url = task.url,
                playlistIndex = playlistIndex,
                processId = task.id,
            )
                .onSuccess { info ->
                    if (task.isCanceledOrGone()) return@onSuccess

                    task.updateState { state ->
                        state.copy(
                            downloadState = DownloadState.ReadyWithInfo,
                            videoInfo = info,
                            viewState = DownloadViewState.fromVideoInfo(info, task.url).copy(
                                thumbnailUrl = info.thumbnail ?: state.viewState.thumbnailUrl,
                            ),
                        )
                    }
                    triggerDispatch()
                }
                .onFailure { throwable ->
                    if (throwable is YoutubeDL.CanceledException ||
                        throwable is CancellationException ||
                        task.isCanceledOrGone()
                    ) {
                        return@onFailure
                    }

                    task.fail(
                        throwable = throwable,
                        action = RestartableAction.FetchInfo,
                    )
                }
        }

        updateState { state ->
            state.copy(
                downloadState = DownloadState.FetchingInfo(
                    job = job,
                    taskId = task.id,
                )
            )
        }
        job.start()
    }

    private fun DownloadTask.download() {
        val currentState = _tasks.value[this] ?: return
        check(currentState.downloadState == DownloadState.ReadyWithInfo)
        val info = currentState.videoInfo ?: run {
            fail(
                message = "Missing video info",
                action = RestartableAction.FetchInfo,
            )
            return
        }

        val task = this
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val outputDir = downloadOutputDir()
            outputDir.mkdirs()
            val startedAt = System.currentTimeMillis()

            try {
                val request = DownloadRequestFactory.build(
                    videoInfo = info,
                    taskUrl = task.url,
                    type = task.type,
                    preferences = task.preferences,
                    outputDir = outputDir,
                )

                val response = engine.execute(
                    request = request,
                    processId = task.id,
                    onProgress = { progressPercent, _, text ->
                        val progress = progressPercent / 100f
                        task.updateState { state ->
                            val running = state.downloadState as? DownloadState.Running
                                ?: return@updateState state
                            state.copy(
                                downloadState = running.copy(
                                    progress = progress,
                                    progressText = "",
                                )
                            )
                        }
                        notifications.notifyProgress(
                            taskId = task.id,
                            title = task.currentTitle(),
                            progress = progress,
                            format = task.preferences.audioFormat.name.takeIf { it != "ORIGINAL" },
                            duration = info.duration?.toInt() ?: 0,
                        )
                    },
                )

                if (task.isCanceledOrGone()) return@launch

                val file = resolveDownloadedFile(
                    stdout = response.out,
                    outputDir = outputDir,
                    startedAt = startedAt,
                    videoInfo = info,
                )

                if (file == null) {
                    task.fail(
                        message = "Download finished but the output file was not found",
                        action = RestartableAction.Download,
                    )
                    return@launch
                }

                // yt-dlp/ffmpeg may report success for --embed-thumbnail while leaving
                // OGG Opus without a METADATA_BLOCK_PICTURE. Fill that gap before publish.
                if (task.preferences.embedThumbnail &&
                    task.preferences.audioFormat == DownloadAudioFormat.OPUS &&
                    file.extension.equals("opus", ignoreCase = true)
                ) {
                    ensureOpusThumbnail(file, info)
                }

                // Publish into the shared library before reporting success.
                val published = mediaStorePublisher.publish(file, info.title)
                if (task.isCanceledOrGone()) return@launch

                val result = published.getOrElse { throwable ->
                    task.fail(
                        throwable = throwable,
                        action = RestartableAction.Download,
                    )
                    return@launch
                }

                task.updateState { state ->
                    state.copy(
                        viewState = state.viewState.copy(
                            format = result.displayName.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.uppercase(),
                        ),
                        downloadState = DownloadState.Completed(
                            // The staging file is deleted once it is published.
                            filePath = null,
                            fileSize = result.size,
                            mediaStoreUri = result.uri,
                        )
                    )
                }
                notifications.notifyFinished(
                    taskId = task.id,
                    title = task.currentTitle(),
                    mediaStoreUri = result.uri,
                    format = result.displayName.substringAfterLast('.', "").uppercase(),
                    duration = info.duration?.toInt() ?: 0,
                )
                task.recordHistoryCompleted(info = info, published = result)
                triggerDispatch()
            } catch (throwable: Throwable) {
                if (throwable is YoutubeDL.CanceledException ||
                    throwable is CancellationException ||
                    task.isCanceledOrGone()
                ) {
                    return@launch
                }

                task.fail(
                    throwable = throwable,
                    action = RestartableAction.Download,
                )
            }
        }

        updateState { state ->
            state.copy(
                downloadState = DownloadState.Running(
                    job = job,
                    taskId = task.id,
                    progress = DownloadState.PROGRESS_INDETERMINATE,
                )
            )
        }
        job.start()
    }

    // endregion

    // region State mutators

    private inline fun DownloadTask.updateState(
        transform: (DownloadTaskState) -> DownloadTaskState,
    ) {
        _tasks.update { map ->
            val current = map[this] ?: return@update map
            map + (this to transform(current))
        }
    }

    private fun DownloadTask.isCanceledOrGone(): Boolean {
        val state = _tasks.value[this] ?: return true
        return state.downloadState is DownloadState.Canceled
    }

    private fun DownloadTask.currentTitle(): String =
        _tasks.value[this]?.viewState?.title?.takeIf {
            it.isNotBlank() && !it.contains("http", true) && !it.contains('/') && !it.contains('\\') && !it.contains("\n")
        } ?: appContext.getString(R.string.download_unknown_title)

    private fun DownloadTask.fail(
        throwable: Throwable,
        action: RestartableAction,
    ) {
        val error = DownloadErrorClassifier.classify(throwable)
        fail(
            message = error.message,
            action = action,
            report = error.report,
            kind = error.kind,
            // The provider already started the cooldown when it rejected the call itself.
            startCooldown = throwable !is DownloadRateLimitedException,
        )
    }

    private fun DownloadTask.fail(
        message: String,
        action: RestartableAction,
        report: String = message,
        kind: DownloadErrorKind = DownloadErrorKind.UNKNOWN,
        startCooldown: Boolean = true,
    ) {
        val rateLimited = kind == DownloadErrorKind.RATE_LIMITED
        if (rateLimited && startCooldown) {
            // A download can be the first call that learns about the rate limit; make sure
            // the next queued info fetch backs off too.
            youtubeProvider.beginRateLimitCooldown()
        }

        val userMessage = if (rateLimited) {
            appContext.getString(R.string.download_error_too_many_requests)
        } else {
            message.takeIf { it.length <= 110 && !it.contains("http", true) && !it.contains('/') && !it.contains('\\') && !it.contains("\n") }
                ?.removePrefix("ERROR: ")?.takeIf { it.isNotBlank() }
                ?: appContext.getString(R.string.download_error_generic)
        }

        updateState {
            it.copy(
                downloadState = DownloadState.Error(
                    message = userMessage,
                    action = action,
                    report = report,
                )
            )
        }
        notifications.notifyError(
            taskId = id,
            title = currentTitle(),
            message = userMessage,
            report = report,
        )
        SnackbarController.trySendEvent(
            SnackbarEvent(
                message = R.string.download_failed,
                rawMessage = if (rateLimited) null else userMessage,
            )
        )
        recordHistoryFailed(userMessage)
        triggerDispatch()
    }

    // region History

    private fun DownloadTask.recordHistoryCompleted(
        info: VideoInfo,
        published: PublishedAudio,
    ) {
        Log.d(TAG, "History COMPLETED save uri=${published.uri}")
        val viewState = _tasks.value[this]?.viewState ?: DownloadViewState()
        val entry = historyBase(info = info, viewState = viewState).copy(
            // `displayName` is the final library file name, so the extension is the real
            // format after yt-dlp's conversion step.
            format = published.displayName.substringAfterLast('.', "").lowercase(),
            fileSize = published.size,
            mediaStoreUri = published.uri,
            status = DownloadHistoryStatus.COMPLETED,
            errorMessage = null,
            timestamp = System.currentTimeMillis(),
        )

        recordHistory(entry) { historyRepository.recordCompleted(it) }
    }

    private fun DownloadTask.recordHistoryFailed(message: String) {
        val state = _tasks.value[this] ?: return
        val entry = historyBase(info = state.videoInfo, viewState = state.viewState).copy(
            format = preferences.historyFormat(),
            fileSize = 0L,
            mediaStoreUri = null,
            status = DownloadHistoryStatus.FAILED,
            errorMessage = message,
            timestamp = System.currentTimeMillis(),
        )

        recordHistory(entry) { historyRepository.recordFailed(it) }
    }

    /**
     * History writes are fire-and-forget: a slow or broken database must never delay or
     * fail a download that already succeeded.
     */
    private fun recordHistory(
        entry: DownloadHistoryEntry,
        write: suspend (DownloadHistoryEntry) -> Unit,
    ) {
        scope.launch {
            runCatching { write(entry) }
                .onFailure { Log.e(TAG, "Failed to write download history", it) }
        }
    }

    private fun DownloadTask.historyBase(
        info: VideoInfo?,
        viewState: DownloadViewState,
    ): DownloadHistoryEntry {
        val sourceUrl = info?.webpageUrl
            ?: info?.originalUrl
            ?: viewState.sourceUrl.takeIf { it.isNotBlank() }
            ?: url

        return DownloadHistoryEntry(
            videoId = info?.id?.takeIf { it.isNotBlank() } ?: viewState.videoId,
            title = info?.title?.takeIf { it.isNotBlank() }
                ?: viewState.title.takeIf { it.isNotBlank() }
                ?: appContext.getString(R.string.download_unknown_title),
            uploader = info?.uploader ?: info?.channel ?: viewState.uploader,
            thumbnailUrl = info?.thumbnail ?: viewState.thumbnailUrl,
            sourceUrl = sourceUrl,
            format = "",
            fileSize = 0L,
            mediaStoreUri = null,
            status = DownloadHistoryStatus.FAILED,
            errorMessage = null,
            timestamp = System.currentTimeMillis(),
        )
    }

    private fun DownloadPreferences.historyFormat(): String = when (audioFormat) {
        DownloadAudioFormat.OPUS -> "opus"
        DownloadAudioFormat.M4A -> "m4a"
        DownloadAudioFormat.MP3 -> "mp3"
        // The original container is only known once yt-dlp finishes.
        DownloadAudioFormat.ORIGINAL -> ""
    }

    // endregion

    private fun DownloadTask.cancelImpl(): Boolean {
        val state = _tasks.value[this] ?: return false

        return when (val preState = state.downloadState) {
            is DownloadState.Cancelable -> {
                val result = engine.cancel(preState.taskId)
                if (result) {
                    preState.job.cancel()
                    val progress = (preState as? DownloadState.Running)?.progress
                    updateState {
                        it.copy(
                            downloadState = DownloadState.Canceled(
                                action = preState.action,
                                progress = progress,
                            )
                        )
                    }
                    notifications.cancel(id)
                    triggerDispatch()
                }
                result
            }

            DownloadState.Idle -> {
                updateState {
                    it.copy(downloadState = DownloadState.Canceled(RestartableAction.FetchInfo))
                }
                triggerDispatch()
                true
            }

            DownloadState.ReadyWithInfo -> {
                updateState {
                    it.copy(downloadState = DownloadState.Canceled(RestartableAction.Download))
                }
                triggerDispatch()
                true
            }

            else -> false
        }
    }

    private fun DownloadTask.restartImpl() {
        val state = _tasks.value[this] ?: return

        when (val preState = state.downloadState) {
            is DownloadState.Restartable -> {
                val nextState = when (preState.action) {
                    RestartableAction.Download -> DownloadState.ReadyWithInfo
                    RestartableAction.FetchInfo -> DownloadState.Idle
                }
                updateState { it.copy(downloadState = nextState) }
                triggerDispatch()
            }

            else -> Unit
        }
    }

    // endregion

    // region Output + persistence

    private fun downloadOutputDir(): File =
        appContext.getExternalFilesDir("downloads") ?: File(appContext.filesDir, "downloads")

    /**
     * Resolves the final file produced by yt-dlp.
     *
     * `--print after_move:filepath` is authoritative; the remaining fallbacks only cover
     * older yt-dlp builds that ignore the print option or print to stderr.
     */
    private fun resolveDownloadedFile(
        stdout: String,
        outputDir: File,
        startedAt: Long,
        videoInfo: VideoInfo,
    ): File? {
        stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("[") }
            .map { File(it) }
            .lastOrNull { it.isFile }
            ?.let { return it }

        listOfNotNull(
            videoInfo.filename,
            videoInfo.requestedDownloads?.firstOrNull()?.filename,
        )
            .map { File(it) }
            .firstOrNull { it.isFile }
            ?.let { return it }

        return outputDir.listFiles()
            ?.filter { it.isFile && it.lastModified() >= startedAt - FILE_LOOKUP_TOLERANCE_MILLIS }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun persist(map: Map<DownloadTask, DownloadTaskState>) {
        runCatching {
            val encoded = json.encodeToString<Map<DownloadTask, DownloadTaskState>>(map)
            queuePreferences.edit().putString(QUEUE_KEY, encoded).apply()
        }.onFailure { Log.e(TAG, "Failed to persist download queue", it) }
    }

    private fun restoreFromBackup() {
        val raw = queuePreferences.getString(QUEUE_KEY, null) ?: return
        val decoded = runCatching {
            json.decodeFromString<Map<DownloadTask, DownloadTaskState>>(raw)
        }.onFailure { Log.e(TAG, "Failed to read download queue", it) }
            .getOrNull()
            ?: return

        val restored = decoded
            .filterValues { it.downloadState !is DownloadState.Completed }
            .mapValues { (_, state) ->
                val restoredState = when (val preState = state.downloadState) {
                    is DownloadState.FetchingInfo,
                    DownloadState.Idle,
                        -> DownloadState.Canceled(RestartableAction.FetchInfo)

                    is DownloadState.Running -> DownloadState.Canceled(
                        action = RestartableAction.Download,
                        progress = preState.progress,
                    )

                    DownloadState.ReadyWithInfo -> DownloadState.Canceled(
                        action = RestartableAction.Download,
                    )

                    else -> preState
                }
                state.copy(downloadState = restoredState)
            }

        // Restored tasks are never auto-started; the user has to restart them.
        _tasks.value = restored
    }

    // endregion

    private fun ensureOpusThumbnail(file: File, info: VideoInfo) {
        runCatching {
            if (OpusTagEditor.readComments(file).any { it.first == "METADATA_BLOCK_PICTURE" }) {
                return
            }
            val thumbnail = info.thumbnail?.takeIf { it.startsWith("https://") } ?: return
            val connection = URL(thumbnail).openConnection().apply {
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            val bytes = connection.getInputStream().use { it.readNBytes(MAX_THUMBNAIL_BYTES + 1) }
            if (bytes.isEmpty() || bytes.size > MAX_THUMBNAIL_BYTES) return
            OpusTagEditor.update(file, Metadata(coverArtBytes = bytes))
        }.onFailure { error ->
            Log.w(TAG, "Could not add thumbnail to OPUS download", error)
        }
    }

    private companion object {
        const val MAX_THUMBNAIL_BYTES = 10 * 1024 * 1024
        const val TAG = "DownloadRepository"
        const val MAX_CONCURRENCY = 3
        const val QUEUE_PREFERENCES = "download-queue"
        const val QUEUE_KEY = "tasks"
        const val PERSIST_DEBOUNCE_MILLIS = 750L
        const val FILE_LOOKUP_TOLERANCE_MILLIS = 2_000L
    }
}
