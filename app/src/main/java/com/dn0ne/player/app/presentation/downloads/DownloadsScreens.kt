package com.dn0ne.player.app.presentation.downloads

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.util.Log
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp as lerpFloat
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.rememberAsyncImagePainter
import com.dn0ne.player.app.data.local.history.DownloadHistoryEntry
import com.dn0ne.player.app.data.local.history.DownloadHistoryStatus
import com.dn0ne.player.app.data.repository.HistoryFileAvailability
import com.dn0ne.player.app.domain.download.DownloadState
import com.dn0ne.player.app.domain.download.DownloadTask
import com.dn0ne.player.app.domain.download.DownloadTaskState
import com.dn0ne.player.app.domain.youtube.YoutubeSearchItem
import com.dn0ne.player.app.presentation.components.topbar.LazyColumnWithCollapsibleTopBar
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSearchScreen(
    viewModel: DownloadsViewModel,
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.search.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val initial = !state.hasSearched
    var missingEntry by remember { mutableStateOf<DownloadHistoryEntry?>(null) }

    DisposableEffect(viewModel) { onDispose { viewModel.stopPreview() } }

    BackHandler(enabled = state.playlist == null) { onBack() }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var resultPositioned by remember { mutableStateOf(false) }
    var searchHeightPx by remember { mutableIntStateOf(0) }
    var titleHeightPx by remember { mutableIntStateOf(0) }

    // Tracks' expanded header is 250.dp. Its bottom action row uses 48.dp IconButtons
    // plus the existing 4.dp top/bottom inset, so the row begins at 250 - 56 = 194.dp.
    // safeDrawingPadding below already places this coordinate space below the status bar.
    val toolbarVerticalInset = 4.dp
    val toolbarRowHeight = 48.dp + toolbarVerticalInset * 2
    val toolbarStartY = 250.dp - toolbarRowHeight
    val toolbarCenterY = toolbarRowHeight / 2
    val spacerHeight = toolbarStartY
    val spacerHeightPx = with(density) { spacerHeight.toPx() }
    val progress by remember(listState, initial, resultPositioned, spacerHeightPx) {
        derivedStateOf {
            when {
                !initial && !resultPositioned -> 1f
                listState.firstVisibleItemIndex > 0 -> 1f
                spacerHeightPx <= 0f -> 1f
                else -> (listState.firstVisibleItemScrollOffset / spacerHeightPx).coerceIn(0f, 1f)
            }
        }
    }

    LaunchedEffect(initial) {
        resultPositioned = false
        if (!initial) {
            listState.scrollToItem(index = 1)
            resultPositioned = true
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        val toolbarBottomY = toolbarStartY + toolbarRowHeight
        val listTopY = lerp(toolbarBottomY, toolbarRowHeight, progress)
        val listHeight = (maxHeight - listTopY).coerceAtLeast(0.dp)
        val searchHeight = with(density) { searchHeightPx.toDp() }
        val titleHeight = with(density) { titleHeightPx.toDp() }
        val initialSearchTopY = toolbarBottomY + spacerHeight
        val initialTitleCenterY = initialSearchTopY - 22.dp - titleHeight / 2
        val titleTopY = initialTitleCenterY - titleHeight / 2
        val toolbarTranslationPx = with(density) { (spacerHeight * (1f - progress)).toPx() }
        val titleTranslationPx = with(density) {
            (toolbarCenterY - initialTitleCenterY).toPx() * progress
        }
        val scrollRangeSpacer = (maxHeight - listTopY - searchHeight).coerceAtLeast(0.dp)

        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(listHeight).offset(y = listTopY),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.Top,
            ) {
                item(key = "download-header-travel") { Spacer(Modifier.height(spacerHeight)) }
                item(key = "download-search") {
                    SearchBox(
                        value = state.query,
                        onValueChange = viewModel::updateQuery,
                        onSubmit = { focusManager.clearFocus(); viewModel.search() },
                        placeholder = "Search YouTube or paste a playlist link",
                        modifier = Modifier.padding(horizontal = 16.dp)
                            .onSizeChanged { searchHeightPx = it.height },
                    )
                }
                if (state.isSearching) item { CenterMessage("Searching…", loading = true, modifier = Modifier.padding(top = 8.dp)) }
                state.error?.let { message -> item { CenterMessage(message, modifier = Modifier.padding(top = 8.dp)) } }
                if (state.hasSearched && !state.isSearching && state.results.isEmpty() && state.error == null && state.playlist == null) {
                    item { CenterMessage("No results found", modifier = Modifier.padding(top = 8.dp)) }
                }
                items(state.results, key = { it.id }) { item ->
                    val matchingTask = tasks.entries.firstOrNull { it.value.viewState.videoId == item.id }
                    val completed = matchingTask?.value?.downloadState is DownloadState.Completed ||
                        history.any { it.videoId == item.id && it.status == DownloadHistoryStatus.COMPLETED }
                    SearchResultRow(
                        item = item,
                        downloadState = matchingTask?.value?.downloadState,
                        completed = completed,
                        isPreviewing = preview.videoId == item.id && preview.isPlaying,
                        isPreviewLoading = preview.videoId == item.id && (preview.isResolving || preview.isBuffering),
                        onPreview = { viewModel.preview(item) },
                        onDownload = {
                            if (matchingTask?.value?.downloadState is DownloadState.Error) viewModel.restart(matchingTask.key)
                            else viewModel.enqueue(item)
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                item(key = "download-scroll-range") { Spacer(Modifier.height(scrollRangeSpacer)) }
            }

            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = toolbarVerticalInset)
                    .align(Alignment.TopCenter)
                    .graphicsLayer { translationY = toolbarTranslationPx },
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = { if (state.playlist != null) viewModel.dismissPlaylist() else onBack() }) {
                    Icon(Icons.Rounded.ArrowBackIosNew, contentDescription = "Back")
                }
                Row {
                    IconButton(onClick = onOpenQueue) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Downloads queue") }
                    IconButton(onClick = onOpenHistory) { Icon(Icons.Rounded.History, "Download history") }
                }
            }

            Text(
                text = "Download",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter)
                    .offset(y = titleTopY)
                    .onSizeChanged { titleHeightPx = it.height }
                    .graphicsLayer {
                        translationY = titleTranslationPx
                        scaleX = lerpFloat(1f, .5f, progress)
                        scaleY = lerpFloat(1f, .5f, progress)
                    },
            )
        }
    }

    state.playlist?.let { playlist ->
        PlaylistPicker(
            title = playlist.title ?: "Playlist",
            entries = playlist.entries.orEmpty(),
            selected = state.selectedPlaylistIndices,
            onToggle = viewModel::togglePlaylistIndex,
            onSelectAll = viewModel::selectAllPlaylistItems,
            onSelectNone = viewModel::selectNoPlaylistItems,
            onDismiss = { viewModel.dismissPlaylist() },
            onConfirm = viewModel::enqueueSelectedPlaylist,
        )
    }

    missingEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { missingEntry = null },
            title = { Text("File missing") },
            text = { Text("This download is no longer in your music library. Would you like to download it again?") },
            confirmButton = { TextButton(onClick = { viewModel.redownload(entry); missingEntry = null }) { Text("Re-download") } },
            dismissButton = { TextButton(onClick = { missingEntry = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadQueueScreen(
    viewModel: DownloadsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    var collapseFraction by remember { mutableStateOf(1f) }
    BackHandler(onBack = onBack)
    LazyColumnWithCollapsibleTopBar(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        contentVerticalArrangement = Arrangement.spacedBy(12.dp),
        collapseFraction = { collapseFraction = it },
        topBarContent = {
            DownloadTopBarTitle(androidx.compose.ui.platform.LocalContext.current.getString(com.dn0ne.player.R.string.downloads_queue_title), collapseFraction, Modifier.align(Alignment.Center))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).align(Alignment.BottomCenter)) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBackIosNew, "Back") }
            }
        },
    ) {
        val ordered = tasks.entries.sortedByDescending { it.key.timeCreated }
        if (ordered.isEmpty()) item { CenterMessage("Your download queue is empty") }
        items(ordered, key = { it.key.id }) { (task, taskState) ->
            QueueTaskRow(task, taskState, onCancel = { viewModel.cancel(task) }, onRetry = { viewModel.restart(task) }, onRemove = { viewModel.remove(task) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadHistoryScreen(
    viewModel: DownloadsViewModel,
    onBack: () -> Unit,
    onOpenTrack: suspend (DownloadHistoryEntry) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val entries by viewModel.history.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var entryIssue by remember { mutableStateOf<HistoryEntryIssue?>(null) }
    val scope = rememberCoroutineScope()
    var collapseFraction by remember { mutableStateOf(1f) }
    val filtered = entries.filter { it.title.contains(query, true) || it.uploader.contains(query, true) }
    suspend fun openHistoryEntry(entry: DownloadHistoryEntry) {
        if (entry.status == DownloadHistoryStatus.FAILED) {
            entryIssue = HistoryEntryIssue(entry, HistoryIssueKind.FAILED)
            return
        }
        val availability = viewModel.checkFileAvailability(entry)
        Log.d("YT_DEBUG", "History tap uri=${entry.mediaStoreUri} availability=$availability")
        when (availability) {
            HistoryFileAvailability.DELETED -> entryIssue = HistoryEntryIssue(entry, HistoryIssueKind.DELETED)
            HistoryFileAvailability.UNKNOWN -> entryIssue = HistoryEntryIssue(entry, HistoryIssueKind.UNKNOWN)
            HistoryFileAvailability.AVAILABLE -> if (!onOpenTrack(entry)) {
                entryIssue = HistoryEntryIssue(entry, HistoryIssueKind.NOT_LOADED)
            }
        }
    }
    BackHandler(onBack = onBack)
    LazyColumnWithCollapsibleTopBar(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        contentVerticalArrangement = Arrangement.spacedBy(10.dp),
        collapseFraction = { collapseFraction = it },
        topBarContent = {
            DownloadTopBarTitle(androidx.compose.ui.platform.LocalContext.current.getString(com.dn0ne.player.R.string.download_history_title), collapseFraction, Modifier.align(Alignment.Center))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).align(Alignment.BottomCenter), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBackIosNew, "Back") }
                IconButton(onClick = { confirmClear = true }) { Icon(Icons.Rounded.Delete, "Clear history") }
            }
        },
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text("Search title or uploader") },
                shape = RoundedCornerShape(28.dp),
            )
        }
        if (filtered.isEmpty()) item { CenterMessage(if (query.isBlank()) "No download history yet" else "No matching downloads") }
        items(filtered, key = { it.id }) { entry ->
            HistoryRow(
                entry = entry,
                onOpen = {
                    scope.launch { openHistoryEntry(entry) }
                },
                onDelete = { viewModel.deleteHistory(entry) },
                onRetry = { viewModel.redownload(entry) },
            )
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear download history?") },
        text = { Text("This removes history entries only. Audio files stay in your library.") },
        confirmButton = { TextButton(onClick = { viewModel.clearHistory(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
    entryIssue?.let { issue ->
        val entry = issue.entry
        AlertDialog(
            onDismissRequest = { entryIssue = null },
            title = {
                Text(when (issue.kind) {
                    HistoryIssueKind.FAILED -> "Download failed"
                    HistoryIssueKind.DELETED -> "File deleted"
                    HistoryIssueKind.NOT_LOADED -> "Not loaded yet"
                    HistoryIssueKind.UNKNOWN -> "Can't check file yet"
                })
            },
            text = {
                Text(when (issue.kind) {
                    HistoryIssueKind.FAILED -> entry.errorMessage?.let(::safeShortReason) ?: "Download failed"
                    HistoryIssueKind.DELETED -> "This file is no longer in MediaStore. Would you like to download it again?"
                    HistoryIssueKind.NOT_LOADED -> "The file exists in MediaStore but has not appeared in the library list yet. Try again shortly."
                    HistoryIssueKind.UNKNOWN -> "MediaStore could not confirm whether this file is available. Try again shortly."
                })
            },
            confirmButton = {
                when (issue.kind) {
                    HistoryIssueKind.DELETED, HistoryIssueKind.FAILED -> TextButton(onClick = { viewModel.redownload(entry); entryIssue = null }) { Text("Re-download") }
                    HistoryIssueKind.NOT_LOADED, HistoryIssueKind.UNKNOWN -> TextButton(onClick = { entryIssue = null; scope.launch { openHistoryEntry(entry) } }) { Text("Retry") }
                }
            },
            dismissButton = { TextButton(onClick = { entryIssue = null }) { Text("Close") } },
        )
    }
}

private enum class HistoryIssueKind { DELETED, NOT_LOADED, UNKNOWN, FAILED }
private data class HistoryEntryIssue(val entry: DownloadHistoryEntry, val kind: HistoryIssueKind)

@Composable
private fun SearchBox(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
    )
}

@Composable
private fun SearchResultRow(
    item: YoutubeSearchItem,
    downloadState: DownloadState?,
    completed: Boolean,
    isPreviewing: Boolean,
    isPreviewLoading: Boolean,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(item.thumbnailUrl, Modifier.size(64.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item.uploader ?: "YouTube", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.durationSeconds?.takeIf { it > 0 }?.let { Text(formatSeconds(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        IconButton(onClick = onPreview, enabled = !isPreviewLoading) {
            if (isPreviewLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(if (isPreviewing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (isPreviewing) "Pause preview" else "Preview")
        }
        IconButton(onClick = onDownload, enabled = downloadState !is DownloadState.FetchingInfo && downloadState !is DownloadState.ReadyWithInfo && downloadState !is DownloadState.Running && !completed) {
            when {
                downloadState is DownloadState.Running && downloadState.progress > 0f -> CircularProgressIndicator(progress = { downloadState.progress.coerceIn(0f, 1f) }, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                downloadState is DownloadState.Running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                downloadState is DownloadState.FetchingInfo || downloadState is DownloadState.ReadyWithInfo -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                completed -> Icon(Icons.Rounded.Check, "Downloaded")
                downloadState is DownloadState.Error -> Icon(Icons.Rounded.Refresh, "Retry download")
                else -> Icon(Icons.Rounded.Download, "Download")
            }
        }
    }
}

@Composable
private fun QueueTaskRow(task: DownloadTask, state: DownloadTaskState, onCancel: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit) {
    val downloadState = state.downloadState
    val progress = (downloadState as? DownloadState.Running)?.progress?.takeIf { it >= 0f }
    val label = when (downloadState) {
        DownloadState.Idle, is DownloadState.FetchingInfo, DownloadState.ReadyWithInfo -> "Queued"
        is DownloadState.Running -> progress?.let { "${(it * 100).toInt()}%" }.orEmpty()
        is DownloadState.Completed -> "Completed"
        is DownloadState.Canceled -> "Canceled"
        is DownloadState.Error -> downloadState.message
    }
    val metadata = listOfNotNull(
        (state.viewState.format ?: task.preferences.audioFormat.name.takeIf { it != "ORIGINAL" })?.takeIf { it.isNotBlank() }?.uppercase(),
        state.viewState.duration.takeIf { it > 0 }?.let(::formatSeconds),
    ).joinToString(" · ")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(state.viewState.thumbnailUrl, Modifier.size(56.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(safeDisplayTitle(state.viewState.title), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (metadata.isNotBlank()) Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (downloadState !is DownloadState.Running) Text(label, style = MaterialTheme.typography.bodySmall, color = if (downloadState is DownloadState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when (downloadState) {
                is DownloadState.FetchingInfo, is DownloadState.Running -> IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel download") }
                is DownloadState.Canceled, is DownloadState.Error -> IconButton(onClick = onRetry) { Icon(Icons.Rounded.Refresh, "Retry") }
                else -> IconButton(onClick = onRemove) { Icon(Icons.Rounded.Delete, "Remove from queue") }
            }
        }
        if (downloadState is DownloadState.Running) {
            if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun HistoryRow(entry: DownloadHistoryEntry, onOpen: () -> Unit, onDelete: () -> Unit, onRetry: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(onClick = onOpen).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(entry.thumbnailUrl, Modifier.size(60.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(safeDisplayTitle(entry.title), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.uploader, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${entry.format.uppercase()} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.timestamp))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.status == DownloadHistoryStatus.FAILED) Text(entry.errorMessage?.let(::safeShortReason) ?: "Download failed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (entry.status == DownloadHistoryStatus.FAILED) IconButton(onClick = onRetry) { Icon(Icons.Rounded.Refresh, "Retry download") }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "More options") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Delete history entry") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menuOpen = false; onDelete() })
            }
        }
    }
}

private fun safeShortReason(message: String): String = message.takeIf {
    it.length <= 110 && !it.contains("http", true) && !it.contains('/') && !it.contains('\\') && !it.contains("\n")
}?.removePrefix("ERROR: ")?.takeIf { it.isNotBlank() } ?: "Download failed"

private fun safeDisplayTitle(title: String): String = title.takeIf {
    it.isNotBlank() && !it.contains("http", true) && !it.contains('/') && !it.contains('\\') && !it.contains("\n")
} ?: "Audio download"

@Composable
private fun DownloadTopBarTitle(title: String, collapseFraction: Float, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge.copy(
            fontSize = lerp(MaterialTheme.typography.titleLarge.fontSize, MaterialTheme.typography.headlineSmall.fontSize, collapseFraction),
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistPicker(
    title: String,
    entries: List<com.dn0ne.player.app.domain.youtube.PlaylistEntry>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSelectAll) { Text("Select all") }
                TextButton(onClick = onSelectNone) { Text("Select none") }
            }
            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.weight(1f, fill = false).height(360.dp)) {
                items(entries.size) { index ->
                    val entry = entries[index]
                    val playlistIndex = index + 1
                    Row(Modifier.fillMaxWidth().clickable { onToggle(playlistIndex) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = playlistIndex in selected, onCheckedChange = { onToggle(playlistIndex) })
                        Text(entry.title ?: "Track $playlistIndex", Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        entry.duration?.toInt()?.takeIf { it > 0 }?.let { Text(formatSeconds(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            Button(onClick = onConfirm, enabled = selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) { Text("Download ${selected.size} selected") }
        }
    }
}

@Composable
private fun Thumbnail(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
        if (url != null) {
            val painter = rememberAsyncImagePainter(url)
            val painterState by painter.state.collectAsState()
            androidx.compose.foundation.Image(painter, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (painterState is coil3.compose.AsyncImagePainter.State.Loading || painterState is coil3.compose.AsyncImagePainter.State.Error) {
                Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CenterMessage(message: String, loading: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loading) CircularProgressIndicator()
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatSeconds(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
