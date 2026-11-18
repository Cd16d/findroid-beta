package dev.jdtech.jellyfin.presentation.cast

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.player.cast.models.CastPlaybackStatus
import dev.jdtech.jellyfin.player.cast.models.CastPlayerState
import dev.jdtech.jellyfin.player.cast.models.Device
import dev.jdtech.jellyfin.player.cast.presentation.CastPlayerViewModel
import dev.jdtech.jellyfin.player.core.R as PlayerCoreR
import dev.jdtech.jellyfin.player.core.domain.models.PlayerImage
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import dev.jdtech.jellyfin.utils.toBlurHashColor

@Immutable
data class MiniPlayerThemeColors(
    val colorScheme: ColorScheme,
    val containerColor: Color = colorScheme.surfaceContainer,
    val subtleContainerColor: Color = colorScheme.surfaceVariant,
    val progressColor: Color =
        if (colorScheme.surface.luminance() < 0.5f) {
            colorScheme.primary.copy(alpha = 0.40f)
        } else {
            colorScheme.primary.copy(alpha = 0.22f)
        },
    val playButtonContainerColor: Color = colorScheme.primary,
    val playButtonContentColor: Color = colorScheme.onPrimary,
    val secondaryButtonContainerColor: Color = colorScheme.surfaceVariant,
    val secondaryButtonContentColor: Color = colorScheme.onSurfaceVariant,
    val skipSegmentContainerColor: Color = colorScheme.secondaryContainer,
    val skipSegmentContentColor: Color = colorScheme.onSecondaryContainer,
    val accentColor: Color = colorScheme.primary,
    val onAccentColor: Color = colorScheme.onPrimary,
)

/**
 * Derives a full Material 3 color scheme from an extracted cover art color. Adapts to day (light)
 * or night (dark) mode dynamically. If baseColor is null or nearly monochromatic, falls back to the
 * app's standard scheme.
 */
