package dev.jdtech.jellyfin.presentation.cast.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.cast.models.Device
import dev.jdtech.jellyfin.player.cast.presentation.CastPlayerViewModel
import dev.jdtech.jellyfin.player.core.domain.models.PlayerImage
import dev.jdtech.jellyfin.player.core.domain.models.Trickplay
import dev.jdtech.jellyfin.presentation.cast.MiniPlayerThemeColors
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.utils.toBlurHashPainter
import dev.jdtech.jellyfin.utils.toOptimizedImageUri
import kotlin.math.abs

/**
 * Top artwork and header section of expanded Google Cast player.
 *
 * @param uiState Cast player UI state model.
 * @param isScrubbing Whether seeker bar is being dragged.
 * @param scrubPosition Current scrub drag position in ms.
 * @param onClose Callback to close/collapse expanded player.
 * @param onDeviceClick Callback when device button is tapped.
 * @param modifier Modifier applied to layout. Defaults to [Modifier].
 */
@Composable
fun PlayerTopSection(
    uiState: CastPlayerViewModel.UiState,
    isScrubbing: Boolean,
    scrubPosition: Float,
    onClose: () -> Unit,
    onDeviceClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDragCollapse: ((Float) -> Unit)? = null,
    onDragCollapseEnd: ((Float) -> Unit)? = null,
    isExpandedScreen: Boolean = false,
    themeColors: MiniPlayerThemeColors? = null,
) {
    val deviceName = uiState.connectedDevice?.name
    val itemTitle =
        remember(uiState.currentItemTitle) {
            val titleInfo = uiState.currentItemTitle
            if (titleInfo.seriesName != null) {
                "${titleInfo.seriesName} - ${titleInfo.title}"
            } else {
                titleInfo.title
            }
        }

    val trickplay = uiState.currentTrickplay
    val computedAspectRatio =
        if (isScrubbing && trickplay != null) {
            uiState.trickplayAspectRatio ?: uiState.defaultAspectRatio
        } else {
            uiState.defaultAspectRatio
        }
    val safeAspectRatio =
        if (computedAspectRatio.isFinite() && computedAspectRatio > 0f) {
            computedAspectRatio
        } else {
            16f / 10f
        }

    val dragModifier =
        if (onDragCollapse != null) {
            Modifier.pointerInput(isScrubbing) {
                if (!isScrubbing) {
                    val velocityTracker = VelocityTracker()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        velocityTracker.resetTracking()
                        velocityTracker.addPosition(down.uptimeMillis, down.position)
                        var isDragging = false
                        var totalDrag = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUp()) {
                                if (isDragging) {
                                    val velocity = velocityTracker.calculateVelocity().y
                                    onDragCollapseEnd?.invoke(velocity)
                                }
                                break
                            }
                            if (change.isConsumed) break
                            val dragY = change.position.y - change.previousPosition.y
                            totalDrag += dragY
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            if (!isDragging && abs(totalDrag) > viewConfiguration.touchSlop) {
                                isDragging = true
                            }
                            if (isDragging) {
                                change.consume()
                                onDragCollapse(dragY)
                            }
                        }
                    }
                }
            }
        } else {
            Modifier
        }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isLandscapePhone = isLandscape && !isExpandedScreen
    val hasSeriesName = uiState.currentItemTitle.seriesName != null
    val bottomArtworkPadding = if (isLandscapePhone && !hasSeriesName) 24.dp else 4.dp

    Column(
        modifier = modifier.then(dragModifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CastExpandedPlayerHeader(
            deviceName = deviceName,
            onClose = onClose,
            onDeviceClick = onDeviceClick,
            isExpandedScreen = isExpandedScreen,
            themeColors = themeColors,
        )

        BoxWithConstraints(
            modifier =
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacings.default)
                    .padding(bottom = bottomArtworkPadding),
            contentAlignment = Alignment.Center,
        ) {
            val targetAspect = safeAspectRatio.coerceIn(0.66f, 1.85f)
            val (artworkWidth, artworkHeight) =
                if (isLandscapePhone) {
                    val maxAllowedHeight = maxHeight * 0.92f
                    val maxAllowedWidth = maxWidth * 0.94f
                    val candidateWidth = maxAllowedHeight * targetAspect
                    if (candidateWidth <= maxAllowedWidth) {
                        candidateWidth to maxAllowedHeight
                    } else {
                        maxAllowedWidth to (maxAllowedWidth / targetAspect)
                    }
                } else {
                    val maxAllowedHeight = (maxHeight * 0.78f).coerceAtMost(320.dp)
                    val maxAllowedWidth = maxWidth * 0.88f
                    val candidateWidth = maxAllowedHeight * targetAspect
                    if (candidateWidth <= maxAllowedWidth) {
                        candidateWidth to maxAllowedHeight
                    } else {
                        maxAllowedWidth to (maxAllowedWidth / targetAspect)
                    }
                }
            val artworkModifier = Modifier.size(width = artworkWidth, height = artworkHeight)

            Box(contentAlignment = Alignment.Center) {
                CastPlayerArtwork(
                    poster = uiState.currentItemPoster,
                    itemTitle = itemTitle,
                    isScrubbing = isScrubbing,
                    scrubPosition = scrubPosition,
                    trickplay = trickplay,
                    hasValidDuration = uiState.playerState.duration > 0,
                    modifier = artworkModifier,
                )
            }
        }

        if (isLandscapePhone && hasSeriesName) {
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 4.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = uiState.currentItemTitle.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!uiState.currentItemTitle.episodeInfo.isNullOrBlank()) {
                    Text(
                        text = uiState.currentItemTitle.episodeInfo!!,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * Displays a trickplay thumbnail preview for scrubbing positions.
 *
 * @param trickplay The trickplay model containing thumbnail images and interval info.
 * @param scrubPosition Current scrub position in milliseconds.
 * @param modifier Modifier applied to the image layout. Defaults to [Modifier].
 */
@Composable
fun TrickplayThumbnail(
    trickplay: Trickplay,
    scrubPosition: Float,
    modifier: Modifier = Modifier,
) {
    if (!trickplay.hasThumbnails || trickplay.interval <= 0) return
    val safePosition = if (scrubPosition.isFinite() && scrubPosition > 0f) scrubPosition else 0f
    dev.jdtech.jellyfin.player.core.presentation.TrickplayThumbnail(
        trickplay = trickplay,
        positionMs = safePosition.toLong(),
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
private fun CastPlayerArtwork(
    poster: PlayerImage?,
    itemTitle: String,
    isScrubbing: Boolean,
    scrubPosition: Float,
    trickplay: Trickplay?,
    hasValidDuration: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (isScrubbing && trickplay != null && hasValidDuration && trickplay.hasThumbnails) {
            TrickplayThumbnail(
                trickplay = trickplay,
                scrubPosition = scrubPosition,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (poster != null) {
            val blurPlaceholder = remember(poster.blurHash) { poster.blurHash.toBlurHashPainter() }

            val configuration = LocalConfiguration.current
            val stableWidthDp = configuration.screenWidthDp.dp
            val stableHeightDp = configuration.screenHeightDp.dp
            val optimizedUri =
                poster.uri.toOptimizedImageUri(widthDp = stableWidthDp, heightDp = stableHeightDp)

            val contentDescription =
                if (itemTitle.isNotBlank()) {
                    stringResource(CoreR.string.image_description_poster, itemTitle)
                } else {
                    stringResource(CoreR.string.series_poster)
                }

            AsyncImage(
                model = optimizedUri,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                placeholder = blurPlaceholder,
                error = blurPlaceholder,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(CoreR.drawable.ic_cast),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true, heightDp = 500)
@Composable
private fun PlayerTopSectionPreview() {
    FindroidTheme {
        PlayerTopSection(
            uiState =
                CastPlayerViewModel.UiState(
                    connectedDevice =
                        Device(
                            id = "device1",
                            name = "Living Room TV",
                            enabled = true,
                            supportsH265 = true,
                        ),
                    currentItemTitle =
                        CastPlayerViewModel.CurrentItemTitle(
                            seriesName = "Big Buck Bunny",
                            title = "The Journey",
                        ),
                    defaultAspectRatio = 16f / 9f,
                ),
            isScrubbing = false,
            scrubPosition = 0f,
            onClose = {},
            onDeviceClick = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 500)
@Composable
private fun PlayerTopSectionNoDevicePreview() {
    FindroidTheme {
        PlayerTopSection(
            uiState =
                CastPlayerViewModel.UiState(
                    connectedDevice = null,
                    currentItemTitle =
                        CastPlayerViewModel.CurrentItemTitle(title = "Standalone Movie"),
                    defaultAspectRatio = 2f / 3f,
                ),
            isScrubbing = false,
            scrubPosition = 0f,
            onClose = {},
            onDeviceClick = {},
        )
    }
}
