package com.dn0ne.player.app.data.repository

import android.content.Context
import android.util.Log
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
import java.security.MessageDigest
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withContext
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
 *  - restored unfinished tasks resume from the last safe queue state,
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
    private val recoveryMutex = Mutex()
    private val queueStateLock = Any()
    private val recoveryReady = CompletableDeferred<Unit>()
    private val dispatchTrigger = Channel<Unit>(Channel.CONFLATED)
    private val pendingResumptions = mutableMapOf<DownloadTask, PendingResume>()

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
            try {
                restoreFromBackup()
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to restore download queue", exception)
            } finally {
                recoveryReady.complete(Unit)
            }

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
        synchronized(queueStateLock) {
            pendingResumptions.remove(task)
            _tasks.update { map ->
                val existing = map[task]?.downloadState
                val canReplace = existing == null ||
                        existing is DownloadState.Completed ||
                        existing is DownloadState.Canceled ||
                        existing is DownloadState.Error

                if (canReplace) map + (task to state) else map
            }
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
        enqueueSearchItem(item, serviceStartSucceeded = true)
    }

    override fun enqueueSearchItem(item: YoutubeSearchItem, serviceStartSucceeded: Boolean) {
        val taskWithState = DownloadTaskFactory.createForSearchItem(item, currentPreferences())
        enqueue(
            taskWithState.task,
            if (serviceStartSucceeded) taskWithState.state else taskWithState.state.asCanceledForServiceFailure(),
        )
    }

    override fun enqueueUrl(url: String) {
        enqueueUrl(url, serviceStartSucceeded = true)
    }

    override fun enqueueUrl(url: String, serviceStartSucceeded: Boolean) {
        val taskWithState = DownloadTaskFactory.createForUrl(url, currentPreferences())
        enqueue(
            taskWithState.task,
            if (serviceStartSucceeded) taskWithState.state else taskWithState.state.asCanceledForServiceFailure(),
        )
    }

    override fun enqueuePlaylist(
        playlistUrl: String,
        selectedIndices: List<Int>,
        playlistResult: PlaylistResult,
    ): List<DownloadTask> {
        return enqueuePlaylist(playlistUrl, selectedIndices, playlistResult, serviceStartSucceeded = true)
    }

    override fun enqueuePlaylist(
        playlistUrl: String,
        selectedIndices: List<Int>,
        playlistResult: PlaylistResult,
        serviceStartSucceeded: Boolean,
    ): List<DownloadTask> {
        val taskWithStates = DownloadTaskFactory.createWithPlaylistResult(
            playlistUrl = playlistUrl,
            indexList = selectedIndices,
            playlistResult = playlistResult,
            preferences = currentPreferences(),
        )
        if (serviceStartSucceeded) {
            enqueueAll(taskWithStates)
        } else {
            enqueueAll(taskWithStates.map {
                it.copy(state = it.state.asCanceledForServiceFailure())
            })
        }
        return taskWithStates.map { it.task }
    }

    override fun cancel(task: DownloadTask): Boolean = synchronized(queueStateLock) {
        pendingResumptions.remove(task)
        task.cancelImpl()
    }

    override fun cancel(taskId: String): Boolean =
        _tasks.value.keys.firstOrNull { it.id == taskId }?.let { cancel(it) } ?: false

    override fun restart(task: DownloadTask) {
        restart(task, serviceStartSucceeded = true)
    }

    override fun restart(task: DownloadTask, serviceStartSucceeded: Boolean) {
        if (!serviceStartSucceeded) {
            synchronized(queueStateLock) { pendingResumptions.remove(task) }
            return
        }
        synchronized(queueStateLock) { pendingResumptions.remove(task) }
        task.restartImpl()
    }

    override fun remove(task: DownloadTask): Boolean {
        // Note: the caller is responsible for stopping the task before removing it.
        cancel(task)
        val removed = synchronized(queueStateLock) {
            pendingResumptions.remove(task)
            val exists = _tasks.value.containsKey(task)
            if (exists) _tasks.update { it - task }
            exists
        }
        triggerDispatch()
        return removed
    }

    override fun countRunning(): Int = synchronized(queueStateLock) { countRunningLocked() }

    override fun hasRunningTasks(): Boolean = countRunning() > 0

    override fun hasUnfinishedTasks(): Boolean = synchronized(queueStateLock) {
        !recoveryReady.isCompleted || pendingResumptions.isNotEmpty() || hasUnfinishedTasksLocked()
    }

    override fun runIfQueueIdle(stopAction: () -> Unit): Boolean = synchronized(queueStateLock) {
        if (hasUnfinishedTasksLocked() || pendingResumptions.isNotEmpty()) {
            return@synchronized false
        }
        stopAction()
        true
    }

    override suspend fun resumeInterruptedTasks(startServiceFromUi: () -> Boolean): Int {
        recoveryReady.await()
        return recoveryMutex.withLock {
            val candidates = synchronized(queueStateLock) { pendingResumptions.toMap() }
            if (candidates.isEmpty()) return@withLock 0

            if (!startServiceFromUi()) {
                synchronized(queueStateLock) {
                    candidates.keys.forEach(pendingResumptions::remove)
                }
                Log.e(TAG, "Foreground service start failed; interrupted tasks remain canceled and resumable")
                return@withLock 0
            }

            val resumedCount = synchronized(queueStateLock) {
                val latest = _tasks.value.toMutableMap()
                var resumed = 0
                candidates.forEach { (task, pending) ->
                    if (pendingResumptions[task] != pending) return@forEach
                    val existing = latest[task] ?: run {
                        pendingResumptions.remove(task)
                        return@forEach
                    }
                    val placeholder = existing.downloadState as? DownloadState.Canceled
                    if (placeholder?.action != pending.action) {
                        pendingResumptions.remove(task)
                        return@forEach
                    }

                    latest[task] = existing.copy(
                        downloadState = pending.resumeState,
                        autoResumeCount = existing.autoResumeCount + 1,
                    )
                    pendingResumptions.remove(task)
                    resumed++
                }
                _tasks.value = latest
                resumed
            }

            if (resumedCount > 0) {
                if (!persistCurrentImmediately()) {
                    synchronized(queueStateLock) {
                        val latest = _tasks.value.toMutableMap()
                        candidates.forEach { (task, pending) ->
                            val state = latest[task] ?: return@forEach
                            if (state.autoResumeCount > 0 && state.downloadState == pending.resumeState) {
                                latest[task] = state.copy(
                                    downloadState = DownloadState.Canceled(pending.action),
                                    autoResumeCount = (state.autoResumeCount - 1).coerceAtLeast(0),
                                )
                            }
                        }
                        _tasks.value = latest
                    }
                    Log.e(TAG, "Could not persist auto-resume counters; interrupted tasks remain canceled")
                    persistCurrent()
                    return@withLock 0
                }
                Log.i(TAG, "Resuming $resumedCount interrupted download task(s) after foreground UI start")
                triggerDispatch()
            }
            resumedCount
        }
    }

    override fun currentPreferences(): DownloadPreferences = DownloadPreferences(
        audioFormat = settings.downloadFormat.value,
        audioQuality = settings.downloadQuality.value,
        embedMetadata = settings.embedMetadata.value,
        embedThumbnail = settings.embedThumbnail.value,
        outputTemplate = DownloadRequestFactory.DEFAULT_OUTPUT_TEMPLATE,
    )

    private fun countRunningLocked(): Int = _tasks.value.values.count { state ->
        state.downloadState is DownloadState.Running ||
                state.downloadState is DownloadState.FetchingInfo
    }

    private fun hasUnfinishedTasksLocked(): Boolean = _tasks.value.values.any { state ->
        state.downloadState !is DownloadState.Completed &&
                state.downloadState !is DownloadState.Canceled &&
                state.downloadState !is DownloadState.Error
    }

    private fun DownloadTaskState.asCanceledForServiceFailure(): DownloadTaskState {
        val (action, progress) = when (val state = downloadState) {
            is DownloadState.FetchingInfo -> RestartableAction.FetchInfo to null
            is DownloadState.Running -> RestartableAction.Download to state.progress
            DownloadState.ReadyWithInfo -> RestartableAction.Download to null
            is DownloadState.Canceled -> state.action to state.progress
            is DownloadState.Error -> state.action to null
            else -> RestartableAction.FetchInfo to null
        }
        return copy(downloadState = DownloadState.Canceled(action, progress))
    }

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
            val startedAt = System.currentTimeMillis()

            try {
                val outputDir = prepareTaskOutputDirectory(task)
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
                val published = mediaStorePublisher.publish(file, info.title, task.id)
                if (task.isCanceledOrGone()) return@launch

                val result = published.getOrElse { throwable ->
                    task.fail(
                        throwable = throwable,
                        action = RestartableAction.Download,
                    )
                    return@launch
                }
                if (outputDir.exists() && !outputDir.deleteRecursively()) {
                    Log.w(TAG, "Could not remove completed staging directory $outputDir")
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
        synchronized(queueStateLock) {
            _tasks.update { map ->
                val current = map[this] ?: return@update map
                map + (this to transform(current))
            }
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

    private fun prepareTaskOutputDirectory(task: DownloadTask): File {
        val root = downloadOutputDir()
        if (!root.exists() && !root.mkdirs()) error("Could not create download staging directory")

        // Remove legacy flat staging files from earlier releases. Current downloads each
        // use a task-specific subdirectory, so concurrent tasks cannot erase one another.
        root.listFiles()
            ?.filter(File::isFile)
            ?.forEach { legacyFile ->
                if (!legacyFile.delete()) Log.w(TAG, "Could not remove legacy staging file $legacyFile")
            }

        val key = MessageDigest.getInstance("SHA-256")
            .digest(task.id.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        val outputDir = File(root, "task_$key")
        if (outputDir.exists() && !outputDir.deleteRecursively()) {
            error("Could not clear interrupted staging directory")
        }
        if (!outputDir.mkdirs() && !outputDir.isDirectory) {
            error("Could not create task staging directory")
        }
        return outputDir
    }

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

    private fun persist(@Suppress("UNUSED_PARAMETER") map: Map<DownloadTask, DownloadTaskState>) {
        persistCurrent()
    }

    private fun persistCurrent() {
        runCatching {
            synchronized(queueStateLock) {
                val encoded = json.encodeToString<Map<DownloadTask, DownloadTaskState>>(_tasks.value)
                queuePreferences.edit().putString(QUEUE_KEY, encoded).apply()
            }
        }.onFailure { Log.e(TAG, "Failed to persist download queue", it) }
    }

    private suspend fun persistCurrentImmediately(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            synchronized(queueStateLock) {
                val encoded = json.encodeToString<Map<DownloadTask, DownloadTaskState>>(_tasks.value)
                queuePreferences.edit().putString(QUEUE_KEY, encoded).commit()
            }
        }.onFailure { Log.e(TAG, "Failed to synchronously persist recovered queue", it) }
            .getOrDefault(false)
    }

    private fun restoreFromBackup() {
        val raw = queuePreferences.getString(QUEUE_KEY, null) ?: return
        val decoded = runCatching {
            json.decodeFromString<Map<DownloadTask, DownloadTaskState>>(raw)
        }.onFailure { Log.e(TAG, "Failed to read download queue", it) }
            .getOrNull()
            ?: return

        val recoveredCandidates = mutableMapOf<DownloadTask, PendingResume>()
        val restored = decoded
            .filterValues { it.downloadState !is DownloadState.Completed }
            .mapValues { (task, state) ->
                val preState = state.downloadState
                val recovery = when (preState) {
                    is DownloadState.FetchingInfo -> RecoveryCandidate(
                        resumeState = DownloadState.Idle,
                        action = RestartableAction.FetchInfo,
                    )

                    is DownloadState.Running -> if (state.videoInfo != null) {
                        RecoveryCandidate(
                            resumeState = DownloadState.ReadyWithInfo,
                            action = RestartableAction.Download,
                        )
                    } else {
                        RecoveryCandidate(
                            resumeState = DownloadState.Idle,
                            action = RestartableAction.FetchInfo,
                        )
                    }

                    DownloadState.ReadyWithInfo -> if (state.videoInfo != null) {
                        RecoveryCandidate(
                            resumeState = DownloadState.ReadyWithInfo,
                            action = RestartableAction.Download,
                        )
                    } else {
                        RecoveryCandidate(
                            resumeState = DownloadState.Idle,
                            action = RestartableAction.FetchInfo,
                        )
                    }

                    else -> null
                }

                if (recovery != null && state.autoResumeCount < MAX_AUTO_RESUMES) {
                    recoveredCandidates[task] = PendingResume(recovery.resumeState, recovery.action)
                    state.copy(
                        downloadState = DownloadState.Canceled(recovery.action),
                    )
                } else if (recovery != null) {
                    Log.w(
                        "YT_DEBUG",
                        "Auto-resume limit reached for task=${task.id}; leaving it canceled and manually resumable",
                    )
                    state.copy(downloadState = DownloadState.Canceled(recovery.action))
                } else {
                    when (preState) {
                        // Queued-but-not-started work is not automatically resumed.
                        DownloadState.Idle -> state.copy(
                            downloadState = DownloadState.Canceled(RestartableAction.FetchInfo),
                        )

                        // Preserve user cancellation and terminal errors exactly.
                        else -> state
                    }
                }
            }

        synchronized(queueStateLock) {
            // An enqueue from the foreground UI can race the asynchronous backup read;
            // current in-memory changes always win over the older snapshot.
            pendingResumptions.putAll(recoveredCandidates.filterKeys { it !in _tasks.value })
            _tasks.value = restored + _tasks.value
        }
        if (recoveredCandidates.isNotEmpty()) {
            Log.i("YT_DEBUG", "Found ${recoveredCandidates.size} interrupted task(s), waiting for foreground UI to resume them")
        }
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
        const val MAX_AUTO_RESUMES = 2
        const val MAX_THUMBNAIL_BYTES = 10 * 1024 * 1024
        const val TAG = "DownloadRepository"
        const val MAX_CONCURRENCY = 3
        const val QUEUE_PREFERENCES = "download-queue"
        const val QUEUE_KEY = "tasks"
        const val PERSIST_DEBOUNCE_MILLIS = 750L
        const val FILE_LOOKUP_TOLERANCE_MILLIS = 2_000L
    }

    private data class RecoveryCandidate(
        val resumeState: DownloadState,
        val action: RestartableAction,
    )

    private data class PendingResume(
        val resumeState: DownloadState,
        val action: RestartableAction,
    )
}
