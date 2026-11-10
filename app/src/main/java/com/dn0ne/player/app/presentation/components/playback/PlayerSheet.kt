package com.dn0ne.player.app.presentation.components.playback

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import android.util.Log
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.dn0ne.player.R
import com.dn0ne.player.app.domain.playback.PlaybackMode
import com.dn0ne.player.app.domain.track.Playlist
import com.dn0ne.player.app.domain.track.Track
import com.dn0ne.player.app.presentation.components.CoverArt
import com.dn0ne.player.app.presentation.components.TrackMenuButton
import com.dn0ne.player.app.presentation.components.isSystemInLandscapeOrientation
import com.dn0ne.player.app.presentation.components.settings.Theme
import com.dn0ne.player.core.data.Settings
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerSheet(
    playbackStateFlow: StateFlow<PlaybackState>,
    canShowJumpButtons: Boolean,
    onPlayerExpandedChange: (Boolean) -> Unit,
    onLyricsSheetExpandedChange: (Boolean) -> Unit,
    onPlayClick: () -> Unit,
    onPauseClick: () -> Unit,
    onSeekToNextClick: () -> Unit,
    onSeekToPreviousClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onReset: () -> Unit,
    onPlaybackModeClick: () -> Unit,
    onCoverArtLoaded: (String, ImageBitmap?) -> Unit,
    onSeekToQueueIndex: (Int) -> Unit,
    onPlayNextClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onViewTrackInfoClick: () -> Unit,
    onGoToAlbumClick: () -> Unit,
    onGoToArtistClick: () -> Unit,
    onLyricsClick: () -> Unit,
    onPasteLyricsClick: () -> Unit,
    onPickLyricsClick: () -> Unit,
    onFetchLyricsFromRemoteClick: () -> Unit,
    onRemoveFromQueueClick: (Int) -> Unit,
    onReorderingQueue: (Int, Int) -> Unit,
    onTrackClick: (Track, Playlist) -> Unit,
    settings: Settings,
    modifier: Modifier = Modifier
) {
    val playbackState by playbackStateFlow.collectAsState()
    val floatingMiniPlayer by settings.floatingMiniPlayer.collectAsState()
    val floatingMiniPlayerHeight by settings.floatingMiniPlayerHeight.collectAsState()
    val isExpanded by remember {
        derivedStateOf {
            playbackState.isPlayerExpanded
        }
    }
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val thresholdY = with(density) { 200.dp.toPx() }
    val decay = rememberSplineBasedDecay<Float>()
    val translationY = remember {
        Animatable(0f).apply {
            if (isExpanded) {
                updateBounds(
                    lowerBound = 0f,
                    upperBound = thresholdY
                )
            } else {
                updateBounds(
                    lowerBound = -thresholdY,
                    upperBound = thresholdY
                )
            }
        }
    }
    val draggableState = rememberDraggableState { dragAmount ->
        coroutineScope.launch {
            translationY.snapTo(
                translationY.value + (dragAmount * (1 - (translationY.value / thresholdY).absoluteValue))
            )
        }
    }

    AnimatedContent(
        targetState = isExpanded,
        label = "player-content",
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (!isExpanded && floatingMiniPlayer) {
                    Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                } else Modifier
            )
            .graphicsLayer {
                this.translationY = translationY.value

                val cornerRadius =
                    lerp(0.dp, 36.dp, this.translationY.absoluteValue / (thresholdY * 0.5f))
                this.clip = true
                this.shape = if (isExpanded) {
                    RoundedCornerShape(cornerRadius)
                } else {
                    RoundedCornerShape(
                        topStart = 36.dp,
                        topEnd = 36.dp,
                        bottomStart = if (floatingMiniPlayer) 36.dp else cornerRadius,
                        bottomEnd = if (floatingMiniPlayer) 36.dp else cornerRadius
                    )
                }
            }
            .draggable(
                state = draggableState,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    val decayY = decay.calculateTargetValue(
                        initialValue = translationY.value,
                        initialVelocity = velocity
                    )

                    coroutineScope.launch {
                        if (!isExpanded) {
                            val shouldStopPlayback = decayY > thresholdY * .5f
                            if (shouldStopPlayback) {
                                onReset()
                                return@launch
                            }
                        }

                        val shouldChangeExpandedState = decayY.absoluteValue > (thresholdY * 0.5f)
                        if (shouldChangeExpandedState) {
                            onPlayerExpandedChange(!isExpanded)
                            translationY.apply {
                                animateTo(0f)
                                if (isExpanded) {
                                    updateBounds(
                                        lowerBound = 0f,
                                        upperBound = thresholdY
                                    )
                                } else {
                                    updateBounds(
                                        lowerBound = -thresholdY,
                                        upperBound = thresholdY
                                    )
                                }
                            }
                        } else {
                            translationY.animateTo(0f)
                        }
                    }
                }
            )
            .background(
                color = if (isExpanded) {
                    MaterialTheme.colorScheme.surfaceContainer
                } else {
                    Color.White.copy(alpha = 0.14f)
                        .compositeOver(MaterialTheme.colorScheme.background)
                }
            )

    ) { state ->
        DisposableEffect(state) {
            val layout = if (state) "expanded" else "compact"
            Log.d("YT_DEBUG", "PlayerSheet layout enter=$layout currentIndex=${playbackState.currentMediaItemIndex}")
            onDispose {
                Log.d("YT_DEBUG", "PlayerSheet layout exit=$layout")
            }
        }
        when (state) {
            false -> {
                BottomPlayer(
                    playbackStateFlow = playbackStateFlow,
                    isFloating = floatingMiniPlayer,
                    floatingMiniPlayerHeight = floatingMiniPlayerHeight.dp,
                    onClick = {
                        onPlayerExpandedChange(true)
                        translationY.updateBounds(
                            lowerBound = 0f,
                            upperBound = thresholdY
                        )
                    },
                    onPlayClick = onPlayClick,
                    onPauseClick = onPauseClick,
                    onSeekToNextClick = onSeekToNextClick,
                    onSeekToPreviousClick = onSeekToPreviousClick,
                    onCoverArtLoaded = onCoverArtLoaded,
                    onSeekToQueueIndex = onSeekToQueueIndex
                )
            }

            true -> {
                val appearance by settings.appearance.collectAsState()
                val amoledDarkTheme by settings.amoledDarkTheme.collectAsState()
                val isDarkTheme = when (appearance) {
                    Theme.Appearance.System -> isSystemInDarkTheme()
                    Theme.Appearance.Light -> false
                    Theme.Appearance.Dark -> true
                }

                ExpandedPlayer(
                    playbackStateFlow = playbackStateFlow,
                    canShowJumpButtons = canShowJumpButtons,
                    onSeekTo = onSeekTo,
                    onPauseClick = onPauseClick,
                    onPlayClick = onPlayClick,
                    onSeekToNextClick = onSeekToNextClick,
                    onSeekToPreviousClick = onSeekToPreviousClick,
                    onHideClick = {
                        onPlayerExpandedChange(false)
                        translationY.updateBounds(
                            lowerBound = -thresholdY,
                            upperBound = 0f
                        )
                    },
                    onPlaybackModeClick = onPlaybackModeClick,
                    onCoverArtLoaded = onCoverArtLoaded,
                    onSeekToQueueIndex = onSeekToQueueIndex,
                    onPlayNextClick = onPlayNextClick,
                    onAddToQueueClick = onAddToQueueClick,
                    onAddToPlaylistClick = onAddToPlaylistClick,
                    onViewTrackInfoClick = onViewTrackInfoClick,
                    onGoToAlbumClick = onGoToAlbumClick,
                    onGoToArtistClick = onGoToArtistClick,
                    onLyricsSheetExpandedChange = onLyricsSheetExpandedChange,
                    onLyricsClick = onLyricsClick,
                    onPasteLyricsClick = onPasteLyricsClick,
                    onPickLyricsClick = onPickLyricsClick,
                    onFetchLyricsFromRemoteClick = onFetchLyricsFromRemoteClick,
                    lyricsTextStyle = MaterialTheme.typography.headlineMedium
                        .copy(
                            fontSize = settings.lyricsFontSize,
                            lineHeight = settings.lyricsLineHeight,
                            letterSpacing = settings.lyricsLetterSpacing,
                            textAlign = settings.lyricsAlignment,
                            fontWeight = FontWeight(settings.lyricsFontWeight),
                        ),
                    lyricsContainerColor = when {
                        settings.useDarkPaletteOnLyricsSheet && !isDarkTheme -> {
                            MaterialTheme.colorScheme.primary
                        }

                        else -> {
                            if (!amoledDarkTheme) {
                                MaterialTheme.colorScheme.inversePrimary
                            } else {
                                MaterialTheme.colorScheme.background
                            }
                        }
                    },
                    lyricsContentColor = when {
                        settings.useDarkPaletteOnLyricsSheet && !isDarkTheme -> {
                            MaterialTheme.colorScheme.surface
                        }
                        else -> MaterialTheme.colorScheme.inverseSurface
                    },
                    onRemoveFromQueueClick = onRemoveFromQueueClick,
                    onReorderingQueue = onReorderingQueue,
                    onTrackClick = onTrackClick,
                    modifier = Modifier.clickable(
                        onClick = {},
                        interactionSource = null,
                        indication = null
                    )
                )
            }
        }
    }
}

