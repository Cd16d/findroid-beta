package dev.jdtech.jellyfin.presentation.player.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.core.presentation.dummy.dummyPersonDetail
import dev.jdtech.jellyfin.core.presentation.theme.CentralShapes
import dev.jdtech.jellyfin.core.presentation.theme.PlayerCardBackground
import dev.jdtech.jellyfin.core.presentation.theme.Yellow
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidItemPerson
import dev.jdtech.jellyfin.models.FindroidItemPersonImage
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidPerson
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.player.core.R as PlayerR
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.utils.toBlurHashPainter
import dev.jdtech.jellyfin.utils.toOptimizedImageUri
import java.util.Locale
import java.util.UUID
import org.jellyfin.sdk.model.api.PersonKind

/**
 * In-player information view presenting movie or episode artwork, metadata, ratings, external
 * provider links, overview, and separate expandable Cast and Crew sections with in-player actor
 * exploration.
 *
 * @param viewModel Active [PlayerViewModel] supplying playback state and media details.
 * @param modifier Optional [Modifier] applied to root container.
 */
@Composable
fun PlayerItemInfoContent(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // 1. In-player person exploration sub-view (Amazon X-Ray style)
    if (uiState.selectedPerson != null) {
        PlayerPersonDetailContent(
            person = uiState.selectedPerson!!,
            items = uiState.selectedPersonItems,
            isLoading = uiState.isLoadingPerson,
            onBack = { viewModel.clearSelectedPerson() },
            modifier = modifier,
        )
        return
    }

    if (uiState.isLoadingPerson && uiState.selectedPerson == null) {
        Box(
            modifier = modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        return
    }

    val currentItem = uiState.currentItem
    val isSeries = uiState.seriesId != null
    val title = currentItem?.name?.ifBlank { uiState.currentItemTitle } ?: uiState.currentItemTitle

    // Subtitle construction
    val subtitle: String? =
        when (currentItem) {
            is FindroidEpisode -> {
                val episodeCode =
                    if (currentItem.parentIndexNumber > 0 && currentItem.indexNumber > 0) {
                        "S${currentItem.parentIndexNumber}:E${currentItem.indexNumber}"
                    } else if (currentItem.indexNumber > 0) {
                        "E${currentItem.indexNumber}"
                    } else {
                        ""
                    }
                val seriesName =
                    currentItem.seriesName.ifBlank { uiState.currentShow?.name.orEmpty() }
                when {
                    episodeCode.isNotBlank() && seriesName.isNotBlank() ->
                        "$episodeCode • $seriesName"
                    episodeCode.isNotBlank() -> episodeCode
                    else -> seriesName.ifBlank { null }
                }
            }
            is FindroidMovie -> {
                val year = currentItem.productionYear?.toString()
                val genres = currentItem.genres.take(2).joinToString(", ")
                when {
                    year != null && genres.isNotBlank() -> "$year • $genres"
                    year != null -> year
                    else -> genres.ifBlank { null }
                }
            }
            else -> uiState.currentItemSubtitle
        }

    // Ratings & External IDs
    val communityRating: Float? =
        when (currentItem) {
            is FindroidMovie -> currentItem.communityRating
            is FindroidEpisode ->
                currentItem.communityRating ?: uiState.currentShow?.communityRating
            else -> null
        }

    val criticRating: Float? =
        when (currentItem) {
            is FindroidMovie -> currentItem.criticRating
            is FindroidEpisode -> currentItem.criticRating ?: uiState.currentShow?.criticRating
            else -> null
        }

    val providerIds: Map<String, String> =
        when (currentItem) {
            is FindroidMovie -> currentItem.providerIds
            is FindroidEpisode ->
                currentItem.providerIds.ifEmpty { uiState.currentShow?.providerIds ?: emptyMap() }
            else -> emptyMap()
        }

    // Cast vs Crew partition
    val cast =
        remember(uiState.currentItemPeople) {
            uiState.currentItemPeople
                .filter { it.type == PersonKind.ACTOR || it.type == PersonKind.GUEST_STAR }
                .distinctBy { it.id to it.role }
        }

    val crew =
        remember(uiState.currentItemPeople) {
            uiState.currentItemPeople
                .filter { it.type != PersonKind.ACTOR && it.type != PersonKind.GUEST_STAR }
                .distinctBy { (it.id to it.type) to it.role }
        }

    val (artworkUri, artworkBlurHash) =
        when (currentItem) {
            is FindroidEpisode ->
                (currentItem.images.primary?.uri ?: uiState.currentImages?.primary?.uri) to
                    (currentItem.images.primary?.blurHash
                        ?: uiState.currentImages?.primary?.blurHash)
            is FindroidMovie ->
                (currentItem.images.primary?.uri ?: uiState.currentImages?.primary?.uri) to
                    (currentItem.images.primary?.blurHash
                        ?: uiState.currentImages?.primary?.blurHash)
            else -> uiState.currentImages?.primary?.uri to uiState.currentImages?.primary?.blurHash
        }

    PlayerItemInfoLayout(
        title = title,
        subtitle = subtitle,
        artworkUri = artworkUri,
        artworkBlurHash = artworkBlurHash,
        isSeries = isSeries,
        communityRating = communityRating,
        criticRating = criticRating,
        providerIds = providerIds,
        overview = currentItem?.overview.orEmpty(),
        cast = cast,
        crew = crew,
        onPersonClick = { person -> viewModel.selectPerson(person.id) },
        onOpenUrl = { url ->
            try {
                val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                context.startActivity(intent)
            } catch (_: Exception) {}
        },
        modifier = modifier,
    )
}

/** Stateless layout displaying media details, ratings, providers, overview, and cast/crew cards. */
@Composable
private fun PlayerItemInfoLayout(
    title: String,
    subtitle: String?,
    artworkUri: Uri?,
    artworkBlurHash: String?,
    isSeries: Boolean,
    communityRating: Float?,
    criticRating: Float?,
    providerIds: Map<String, String>,
    overview: String,
    cast: List<FindroidItemPerson>,
    crew: List<FindroidItemPerson>,
    onPersonClick: (FindroidItemPerson) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val artworkAspectRatio = if (isSeries) 16f / 9f else 2f / 3f
    val blurHashPainter = remember(artworkBlurHash) { artworkBlurHash.toBlurHashPainter() }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(scrollState).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1. Artwork Poster Header
        Surface(
            modifier =
                Modifier.fillMaxWidth(if (isSeries) 1f else 0.65f)
                    .aspectRatio(artworkAspectRatio)
                    .heightIn(max = 260.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            shadowElevation = 4.dp,
        ) {
            val optimizedUri =
                artworkUri.toOptimizedImageUri(
                    widthDp = if (isSeries) 400.dp else 240.dp,
                    heightDp = 260.dp,
                )
            AsyncImage(
                model = optimizedUri,
                contentDescription = title,
                placeholder = blurHashPainter,
                error = blurHashPainter,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        // 2. Title & Subtitle
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 3. Ratings (Community Stars & Critic Scores)
        val hasCommunityScore = communityRating != null
        val hasCriticScore = criticRating != null

        if (hasCommunityScore || hasCriticScore) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Community Score (Neutral yellow star representation)
                if (communityRating != null) {
                    PlayerRatingPill(
                        iconRes = CoreR.drawable.ic_star,
                        iconTint = Yellow,
                        label = String.format(Locale.ROOT, "%.1f", communityRating),
                        onClick = null,
                    )
                }

                // Critic Score
                if (criticRating != null) {
                    val iconRes =
                        when {
                            criticRating >= 60f -> CoreR.drawable.ic_rotten_tomatoes_fresh
                            else -> CoreR.drawable.ic_rotten_tomatoes_rotten
                        }
                    val rtId = providerIds["RottenTomatoes"]
                    val rtUrl =
                        if (!rtId.isNullOrBlank()) {
                            if (rtId.startsWith("http")) rtId
                            else "https://www.rottentomatoes.com/$rtId"
                        } else {
                            null
                        }

                    PlayerRatingPill(
                        iconRes = iconRes,
                        label = "${criticRating.toInt()}%",
                        onClick = rtUrl?.let { { onOpenUrl(it) } },
                    )
                }
            }
        }

        // 4. External Data Provider Links
        val hasImdb = !providerIds["Imdb"].isNullOrBlank()
        val hasTmdb = !providerIds["Tmdb"].isNullOrBlank()
        val hasTvdb = !providerIds["Tvdb"].isNullOrBlank()
        val hasRtWithoutCritic =
            !providerIds["RottenTomatoes"].isNullOrBlank() && criticRating == null

        if (hasImdb || hasTmdb || hasTvdb || hasRtWithoutCritic) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // IMDb
                val imdbId = providerIds["Imdb"]
                if (!imdbId.isNullOrBlank()) {
                    PlayerRatingPill(
                        iconRes = CoreR.drawable.ic_imdb,
                        label = "IMDb",
                        onClick = { onOpenUrl("https://www.imdb.com/title/$imdbId/") },
                    )
                }

                // TheMovieDb (TMDb)
                val tmdbId = providerIds["Tmdb"]
                if (!tmdbId.isNullOrBlank()) {
                    val type = if (isSeries) "tv" else "movie"
                    PlayerRatingPill(
                        iconRes = CoreR.drawable.ic_tmdb,
                        label = "TMDb",
                        onClick = { onOpenUrl("https://www.themoviedb.org/$type/$tmdbId") },
                    )
                }

                // TheTVDB
                val tvdbId = providerIds["Tvdb"]
                if (!tvdbId.isNullOrBlank()) {
                    val type = if (isSeries) "series" else "movie"
                    PlayerRatingPill(
                        iconRes = CoreR.drawable.ic_tvdb,
                        label = "TheTVDB",
                        onClick = { onOpenUrl("https://thetvdb.com/dereferrer/$type/$tvdbId") },
                    )
                }

                // Rotten Tomatoes link (if no critic score was present above)
                val rtId = providerIds["RottenTomatoes"]
                if (!rtId.isNullOrBlank() && criticRating == null) {
                    val url =
                        if (rtId.startsWith("http")) rtId
                        else "https://www.rottentomatoes.com/$rtId"
                    PlayerRatingPill(
                        iconRes = CoreR.drawable.ic_rotten_tomatoes_neutral,
                        label = "Rotten Tomatoes",
                        onClick = { onOpenUrl(url) },
                    )
                }
            }
        }

        // 5. Expandable Description / Overview
        if (overview.isNotBlank()) {
            var isExpanded by remember { mutableStateOf(false) }
            var canExpand by remember { mutableStateOf(false) }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { textLayoutResult ->
                        if (!canExpand && textLayoutResult.hasVisualOverflow) {
                            canExpand = true
                        }
                    },
                )
                if (canExpand || isExpanded) {
                    Text(
                        text =
                            stringResource(
                                if (isExpanded) PlayerR.string.show_less
                                else PlayerR.string.show_more
                            ),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier.clip(RoundedCornerShape(4.dp))
                                .clickable { isExpanded = !isExpanded }
                                .padding(vertical = 4.dp),
                    )
                }
            }
        }

        // 6. Collapsible Cast Section Card
        if (cast.isNotEmpty()) {
            PlayerPeopleSectionCard(
                title = stringResource(id = PlayerR.string.cast),
                count = cast.size,
                people = cast,
                onPersonClick = onPersonClick,
                emptyMessage = stringResource(id = PlayerR.string.no_cast_info),
            )
        }

        // 7. Collapsible Crew Section Card
        if (crew.isNotEmpty()) {
            PlayerPeopleSectionCard(
                title = stringResource(id = PlayerR.string.crew),
                count = crew.size,
                people = crew,
                onPersonClick = onPersonClick,
                emptyMessage = stringResource(id = PlayerR.string.no_crew_info),
            )
        }
    }
}

