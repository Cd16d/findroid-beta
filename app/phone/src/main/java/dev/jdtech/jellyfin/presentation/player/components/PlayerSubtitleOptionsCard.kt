package dev.jdtech.jellyfin.presentation.player.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.theme.CentralShapes
import dev.jdtech.jellyfin.core.presentation.theme.PlayerOverlayBackground
import dev.jdtech.jellyfin.player.core.R as PlayerR
import dev.jdtech.jellyfin.player.core.presentation.drawCustomRoundRect
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Formats subtitle timing offset in seconds with sign and two decimal places. */
@Composable
fun formatSubtitleDelay(delayMs: Long): String {
    return if (delayMs == 0L) {
        stringResource(id = PlayerR.string.subtitle_delay_zero)
    } else {
        val seconds = delayMs / 1000f
        stringResource(id = PlayerR.string.delay_value_format, seconds)
    }
}

/** Maps subtitle delay in ms to a 0f...1f slider fraction, placing 0L at center (0.5f). */
private fun delayToFraction(delayMs: Long, minDelay: Long, maxDelay: Long): Float {
    return if (delayMs <= 0L) {
        val span = (0L - minDelay).coerceAtLeast(100L).toFloat()
        (0.5f * (delayMs - minDelay) / span).coerceIn(0f, 0.5f)
    } else {
        val span = (maxDelay - 0L).coerceAtLeast(100L).toFloat()
        (0.5f + 0.5f * delayMs / span).coerceIn(0.5f, 1f)
    }
}

/** Maps a 0f...1f slider fraction to subtitle delay in ms, where 0.5f maps to 0L. */
private fun fractionToDelay(fraction: Float, minDelay: Long, maxDelay: Long): Long {
    val rawDelay =
        if (fraction <= 0.5f) {
            val ratio = (fraction / 0.5f).coerceIn(0f, 1f)
            minDelay + ratio * (0L - minDelay)
        } else {
            val ratio = ((fraction - 0.5f) / 0.5f).coerceIn(0f, 1f)
            ratio * maxDelay
        }
    val snapped = (rawDelay.roundToLong() / 50L) * 50L
    return if (abs(snapped) < 50L) 0L else snapped
}

/**
 * Expandable card positioned at the top of the subtitle track selection list.
 *
 * Houses two distinct subcards:
 * 1. Centered real-time subtitle synchronization slider with +/- 100ms steppers and reset pill.
 * 2. External subtitle file loader (.srt, .vtt) via Storage Access Framework.
 *
 * @param subtitleDelayMs Currently applied subtitle delay offset in milliseconds.
 * @param isExternalSubtitleLoaded Whether a custom external subtitle has been loaded.
 * @param onDelayChange Callback invoked when the user adjusts timing offset.
 * @param onLoadExternalSubtitle Callback invoked with selected subtitle [Uri].
 * @param modifier Optional [Modifier] for this layout.
 * @param shape Corner [Shape] for card boundaries.
 * @param initialExpanded Initial expanded state of the card.
 */
