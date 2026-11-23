package dev.jdtech.jellyfin.cache

import android.content.Context
import android.net.Uri
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidBoxSet
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidFolder
import dev.jdtech.jellyfin.models.FindroidImage
import dev.jdtech.jellyfin.models.FindroidImages
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

@Serializable
data class HomeCacheSnapshot(
    val serverId: String,
    val serverName: String,
    val userId: String,
    val userName: String,
    val userPrimaryImageTag: String? = null,
    val currentSuggestions: List<CachedHomeItem> = emptyList(),
    val bufferedSuggestions: List<CachedHomeItem> = emptyList(),
    val resumeItems: List<CachedHomeItem> = emptyList(),
    val nextUpItems: List<CachedHomeItem> = emptyList(),
    val views: List<CachedHomeView> = emptyList(),
    val updatedAtMs: Long = 0L,
)

@Serializable
data class CachedHomeView(
    val id: String,
    val name: String,
    val type: String,
    val items: List<CachedHomeItem> = emptyList(),
)

@Serializable data class CachedFindroidImage(val uri: String? = null, val blurHash: String? = null)

@Serializable
data class CachedFindroidImages(
    val primary: CachedFindroidImage? = null,
    val backdrop: CachedFindroidImage? = null,
    val logo: CachedFindroidImage? = null,
    val showPrimary: CachedFindroidImage? = null,
    val showBackdrop: CachedFindroidImage? = null,
    val showLogo: CachedFindroidImage? = null,
)

@Serializable
data class CachedHomeItem(
    val kind: String,
    val id: String,
    val name: String,
    val originalTitle: String? = null,
    val overview: String = "",
    val played: Boolean = false,
    val favorite: Boolean = false,
    val canPlay: Boolean = true,
    val canDownload: Boolean = false,
    val runtimeTicks: Long = 0L,
    val playbackPositionTicks: Long = 0L,
    val unplayedItemCount: Int? = null,
    val premiereDate: String? = null,
    val productionYear: Int? = null,
    val endDate: String? = null,
    val genres: List<String> = emptyList(),
    val communityRating: Float? = null,
    val criticRating: Float? = null,
    val officialRating: String? = null,
    val status: String = "Ended",
    val indexNumber: Int = 0,
    val indexNumberEnd: Int? = null,
    val parentIndexNumber: Int = 0,
    val seriesId: String? = null,
    val seriesName: String = "",
    val seasonId: String? = null,
    val seasonName: String? = null,
    val missing: Boolean = false,
    val images: CachedFindroidImages = CachedFindroidImages(),
)

/**
 * Persistent disk-backed SWR cache for the Home screen with double-buffered Suggestions rotation
 * across app sessions.
 */
