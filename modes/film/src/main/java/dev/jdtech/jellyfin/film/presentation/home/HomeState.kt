package dev.jdtech.jellyfin.film.presentation.home

import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.Server

data class HomeState(
    val server: Server? = null,
    val userName: String = "",
    val userImageUrl: Any? = null,
    val suggestionsSection: HomeItem.Suggestions? = null,
    val resumeSection: HomeItem.Section? = null,
    val nextUpSection: HomeItem.Section? = null,
    val offlineDownloadsSection: HomeItem.Section? = null,
    val views: List<HomeItem.ViewItem> = emptyList(),
    val showLogos: Boolean = false,
    val hideEpisodeSpoilers: Boolean = false,
    val isLoading: Boolean = true,
    val isOffline: Boolean = false,
    val isCheckingConnection: Boolean = false,
    val error: Exception? = null,
)