fun generatePlayerColorScheme(
    baseColor: Color?,
    fallback: ColorScheme,
    isDarkTheme: Boolean = fallback.surface.luminance() < 0.5f,
): ColorScheme {
    if (baseColor == null) return fallback
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(baseColor.toArgb(), hsv)
    val hue = hsv[0]
    val sat = hsv[1]

    if (sat < 0.05f) return fallback

    fun hsvColor(h: Float, s: Float, v: Float): Color {
        val intColor =
            android.graphics.Color.HSVToColor(
                floatArrayOf(
                    (h % 360f + 360f) % 360f,
                    s.coerceIn(0f, 1f),
                    v.coerceIn(0f, 1f),
                ),
            )
        return Color(intColor)
    }

    val isGreenHue = hue in 65f..165f

    return if (isDarkTheme) {
        // Calibrate saturation and value for elegant, modern Material 3 dark theme
        // Green hues (65°..165°) have naturally high perceived luminosity, so we tone down their
        // brightness and saturation to avoid fluorescent/neon looks
        val primarySat = if (isGreenHue) sat.coerceIn(0.35f, 0.58f) else sat.coerceIn(0.45f, 0.70f)
        val primaryVal = if (isGreenHue) 0.72f else 0.78f

        val primary = hsvColor(hue, primarySat, primaryVal)
        val onPrimary = hsvColor(hue, 0.70f, 0.08f)
        val primaryContainer = hsvColor(hue, primarySat, 0.28f)
        val onPrimaryContainer = hsvColor(hue, 0.20f, 0.92f)

        // Secondary: muted tonal variant for secondary container actions (Skip Intro / Outro)
        val secondarySat = (sat * 0.35f).coerceIn(0.18f, 0.35f)
        val secondary = hsvColor(hue, secondarySat, 0.75f)
        val onSecondary = hsvColor(hue, secondarySat, 0.10f)
        val secondaryContainer = hsvColor(hue, (sat * 0.40f).coerceIn(0.20f, 0.38f), 0.28f)
        val onSecondaryContainer = hsvColor(hue, 0.18f, 0.92f)

        // Tertiary: complementary/analogous harmonic hue offset (+60 deg)
        val tertHue = (hue + 60f) % 360f
        val tertSat = (sat * 0.45f).coerceIn(0.22f, 0.45f)
        val tertiary = hsvColor(tertHue, tertSat, 0.75f)
        val onTertiary = hsvColor(tertHue, tertSat, 0.10f)
        val tertiaryContainer = hsvColor(tertHue, tertSat, 0.28f)
        val onTertiaryContainer = hsvColor(tertHue, 0.18f, 0.92f)

        // Neutral Surfaces: dark ambient canvas
        val background = hsvColor(hue, 0.08f, 0.08f)
        val surface = background
        val surfaceContainerLowest = hsvColor(hue, 0.07f, 0.05f)
        val surfaceContainerLow = hsvColor(hue, 0.08f, 0.09f)
        val surfaceContainer = hsvColor(hue, 0.10f, 0.13f)
        val surfaceContainerHigh = hsvColor(hue, 0.11f, 0.17f)
        val surfaceContainerHighest = hsvColor(hue, 0.12f, 0.21f)

        // Neutral Variant: Secondary buttons (skip back, forward, chevron) & Inactive Tracks
        val surfaceVariant = hsvColor(hue, 0.18f, 0.27f)
        val onSurfaceVariant = hsvColor(hue, 0.12f, 0.82f)
        val onSurface = hsvColor(hue, 0.05f, 0.94f)

        val outline = hsvColor(hue, 0.15f, 0.40f)
        val outlineVariant = hsvColor(hue, 0.12f, 0.25f)

        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            error = fallback.error,
            onError = fallback.onError,
            errorContainer = fallback.errorContainer,
            onErrorContainer = fallback.onErrorContainer,
            background = background,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = Color.Black,
            inverseSurface = onSurface,
            inverseOnSurface = surface,
            inversePrimary = primaryContainer,
            surfaceDim = surfaceContainerLow,
            surfaceBright = surfaceContainerHigh,
            surfaceContainerLowest = surfaceContainerLowest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
        )
    } else {
        // Calibrate saturation and value for elegant, modern Material 3 light theme
        // In light theme, primary is deep and rich (tone 40) so it contrasts well against light
        // backgrounds
        val primarySat = if (isGreenHue) sat.coerceIn(0.50f, 0.72f) else sat.coerceIn(0.55f, 0.80f)
        val primaryVal = if (isGreenHue) 0.38f else 0.44f

        val primary = hsvColor(hue, primarySat, primaryVal)
        val onPrimary = Color.White
        val primaryContainer = hsvColor(hue, (primarySat * 0.35f).coerceIn(0.12f, 0.28f), 0.90f)
        val onPrimaryContainer = hsvColor(hue, primarySat, 0.18f)

        // Secondary: muted tonal variant for secondary container actions (Skip Intro / Outro)
        val secondarySat = (sat * 0.40f).coerceIn(0.25f, 0.45f)
        val secondary = hsvColor(hue, secondarySat, 0.42f)
        val onSecondary = Color.White
        val secondaryContainer = hsvColor(hue, (secondarySat * 0.35f).coerceIn(0.10f, 0.24f), 0.92f)
        val onSecondaryContainer = hsvColor(hue, secondarySat, 0.18f)

        // Tertiary: complementary/analogous harmonic hue offset (+60 deg)
        val tertHue = (hue + 60f) % 360f
        val tertSat = (sat * 0.45f).coerceIn(0.25f, 0.45f)
        val tertiary = hsvColor(tertHue, tertSat, 0.42f)
        val onTertiary = Color.White
        val tertiaryContainer = hsvColor(tertHue, (tertSat * 0.35f).coerceIn(0.10f, 0.24f), 0.92f)
        val onTertiaryContainer = hsvColor(tertHue, tertSat, 0.18f)

        // Neutral Surfaces: light ambient canvas tinted subtly with cover hue
        val background = hsvColor(hue, 0.03f, 0.98f)
        val surface = background
        val surfaceContainerLowest = Color.White
        val surfaceContainerLow = hsvColor(hue, 0.04f, 0.96f)
        val surfaceContainer = hsvColor(hue, 0.05f, 0.94f)
        val surfaceContainerHigh = hsvColor(hue, 0.07f, 0.91f)
        val surfaceContainerHighest = hsvColor(hue, 0.09f, 0.88f)

        // Neutral Variant: Secondary buttons (skip back, forward, chevron) & Inactive Tracks
        val surfaceVariant = hsvColor(hue, 0.08f, 0.90f)
        val onSurfaceVariant = hsvColor(hue, 0.12f, 0.32f)
        val onSurface = hsvColor(hue, 0.08f, 0.10f)

        val outline = hsvColor(hue, 0.15f, 0.55f)
        val outlineVariant = hsvColor(hue, 0.10f, 0.82f)

        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            error = fallback.error,
            onError = fallback.onError,
            errorContainer = fallback.errorContainer,
            onErrorContainer = fallback.onErrorContainer,
            background = background,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = Color.Black,
            inverseSurface = onSurface,
            inverseOnSurface = surface,
            inversePrimary = primaryContainer,
            surfaceDim = surfaceContainerLow,
            surfaceBright = surfaceContainerLowest,
            surfaceContainerLowest = surfaceContainerLowest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
        )
    }
}

