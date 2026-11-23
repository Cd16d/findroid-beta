package dev.jdtech.jellyfin.presentation.player.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import dev.jdtech.jellyfin.presentation.film.components.Direction
import dev.jdtech.jellyfin.presentation.film.components.ItemPoster
import dev.jdtech.jellyfin.presentation.film.components.PlayedBadge
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme

private val EpisodeThumbShape = RoundedCornerShape(16.dp)

/** Tab options for unified in-player navigation sheet. */
enum class PlayerNavTab {
    CHAPTERS,
    EPISODES,
    INFO,
}

/**
 * Top header horizontal connected button selector for switching between Chapters, Episodes, and
 * Info.
 *
 * Outer buttons have fully rounded outer corners and dynamic inner corners. Middle buttons have
 * dynamic inner corners on both sides. When selected, the active button transitions into a full
 * pill shape.
 */
@Composable
fun PlayerNavigationHeaderSelector(
    tabs: List<PlayerNavTab>,
    selectedTab: PlayerNavTab,
    onTabSelected: (PlayerNavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val n = tabs.size
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = tab == selectedTab
            val startRadius by
                animateDpAsState(
                    targetValue = if (isSelected || index == 0) 50.dp else 6.dp,
                    animationSpec = tween(durationMillis = 200),
                    label = "tabStartRadius_$index",
                )
            val endRadius by
                animateDpAsState(
                    targetValue = if (isSelected || index == n - 1) 50.dp else 6.dp,
                    animationSpec = tween(durationMillis = 200),
                    label = "tabEndRadius_$index",
                )
            val shape =
                RoundedCornerShape(
                    topStart = startRadius,
                    bottomStart = startRadius,
                    topEnd = endRadius,
                    bottomEnd = endRadius,
                )

            Surface(
                modifier = Modifier.weight(1f).height(44.dp),
                shape = shape,
                color =
                    if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor =
                    if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { onTabSelected(tab) },
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text =
                            stringResource(
                                id =
                                    when (tab) {
                                        PlayerNavTab.CHAPTERS -> PlayerR.string.chapters
                                        PlayerNavTab.EPISODES -> PlayerR.string.episodes
                                        PlayerNavTab.INFO -> PlayerR.string.player_tab_info
                                    },
                            ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Unified in-player navigation sheet hosting chapters, episodes, or media details based on
 * [selectedTab].
 *
 * @param viewModel Active [PlayerViewModel].
 * @param onDismiss Callback to dismiss the player sheet.
 * @param modifier Optional modifier applied to root container.
 * @param selectedTab Current active tab ([PlayerNavTab.CHAPTERS], [PlayerNavTab.EPISODES], or
 *   [PlayerNavTab.INFO]).
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

    val resolvedTab =
        if (!isSeries && selectedTab == PlayerNavTab.EPISODES) PlayerNavTab.CHAPTERS
        else selectedTab

    when (resolvedTab) {
        PlayerNavTab.CHAPTERS -> {
            PhoneChaptersList(viewModel = viewModel)
        }
        PlayerNavTab.EPISODES -> {
            PlayerEpisodeSelectorContent(
                viewModel = viewModel,
                onEpisodeSelected = { episode ->
                    viewModel.playEpisode(episode)
                    onDismiss()
                },
                modifier = modifier,
            )
        }
        PlayerNavTab.INFO -> {
            PlayerItemInfoContent(
                viewModel = viewModel,
                modifier = modifier,
            )
        }
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
        } else if (uiState.isSeasonLoadError || uiState.isSeasonEpisodesError) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(id = CoreR.string.error_loading_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(
                    onClick = {
                        if (uiState.isSeasonLoadError) {
                            viewModel.retryLoadSeasons()
                        } else if (selectedSeasonId != null) {
                            viewModel.selectSeason(selectedSeasonId)
                        }
                    },
                ) {
                    Text(text = stringResource(id = CoreR.string.retry))
                }
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
    val isEpisodeSpoiler = hideEpisodeSpoilers && !episode.played
    val isUnavailable = episode.missing || !episode.canPlay

    val thumbBorder = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.15f))

    Surface(
        modifier = modifier.fillMaxWidth().alpha(if (isUnavailable) 0.6f else 1f),
        shape = CentralShapes.GroupSingle,
        color =
            if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surfaceVariant,
        border =
            if (isCurrent) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
            else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
        onClick = onClick,
        enabled = !isUnavailable,
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
                        .background(Color.Black),
            ) {
                ItemPoster(
                    item = episode,
                    direction = Direction.HORIZONTAL,
                    modifier = Modifier.fillMaxSize(),
                    blurOnly = isEpisodeSpoiler,
                    targetWidth = 114.dp,
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
                            Modifier.align(Alignment.BottomCenter)
                                .padding(horizontal = 6.dp, vertical = 6.dp)
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Black.copy(alpha = 0.6f),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                val episodeTitle =
                    episode.name.ifBlank {
                        stringResource(id = CoreR.string.episode_number, episode.indexNumber)
                    }

                Text(
                    text = episodeTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (episode.indexNumber > 0) {
                        val indexEnd = episode.indexNumberEnd
                        val episodeCode =
                            if (indexEnd != null && indexEnd > episode.indexNumber) {
                                stringResource(
                                    id = PlayerR.string.episode_short_with_end,
                                    episode.indexNumber,
                                    indexEnd,
                                )
                            } else {
                                stringResource(
                                    id = PlayerR.string.episode_short,
                                    episode.indexNumber,
                                )
                            }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color =
                                if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else
                                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(
                                        alpha = 0.7f,
                                    ),
                        ) {
                            Text(
                                text = episodeCode,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color =
                                    if (isCurrent) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }

                    if (isUnavailable) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Text(
                                text = stringResource(id = PlayerR.string.unavailable),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    } else if (isCurrent) {
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
                }
            }
        }
    }
}

@Preview(name = "Episode Card", showBackground = true, backgroundColor = 0xFF141416)
@Composable
private fun PlayerEpisodeCardPreview() {
    FindroidTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(modifier = Modifier.padding(16.dp)) {
                PlayerEpisodeCard(
                    episode = dummyEpisode,
                    isCurrent = true,
                    hideEpisodeSpoilers = false,
                    onClick = {},
                )
            }
        }
    }
}

@Preview(name = "Episode Card - Unavailable", showBackground = true, backgroundColor = 0xFF141416)
@Composable
private fun PlayerEpisodeCardUnavailablePreview() {
    FindroidTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(modifier = Modifier.padding(16.dp)) {
                PlayerEpisodeCard(
                    episode = dummyEpisode.copy(missing = true, canPlay = false),
                    isCurrent = false,
                    hideEpisodeSpoilers = false,
                    onClick = {},
                )
            }
        }
    }
}

