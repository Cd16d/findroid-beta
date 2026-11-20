package dev.jdtech.jellyfin.presentation.film

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.LocalCastPlayerHeight
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeSection
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeSuggestions
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeView
import dev.jdtech.jellyfin.core.presentation.dummy.dummyServer
import dev.jdtech.jellyfin.core.presentation.theme.FindroidMotion
import dev.jdtech.jellyfin.core.presentation.theme.rememberLazyListStateAtStart
import dev.jdtech.jellyfin.film.presentation.home.HomeAction
import dev.jdtech.jellyfin.film.presentation.home.HomeState
import dev.jdtech.jellyfin.film.presentation.home.HomeViewModel
import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.presentation.components.ErrorDialog
import dev.jdtech.jellyfin.presentation.film.components.HomeCarousel
import dev.jdtech.jellyfin.presentation.film.components.HomeHeader
import dev.jdtech.jellyfin.presentation.film.components.HomeSection
import dev.jdtech.jellyfin.presentation.film.components.HomeView
import dev.jdtech.jellyfin.presentation.film.components.PlaceholderScreen
import dev.jdtech.jellyfin.presentation.film.components.ServerSelectionBottomSheet
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onLibraryClick: (library: FindroidCollection) -> Unit,
    onUserClick: () -> Unit,
    onManageServers: () -> Unit,
    onItemClick: (item: FindroidItem) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadData(refreshSuggestions = true, isAppOpen = true) }

    HomeScreenLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is HomeAction.OnItemClick -> onItemClick(action.item)
                is HomeAction.OnLibraryClick -> onLibraryClick(action.library)
                is HomeAction.OnUserClick -> onUserClick()
                is HomeAction.OnManageServers -> onManageServers()
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreenLayout(
    state: HomeState,
    onAction: (HomeAction) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val safePadding = rememberSafePadding(handleStartInsets = false, handleBottomInsets = false)
    val castPadding = LocalCastPlayerHeight.current

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingTop = safePadding.top + MaterialTheme.spacings.small
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + castPadding

    val itemsPadding = PaddingValues(start = paddingStart, end = paddingEnd)

    val contentPaddingTop = safePadding.top + 88.dp

    var showErrorDialog by rememberSaveable { mutableStateOf(false) }
    val showServerSelectionSheetState = rememberModalBottomSheetState()
    var showServerSelectionBottomSheet by remember { mutableStateOf(false) }

    val isHomeEmpty =
        state.suggestionsSection == null &&
            state.resumeSection == null &&
            state.nextUpSection == null &&
            state.views.isEmpty()

    val rows =
        remember(state.suggestionsSection, state.resumeSection, state.nextUpSection, state.views) {
            buildList {
                state.suggestionsSection?.let { add(it) }
                state.resumeSection?.let { add(it) }
                state.nextUpSection?.let { add(it) }
                addAll(state.views.distinctBy { it.id })
            }
        }
    val listState = rememberLazyListStateAtStart(firstKey = rows.firstOrNull()?.id)

    var isManualRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) {
            isManualRefreshing = false
        }
    }

    val screenAlpha = remember { Animatable(0f) }
    val screenOffsetY = remember { Animatable(16f) }
    LaunchedEffect(Unit) {
        launch {
            screenAlpha.animateTo(
                targetValue = 1f,
                animationSpec =
                    tween(FindroidMotion.DURATION_MEDIUM, easing = FindroidMotion.EasingEnter),
            )
        }
        launch {
            screenOffsetY.animateTo(
                targetValue = 0f,
                animationSpec =
                    tween(FindroidMotion.DURATION_MEDIUM, easing = FindroidMotion.EasingEnter),
            )
        }
    }

    val pullRefreshState = rememberPullToRefreshState()

    Box(modifier = Modifier.fillMaxSize().semantics { isTraversalGroup = true }) {
        PullToRefreshBox(
            isRefreshing = isManualRefreshing && state.isLoading,
            onRefresh = {
                isManualRefreshing = true
                onAction(HomeAction.Refresh)
            },
            state = pullRefreshState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullRefreshState,
                    isRefreshing = isManualRefreshing && state.isLoading,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = contentPaddingTop),
                )
            },
        ) {
            LazyColumn(
                modifier =
                    Modifier.fillMaxSize()
                        .graphicsLayer {
                            alpha = screenAlpha.value
                            translationY = screenOffsetY.value * density
                        }
                        .semantics { traversalIndex = 1f },
                state = listState,
                contentPadding = PaddingValues(top = contentPaddingTop, bottom = paddingBottom),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            ) {
                items(rows, key = { it.id }, contentType = { it::class }) { row ->
                    when (row) {
                        is HomeItem.Suggestions -> {
                            HomeCarousel(
                                items = row.items,
                                itemsPadding = itemsPadding,
                                onAction = onAction,
                                modifier =
                                    Modifier.animateItem(
                                        placementSpec =
                                            tween(
                                                FindroidMotion.DURATION_MEDIUM,
                                                easing = FindroidMotion.EasingStandard,
                                            ),
                                    ),
                            )
                        }
                        is HomeItem.Section -> {
                            HomeSection(
                                section = row.homeSection,
                                itemsPadding = itemsPadding,
                                onAction = onAction,
                                modifier =
                                    Modifier.animateItem(
                                        placementSpec =
                                            tween(
                                                FindroidMotion.DURATION_MEDIUM,
                                                easing = FindroidMotion.EasingStandard,
                                            ),
                                        fadeOutSpec =
                                            tween(
                                                FindroidMotion.DURATION_SHORT,
                                                easing = FindroidMotion.EasingExit,
                                            ),
                                    ),
                                hideEpisodeSpoilers = state.hideEpisodeSpoilers,
                            )
                        }
                        is HomeItem.ViewItem -> {
                            HomeView(
                                view = row,
                                itemsPadding = itemsPadding,
                                onAction = onAction,
                                modifier =
                                    Modifier.animateItem(
                                        placementSpec =
                                            tween(
                                                FindroidMotion.DURATION_MEDIUM,
                                                easing = FindroidMotion.EasingStandard,
                                            ),
                                        fadeOutSpec =
                                            tween(
                                                FindroidMotion.DURATION_SHORT,
                                                easing = FindroidMotion.EasingExit,
                                            ),
                                    ),
                                hideEpisodeSpoilers = state.hideEpisodeSpoilers,
                            )
                        }
                        else -> Unit
                    }
                }
            }
        }

        if (isHomeEmpty && !state.isLoading && state.error != null) {
            PlaceholderScreen(
                modifier = Modifier.fillMaxSize().padding(top = contentPaddingTop),
                title = stringResource(CoreR.string.error_loading_data),
                subtitle = state.error?.message,
                buttonText = stringResource(CoreR.string.retry),
                onButtonClick = { onAction(HomeAction.OnRetryClick) },
            )
        }

        if (state.error != null && showErrorDialog) {
            ErrorDialog(exception = state.error!!, onDismissRequest = { showErrorDialog = false })
        }

        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .background(
                        brush =
                            Brush.verticalGradient(
                                colors =
                                    listOf(
                                        MaterialTheme.colorScheme.surface,
                                        Color.Transparent,
                                    ),
                            ),
                    )
                    .padding(start = paddingStart, top = paddingTop, end = paddingEnd)
                    .padding(bottom = MaterialTheme.spacings.small),
        ) {
            HomeHeader(
                serverName = state.server?.name ?: "",
                userName = state.userName,
                userImageUrl = state.userImageUrl,
                isLoading = state.isLoading,
                isError = state.error != null,
                onServerClick = { showServerSelectionBottomSheet = true },
                onErrorClick = { showErrorDialog = true },
                onRetryClick = { onAction(HomeAction.OnRetryClick) },
                onUserClick = { onAction(HomeAction.OnUserClick) },
            )
        }
    }

    if (showServerSelectionBottomSheet) {
        ServerSelectionBottomSheet(
            currentServerId = state.server?.id ?: "",
            onUpdate = {
                onAction(HomeAction.OnRetryClick)
                scope
                    .launch { showServerSelectionSheetState.hide() }
                    .invokeOnCompletion {
                        if (!showServerSelectionSheetState.isVisible) {
                            showServerSelectionBottomSheet = false
                        }
                    }
            },
            onManage = {
                onAction(HomeAction.OnManageServers)
                scope.launch { showServerSelectionSheetState.hide() }
            },
            onDismissRequest = { showServerSelectionBottomSheet = false },
            sheetState = showServerSelectionSheetState,
        )
    }
}

@PreviewScreenSizes
@Composable
private fun HomeScreenLayoutPreview() {
    FindroidTheme {
        HomeScreenLayout(
            state =
                HomeState(
                    server = dummyServer,
                    suggestionsSection = dummyHomeSuggestions,
                    resumeSection = dummyHomeSection,
                    views = listOf(dummyHomeView),
                    error = Exception("Failed to load data"),
                ),
            onAction = {},
        )
    }
}
