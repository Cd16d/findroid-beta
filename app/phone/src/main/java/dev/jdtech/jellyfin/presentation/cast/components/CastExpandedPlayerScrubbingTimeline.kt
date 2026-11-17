package dev.jdtech.jellyfin.presentation.cast.components

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.models.FindroidSegment
import dev.jdtech.jellyfin.player.cast.models.CastPlaybackStatus
import dev.jdtech.jellyfin.player.core.R
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.core.presentation.formatDuration
import dev.jdtech.jellyfin.presentation.cast.MiniPlayerThemeColors
import dev.jdtech.jellyfin.presentation.player.components.VideoPlayerSeekBar
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import java.util.Date

/**
 * Timeline component for the expanded Cast player, including badges, chapter info, remaining time,
 * and seek bar.
 *
 * @param playerStatus Current playback status.
 * @param currentProgress Current progress in milliseconds.
 * @param duration Duration in milliseconds.
 * @param chapters List of chapters for the current media item.
 * @param onScrubStart Callback when scrub drag starts.
 * @param onScrubStop Callback when scrub drag stops.
 * @param modifier Modifier applied to the timeline layout. Defaults to [Modifier].
 * @param segments List of media segments (e.g. intros, credits). Defaults to empty list.
 * @param onOpenChapters Optional callback when chapter badge is tapped to open chapter selector.
 * @param isItemLoaded Whether media item metadata is loaded and ready. Defaults to true if
 *   duration > 0 or not idle.
 * @param enabled Whether seek bar is enabled for scrubbing. Defaults to true when item is loaded
 *   with valid duration.
 */
@Composable
fun CastScrubbingTimeline(
    playerStatus: CastPlaybackStatus,
    currentProgress: Float,
    duration: Float,
    chapters: List<PlayerChapter>,
    onScrubStart: (Float) -> Unit,
    onScrubStop: () -> Unit,
    modifier: Modifier = Modifier,
    segments: List<FindroidSegment> = emptyList(),
    isItemLoaded: Boolean = duration > 0f || playerStatus != CastPlaybackStatus.IDLE,
    enabled: Boolean = isItemLoaded && duration > 0f,
    themeColors: MiniPlayerThemeColors? = null,
) {
    val safeDuration = if (duration.isFinite() && duration > 0f) duration else 0f
    val safeProgress =
        if (currentProgress.isFinite() && currentProgress > 0f) {
            currentProgress.coerceIn(0f, if (safeDuration > 0f) safeDuration else 1f)
        } else {
            0f
        }

    val durationMs = safeDuration.toLong()
    val progressMs = safeProgress.toLong()

    val context = LocalContext.current
    val contentProgressString = formatDuration(progressMs)
    val contentDurationString = formatDuration(durationMs)

    val remainingMs = if (durationMs > 0L) (durationMs - progressMs).coerceAtLeast(0L) else 0L
    val formattedEndTime =
        remember(remainingMs, context) {
            if (durationMs > 0L) {
                val endTimeMillis = System.currentTimeMillis() + remainingMs
                DateFormat.getTimeFormat(context).format(Date(endTimeMillis))
            } else {
                null
            }
        }
    val endsAtText = formattedEndTime?.let { stringResource(R.string.ends_at, it) }

    val pillBackground =
        themeColors?.subtleContainerColor ?: MaterialTheme.colorScheme.surfaceVariant
    val pillTextColor =
        themeColors?.colorScheme?.onSurfaceVariant ?: MaterialTheme.colorScheme.onSurfaceVariant
    val pillBorder =
        BorderStroke(
            1.dp,
            (themeColors?.colorScheme?.outlineVariant ?: MaterialTheme.colorScheme.outlineVariant)
                .copy(alpha = 0.35f),
        )

    Column(modifier = modifier.padding(horizontal = 24.dp)) {
        // Badges row: Progress • Duration pill on left, Ends at pill on right
        Box(modifier = Modifier.fillMaxWidth().alpha(if (isItemLoaded) 1f else 0f)) {
            // Pill: Current progress • Content duration (right above seekbar)
            Box(
                modifier =
                    Modifier.align(Alignment.CenterStart)
                        .clip(CircleShape)
                        .background(pillBackground)
                        .border(pillBorder, CircleShape)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = contentProgressString,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = pillTextColor,
                    )
                    Box(
                        modifier =
                            Modifier.size(4.dp)
                                .clip(CircleShape)
                                .background(pillTextColor.copy(alpha = 0.6f)),
                    )
                    Text(
                        text = if (durationMs > 0L) contentDurationString else "--:--",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = pillTextColor.copy(alpha = 0.85f),
                    )
                }
            }

            // Ends at HH:mm
            if (endsAtText != null) {
                Box(
                    modifier =
                        Modifier.align(Alignment.CenterEnd)
                            .clip(CircleShape)
                            .background(pillBackground)
                            .border(pillBorder, CircleShape)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = endsAtText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = pillTextColor.copy(alpha = 0.85f),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        val isBuffering = playerStatus == CastPlaybackStatus.BUFFERING
        val progressFraction =
            if (durationMs > 0L) {
                (progressMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }

        VideoPlayerSeekBar(
            progress = progressFraction,
            onSeek = { fraction ->
                val targetMs = fraction * durationMs
                onScrubStart(targetMs)
                onScrubStop()
            },
            durationMs = durationMs,
            chapters = chapters,
            segments = segments,
            onScrubbingChanged = { isScrubbing, scrubPosMs, _ ->
                if (isScrubbing) {
                    onScrubStart(scrubPosMs.toFloat())
                }
            },
            isBuffering = isBuffering,
            enabled = enabled,
            inactiveTrackColor =
                themeColors?.subtleContainerColor ?: MaterialTheme.colorScheme.surfaceVariant,
            activeTrackColor = themeColors?.accentColor,
            thumbColor = themeColors?.accentColor ?: MaterialTheme.colorScheme.primary,
            disabledThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            trackHeight = 16.dp,
            highlightTrackHeight = 16.dp,
            thumbWidth = 4.dp,
            highlightThumbWidth = 2.dp,
            thumbHeight = 44.dp,
            highlightThumbHeight = 44.dp,
            canvasHeight = 48.dp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CastScrubbingTimelinePreview() {
    FindroidTheme {
        CastScrubbingTimeline(
            playerStatus = CastPlaybackStatus.PLAYING,
            currentProgress = 120000f,
            duration = 600000f,
            chapters =
                listOf(
                    PlayerChapter(0L, "Chapter 1"),
                    PlayerChapter(300000L, "Chapter 2"),
                ),
            onScrubStart = {},
            onScrubStop = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CastScrubbingTimelineBufferingPreview() {
    FindroidTheme {
        CastScrubbingTimeline(
            playerStatus = CastPlaybackStatus.BUFFERING,
            currentProgress = 120000f,
            duration = 600000f,
            chapters = emptyList(),
            onScrubStart = {},
            onScrubStop = {},
            isItemLoaded = true,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CastScrubbingTimelineIdlePreview() {
    FindroidTheme {
        CastScrubbingTimeline(
            playerStatus = CastPlaybackStatus.IDLE,
            currentProgress = 0f,
            duration = 0f,
            chapters = emptyList(),
            onScrubStart = {},
            onScrubStop = {},
            isItemLoaded = false,
            enabled = false,
        )
    }
}