class HomeCacheStore(
    private val context: Context,
    private val appPreferences: AppPreferences,
    private val database: ServerDatabaseDao,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val cacheDir: File by lazy {
        File(context.noBackupFilesDir, "home_cache").apply { mkdirs() }
    }

    private val inMemorySnapshots = ConcurrentHashMap<String, HomeCacheSnapshot>()
    private val promotedSessions = ConcurrentHashMap.newKeySet<String>()
    private val writeMutex = Mutex()

    @Volatile private var activeCacheKey: String? = null

    private val _isPreloadComplete = MutableStateFlow(false)
    val isPreloadComplete: StateFlow<Boolean> = _isPreloadComplete.asStateFlow()

    /**
     * Preloads the active user's cached Home snapshot into memory during app startup and promotes
     * any buffered suggestions carousel once per process session.
     */
    suspend fun preloadActiveUserCache() {
        try {
            withContext(Dispatchers.IO) {
                val serverId =
                    appPreferences.getValue(appPreferences.currentServer) ?: return@withContext
                val user = database.getServerCurrentUser(serverId) ?: return@withContext
                loadSnapshot(serverId, user.id)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to preload active user home cache")
        } finally {
            _isPreloadComplete.value = true
        }
    }

    /**
     * Synchronous non-blocking memory read so HomeViewModel can render cached sections on frame 0.
     */
    fun getPreloadedSnapshot(serverId: String? = null, userId: UUID? = null): HomeCacheSnapshot? {
        if (serverId != null && userId != null) {
            return inMemorySnapshots[cacheKey(serverId, userId)]
        }
        val targetServerId =
            serverId ?: appPreferences.getValue(appPreferences.currentServer) ?: return null
        val key = activeCacheKey ?: return null
        return if (key.startsWith("${sanitize(targetServerId)}_")) {
            inMemorySnapshots[key]
        } else {
            null
        }
    }

    /**
     * Loads the snapshot for [serverId] and [userId] from memory or disk, promoting any buffered
     * suggestions on the first read of this process session.
     */
    suspend fun loadSnapshot(serverId: String, userId: UUID): HomeCacheSnapshot? {
        return withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val key = cacheKey(serverId, userId)
                val existing = inMemorySnapshots[key] ?: readSnapshotFromDisk(serverId, userId)
                if (existing == null) {
                    return@withLock null
                }
                val result = promoteBufferIfNeededLocked(serverId, userId, existing)
                inMemorySnapshots[key] = result
                activeCacheKey = key
                result
            }
        }
    }

    /**
     * Returns whether a next-session buffered carousel is already stored for [serverId] and
     * [userId].
     */
    suspend fun hasBufferedSuggestions(serverId: String, userId: UUID): Boolean {
        return withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val key = cacheKey(serverId, userId)
                val snapshot = inMemorySnapshots[key] ?: readSnapshotFromDisk(serverId, userId)
                snapshot?.bufferedSuggestions?.isNotEmpty() == true
            }
        }
    }

    /**
     * Persists a refreshed Home snapshot while preserving any already-buffered suggestions unless
     * [bufferedSuggestions] is explicitly provided.
     */
    suspend fun saveSnapshot(
        server: Server,
        userId: UUID,
        userName: String,
        userPrimaryImageTag: String?,
        currentSuggestions: List<FindroidItem>,
        resumeItems: List<FindroidItem>,
        nextUpItems: List<FindroidItem>,
        views: List<CachedHomeView>,
        bufferedSuggestions: List<FindroidItem>? = null,
    ) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val key = cacheKey(server.id, userId)
                val previous = inMemorySnapshots[key] ?: readSnapshotFromDisk(server.id, userId)
                val resolvedBuffer =
                    bufferedSuggestions?.map { it.toCachedHomeItem() }
                        ?: previous?.bufferedSuggestions.orEmpty()

                val snapshot =
                    HomeCacheSnapshot(
                        serverId = server.id,
                        serverName = server.name,
                        userId = userId.toString(),
                        userName = userName,
                        userPrimaryImageTag = userPrimaryImageTag,
                        currentSuggestions = currentSuggestions.map { it.toCachedHomeItem() },
                        bufferedSuggestions = resolvedBuffer,
                        resumeItems = resumeItems.map { it.toCachedHomeItem() },
                        nextUpItems = nextUpItems.map { it.toCachedHomeItem() },
                        views = views,
                        updatedAtMs = System.currentTimeMillis(),
                    )
                inMemorySnapshots[key] = snapshot
                activeCacheKey = key
                promotedSessions.add(sessionKey(server.id, userId))
                writeSnapshotToDisk(server.id, userId, snapshot)
            }
        }
    }

    /**
     * Updates only [HomeCacheSnapshot.bufferedSuggestions] in background without altering the
     * currently displayed carousel.
     */
    suspend fun saveBufferedSuggestions(
        serverId: String,
        userId: UUID,
        items: List<FindroidItem>,
    ) {
        if (items.isEmpty()) return
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val key = cacheKey(serverId, userId)
                val existing = inMemorySnapshots[key] ?: readSnapshotFromDisk(serverId, userId)
                val cachedItems = items.map { it.toCachedHomeItem() }
                val updated =
                    existing?.copy(
                        bufferedSuggestions = cachedItems,
                        updatedAtMs = System.currentTimeMillis(),
                    ) ?: return@withLock
                inMemorySnapshots[key] = updated
                writeSnapshotToDisk(serverId, userId, updated)
            }
        }
    }

    /** Updates cached user metadata (name and image tag) after a background profile sync. */
    suspend fun updateUserMetadata(
        serverId: String,
        userId: UUID,
        userName: String,
        primaryImageTag: String?,
    ) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val key = cacheKey(serverId, userId)
                val existing =
                    inMemorySnapshots[key]
                        ?: readSnapshotFromDisk(serverId, userId)
                        ?: return@withLock
                if (
                    existing.userName == userName && existing.userPrimaryImageTag == primaryImageTag
                ) {
                    return@withLock
                }
                val updated =
                    existing.copy(
                        userName = userName,
                        userPrimaryImageTag = primaryImageTag,
                    )
                inMemorySnapshots[key] = updated
                writeSnapshotToDisk(serverId, userId, updated)
            }
        }
    }

    private fun promoteBufferIfNeededLocked(
        serverId: String,
        userId: UUID,
        snapshot: HomeCacheSnapshot,
    ): HomeCacheSnapshot {
        val sKey = sessionKey(serverId, userId)
        if (!promotedSessions.add(sKey)) {
            return snapshot
        }
        if (snapshot.bufferedSuggestions.isEmpty()) {
            return snapshot
        }
        val promoted =
            snapshot.copy(
                currentSuggestions = snapshot.bufferedSuggestions,
                bufferedSuggestions = emptyList(),
            )
        writeSnapshotToDisk(serverId, userId, promoted)
        return promoted
    }

    private fun readSnapshotFromDisk(serverId: String, userId: UUID): HomeCacheSnapshot? {
        val file = snapshotFile(serverId, userId)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            json.decodeFromString<HomeCacheSnapshot>(file.readText())
        } catch (e: Exception) {
            Timber.w(e, "Failed to decode home cache snapshot %s", file.name)
            runCatching { file.delete() }
            null
        }
    }

    private fun writeSnapshotToDisk(serverId: String, userId: UUID, snapshot: HomeCacheSnapshot) {
        val target = snapshotFile(serverId, userId)
        val tmp = File(cacheDir, "${target.name}.tmp")
        try {
            cacheDir.mkdirs()
            tmp.writeText(json.encodeToString(HomeCacheSnapshot.serializer(), snapshot))
            if (target.exists()) {
                target.delete()
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to persist home cache snapshot %s", target.name)
            runCatching { tmp.delete() }
        }
    }

    private fun cacheKey(serverId: String, userId: UUID): String =
        "${sanitize(serverId)}_${sanitize(userId.toString())}"

    private fun sessionKey(serverId: String, userId: UUID): String = "$serverId:$userId"

    private fun snapshotFile(serverId: String, userId: UUID): File =
        File(cacheDir, "${cacheKey(serverId, userId)}.json")

    private fun sanitize(raw: String): String = raw.replace(Regex("[^a-zA-Z0-9._-]"), "_")
}

