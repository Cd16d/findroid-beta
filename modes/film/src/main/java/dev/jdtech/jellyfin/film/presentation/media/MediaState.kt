package dev.jdtech.jellyfin.film.presentation.media

import dev.jdtech.jellyfin.models.FindroidCollection

data class MediaState(
    val libraries: List<FindroidCollection> = emptyList(),
    val isLoading: Boolean = false,
    val isOffline: Boolean = false,
    val isCheckingConnection: Boolean = false,
    val error: Exception? = null,
)