@Preview(
    name = "Series Navigation Header (3 Tabs)",
    showBackground = true,
    backgroundColor = 0xFF141416,
)
@Composable
private fun PlayerNavigationHeaderSelectorSeriesPreview() {
    FindroidTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(modifier = Modifier.padding(16.dp)) {
                PlayerNavigationHeaderSelector(
                    selectedTab = PlayerNavTab.EPISODES,
                    onTabSelected = {},
                    tabs = listOf(PlayerNavTab.CHAPTERS, PlayerNavTab.EPISODES, PlayerNavTab.INFO),
                )
            }
        }
    }
}

@Preview(
    name = "Movie Navigation Header (2 Tabs)",
    showBackground = true,
    backgroundColor = 0xFF141416,
)
@Composable
private fun PlayerNavigationHeaderSelectorMoviePreview() {
    FindroidTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(modifier = Modifier.padding(16.dp)) {
                PlayerNavigationHeaderSelector(
                    selectedTab = PlayerNavTab.INFO,
                    onTabSelected = {},
                    tabs = listOf(PlayerNavTab.CHAPTERS, PlayerNavTab.INFO),
                )
            }
        }
    }
}

@Preview(name = "Season Chips Rail", showBackground = true, backgroundColor = 0xFF141416)
@Composable
private fun PlayerSeasonChipsRailPreview() {
    FindroidTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text("Season 1") },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                )
                FilterChip(
                    selected = false,
                    onClick = {},
                    label = { Text("Season 2") },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                )
                FilterChip(
                    selected = false,
                    onClick = {},
                    label = { Text("Specials") },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                )
            }
        }
    }
}
