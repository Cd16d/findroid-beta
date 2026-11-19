package dev.jdtech.jellyfin.player.core.domain

import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import dev.jdtech.jellyfin.models.FindroidChapter
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidImage
import dev.jdtech.jellyfin.models.FindroidImages
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidPart
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.FindroidSources
import dev.jdtech.jellyfin.player.core.domain.models.ExternalSubtitle
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.core.domain.models.PlayerImage
import dev.jdtech.jellyfin.player.core.domain.models.PlayerImages
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.PlayerMediaType
import dev.jdtech.jellyfin.player.core.domain.models.PlaylistWindow
import dev.jdtech.jellyfin.player.core.domain.models.TrickplayInfo
import dev.jdtech.jellyfin.player.core.domain.utils.StreamUtils
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.utils.isPartName
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import timber.log.Timber

/**
 * Manages playback playlists and queue navigation. Converts Jellyfin domain models into
 * player-ready [PlayerItem] instances and handles sequence navigation.
 */
class PlaylistManager @Inject internal constructor(private val repository: JellyfinRepository) {
    private var startItem: FindroidItem? = null
    private var items: MutableList<FindroidItem> = mutableListOf()
    private val playerItems: MutableList<PlayerItem> = mutableListOf()
    private var seriesId: UUID? = null
    private var currentSeasonIndex: Int? = null
    private var currentSeasonId: UUID? = null
    private var cachedSeasons: List<FindroidSeason>? = null

    /** The index of the currently active media item in the playlist. */
    var currentItemIndex: Int = 0

    /**
     * Builds and returns the initial 3-item playback window around the active media item. Pre-seeds
     * adjacent items concurrently to guarantee immediate Next/Previous availability.
     */
    suspend fun getInitialWindow(
        itemId: UUID,
        itemKind: BaseItemKind,
        mediaSourceIndex: Int? = null,
        startFromBeginning: Boolean = false,
        maxStreamingBitrate: Int? = null,
        deviceProfile: DeviceProfile? = null,
        enableDirectPlay: Boolean = true,
    ): PlaylistWindow? {
        val initialItem =
            getInitialItem(
                itemId = itemId,
                itemKind = itemKind,
                mediaSourceIndex = mediaSourceIndex,
                startFromBeginning = startFromBeginning,
                maxStreamingBitrate = maxStreamingBitrate,
                deviceProfile = deviceProfile,
                enableDirectPlay = enableDirectPlay,
            ) ?: return null

        if (itemKind != BaseItemKind.EPISODE) {
            return PlaylistWindow(
                previousItem = null,
                currentItem = initialItem,
                nextItem = null,
            )
        }

        val (prev, next) =
            coroutineScope {
                val prevDeferred = async {
                    getPreviousPlayerItem(
                        maxStreamingBitrate = maxStreamingBitrate,
                        deviceProfile = deviceProfile,
                        enableDirectPlay = enableDirectPlay,
                    )
                }
                val nextDeferred = async {
                    getNextPlayerItem(
                        maxStreamingBitrate = maxStreamingBitrate,
                        deviceProfile = deviceProfile,
                        enableDirectPlay = enableDirectPlay,
                    )
                }
                prevDeferred.await() to nextDeferred.await()
            }

        return PlaylistWindow(
            previousItem = prev,
            currentItem = initialItem,
            nextItem = next,
        )
    }

