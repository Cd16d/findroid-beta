package dev.jdtech.jellyfin.player.core.domain.models

/**
 * Encapsulates the initial 3-item playback window around the active media item.
 *
 * @property previousItem The immediately preceding media item, or `null` if at the beginning of the
 *   playlist.
 * @property currentItem The currently active media item requested for playback.
 * @property nextItem The immediately following media item, or `null` if at the end of the playlist.
 */
data class PlaylistWindow(
    val previousItem: PlayerItem?,
    val currentItem: PlayerItem,
    val nextItem: PlayerItem?,
)
