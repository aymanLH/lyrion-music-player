package com.dn0ne.player.app.presentation.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dn0ne.player.app.data.local.history.DownloadHistoryEntry
import com.dn0ne.player.app.data.preview.PreviewPlayer
import com.dn0ne.player.app.data.preview.PreviewState
import com.dn0ne.player.app.data.remote.youtube.YoutubeProvider
import com.dn0ne.player.app.data.remote.youtube.toSearchItem
import com.dn0ne.player.app.data.repository.DownloadHistoryRepository
import com.dn0ne.player.app.data.repository.HistoryFileAvailability
import com.dn0ne.player.app.data.repository.DownloadRepository
import com.dn0ne.player.app.domain.download.DownloadState
import com.dn0ne.player.app.domain.download.DownloadTask
import com.dn0ne.player.app.domain.download.DownloadTaskState
import com.dn0ne.player.app.domain.youtube.PlaylistResult
import com.dn0ne.player.app.domain.youtube.VideoInfo
import com.dn0ne.player.app.domain.youtube.YoutubeInfo
import com.dn0ne.player.app.domain.youtube.YoutubeSearchItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadsSearchState(
    val query: String = "",
    val hasSearched: Boolean = false,
    val results: List<YoutubeSearchItem> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
    val playlistUrl: String? = null,
    val playlist: PlaylistResult? = null,
    val selectedPlaylistIndices: Set<Int> = emptySet(),
)

class DownloadsViewModel(
    private val youtubeProvider: YoutubeProvider,
    private val downloadRepository: DownloadRepository,
    private val historyRepository: DownloadHistoryRepository,
    val previewPlayer: PreviewPlayer,
) : ViewModel() {
    private val _search = MutableStateFlow(DownloadsSearchState())
    val search: StateFlow<DownloadsSearchState> = _search.asStateFlow()

    val tasks = downloadRepository.tasks
    val history = historyRepository.entries.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val preview: StateFlow<PreviewState> = previewPlayer.state
    private var searchJob: Job? = null

    fun updateQuery(query: String) = _search.update { it.copy(query = query) }

    fun search() {
        val query = _search.value.query.trim()
        if (query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _search.update { it.copy(hasSearched = true, isSearching = true, error = null, results = emptyList(), playlist = null) }
            val isYouTubeUrl = query.startsWith("http", ignoreCase = true) &&
                (query.contains("youtube.com", true) || query.contains("youtu.be", true))
            if (isYouTubeUrl) {
                youtubeProvider.fetchPlaylistOrVideoInfo(query)
                    .onSuccess { info ->
                        when (info) {
                            is PlaylistResult -> {
                                val count = info.entries.orEmpty().size
                                if (count > 1) {
                                    _search.update {
                                        it.copy(
                                            playlistUrl = query,
                                            playlist = info,
                                            selectedPlaylistIndices = (1..count).toSet(),
                                            isSearching = false,
                                        )
                                    }
                                } else {
                                    _search.update { it.copy(results = info.entries.orEmpty().mapNotNull { entry -> entry.toSearchItem() }, isSearching = false) }
                                }
                            }
                            is VideoInfo -> _search.update {
                                it.copy(results = listOf(info.toSearchItem(query)), isSearching = false)
                            }
                        }
                    }
                    .onFailure { error -> _search.update { it.copy(isSearching = false, error = error.message ?: "Could not load this YouTube link") } }
            } else {
                youtubeProvider.search(query)
                    .onSuccess { items -> _search.update { it.copy(results = items, isSearching = false) } }
                    .onFailure { error -> _search.update { it.copy(isSearching = false, error = error.message ?: "Search failed") } }
            }
        }
    }

    fun togglePlaylistIndex(index: Int) = _search.update { state ->
        val selected = state.selectedPlaylistIndices
        state.copy(selectedPlaylistIndices = if (index in selected) selected - index else selected + index)
    }

    fun selectAllPlaylistItems() = _search.update { state ->
        val count = state.playlist?.entries.orEmpty().size
        state.copy(selectedPlaylistIndices = (1..count).toSet())
    }

    fun selectNoPlaylistItems() = _search.update { it.copy(selectedPlaylistIndices = emptySet()) }

    fun enqueueSelectedPlaylist() {
        val state = _search.value
        val playlist = state.playlist ?: return
        val url = state.playlistUrl ?: return
        downloadRepository.enqueuePlaylist(url, state.selectedPlaylistIndices.sorted(), playlist)
        _search.update { it.copy(playlist = null, playlistUrl = null) }
    }

    fun dismissPlaylist() = _search.update { it.copy(playlist = null, playlistUrl = null) }

    fun enqueue(item: YoutubeSearchItem) = downloadRepository.enqueueSearchItem(item)
    fun preview(item: YoutubeSearchItem) = previewPlayer.toggle(item)
    fun stopPreview() = previewPlayer.stop()

    override fun onCleared() {
        previewPlayer.stop()
        super.onCleared()
    }

    fun cancel(task: DownloadTask) { downloadRepository.cancel(task) }
    fun restart(task: DownloadTask) { downloadRepository.restart(task) }
    fun remove(task: DownloadTask) { downloadRepository.remove(task) }

    fun isQueuedOrCompleted(item: YoutubeSearchItem): Boolean {
        val queued = tasks.value.any { (task, state) ->
            state.viewState.videoId == item.id && state.downloadState !is DownloadState.Canceled &&
                state.downloadState !is DownloadState.Error
        }
        return queued || history.value.any {
            it.videoId == item.id && it.status.name == "COMPLETED"
        }
    }

    suspend fun checkFileAvailability(entry: DownloadHistoryEntry): HistoryFileAvailability =
        historyRepository.checkFileAvailability(entry)

    fun deleteHistory(entry: DownloadHistoryEntry) {
        viewModelScope.launch { historyRepository.delete(entry) }
    }

    fun clearHistory() {
        viewModelScope.launch { historyRepository.deleteAll() }
    }

    fun redownload(entry: DownloadHistoryEntry) = downloadRepository.enqueueUrl(entry.sourceUrl)

    private fun VideoInfo.toSearchItem(fallbackUrl: String): YoutubeSearchItem = YoutubeSearchItem(
        id = id,
        title = title.ifBlank { id },
        uploader = uploader ?: channel,
        durationSeconds = duration?.toInt(),
        thumbnailUrl = thumbnail ?: "https://i.ytimg.com/vi/$id/mqdefault.jpg",
        url = webpageUrl ?: originalUrl ?: fallbackUrl,
    )
}
