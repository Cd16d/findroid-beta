package dev.jdtech.jellyfin.film.presentation.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.film.R as FilmR
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.HomeSection
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.getProfileImageModel
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.toView
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * ViewModel responsible for loading, mapping, and state management of the home screen layout.
 * Fetches media sections (suggestions, continue watching, next up, and library views) concurrently
 * based on user preferences and Jellyfin server capabilities.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    val repository: JellyfinRepository,
    val appPreferences: AppPreferences,
    val database: ServerDatabaseDao,
) : ViewModel() {
    private val _state =
        MutableStateFlow(cachedHomeState?.copy(isLoading = true) ?: HomeState(isLoading = true))
    val state = _state.asStateFlow()

    private var activeUserId: UUID? = cachedUserId
    private var loadDataJob: Job? = null

    private val uuidSuggestions = UUID.fromString("31e47044-9b79-4bb0-99d0-0e477ed65420")
    private val uuidContinueWatching = UUID.fromString("44845958-8326-4e83-beb4-c4f42e9eeb95")
    private val uuidNextUp = UUID.fromString("18bfced5-f237-4d42-aa72-d9d7fed19279")

    private val uiTextContinueWatching = UiText.StringResource(FilmR.string.continue_watching)
    private val uiTextNextUp = UiText.StringResource(FilmR.string.next_up)

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
        viewModelScope.launch {
            appPreferences
                .observe(appPreferences.currentServer)
                .flatMapLatest { serverId ->
                    if (serverId != null) {
                        database.observeServerCurrentUserId(serverId)
                    } else {
                        flowOf(null)
                    }
                }
                .distinctUntilChanged()
                .collect { newUserId ->
                    if (newUserId != null) {
                        if (activeUserId != null && activeUserId != newUserId) {
                            Timber.i(
                                "Active user changed from %s to %s, reloading home screen",
                                activeUserId,
                                newUserId,
                            )
                            loadDataJob?.cancel()
                            activeUserId = newUserId
                            cachedHomeState = null
                            cachedUserId = newUserId
                            _state.update {
                                HomeState(
                                    isLoading = true,
                                    hideEpisodeSpoilers = it.hideEpisodeSpoilers,
                                    showLogos = it.showLogos,
                                )
                            }
                            loadData(forceRefreshSuggestions = true)
                        } else {
                            activeUserId = newUserId
                        }
                    }
                }
        }
    }

    fun loadData(forceRefreshSuggestions: Boolean = false) {
        Timber.i("Loading data")
        loadDataJob?.cancel()
        loadDataJob =
            viewModelScope.launch(Dispatchers.Default) {
                val hideEpisodeSpoilers =
                    appPreferences.getValue(appPreferences.hideEpisodeSpoilers)
                val showLogos = appPreferences.getValue(appPreferences.tvShowLogos)
                _state.update {
                    it.copy(
                        isLoading = true,
                        error = null,
                        hideEpisodeSpoilers = hideEpisodeSpoilers,
                        showLogos = showLogos,
                    )
                }
                try {
                    var targetUserId: UUID? = null
                    appPreferences.getValue(appPreferences.currentServer)?.let { serverId ->
                        val server = database.getServer(serverId)
                        val user = database.getServerCurrentUser(serverId)
                        targetUserId = user?.id
                        activeUserId = user?.id
                        val baseUrl = repository.getBaseUrl()
                        val imageUrl = user?.getProfileImageModel(context, baseUrl)
                        _state.update {
                            it.copy(
                                server = server ?: it.server,
                                userName = user?.name ?: "",
                                userImageUrl = imageUrl,
                            )
                        }
                    }

                    coroutineScope {
                        // Concurrently fetch each configured home screen section
                        val suggestionsDeferred = async {
                            fetchSuggestions(forceRefreshSuggestions)
                        }
                        val resumeDeferred = async { fetchResumeItems() }
                        val nextUpDeferred = async { fetchNextUpItems() }
                        val viewsDeferred = async { fetchViews() }

                        val suggestions = suggestionsDeferred.await()
                        val resume = resumeDeferred.await()
                        val nextUp = nextUpDeferred.await()
                        val views = viewsDeferred.await()

                        // Ensure this load job still matches the current active user before
                        // applying state
                        if (targetUserId != null && activeUserId != targetUserId) {
                            Timber.w(
                                "Discarding stale home screen data for user %s (current active is %s)",
                                targetUserId,
                                activeUserId,
                            )
                            return@coroutineScope
                        }

                        // Map fetched section items into the unified HomeState
                        _state.update {
                            it.copy(
                                suggestionsSection = suggestions,
                                resumeSection = resume,
                                nextUpSection = nextUp,
                                views = views,
                                isLoading = false,
                                error = null,
                            )
                        }
                        cachedHomeState = _state.value
                        cachedUserId = activeUserId
                    }
                } catch (e: CancellationException) {
                    Timber.d("Home data loading cancelled")
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to load home data")
                    _state.update { it.copy(error = e, isLoading = false) }
                }
            }
    }

    private suspend fun fetchSuggestions(forceRefresh: Boolean = false): HomeItem.Suggestions? {
        Timber.i("Fetching suggestions")
        if (!appPreferences.getValue(appPreferences.homeSuggestions)) {
            return null
        }

        val items = repository.getSuggestions(forceRefresh)

        return if (items.isEmpty()) {
            null
        } else {
            HomeItem.Suggestions(id = uuidSuggestions, items = items)
        }
    }

    private suspend fun fetchResumeItems(): HomeItem.Section? {
        Timber.i("Fetching resume items")
        if (!appPreferences.getValue(appPreferences.homeContinueWatching)) {
            return null
        }

        val resumeItems = repository.getResumeItems()

        return if (resumeItems.isEmpty()) {
            null
        } else {
            HomeItem.Section(HomeSection(uuidContinueWatching, uiTextContinueWatching, resumeItems))
        }
    }

    private suspend fun fetchNextUpItems(): HomeItem.Section? {
        Timber.i("Fetching next up items")
        if (!appPreferences.getValue(appPreferences.homeNextUp)) {
            return null
        }

        val nextUpItems = repository.getNextUp()

        return if (nextUpItems.isEmpty()) {
            null
        } else {
            HomeItem.Section(HomeSection(uuidNextUp, uiTextNextUp, nextUpItems))
        }
    }

    private suspend fun fetchViews(): List<HomeItem.ViewItem> {
        Timber.i("Fetching views")
        if (!appPreferences.getValue(appPreferences.homeLatest)) {
            return emptyList()
        }
        // Filter user library views down to only supported collection types (e.g. movies, tv shows)
        val supportedViews =
            repository.getUserViews().filter { view ->
                CollectionType.fromString(view.collectionType?.serialName) in
                    CollectionType.supported
            }
        // Concurrently fetch latest media items for each supported library view
        return coroutineScope {
            supportedViews
                .map { view ->
                    async {
                        val latest = repository.getLatestMedia(view.id)
                        view to latest
                    }
                }
                .awaitAll()
        }
        // Exclude empty view categories and map remaining views to HomeItem.ViewItem wrappers
        .filter { (_, latest) -> latest.isNotEmpty() }
            .map { (view, latest) -> HomeItem.ViewItem(view.toView(latest)) }
    }

    fun onAction(action: HomeAction) {
        when (action) {
            is HomeAction.OnRetryClick,
            is HomeAction.Refresh -> {
                cachedHomeState = null
                loadData(forceRefreshSuggestions = true)
            }
            else -> Unit
        }
    }

    suspend fun getShow(showId: UUID): FindroidShow = repository.getShow(showId)

    companion object {
        private var cachedHomeState: HomeState? = null
        private var cachedUserId: UUID? = null

        fun clearCache() {
            cachedHomeState = null
            cachedUserId = null
        }
    }
}
