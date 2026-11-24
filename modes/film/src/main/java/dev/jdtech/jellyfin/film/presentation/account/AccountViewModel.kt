package dev.jdtech.jellyfin.film.presentation.account

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.cache.HomeCacheStore
import dev.jdtech.jellyfin.cache.UserAvatarStore
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.getProfileImageModel
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.Downloader
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class AccountViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    val repository: JellyfinRepository,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    private val downloader: Downloader,
    private val userAvatarStore: UserAvatarStore,
    private val homeCacheStore: HomeCacheStore,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<AccountEvent>()
    val events = eventsChannel.receiveAsFlow()

    fun loadData() {
        Timber.i("Loading data")
        viewModelScope.launch(Dispatchers.Default) {
            appPreferences.getValue(appPreferences.currentServer)?.let { serverId ->
                loadUser(serverId)
                val baseUrl = repository.getBaseUrl()
                if (!appPreferences.getValue(appPreferences.offlineMode) && baseUrl.isNotBlank()) {
                    userAvatarStore.syncAllUsersForServer(serverId, baseUrl)
                    loadUser(serverId)
                }
            }
        }
    }

    private suspend fun loadUser(serverId: String) {
        val user = database.getServerCurrentUser(serverId)
        val otherUsers = database.getUsers(serverId).filter { it.id != user?.id }

        val baseUrl = repository.getBaseUrl()

        val imageUrl = user?.let {
            userAvatarStore.getLocalAvatarFile(it.id, it.primaryImageTag)
                ?: it.getProfileImageModel(context, baseUrl)
        }

        _state.update { current ->
            val next =
                current.copy(
                    user = user,
                    userImageUrl = imageUrl,
                    otherUsers = otherUsers,
                    baseUrl = baseUrl,
                )
            if (next == current) current else next
        }
    }

    private fun authorizeQuickConnect(code: String) {
        viewModelScope.launch {
            _state.update { it.copy(isQuickConnectLoading = true, quickConnectSuccess = null) }
            val success = repository.authorizeQuickConnect(code)
            _state.update { it.copy(isQuickConnectLoading = false, quickConnectSuccess = success) }
        }
    }

    private fun switchUser(userId: UUID) {
        viewModelScope.launch {
            appPreferences.getValue(appPreferences.currentServer)?.let { serverId ->
                homeCacheStore.setActiveKey(serverId, userId)
            }
            repository.setCurrentUser(userId)
            val baseUrl = repository.getBaseUrl()
            val imageTag = repository.refreshUser(userId)
            val updatedUser = database.getUser(userId)
            if (baseUrl.isNotBlank()) {
                userAvatarStore.ensureUserAvatar(
                    userId = userId,
                    baseUrl = baseUrl,
                    serverTag = imageTag ?: updatedUser?.primaryImageTag,
                    accessToken = updatedUser?.accessToken,
                )
            } else {
                downloader.downloadUserImage(userId, imageTag)
            }
            eventsChannel.send(AccountEvent.UserSwitched)
        }
    }

    fun onAction(action: AccountAction) {
        when (action) {
            is AccountAction.OnQuickConnectSubmit -> authorizeQuickConnect(action.code)
            AccountAction.ClearQuickConnectStatus ->
                _state.update { it.copy(quickConnectSuccess = null) }
            is AccountAction.SwitchUser -> switchUser(action.userId)
            AccountAction.ToggleAccountList ->
                _state.update { it.copy(isAccountListExpanded = !it.isAccountListExpanded) }
        }
    }
}
