package dev.jdtech.jellyfin.presentation.cast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import dev.jdtech.jellyfin.CollectionRoute
import dev.jdtech.jellyfin.EpisodeRoute
import dev.jdtech.jellyfin.FavoritesRoute
import dev.jdtech.jellyfin.HomeRoute
import dev.jdtech.jellyfin.LibraryRoute
import dev.jdtech.jellyfin.LocalCastPlayerHeight
import dev.jdtech.jellyfin.MovieRoute
import dev.jdtech.jellyfin.PersonRoute
import dev.jdtech.jellyfin.SeasonRoute
import dev.jdtech.jellyfin.ShowRoute
import dev.jdtech.jellyfin.player.cast.models.CastConnectionState
import dev.jdtech.jellyfin.player.cast.presentation.CastSessionViewModel
import dev.jdtech.jellyfin.presentation.cast.components.CastBottomSheet
import dev.jdtech.jellyfin.presentation.cast.components.CastButton
import dev.jdtech.jellyfin.presentation.cast.components.CastPlayerContainer
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.viewmodels.MainViewModel

/**
 * Scope exposed by [CastNavigationHost] so the inner navigation scaffold can embed the Cast FAB,
 * tablet side panel, and tablet mini player without holding any Cast state itself.
 */
@Stable
interface CastNavigationScope {
    /**
     * Wraps the primary navigation display with the floating [CastButton], tablet side-by-side
     * [CastExpandedPlayer], and tablet [CastMiniPlayer].
     */
    @Composable
    fun CastContentLayout(
        modifier: Modifier,
        content: @Composable () -> Unit,
    )
}

/**
 * Stable snapshot-state holder for [CastNavigationScope]. Using [mutableStateOf] fields on a single
 * remembered instance guarantees [CastContentLayout] recomposes directly when Cast readiness or
 * connection state changes, even when intermediate scaffolds (such as `NavigationSuiteScaffold`)
 * skip recomposition.
 */
@Stable
private class CastNavigationState(
    initialShowCastButton: Boolean,
    initialIsCastButtonExpanded: Boolean,
    initialShowNavSuite: Boolean,
    initialShowTabletExpandedPlayer: Boolean,
    initialIsExpandedScreen: Boolean,
    initialShowTabletMiniPlayer: Boolean,
    private val onCastButtonClick: () -> Unit,
    private val onOpenExpandedPlayer: () -> Unit,
    private val onCloseExpandedPlayer: () -> Unit,
    private val scrollConnection: NestedScrollConnection,
) : CastNavigationScope {
    var showCastButton by mutableStateOf(initialShowCastButton)
    var isCastButtonExpanded by mutableStateOf(initialIsCastButtonExpanded)
    var showNavSuite by mutableStateOf(initialShowNavSuite)
    var showTabletExpandedPlayer by mutableStateOf(initialShowTabletExpandedPlayer)
    var isExpandedScreen by mutableStateOf(initialIsExpandedScreen)
    var showTabletMiniPlayer by mutableStateOf(initialShowTabletMiniPlayer)

    @Composable
    override fun CastContentLayout(
        modifier: Modifier,
        content: @Composable () -> Unit,
    ) {
        CastContentLayoutInternal(
            showCastButton = showCastButton,
            isCastButtonExpanded = isCastButtonExpanded,
            showNavSuite = showNavSuite,
            showTabletExpandedPlayer = showTabletExpandedPlayer,
            isExpandedScreen = isExpandedScreen,
            showTabletMiniPlayer = showTabletMiniPlayer,
            onCastButtonClick = onCastButtonClick,
            onOpenExpandedPlayer = onOpenExpandedPlayer,
            onCloseExpandedPlayer = onCloseExpandedPlayer,
            modifier = modifier.nestedScroll(scrollConnection),
            content = content,
        )
    }
}