private const val KIND_MOVIE = "MOVIE"
private const val KIND_SHOW = "SHOW"
private const val KIND_EPISODE = "EPISODE"
private const val KIND_SEASON = "SEASON"
private const val KIND_BOX_SET = "BOX_SET"
private const val KIND_FOLDER = "FOLDER"

fun CachedHomeItem.toFindroidItem(): FindroidItem? {
    val parsedId = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
    val mappedImages = images.toFindroidImages()
    return when (kind) {
        KIND_MOVIE ->
            FindroidMovie(
                id = parsedId,
                name = name,
                originalTitle = originalTitle,
                overview = overview,
                sources = emptyList(),
                played = played,
                favorite = favorite,
                canPlay = canPlay,
                canDownload = canDownload,
                runtimeTicks = runtimeTicks,
                playbackPositionTicks = playbackPositionTicks,
                premiereDate = premiereDate.toLocalDateTimeOrNull(),
                people = emptyList(),
                genres = genres,
                communityRating = communityRating,
                criticRating = criticRating,
                providerIds = emptyMap(),
                officialRating = officialRating,
                status = status,
                productionYear = productionYear,
                endDate = endDate.toLocalDateTimeOrNull(),
                trailer = null,
                unplayedItemCount = unplayedItemCount,
                images = mappedImages,
                chapters = emptyList(),
                trickplayInfo = null,
                additionalParts = emptyList(),
            )
        KIND_SHOW ->
            FindroidShow(
                id = parsedId,
                name = name,
                originalTitle = originalTitle,
                overview = overview,
                sources = emptyList(),
                seasons = emptyList(),
                played = played,
                favorite = favorite,
                canPlay = canPlay,
                canDownload = canDownload,
                playbackPositionTicks = playbackPositionTicks,
                unplayedItemCount = unplayedItemCount,
                genres = genres,
                people = emptyList(),
                runtimeTicks = runtimeTicks,
                communityRating = communityRating,
                criticRating = criticRating,
                providerIds = emptyMap(),
                officialRating = officialRating,
                status = status,
                productionYear = productionYear,
                endDate = endDate.toLocalDateTimeOrNull(),
                trailer = null,
                images = mappedImages,
            )
        KIND_EPISODE -> {
            val parsedSeriesId =
                seriesId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
            val parsedSeasonId =
                seasonId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
            FindroidEpisode(
                id = parsedId,
                name = name,
                originalTitle = originalTitle,
                overview = overview,
                indexNumber = indexNumber,
                indexNumberEnd = indexNumberEnd,
                parentIndexNumber = parentIndexNumber,
                sources = emptyList(),
                played = played,
                favorite = favorite,
                canPlay = canPlay,
                canDownload = canDownload,
                runtimeTicks = runtimeTicks,
                playbackPositionTicks = playbackPositionTicks,
                premiereDate = premiereDate.toLocalDateTimeOrNull(),
                seriesId = parsedSeriesId,
                seriesName = seriesName,
                seasonId = parsedSeasonId,
                seasonName = seasonName,
                communityRating = communityRating,
                criticRating = criticRating,
                providerIds = emptyMap(),
                people = emptyList(),
                unplayedItemCount = unplayedItemCount,
                missing = missing,
                images = mappedImages,
                chapters = emptyList(),
                trickplayInfo = null,
            )
        }
        KIND_SEASON -> {
            val parsedSeriesId =
                seriesId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
            FindroidSeason(
                id = parsedId,
                name = name,
                seriesId = parsedSeriesId,
                seriesName = seriesName,
                originalTitle = originalTitle,
                overview = overview,
                sources = emptyList(),
                indexNumber = indexNumber,
                episodes = emptyList(),
                premiereDate = premiereDate.toLocalDateTimeOrNull(),
                productionYear = productionYear,
                played = played,
                favorite = favorite,
                canPlay = canPlay,
                canDownload = canDownload,
                runtimeTicks = runtimeTicks,
                playbackPositionTicks = playbackPositionTicks,
                unplayedItemCount = unplayedItemCount,
                images = mappedImages,
            )
        }
        KIND_BOX_SET ->
            FindroidBoxSet(
                id = parsedId,
                name = name,
                originalTitle = originalTitle,
                overview = overview,
                played = played,
                favorite = favorite,
                canPlay = canPlay,
                canDownload = canDownload,
                sources = emptyList(),
                runtimeTicks = runtimeTicks,
                playbackPositionTicks = playbackPositionTicks,
                unplayedItemCount = unplayedItemCount,
                images = mappedImages,
            )
        KIND_FOLDER ->
            FindroidFolder(
                id = parsedId,
                name = name,
                originalTitle = originalTitle,
                overview = overview,
                played = played,
                favorite = favorite,
                canPlay = canPlay,
                canDownload = canDownload,
                sources = emptyList(),
                runtimeTicks = runtimeTicks,
                playbackPositionTicks = playbackPositionTicks,
                unplayedItemCount = unplayedItemCount,
                images = mappedImages,
            )
        else -> null
    }
}

