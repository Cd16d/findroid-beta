package dev.jdtech.jellyfin.presentation.cast.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Controller class managing the smooth expansion, drag gestures, and state synchronization of the
 * shared phone player container between collapsed pill and full-screen expanded views.
 */
class CastPlayerSheetState(
    initialExpanded: Boolean,
    private val onExpandedChange: (Boolean) -> Unit,
    private val screenHeightPx: () -> Float,
) {
    val animatable = Animatable(if (initialExpanded) 1f else 0f)

    val progress: Float
        get() = animatable.value

    suspend fun snapTo(target: Float) {
        animatable.snapTo(target.coerceIn(0f, 1f))
    }

    suspend fun drag(dragAmountY: Float) {
        val totalPx = screenHeightPx().coerceAtLeast(1f)
        val deltaFraction = -dragAmountY / totalPx
        val newProgress = (animatable.value + deltaFraction).coerceIn(0f, 1f)
        animatable.snapTo(newProgress)
    }

    suspend fun settle(velocity: Float) {
        val target =
            when {
                velocity < -500f -> 1f // Flicked UP -> expand
                velocity > 500f -> 0f // Flicked DOWN -> collapse
                animatable.value > 0.5f -> 1f
                else -> 0f
            }
        if (target == 1f) {
            onExpandedChange(true)
            animatable.animateTo(1f, tween(durationMillis = 280, easing = FastOutSlowInEasing))
            animatable.snapTo(1f)
        } else {
            onExpandedChange(false)
            animatable.animateTo(0f, tween(durationMillis = 250, easing = FastOutSlowInEasing))
            animatable.snapTo(0f)
        }
    }

    suspend fun expand() {
        onExpandedChange(true)
        animatable.animateTo(1f, tween(durationMillis = 300, easing = FastOutSlowInEasing))
        animatable.snapTo(1f)
    }

    suspend fun collapse() {
        onExpandedChange(false)
        animatable.animateTo(0f, tween(durationMillis = 250, easing = FastOutSlowInEasing))
        animatable.snapTo(0f)
    }
}

@Composable
fun rememberCastPlayerSheetState(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    screenHeightPx: () -> Float,
): CastPlayerSheetState {
    val currentOnExpandedChange by rememberUpdatedState(onExpandedChange)
    val currentScreenHeightPx by rememberUpdatedState(screenHeightPx)
    return remember {
        CastPlayerSheetState(
            initialExpanded = isExpanded,
            onExpandedChange = { currentOnExpandedChange(it) },
            screenHeightPx = { currentScreenHeightPx() },
        )
    }
}

