package dev.jdtech.jellyfin.repository

import android.net.Uri
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.database.HomeSectionDao
import dev.jdtech.jellyfin.database.entity.HomeItemEntity
import dev.jdtech.jellyfin.database.entity.HomeSectionEntity
import dev.jdtech.jellyfin.database.entity.HomeSectionWithItems
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.FindroidBoxSet
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidFolder
import dev.jdtech.jellyfin.models.FindroidImage
import dev.jdtech.jellyfin.models.FindroidImages
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.HomeSection
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.View
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber

class HomeRepositoryImpl
@Inject
constructor(
    private val homeSectionDao: HomeSectionDao,
    private val jellyfinRepository: JellyfinRepository,
    private val appPreferences: AppPreferences,
) : HomeRepository {

    private val uuidSuggestions = UUID.fromString("31e47044-9b79-4bb0-99d0-0e477ed65420")
    private val uuidContinueWatching = UUID.fromString("44845958-8326-4e83-beb4-c4f42e9eeb95")
    private val uuidNextUp = UUID.fromString("18bfced5-f237-4d42-aa72-d9d7fed19279")

    override fun observeHomeData(serverId: String, userId: UUID): Flow<HomeData> {
        return homeSectionDao.observeHomeSectionsWithItems(serverId, userId).map { sectionsWithItems
            ->
            mapSectionsToHomeData(sectionsWithItems)
        }
    }

    override suspend fun getCachedHomeData(serverId: String, userId: UUID): HomeData {
        val sectionsWithItems = homeSectionDao.getHomeSectionsWithItems(serverId, userId)
        return mapSectionsToHomeData(sectionsWithItems)
    }

    private fun mapSectionsToHomeData(sectionsWithItems: List<HomeSectionWithItems>): HomeData {
        var suggestions: HomeItem.Suggestions? = null
        var resume: HomeItem.Section? = null
        var nextUp: HomeItem.Section? = null
        val views = mutableListOf<HomeItem.ViewItem>()

        for (sectionWithItems in sectionsWithItems) {
            val section = sectionWithItems.section
            val items =
                sectionWithItems.items.sortedBy { it.orderIndex }.map { it.toFindroidItem() }
            if (items.isEmpty()) continue

            when (section.sectionType) {
                SECTION_TYPE_SUGGESTIONS -> {
                    suggestions = HomeItem.Suggestions(id = uuidSuggestions, items = items)
                }
                SECTION_TYPE_RESUME -> {
                    resume =
                        HomeItem.Section(
                            HomeSection(
                                id = uuidContinueWatching,
                                name = UiText.StringResource(CoreR.string.continue_watching),
                                items = items,
                            ),
                        )
                }
                SECTION_TYPE_NEXT_UP -> {
                    nextUp =
                        HomeItem.Section(
                            HomeSection(
                                id = uuidNextUp,
                                name = UiText.StringResource(CoreR.string.next_up),
                                items = items,
                            ),
                        )
                }
                SECTION_TYPE_VIEW -> {
                    val viewId =
                        try {
                            UUID.fromString(section.sectionId)
                        } catch (_: Exception) {
                            UUID.randomUUID()
                        }
                    val view =
                        View(
                            id = viewId,
                            name = section.title,
                            items = items,
                            type = CollectionType.fromString(section.collectionType),
                        )
                    views.add(HomeItem.ViewItem(view))
                }
            }
        }

        return HomeData(
            suggestions = suggestions,
            resume = resume,
            nextUp = nextUp,
            views = views,
        )
    }

    private var suggestionsOffset = 0

    override suspend fun rotateSuggestionsBuffer(serverId: String, userId: UUID): Boolean {
        val promoted = homeSectionDao.promoteSuggestionsBuffer(serverId, userId)
        if (promoted) {
            suggestionsOffset += SUGGESTIONS_BATCH_SIZE
        }
        return promoted
    }

    override suspend fun refreshHome(
        serverId: String,
        userId: UUID,
        refreshSuggestions: Boolean,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                coroutineScope {
                    val suggestionsJob = async {
                        if (
                            refreshSuggestions &&
                                appPreferences.getValue(appPreferences.homeSuggestions)
                        ) {
                            val fetched =
                                jellyfinRepository.getSuggestions(
                                    forceRefresh = true,
                                    startIndex = suggestionsOffset,
                                    limit = SUGGESTIONS_BATCH_SIZE,
                                )
                            val finalSuggestions =
                                if (fetched.isEmpty() && suggestionsOffset > 0) {
                                    suggestionsOffset = 0
                                    jellyfinRepository.getSuggestions(
                                        forceRefresh = true,
                                        startIndex = 0,
                                        limit = SUGGESTIONS_BATCH_SIZE,
                                    )
                                } else {
                                    fetched
                                }

                            if (finalSuggestions.isNotEmpty()) {
                                val activeSuggestions =
                                    homeSectionDao.getItemsForSection(
                                        serverId,
                                        userId,
                                        SECTION_ID_SUGGESTIONS,
                                    )
                                if (activeSuggestions.isEmpty()) {
                                    val section =
                                        HomeSectionEntity(
                                            id = "${serverId}_${userId}_$SECTION_ID_SUGGESTIONS",
                                            serverId = serverId,
                                            userId = userId,
                                            sectionId = SECTION_ID_SUGGESTIONS,
                                            sectionType = SECTION_TYPE_SUGGESTIONS,
                                            title = "",
                                            collectionType = null,
                                            orderIndex = 0,
                                        )
                                    val items = finalSuggestions.mapIndexed { idx, item ->
                                        item.toHomeItemEntity(
                                            serverId,
                                            userId,
                                            SECTION_ID_SUGGESTIONS,
                                            idx,
                                        )
                                    }
                                    homeSectionDao.replaceSectionItems(
                                        serverId,
                                        userId,
                                        section,
                                        items,
                                    )

                                    suggestionsOffset = SUGGESTIONS_BATCH_SIZE
                                    val bufferBatch =
                                        jellyfinRepository.getSuggestions(
                                            forceRefresh = true,
                                            startIndex = suggestionsOffset,
                                            limit = SUGGESTIONS_BATCH_SIZE,
                                        )
                                    if (bufferBatch.isNotEmpty()) {
                                        homeSectionDao.saveSuggestionsBuffer(
                                            serverId,
                                            userId,
                                            bufferBatch.mapIndexed { idx, item ->
                                                item.toHomeItemEntity(
                                                    serverId,
                                                    userId,
                                                    SECTION_ID_SUGGESTIONS_BUFFER,
                                                    idx,
                                                )
                                            },
                                        )
                                    }
                                } else {
                                    homeSectionDao.saveSuggestionsBuffer(
                                        serverId,
                                        userId,
                                        finalSuggestions.mapIndexed { idx, item ->
                                            item.toHomeItemEntity(
                                                serverId,
                                                userId,
                                                SECTION_ID_SUGGESTIONS_BUFFER,
                                                idx,
                                            )
                                        },
                                    )
                                }
                            }
                        } else if (!appPreferences.getValue(appPreferences.homeSuggestions)) {
                            homeSectionDao.deleteSection(serverId, userId, SECTION_ID_SUGGESTIONS)
                        }
                    }

                    val resumeJob = async {
                        if (appPreferences.getValue(appPreferences.homeContinueWatching)) {
                            val resume = jellyfinRepository.getResumeItems()
                            val section =
                                HomeSectionEntity(
                                    id = "${serverId}_${userId}_$SECTION_ID_RESUME",
                                    serverId = serverId,
                                    userId = userId,
                                    sectionId = SECTION_ID_RESUME,
                                    sectionType = SECTION_TYPE_RESUME,
                                    title = "Continue Watching",
                                    collectionType = null,
                                    orderIndex = 1,
                                )
                            val items = resume.mapIndexed { idx, item ->
                                item.toHomeItemEntity(serverId, userId, SECTION_ID_RESUME, idx)
                            }
                            homeSectionDao.replaceSectionItems(serverId, userId, section, items)
                        } else {
                            homeSectionDao.deleteSection(serverId, userId, SECTION_ID_RESUME)
                        }
                    }

                    val nextUpJob = async {
                        if (appPreferences.getValue(appPreferences.homeNextUp)) {
                            val nextUp = jellyfinRepository.getNextUp()
                            val section =
                                HomeSectionEntity(
                                    id = "${serverId}_${userId}_$SECTION_ID_NEXT_UP",
                                    serverId = serverId,
                                    userId = userId,
                                    sectionId = SECTION_ID_NEXT_UP,
                                    sectionType = SECTION_TYPE_NEXT_UP,
                                    title = "Next Up",
                                    collectionType = null,
                                    orderIndex = 2,
                                )
                            val items = nextUp.mapIndexed { idx, item ->
                                item.toHomeItemEntity(serverId, userId, SECTION_ID_NEXT_UP, idx)
                            }
                            homeSectionDao.replaceSectionItems(serverId, userId, section, items)
                        } else {
                            homeSectionDao.deleteSection(serverId, userId, SECTION_ID_NEXT_UP)
                        }
                    }

                    val viewsJob = async {
                        if (appPreferences.getValue(appPreferences.homeLatest)) {
                            val supportedViews =
                                jellyfinRepository.getUserViews().filter { view ->
                                    CollectionType.fromString(view.collectionType?.serialName) in
                                        CollectionType.supported
                                }
                            val supportedViewIds = supportedViews.map { it.id.toString() }.toSet()
                            val cachedSections =
                                homeSectionDao.getHomeSectionsWithItems(serverId, userId)
                            for (cached in cachedSections) {
                                if (
                                    cached.section.sectionType == SECTION_TYPE_VIEW &&
                                        cached.section.sectionId !in supportedViewIds
                                ) {
                                    homeSectionDao.deleteSection(
                                        serverId,
                                        userId,
                                        cached.section.sectionId,
                                    )
                                }
                            }
                            supportedViews
                                .mapIndexed { viewIdx, view ->
                                    async {
                                        val latestItems = jellyfinRepository.getLatestMedia(view.id)
                                        val viewSectionId = view.id.toString()
                                        val section =
                                            HomeSectionEntity(
                                                id = "${serverId}_${userId}_$viewSectionId",
                                                serverId = serverId,
                                                userId = userId,
                                                sectionId = viewSectionId,
                                                sectionType = SECTION_TYPE_VIEW,
                                                title = view.name.orEmpty(),
                                                collectionType = view.collectionType?.serialName,
                                                orderIndex = 10 + viewIdx,
                                            )
                                        val items = latestItems.mapIndexed { itemIdx, item ->
                                            item.toHomeItemEntity(
                                                serverId,
                                                userId,
                                                viewSectionId,
                                                itemIdx,
                                            )
                                        }
                                        homeSectionDao.replaceSectionItems(
                                            serverId,
                                            userId,
                                            section,
                                            items,
                                        )
                                    }
                                }
                                .awaitAll()
                        } else {
                            val cachedSections =
                                homeSectionDao.getHomeSectionsWithItems(serverId, userId)
                            for (cached in cachedSections) {
                                if (cached.section.sectionType == SECTION_TYPE_VIEW) {
                                    homeSectionDao.deleteSection(
                                        serverId,
                                        userId,
                                        cached.section.sectionId,
                                    )
                                }
                            }
                        }
                    }

                    suggestionsJob.await()
                    resumeJob.await()
                    nextUpJob.await()
                    viewsJob.await()
                    Result.success(Unit)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Failed to revalidate home data from network")
                Result.failure(e)
            }
        }

    override suspend fun updateOptimisticPlayback(
        userId: UUID,
        itemId: UUID,
        positionTicks: Long,
        playedPercentage: Int,
    ) {
        withContext(Dispatchers.IO) {
            try {
                when {
                    playedPercentage > 90 -> {
                        homeSectionDao.updateItemPlaybackProgress(
                            userId = userId,
                            itemId = itemId,
                            ticks = 0L,
                            played = true,
                        )
                        homeSectionDao.removeResumeItem(userId, itemId)
                    }
                    playedPercentage < 10 -> {
                        homeSectionDao.updateItemPlaybackProgress(
                            userId = userId,
                            itemId = itemId,
                            ticks = 0L,
                            played = false,
                        )
                    }
                    else -> {
                        homeSectionDao.updateItemPlaybackProgress(
                            userId = userId,
                            itemId = itemId,
                            ticks = positionTicks,
                            played = false,
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to apply optimistic playback update")
            }
        }
    }

    private fun FindroidItem.toHomeItemEntity(
        serverId: String,
        userId: UUID,
        sectionId: String,
        orderIndex: Int,
    ): HomeItemEntity {
        val itemType =
            when (this) {
                is FindroidMovie -> "MOVIE"
                is FindroidEpisode -> "EPISODE"
                is FindroidShow -> "SHOW"
                is FindroidSeason -> "SEASON"
                is FindroidBoxSet -> "BOX_SET"
                is FindroidFolder -> "FOLDER"
                else -> "UNKNOWN"
            }
        val seriesName =
            (this as? FindroidEpisode)?.seriesName ?: (this as? FindroidSeason)?.seriesName
        val seriesId = (this as? FindroidEpisode)?.seriesId ?: (this as? FindroidSeason)?.seriesId
        val seasonName = (this as? FindroidEpisode)?.seasonName
        val seasonId = (this as? FindroidEpisode)?.seasonId
        val indexNumber =
            (this as? FindroidEpisode)?.indexNumber ?: (this as? FindroidSeason)?.indexNumber
        val parentIndexNumber = (this as? FindroidEpisode)?.parentIndexNumber
        val productionYear =
            (this as? FindroidMovie)?.productionYear
                ?: (this as? FindroidShow)?.productionYear
                ?: (this as? FindroidSeason)?.productionYear
        val premiereDate =
            (this as? FindroidMovie)?.premiereDate
                ?: (this as? FindroidEpisode)?.premiereDate
                ?: (this as? FindroidSeason)?.premiereDate
        val communityRating =
            (this as? FindroidMovie)?.communityRating
                ?: (this as? FindroidShow)?.communityRating
                ?: (this as? FindroidEpisode)?.communityRating
        val criticRating =
            (this as? FindroidMovie)?.criticRating
                ?: (this as? FindroidShow)?.criticRating
                ?: (this as? FindroidEpisode)?.criticRating
        val officialRating =
            (this as? FindroidMovie)?.officialRating ?: (this as? FindroidShow)?.officialRating
        val status = (this as? FindroidMovie)?.status ?: (this as? FindroidShow)?.status

        return HomeItemEntity(
            id = "${serverId}_${userId}_${sectionId}_$id",
            sectionCompositeId = "${serverId}_${userId}_$sectionId",
            serverId = serverId,
            userId = userId,
            sectionId = sectionId,
            itemId = id,
            orderIndex = orderIndex,
            itemType = itemType,
            name = name,
            originalTitle = originalTitle,
            overview = overview,
            played = played,
            favorite = favorite,
            canPlay = canPlay,
            canDownload = canDownload,
            runtimeTicks = runtimeTicks,
            playbackPositionTicks = playbackPositionTicks,
            unplayedItemCount = unplayedItemCount,
            seriesName = seriesName,
            seriesId = seriesId,
            seasonName = seasonName,
            seasonId = seasonId,
            indexNumber = indexNumber,
            parentIndexNumber = parentIndexNumber,
            productionYear = productionYear,
            premiereDate = premiereDate,
            communityRating = communityRating,
            criticRating = criticRating,
            officialRating = officialRating,
            status = status,
            primaryImageUri = images.primary?.uri?.toString(),
            primaryImageBlurHash = images.primary?.blurHash,
            showPrimaryImageUri = images.showPrimary?.uri?.toString(),
            showPrimaryImageBlurHash = images.showPrimary?.blurHash,
            backdropImageUri =
                images.backdrop?.uri?.toString() ?: images.showBackdrop?.uri?.toString(),
            backdropImageBlurHash = images.backdrop?.blurHash ?: images.showBackdrop?.blurHash,
            logoImageUri = images.logo?.uri?.toString() ?: images.showLogo?.uri?.toString(),
            logoImageBlurHash = images.logo?.blurHash ?: images.showLogo?.blurHash,
            isDownloaded = isDownloaded(),
        )
    }

    private fun HomeItemEntity.toFindroidItem(): FindroidItem {
        val itemImages =
            FindroidImages(
                primary =
                    primaryImageUri?.let { FindroidImage(Uri.parse(it), primaryImageBlurHash) },
                backdrop =
                    backdropImageUri?.let { FindroidImage(Uri.parse(it), backdropImageBlurHash) },
                logo = logoImageUri?.let { FindroidImage(Uri.parse(it), logoImageBlurHash) },
                showPrimary =
                    showPrimaryImageUri?.let {
                        FindroidImage(Uri.parse(it), showPrimaryImageBlurHash)
                    },
            )
        val localSources =
            if (isDownloaded) {
                listOf(
                    FindroidSource(
                        id = "offline_$itemId",
                        name = "Local",
                        type = FindroidSourceType.LOCAL,
                        path = "local.mp4",
                        size = 0L,
                        mediaStreams = emptyList(),
                    ),
                )
            } else {
                emptyList()
            }

        return when (itemType) {
            "MOVIE" ->
                FindroidMovie(
                    id = itemId,
                    name = name,
                    originalTitle = originalTitle,
                    overview = overview,
                    sources = localSources,
                    played = played,
                    favorite = favorite,
                    canPlay = canPlay,
                    canDownload = canDownload,
                    runtimeTicks = runtimeTicks,
                    playbackPositionTicks = playbackPositionTicks,
                    premiereDate = premiereDate,
                    people = emptyList(),
                    genres = emptyList(),
                    communityRating = communityRating,
                    criticRating = criticRating,
                    officialRating = officialRating,
                    status = status ?: "Ended",
                    productionYear = productionYear ?: premiereDate?.year,
                    endDate = null,
                    trailer = null,
                    unplayedItemCount = unplayedItemCount,
                    images = itemImages,
                    chapters = emptyList(),
                    trickplayInfo = null,
                )
            "EPISODE" ->
                FindroidEpisode(
                    id = itemId,
                    name = name,
                    originalTitle = originalTitle,
                    overview = overview,
                    indexNumber = indexNumber ?: 0,
                    indexNumberEnd = null,
                    parentIndexNumber = parentIndexNumber ?: 0,
                    sources = localSources,
                    played = played,
                    favorite = favorite,
                    canPlay = canPlay,
                    canDownload = canDownload,
                    runtimeTicks = runtimeTicks,
                    playbackPositionTicks = playbackPositionTicks,
                    premiereDate = premiereDate,
                    seriesId = seriesId ?: UUID.randomUUID(),
                    seriesName = seriesName ?: "",
                    seasonId = seasonId ?: UUID.randomUUID(),
                    seasonName = seasonName,
                    communityRating = communityRating,
                    criticRating = criticRating,
                    people = emptyList(),
                    unplayedItemCount = unplayedItemCount,
                    images = itemImages,
                    chapters = emptyList(),
                    trickplayInfo = null,
                )
            "SHOW" ->
                FindroidShow(
                    id = itemId,
                    name = name,
                    originalTitle = originalTitle,
                    overview = overview,
                    sources = localSources,
                    seasons = emptyList(),
                    played = played,
                    favorite = favorite,
                    canPlay = canPlay,
                    canDownload = canDownload,
                    playbackPositionTicks = playbackPositionTicks,
                    unplayedItemCount = unplayedItemCount,
                    genres = emptyList(),
                    people = emptyList(),
                    runtimeTicks = runtimeTicks,
                    communityRating = communityRating,
                    criticRating = criticRating,
                    officialRating = officialRating,
                    status = status ?: "Ended",
                    productionYear = productionYear ?: premiereDate?.year,
                    endDate = null,
                    trailer = null,
                    images = itemImages,
                )
            "SEASON" ->
                FindroidSeason(
                    id = itemId,
                    name = name,
                    seriesId = seriesId ?: UUID.randomUUID(),
                    seriesName = seriesName ?: "",
                    originalTitle = originalTitle,
                    overview = overview,
                    sources = localSources,
                    indexNumber = indexNumber ?: 0,
                    episodes = emptyList(),
                    productionYear = productionYear ?: premiereDate?.year,
                    premiereDate = premiereDate,
                    played = played,
                    favorite = favorite,
                    canPlay = canPlay,
                    canDownload = canDownload,
                    runtimeTicks = runtimeTicks,
                    playbackPositionTicks = playbackPositionTicks,
                    unplayedItemCount = unplayedItemCount,
                    images = itemImages,
                )
            "BOX_SET" ->
                FindroidBoxSet(
                    id = itemId,
                    name = name,
                    originalTitle = originalTitle,
                    overview = overview,
                    played = played,
                    favorite = favorite,
                    canPlay = canPlay,
                    canDownload = canDownload,
                    sources = localSources,
                    runtimeTicks = runtimeTicks,
                    playbackPositionTicks = playbackPositionTicks,
                    unplayedItemCount = unplayedItemCount,
                    images = itemImages,
                )
            else ->
                FindroidFolder(
                    id = itemId,
                    name = name,
                    originalTitle = originalTitle,
                    overview = overview,
                    played = played,
                    favorite = favorite,
                    canPlay = canPlay,
                    canDownload = canDownload,
                    sources = localSources,
                    runtimeTicks = runtimeTicks,
                    playbackPositionTicks = playbackPositionTicks,
                    unplayedItemCount = unplayedItemCount,
                    images = itemImages,
                )
        }
    }

    companion object {
        const val SECTION_ID_SUGGESTIONS = "suggestions"
        const val SECTION_ID_SUGGESTIONS_BUFFER = "suggestions_buffer"
        const val SECTION_ID_RESUME = "resume"
        const val SECTION_ID_NEXT_UP = "next_up"

        const val SECTION_TYPE_SUGGESTIONS = "SUGGESTIONS"
        const val SECTION_TYPE_RESUME = "RESUME"
        const val SECTION_TYPE_NEXT_UP = "NEXT_UP"
        const val SECTION_TYPE_VIEW = "VIEW"

        const val SUGGESTIONS_BATCH_SIZE = 10
    }
}
