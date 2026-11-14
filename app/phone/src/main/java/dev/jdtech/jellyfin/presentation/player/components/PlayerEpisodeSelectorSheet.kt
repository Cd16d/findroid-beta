package dev.jdtech.jellyfin.presentation.player.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.core.presentation.theme.CentralShapes
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.player.core.R as PlayerR
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.presentation.components.SpoilerMask
import dev.jdtech.jellyfin.presentation.film.components.Direction
import dev.jdtech.jellyfin.presentation.film.components.ItemPoster
import dev.jdtech.jellyfin.presentation.film.components.PlayedBadge
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme

private val EpisodeThumbShape = RoundedCornerShape(16.dp)

/** Tab options for unified in-player navigation sheet. */
enum class PlayerNavTab {
    CHAPTERS,
    EPISODES,
}

/** Top header segmented button selector for switching between Chapters and Episodes. */
@Composable
fun PlayerNavigationHeaderSelector(
    selectedTab: PlayerNavTab,
    onTabSelected: (PlayerNavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        SegmentedButton(
            selected = selectedTab == PlayerNavTab.CHAPTERS,
            onClick = { onTabSelected(PlayerNavTab.CHAPTERS) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            colors =
                SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primary,
                    activeContentColor = MaterialTheme.colorScheme.onPrimary,
                    inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            icon = {},
        ) {
            Text(
                text = stringResource(id = PlayerR.string.chapters),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        SegmentedButton(
            selected = selectedTab == PlayerNavTab.EPISODES,
            onClick = { onTabSelected(PlayerNavTab.EPISODES) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            colors =
                SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primary,
                    activeContentColor = MaterialTheme.colorScheme.onPrimary,
                    inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            icon = {},
        ) {
            Text(
                text = stringResource(id = PlayerR.string.episodes),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/**
 * Unified in-player navigation sheet hosting chapters or episodes based on [selectedTab].
 *
 * @param viewModel Active [PlayerViewModel].
 * @param onDismiss Callback to dismiss the player sheet.
 * @param modifier Optional modifier applied to root container.
 * @param selectedTab Current active tab ([PlayerNavTab.CHAPTERS] or [PlayerNavTab.EPISODES]).
 */
@Composable
fun PlayerChaptersAndEpisodesSheet(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    selectedTab: PlayerNavTab = PlayerNavTab.CHAPTERS,
) {
    val uiState by viewModel.uiState.collectAsState()
    val isSeries = uiState.seriesId != null

    if (!isSeries || selectedTab == PlayerNavTab.CHAPTERS) {
        PhoneChaptersList(viewModel = viewModel)
    } else {
        PlayerEpisodeSelectorContent(
            viewModel = viewModel,
            onEpisodeSelected = { episode ->
                viewModel.playEpisode(episode)
                onDismiss()
            },
            modifier = modifier,
        )
    }
}

/**
 * Episode tab content displaying the horizontal season rail and vertical list of season episodes.
 *
 * @param viewModel Active [PlayerViewModel].
 * @param onEpisodeSelected Callback when an episode card is tapped.
 * @param modifier Optional modifier applied to column.
 */
@Composable
fun PlayerEpisodeSelectorContent(
    viewModel: PlayerViewModel,
    onEpisodeSelected: (FindroidEpisode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val seasons = uiState.seasons
    val selectedSeasonId = uiState.selectedSeasonId
    val episodes = uiState.seasonEpisodes
    val currentEpisodeId = uiState.currentEpisodeId
    val episodeListState = rememberLazyListState()

    val currentEpisodeIndex =
        remember(currentEpisodeId, episodes) { episodes.indexOfFirst { it.id == currentEpisodeId } }

    LaunchedEffect(currentEpisodeIndex) {
        if (currentEpisodeIndex >= 0) {
            episodeListState.animateScrollToItem(currentEpisodeIndex)
        }
    }

    LaunchedEffect(selectedSeasonId, episodes.size) {
        if (selectedSeasonId != null && episodes.isEmpty() && !uiState.isLoadingEpisodes) {
            viewModel.selectSeason(selectedSeasonId)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Top Season Rail
        if (seasons.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(seasons, key = { it.id }) { season ->
                    val isSelected = season.id == selectedSeasonId
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.selectSeason(season.id) },
                        label = {
                            Text(
                                text =
                                    season.name.ifBlank {
                                        stringResource(
                                            id = CoreR.string.season_number,
                                            season.indexNumber,
                                        )
                                    },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            )
                        },
                        colors =
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                    )
                }
            }
        }

        if (uiState.isLoadingEpisodes) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                )
            }
        } else if (episodes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(id = PlayerR.string.no_episodes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = episodeListState,
                contentPadding =
                    PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(episodes, key = { it.id }) { episode ->
                    val isCurrent = episode.id == currentEpisodeId
                    PlayerEpisodeCard(
                        episode = episode,
                        isCurrent = isCurrent,
                        hideEpisodeSpoilers = uiState.hideEpisodeSpoilers,
                        onClick = { onEpisodeSelected(episode) },
                    )
                }
            }
        }
    }
}

/**
 * Episode item card designed for the in-player sheet matching [ChapterCard] surface styling.
 *
 * @param episode Episode model containing media metadata.
 * @param isCurrent Whether this episode is currently playing.
 * @param hideEpisodeSpoilers Whether anti-spoiler protection should blur artwork and overview.
 * @param onClick Callback when episode is tapped.
 * @param modifier Optional modifier applied to surface.
 */
@Composable
fun PlayerEpisodeCard(
    episode: FindroidEpisode,
    isCurrent: Boolean,
    hideEpisodeSpoilers: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isRevealed by rememberSaveable(episode.id) { mutableStateOf(false) }
    val isEpisodeSpoiler = hideEpisodeSpoilers && !episode.played

    val thumbBorder = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.15f))

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CentralShapes.GroupSingle,
        color =
            if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surfaceContainerLow,
        border =
            if (isCurrent) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
            else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
        onClick = onClick,
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier.height(64.dp)
                        .aspectRatio(16f / 9f)
                        .clip(EpisodeThumbShape)
                        .border(thumbBorder, EpisodeThumbShape)
                        .background(Color.Black)
            ) {
                ItemPoster(
                    item = episode,
                    direction = Direction.HORIZONTAL,
                    modifier = Modifier.fillMaxSize(),
                    blurOnly = isEpisodeSpoiler && !isRevealed,
                )

                if (episode.played) {
                    PlayedBadge(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
                }

                if (
                    episode.playbackPositionTicks > 0 && episode.runtimeTicks > 0 && !episode.played
                ) {
                    val progress =
                        (episode.playbackPositionTicks.toFloat() / episode.runtimeTicks.toFloat())
                            .coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier =
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Black.copy(alpha = 0.5f),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text =
                        stringResource(
                            id = CoreR.string.episode_name,
                            episode.indexNumber,
                            episode.name,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (isCurrent) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            text = stringResource(id = PlayerR.string.now_playing),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }

                if (episode.overview.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    SpoilerMask(
                        enabled = isEpisodeSpoiler,
                        isRevealed = isRevealed,
                        onRevealChange = { isRevealed = it },
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = episode.overview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PlayerEpisodeCardPreview() {
    FindroidTheme {
        PlayerEpisodeCard(
            episode = dummyEpisode,
            isCurrent = true,
            hideEpisodeSpoilers = false,
            onClick = {},
        )
    }
}