    /**
     * Builds and returns the initial [PlayerItem] for playback given an item ID and kind.
     *
     * @param itemId Unique identifier of the requested item.
     * @param itemKind The [BaseItemKind] of the requested item (e.g. MOVIE, SERIES, EPISODE).
     * @param mediaSourceIndex Optional index of the specific media source to use.
     * @param startFromBeginning Whether to start playback from position 0 instead of saved
     *   position.
     * @param maxStreamingBitrate Optional maximum bitrate in bps for transcode resolution.
     * @param deviceProfile Optional device profile for transcode compatibility checks.
     * @param enableDirectPlay Whether direct play is allowed for this item.
     * @return The constructed [PlayerItem], or `null` if the item could not be retrieved.
     */
    suspend fun getInitialItem(
        itemId: UUID,
        itemKind: BaseItemKind,
        mediaSourceIndex: Int? = null,
        startFromBeginning: Boolean = false,
        maxStreamingBitrate: Int? = null,
        deviceProfile: DeviceProfile? = null,
        enableDirectPlay: Boolean = true,
    ): PlayerItem? {
        Timber.d("Retrieving initial player item")

        cachedSeasons = null

        val initialItem =
            when (itemKind) {
                BaseItemKind.MOVIE -> {
                    seriesId = null
                    currentSeasonId = null
                    currentSeasonIndex = null
                    val movie = repository.getMovie(itemId)
                    items = movie.toPlaylist().toMutableList()
                    movie
                }
                BaseItemKind.SERIES -> {
                    val nextUpEpisode = repository.getNextUp(itemId).firstOrNull()

                    val season =
                        if (nextUpEpisode != null) {
                            repository.getSeason(nextUpEpisode.seasonId)
                        } else {
                            val seasons = repository.getSeasons(itemId)
                            if (seasons.isEmpty()) {
                                return null
                            }
                            seasons.first()
                        }

                    seriesId = itemId
                    currentSeasonId = season.id
                    currentSeasonIndex = season.indexNumber

                    val episodes =
                        repository
                            .getEpisodes(
                                seriesId = itemId,
                                seasonId = season.id,
                                fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
                            )
                            .filter { !it.missing }

                    if (episodes.isEmpty()) {
                        return null
                    }

                    val episode = nextUpEpisode ?: episodes.first()

                    items = episodes.flatMap { it.toPlaylist() }.toMutableList()
                    episode
                }
                BaseItemKind.SEASON -> {
                    val season = repository.getSeason(itemId)
                    seriesId = season.seriesId
                    currentSeasonId = season.id
                    currentSeasonIndex = season.indexNumber

                    val episodes =
                        repository
                            .getEpisodes(
                                seriesId = season.seriesId,
                                seasonId = season.id,
                                fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
                            )
                            .filter { !it.missing }

                    if (episodes.isEmpty()) {
                        return null
                    }

                    val episode = episodes.first()

                    items = episodes.flatMap { it.toPlaylist() }.toMutableList()
                    episode
                }
                BaseItemKind.EPISODE -> {
                    val episode = repository.getEpisode(itemId)
                    seriesId = episode.seriesId
                    currentSeasonId = episode.seasonId
                    currentSeasonIndex = episode.parentIndexNumber

                    val episodes =
                        repository
                            .getEpisodes(
                                seriesId = episode.seriesId,
                                seasonId = episode.seasonId,
                                fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
                            )
                            .filter { !it.missing }

                    items = episodes.flatMap { it.toPlaylist() }.toMutableList()
                    episode
                }
                else -> null
            }

        if (initialItem == null) {
            return null
        }

        startItem = initialItem
        playerItems.clear()

        currentItemIndex = items.indexOfFirst { it.id == initialItem.id }

        val playbackPosition =
            if (!startFromBeginning) initialItem.playbackPositionTicks / 10000 else 0
        val playerItem =
            initialItem.toPlayerItem(
                mediaSourceIndex = mediaSourceIndex,
                playbackPosition = playbackPosition,
                maxStreamingBitrate = maxStreamingBitrate,
                deviceProfile = deviceProfile,
                enableDirectPlay = enableDirectPlay,
            )
        playerItems.add(playerItem)

        return playerItem
    }

    /**
     * Retrieves the previous [PlayerItem] in the active playlist relative to [currentItemIndex].
     * Lazily queries the preceding season if at the season boundary.
     *
     * @param maxStreamingBitrate Optional maximum bitrate in bps for transcode resolution.
     * @param deviceProfile Optional device profile for transcode compatibility checks.
     * @param enableDirectPlay Whether direct play is allowed for this item.
     * @return The previous [PlayerItem], or `null` if at the start of the playlist or on error.
     */
    suspend fun getPreviousPlayerItem(
        maxStreamingBitrate: Int? = null,
        deviceProfile: DeviceProfile? = null,
        enableDirectPlay: Boolean = true,
    ): PlayerItem? {
        Timber.d("Retrieving previous player item")

        if (currentItemIndex <= 0) {
            val loaded = loadPreviousSeasonEpisodes()
            if (!loaded || currentItemIndex <= 0) {
                return null
            }
        }

        val itemIndex = currentItemIndex - 1
        val item = items[itemIndex]
        val cached = playerItems.firstOrNull { it.itemId == item.id }
        if (cached != null) {
            return cached
        }

        return try {
            val converted =
                item.toPlayerItem(
                    mediaSourceIndex = null,
                    playbackPosition = 0L,
                    maxStreamingBitrate = maxStreamingBitrate,
                    deviceProfile = deviceProfile,
                    enableDirectPlay = enableDirectPlay,
                )
            playerItems.add(converted)
            converted
        } catch (e: Exception) {
            Timber.e("Failed to retrieve previous player item: $e")
            null
        }
    }

