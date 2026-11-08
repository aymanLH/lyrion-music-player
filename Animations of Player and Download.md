# Animations of Player and Download

Study copy of the existing app code. The excerpts below are copied from the listed Kotlin files; this file does not replace or modify those source files.

## What controls the animation and spacing

- Both collapsible top bars use the same default heights: 250 dp expanded, 60 dp collapsed, and 150 dp in landscape.
- The Tracks grid top bar animates its height from scroll state. The selected tab title interpolates between the app typography styles as that height changes.
- Tracks places its toolbar controls at the bottom of the header with 12 dp horizontal and 4 dp vertical padding. Its grid content uses 16 dp horizontal padding, 8 dp vertical spacing, and 16 dp horizontal spacing.
- Download Search uses the same collapsible list top bar, the same toolbar control insets, and matching 16 dp / 8 dp list spacing. Its centered initial headline and search field are the first item in the scroll content.
- Queue and History currently use 20 dp horizontal content padding. Their top bar titles use the same title interpolation helper as Download Search.

## Tracks: collapsible grid header

Source: `app/src/main/java/com/dn0ne/player/app/presentation/components/topbar/LazyGridWithCollapsibleTabsTopBar.kt` (header sizing and scroll animation; lines 61–127).

### Collapsing header implementation

````kotlin
fun LazyGridWithCollapsibleTabsTopBar(
    topBarTabs: List<Tab>,
    gridStates: List<LazyGridState>,
    defaultSelectedTab: Tab = Tab.Tracks,
    onTabChange: (tab: Tab) -> Unit = {},
    tabTitleTextStyle: TextStyle = MaterialTheme.typography.headlineLarge,
    tabRowTitleTextStyle: TextStyle = MaterialTheme.typography.titleLarge,
    topBarButtons: @Composable BoxScope.(tab: Tab) -> Unit = {},
    minTopBarHeight: Dp = 60.dp,
    maxTopBarHeight: Dp = 250.dp,
    maxTopBarHeightLandscape: Dp = 150.dp,
    collapseFraction: (Float) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp),
    contentHorizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    contentVerticalArrangement: Arrangement.Vertical = Arrangement.Top,
    enableScrollbar: Boolean = true,
    modifier: Modifier = Modifier,
    gridCells: (tab: Tab) -> GridCells = { GridCells.Fixed(1) },
    tabContent: LazyGridScope.(tab: Tab) -> Unit
) {
    if (topBarTabs.isEmpty()) return
    val initialPage = topBarTabs.indexOf(defaultSelectedTab).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage) { topBarTabs.size }
    val settledTab = topBarTabs[pagerState.settledPage.coerceIn(topBarTabs.indices)]
    val activeGridState = gridStates[pagerState.currentPage.coerceIn(gridStates.indices)]
    val isScrolledFromTop by remember(activeGridState) {
        derivedStateOf {
            activeGridState.firstVisibleItemIndex > 0 ||
                    activeGridState.firstVisibleItemScrollOffset > 24
        }
    }
    val collapseTarget by animateFloatAsState(
        targetValue = if (isScrolledFromTop) 1f else 0f,
        label = "tabs-header-collapse"
    )
    val maxHeight = if (isSystemInLandscapeOrientation()) maxTopBarHeightLandscape else maxTopBarHeight
    val currentHeight = lerp(maxHeight, minTopBarHeight, collapseTarget)
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(collapseTarget) {
        collapseFraction(1f - collapseTarget)
    }
    LaunchedEffect(pagerState.settledPage) {
        onTabChange(settledTab)
    }
    if (settledTab != defaultSelectedTab) {
        BackHandler {
            coroutineScope.launch {
                pagerState.animateScrollToPage(initialPage)
            }
        }
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(currentHeight)
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                TabHeaderLabels(
                    tabs = topBarTabs,
                    selectedPage = pagerState.currentPage,
                    pagerState = pagerState,
                    collapseFraction = collapseTarget,
                    style = tabRowTitleTextStyle,
                    modifier = Modifier.fillMaxSize()
````

Source: `app/src/main/java/com/dn0ne/player/app/presentation/PlayerScreen.kt` (Tracks title style, toolbar inset, and list spacing).

### Tracks title and action-row style

````kotlin
            onTabChange(it)
        },
        tabTitleTextStyle = MaterialTheme.typography.titleLarge.copy(
            fontSize = lerp(
                MaterialTheme.typography.titleLarge.fontSize,
                MaterialTheme.typography.displaySmall.fontSize,
                collapseFraction
            ),
            fontWeight = FontWeight.Bold
        ),
        topBarButtons = { tab ->
            AnimatedContent(
                targetState = topBarContent,
                label = "top-bar-search-bar-animation",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .align(Alignment.BottomCenter)
            ) { state ->
                when (state) {
                    TopBarContent.Default -> {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween
````

### Tracks grid spacing

````kotlin
            collapseFraction = it
        },
        contentHorizontalArrangement = Arrangement.spacedBy(
            16.dp,
            alignment = Alignment.CenterHorizontally
        ),
        contentVerticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        gridCells = {
            if (it == Tab.Tracks || !gridPlaylists) GridCells.Fixed(1) else {
                GridCells.Adaptive(150.dp)
````

## Download: collapsing list header

Source: `app/src/main/java/com/dn0ne/player/app/presentation/components/topbar/ColumnWithCollapsibleTopBar.kt` (the list-based collapsible header used by Download Search, Queue, and History).

### Lazy list header animation and content placement

````kotlin
fun LazyColumnWithCollapsibleTopBar(
    topBarContent: @Composable BoxScope.() -> Unit,
    minTopBarHeight: Dp = 60.dp,
    maxTopBarHeight: Dp = 250.dp,
    maxTopBarHeightLandscape: Dp = 150.dp,
    collapsedByDefault: Boolean = false,
    collapseFraction: (Float) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    contentHorizontalAlignment: Alignment.Horizontal = Alignment.Start,
    contentVerticalArrangement: Arrangement.Vertical = Arrangement.Top,
    enableScrollbar: Boolean = true,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    val isInLandscapeOrientation = isSystemInLandscapeOrientation()
    val minTopBarHeight = remember { with(density) { minTopBarHeight.toPx() } }
    val maxTopBarHeight = remember {
        with(density) {
            if (isInLandscapeOrientation) {
                maxTopBarHeightLandscape.toPx()
            } else maxTopBarHeight.toPx()
        }
    }
    val topBarHeight = rememberAnimatable(
        initialValue = if (collapsedByDefault || isInLandscapeOrientation) {
            minTopBarHeight
        } else maxTopBarHeight
    )

    LaunchedEffect(isInLandscapeOrientation) {
        topBarHeight.snapTo(maxTopBarHeight)
    }

    LaunchedEffect(topBarHeight.value) {
        collapseFraction(
            (topBarHeight.value - minTopBarHeight) / (maxTopBarHeight - minTopBarHeight)
        )
    }

    val topBarScrollConnection = remember {
        return@remember object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val previousHeight = topBarHeight.value
                val newHeight = if (listState.firstVisibleItemIndex >= 0 && available.y < 0) {
                    (previousHeight + available.y).coerceIn(
                        minTopBarHeight,
                        maxTopBarHeight
                    )
                } else if (
                    listState.firstVisibleItemIndex == 0 &&
                    listState.layoutInfo.visibleItemsInfo.firstOrNull()?.offset == 0
                ) {
                    (previousHeight + available.y).coerceIn(
                        minTopBarHeight,
                        maxTopBarHeight
                    )
                } else previousHeight

                coroutineScope.launch {
                    topBarHeight.snapTo(newHeight)
                }
                return Offset(0f, newHeight - previousHeight)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                coroutineScope.launch {
                    val threshold = (maxTopBarHeight - minTopBarHeight)
                    topBarHeight.animateTo(
                        targetValue = if (topBarHeight.value < threshold) minTopBarHeight else maxTopBarHeight,
                        animationSpec = spring(stiffness = Spring.StiffnessLow)
                    )
                }

                return super.onPostFling(consumed, available)
            }
        }
    }

    Box(
        modifier = modifier
            .nestedScroll(topBarScrollConnection)
    ) {
        Column {
            Spacer(
                modifier = Modifier
                    .height(with(density) { topBarHeight.value.toDp() })
            )

            LazyColumnScrollbar(
                state = listState,
                settings = ScrollbarSettings(
                    enabled = enableScrollbar,
                    thumbUnselectedColor = MaterialTheme.colorScheme.surfaceContainer,
                    thumbSelectedColor = MaterialTheme.colorScheme.primaryContainer,
                )
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    horizontalAlignment = contentHorizontalAlignment,
                    verticalArrangement = contentVerticalArrangement
                ) {
                    content()

                    item {
                        Spacer(modifier = Modifier.height(200.dp))
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { topBarHeight.value.toDp() }),
        ) {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface
            ) {
                topBarContent()
            }
        }
    }
}
````

## Download Search: headline, title handoff, and margins

Source: `app/src/main/java/com/dn0ne/player/app/presentation/downloads/DownloadsScreens.kt`.

### Search collapsing header and action positions

````kotlin
    var missingEntry by remember { mutableStateOf<DownloadHistoryEntry?>(null) }
    var collapseFraction by remember { mutableStateOf(1f) }

    DisposableEffect(viewModel) { onDispose { viewModel.stopPreview() } }

    BackHandler(enabled = state.playlist == null) { onBack() }
    LazyColumnWithCollapsibleTopBar(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        contentVerticalArrangement = Arrangement.spacedBy(8.dp),
        enableScrollbar = false,
        collapseFraction = { collapseFraction = it },
        topBarContent = {
            if (!initial || collapseFraction <= .5f) Text(
                text = "Download",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = lerp(MaterialTheme.typography.titleLarge.fontSize, MaterialTheme.typography.headlineSmall.fontSize, collapseFraction),
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                ),
                modifier = Modifier.align(Alignment.Center),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).align(Alignment.BottomCenter), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { if (state.playlist != null) viewModel.dismissPlaylist() else onBack() }) {
                    Icon(Icons.Rounded.ArrowBackIosNew, contentDescription = "Back")
                }
                Row {
                    IconButton(onClick = onOpenQueue) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Downloads queue") }
                    IconButton(onClick = onOpenHistory) { Icon(Icons.Rounded.History, "Download history") }
                }
            }
        },
    ) {
        if (initial) {
            item {
                Column(
                    modifier = Modifier.fillParentMaxHeight(.78f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "Download",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.graphicsLayer { alpha = if (collapseFraction > .5f) 1f else 0f },
                    )
                    Spacer(Modifier.height(22.dp))
                    SearchBox(
                        value = state.query,
                        onValueChange = viewModel::updateQuery,
                        onSubmit = { focusManager.clearFocus(); viewModel.search() },
                        placeholder = "Search YouTube or paste a playlist link",
                    )
                }
            }
        } else {
            item {
                SearchBox(
                    value = state.query,
                    onValueChange = viewModel::updateQuery,
                    onSubmit = { focusManager.clearFocus(); viewModel.search() },
                    placeholder = "Search YouTube or paste a playlist link",
````

### Queue and History title setup

````kotlin
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
    onOpenTrack: (DownloadHistoryEntry) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val entries by viewModel.history.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var missingEntry by remember { mutableStateOf<DownloadHistoryEntry?>(null) }
    val scope = rememberCoroutineScope()
    var collapseFraction by remember { mutableStateOf(1f) }
    val filtered = entries.filter { it.title.contains(query, true) || it.uploader.contains(query, true) }
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
````

### Shared Download top bar title style

````kotlin

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
````