/**
 * Navigation-level wrapper that owns all Google Cast UI state, recomposition scope,
 * [LocalCastPlayerHeight] provision, bottom sheet, and phone/tablet player overlays.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastNavigationHost(
    currentRoute: NavKey?,
    isOfflineMode: Boolean,
    isExpandedScreen: Boolean,
    isMediumScreen: Boolean,
    isNavRail: Boolean,
    showNavSuite: Boolean,
    isAccountMenuOpen: Boolean,
    content: @Composable CastNavigationScope.() -> Unit,
) {
    val context = LocalContext.current
    val viewModelStoreOwner = context as ViewModelStoreOwner
    val mainViewModel: MainViewModel = hiltViewModel(viewModelStoreOwner)
    val castSessionViewModel: CastSessionViewModel = hiltViewModel(viewModelStoreOwner)

    var showCastSheet by remember { mutableStateOf(false) }
    var showCastExpandedPlayer by remember { mutableStateOf(false) }
    var isCastButtonExpanded by remember { mutableStateOf(true) }

    LaunchedEffect(currentRoute) { isCastButtonExpanded = true }

    LaunchedEffect(showCastExpandedPlayer) {
        mainViewModel.setCastPlayerOpen(showCastExpandedPlayer)
    }

    val connectionState by castSessionViewModel.connectionState.collectAsStateWithLifecycle()
    val isCastReady by castSessionViewModel.sessionManager.isCastReady.collectAsStateWithLifecycle()
    val connectedDevice by
        castSessionViewModel.sessionManager.connectedDevice.collectAsStateWithLifecycle()

    LaunchedEffect(connectedDevice?.id) { showCastExpandedPlayer = false }

    LaunchedEffect(connectionState) {
        if (connectionState != CastConnectionState.CONNECTED) {
            showCastExpandedPlayer = false
        }
    }

    val isCastSupportedRoute = isCastSupportedRoute(currentRoute)

    val showCastButton =
        isCastReady &&
            isCastSupportedRoute &&
            !isOfflineMode &&
            castSessionViewModel.sessionManager.isSupported

    val showCastPlayerPhone by
        remember(
            connectionState,
            isExpandedScreen,
            isCastSupportedRoute,
            showCastExpandedPlayer,
        ) {
            derivedStateOf {
                connectionState == CastConnectionState.CONNECTED &&
                    !isExpandedScreen &&
                    (isCastSupportedRoute || showCastExpandedPlayer)
            }
        }

    val showCastMiniPlayer by
        remember(
            connectionState,
            showCastExpandedPlayer,
            isExpandedScreen,
            isCastSupportedRoute,
        ) {
            derivedStateOf {
                val connected = connectionState == CastConnectionState.CONNECTED
                connected && isExpandedScreen && !showCastExpandedPlayer && isCastSupportedRoute
            }
        }

    val castPlayerHeight by
        remember(showCastPlayerPhone, showCastExpandedPlayer, isMediumScreen) {
            derivedStateOf {
                if (showCastPlayerPhone && !showCastExpandedPlayer && !isMediumScreen) {
                    // Collapsed pill (72.dp) + bottom spacing (16.dp) + matching top spacing
                    // (16.dp)
                    104.dp
                } else {
                    MaterialTheme.spacings.default
                }
            }
        }

    val castScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (consumed.y < -15f) {
                    isCastButtonExpanded = false
                } else if (consumed.y > 15f || available.y > 15f) {
                    isCastButtonExpanded = true
                }
                return Offset.Zero
            }
        }
    }

    val effectiveShowCastButton = showCastButton && connectionState != CastConnectionState.CONNECTED
    val effectiveShowTabletExpandedPlayer = showCastExpandedPlayer && isExpandedScreen

    val scope = remember {
        CastNavigationState(
            initialShowCastButton = effectiveShowCastButton,
            initialIsCastButtonExpanded = isCastButtonExpanded,
            initialShowNavSuite = showNavSuite,
            initialShowTabletExpandedPlayer = effectiveShowTabletExpandedPlayer,
            initialIsExpandedScreen = isExpandedScreen,
            initialShowTabletMiniPlayer = showCastMiniPlayer,
            onCastButtonClick = { showCastSheet = true },
            onOpenExpandedPlayer = { showCastExpandedPlayer = true },
            onCloseExpandedPlayer = { showCastExpandedPlayer = false },
            scrollConnection = castScrollConnection,
        )
    }

    SideEffect {
        if (scope.showCastButton != effectiveShowCastButton) {
            scope.showCastButton = effectiveShowCastButton
        }
        if (scope.isCastButtonExpanded != isCastButtonExpanded) {
            scope.isCastButtonExpanded = isCastButtonExpanded
        }
        if (scope.showNavSuite != showNavSuite) {
            scope.showNavSuite = showNavSuite
        }
        if (scope.showTabletExpandedPlayer != effectiveShowTabletExpandedPlayer) {
            scope.showTabletExpandedPlayer = effectiveShowTabletExpandedPlayer
        }
        if (scope.isExpandedScreen != isExpandedScreen) {
            scope.isExpandedScreen = isExpandedScreen
        }
        if (scope.showTabletMiniPlayer != showCastMiniPlayer) {
            scope.showTabletMiniPlayer = showCastMiniPlayer
        }
    }

    CompositionLocalProvider(LocalCastPlayerHeight provides castPlayerHeight) {
        Box(modifier = Modifier.fillMaxSize()) {
            scope.content()

            if (showCastSheet) {
                CastBottomSheet(onDismissRequest = { showCastSheet = false })
            }

            // Phone Cast Player (Morphing Shared Container Transform)
            if (!isExpandedScreen) {
                AnimatedVisibility(
                    visible = showCastPlayerPhone,
                    enter = fadeIn(animationSpec = tween(durationMillis = 300)),
                    exit = fadeOut(animationSpec = tween(durationMillis = 200)),
                ) {
                    CastPlayerContainer(
                        isExpanded = showCastExpandedPlayer,
                        onExpandedChange = { expanded -> showCastExpandedPlayer = expanded },
                        onDeviceClick = { showCastSheet = true },
                        showNavSuite = showNavSuite,
                        isNavRail = isNavRail,
                        isMediumScreen = isMediumScreen,
                        modifier = Modifier.zIndex(if (isAccountMenuOpen) 0f else 2f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CastContentLayoutInternal(
    showCastButton: Boolean,
    isCastButtonExpanded: Boolean,
    showNavSuite: Boolean,
    showTabletExpandedPlayer: Boolean,
    isExpandedScreen: Boolean,
    showTabletMiniPlayer: Boolean,
    onCastButtonClick: () -> Unit,
    onOpenExpandedPlayer: () -> Unit,
    onCloseExpandedPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight().then(modifier)) {
                content()

                if (showCastButton) {
                    CastButton(
                        expanded = isCastButtonExpanded,
                        onClick = onCastButtonClick,
                        modifier = Modifier.align(Alignment.BottomEnd),
                        handleBottomInsets = !showNavSuite,
                    )
                }
            }

            AnimatedVisibility(
                visible = showTabletExpandedPlayer,
                enter =
                    slideInHorizontally(initialOffsetX = { it }) +
                        expandHorizontally(expandFrom = Alignment.End) +
                        fadeIn(),
                exit =
                    slideOutHorizontally(targetOffsetX = { it }) +
                        shrinkHorizontally(shrinkTowards = Alignment.End) +
                        fadeOut(),
            ) {
                CastExpandedPlayer(
                    onDeviceClick = onCastButtonClick,
                    onClose = onCloseExpandedPlayer,
                )
            }
        }

        // Tablet Cast Mini Player
        if (isExpandedScreen) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomEnd,
            ) {
                AnimatedVisibility(
                    visible = showTabletMiniPlayer,
                    enter = fadeIn(animationSpec = tween(durationMillis = 300)),
                    exit = fadeOut(animationSpec = tween(durationMillis = 200)),
                ) {
                    CastMiniPlayer(
                        onClick = onOpenExpandedPlayer,
                        handleBottomInsets = !showNavSuite,
                    )
                }
            }
        }
    }
}

private fun isCastSupportedRoute(route: NavKey?): Boolean =
    when (route) {
        is HomeRoute,
        is MovieRoute,
        is ShowRoute,
        is SeasonRoute,
        is EpisodeRoute,
        is PersonRoute,
        is LibraryRoute,
        is CollectionRoute,
        is FavoritesRoute -> true
        else -> false
    }

@Preview(showBackground = true)
@Composable
private fun CastContentLayoutExpandedButtonPreview() {
    FindroidTheme(darkTheme = true) {
        CastContentLayoutInternal(
            showCastButton = true,
            isCastButtonExpanded = true,
            showNavSuite = true,
            showTabletExpandedPlayer = false,
            isExpandedScreen = false,
            showTabletMiniPlayer = false,
            onCastButtonClick = {},
            onOpenExpandedPlayer = {},
            onCloseExpandedPlayer = {},
        ) {
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Main Navigation Content",
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CastContentLayoutCollapsedButtonPreview() {
    FindroidTheme(darkTheme = true) {
        CastContentLayoutInternal(
            showCastButton = true,
            isCastButtonExpanded = false,
            showNavSuite = false,
            showTabletExpandedPlayer = false,
            isExpandedScreen = false,
            showTabletMiniPlayer = false,
            onCastButtonClick = {},
            onOpenExpandedPlayer = {},
            onCloseExpandedPlayer = {},
        ) {
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Scrolled Content",
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}