    /**
     * Retrieves the next [PlayerItem] in the active playlist relative to [currentItemIndex]. Lazily
     * queries the subsequent season if at the season boundary.
     *
     * @param maxStreamingBitrate Optional maximum bitrate in bps for transcode resolution.
     * @param deviceProfile Optional device profile for transcode compatibility checks.
     * @param enableDirectPlay Whether direct play is allowed for this item.
     * @return The next [PlayerItem], or `null` if at the end of the playlist or on error.
     */
    suspend fun getNextPlayerItem(
        maxStreamingBitrate: Int? = null,
        deviceProfile: DeviceProfile? = null,
        enableDirectPlay: Boolean = true,
    ): PlayerItem? {
        Timber.d("Retrieving next player item")

        if (currentItemIndex >= items.lastIndex) {
            val loaded = loadNextSeasonEpisodes()
            if (!loaded || currentItemIndex >= items.lastIndex) {
                return null
            }
        }

        val itemIndex = currentItemIndex + 1
        val item = items[itemIndex]
        val cached = playerItems.firstOrNull { it.itemId == item.id }
        if (cached != null) {
            return cached
        }

        return try {
            val converted =
                item.toPlayerItem(
                    mediaSourceIndex = null,
                    playbackPosition = 0L,
                    maxStreamingBitrate = maxStreamingBitrate,
                    deviceProfile = deviceProfile,
                    enableDirectPlay = enableDirectPlay,
                )
            playerItems.add(converted)
            converted
        } catch (e: Exception) {
            Timber.e("Failed to retrieve next player item: $e")
            null
        }
    }

    private suspend fun loadNextSeasonEpisodes(): Boolean {
        val sId = seriesId ?: return false
        val curSeasonIdx = currentSeasonIndex ?: return false
        try {
            val seasons = cachedSeasons ?: repository.getSeasons(sId).also { cachedSeasons = it }
            val nextSeason =
                if (curSeasonIdx > 0) {
                    seasons.filter { it.indexNumber > curSeasonIdx }.minByOrNull { it.indexNumber }
                } else {
                    seasons.filter { it.indexNumber > 0 }.minByOrNull { it.indexNumber }
                } ?: return false

            val nextEpisodes =
                repository
                    .getEpisodes(
                        seriesId = sId,
                        seasonId = nextSeason.id,
                        fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
                    )
                    .filter { !it.missing }

            if (nextEpisodes.isEmpty()) return false

            val newItems = nextEpisodes.flatMap { it.toPlaylist() }
            items.addAll(newItems)
            currentSeasonIndex = nextSeason.indexNumber
            currentSeasonId = nextSeason.id
            return true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load next season episodes")
            return false
        }
    }

    private suspend fun loadPreviousSeasonEpisodes(): Boolean {
        val sId = seriesId ?: return false
        val curSeasonIdx = currentSeasonIndex ?: return false
        if (curSeasonIdx <= 1) return false

        try {
            val seasons = cachedSeasons ?: repository.getSeasons(sId).also { cachedSeasons = it }
            val prevSeason =
                seasons
                    .filter { it.indexNumber in 1 until curSeasonIdx }
                    .maxByOrNull { it.indexNumber } ?: return false

            val prevEpisodes =
                repository
                    .getEpisodes(
                        seriesId = sId,
                        seasonId = prevSeason.id,
                        fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
                    )
                    .filter { !it.missing }

            if (prevEpisodes.isEmpty()) return false

            val prevItems = prevEpisodes.flatMap { it.toPlaylist() }
            items.addAll(0, prevItems)
            currentItemIndex += prevItems.size
            currentSeasonIndex = prevSeason.indexNumber
            currentSeasonId = prevSeason.id
            return true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load previous season episodes")
            return false
        }
    }

    /**
     * Updates [currentItemIndex] to match the specified item ID and synchronizes season pointers.
     *
     * @param itemId Unique identifier of the target item in the playlist.
     */
    fun setCurrentMediaItemIndex(itemId: UUID) {
        val idx = items.indexOfFirst { it.id == itemId }
        if (idx >= 0) {
            currentItemIndex = idx
            val currentItem = items[idx]
            if (currentItem is FindroidEpisode) {
                currentSeasonIndex = currentItem.parentIndexNumber
                currentSeasonId = currentItem.seasonId
            }
        }
    }

