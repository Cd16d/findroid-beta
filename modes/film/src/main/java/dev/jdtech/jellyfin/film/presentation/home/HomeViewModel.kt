package dev.jdtech.jellyfin.film.presentation.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.cache.CachedHomeView
import dev.jdtech.jellyfin.cache.HomeCacheSnapshot
import dev.jdtech.jellyfin.cache.HomeCacheStore
import dev.jdtech.jellyfin.cache.UserAvatarStore
import dev.jdtech.jellyfin.cache.toCachedHomeItem
import dev.jdtech.jellyfin.cache.toFindroidItem
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.film.R as FilmR
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.HomeSection
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.View
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
 * Implements Stale-While-Revalidate (SWR) with disk caching, double-buffered suggestions across
 * sessions, and background tag-validated user avatar synchronization.
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
    private val homeCacheStore: HomeCacheStore,
    private val userAvatarStore: UserAvatarStore,
) : ViewModel() {
    private val uuidSuggestions = UUID.fromString("31e47044-9b79-4bb0-99d0-0e477ed65420")
    private val uuidContinueWatching = UUID.fromString("44845958-8326-4e83-beb4-c4f42e9eeb95")
    private val uuidNextUp = UUID.fromString("18bfced5-f237-4d42-aa72-d9d7fed19279")

    private val uiTextContinueWatching = UiText.StringResource(FilmR.string.continue_watching)
    private val uiTextNextUp = UiText.StringResource(FilmR.string.next_up)

    private val isOfflineMode = appPreferences.getValue(appPreferences.offlineMode)
    private val initialSnapshot =
        if (!isOfflineMode) homeCacheStore.getPreloadedSnapshot() else null

    private val _state =
        MutableStateFlow(
            cachedHomeState?.copy(isLoading = false)
                ?: initialSnapshot?.toHomeState(isLoading = false)
                ?: HomeState(isLoading = true),
        )
    val state = _state.asStateFlow()

    private var activeUserId: UUID? =
        cachedUserId
            ?: initialSnapshot?.userId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    private var loadDataJob: Job? = null
    private var bufferRefillJob: Job? = null
    private var avatarSyncJob: Job? = null

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
                        database.observeServerCurrentUserId(serverId).distinctUntilChanged().let {
                            userFlow ->
                            userFlow.flatMapLatest { userId -> flowOf(serverId to userId) }
                        }
                    } else {
                        flowOf(null to null)
                    }
                }
                .distinctUntilChanged()
                .collect { (serverId, newUserId) ->
                    if (newUserId != null) {
                        if (activeUserId != null && activeUserId != newUserId) {
                            Timber.i(
                                "Active user changed from %s to %s, reloading home screen",
                                activeUserId,
                                newUserId,
                            )
                            loadDataJob?.cancel()
                            bufferRefillJob?.cancel()
                            avatarSyncJob?.cancel()
                            activeUserId = newUserId
                            cachedHomeState = null
                            cachedUserId = newUserId

                            val cachedForUser =
                                if (!isOfflineMode && serverId != null) {
                                    homeCacheStore.loadSnapshot(serverId, newUserId)
                                } else {
                                    null
                                }
                            if (cachedForUser != null) {
                                _state.value = cachedForUser.toHomeState(isLoading = false)
                                loadData(forceRefreshSuggestions = false)
                            } else {
                                _state.update {
                                    HomeState(
                                        isLoading = true,
                                        hideEpisodeSpoilers = it.hideEpisodeSpoilers,
                                        showLogos = it.showLogos,
                                    )
                                }
                                loadData(forceRefreshSuggestions = true)
                            }
                        } else {
                            activeUserId = newUserId
                        }
                    }
                }
        }
    }

    fun loadData(forceRefreshSuggestions: Boolean = false) {
        Timber.i("Loading data (forceRefresh=%s)", forceRefreshSuggestions)
        loadDataJob?.cancel()
        loadDataJob =
            viewModelScope.launch(Dispatchers.Default) {
                val hideEpisodeSpoilers =
                    appPreferences.getValue(appPreferences.hideEpisodeSpoilers)
                val showLogos = appPreferences.getValue(appPreferences.tvShowLogos)
                val suggestionsEnabled = appPreferences.getValue(appPreferences.homeSuggestions)

                val serverId = appPreferences.getValue(appPreferences.currentServer)
                val server = serverId?.let { database.getServer(it) }
                val user = serverId?.let { database.getServerCurrentUser(it) }
                val targetUserId = user?.id
                if (targetUserId != null) {
                    activeUserId = targetUserId
                }

                // SWR Phase 1: Immediately emit persisted disk cache if state has no sections yet
                if (
                    !forceRefreshSuggestions &&
                        !isOfflineMode &&
                        !_state.value.hasContent() &&
                        serverId != null &&
                        targetUserId != null
                ) {
                    homeCacheStore.loadSnapshot(serverId, targetUserId)?.let { snapshot ->
                        _state.value = snapshot.toHomeState(isLoading = false)
                    }
                }

                val hasVisibleContent = _state.value.hasContent()
                val showLoadingIndicator = forceRefreshSuggestions || !hasVisibleContent

                _state.update {
                    it.copy(
                        isLoading = showLoadingIndicator,
                        error = if (showLoadingIndicator) null else it.error,
                        hideEpisodeSpoilers = hideEpisodeSpoilers,
                        showLogos = showLogos,
                    )
                }

                val baseUrl = repository.getBaseUrl()
                if (user != null) {
                    val immediateAvatar =
                        userAvatarStore.getLocalAvatarFile(user.id, user.primaryImageTag)
                            ?: user.getProfileImageModel(context, baseUrl)
                    _state.update {
                        it.copy(
                            server = server ?: it.server,
                            userName = user.name,
                            userImageUrl = immediateAvatar,
                        )
                    }
                    if (!isOfflineMode && baseUrl.isNotBlank()) {
                        launchBackgroundAvatarSync(
                            serverId = serverId,
                            user = user,
                            baseUrl = baseUrl,
                        )
                    }
                }

                try {
                    val hasCurrentSuggestions =
                        _state.value.suggestionsSection?.items?.isNotEmpty() == true

                    // Keep the Suggestions carousel static for the entire app session (including
                    // pull-to-refresh). It only rotates when closing and reopening the app via
                    // HomeCacheStore's double buffer promotion on startup.
                    var needsForegroundSuggestionsFetch = false
                    var needsBackgroundBufferRefill = false

                    if (suggestionsEnabled && !isOfflineMode) {
                        if (!hasCurrentSuggestions) {
                            // First launch ever (empty cache): fetch current carousel and populate
                            // the next-session buffer in the background.
                            needsForegroundSuggestionsFetch = true
                            needsBackgroundBufferRefill = true
                        } else {
                            val hasBuffer =
                                serverId != null &&
                                    targetUserId != null &&
                                    homeCacheStore.hasBufferedSuggestions(serverId, targetUserId)
                            needsBackgroundBufferRefill = !hasBuffer
                        }
                    }

                    coroutineScope {
                        val suggestionsDeferred =
                            if (needsForegroundSuggestionsFetch) {
                                async { fetchSuggestions(forceRefresh = true) }
                            } else {
                                null
                            }
                        val resumeDeferred = async { fetchResumeItems() }
                        val nextUpDeferred = async { fetchNextUpItems() }
                        val viewsDeferred = async { fetchViews() }

                        val fetchedSuggestions = suggestionsDeferred?.await()
                        val resume = resumeDeferred.await()
                        val nextUp = nextUpDeferred.await()
                        val views = viewsDeferred.await()

                        if (targetUserId != null && activeUserId != targetUserId) {
                            Timber.w(
                                "Discarding stale home screen data for user %s (current active is %s)",
                                targetUserId,
                                activeUserId,
                            )
                            return@coroutineScope
                        }

                        val finalSuggestions: HomeItem.Suggestions? =
                            when {
                                !suggestionsEnabled || isOfflineMode -> null
                                needsForegroundSuggestionsFetch -> fetchedSuggestions
                                else -> _state.value.suggestionsSection
                            }

                        _state.update { current ->
                            current.copy(
                                suggestionsSection =
                                    if (current.suggestionsSection == finalSuggestions) {
                                        current.suggestionsSection
                                    } else {
                                        finalSuggestions
                                    },
                                resumeSection =
                                    if (current.resumeSection == resume) current.resumeSection
                                    else resume,
                                nextUpSection =
                                    if (current.nextUpSection == nextUp) current.nextUpSection
                                    else nextUp,
                                views = if (current.views == views) current.views else views,
                                isLoading = false,
                                error = null,
                            )
                        }
                        cachedHomeState = _state.value
                        cachedUserId = activeUserId

                        if (!isOfflineMode && server != null && user != null) {
                            homeCacheStore.saveSnapshot(
                                server = server,
                                userId = user.id,
                                userName = user.name,
                                userPrimaryImageTag = user.primaryImageTag,
                                currentSuggestions = finalSuggestions?.items.orEmpty(),
                                resumeItems = resume?.homeSection?.items.orEmpty(),
                                nextUpItems = nextUp?.homeSection?.items.orEmpty(),
                                views =
                                    views.map { viewItem ->
                                        CachedHomeView(
                                            id = viewItem.view.id.toString(),
                                            name = viewItem.view.name,
                                            type = viewItem.view.type.type,
                                            items =
                                                viewItem.view.items.map { it.toCachedHomeItem() },
                                        )
                                    },
                            )

                            if (needsBackgroundBufferRefill) {
                                launchBackgroundBufferRefill(
                                    serverId = server.id,
                                    userId = user.id,
                                )
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    Timber.d("Home data loading cancelled")
                    throw e
                } catch (e: Exception) {
                    if (showLoadingIndicator) {
                        Timber.e(e, "Failed to load home data")
                        _state.update { it.copy(error = e, isLoading = false) }
                    } else {
                        // Keep cached Home navigable while surfacing an unobtrusive error/retry
                        // status in the header
                        Timber.w(e, "Background SWR refresh failed; keeping cached home state")
                        _state.update { it.copy(error = e, isLoading = false) }
                    }
                }
            }
    }

    private fun launchBackgroundBufferRefill(serverId: String, userId: UUID) {
        bufferRefillJob?.cancel()
        bufferRefillJob =
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    val items = repository.getSuggestions(forceRefresh = true)
                    if (items.isNotEmpty() && activeUserId == userId) {
                        homeCacheStore.saveBufferedSuggestions(serverId, userId, items)
                    }
                }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        Timber.w(e, "Failed to refill suggestions buffer")
                    }
            }
    }

    private fun launchBackgroundAvatarSync(serverId: String, user: User, baseUrl: String) {
        avatarSyncJob?.cancel()
        avatarSyncJob =
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    userAvatarStore.syncAllUsersForServer(serverId = serverId, baseUrl = baseUrl)
                    val updatedUser = database.getUser(user.id) ?: return@runCatching
                    homeCacheStore.updateUserMetadata(
                        serverId = serverId,
                        userId = updatedUser.id,
                        userName = updatedUser.name,
                        primaryImageTag = updatedUser.primaryImageTag,
                    )
                    if (activeUserId == updatedUser.id) {
                        val syncedModel =
                            userAvatarStore.getLocalAvatarFile(
                                updatedUser.id,
                                updatedUser.primaryImageTag,
                            ) ?: updatedUser.getProfileImageModel(context, baseUrl)
                        _state.update { current ->
                            if (
                                current.userName == updatedUser.name &&
                                    current.userImageUrl == syncedModel
                            ) {
                                current
                            } else {
                                current.copy(
                                    userName = updatedUser.name,
                                    userImageUrl = syncedModel,
                                )
                            }
                        }
                        cachedHomeState = _state.value
                    }
                }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        Timber.w(e, "Failed background avatar sync")
                    }
            }
    }

    private fun HomeCacheSnapshot.toHomeState(isLoading: Boolean = false): HomeState {
        val parsedUserId = runCatching { UUID.fromString(userId) }.getOrNull()
        val showSuggestions = appPreferences.getValue(appPreferences.homeSuggestions)
        val showContinue = appPreferences.getValue(appPreferences.homeContinueWatching)
        val showNextUp = appPreferences.getValue(appPreferences.homeNextUp)
        val showLatest = appPreferences.getValue(appPreferences.homeLatest)
        val hideSpoilers = appPreferences.getValue(appPreferences.hideEpisodeSpoilers)
        val showLogos = appPreferences.getValue(appPreferences.tvShowLogos)

        val suggestions =
            if (showSuggestions) {
                currentSuggestions
                    .mapNotNull { it.toFindroidItem() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { HomeItem.Suggestions(id = uuidSuggestions, items = it) }
            } else {
                null
            }

        val resume =
            if (showContinue) {
                resumeItems
                    .mapNotNull { it.toFindroidItem() }
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        HomeItem.Section(
                            HomeSection(uuidContinueWatching, uiTextContinueWatching, it),
                        )
                    }
            } else {
                null
            }

        val nextUp =
            if (showNextUp) {
                nextUpItems
                    .mapNotNull { it.toFindroidItem() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { HomeItem.Section(HomeSection(uuidNextUp, uiTextNextUp, it)) }
            } else {
                null
            }

        val viewItems =
            if (showLatest) {
                views
                    .mapNotNull { cachedView ->
                        val parsedViewId =
                            runCatching { UUID.fromString(cachedView.id) }.getOrNull()
                                ?: return@mapNotNull null
                        val items = cachedView.items.mapNotNull { it.toFindroidItem() }
                        if (items.isEmpty()) return@mapNotNull null
                        View(
                            id = parsedViewId,
                            name = cachedView.name,
                            items = items,
                            type = CollectionType.fromString(cachedView.type),
                        )
                    }
                    .map { HomeItem.ViewItem(it) }
            } else {
                emptyList()
            }

        val avatarModel: Any? = parsedUserId?.let { uid ->
            userAvatarStore.getLocalAvatarFile(uid, userPrimaryImageTag)
        }

        return HomeState(
            server =
                Server(
                    id = serverId,
                    name = serverName,
                    currentServerAddressId = null,
                    currentUserId = parsedUserId,
                ),
            userName = userName,
            userImageUrl = avatarModel,
            suggestionsSection = suggestions,
            resumeSection = resume,
            nextUpSection = nextUp,
            views = viewItems,
            showLogos = showLogos,
            hideEpisodeSpoilers = hideSpoilers,
            isLoading = isLoading,
            error = null,
        )
    }

    private fun HomeState.hasContent(): Boolean =
        suggestionsSection != null ||
            resumeSection != null ||
            nextUpSection != null ||
            views.isNotEmpty()

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
                _state.update { it.copy(isLoading = true, error = null) }
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
