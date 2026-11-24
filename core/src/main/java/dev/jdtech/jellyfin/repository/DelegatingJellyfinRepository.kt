package dev.jdtech.jellyfin.repository

import androidx.paging.PagingData
import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidPart
import dev.jdtech.jellyfin.models.FindroidPerson
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidSegment
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.network.ServerConnectionManager
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.SeriesStatus
import org.jellyfin.sdk.model.api.TranscodingInfo
import org.jellyfin.sdk.model.api.UserConfiguration
import org.jellyfin.sdk.model.api.VideoType

/**
 * Delegates [JellyfinRepository] calls dynamically between [online] and [offline] implementations
 * based on the live [ServerConnectionManager] state, enabling seamless transitions without
 * restarts.
 */
class DelegatingJellyfinRepository(
    private val online: JellyfinRepositoryImpl,
    private val offline: JellyfinRepositoryOfflineImpl,
    private val connectionManager: ServerConnectionManager,
) : JellyfinRepository {
    private fun current(): JellyfinRepository =
        if (connectionManager.state.value.isOffline) offline else online

    override suspend fun getAdditionalParts(itemId: UUID): List<FindroidPart> =
        current().getAdditionalParts(itemId)

    override suspend fun getPublicSystemInfo(): PublicSystemInfo = current().getPublicSystemInfo()

    override suspend fun authorizeQuickConnect(code: String): Boolean =
        current().authorizeQuickConnect(code)

    override suspend fun getUserViews(): List<BaseItemDto> = current().getUserViews()

    override suspend fun getEpisode(itemId: UUID): FindroidEpisode = current().getEpisode(itemId)

    override suspend fun getMovie(itemId: UUID): FindroidMovie = current().getMovie(itemId)

    override suspend fun getShow(itemId: UUID): FindroidShow = current().getShow(itemId)

    override suspend fun getSeason(itemId: UUID): FindroidSeason = current().getSeason(itemId)

    override suspend fun getLibraries(): List<FindroidCollection> = current().getLibraries()

    override suspend fun getItem(itemId: UUID): FindroidItem? = current().getItem(itemId)

    override suspend fun getItems(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        filters: List<ItemFilter>?,
        startIndex: Int?,
        limit: Int?,
    ): List<FindroidItem> =
        current()
            .getItems(
                parentId = parentId,
                includeTypes = includeTypes,
                recursive = recursive,
                sortBy = sortBy,
                sortOrder = sortOrder,
                filters = filters,
                startIndex = startIndex,
                limit = limit,
            )

    override suspend fun getItemsPaging(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        filters: List<ItemFilter>?,
    ): Flow<PagingData<FindroidItem>> =
        current()
            .getItemsPaging(
                parentId = parentId,
                includeTypes = includeTypes,
                recursive = recursive,
                sortBy = sortBy,
                sortOrder = sortOrder,
                filters = filters,
            )

    override suspend fun getPerson(personId: UUID): FindroidPerson = current().getPerson(personId)

    override suspend fun getPersonItems(
        personIds: List<UUID>,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
    ): List<FindroidItem> = current().getPersonItems(personIds, includeTypes, recursive)

    override suspend fun getFavoriteItems(): List<FindroidItem> = current().getFavoriteItems()

    override suspend fun getSearchItems(query: String): List<FindroidItem> =
        current().getSearchItems(query)

    override suspend fun getSearchSuggestions(): List<String> = current().getSearchSuggestions()

    override suspend fun getAvailableContentTypes(): List<BaseItemKind> =
        current().getAvailableContentTypes()

    override suspend fun getAvailableVideoQualities(): List<String> =
        current().getAvailableVideoQualities()

    override suspend fun getAvailableItemFilters(): List<ItemFilter> =
        current().getAvailableItemFilters()

    override suspend fun getAvailableSeriesStatuses(): List<SeriesStatus> =
        current().getAvailableSeriesStatuses()

    override suspend fun getAvailableFeatures(): List<String> = current().getAvailableFeatures()

    override suspend fun getGenres(includeItemTypes: List<BaseItemKind>?): List<String> =
        current().getGenres(includeItemTypes)

    override suspend fun getTags(includeItemTypes: List<BaseItemKind>?): List<String> =
        current().getTags(includeItemTypes)

    override suspend fun getStudios(includeItemTypes: List<BaseItemKind>?): List<String> =
        current().getStudios(includeItemTypes)

    override suspend fun getOfficialRatings(includeItemTypes: List<BaseItemKind>?): List<String> =
        current().getOfficialRatings(includeItemTypes)

    override suspend fun getYears(includeItemTypes: List<BaseItemKind>?): List<Int> =
        current().getYears(includeItemTypes)

    override suspend fun getAudioLanguages(includeItemTypes: List<BaseItemKind>?): List<String> =
        current().getAudioLanguages(includeItemTypes)

    override suspend fun getSubtitleLanguages(includeItemTypes: List<BaseItemKind>?): List<String> =
        current().getSubtitleLanguages(includeItemTypes)

    override suspend fun getSearchItemsFiltered(
        query: String,
        includeItemTypes: List<BaseItemKind>?,
        seriesStatuses: List<SeriesStatus>?,
        genres: List<String>?,
        tags: List<String>?,
        officialRatings: List<String>?,
        years: List<Int>?,
        studios: List<String>?,
        audioLanguages: List<String>?,
        subtitleLanguages: List<String>?,
        filters: List<ItemFilter>?,
        is3D: Boolean?,
        hasSubtitles: Boolean?,
        is4K: Boolean?,
        isHD: Boolean?,
        videoTypes: List<VideoType>?,
        hasTrailer: Boolean?,
        hasSpecialFeatures: Boolean?,
        hasThemeSong: Boolean?,
        hasThemeVideo: Boolean?,
        minCommunityRating: Double?,
        startIndex: Int?,
        limit: Int?,
    ): List<FindroidItem> =
        current()
            .getSearchItemsFiltered(
                query = query,
                includeItemTypes = includeItemTypes,
                seriesStatuses = seriesStatuses,
                genres = genres,
                tags = tags,
                officialRatings = officialRatings,
                years = years,
                studios = studios,
                audioLanguages = audioLanguages,
                subtitleLanguages = subtitleLanguages,
                filters = filters,
                is3D = is3D,
                hasSubtitles = hasSubtitles,
                is4K = is4K,
                isHD = isHD,
                videoTypes = videoTypes,
                hasTrailer = hasTrailer,
                hasSpecialFeatures = hasSpecialFeatures,
                hasThemeSong = hasThemeSong,
                hasThemeVideo = hasThemeVideo,
                minCommunityRating = minCommunityRating,
                startIndex = startIndex,
                limit = limit,
            )

    override fun getSearchItemsFilteredPaging(
        query: String,
        includeItemTypes: List<BaseItemKind>?,
        seriesStatuses: List<SeriesStatus>?,
        genres: List<String>?,
        tags: List<String>?,
        officialRatings: List<String>?,
        years: List<Int>?,
        studios: List<String>?,
        audioLanguages: List<String>?,
        subtitleLanguages: List<String>?,
        filters: List<ItemFilter>?,
        is3D: Boolean?,
        hasSubtitles: Boolean?,
        is4K: Boolean?,
        isHD: Boolean?,
        videoTypes: List<VideoType>?,
        hasTrailer: Boolean?,
        hasSpecialFeatures: Boolean?,
        hasThemeSong: Boolean?,
        hasThemeVideo: Boolean?,
        minCommunityRating: Double?,
    ): Flow<PagingData<FindroidItem>> =
        current()
            .getSearchItemsFilteredPaging(
                query = query,
                includeItemTypes = includeItemTypes,
                seriesStatuses = seriesStatuses,
                genres = genres,
                tags = tags,
                officialRatings = officialRatings,
                years = years,
                studios = studios,
                audioLanguages = audioLanguages,
                subtitleLanguages = subtitleLanguages,
                filters = filters,
                is3D = is3D,
                hasSubtitles = hasSubtitles,
                is4K = is4K,
                isHD = isHD,
                videoTypes = videoTypes,
                hasTrailer = hasTrailer,
                hasSpecialFeatures = hasSpecialFeatures,
                hasThemeSong = hasThemeSong,
                hasThemeVideo = hasThemeVideo,
                minCommunityRating = minCommunityRating,
            )

    override suspend fun getRandomItem(): FindroidItem? = current().getRandomItem()

    override suspend fun getSuggestions(forceRefresh: Boolean): List<FindroidItem> =
        current().getSuggestions(forceRefresh)

    override suspend fun getResumeItems(): List<FindroidItem> = current().getResumeItems()

    override suspend fun getLatestMedia(parentId: UUID): List<FindroidItem> =
        current().getLatestMedia(parentId)

    override suspend fun getSeasons(seriesId: UUID, offline: Boolean): List<FindroidSeason> =
        current().getSeasons(seriesId, offline)

    override suspend fun getNextUp(seriesId: UUID?): List<FindroidEpisode> =
        current().getNextUp(seriesId)

    override suspend fun getEpisodes(
        seriesId: UUID,
        seasonId: UUID,
        fields: List<ItemFields>?,
        startItemId: UUID?,
        limit: Int?,
        offline: Boolean,
    ): List<FindroidEpisode> =
        current().getEpisodes(seriesId, seasonId, fields, startItemId, limit, offline)

    override suspend fun getMediaSources(
        itemId: UUID,
        includePath: Boolean,
        maxStreamingBitrate: Int?,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        mediaSourceId: String?,
        deviceProfile: DeviceProfile?,
        enableDirectPlay: Boolean,
    ): List<FindroidSource> =
        current()
            .getMediaSources(
                itemId = itemId,
                includePath = includePath,
                maxStreamingBitrate = maxStreamingBitrate,
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                mediaSourceId = mediaSourceId,
                deviceProfile = deviceProfile,
                enableDirectPlay = enableDirectPlay,
            )

    override suspend fun getStreamUrl(itemId: UUID, mediaSourceId: String): String =
        current().getStreamUrl(itemId, mediaSourceId)

    override suspend fun getSegments(itemId: UUID): List<FindroidSegment> =
        current().getSegments(itemId)

    override suspend fun getTrickplayData(itemId: UUID, width: Int, index: Int): ByteArray? =
        current().getTrickplayData(itemId, width, index)

    override suspend fun postCapabilities() = current().postCapabilities()

    override suspend fun postPlaybackStart(
        itemId: UUID,
        positionTicks: Long?,
        playMethod: PlayMethod,
        mediaSourceId: String?,
        playSessionId: String?,
    ) = current().postPlaybackStart(itemId, positionTicks, playMethod, mediaSourceId, playSessionId)

    override suspend fun postPlaybackStop(
        itemId: UUID,
        positionTicks: Long,
        playedPercentage: Int,
        mediaSourceId: String?,
        playSessionId: String?,
    ) =
        current()
            .postPlaybackStop(
                itemId,
                positionTicks,
                playedPercentage,
                mediaSourceId,
                playSessionId,
            )

    override suspend fun postPlaybackProgress(
        itemId: UUID,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: PlayMethod,
        mediaSourceId: String?,
        playSessionId: String?,
    ) =
        current()
            .postPlaybackProgress(
                itemId,
                positionTicks,
                isPaused,
                playMethod,
                mediaSourceId,
                playSessionId,
            )

    override suspend fun markAsFavorite(itemId: UUID) = current().markAsFavorite(itemId)

    override suspend fun unmarkAsFavorite(itemId: UUID) = current().unmarkAsFavorite(itemId)

    override suspend fun markAsPlayed(itemId: UUID) = current().markAsPlayed(itemId)

    override suspend fun markAsUnplayed(itemId: UUID) = current().markAsUnplayed(itemId)

    override fun getBaseUrl(): String = current().getBaseUrl()

    override fun getDeviceName(): String = current().getDeviceName()

    override suspend fun updateDeviceName(name: String) = current().updateDeviceName(name)

    override suspend fun getUserConfiguration(): UserConfiguration? =
        current().getUserConfiguration()

    override suspend fun getDownloads(): List<FindroidItem> = current().getDownloads()

    override fun getUserId(): UUID = current().getUserId()

    override suspend fun getCurrentServer(): Server? = current().getCurrentServer()

    override suspend fun refreshUser(userId: UUID): String? = current().refreshUser(userId)

    override suspend fun setCurrentUser(userId: UUID) = current().setCurrentUser(userId)

    override suspend fun canTranscode(): Boolean = current().canTranscode()

    override suspend fun getActiveTranscodingInfo(playSessionId: String?): TranscodingInfo? =
        current().getActiveTranscodingInfo(playSessionId)
}
