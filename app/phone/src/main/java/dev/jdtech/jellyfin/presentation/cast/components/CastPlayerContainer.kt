package dev.jdtech.jellyfin.presentation.cast.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.player.cast.models.CastPlaybackStatus
import dev.jdtech.jellyfin.player.cast.models.CastPlayerState
import dev.jdtech.jellyfin.player.cast.models.Device
import dev.jdtech.jellyfin.player.cast.presentation.CastPlayerViewModel
import dev.jdtech.jellyfin.player.core.domain.models.PlayerImage
import dev.jdtech.jellyfin.presentation.cast.CastExpandedPlayerLayout
import dev.jdtech.jellyfin.presentation.cast.CastPlayerActions
import dev.jdtech.jellyfin.presentation.cast.MiniPlayerContent
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import kotlinx.coroutines.launch

/**
 * Shared container transform container for Google Cast playback on phone devices. Seamlessly morphs
 * between a floating docked [MiniPlayerContent] card and a full-screen [CastExpandedPlayerLayout]
 * on a single continuous [Surface], eliminating modal scrims, dialog recreation, and visual
 * flicker.
 */
@Composable
fun CastPlayerContainer(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onDeviceClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CastPlayerViewModel = hiltViewModel(),
    showNavSuite: Boolean = true,
    isNavRail: Boolean = false,
    onHeightChanged: (Dp) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val connectedDevice = uiState.connectedDevice ?: return
    val playbackState = uiState.playerState

    val onTogglePlayback = remember(viewModel) { { viewModel.togglePlayPause() } }

    val coroutineScope = rememberCoroutineScope()
    val expansionProgress = remember { Animatable(if (isExpanded) 1f else 0f) }

    LaunchedEffect(isExpanded) {
        val target = if (isExpanded) 1f else 0f
        if (expansionProgress.targetValue != target) {
            expansionProgress.animateTo(
                targetValue = target,
                animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
            )
        }
    }

    val onDragCollapse: (Float) -> Unit = { dragAmount ->
        coroutineScope.launch {
            val totalPx = 800f
            val deltaFraction = dragAmount / totalPx
            expansionProgress.snapTo((expansionProgress.value - deltaFraction).coerceIn(0f, 1f))
        }
    }

    val onDragCollapseEnd: () -> Unit = {
        coroutineScope.launch {
            if (expansionProgress.value < 0.75f) {
                onExpandedChange(false)
                expansionProgress.animateTo(
                    0f,
                    tween(durationMillis = 250, easing = FastOutSlowInEasing),
                )
            } else {
                expansionProgress.animateTo(
                    1f,
                    tween(durationMillis = 250, easing = FastOutSlowInEasing),
                )
            }
        }
    }

    val actions =
        remember(viewModel, onDeviceClick, onExpandedChange) {
            CastPlayerActions(
                onPlay = { viewModel.play() },
                onPause = { viewModel.pause() },
                onSeek = { viewModel.seekTo(it) },
                onPlayPreviousItem = { viewModel.rewindOrPlayPreviousItem() },
                onPlayNextItem = { viewModel.playNextItem() },
                onSkipSegment = { viewModel.skipSegment(it) },
                onVolumeChange = { viewModel.setVolume(it) },
                onAudioTrackSelected = { viewModel.onAudioTrackSelected(it) },
                onSubtitleTrackSelected = { viewModel.onSubtitleTrackSelected(it) },
                onDeviceClick = onDeviceClick,
                onClose = { onExpandedChange(false) },
                onDragCollapse = onDragCollapse,
                onDragCollapseEnd = onDragCollapseEnd,
            )
        }

    CastPlayerContainerLayout(
        uiState = uiState,
        connectedDevice = connectedDevice,
        playbackState = playbackState,
        expansionFraction = expansionProgress.value,
        actions = actions,
        onTogglePlayback = onTogglePlayback,
        onExpandedChange = onExpandedChange,
        onSnapExpansion = { fraction ->
            coroutineScope.launch { expansionProgress.snapTo(fraction) }
        },
        onAnimateExpansion = { target ->
            coroutineScope.launch {
                expansionProgress.animateTo(
                    target,
                    tween(durationMillis = 300, easing = FastOutSlowInEasing),
                )
            }
        },
        showNavSuite = showNavSuite,
        isNavRail = isNavRail,
        onHeightChanged = onHeightChanged,
        modifier = modifier,
    )
}