fun FindroidItem.toCachedHomeItem(): CachedHomeItem {
    val cachedImages = images.toCachedFindroidImages()
    return when (this) {
        is FindroidMovie ->
            CachedHomeItem(
                kind = KIND_MOVIE,
                id = id.toString(),
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
                premiereDate = premiereDate?.toString(),
                productionYear = productionYear,
                endDate = endDate?.toString(),
                genres = genres,
                communityRating = communityRating,
                criticRating = criticRating,
                officialRating = officialRating,
                status = status,
                images = cachedImages,
            )
        is FindroidShow ->
            CachedHomeItem(
                kind = KIND_SHOW,
                id = id.toString(),
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
                productionYear = productionYear,
                endDate = endDate?.toString(),
                genres = genres,
                communityRating = communityRating,
                criticRating = criticRating,
                officialRating = officialRating,
                status = status,
                images = cachedImages,
            )
        is FindroidEpisode ->
            CachedHomeItem(
                kind = KIND_EPISODE,
                id = id.toString(),
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
                premiereDate = premiereDate?.toString(),
                communityRating = communityRating,
                criticRating = criticRating,
                indexNumber = indexNumber,
                indexNumberEnd = indexNumberEnd,
                parentIndexNumber = parentIndexNumber,
                seriesId = seriesId.toString(),
                seriesName = seriesName,
                seasonId = seasonId.toString(),
                seasonName = seasonName,
                missing = missing,
                images = cachedImages,
            )
        is FindroidSeason ->
            CachedHomeItem(
                kind = KIND_SEASON,
                id = id.toString(),
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
                premiereDate = premiereDate?.toString(),
                productionYear = productionYear,
                indexNumber = indexNumber,
                seriesId = seriesId.toString(),
                seriesName = seriesName,
                images = cachedImages,
            )
        is FindroidBoxSet ->
            CachedHomeItem(
                kind = KIND_BOX_SET,
                id = id.toString(),
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
                images = cachedImages,
            )
        else ->
            CachedHomeItem(
                kind = KIND_FOLDER,
                id = id.toString(),
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
                images = cachedImages,
            )
    }
}