/**
 * Shared container transform container for Google Cast playback on phone devices. Seamlessly morphs
 * between a floating docked [MiniPlayerContent] pill and a full-screen [CastExpandedPlayerLayout]
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
    var screenHeightPx by remember { mutableFloatStateOf(1000f) }

    val sheetState =
        rememberCastPlayerSheetState(
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
            screenHeightPx = { screenHeightPx },
        )

    LaunchedEffect(isExpanded) {
        if (isExpanded && sheetState.progress < 0.99f) {
            sheetState.expand()
        } else if (!isExpanded && sheetState.progress > 0.01f) {
            sheetState.collapse()
        }
    }

    val actions =
        remember(viewModel, onDeviceClick, sheetState) {
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
                onClose = { coroutineScope.launch { sheetState.collapse() } },
                onDragCollapse = { dragY -> coroutineScope.launch { sheetState.drag(dragY) } },
                onDragCollapseEnd = { velocity ->
                    coroutineScope.launch { sheetState.settle(velocity) }
                },
            )
        }

    CastPlayerContainerLayout(
        uiState = uiState,
        connectedDevice = connectedDevice,
        playbackState = playbackState,
        expansionFraction = sheetState.progress,
        actions = actions,
        onTogglePlayback = onTogglePlayback,
        onExpandedChange = onExpandedChange,
        onDrag = { dragY -> coroutineScope.launch { sheetState.drag(dragY) } },
        onSettle = { velocity -> coroutineScope.launch { sheetState.settle(velocity) } },
        onExpand = { coroutineScope.launch { sheetState.expand() } },
        onCollapse = { coroutineScope.launch { sheetState.collapse() } },
        onReportScreenHeightPx = { screenHeightPx = it },
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
    modifier: Modifier = Modifier,
    onDrag: (Float) -> Unit = {},
    onSettle: (Float) -> Unit = {},
    onExpand: () -> Unit = {},
    onCollapse: () -> Unit = {},
    onReportScreenHeightPx: (Float) -> Unit = {},
    showNavSuite: Boolean = true,
    isNavRail: Boolean = false,
    onHeightChanged: (Dp) -> Unit = {},
) {
    val fraction = expansionFraction.coerceIn(0f, 1f)

    BackHandler(enabled = fraction > 0.05f) { onCollapse() }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenHeight = maxHeight
        val density = LocalDensity.current

        val currentScreenHeightPx = with(density) { screenHeight.toPx() }
        LaunchedEffect(currentScreenHeightPx) { onReportScreenHeightPx(currentScreenHeightPx) }

        val safePadding = rememberSafePadding(handleBottomInsets = !showNavSuite)

        val collapsedHeight = 64.dp
        val collapsedBottomOffset =
            if (showNavSuite && !isNavRail) {
                MaterialTheme.spacings.medium
            } else {
                safePadding.bottom + MaterialTheme.spacings.medium
            }

        val collapsedHorizontalPadding = safePadding.start + MaterialTheme.spacings.medium

        val currentHeight = lerp(collapsedHeight, screenHeight, fraction)
        val currentBottomPadding = lerp(collapsedBottomOffset, 0.dp, fraction)
        val currentStartPadding = lerp(collapsedHorizontalPadding, 0.dp, fraction)
        val currentEndPadding = lerp(collapsedHorizontalPadding, 0.dp, fraction)
        val currentCornerRadius = lerp(32.dp, 0.dp, fraction)
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
            border =
                if (fraction < 0.5f) {
                    BorderStroke(
                        width = 1.dp,
                        color =
                            MaterialTheme.colorScheme.outlineVariant.copy(
                                alpha = 0.25f * (1f - fraction * 2f),
                            ),
                    )
                } else {
                    null
                },
        ) {
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .then(
                            if (fraction < 0.2f) {
                                Modifier.pointerInput(Unit) {
                                    val velocityTracker = VelocityTracker()
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        velocityTracker.resetTracking()
                                        velocityTracker.addPosition(
                                            down.uptimeMillis,
                                            down.position,
                                        )
                                        var totalDrag = 0f
                                        var isDragging = false

                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change =
                                                event.changes.firstOrNull { it.id == down.id }
                                                    ?: break

                                            if (change.changedToUp()) {
                                                if (!isDragging) {
                                                    onExpand()
                                                } else {
                                                    val velocity =
                                                        velocityTracker.calculateVelocity().y
                                                    onSettle(velocity)
                                                }
                                                break
                                            }

                                            if (change.isConsumed) break

                                            val dragY =
                                                change.position.y - change.previousPosition.y
                                            totalDrag += dragY
                                            velocityTracker.addPosition(
                                                change.uptimeMillis,
                                                change.position,
                                            )

                                            if (
                                                !isDragging &&
                                                    abs(totalDrag) > viewConfiguration.touchSlop
                                            ) {
                                                isDragging = true
                                            }

                                            if (isDragging) {
                                                change.consume()
                                                onDrag(dragY)
                                            }
                                        }
                                    }
                                }
                            } else {
                                Modifier
                            },
                        ),
            ) {
                // Collapsed Mini Player Pill Content
                if (fraction < 0.4f) {
                    val miniAlpha = (1f - (fraction / 0.25f)).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = miniAlpha },
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
                if (fraction > 0.1f) {
                    val expandedAlpha = ((fraction - 0.15f) / 0.85f).coerceIn(0f, 1f)
                    val translationYPx = with(density) { ((1f - fraction) * 40.dp.toPx()) }
                    val contentScale = 0.94f + 0.06f * fraction

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

@Preview(name = "Pill Collapsed - Light", showBackground = true)
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
        )
    }
}

@Preview(name = "Pill Collapsed - Dark", showBackground = true)
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
        )
    }
}