@Composable
fun CastPlayerContainerLayout(
    uiState: CastPlayerViewModel.UiState,
    connectedDevice: Device,
    playbackState: CastPlayerState,
    expansionFraction: Float,
    actions: CastPlayerActions,
    onTogglePlayback: () -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onSnapExpansion: (Float) -> Unit,
    onAnimateExpansion: (Float) -> Unit,
    modifier: Modifier = Modifier,
    showNavSuite: Boolean = true,
    isNavRail: Boolean = false,
    onHeightChanged: (Dp) -> Unit = {},
) {
    BackHandler(enabled = expansionFraction > 0.05f) { onExpandedChange(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenHeight = maxHeight
        val density = LocalDensity.current

        val safePadding = rememberSafePadding(handleBottomInsets = false)
        val systemBottomPadding =
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        val navBarHeight = if (showNavSuite && !isNavRail) 80.dp else 0.dp
        val collapsedBottomOffset =
            systemBottomPadding + navBarHeight + MaterialTheme.spacings.medium
        val animatedCollapsedBottomOffset by
            animateDpAsState(
                targetValue = collapsedBottomOffset,
                animationSpec = tween(durationMillis = 250),
                label = "collapsedBottomOffset",
            )

        val collapsedHorizontalPadding = safePadding.start + MaterialTheme.spacings.medium
        val collapsedHeight = 80.dp

        val fraction = expansionFraction.coerceIn(0f, 1f)

        val currentHeight = lerp(collapsedHeight, screenHeight, fraction)
        val currentBottomPadding = lerp(animatedCollapsedBottomOffset, 0.dp, fraction)
        val currentStartPadding = lerp(collapsedHorizontalPadding, 0.dp, fraction)
        val currentEndPadding = lerp(collapsedHorizontalPadding, 0.dp, fraction)
        val currentCornerRadius = lerp(16.dp, 0.dp, fraction)
        val currentElevation = lerp(8.dp, 0.dp, fraction)
        val currentContainerColor =
            lerp(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                MaterialTheme.colorScheme.background,
                fraction,
            )

        Surface(
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .padding(
                        start = currentStartPadding,
                        end = currentEndPadding,
                        bottom = currentBottomPadding,
                    )
                    .fillMaxWidth()
                    .height(currentHeight)
                    .onSizeChanged { size ->
                        if (fraction == 0f) {
                            val h = with(density) { size.height.toDp() }
                            onHeightChanged(h)
                        }
                    },
            shape = RoundedCornerShape(currentCornerRadius),
            color = currentContainerColor,
            shadowElevation = currentElevation,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Collapsed Mini Player Content
                if (fraction < 0.35f) {
                    val miniAlpha = (1f - (fraction / 0.25f)).coerceIn(0f, 1f)
                    Box(
                        modifier =
                            Modifier.fillMaxSize()
                                .graphicsLayer { alpha = miniAlpha }
                                .then(
                                    if (fraction == 0f) {
                                        Modifier.clickable { onExpandedChange(true) }
                                    } else {
                                        Modifier
                                    },
                                )
                                .pointerInput(Unit) {
                                    detectVerticalDragGestures(
                                        onDragEnd = {
                                            if (fraction > 0.25f) {
                                                onExpandedChange(true)
                                                onAnimateExpansion(1f)
                                            } else {
                                                onAnimateExpansion(0f)
                                            }
                                        },
                                        onVerticalDrag = { _, dragAmount ->
                                            val totalPx = with(density) { screenHeight.toPx() }
                                            val deltaFraction = -dragAmount / totalPx
                                            onSnapExpansion(
                                                (fraction + deltaFraction).coerceIn(0f, 1f),
                                            )
                                        },
                                    )
                                },
                    ) {
                        MiniPlayerContent(
                            connectedDevice = connectedDevice,
                            uiState = uiState,
                            playbackState = playbackState,
                            onTogglePlayback = onTogglePlayback,
                            isMediumScreen = false,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // Expanded Player Content
                if (fraction > 0.15f) {
                    val expandedAlpha = ((fraction - 0.2f) / 0.8f).coerceIn(0f, 1f)
                    val translationYPx = with(density) { ((1f - fraction) * 50.dp.toPx()) }
                    val contentScale = 0.92f + 0.08f * fraction

                    Box(
                        modifier =
                            Modifier.fillMaxSize().graphicsLayer {
                                alpha = expandedAlpha
                                translationY = translationYPx
                                scaleX = contentScale
                                scaleY = contentScale
                            },
                    ) {
                        CastExpandedPlayerLayout(
                            isExpandedScreen = false,
                            uiState = uiState,
                            actions = actions,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Previews
// -------------------------------------------------------------------------------------------------

private fun mockDevice() =
    Device(id = "cast_1", name = "Living Room TV", enabled = true, supportsH265 = true)

private fun mockUiState(isEpisode: Boolean = true): CastPlayerViewModel.UiState {
    val item = if (isEpisode) dummyEpisode else dummyMovie
    return CastPlayerViewModel.UiState(
        connectedDevice = mockDevice(),
        fileLoaded = true,
        currentItemTitle =
            if (isEpisode) {
                CastPlayerViewModel.CurrentItemTitle(
                    seriesName = "Silo",
                    title = item.name,
                    episodeInfo = "S01E01",
                )
            } else {
                CastPlayerViewModel.CurrentItemTitle(title = item.name)
            },
        currentItemPoster =
            PlayerImage(
                uri = android.net.Uri.parse("https://example.com/poster.jpg"),
                blurHash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj",
            ),
        playerState =
            CastPlayerState(
                status = CastPlaybackStatus.PLAYING,
                currentPosition = 1250000L,
                duration = 3600000L,
            ),
    )
}

private fun mockActions() =
    CastPlayerActions(
        onPlay = {},
        onPause = {},
        onSeek = {},
        onPlayPreviousItem = {},
        onPlayNextItem = {},
        onSkipSegment = {},
        onVolumeChange = {},
        onAudioTrackSelected = {},
        onSubtitleTrackSelected = {},
        onDeviceClick = {},
        onClose = {},
    )

@Preview(name = "Collapsed - Light", showBackground = true)
@Composable
private fun CastPlayerContainerCollapsedLightPreview() {
    FindroidTheme(darkTheme = false) {
        CastPlayerContainerLayout(
            uiState = mockUiState(),
            connectedDevice = mockDevice(),
            playbackState = mockUiState().playerState,
            expansionFraction = 0f,
            actions = mockActions(),
            onTogglePlayback = {},
            onExpandedChange = {},
            onSnapExpansion = {},
            onAnimateExpansion = {},
        )
    }
}

@Preview(name = "Collapsed - Dark", showBackground = true)
@Composable
private fun CastPlayerContainerCollapsedDarkPreview() {
    FindroidTheme(darkTheme = true) {
        CastPlayerContainerLayout(
            uiState = mockUiState(),
            connectedDevice = mockDevice(),
            playbackState = mockUiState().playerState,
            expansionFraction = 0f,
            actions = mockActions(),
            onTogglePlayback = {},
            onExpandedChange = {},
            onSnapExpansion = {},
            onAnimateExpansion = {},
        )
    }
}

@Preview(name = "Expanded - Light", showBackground = true)
@Composable
private fun CastPlayerContainerExpandedLightPreview() {
    FindroidTheme(darkTheme = false) {
        CastPlayerContainerLayout(
            uiState = mockUiState(),
            connectedDevice = mockDevice(),
            playbackState = mockUiState().playerState,
            expansionFraction = 1f,
            actions = mockActions(),
            onTogglePlayback = {},
            onExpandedChange = {},
            onSnapExpansion = {},
            onAnimateExpansion = {},
        )
    }
}

@Preview(name = "Expanded - Dark", showBackground = true)
@Composable
private fun CastPlayerContainerExpandedDarkPreview() {
    FindroidTheme(darkTheme = true) {
        CastPlayerContainerLayout(
            uiState = mockUiState(),
            connectedDevice = mockDevice(),
            playbackState = mockUiState().playerState,
            expansionFraction = 1f,
            actions = mockActions(),
            onTogglePlayback = {},
            onExpandedChange = {},
            onSnapExpansion = {},
            onAnimateExpansion = {},
        )
    }
}
