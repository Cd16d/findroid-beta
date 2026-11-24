package dev.jdtech.jellyfin.setup.presentation.users

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.cache.UserAvatarStore
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.getProfileImageModel
import dev.jdtech.jellyfin.network.ServerConnectionManager
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUserEntry(
    val user: User,
    val avatarModel: Any?,
    val isActive: Boolean,
)

data class SettingsUsersState(
    val serverId: String = "",
    val serverName: String = "",
    val baseUrl: String = "",
    val savedUsers: List<SettingsUserEntry> = emptyList(),
    val publicUsers: List<SettingsUserEntry> = emptyList(),
    val isOffline: Boolean = false,
    val isLoading: Boolean = true,
)

sealed interface SettingsUsersEvent {
    data object NavigateToHome : SettingsUsersEvent
}

@HiltViewModel
class SettingsUsersViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val setupRepository: SetupRepository,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    private val userAvatarStore: UserAvatarStore,
    private val serverConnectionManager: ServerConnectionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUsersState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<SettingsUsersEvent>(Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()

    private var requestedServerId: String? = null
    private var lastObservedOffline: Boolean? = null

    init {
        viewModelScope.launch {
            serverConnectionManager.state.collect { connState ->
                _state.update { it.copy(isOffline = connState.isOffline) }
                val recovered = lastObservedOffline == true && !connState.isOffline
                lastObservedOffline = connState.isOffline
                if (recovered && _state.value.serverId.isNotEmpty()) {
                    loadUsers(requestedServerId)
                }
            }
        }
    }

    fun loadUsers(serverId: String?) {
        requestedServerId = serverId
        viewModelScope.launch {
            val activeServerId = appPreferences.getValue(appPreferences.currentServer)
            val resolvedServerId = serverId ?: activeServerId ?: return@launch
            val server = database.getServer(resolvedServerId) ?: return@launch
            val isActiveServer = resolvedServerId == activeServerId
            val isOffline = serverConnectionManager.state.value.isOffline
            val baseUrl =
                if (isActiveServer) {
                    setupRepository.getBaseUrl()
                } else {
                    database.getServerCurrentAddress(resolvedServerId)?.address
                        ?: database
                            .getServerAddressesOrdered(resolvedServerId)
                            .firstOrNull()
                            ?.address
                        ?: ""
                }

            val saved = database.getUsers(resolvedServerId)
            val savedIds = saved.map { it.id }.toSet()
            val savedEntries = saved.map { user ->
                val avatar =
                    userAvatarStore.getLocalAvatarFile(user.id, user.primaryImageTag)
                        ?: if (baseUrl.isNotBlank()) user.getProfileImageModel(context, baseUrl)
                        else null
                SettingsUserEntry(
                    user = user,
                    avatarModel = avatar,
                    isActive = isActiveServer && user.id == server.currentUserId,
                )
            }

            _state.value =
                SettingsUsersState(
                    serverId = server.id,
                    serverName = server.name,
                    baseUrl = baseUrl,
                    savedUsers = savedEntries,
                    publicUsers = emptyList(),
                    isOffline = isOffline,
                    isLoading = false,
                )

            if (!isOffline && isActiveServer && baseUrl.isNotBlank()) {
                runCatching {
                    setupRepository
                        .getPublicUsers(resolvedServerId)
                        .filterNot { it.id in savedIds }
                        .map { publicUser ->
                            SettingsUserEntry(
                                user = publicUser,
                                avatarModel = publicUser.getProfileImageModel(context, baseUrl),
                                isActive = false,
                            )
                        }
                }
                    .onSuccess { publicEntries ->
                        _state.update { it.copy(publicUsers = publicEntries) }
                    }
            }
        }
    }

    fun switchToUser(userId: UUID) {
        viewModelScope.launch {
            val targetServerId =
                _state.value.serverId.ifBlank {
                    return@launch
                }
            val activeServerId = appPreferences.getValue(appPreferences.currentServer)
            if (activeServerId != targetServerId) {
                appPreferences.setValue(appPreferences.currentServer, targetServerId)
                setupRepository.setCurrentServer(targetServerId)
            }
            setupRepository.setCurrentUser(userId)
            eventsChannel.send(SettingsUsersEvent.NavigateToHome)
        }
    }

    fun deleteUser(userId: UUID) {
        viewModelScope.launch {
            setupRepository.deleteUser(userId)
            loadUsers(requestedServerId)
        }
    }

    fun prepareServerForLogin(onReady: () -> Unit) {
        viewModelScope.launch {
            val targetServerId =
                _state.value.serverId.ifBlank {
                    return@launch
                }
            val activeServerId = appPreferences.getValue(appPreferences.currentServer)
            if (activeServerId != targetServerId) {
                appPreferences.setValue(appPreferences.currentServer, targetServerId)
                setupRepository.setCurrentServer(targetServerId)
            }
            onReady()
        }
    }
}