/**
 * Clickable rating or provider pill with reviewer/platform branding icon, formatted score or label,
 * and optional external link indicator.
 */
@Composable
private fun PlayerRatingPill(
    iconRes: Int,
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    iconTint: Color = Color.Unspecified,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = iconTint,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (onClick != null) {
                Icon(
                    painter = painterResource(id = CoreR.drawable.ic_external_link),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/** Collapsible section card hosting vertical rows of people (Cast or Crew). */
@Composable
private fun PlayerPeopleSectionCard(
    title: String,
    count: Int,
    people: List<FindroidItemPerson>,
    onPersonClick: (FindroidItemPerson) -> Unit,
    modifier: Modifier = Modifier,
    emptyMessage: String = "",
) {
    var isExpanded by rememberSaveable(title) { mutableStateOf(true) }
    val rotationAngle by
        animateFloatAsState(
            targetValue = if (isExpanded) 180f else 0f,
            animationSpec = tween(durationMillis = 200),
            label = "peopleChevronRotation",
        )

    Surface(
        shape = CentralShapes.GroupSingle,
        color = PlayerCardBackground,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { isExpanded = !isExpanded }
                        .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (count > 0) {
                        Text(
                            text = "($count)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    painter = painterResource(id = CoreR.drawable.ic_chevron_down),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).rotate(rotationAngle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (people.isEmpty()) {
                        Text(
                            text = emptyMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        people.forEachIndexed { index, person ->
                            PlayerPersonRow(
                                person = person,
                                onClick = { onPersonClick(person) },
                            )
                            if (index < people.lastIndex) {
                                HorizontalDivider(
                                    color = Color.White.copy(alpha = 0.05f),
                                    modifier = Modifier.padding(start = 56.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Single person row within a Cast or Crew section card. */
@Composable
private fun PlayerPersonRow(
    person: FindroidItemPerson,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val image = person.image
        val imageUri = image.uri.toOptimizedImageUri(widthDp = 44.dp, heightDp = 44.dp)
        val blurPlaceholder = remember(image.blurHash) { image.blurHash.toBlurHashPainter() }

        Box(
            modifier =
                Modifier.size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = CoreR.drawable.ic_user),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            AsyncImage(
                model = imageUri,
                contentDescription = null,
                placeholder = blurPlaceholder,
                error = blurPlaceholder,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = person.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val roleText =
                if (person.role.isNotBlank()) {
                    person.role
                } else {
                    when (person.type) {
                        PersonKind.DIRECTOR -> stringResource(id = PlayerR.string.director)
                        PersonKind.WRITER -> stringResource(id = PlayerR.string.writer)
                        PersonKind.PRODUCER -> stringResource(id = PlayerR.string.producer)
                        else ->
                            person.type.name.lowercase().replaceFirstChar {
                                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                            }
                    }
                }
            if (roleText.isNotBlank()) {
                Text(
                    text = roleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Icon(
            painter = painterResource(id = CoreR.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

/**
 * In-player person exploration view (Amazon X-Ray style) showing photo, name, bio, and other server
 * items starring this person.
 */
@Composable
private fun PlayerPersonDetailContent(
    person: FindroidPerson,
    items: List<FindroidItem>,
    isLoading: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1. Back button header
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onBack)
                    .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painter = painterResource(id = CoreR.drawable.ic_arrow_left),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(id = PlayerR.string.player_tab_info),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // 2. Profile Card
        Surface(
            shape = CentralShapes.GroupSingle,
            color = PlayerCardBackground,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val image = person.images.primary
                val imageUri = image?.uri.toOptimizedImageUri(widthDp = 96.dp, heightDp = 96.dp)
                val blurPlaceholder =
                    remember(image?.blurHash) { image?.blurHash.toBlurHashPainter() }

                Box(
                    modifier =
                        Modifier.size(96.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(id = CoreR.drawable.ic_user),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    AsyncImage(
                        model = imageUri,
                        contentDescription = null,
                        placeholder = blurPlaceholder,
                        error = blurPlaceholder,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }

                Text(
                    text = person.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )

                // Overview / Biography
                if (person.overview.isNotBlank()) {
                    var isExpanded by remember { mutableStateOf(false) }
                    var canExpand by remember { mutableStateOf(false) }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = person.overview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { textLayoutResult ->
                                if (!canExpand && textLayoutResult.hasVisualOverflow) {
                                    canExpand = true
                                }
                            },
                        )
                        if (canExpand || isExpanded) {
                            Text(
                                text =
                                    stringResource(
                                        if (isExpanded) PlayerR.string.show_less
                                        else PlayerR.string.show_more
                                    ),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier =
                                    Modifier.clip(RoundedCornerShape(4.dp))
                                        .clickable { isExpanded = !isExpanded }
                                        .padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        // 3. Server Items ("Also on this server")
        Surface(
            shape = CentralShapes.GroupSingle,
            color = PlayerCardBackground,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(id = PlayerR.string.appeared_in),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (items.isNotEmpty()) {
                        Text(
                            text = "(${items.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp,
                        )
                    }
                } else if (items.isEmpty()) {
                    Text(
                        text = stringResource(id = PlayerR.string.no_other_items_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items.forEachIndexed { index, item ->
                            PlayerPersonMediaItemRow(item = item)
                            if (index < items.lastIndex) {
                                HorizontalDivider(
                                    color = Color.White.copy(alpha = 0.05f),
                                    modifier = Modifier.padding(start = 54.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Row representation of a media item in the actor's server catalog. */
@Composable
private fun PlayerPersonMediaItemRow(
    item: FindroidItem,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val image = item.images.primary
        val imageUri = image?.uri.toOptimizedImageUri(widthDp = 42.dp, heightDp = 63.dp)
        val blurPlaceholder = remember(image?.blurHash) { image?.blurHash.toBlurHashPainter() }

        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.width(42.dp).aspectRatio(2f / 3f),
        ) {
            AsyncImage(
                model = imageUri,
                contentDescription = null,
                placeholder = blurPlaceholder,
                error = blurPlaceholder,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val year =
                when (item) {
                    is FindroidMovie -> item.productionYear?.toString()
                    is FindroidShow -> item.productionYear?.toString()
                    else -> null
                }
            val itemType =
                when (item) {
                    is FindroidMovie -> stringResource(id = CoreR.string.movies_label)
                    is FindroidShow -> stringResource(id = CoreR.string.shows_label)
                    else -> null
                }
            val subtitle = listOfNotNull(year, itemType).joinToString(" • ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// =========================================================================================
// Compose Previews
// =========================================================================================

private val dummyPreviewCast =
    listOf(
        FindroidItemPerson(
            id = UUID.randomUUID(),
            name = "Bryan Cranston",
            type = PersonKind.ACTOR,
            role = "Walter White",
            image = FindroidItemPersonImage(uri = null, blurHash = null),
        ),
        FindroidItemPerson(
            id = UUID.randomUUID(),
            name = "Aaron Paul",
            type = PersonKind.ACTOR,
            role = "Jesse Pinkman",
            image = FindroidItemPersonImage(uri = null, blurHash = null),
        ),
    )

private val dummyPreviewCrew =
    listOf(
        FindroidItemPerson(
            id = UUID.randomUUID(),
            name = "Vince Gilligan",
            type = PersonKind.DIRECTOR,
            role = "Director",
            image = FindroidItemPersonImage(uri = null, blurHash = null),
        ),
        FindroidItemPerson(
            id = UUID.randomUUID(),
            name = "Mark Johnson",
            type = PersonKind.PRODUCER,
            role = "Executive Producer",
            image = FindroidItemPersonImage(uri = null, blurHash = null),
        ),
    )

@Preview(
    name = "Movie Info Layout",
    showBackground = true,
    backgroundColor = 0xFF141416,
    widthDp = 420,
    heightDp = 800,
)
@Composable
private fun PlayerItemInfoMoviePreview() {
    FindroidTheme(darkTheme = true) {
        Surface(color = PlayerCardBackground) {
            Box(modifier = Modifier.padding(16.dp)) {
                PlayerItemInfoLayout(
                    title = "Inception",
                    subtitle = "2010 • Sci-Fi, Action",
                    artworkUri = null,
                    artworkBlurHash = null,
                    isSeries = false,
                    communityRating = 8.8f,
                    criticRating = 87f,
                    providerIds = mapOf("Imdb" to "tt1375666", "Tmdb" to "27205", "Tvdb" to "1234"),
                    overview =
                        "A thief who steals corporate secrets through the use of dream-sharing technology is given the inverse task of planting an idea into the mind of a C.E.O., but his tragic past may doom the project and his team to disaster.",
                    cast = dummyPreviewCast,
                    crew = dummyPreviewCrew,
                    onPersonClick = {},
                    onOpenUrl = {},
                )
            }
        }
    }
}

@Preview(
    name = "Episode Info Layout",
    showBackground = true,
    backgroundColor = 0xFF141416,
    widthDp = 420,
    heightDp = 800,
)
@Composable
private fun PlayerItemInfoEpisodePreview() {
    FindroidTheme(darkTheme = true) {
        Surface(color = PlayerCardBackground) {
            Box(modifier = Modifier.padding(16.dp)) {
                PlayerItemInfoLayout(
                    title = "Ozymandias",
                    subtitle = "S5:E14 • Breaking Bad",
                    artworkUri = null,
                    artworkBlurHash = null,
                    isSeries = true,
                    communityRating = 10.0f,
                    criticRating = 100f,
                    providerIds = mapOf("Imdb" to "tt2301451", "Tmdb" to "62085"),
                    overview =
                        "Everyone copes with the cataclysmic events in the desert in radically different ways, while Walt's family begins to tear itself apart.",
                    cast = dummyPreviewCast,
                    crew = dummyPreviewCrew,
                    onPersonClick = {},
                    onOpenUrl = {},
                )
            }
        }
    }
}

@Preview(
    name = "Person Details X-Ray",
    showBackground = true,
    backgroundColor = 0xFF141416,
    widthDp = 420,
    heightDp = 700,
)
@Composable
private fun PlayerPersonDetailContentPreview() {
    FindroidTheme(darkTheme = true) {
        Surface(color = PlayerCardBackground) {
            Box(modifier = Modifier.padding(16.dp)) {
                PlayerPersonDetailContent(
                    person = dummyPersonDetail,
                    items = listOf(dummyMovie),
                    isLoading = false,
                    onBack = {},
                )
            }
        }
    }
}

@Preview(
    name = "People Card Cast",
    showBackground = true,
    backgroundColor = 0xFF141416,
    widthDp = 400,
)
@Composable
private fun PlayerPeopleSectionCardCastPreview() {
    FindroidTheme(darkTheme = true) {
        Surface(color = PlayerCardBackground) {
            Box(modifier = Modifier.padding(16.dp)) {
                PlayerPeopleSectionCard(
                    title = "Cast",
                    count = dummyPreviewCast.size,
                    people = dummyPreviewCast,
                    onPersonClick = {},
                )
            }
        }
    }
}

@Preview(
    name = "People Card Crew",
    showBackground = true,
    backgroundColor = 0xFF141416,
    widthDp = 400,
)
@Composable
private fun PlayerPeopleSectionCardCrewPreview() {
    FindroidTheme(darkTheme = true) {
        Surface(color = PlayerCardBackground) {
            Box(modifier = Modifier.padding(16.dp)) {
                PlayerPeopleSectionCard(
                    title = "Crew",
                    count = dummyPreviewCrew.size,
                    people = dummyPreviewCrew,
                    onPersonClick = {},
                )
            }
        }
    }
}

@Preview(
    name = "Rating & Provider Pills Row",
    showBackground = true,
    backgroundColor = 0xFF141416,
)
@Composable
private fun PlayerRatingPillsRowPreview() {
    FindroidTheme(darkTheme = true) {
        Surface(color = PlayerCardBackground) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlayerRatingPill(
                        iconRes = CoreR.drawable.ic_star,
                        iconTint = Yellow,
                        label = "8.8",
                        onClick = null,
                    )
                    PlayerRatingPill(
                        iconRes = CoreR.drawable.ic_rotten_tomatoes_fresh,
                        label = "87%",
                        onClick = {},
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlayerRatingPill(
                        iconRes = CoreR.drawable.ic_imdb,
                        label = "IMDb",
                        onClick = {},
                    )
                    PlayerRatingPill(
                        iconRes = CoreR.drawable.ic_tmdb,
                        label = "TMDb",
                        onClick = {},
                    )
                    PlayerRatingPill(
                        iconRes = CoreR.drawable.ic_tvdb,
                        label = "TheTVDB",
                        onClick = {},
                    )
                }
            }
        }
    }
}
