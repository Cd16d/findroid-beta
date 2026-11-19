package dev.jdtech.jellyfin.film.presentation.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.getProfileImageModel
import dev.jdtech.jellyfin.repository.HomeRepository
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.Downloader
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * ViewModel responsible for loading, mapping, and state management of the home screen layout.
 * Observes cached home sections reactively via [HomeRepository] (Room database) for immediate
 * cold-start display (<50ms) and coordinates background revalidation (Stale-While-Revalidate).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    val repository: JellyfinRepository,
    val homeRepository: HomeRepository,
    val appPreferences: AppPreferences,
    val database: ServerDatabaseDao,
    private val downloader: Downloader,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeState(isLoading = true))
    val state = _state.asStateFlow()

    private var activeUserId: UUID? = null
    private var activeServerId: String? = null
    private var loadDataJob: Job? = null

    init {
        viewModelScope.launch {
            appPreferences.observe(appPreferences.hideEpisodeSpoilers).collect { hideSpoilers ->
                _state.update { it.copy(hideEpisodeSpoilers = hideSpoilers) }
            }
        }
        viewModelScope.launch {
            appPreferences.observe(appPreferences.tvShowLogos).collect { showLogos ->
                _state.update { it.copy(showLogos = showLogos) }
            }
        }

        // Reactively observe current server and user, streaming cached Room home data
        viewModelScope.launch {
            appPreferences
                .observe(appPreferences.currentServer)
                .flatMapLatest { serverId ->
                    if (serverId != null) {
                        database.observeServerCurrentUserId(serverId).combine(flowOf(serverId)) {
                            userId,
                            sId ->
                            sId to userId
                        }
                    } else {
                        flowOf(null to null)
                    }
                }
                .distinctUntilChanged()
                .flatMapLatest { (serverId, userId) ->
                    activeServerId = serverId
                    activeUserId = userId
                    if (serverId != null && userId != null) {
                        homeRepository.observeHomeData(serverId, userId)
                    } else {
                        flowOf(null)
                    }
                }
                .collect { homeData ->
                    if (homeData != null) {
                        _state.update { current ->
                            current.copy(
                                suggestionsSection = homeData.suggestions,
                                resumeSection = homeData.resume,
                                nextUpSection = homeData.nextUp,
                                views = homeData.views,
                                isLoading =
                                    if (homeData.isEmpty && current.isLoading) true else false,
                            )
                        }
                    }
                }
        }
    }

    fun loadData(forceRefreshSuggestions: Boolean = false) {
        Timber.i("Loading data (forceRefreshSuggestions = %b)", forceRefreshSuggestions)
        loadDataJob?.cancel()
        loadDataJob =
            viewModelScope.launch(Dispatchers.Default) {
                val hideEpisodeSpoilers =
                    appPreferences.getValue(appPreferences.hideEpisodeSpoilers)
                val showLogos = appPreferences.getValue(appPreferences.tvShowLogos)
                _state.update {
                    it.copy(
                        hideEpisodeSpoilers = hideEpisodeSpoilers,
                        showLogos = showLogos,
                    )
                }

                val serverId =
                    appPreferences.getValue(appPreferences.currentServer) ?: return@launch
                val server = database.getServer(serverId)
                val user = database.getServerCurrentUser(serverId) ?: return@launch
                activeUserId = user.id
                activeServerId = serverId

                val baseUrl = repository.getBaseUrl()
                val imageUrl = user.getProfileImageModel(context, baseUrl)
                _state.update {
                    it.copy(
                        server = server ?: it.server,
                        userName = user.name,
                        userImageUrl = imageUrl,
                    )
                }

                // Cache user profile image to internal storage if not already present
                if (user.primaryImageTag != null) {
                    val fileName = "primary_${user.primaryImageTag}"
                    val localFile = File(context.filesDir, "images/users/${user.id}/$fileName")
                    if (!localFile.exists()) {
                        downloader.downloadUserImage(user.id, user.primaryImageTag)
                    }
                }

                // Background revalidation (Stale-While-Revalidate)
                val refreshResult =
                    homeRepository.refreshHome(serverId, user.id, forceRefreshSuggestions)
                refreshResult.fold(
                    onSuccess = { _state.update { it.copy(isLoading = false, error = null) } },
                    onFailure = { error ->
                        Timber.w(error, "Background home revalidation failed")
                        _state.update { current ->
                            current.copy(
                                isLoading = false,
                                error = if (error is Exception) error else Exception(error),
                            )
                        }
                    },
                )
            }
    }

    fun onAction(action: HomeAction) {
        when (action) {
            is HomeAction.OnRetryClick,
            is HomeAction.Refresh -> {
                loadData(forceRefreshSuggestions = true)
            }
            else -> Unit
        }
    }

    suspend fun getShow(showId: UUID): FindroidShow = repository.getShow(showId)

    companion object {
        fun clearCache() {
            // Room database serves as persistent cache
        }
    }
}