    /** Resolves and builds a [PlayerItem] with explicit quality and device profile constraints. */
    suspend fun resolvePlayerItem(
        itemId: UUID,
        mediaSourceIndex: Int? = null,
        playbackPosition: Long = 0L,
        maxStreamingBitrate: Int? = null,
        deviceProfile: DeviceProfile? = null,
        enableDirectPlay: Boolean = true,
    ): PlayerItem? {
        val item =
            items.firstOrNull { it.id == itemId } ?: repository.getItem(itemId) ?: return null
        return try {
            item.toPlayerItem(
                mediaSourceIndex = mediaSourceIndex,
                playbackPosition = playbackPosition,
                maxStreamingBitrate = maxStreamingBitrate,
                deviceProfile = deviceProfile,
                enableDirectPlay = enableDirectPlay,
            )
        } catch (e: Exception) {
            Timber.e(e, "Error resolving player item $itemId")
            null
        }
    }

    private suspend fun FindroidItem.toPlayerItem(
        mediaSourceIndex: Int?,
        playbackPosition: Long,
        maxStreamingBitrate: Int? = null,
        deviceProfile: DeviceProfile? = null,
        enableDirectPlay: Boolean = true,
    ): PlayerItem {
        Timber.d("Converting FindroidItem ${this.id} to PlayerItem")

        val mediaSources =
            try {
                repository.getMediaSources(
                    itemId = id,
                    includePath = true,
                    maxStreamingBitrate = maxStreamingBitrate,
                    deviceProfile = deviceProfile,
                    enableDirectPlay = enableDirectPlay,
                )
            } catch (e: Exception) {
                Timber.e(e, "Error fetching media sources for $id")
                emptyList()
            }
        val mediaSource =
            if (mediaSourceIndex == null) {
                mediaSources.firstOrNull { it.type == FindroidSourceType.LOCAL }
                    ?: mediaSources.firstOrNull()
            } else {
                mediaSources.getOrNull(mediaSourceIndex) ?: mediaSources.firstOrNull()
            }
        if (mediaSource == null) {
            throw IllegalStateException("No media source available for item $id")
        }

        val playMethod =
            PlayMethodHelper.determinePlayMethod(
                supportsDirectPlay = mediaSource.supportsDirectPlay,
                enableDirectPlay = enableDirectPlay,
                transcodingUrl = mediaSource.transcodingUrl,
            )

        val targetUri =
            if (
                playMethod != PlayMethod.DIRECT_PLAY || !mediaSource.transcodingUrl.isNullOrBlank()
            ) {
                StreamUtils.buildStreamUrl(
                    baseUrl = repository.getBaseUrl(),
                    itemId = id,
                    mediaSourceId = mediaSource.id,
                    transcodingUrl = mediaSource.transcodingUrl,
                )
            } else {
                mediaSource.path
            }

        val externalSubtitles =
            if (playMethod != PlayMethod.DIRECT_PLAY) {
                mediaSource.mediaStreams
                    .filter { mediaStream ->
                        mediaStream.type == MediaStreamType.SUBTITLE &&
                            !mediaStream.path.isNullOrBlank()
                    }
                    .map { mediaStream ->
                        ExternalSubtitle(
                            title = mediaStream.title,
                            language = mediaStream.language,
                            uri = mediaStream.path!!.toUri(),
                            mimeType =
                                when (mediaStream.codec.lowercase()) {
                                    "subrip",
                                    "srt" -> MimeTypes.APPLICATION_SUBRIP
                                    "webvtt",
                                    "vtt" -> MimeTypes.TEXT_VTT
                                    "ass",
                                    "ssa" -> MimeTypes.TEXT_SSA
                                    else -> MimeTypes.TEXT_VTT
                                },
                            isForced = mediaStream.isForced ?: false,
                            isHearingImpaired = mediaStream.isHearingImpaired ?: false,
                            isDefault = mediaStream.isDefault ?: false,
                            codec = mediaStream.codec,
                            displayTitle = mediaStream.displayTitle,
                            index = mediaStream.index,
                        )
                    }
            } else {
                mediaSource.mediaStreams
                    .filter { mediaStream ->
                        mediaStream.isExternal &&
                            mediaStream.type == MediaStreamType.SUBTITLE &&
                            !mediaStream.path.isNullOrBlank()
                    }
                    .map { mediaStream ->
                        ExternalSubtitle(
                            title = mediaStream.title,
                            language = mediaStream.language,
                            uri = mediaStream.path!!.toUri(),
                            mimeType =
                                when (mediaStream.codec.lowercase()) {
                                    "subrip",
                                    "srt" -> MimeTypes.APPLICATION_SUBRIP
                                    "webvtt",
                                    "vtt" -> MimeTypes.TEXT_VTT
                                    "ass",
                                    "ssa" -> MimeTypes.TEXT_SSA
                                    else -> MimeTypes.TEXT_UNKNOWN
                                },
                            isForced = mediaStream.isForced ?: false,
                            isHearingImpaired = mediaStream.isHearingImpaired ?: false,
                            isDefault = mediaStream.isDefault ?: false,
                            codec = mediaStream.codec,
                            displayTitle = mediaStream.displayTitle,
                            index = mediaStream.index,
                        )
                    }
            }
        val trickplayInfo =
            when (this) {
                is FindroidSources -> {
                    this.trickplayInfo?.get(mediaSource.id)?.let {
                        TrickplayInfo(
                            width = it.width,
                            height = it.height,
                            tileWidth = it.tileWidth,
                            tileHeight = it.tileHeight,
                            thumbnailCount = it.thumbnailCount,
                            interval = it.interval,
                            bandwidth = it.bandwidth,
                        )
                    }
                }
                else -> null
            }

        val mainName = (this as? FindroidPart)?.parentName ?: name

        val partName = if (mediaSource.name.isPartName()) mediaSource.name else null

        val episodeInfo =
            when (this) {
                is FindroidPart -> {
                    EpisodeInfo(parentIndexNumber, indexNumber, indexNumberEnd, null)
                }

                is FindroidEpisode -> {
                    EpisodeInfo(parentIndexNumber, indexNumber, indexNumberEnd, seriesName)
                }

                else -> {
                    EpisodeInfo(null, null, null, null)
                }
            }

        val mediaType =
            when (this) {
                is FindroidPart -> {
                    if (parentIndexNumber == null) {
                        PlayerMediaType.MOVIE
                    } else {
                        PlayerMediaType.EPISODE
                    }
                }
                is FindroidEpisode -> PlayerMediaType.EPISODE
                else -> PlayerMediaType.MOVIE
            }

        return PlayerItem(
            name = mainName,
            itemId = id,
            partName = partName,
            mediaType = mediaType,
            mediaSourceId = mediaSource.id,
            mediaSourceUri = targetUri,
            playbackPosition = playbackPosition,
            parentIndexNumber = episodeInfo.parentIndexNumber,
            indexNumber = episodeInfo.indexNumber,
            indexNumberEnd = episodeInfo.indexNumberEnd,
            seriesName = episodeInfo.seriesName,
            externalSubtitles = externalSubtitles,
            chapters = chapters.toPlayerChapters(),
            trickplayInfo = trickplayInfo,
            images = images.toPlayerImages(),
            playSessionId = mediaSource.playSessionId,
        )
    }