@Composable
fun rememberMiniPlayerThemeColors(
    blurHash: String?,
    isDarkTheme: Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f,
): MiniPlayerThemeColors {
    val currentColorScheme = MaterialTheme.colorScheme

    return remember(blurHash, currentColorScheme, isDarkTheme) {
        val baseColor = blurHash.toBlurHashColor()
        val playerColorScheme =
            generatePlayerColorScheme(
                baseColor = baseColor,
                fallback = currentColorScheme,
                isDarkTheme = isDarkTheme,
            )
        MiniPlayerThemeColors(colorScheme = playerColorScheme)
    }
}

@Composable
fun CastMiniPlayer(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CastPlayerViewModel = hiltViewModel(),
    handleBottomInsets: Boolean = true,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val connectedDevice = uiState.connectedDevice
    val playbackState = uiState.playerState

    if (connectedDevice != null) {
        val onTogglePlayback = remember(viewModel) { { viewModel.togglePlayPause() } }
        CastMiniPlayerLayout(
            connectedDevice = connectedDevice,
            uiState = uiState,
            playbackState = playbackState,
            onTogglePlayback = onTogglePlayback,
            onClick = onClick,
            modifier = modifier,
            handleBottomInsets = handleBottomInsets,
        )
    }
}

@Composable
fun CastMiniPlayerLayout(
    connectedDevice: Device,
    uiState: CastPlayerViewModel.UiState,
    playbackState: CastPlayerState,
    onTogglePlayback: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    handleBottomInsets: Boolean = true,
) {
    val windowAdaptiveInfo = currentWindowAdaptiveInfo()
    val isMediumScreen =
        windowAdaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )

    val safePadding = rememberSafePadding(handleBottomInsets = handleBottomInsets)

    val paddingStart = safePadding.start + MaterialTheme.spacings.medium
    val paddingEnd = safePadding.end + MaterialTheme.spacings.medium
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.medium

    val themeColors = rememberMiniPlayerThemeColors(uiState.currentItemPoster?.blurHash)

    MaterialTheme(colorScheme = themeColors.colorScheme) {
        Card(
            onClick = onClick,
            shape = CircleShape,
            colors =
                CardDefaults.cardColors(
                    containerColor = themeColors.containerColor,
                ),
            border =
                BorderStroke(
                    width = 1.dp,
                    color = themeColors.colorScheme.outlineVariant.copy(alpha = 0.25f),
                ),
            modifier =
                modifier
                    .padding(
                        start = paddingStart,
                        end = paddingEnd,
                        bottom = paddingBottom,
                    )
                    .then(
                        if (isMediumScreen) {
                            Modifier.width(420.dp).height(72.dp)
                        } else {
                            Modifier.fillMaxWidth().height(72.dp)
                        },
                    ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            MiniPlayerContent(
                connectedDevice = connectedDevice,
                uiState = uiState,
                playbackState = playbackState,
                onTogglePlayback = onTogglePlayback,
                themeColors = themeColors,
            )
        }
    }
}

