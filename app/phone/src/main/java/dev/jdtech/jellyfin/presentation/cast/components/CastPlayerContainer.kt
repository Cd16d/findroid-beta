package dev.jdtech.jellyfin.presentation.cast.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp as floatLerp
import androidx.core.net.toUri
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
import dev.jdtech.jellyfin.presentation.cast.rememberMiniPlayerThemeColors
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// -------------------------------------------------------------------------------------------------
// Morphing Shape & Motion
// -------------------------------------------------------------------------------------------------

/**
 * Shape that smoothly interpolates between a floating docked pill rect and full-screen bounds,
 * maintaining a linearly scaling corner radius directly proportional to expansion progress.
 */
class MorphingContainerShape(
    private val left: Float,
    private val top: Float,
    private val right: Float,
    private val bottom: Float,
    private val cornerRadiusPx: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val rect = Rect(left, top, right, bottom)
        return if (cornerRadiusPx > 0.5f) {
            Outline.Rounded(
                RoundRect(
                    rect = rect,
                    topLeft = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    topRight = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    bottomRight = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    bottomLeft = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                ),
            )
        } else {
            Outline.Rectangle(rect)
        }
    }
}

// -------------------------------------------------------------------------------------------------
// State & Controller
// -------------------------------------------------------------------------------------------------

/**
 * Controller class managing the smooth expansion, drag gestures, and state synchronization of the
 * shared phone player container between collapsed pill and full-screen expanded views.
 */