    private fun List<FindroidChapter>.toPlayerChapters(): List<PlayerChapter> {
        val serverUrl = repository.getBaseUrl()
        return this.map { chapter ->
            val imageUri =
                chapter.imageUri?.let { uri ->
                    if (uri.startsWith("/")) {
                        "$serverUrl$uri"
                    } else uri
                }
            PlayerChapter(
                startPosition = chapter.startPosition,
                name = chapter.name,
                imageUri = imageUri,
            )
        }
    }

    private fun FindroidItem.toPlaylist(): List<FindroidItem> {
        val items = mutableListOf<FindroidItem>()
        items.add(this)
        val parts =
            when (this) {
                is FindroidMovie -> this.additionalParts
                is FindroidEpisode -> this.additionalParts
                else -> emptyList()
            }
        for (part in parts) {
            items.add(part)
        }
        return items
    }

    private fun FindroidImage.toPlayerImage() =
        PlayerImage(
            uri = uri,
            blurHash = blurHash,
        )

    private fun FindroidImages.toPlayerImages() =
        PlayerImages(
            primary = primary?.toPlayerImage(),
            backdrop = backdrop?.toPlayerImage(),
            logo = logo?.toPlayerImage(),
            showPrimary = showPrimary?.toPlayerImage(),
            showBackdrop = showBackdrop?.toPlayerImage(),
            showLogo = showLogo?.toPlayerImage(),
        )

    private data class EpisodeInfo(
        val parentIndexNumber: Int?,
        val indexNumber: Int?,
        val indexNumberEnd: Int?,
        val seriesName: String?,
    )
}
