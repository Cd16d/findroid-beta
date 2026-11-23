package dev.jdtech.jellyfin.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/** Process-wide LRU cache of decoded user avatar bitmaps keyed by absolute local file path. */
object UserAvatarBitmapCache {
    private val cache = LruCache<String, Bitmap>(32)

    fun get(path: String): Bitmap? = cache.get(path)

    fun put(path: String, bitmap: Bitmap) {
        cache.put(path, bitmap)
    }

    fun evictPrefix(prefix: String) {
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
    }
}

@Serializable
private data class UserSummaryPayload(
    @SerialName("Name") val name: String? = null,
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
)

/**
 * Manages offline-persisted, density-sized user avatars and Jellyfin `primaryImageTag` validation
 * across all saved accounts.
 */
class UserAvatarStore(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Exact pixel size corresponding to the 56dp header avatar scaled by display density. */
    fun getHeaderAvatarSizePx(): Int =
        (56f * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(112)

    /** Returns the persisted avatar file if present and non-empty on disk, or null otherwise. */
    fun getLocalAvatarFile(userId: UUID, primaryImageTag: String?): File? {
        val fileName = if (primaryImageTag != null) "primary_$primaryImageTag" else "primary"
        val file = File(userDir(userId), fileName)
        return file.takeIf { it.exists() && it.length() > 0L }
    }

    /**
     * Preloads decoded bitmaps for all saved users of the active server into
     * [UserAvatarBitmapCache] so frame 0 renders avatars instantaneously without placeholder
     * flicker.
     */
    suspend fun preloadActiveServerAvatars() {
        withContext(Dispatchers.IO) {
            try {
                val serverId =
                    appPreferences.getValue(appPreferences.currentServer) ?: return@withContext
                val users = database.getUsers(serverId)
                for (user in users) {
                    val file = getLocalAvatarFile(user.id, user.primaryImageTag) ?: continue
                    cacheFileBitmap(file)
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to preload user avatar bitmaps")
            }
        }
    }

    /**
     * Ensures the local avatar file for [userId] matches [serverTag]. Reuses the existing disk file
     * when the tag matches; otherwise downloads the exact-sized image, purges stale tag files, and
     * updates [UserAvatarBitmapCache].
     */
    suspend fun ensureUserAvatar(
        userId: UUID,
        baseUrl: String,
        serverTag: String?,
        accessToken: String? = null,
    ): File? =
        withContext(Dispatchers.IO) {
            val dir = userDir(userId)
            if (serverTag.isNullOrBlank()) {
                deleteUserFiles(dir, keepFileName = null)
                return@withContext null
            }

            val fileName = "primary_$serverTag"
            val targetFile = File(dir, fileName)
            if (targetFile.exists() && targetFile.length() > 0L) {
                cacheFileBitmap(targetFile)
                return@withContext targetFile
            }

            val cleanBaseUrl = baseUrl.trim().trimEnd('/')
            if (cleanBaseUrl.isBlank()) {
                return@withContext null
            }

            val px = getHeaderAvatarSizePx()
            val imageUrl =
                "$cleanBaseUrl/Users/$userId/Images/Primary?tag=$serverTag&maxWidth=$px&maxHeight=$px&quality=90"

            val requestBuilder = Request.Builder().url(imageUrl)
            if (!accessToken.isNullOrBlank()) {
                requestBuilder.header("X-Emby-Token", accessToken)
            }

            try {
                dir.mkdirs()
                val tmpFile = File(dir, "$fileName.tmp")
                okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.w("Avatar download failed (%d) for user %s", response.code, userId)
                        return@withContext null
                    }
                    response.body.byteStream().use { input ->
                        tmpFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (tmpFile.exists() && tmpFile.length() > 0L) {
                    if (targetFile.exists()) targetFile.delete()
                    if (!tmpFile.renameTo(targetFile)) {
                        tmpFile.copyTo(targetFile, overwrite = true)
                        tmpFile.delete()
                    }
                    deleteUserFiles(dir, keepFileName = fileName)
                    cacheFileBitmap(targetFile)
                    targetFile
                } else {
                    tmpFile.delete()
                    null
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to download user avatar for %s", userId)
                null
            }
        }

    /**
     * Checks `primaryImageTag` for every saved user on [serverId], updating Room and re-downloading
     * local avatar files only when a tag changed or the file is missing.
     */
    suspend fun syncAllUsersForServer(
        serverId: String,
        baseUrl: String,
        currentUserTagOverride: Pair<UUID, String?>? = null,
    ) {
        withContext(Dispatchers.IO) {
            val cleanBaseUrl = baseUrl.trim().trimEnd('/')
            if (cleanBaseUrl.isBlank()) return@withContext

            val users = database.getUsers(serverId)
            for (user in users) {
                runCatching {
                    val overrideTag =
                        if (currentUserTagOverride?.first == user.id) {
                            currentUserTagOverride.second
                        } else {
                            null
                        }

                    var resolvedName = user.name
                    var resolvedTag = user.primaryImageTag
                    var hasFreshTag = false

                    if (currentUserTagOverride?.first == user.id) {
                        resolvedTag = overrideTag
                        hasFreshTag = true
                    } else if (!user.accessToken.isNullOrBlank()) {
                        val userUrl = "$cleanBaseUrl/Users/${user.id}"
                        val req =
                            Request.Builder()
                                .url(userUrl)
                                .header("X-Emby-Token", user.accessToken)
                                .build()
                        okHttpClient.newCall(req).execute().use { response ->
                            if (response.isSuccessful) {
                                val bodyStr = response.body.string()
                                val payload = json.decodeFromString<UserSummaryPayload>(bodyStr)
                                resolvedName = payload.name ?: user.name
                                resolvedTag = payload.primaryImageTag
                                hasFreshTag = true
                            }
                        }
                    }

                    if (
                        hasFreshTag &&
                            (resolvedTag != user.primaryImageTag || resolvedName != user.name)
                    ) {
                        database.insertUser(
                            user.copy(name = resolvedName, primaryImageTag = resolvedTag),
                        )
                    }

                    ensureUserAvatar(
                        userId = user.id,
                        baseUrl = cleanBaseUrl,
                        serverTag = resolvedTag,
                        accessToken = user.accessToken,
                    )
                }
                    .onFailure { e -> Timber.w(e, "Failed to sync avatar for user %s", user.id) }
            }
        }
    }

    private fun cacheFileBitmap(file: File) {
        if (UserAvatarBitmapCache.get(file.absolutePath) != null) return
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap != null) {
            UserAvatarBitmapCache.put(file.absolutePath, bitmap)
        }
    }

    private fun deleteUserFiles(dir: File, keepFileName: String?) {
        if (!dir.exists()) return
        UserAvatarBitmapCache.evictPrefix(dir.absolutePath)
        dir.listFiles { _, name ->
                name.startsWith("primary") && name != keepFileName && !name.endsWith(".tmp")
            }
            ?.forEach { it.delete() }
        // Re-warm kept bitmap if eviction cleared it
        if (keepFileName != null) {
            val kept = File(dir, keepFileName)
            if (kept.exists() && kept.length() > 0L) {
                cacheFileBitmap(kept)
            }
        }
    }

    private fun userDir(userId: UUID): File = File(context.filesDir, "images/users/$userId")
}
