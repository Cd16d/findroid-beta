package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.film.presentation.home.HomeAction
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.HomeSection
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import java.util.UUID

/**
 * Remembers a [LazyListState] for horizontal Home rows that smoothly scrolls back to index 0 when a
 * new item is prepended at the start while the row was still positioned at the beginning.
 */
@Composable
internal fun rememberAutoScrollToStartRowState(items: List<FindroidItem>): LazyListState {
    val listState = rememberLazyListState()
    val firstItemId = items.firstOrNull()?.id
    var previousFirstItemId by remember { mutableStateOf(firstItemId) }

    LaunchedEffect(firstItemId) {
        val prevId = previousFirstItemId
        if (
            prevId != null &&
                firstItemId != null &&
                firstItemId != prevId &&
                !listState.isScrollInProgress
        ) {
            val wasAtStart =
                listState.firstVisibleItemIndex == 0 ||
                    items.getOrNull(listState.firstVisibleItemIndex)?.id == prevId
            if (wasAtStart) {
                listState.animateScrollToItem(0)
            }
        }
        previousFirstItemId = firstItemId
    }

    return listState
}

/**
 * Horizontal row component rendering a home screen section with a title header.
 *
 * @param section HomeSection model containing section title and items.
 * @param itemsPadding Padding applied around row items.
 * @param onAction Callback invoked when an item in the section is interacted with.
 * @param modifier Optional modifier applied to column layout. Defaults to [Modifier].
 * @param hideEpisodeSpoilers Whether episode thumbnails/overviews should be blurred. Defaults to
 *   `false`.
 */
@Composable
fun HomeSection(
    section: HomeSection,
    itemsPadding: PaddingValues,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
    hideEpisodeSpoilers: Boolean = false,
) {
    val distinctItems = remember(section.items) { section.items.distinctBy { it.id } }
    val listState = rememberAutoScrollToStartRowState(distinctItems)

    Column(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth().height(42.dp).padding(itemsPadding)) {
            Text(
                text = section.name.asString(),
                modifier = Modifier.align(Alignment.CenterStart),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
        LazyRow(
            state = listState,
            contentPadding = itemsPadding,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
        ) {
            items(distinctItems, key = { it.id }) { item ->
                ItemCard(
                    item = item,
                    direction = Direction.HORIZONTAL,
                    onClick = { onAction(HomeAction.OnItemClick(item)) },
                    modifier = Modifier.animateItem(),
                    hideEpisodeSpoilers = hideEpisodeSpoilers,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeSectionPreview() {
    FindroidTheme {
        HomeSection(
            section =
                HomeSection(
                    id = UUID.randomUUID(),
                    name = UiText.DynamicString("Continue Watching"),
                    items = listOf(dummyMovie),
                ),
            itemsPadding = PaddingValues(16.dp),
            onAction = {},
        )
    }
}