private fun FindroidImages.toCachedFindroidImages(): CachedFindroidImages =
    CachedFindroidImages(
        primary = primary?.toCached(),
        backdrop = backdrop?.toCached(),
        logo = logo?.toCached(),
        showPrimary = showPrimary?.toCached(),
        showBackdrop = showBackdrop?.toCached(),
        showLogo = showLogo?.toCached(),
    )

private fun FindroidImage.toCached(): CachedFindroidImage =
    CachedFindroidImage(uri = uri?.toString(), blurHash = blurHash)

private fun CachedFindroidImages.toFindroidImages(): FindroidImages =
    FindroidImages(
        primary = primary?.toFindroidImage(),
        backdrop = backdrop?.toFindroidImage(),
        logo = logo?.toFindroidImage(),
        showPrimary = showPrimary?.toFindroidImage(),
        showBackdrop = showBackdrop?.toFindroidImage(),
        showLogo = showLogo?.toFindroidImage(),
    )

private fun CachedFindroidImage.toFindroidImage(): FindroidImage? {
    if (uri == null && blurHash == null) return null
    val parsedUri = uri?.let { runCatching { Uri.parse(it) }.getOrNull() }
    return FindroidImage(uri = parsedUri, blurHash = blurHash)
}

private fun String?.toLocalDateTimeOrNull(): LocalDateTime? =
    this?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
