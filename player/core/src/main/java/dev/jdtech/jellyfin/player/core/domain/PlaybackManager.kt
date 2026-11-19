package dev.jdtech.jellyfin.player.core.domain

import androidx.media3.common.C
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadQueue
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import org.jellyfin.sdk.model.api.PlayMethod
import timber.log.Timber

/**
 * Centralized manager for reporting playback events to the Jellyfin server. Ensures consistent data
 * formatting and error handling across different player implementations.
 */
@Singleton
class PlaybackManager
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val downloadQueue: dagger.Lazy<DownloadQueue>,
) {
    @Volatile private var activeItemId: UUID? = null
    @Volatile private var activeMediaSourceId: String? = null
    @Volatile private var activePlaySessionId: String? = null
    @Volatile private var activePlayMethod: PlayMethod = PlayMethod.DIRECT_PLAY
    @Volatile private var isSessionActive: Boolean = false

    val isReporting: Boolean
        get() = isSessionActive

    val currentPlaySessionId: String?
        get() = activePlaySessionId

    val currentPlayMethod: PlayMethod
        get() = activePlayMethod

    /**
     * Starts a new managed playback session. If an existing session is running, it is automatically
     * reported as stopped before starting the new session.
     */
    suspend fun startSession(
        itemId: UUID,
        positionMs: Long? = null,
        playMethod: PlayMethod = PlayMethod.DIRECT_PLAY,
        mediaSourceId: String? = null,
        playSessionId: String? = null,
        previousDurationMs: Long? = null,
    ) {
        if (isSessionActive && activeItemId != null) {
            reportStop(
                itemId = activeItemId!!,
                positionMs = positionMs ?: 0L,
                durationMs = previousDurationMs ?: 1L,
                mediaSourceId = activeMediaSourceId,
                playSessionId = activePlaySessionId,
            )
        }
        activeItemId = itemId
        activeMediaSourceId = mediaSourceId
        activePlaySessionId = playSessionId
        activePlayMethod = playMethod
        isSessionActive = true

        reportStart(
            itemId = itemId,
            positionMs = positionMs,
            playMethod = playMethod,
            mediaSourceId = mediaSourceId,
            playSessionId = playSessionId,
        )
    }

    /** Reports periodic progress using active session parameters. */
    suspend fun reportProgress(
        positionMs: Long,
        isPaused: Boolean,
        playSessionId: String? = null,
    ) {
        val itemId = activeItemId ?: return
        if (!isSessionActive) return
        val effectivePlaySessionId = playSessionId ?: activePlaySessionId
        if (playSessionId != null && playSessionId != activePlaySessionId) {
            activePlaySessionId = playSessionId
        }
        reportProgress(
            itemId = itemId,
            positionMs = positionMs,
            isPaused = isPaused,
            playMethod = activePlayMethod,
            mediaSourceId = activeMediaSourceId,
            playSessionId = effectivePlaySessionId,
        )
    }

    /** Stops the active playback session and resets session tracking. */
    suspend fun stopSession(
        positionMs: Long,
        durationMs: Long,
    ) {
        val itemId = activeItemId ?: return
        if (!isSessionActive) return
        reportStop(
            itemId = itemId,
            positionMs = positionMs,
            durationMs = durationMs,
            mediaSourceId = activeMediaSourceId,
            playSessionId = activePlaySessionId,
        )
        isSessionActive = false
        activeItemId = null
        activeMediaSourceId = null
        activePlaySessionId = null
    }

    fun updateActivePlayMethod(playMethod: PlayMethod) {
        activePlayMethod = playMethod
    }

    fun updateActiveSessionParameters(
        playSessionId: String?,
        mediaSourceId: String? = null,
        playMethod: PlayMethod = PlayMethod.DIRECT_PLAY,
    ) {
        activePlaySessionId = playSessionId
        mediaSourceId?.let { activeMediaSourceId = it }
        activePlayMethod = playMethod
    }

    /**
     * Stops a specific stream/transcode session on the server to terminate background FFmpeg jobs
     * without triggering watched status or auto-delete side effects.
     */
    suspend fun stopStreamSession(
        itemId: UUID,
        positionMs: Long,
        mediaSourceId: String? = null,
        playSessionId: String? = null,
    ) {
        if (playSessionId.isNullOrEmpty()) return
        try {
            repository.postPlaybackStop(
                itemId = itemId,
                positionTicks = positionMs.coerceAtLeast(0L) * 10000L,
                playedPercentage = 0,
                mediaSourceId = mediaSourceId,
                playSessionId = playSessionId,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop stream session: $playSessionId")
        }
    }

    /**
     * Handles switching stream sessions (e.g. transcode reload on audio track or quality switch).
     * If the playSessionId has changed, stops the old session on the server to terminate its FFmpeg
     * transcode job, then starts the new session with the updated parameters.
     */
    suspend fun switchStreamSession(
        itemId: UUID,
        positionMs: Long,
        newPlaySessionId: String?,
        newMediaSourceId: String?,
        newPlayMethod: PlayMethod,
        oldPlaySessionId: String? = activePlaySessionId,
        oldMediaSourceId: String? = activeMediaSourceId,
    ) {
        if (oldPlaySessionId != null && oldPlaySessionId != newPlaySessionId) {
            stopStreamSession(
                itemId = itemId,
                positionMs = positionMs,
                mediaSourceId = oldMediaSourceId,
                playSessionId = oldPlaySessionId,
            )
        }

        activeItemId = itemId
        activePlaySessionId = newPlaySessionId
        activeMediaSourceId = newMediaSourceId
        activePlayMethod = newPlayMethod
        isSessionActive = true

        reportStart(
            itemId = itemId,
            positionMs = positionMs,
            playMethod = newPlayMethod,
            mediaSourceId = newMediaSourceId,
            playSessionId = newPlaySessionId,
        )
    }

    /**
     * Reports that playback has started for a specific item.
     *
     * @param itemId The UUID of the item being played.
     * @param positionMs The starting playback position in milliseconds.
     * @param playMethod The [PlayMethod] used for playback (defaults to [PlayMethod.DIRECT_PLAY]).
     * @param mediaSourceId The ID of the media source (defaults to null).
     * @param playSessionId The ID of the play session (defaults to null).
     */
    suspend fun reportStart(
        itemId: UUID,
        positionMs: Long? = null,
        playMethod: PlayMethod = PlayMethod.DIRECT_PLAY,
        mediaSourceId: String? = null,
        playSessionId: String? = null,
    ) {
        try {
            repository.postPlaybackStart(
                itemId = itemId,
                positionTicks =
                    positionMs?.let {
                        it * 10000
                    }, // Convert ms to ticks (1 tick = 100 nanoseconds)
                playMethod = playMethod,
                mediaSourceId = mediaSourceId,
                playSessionId = playSessionId,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to report playback start for item: $itemId")
        }
    }

    /**
     * Reports the current playback progress (position and pause state).
     *
     * @param itemId The UUID of the item being played.
     * @param positionMs The current playback position in milliseconds. Must be non-negative.
     * @param isPaused Whether the playback is currently paused (defaults to false).
     * @param playMethod The [PlayMethod] used for playback (defaults to [PlayMethod.DIRECT_PLAY]).
     * @param mediaSourceId The ID of the media source (defaults to null).
     * @param playSessionId The ID of the play session (defaults to null).
     */
    suspend fun reportProgress(
        itemId: UUID,
        positionMs: Long,
        isPaused: Boolean = false,
        playMethod: PlayMethod = PlayMethod.DIRECT_PLAY,
        mediaSourceId: String? = null,
        playSessionId: String? = null,
    ) {
        if (positionMs < 0) {
            Timber.w(
                "Skipping playback progress report for item $itemId: invalid positionMs: $positionMs",
            )
            return
        }
        try {
            repository.postPlaybackProgress(
                itemId = itemId,
                positionTicks =
                    positionMs * 10000, // Convert ms to ticks (1 tick = 100 nanoseconds)
                isPaused = isPaused,
                playMethod = playMethod,
                mediaSourceId = mediaSourceId,
                playSessionId = playSessionId,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to report playback progress for item: $itemId")
        }
    }

    /**
     * Reports that playback has stopped. Calculates the played percentage based on current position
     * and total duration.
     *
     * @param itemId The UUID of the item being played.
     * @param positionMs The final playback position in milliseconds. Must be non-negative.
     * @param durationMs The total duration of the item in milliseconds. Must be greater than 0.
     * @param mediaSourceId The ID of the media source (defaults to null).
     * @param playSessionId The ID of the play session (defaults to null).
     */
    suspend fun reportStop(
        itemId: UUID,
        positionMs: Long,
        durationMs: Long,
        mediaSourceId: String? = null,
        playSessionId: String? = null,
    ) {
        if (positionMs < 0) {
            Timber.w(
                "Skipping playback progress report for item $itemId: invalid positionMs: $positionMs",
            )
            return
        }
        if (durationMs == C.TIME_UNSET || durationMs <= 0L) {
            Timber.w("Skipping playback stop report for item ${itemId}: invalid duration.")
            return
        }

        val positionTicks = positionMs * 10000 // Convert ms to ticks (1 tick = 100 nanoseconds)
        val playedPercentage =
            (positionMs.toFloat() / durationMs.toFloat() * 100).toInt().coerceIn(0, 100)

        try {
            repository.postPlaybackStop(
                itemId = itemId,
                positionTicks = positionTicks,
                playedPercentage = playedPercentage,
                mediaSourceId = mediaSourceId,
                playSessionId = playSessionId,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to report playback stop for item: $itemId")
        }

        if (playedPercentage >= 90) {
            try {
                downloadQueue.get().checkAutoDeleteWatched(itemId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to trigger auto delete for watched item: $itemId")
            }
            try {
                downloadQueue.get().checkSmartDownloadOnWatched(itemId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to trigger smart download for watched item: $itemId")
            }
        }

        try {
            downloadQueue.get().scheduleUserDataSync()
        } catch (e: Exception) {
            Timber.e(e, "Failed to schedule user data sync for item: $itemId")
        }
    }
}