@Composable
fun BottomPlayer(
    playbackStateFlow: StateFlow<PlaybackState>,
    isFloating: Boolean,
    floatingMiniPlayerHeight: Dp,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    onPauseClick: () -> Unit,
    onSeekToNextClick: () -> Unit,
    onSeekToPreviousClick: () -> Unit,
    onCoverArtLoaded: (String, ImageBitmap?) -> Unit,
    onSeekToQueueIndex: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Let the artwork follow the floating bar height so a taller bar does not
    // leave a small cover floating in empty space.
    val coverArtSize = if (isFloating) {
        (floatingMiniPlayerHeight - 24.dp).coerceIn(40.dp, 88.dp)
    } else {
        48.dp
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable {
                onClick()
            }
    ) {
        val playbackState by playbackStateFlow.collectAsState()
        val currentTrack by remember {
            derivedStateOf {
                playbackState.currentTrack!!
            }
        }
        val isPlaying by remember {
            derivedStateOf {
                playbackState.isPlaying
            }
        }
        val position by remember {
            derivedStateOf {
                playbackState.position
            }
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(
                    animateFloatAsState(
                        targetValue = position.toFloat() / currentTrack.duration,
                        animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
                        label = "bottom-player-progress"
                    ).value
                )
                .background(
                    color = Color.White.copy(alpha = 0.20f)
                        .compositeOver(MaterialTheme.colorScheme.background)
                )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isFloating) {
                        Modifier.height(floatingMiniPlayerHeight)
                    } else {
                        Modifier.navigationBarsPadding()
                    }
                )
                .padding(
                    horizontal = 28.dp,
                    vertical = if (isFloating) 0.dp else 28.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(0.7f)
            ) {
                    PlayerCoverPager(
                        playbackState = playbackState,
                        onCoverArtLoaded = onCoverArtLoaded,
                        onSeekToQueueIndex = onSeekToQueueIndex,
                        modifier = Modifier.size(coverArtSize).clip(ShapeDefaults.Small)
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column {
                        val context = LocalContext.current
                        Text(
                            text = currentTrack.title
                                ?: context.resources.getString(R.string.unknown_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.basicMarquee()
                        )
                        Text(
                            text = currentTrack.artist
                                ?: context.resources.getString(R.string.unknown_artist),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.basicMarquee()
                        )
                    }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isPlaying) {
                    IconButton(
                        onClick = onPauseClick,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Pause,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = onPlayClick,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onSeekToNextClick
                ) {
                    val layoutDirection = LocalLayoutDirection.current
                    val isRtl = layoutDirection == LayoutDirection.Rtl
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .size(36.dp)
                            .graphicsLayer {
                                if (isRtl) {
                                    rotationY = 180f
                                }
                            }
                    )
                }
            }
        }
    }
}

