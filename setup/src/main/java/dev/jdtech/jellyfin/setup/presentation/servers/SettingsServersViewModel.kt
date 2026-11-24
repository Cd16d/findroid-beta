package dev.jdtech.jellyfin.setup.presentation.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.network.ServerConnectionManager
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class SettingsServerEntry(
    val server: Server,
    val addresses: List<ServerAddress>,
    val users: List<User>,
    val activeUser: User?,
    val isActiveServer: Boolean,
    val isOffline: Boolean,
    val activeUrl: String?,
)

data class SettingsServersState(
    val servers: List<SettingsServerEntry> = emptyList(),
    val isLoading: Boolean = true,
)

sealed interface SettingsServersAction {
    data class SwitchServer(val serverId: String) : SettingsServersAction

    data class DeleteServer(val serverId: String) : SettingsServersAction
}

sealed interface SettingsServersEvent {
    data object ServerActivated : SettingsServersEvent

    data class OpenUsers(val serverId: String) : SettingsServersEvent
}

@HiltViewModel
class SettingsServersViewModel
@Inject
constructor(
    private val database: ServerDatabaseDao,
    private val setupRepository: SetupRepository,
    private val appPreferences: AppPreferences,
    private val serverConnectionManager: ServerConnectionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsServersState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<SettingsServersEvent>(Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(
                    appPreferences.observe(appPreferences.currentServer),
                    serverConnectionManager.state,
                ) { currentServerId, connState ->
                    currentServerId to connState
                }
                .collect { (currentServerId, connState) ->
                    loadServersInternal(
                        currentServerId,
                        connState.isOffline,
                        connState.activeAddressUrl,
                    )
                }
        }
    }

    fun loadServers() {
        viewModelScope.launch {
            val currentServerId = appPreferences.getValue(appPreferences.currentServer)
            val connState = serverConnectionManager.state.value
            loadServersInternal(currentServerId, connState.isOffline, connState.activeAddressUrl)
        }
    }

    private suspend fun loadServersInternal(
        currentServerId: String?,
        connectionOffline: Boolean,
        connectionActiveUrl: String?,
    ) {
        val serversWithAddresses = database.getServersWithAddresses()
        val entries = serversWithAddresses.map { swa ->
            val server = swa.server
            val orderedAddresses = database.getServerAddressesOrdered(server.id)
            val users = database.getUsers(server.id)
            val activeUser = users.firstOrNull { it.id == server.currentUserId }
            val isActive = server.id == currentServerId
            val fallbackUrl =
                orderedAddresses.firstOrNull { it.id == server.currentServerAddressId }?.address
                    ?: orderedAddresses.firstOrNull()?.address
            SettingsServerEntry(
                server = server,
                addresses = orderedAddresses,
                users = users,
                activeUser = activeUser,
                isActiveServer = isActive,
                isOffline = isActive && connectionOffline,
                activeUrl =
                    if (isActive && !connectionOffline) connectionActiveUrl ?: fallbackUrl
                    else fallbackUrl,
            )
        }
        _state.value = SettingsServersState(servers = entries, isLoading = false)
    }

    private fun switchServer(serverId: String) {
        viewModelScope.launch {
            val server = database.getServer(serverId) ?: return@launch
            appPreferences.setValue(appPreferences.currentServer, serverId)
            setupRepository.setCurrentServer(serverId)
            if (server.currentUserId != null) {
                eventsChannel.send(SettingsServersEvent.ServerActivated)
            } else {
                eventsChannel.send(SettingsServersEvent.OpenUsers(serverId))
            }
        }
    }

    private fun deleteServer(serverId: String) {
        viewModelScope.launch {
            val currentServerId = appPreferences.getValue(appPreferences.currentServer)
            setupRepository.deleteServer(serverId)
            if (currentServerId == serverId) {
                val nextServer = database.getServers().firstOrNull()
                if (nextServer != null) {
                    appPreferences.setValue(appPreferences.currentServer, nextServer.id)
                    setupRepository.setCurrentServer(nextServer.id)
                } else {
                    appPreferences.setValue(appPreferences.currentServer, null)
                }
            }
            loadServers()
        }
    }

    fun onAction(action: SettingsServersAction) {
        when (action) {
            is SettingsServersAction.SwitchServer -> switchServer(action.serverId)
            is SettingsServersAction.DeleteServer -> deleteServer(action.serverId)
        }
    }
}