class CastPlayerSheetState(
    initialExpanded: Boolean,
    private val onExpandedChange: (Boolean) -> Unit,
    private val screenHeightPx: () -> Float,
    private val coroutineScope: CoroutineScope,
) {
    val animatable = Animatable(if (initialExpanded) 1f else 0f)

    var isDragging by mutableStateOf(false)

    val progress: Float
        get() = animatable.value

    fun drag(dragAmountY: Float) {
        isDragging = true
        val totalPx = screenHeightPx().coerceAtLeast(1f)
        val deltaFraction = -dragAmountY / totalPx
        coroutineScope.launch {
            val newProgress = (animatable.value + deltaFraction).coerceIn(0f, 1f)
            animatable.snapTo(newProgress)
        }
    }

    fun settle(velocityY: Float) {
        isDragging = false
        val target =
            when {
                velocityY > 250f -> 1f // Flicked UP -> expand
                velocityY < -250f -> 0f // Flicked DOWN -> collapse
                animatable.value > 0.5f -> 1f
                else -> 0f
            }
        coroutineScope.launch {
            if (target == 1f) {
                expandSoft()
            } else {
                collapseSoft()
            }
        }
    }

    fun expandSoft() {
        isDragging = false
        coroutineScope.launch {
            animatable.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
            onExpandedChange(true)
        }
    }

    fun collapseSoft() {
        isDragging = false
        coroutineScope.launch {
            animatable.animateTo(
                0f,
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
            onExpandedChange(false)
        }
    }
}

@Composable
fun rememberCastPlayerSheetState(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    screenHeightPx: () -> Float,
): CastPlayerSheetState {
    val coroutineScope = rememberCoroutineScope()
    val currentOnExpandedChange by rememberUpdatedState(onExpandedChange)
    val currentScreenHeightPx by rememberUpdatedState(screenHeightPx)
    return remember {
        CastPlayerSheetState(
            initialExpanded = isExpanded,
            onExpandedChange = { currentOnExpandedChange(it) },
            screenHeightPx = { currentScreenHeightPx() },
            coroutineScope = coroutineScope,
        )
    }
}

/**
 * Shared container transform container for Google Cast playback on phone devices. Seamlessly morphs
 * between a floating docked [MiniPlayerContent] pill and a full-screen [CastExpandedPlayerLayout]
 * on a single continuous container, eliminating modal scrims, dialog recreation, and visual
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
    isMediumScreen: Boolean = false,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val connectedDevice = uiState.connectedDevice ?: return
    val playbackState = uiState.playerState

    val onTogglePlayback = remember(viewModel) { { viewModel.togglePlayPause() } }

    var screenHeightPx by remember { mutableFloatStateOf(1000f) }

    val sheetState =
        rememberCastPlayerSheetState(
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
            screenHeightPx = { screenHeightPx },
        )

    LaunchedEffect(isExpanded) {
        if (!sheetState.isDragging) {
            if (isExpanded && sheetState.progress < 0.99f) {
                sheetState.expandSoft()
            } else if (!isExpanded && sheetState.progress > 0.01f) {
                sheetState.collapseSoft()
            }
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
                onClose = { sheetState.collapseSoft() },
                onDragCollapse = null,
                onDragCollapseEnd = null,
            )
        }

    CastPlayerContainerLayout(
        uiState = uiState,
        connectedDevice = connectedDevice,
        playbackState = playbackState,
        expansionFraction = sheetState.progress,
        actions = actions,
        onTogglePlayback = onTogglePlayback,
        onDrag = { dragY -> sheetState.drag(dragY) },
        onSettle = { velocity -> sheetState.settle(velocity) },
        onExpand = { sheetState.expandSoft() },
        onCollapse = { sheetState.collapseSoft() },
        onReportScreenHeightPx = { screenHeightPx = it },
        showNavSuite = showNavSuite,
        isNavRail = isNavRail,
        isMediumScreen = isMediumScreen,
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
    modifier: Modifier = Modifier,
    onDrag: (Float) -> Unit = {},
    onSettle: (Float) -> Unit = {},
    onExpand: () -> Unit = {},
    onCollapse: () -> Unit = {},
    onReportScreenHeightPx: (Float) -> Unit = {},
    showNavSuite: Boolean = true,
    isNavRail: Boolean = false,
    isMediumScreen: Boolean = false,
) {
    val fraction = expansionFraction.coerceIn(0f, 1f)

    BackHandler(enabled = fraction > 0.05f) { onCollapse() }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenHeight = maxHeight
        val screenWidth = maxWidth
        val density = LocalDensity.current

        val currentScreenHeightPx = with(density) { screenHeight.toPx() }
        LaunchedEffect(currentScreenHeightPx) { onReportScreenHeightPx(currentScreenHeightPx) }

        val safePadding = rememberSafePadding(handleBottomInsets = true)
        val collapsedHeight = 72.dp

        val navBarBottomOffset by
            animateDpAsState(
                targetValue =
                    if (showNavSuite && !isNavRail) {
                        64.dp + MaterialTheme.spacings.medium
                    } else {
                        safePadding.bottom + MaterialTheme.spacings.medium
                    },
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                label = "navBarBottomOffset",
            )

        val railWidth = if (isNavRail && showNavSuite) 80.dp else 0.dp
        val startRailPadding = safePadding.start + railWidth

        val collapsedHeightPx = with(density) { collapsedHeight.toPx() }
        val collapsedTopPx =
            with(density) { (screenHeight - navBarBottomOffset - collapsedHeight).toPx() }
        val collapsedBottomPx = collapsedTopPx + collapsedHeightPx

        val screenWidthPx = with(density) { screenWidth.toPx() }
        val screenHeightPx = with(density) { screenHeight.toPx() }

        val startPaddingPx =
            with(density) { (startRailPadding + MaterialTheme.spacings.medium).toPx() }
        val endPaddingPx =
            with(density) { (safePadding.end + MaterialTheme.spacings.medium).toPx() }
        val availableWidthPx = (screenWidthPx - startPaddingPx - endPaddingPx).coerceAtLeast(0f)
        val maxPillWidthPx = with(density) { 420.dp.toPx() }
        val pillWidthPx =
            if (isMediumScreen || isNavRail) {
                minOf(maxPillWidthPx, availableWidthPx)
            } else {
                availableWidthPx
            }
        // Anchor to bottom-right corner in landscape / medium screen
        val pillRightPx = screenWidthPx - endPaddingPx
        val pillLeftPx = pillRightPx - pillWidthPx

        // Interpolate position and shape directly between collapsed pill and full screen
        val currentLeftPx = floatLerp(pillLeftPx, 0f, fraction)
        val currentRightPx = floatLerp(pillRightPx, screenWidthPx, fraction)
        val currentTopPx = floatLerp(collapsedTopPx, 0f, fraction)
        val currentBottomPx = floatLerp(collapsedBottomPx, screenHeightPx, fraction)
        val currentRadiusPx = with(density) { (36.dp * (1f - fraction)).toPx() }
        val elevationDp = 8.dp * (1f - fraction)
        val borderAlpha = (1f - fraction).coerceIn(0f, 1f)

        val currentTopDp = with(density) { currentTopPx.toDp() }

        val themeColors = rememberMiniPlayerThemeColors(uiState.currentItemPoster?.blurHash)
        val outlineVariantColor = themeColors.colorScheme.outlineVariant

        val morphShape =
            remember(
                currentLeftPx,
                currentTopPx,
                currentRightPx,
                currentBottomPx,
                currentRadiusPx,
            ) {
                MorphingContainerShape(
                    left = currentLeftPx,
                    top = currentTopPx,
                    right = currentRightPx,
                    bottom = currentBottomPx,
                    cornerRadiusPx = currentRadiusPx,
                )
            }

        // Dim scrim layer behind the morphing container (fades in smoothly as progress increases)
        val scrimAlpha = (fraction * 1.5f).coerceIn(0f, 1f)
        if (scrimAlpha > 0f) {
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .graphicsLayer { alpha = scrimAlpha }
                        .background(Color.Black.copy(alpha = 0.35f)),
            )
        }

        val dragGestureModifier =
            Modifier.pointerInput(Unit) {
                val velocityTracker = VelocityTracker()
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        velocityTracker.addPointerInputChange(change)
                        onDrag(dragAmount)
                    },
                    onDragCancel = {
                        velocityTracker.resetTracking()
                        onCollapse()
                    },
                    onDragEnd = {
                        val velocityY = -velocityTracker.calculateVelocity().y
                        velocityTracker.resetTracking()
                        onSettle(velocityY)
                    },
                )
            }

        Box(
            modifier =
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        shape = morphShape
                        shadowElevation = with(density) { elevationDp.toPx() }
                        clip = true
                    }
                    .drawWithContent {
                        val outline = morphShape.createOutline(size, layoutDirection, this)
                        drawOutline(outline, themeColors.containerColor)
                        drawContent()
                        if (borderAlpha > 0.01f) {
                            drawOutline(
                                outline = outline,
                                color = outlineVariantColor.copy(alpha = 0.25f * borderAlpha),
                                style = Stroke(width = with(density) { 1.dp.toPx() * 2f }),
                            )
                        }
                    }
                    .then(dragGestureModifier),
        ) {
            // Expanded Player Content (reveals smoothly as container expands)
            if (fraction > 0.05f) {
                val expandedAlpha = ((fraction - 0.1f) / 0.9f).coerceIn(0f, 1f)
                if (expandedAlpha > 0f) {
                    Box(
                        modifier =
                            Modifier.fillMaxSize().graphicsLayer {
                                translationY = currentTopPx
                                alpha = expandedAlpha
                            },
                    ) {
                        CastExpandedPlayerLayout(
                            isExpandedScreen = false,
                            uiState = uiState,
                            actions = actions,
                            expansionFraction = fraction,
                            themeColors = themeColors,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // Collapsed Mini Player Pill Content (fades out as container expands)
            if (fraction < 0.5f) {
                val miniAlpha = (1f - fraction * 2f).coerceIn(0f, 1f)
                if (miniAlpha > 0f) {
                    val currentPillLeftDp = with(density) { currentLeftPx.toDp() }
                    val currentPillWidthDp =
                        with(density) { (currentRightPx - currentLeftPx).toDp() }

                    Box(
                        modifier =
                            Modifier.offset(x = currentPillLeftDp, y = currentTopDp)
                                .width(currentPillWidthDp)
                                .height(collapsedHeight)
                                .clip(CircleShape)
                                .graphicsLayer { alpha = miniAlpha }
                                .clickable(
                                    enabled = fraction <= 0.05f,
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = onExpand,
                                ),
                    ) {
                        MiniPlayerContent(
                            connectedDevice = connectedDevice,
                            uiState = uiState,
                            playbackState = playbackState,
                            onTogglePlayback = onTogglePlayback,
                            modifier = Modifier.fillMaxSize(),
                            themeColors = themeColors,
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
                uri = "https://example.com/poster.jpg".toUri(),
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
        onDragCollapse = null,
        onDragCollapseEnd = null,
    )

@Preview(name = "Phone Collapsed Pill", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun CastPlayerContainerCollapsedPreview() {
    FindroidTheme(darkTheme = true) {
        CastPlayerContainerLayout(
            uiState = mockUiState(),
            connectedDevice = mockDevice(),
            playbackState = CastPlayerState(status = CastPlaybackStatus.PLAYING),
            expansionFraction = 0f,
            actions = mockActions(),
            onTogglePlayback = {},
        )
    }
}

@Preview(name = "Phone Half Expanded", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun CastPlayerContainerHalfExpandedPreview() {
    FindroidTheme(darkTheme = true) {
        CastPlayerContainerLayout(
            uiState = mockUiState(),
            connectedDevice = mockDevice(),
            playbackState = CastPlayerState(status = CastPlaybackStatus.PLAYING),
            expansionFraction = 0.5f,
            actions = mockActions(),
            onTogglePlayback = {},
        )
    }
}

@Preview(name = "Phone Fully Expanded", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun CastPlayerContainerExpandedPreview() {
    FindroidTheme(darkTheme = true) {
        CastPlayerContainerLayout(
            uiState = mockUiState(),
            connectedDevice = mockDevice(),
            playbackState = CastPlayerState(status = CastPlaybackStatus.PLAYING),
            expansionFraction = 1f,
            actions = mockActions(),
            onTogglePlayback = {},
        )
    }
}

@Preview(
    name = "Phone Landscape Collapsed Pill",
    showBackground = true,
    widthDp = 891,
    heightDp = 411,
)
@Composable
private fun CastPlayerContainerLandscapePreview() {
    FindroidTheme(darkTheme = true) {
        CastPlayerContainerLayout(
            uiState = mockUiState(),
            connectedDevice = mockDevice(),
            playbackState = CastPlayerState(status = CastPlaybackStatus.PLAYING),
            expansionFraction = 0f,
            actions = mockActions(),
            onTogglePlayback = {},
            isNavRail = true,
            isMediumScreen = true,
        )
    }
}