@Composable
fun PlayerSubtitleOptionsCard(
    subtitleDelayMs: Long,
    isExternalSubtitleLoaded: Boolean,
    onDelayChange: (Long) -> Unit,
    onLoadExternalSubtitle: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CentralShapes.GroupSingle,
    initialExpanded: Boolean = false,
) {
    var isExpanded by rememberSaveable { mutableStateOf(initialExpanded) }
    val chevronRotation by
        animateFloatAsState(
            targetValue = if (isExpanded) 180f else 0f,
            label = "SubtitleOptionsChevronRotation",
        )

    val subtitlePickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) {
            uri: Uri? ->
            uri?.let { onLoadExternalSubtitle(it) }
        }

    val isDelayActive = subtitleDelayMs != 0L
    val delayText = formatSubtitleDelay(subtitleDelayMs)

    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header row (Main container header)
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true),
                            onClick = { isExpanded = !isExpanded },
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    painter = painterResource(id = CoreR.drawable.ic_wrench),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = PlayerR.string.subtitle_options),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AnimatedVisibility(
                        visible = isDelayActive && !isExpanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Text(
                            text =
                                stringResource(
                                    id = PlayerR.string.subtitle_sync_active_format,
                                    delayText,
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Icon(
                    painter = painterResource(id = CoreR.drawable.ic_chevron_down),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).rotate(chevronRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Expanded content with the two subcards
            if (isExpanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Subcard 1: Subtitle Synchronization Slider Subcard
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border =
                            BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            ),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            SubtitleDelaySliderSection(
                                subtitleDelayMs = subtitleDelayMs,
                                onDelayChange = onDelayChange,
                            )
                        }
                    }

                    // Subcard 2: Load subtitle file Subcard
                    Surface(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border =
                            BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            ),
                        onClick = { subtitlePickerLauncher.launch(arrayOf("*/*")) },
                    ) {
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                painter = painterResource(id = CoreR.drawable.ic_upload),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text =
                                        stringResource(id = PlayerR.string.load_external_subtitle),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text =
                                        stringResource(
                                            id = PlayerR.string.load_external_subtitle_summary,
                                        ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.repeatingClickable(
    interactionSource: MutableInteractionSource,
    initialDelayMs: Long = 400L,
    repeatDelayMs: Long = 100L,
    onClick: () -> Unit,
): Modifier {
    val currentOnClick = rememberUpdatedState(onClick)
    val coroutineScope = rememberCoroutineScope()
    return this.pointerInput(interactionSource) {
        detectTapGestures(
            onPress = { offset ->
                val press = PressInteraction.Press(offset)
                interactionSource.emit(press)
                currentOnClick.value()
                val job = coroutineScope.launch {
                    delay(initialDelayMs.milliseconds)
                    while (isActive) {
                        currentOnClick.value()
                        delay(repeatDelayMs.milliseconds)
                    }
                }
                try {
                    tryAwaitRelease()
                } finally {
                    job.cancel()
                    interactionSource.emit(PressInteraction.Release(press))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtitleDelaySliderSection(
    subtitleDelayMs: Long,
    onDelayChange: (Long) -> Unit,
) {
    val haptics = LocalPlayerHaptics.current
    var wasAtCenter by remember(subtitleDelayMs) { mutableStateOf(subtitleDelayMs == 0L) }

    val minDelay = -5000L
    val maxDelay = 5000L
    val delayText = formatSubtitleDelay(subtitleDelayMs)
    val isDelayActive = subtitleDelayMs != 0L

    // Section title
    Text(
        text = stringResource(id = PlayerR.string.subtitle_synchronization),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = Color.White,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Large centered readout (matching speed modal style)
    Text(
        text = delayText,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = Color.White,
    )

    Spacer(modifier = Modifier.height(14.dp))

    // Minus Button, Centered Slider, Plus Button
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val minusInteractionSource = remember { MutableInteractionSource() }
        // Minus Button Column (-100ms step with hold-to-repeat)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier =
                    Modifier.size(46.dp)
                        .clip(CircleShape)
                        .background(PlayerOverlayBackground)
                        .indication(minusInteractionSource, ripple(bounded = true, radius = 23.dp))
                        .repeatingClickable(
                            interactionSource = minusInteractionSource,
                            onClick = { onDelayChange(subtitleDelayMs - 100L) },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = CoreR.drawable.ic_minus),
                    contentDescription = stringResource(id = PlayerR.string.decrease_delay),
                    modifier = Modifier.size(20.dp),
                    tint = Color.White,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(id = PlayerR.string.delay_step_minus_hundred_ms),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }

        // Slider with Centered Track (active bar starts from 0.0s at center)
        val sliderFraction = delayToFraction(subtitleDelayMs, minDelay, maxDelay)
        val sliderInteractionSource = remember { MutableInteractionSource() }
        val isPressed by sliderInteractionSource.collectIsPressedAsState()
        val isDragged by sliderInteractionSource.collectIsDraggedAsState()
        val isInteracting = isPressed || isDragged

        val trackHeightDp = 16.dp
        val animatedThumbWidth by
            animateDpAsState(
                targetValue = if (isInteracting) 2.dp else 4.dp,
                animationSpec = tween(150),
                label = "subtitleThumbWidth",
            )
        val animatedThumbHeight by
            animateDpAsState(
                targetValue = if (isInteracting) 38.dp else 32.dp,
                animationSpec = tween(150),
                label = "subtitleThumbHeight",
            )

        val sliderState = remember { SliderState(value = sliderFraction, trackRange = 0f..1f) }
        LaunchedEffect(sliderFraction) {
            if (sliderState.value != sliderFraction) {
                sliderState.value = sliderFraction
            }
        }

        Slider(
            state = sliderState,
            onValueChange = { f ->
                val snapped = fractionToDelay(f, minDelay, maxDelay)
                sliderState.value = delayToFraction(snapped, minDelay, maxDelay)
                val isAtCenter = snapped == 0L
                if (isAtCenter && !wasAtCenter) {
                    haptics.chapterSnap()
                }
                wasAtCenter = isAtCenter
                onDelayChange(snapped)
            },
            interactionSource = sliderInteractionSource,
            colors =
                SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                ),
            thumb = {
                Box(
                    modifier =
                        Modifier.size(width = animatedThumbWidth, height = animatedThumbHeight)
                            .clip(RoundedCornerShape(animatedThumbWidth / 2f))
                            .background(Color.White),
                )
            },
            track = { sliderState ->
                val fraction = sliderState.value
                Canvas(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    val trackHeight = trackHeightDp.toPx()
                    val centerY = size.height / 2f
                    val centerX = size.width * 0.5f
                    val thumbX = size.width * fraction.coerceIn(0f, 1f)
                    val thumbWidthPx = animatedThumbWidth.toPx()
                    val thumbGapPx = 4.dp.toPx()
                    val centerGapPx = 4.dp.toPx()
                    val centerLeft = centerX - centerGapPx / 2f
                    val centerRight = centerX + centerGapPx / 2f
                    val halfTrack = trackHeight / 2f
                    val fullCornerRadius = CornerRadius(halfTrack, halfTrack)
                    val smallCornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())

                    val clampedThumbX =
                        thumbX.coerceIn(thumbWidthPx / 2f, size.width - thumbWidthPx / 2f)
                    val thumbLeft = clampedThumbX - thumbWidthPx / 2f
                    val thumbRight = clampedThumbX + thumbWidthPx / 2f
                    val gapStart = (thumbLeft - thumbGapPx).coerceAtLeast(0f)
                    val gapEnd = (thumbRight + thumbGapPx).coerceAtMost(size.width)

                    val inactiveColor = Color.White.copy(alpha = 0.20f)
                    val activeColor = Color.White

                    // 1. Left inactive track (from 0 to minOf(centerLeft, gapStart))
                    val leftEnd = minOf(centerLeft, gapStart)
                    if (leftEnd > 0f) {
                        drawCustomRoundRect(
                            color = inactiveColor,
                            topLeft = Offset(0f, centerY - halfTrack),
                            size = Size(leftEnd, trackHeight),
                            topLeftRadius = fullCornerRadius,
                            bottomLeftRadius = fullCornerRadius,
                            topRightRadius = smallCornerRadius,
                            bottomRightRadius = smallCornerRadius,
                        )
                    }

                    // 2. Active track segment extending from center
                    if (thumbX >= centerX) {
                        if (gapStart > centerRight) {
                            drawCustomRoundRect(
                                color = activeColor,
                                topLeft = Offset(centerRight, centerY - halfTrack),
                                size = Size(gapStart - centerRight, trackHeight),
                                topLeftRadius = smallCornerRadius,
                                bottomLeftRadius = smallCornerRadius,
                                topRightRadius = smallCornerRadius,
                                bottomRightRadius = smallCornerRadius,
                            )
                        }
                    } else {
                        if (gapEnd < centerLeft) {
                            drawCustomRoundRect(
                                color = activeColor,
                                topLeft = Offset(gapEnd, centerY - halfTrack),
                                size = Size(centerLeft - gapEnd, trackHeight),
                                topLeftRadius = smallCornerRadius,
                                bottomLeftRadius = smallCornerRadius,
                                topRightRadius = smallCornerRadius,
                                bottomRightRadius = smallCornerRadius,
                            )
                        }
                    }

                    // 3. Right inactive track (from maxOf(centerRight, gapEnd) to size.width)
                    val rightStart = maxOf(centerRight, gapEnd)
                    if (size.width > rightStart) {
                        drawCustomRoundRect(
                            color = inactiveColor,
                            topLeft = Offset(rightStart, centerY - halfTrack),
                            size = Size(size.width - rightStart, trackHeight),
                            topLeftRadius = smallCornerRadius,
                            bottomLeftRadius = smallCornerRadius,
                            topRightRadius = fullCornerRadius,
                            bottomRightRadius = fullCornerRadius,
                        )
                    }
                }
            },
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )

        val plusInteractionSource = remember { MutableInteractionSource() }
        // Plus Button Column (+100ms step with hold-to-repeat)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier =
                    Modifier.size(46.dp)
                        .clip(CircleShape)
                        .background(PlayerOverlayBackground)
                        .indication(plusInteractionSource, ripple(bounded = true, radius = 23.dp))
                        .repeatingClickable(
                            interactionSource = plusInteractionSource,
                            onClick = { onDelayChange(subtitleDelayMs + 100L) },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = CoreR.drawable.ic_plus),
                    contentDescription = stringResource(id = PlayerR.string.increase_delay),
                    modifier = Modifier.size(20.dp),
                    tint = Color.White,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(id = PlayerR.string.delay_step_plus_hundred_ms),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
    }

    // Reset Pill (visible only when delay != 0)
    AnimatedVisibility(
        visible = isDelayActive,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Surface(
            shape = CircleShape,
            color = PlayerOverlayBackground,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            onClick = { onDelayChange(0L) },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painter = painterResource(id = CoreR.drawable.ic_undo),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(id = PlayerR.string.reset),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
            }
        }
    }
}

@Preview(
    name = "Subtitle Options Card - Collapsed (Default)",
    showBackground = true,
    backgroundColor = 0xFF141416,
)
@Composable
private fun PlayerSubtitleOptionsCardCollapsedPreview() {
    FindroidTheme(darkTheme = true) {
        Column(modifier = Modifier.padding(16.dp)) {
            PlayerSubtitleOptionsCard(
                subtitleDelayMs = 0L,
                isExternalSubtitleLoaded = false,
                onDelayChange = {},
                onLoadExternalSubtitle = {},
                initialExpanded = false,
            )
        }
    }
}

@Preview(
    name = "Subtitle Options Card - Collapsed (Active Sync)",
    showBackground = true,
    backgroundColor = 0xFF141416,
)
@Composable
private fun PlayerSubtitleOptionsCardCollapsedActivePreview() {
    FindroidTheme(darkTheme = true) {
        Column(modifier = Modifier.padding(16.dp)) {
            PlayerSubtitleOptionsCard(
                subtitleDelayMs = 250L,
                isExternalSubtitleLoaded = false,
                onDelayChange = {},
                onLoadExternalSubtitle = {},
                initialExpanded = false,
            )
        }
    }
}

@Preview(
    name = "Subtitle Options Card - Expanded",
    showBackground = true,
    backgroundColor = 0xFF141416,
)
@Composable
private fun PlayerSubtitleOptionsCardExpandedPreview() {
    FindroidTheme(darkTheme = true) {
        Column(modifier = Modifier.padding(16.dp)) {
            PlayerSubtitleOptionsCard(
                subtitleDelayMs = 250L,
                isExternalSubtitleLoaded = false,
                onDelayChange = {},
                onLoadExternalSubtitle = {},
                initialExpanded = true,
            )
        }
    }
}