@Composable
fun MiniPlayerContent(
    connectedDevice: Device,
    uiState: CastPlayerViewModel.UiState,
    playbackState: CastPlayerState,
    onTogglePlayback: () -> Unit,
    modifier: Modifier = Modifier,
    themeColors: MiniPlayerThemeColors =
        rememberMiniPlayerThemeColors(uiState.currentItemPoster?.blurHash),
) {
    val progressFraction =
        remember(playbackState.currentPosition, playbackState.duration) {
            if (playbackState.duration > 0L) {
                (playbackState.currentPosition.toFloat() / playbackState.duration.toFloat())
                    .coerceIn(0f, 1f)
            } else {
                0f
            }
        }
    val progressColor = themeColors.progressColor

    Box(
        modifier =
            modifier.fillMaxSize().clip(CircleShape).drawBehind {
                if (uiState.fileLoaded && progressFraction > 0f) {
                    drawRect(
                        color = progressColor,
                        size = Size(width = size.width * progressFraction, height = size.height),
                    )
                }
            },
    ) {
        Row(
            modifier =
                Modifier.fillMaxSize()
                    .padding(
                        start = 20.dp,
                        end = 12.dp,
                    ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        ) {
            if (uiState.fileLoaded) {
                MiniPlayerMediaInfo(
                    titleInfo = uiState.currentItemTitle,
                    deviceName = connectedDevice.name,
                    modifier = Modifier.weight(1f),
                )

                val isPlaying = playbackState.status == CastPlaybackStatus.PLAYING
                MiniPlayerPlayPauseButton(
                    isPlaying = isPlaying,
                    onTogglePlayback = onTogglePlayback,
                    containerColor = themeColors.playButtonContainerColor,
                    contentColor = themeColors.playButtonContentColor,
                )
            } else {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_cast),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                MiniPlayerIdleInfo(
                    deviceName = connectedDevice.name,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerMediaInfo(
    titleInfo: CastPlayerViewModel.CurrentItemTitle,
    deviceName: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
    ) {
        val seriesName = titleInfo.seriesName
        if (seriesName != null) {
            // Episode layout
            Text(
                text = seriesName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val episodeText =
                remember(titleInfo.episodeInfo, titleInfo.title) {
                    if (titleInfo.episodeInfo.isNullOrEmpty()) {
                        titleInfo.title
                    } else {
                        "${titleInfo.episodeInfo} · ${titleInfo.title}"
                    }
                }
            Text(
                text = episodeText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = deviceName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            // Film layout
            Text(
                text = titleInfo.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = deviceName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MiniPlayerPlayPauseButton(
    isPlaying: Boolean,
    onTogglePlayback: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Box(
        modifier = modifier.size(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onTogglePlayback,
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor = containerColor,
                    contentColor = contentColor,
                ),
            shape = CircleShape,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                painter =
                    if (isPlaying) painterResource(CoreR.drawable.ic_pause)
                    else painterResource(CoreR.drawable.ic_play),
                contentDescription =
                    if (isPlaying) {
                        stringResource(PlayerCoreR.string.player_controls_pause)
                    } else {
                        stringResource(PlayerCoreR.string.player_controls_play)
                    },
            )
        }
    }
}

@Composable
private fun MiniPlayerIdleInfo(
    deviceName: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = deviceName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(CoreR.string.cast_connected),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CastMiniPlayerPhonePreview() {
    FindroidTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            CastMiniPlayerLayout(
                connectedDevice = Device("1", "Living Room TV"),
                uiState = previewUiState(poster = null, fileLoaded = false),
                playbackState = CastPlayerState(),
                onTogglePlayback = {},
                onClick = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CastMiniPlayerPlayingPhonePreview() {
    FindroidTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            CastMiniPlayerLayout(
                connectedDevice = Device("1", "Living Room TV"),
                uiState =
                    previewUiState(
                        title = "Inception",
                        isMovie = true,
                        aspectRatio = 2f / 3f,
                        fileLoaded = true,
                    ),
                playbackState =
                    CastPlayerState(
                        status = CastPlaybackStatus.PLAYING,
                        currentPosition = 5000L,
                        duration = 10000L,
                    ),
                onTogglePlayback = {},
                onClick = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CastMiniPlayerBufferingPhonePreview() {
    FindroidTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            CastMiniPlayerLayout(
                connectedDevice = Device("1", "Living Room TV"),
                uiState =
                    previewUiState(
                        title = "Inception",
                        isMovie = true,
                        aspectRatio = 2f / 3f,
                        fileLoaded = true,
                    ),
                playbackState =
                    CastPlayerState(
                        status = CastPlaybackStatus.BUFFERING,
                        currentPosition = 5000L,
                        duration = 10000L,
                    ),
                onTogglePlayback = {},
                onClick = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CastMiniPlayerPlayingEpisodePhonePreview() {
    FindroidTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            CastMiniPlayerLayout(
                connectedDevice = Device("1", "Living Room TV"),
                uiState =
                    previewUiState(
                        title = "Pilot",
                        seriesName = "Breaking Bad",
                        episodeInfo = "S01:E01",
                        isMovie = false,
                        aspectRatio = 16f / 9f,
                        fileLoaded = true,
                    ),
                playbackState =
                    CastPlayerState(
                        status = CastPlaybackStatus.PLAYING,
                        currentPosition = 2500L,
                        duration = 10000L,
                    ),
                onTogglePlayback = {},
                onClick = {},
            )
        }
    }
}

@Preview(widthDp = 900, showBackground = true)
@Composable
private fun CastMiniPlayerTabletPreview() {
    FindroidTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            CastMiniPlayerLayout(
                connectedDevice = Device("1", "Living Room TV"),
                uiState =
                    previewUiState(
                        title = "Inception",
                        isMovie = true,
                        aspectRatio = 2f / 3f,
                        fileLoaded = true,
                    ),
                playbackState =
                    CastPlayerState(
                        status = CastPlaybackStatus.PAUSED,
                        currentPosition = 3000L,
                        duration = 10000L,
                    ),
                onTogglePlayback = {},
                onClick = {},
            )
        }
    }
}

@Preview(widthDp = 900, showBackground = true)
@Composable
private fun CastMiniPlayerEpisodeTabletPreview() {
    FindroidTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            CastMiniPlayerLayout(
                connectedDevice = Device("1", "Living Room TV"),
                uiState =
                    previewUiState(
                        title = "Pilot",
                        seriesName = "Breaking Bad",
                        episodeInfo = "S01:E01",
                        isMovie = false,
                        aspectRatio = 16f / 9f,
                        fileLoaded = true,
                    ),
                playbackState =
                    CastPlayerState(
                        status = CastPlaybackStatus.PAUSED,
                        currentPosition = 5000L,
                        duration = 10000L,
                    ),
                onTogglePlayback = {},
                onClick = {},
            )
        }
    }
}

@Preview(name = "Light Mode", showBackground = true)
@Composable
private fun CastMiniPlayerPlayingLightPhonePreview() {
    FindroidTheme(darkTheme = false) {
        Box(modifier = Modifier.padding(16.dp)) {
            CastMiniPlayerLayout(
                connectedDevice = Device("1", "Living Room TV"),
                uiState =
                    previewUiState(
                        title = "Pilot",
                        seriesName = "Breaking Bad",
                        episodeInfo = "S01:E01",
                        isMovie = false,
                        aspectRatio = 16f / 9f,
                        fileLoaded = true,
                    ),
                playbackState =
                    CastPlayerState(
                        status = CastPlaybackStatus.PLAYING,
                        currentPosition = 2500L,
                        duration = 10000L,
                    ),
                onTogglePlayback = {},
                onClick = {},
            )
        }
    }
}

private fun previewUiState(
    title: String = dummyMovie.name,
    seriesName: String? = null,
    episodeInfo: String? = null,
    poster: PlayerImage? = null,
    isMovie: Boolean = true,
    aspectRatio: Float = 16f / 9f,
    fileLoaded: Boolean = false,
) =
    CastPlayerViewModel.UiState(
        currentItemTitle =
            CastPlayerViewModel.CurrentItemTitle(
                seriesName = seriesName,
                episodeInfo = episodeInfo,
                title = title,
            ),
        currentItemPoster = poster,
        isMovie = isMovie,
        defaultAspectRatio = aspectRatio,
        trickplayAspectRatio = null,
        currentSegment = null,
        currentSkipButtonStringRes = 0,
        currentTrickplay = null,
        currentChapters = emptyList(),
        fileLoaded = fileLoaded,
    )