@Composable
fun ExpandedPlayer(
    playbackStateFlow: StateFlow<PlaybackState>,
    canShowJumpButtons: Boolean,
    onPlayClick: () -> Unit,
    onPauseClick: () -> Unit,
    onSeekToNextClick: () -> Unit,
    onSeekToPreviousClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onHideClick: () -> Unit,
    onPlaybackModeClick: () -> Unit,
    onCoverArtLoaded: (String, ImageBitmap?) -> Unit,
    onSeekToQueueIndex: (Int) -> Unit,
    onPlayNextClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onViewTrackInfoClick: () -> Unit,
    onGoToAlbumClick: () -> Unit,
    onGoToArtistClick: () -> Unit,
    onLyricsSheetExpandedChange: (Boolean) -> Unit,
    onLyricsClick: () -> Unit,
    onPasteLyricsClick: () -> Unit,
    onPickLyricsClick: () -> Unit,
    onFetchLyricsFromRemoteClick: () -> Unit,
    lyricsTextStyle: TextStyle,
    lyricsContainerColor: Color,
    lyricsContentColor: Color,
    onRemoveFromQueueClick: (Int) -> Unit,
    onReorderingQueue: (Int, Int) -> Unit,
    onTrackClick: (Track, Playlist) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onHideClick()
    }

    val playbackState by playbackStateFlow.collectAsState()
    val showLyricsSheet by remember {
        derivedStateOf {
            playbackState.isLyricsSheetExpanded
        }
    }
    var showQueue by remember {
        mutableStateOf(false)
    }
    var showSleepTimerSheet by remember {
        mutableStateOf(false)
    }

    Box {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(color = MaterialTheme.colorScheme.surface)
                .safeDrawingPadding()
                .padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            val playbackMode by remember {
                derivedStateOf {
                    playbackState.playbackMode
                }
            }

            val context = LocalContext.current
            if (!isSystemInLandscapeOrientation()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onHideClick
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ExpandMore,
                            contentDescription = context.resources.getString(R.string.close_player_sheet),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }


                    Row {
                        IconButton(
                            onClick = {
                                showSleepTimerSheet = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Timer,
                                contentDescription = context.resources.getString(R.string.set_sleep_timer),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = {
                                showQueue = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = context.resources.getString(R.string.show_queue),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = {
                                onLyricsSheetExpandedChange(true)
                                onLyricsClick()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lyrics,
                                contentDescription = context.resources.getString(R.string.show_lyrics),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = onPlaybackModeClick
                        ) {
                            Icon(
                                imageVector = when (playbackMode) {
                                    PlaybackMode.Repeat -> Icons.Rounded.Repeat
                                    PlaybackMode.RepeatOne -> Icons.Rounded.RepeatOne
                                    PlaybackMode.Shuffle -> Icons.Rounded.Shuffle
                                },
                                contentDescription = context.resources.getString(R.string.playback_mode_toggle),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        TrackMenuButton(
                            onPlayNextClick = onPlayNextClick,
                            onAddToQueueClick = onAddToQueueClick,
                            onAddToPlaylistClick = onAddToPlaylistClick,
                            onViewTrackInfoClick = onViewTrackInfoClick,
                            onGoToAlbumClick = onGoToAlbumClick,
                            onGoToArtistClick = onGoToArtistClick
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val currentTrack by remember {
                        derivedStateOf {
                            playbackState.currentTrack!!
                        }
                    }

                    PlayerCoverPager(
                        playbackState = playbackState,
                        onCoverArtLoaded = onCoverArtLoaded,
                        onSeekToQueueIndex = onSeekToQueueIndex,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(ShapeDefaults.Large)
                    )

                    Spacer(modifier = Modifier.height(28.dp))

                    AnimatedContent(
                        targetState = currentTrack,
                        label = "title-artist-text-animation",
                        transitionSpec = {
                            fadeIn() togetherWith fadeOut()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { track ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val context = LocalContext.current
                            Text(
                                text = track.title
                                    ?: context.resources.getString(R.string.unknown_title),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.basicMarquee()
                            )

                            Text(
                                text = track.artist
                                    ?: context.resources.getString(R.string.unknown_artist),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.basicMarquee()
                            )
                        }

                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    PlaybackControl(
                        playbackStateFlow = playbackStateFlow,
                        onPlayClick = onPlayClick,
                        onPauseClick = onPauseClick,
                        onSeekTo = onSeekTo,
                        onSeekToNextClick = onSeekToNextClick,
                        onSeekToPreviousClick = onSeekToPreviousClick,
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {

                    val currentTrack by remember {
                        derivedStateOf {
                            playbackState.currentTrack!!
                        }
                    }
                    PlayerCoverPager(
                        playbackState = playbackState,
                        onCoverArtLoaded = onCoverArtLoaded,
                        onSeekToQueueIndex = onSeekToQueueIndex,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 28.dp)
                            .padding(vertical = 28.dp)
                            .clip(ShapeDefaults.ExtraLarge)
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp)
                                .padding(horizontal = 16.dp)
                                .align(Alignment.TopCenter),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(
                                onClick = onHideClick
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ExpandMore,
                                    contentDescription = context.resources.getString(R.string.close_player_sheet),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }


                            Row {
                                IconButton(
                                    onClick = {
                                        showSleepTimerSheet = true
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Timer,
                                        contentDescription = context.resources.getString(R.string.set_sleep_timer),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        showQueue = true
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                        contentDescription = context.resources.getString(R.string.show_queue),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        onLyricsSheetExpandedChange(true)
                                        onLyricsClick()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lyrics,
                                        contentDescription = context.resources.getString(R.string.show_lyrics),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                IconButton(
                                    onClick = onPlaybackModeClick
                                ) {
                                    Icon(
                                        imageVector = when (playbackMode) {
                                            PlaybackMode.Repeat -> Icons.Rounded.Repeat
                                            PlaybackMode.RepeatOne -> Icons.Rounded.RepeatOne
                                            PlaybackMode.Shuffle -> Icons.Rounded.Shuffle
                                        },
                                        contentDescription = context.resources.getString(R.string.playback_mode_toggle),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                TrackMenuButton(
                                    onPlayNextClick = onPlayNextClick,
                                    onAddToQueueClick = onAddToQueueClick,
                                    onAddToPlaylistClick = onAddToPlaylistClick,
                                    onViewTrackInfoClick = onViewTrackInfoClick,
                                    onGoToAlbumClick = onGoToAlbumClick,
                                    onGoToArtistClick = onGoToArtistClick
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .offset(y = 28.dp)
                                .padding(horizontal = 28.dp)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            AnimatedContent(
                                targetState = currentTrack,
                                label = "title-artist-text-animation",
                                transitionSpec = {
                                    fadeIn() togetherWith fadeOut()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { track ->
                                Column(
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val context = LocalContext.current
                                    Text(
                                        text = track.title
                                            ?: context.resources.getString(R.string.unknown_title),
                                        style = MaterialTheme.typography.displaySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.basicMarquee()
                                    )

                                    Text(
                                        text = track.artist
                                            ?: context.resources.getString(R.string.unknown_artist),
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.basicMarquee()
                                    )
                                }

                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            PlaybackControl(
                                playbackStateFlow = playbackStateFlow,
                                onPlayClick = onPlayClick,
                                onPauseClick = onPauseClick,
                                onSeekTo = onSeekTo,
                                onSeekToNextClick = onSeekToNextClick,
                                onSeekToPreviousClick = onSeekToPreviousClick,
                                modifier = Modifier
                                    .fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showLyricsSheet,
            enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMedium)) + slideInVertically(
                initialOffsetY = { it / 10 }),
            exit = fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium)) + slideOutVertically(
                targetOffsetY = { it / 10 }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            LyricsSheet(
                playbackStateFlow = playbackStateFlow,
                lyricsTextStyle = lyricsTextStyle,
                onBackClick = {
                    onLyricsSheetExpandedChange(false)
                },
                onPlayClick = onPlayClick,
                onPauseClick = onPauseClick,
                containerColor = lyricsContainerColor,
                contentColor = lyricsContentColor,
                onSeekTo = onSeekTo,
                onPasteLyricsClick = onPasteLyricsClick,
                onPickLyricsClick = onPickLyricsClick,
                onFetchLyricsFromRemoteClick = onFetchLyricsFromRemoteClick,
                modifier = Modifier.fillMaxSize()
            )
        }

        AnimatedVisibility(
            visible = showQueue,
            enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMedium)) + slideInVertically(
                initialOffsetY = { it / 10 }),
            exit = fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium)) + slideOutVertically(
                targetOffsetY = { it / 10 }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val playlist by remember {
                derivedStateOf {
                    playbackState.playlist
                }
            }
            val currentTrack by remember {
                derivedStateOf {
                    playbackState.currentTrack
                }
            }

            val listState = rememberLazyListState()
            Queue(
                listState = listState,
                playlist = playlist,
                currentTrack = currentTrack,
                canShowJumpButtons = canShowJumpButtons,
                onRemoveFromQueueClick = onRemoveFromQueueClick,
                onReorderingQueue = onReorderingQueue,
                onTrackClick = onTrackClick,
                onBackClick = {
                    showQueue = false
                }
            )
        }

        if (showSleepTimerSheet) {
            SleepTimerBottomSheet(
                onDismissRequest = {
                    showSleepTimerSheet = false
                }
            )
        }

    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerCoverPager(
    playbackState: PlaybackState,
    onCoverArtLoaded: (String, ImageBitmap?) -> Unit,
    onSeekToQueueIndex: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val queue = playbackState.playlist?.trackList.orEmpty()
    if (queue.isEmpty()) return

    val queueKeys = remember(queue) {
        val occurrences = mutableMapOf<String, Int>()
        queue.map { track ->
            val id = track.uri.toString()
            val occurrence = occurrences.getOrDefault(id, 0)
            occurrences[id] = occurrence + 1
            "$id#$occurrence"
        }
    }
    val queueSignature = remember(queueKeys) { queueKeys.joinToString("\u001f") }
    val targetIndex = playbackState.currentMediaItemIndex
        .takeIf { it in queue.indices }
        ?: queue.indexOfFirst { it.uri == playbackState.currentTrack?.uri }
            .takeIf { it >= 0 }
        ?: 0
    val pagerState = rememberPagerState(
        initialPage = targetIndex,
        pageCount = { queue.size }
    )
    val direction = LocalLayoutDirection.current
    val context = LocalContext.current
    val imageLoader = remember(context) { SingletonImageLoader.get(context) }
    val bitmaps = remember { mutableStateMapOf<String, ImageBitmap?>() }
    var measuredSize by remember { mutableStateOf(IntSize.Zero) }
    var userDragActive by remember { mutableStateOf(false) }
    var userDragSettlePending by remember { mutableStateOf(false) }
    var dragQueueSignature by remember { mutableStateOf<String?>(null) }
    var pendingUserTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val latestQueueSignature by rememberUpdatedState(queueSignature)
    val latestQueue by rememberUpdatedState(queue)
    val latestCurrentIndex by rememberUpdatedState(playbackState.currentMediaItemIndex)
    val callback by rememberUpdatedState(onSeekToQueueIndex)
    val coverCallback by rememberUpdatedState(onCoverArtLoaded)

    DisposableEffect(pagerState) {
        val instance = System.identityHashCode(pagerState)
        Log.d("YT_DEBUG", "CoverPager mounted instance=$instance page=${pagerState.settledPage} target=$targetIndex count=${queue.size}")
        onDispose { Log.d("YT_DEBUG", "CoverPager disposed instance=$instance page=${pagerState.settledPage}") }
    }

    LaunchedEffect(queueSignature, playbackState.playbackMode) {
        Log.d("YT_DEBUG", "CoverPager queue/mode changed mode=${playbackState.playbackMode} target=$targetIndex page=${pagerState.settledPage} keys=$queueSignature")
    }

    LaunchedEffect(pagerState) {
        pagerState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    userDragActive = true
                    userDragSettlePending = true
                    dragQueueSignature = latestQueueSignature
                    Log.d("YT_DEBUG", "CoverPager drag start page=${pagerState.currentPage} queue=$queueSignature")
                }
                is DragInteraction.Stop -> {
                    Log.d("YT_DEBUG", "CoverPager drag stop page=${pagerState.currentPage} (waiting for settle)")
                }
                is DragInteraction.Cancel -> {
                    Log.d("YT_DEBUG", "CoverPager drag cancel page=${pagerState.currentPage} (waiting for settle)")
                }
            }
        }
    }

    // Commit a swipe only once the pager has actually settled. At DragInteraction.Stop the
    // settle/fling animation has not run yet, so reading settledPage there returns the page
    // the pager was on *before* the swipe. That made the callback select the previous track,
    // leaving the cover one song ahead of playback. A long-lived observer is also important:
    // a one-shot first { !isScrollInProgress } can observe a stale `false` and skip the
    // settle entirely, which delayed the track change until the next swipe.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { !it }
            .collect {
                if (!userDragSettlePending) return@collect
                userDragSettlePending = false
                userDragActive = false
                val dragSignature = dragQueueSignature
                dragQueueSignature = null
                val settled = pagerState.currentPage
                if (dragSignature == latestQueueSignature && settled in latestQueue.indices && settled != latestCurrentIndex) {
                    pendingUserTarget = latestQueueSignature to settled
                    Log.d("YT_DEBUG", "CoverPager user settle page=$settled track=${latestQueue[settled].uri} queue=$latestQueueSignature")
                    callback(settled)
                } else {
                    Log.d("YT_DEBUG", "CoverPager user settle ignored page=$settled current=$latestCurrentIndex queueChanged=${dragSignature != latestQueueSignature}")
                }
            }
    }

    LaunchedEffect(targetIndex, queueSignature, userDragActive, pendingUserTarget) {
        if (userDragActive) return@LaunchedEffect
        val pending = pendingUserTarget
        if (pending != null) {
            if (pending.first != queueSignature) pendingUserTarget = null
            else if (targetIndex == pending.second) pendingUserTarget = null
            else return@LaunchedEffect
        }
        if (targetIndex !in queue.indices || pagerState.settledPage == targetIndex) return@LaunchedEffect
        val from = pagerState.settledPage
        val trackId = queue[targetIndex].uri
        if (kotlin.math.abs(targetIndex - from) == 1) {
            Log.d("YT_DEBUG", "CoverPager external adjacent from=$from to=$targetIndex track=$trackId")
            pagerState.animateScrollToPage(
                targetIndex,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        } else {
            Log.d("YT_DEBUG", "CoverPager external jump from=$from to=$targetIndex track=$trackId")
            pagerState.scrollToPage(targetIndex)
        }
    }

    val settledTrackId = queue.getOrNull(pagerState.settledPage)?.uri?.toString()
    LaunchedEffect(settledTrackId, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) {
            settledTrackId?.let { id ->
                if (bitmaps.containsKey(id)) coverCallback(id, bitmaps[id])
            }
        }
    }

    LaunchedEffect(pagerState.currentPage, measuredSize, queueSignature) {
        if (measuredSize.width <= 0 || measuredSize.height <= 0) return@LaunchedEffect
        listOf(pagerState.currentPage - 1, pagerState.currentPage + 1)
            .mapNotNull(queue::getOrNull)
            .forEach { track ->
                imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(track.coverArtUri)
                        .size(measuredSize.width, measuredSize.height)
                        .build()
                )
            }
    }

    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        pagerSnapDistance = PagerSnapDistance.atMost(1)
    )
    HorizontalPager(
        state = pagerState,
        modifier = modifier.onSizeChanged { measuredSize = it },
        beyondViewportPageCount = 1,
        flingBehavior = flingBehavior,
        reverseLayout = direction == LayoutDirection.Rtl,
        key = { page -> queueKeys[page] }
    ) { page ->
        val track = queue.getOrNull(page) ?: return@HorizontalPager
        val trackId = track.uri.toString()
        CoverArt(
            uri = track.coverArtUri,
            onCoverArtLoaded = { bitmap ->
                bitmaps[trackId] = bitmap
                if (page == pagerState.settledPage && !pagerState.isScrollInProgress) {
                    coverCallback(trackId, bitmap)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun PlaybackControl(
    playbackStateFlow: StateFlow<PlaybackState>,
    onPlayClick: () -> Unit,
    onPauseClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekToNextClick: () -> Unit,
    onSeekToPreviousClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val playbackState by playbackStateFlow.collectAsState()
        val position by remember {
            derivedStateOf {
                playbackState.position
            }
        }
        val currentTrack by remember {
            derivedStateOf {
                playbackState.currentTrack!!
            }
        }
        val isPlaying by remember {
            derivedStateOf {
                playbackState.isPlaying
            }
        }

        WavingSeekBar(
            position = position,
            duration = currentTrack.duration.toLong(),
            onPositionChange = {
                onSeekTo(it)
            },
            isPlaying = isPlaying,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val layoutDirection = LocalLayoutDirection.current
            val isRtl = layoutDirection == LayoutDirection.Rtl
            IconButton(
                onClick = onSeekToPreviousClick,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(36.dp)
                        .graphicsLayer {
                            if (isRtl) {
                                rotationY = 180f
                            }
                        }
                )
            }

            if (isPlaying) {
                IconButton(
                    onClick = onPauseClick,
                    modifier = Modifier.size(60.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Pause,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                }
            } else {
                IconButton(
                    onClick = onPlayClick,
                    modifier = Modifier.size(60.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)

                    )
                }
            }

            IconButton(
                onClick = onSeekToNextClick,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(36.dp)
                        .graphicsLayer {
                            if (isRtl) {
                                rotationY = 180f
                            }
                        }
                )
            }
        }
    }
}
