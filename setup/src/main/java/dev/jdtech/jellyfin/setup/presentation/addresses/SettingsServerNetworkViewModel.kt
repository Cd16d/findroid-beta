package dev.jdtech.jellyfin.setup.presentation.addresses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.network.EndpointCheckStatus
import dev.jdtech.jellyfin.network.ServerConnectionManager
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.setup.R as SetupR
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SettingsServerNetworkState(
    val serverId: String = "",
    val serverName: String = "",
    val autoSwitchEnabled: Boolean = true,
    val isActiveServer: Boolean = false,
    val isOffline: Boolean = false,
    val isCheckingConnection: Boolean = false,
    val activeAddressUrl: String? = null,
    val isActiveAddressLocal: Boolean = false,
    val currentAddressId: UUID? = null,
    val localAddress: ServerAddress? = null,
    val externalAddresses: List<ServerAddress> = emptyList(),
    val statuses: Map<UUID, EndpointCheckStatus> = emptyMap(),
    val isLoading: Boolean = true,
)

sealed interface SettingsServerNetworkAction {
    data class ToggleAutoSwitch(val enabled: Boolean) : SettingsServerNetworkAction

    data class SaveLocalEndpoint(val url: String) : SettingsServerNetworkAction

    data object ClearLocalEndpoint : SettingsServerNetworkAction

    data object UseCurrentAddressAsLocal : SettingsServerNetworkAction

    data class AddExternalEndpoint(val url: String) : SettingsServerNetworkAction

    data class EditExternalEndpoint(val addressId: UUID, val url: String) :
        SettingsServerNetworkAction

    data class DeleteExternalEndpoint(val addressId: UUID) : SettingsServerNetworkAction

    data class ReorderExternalEndpoints(val fromIndex: Int, val toIndex: Int) :
        SettingsServerNetworkAction

    data class SelectManualEndpoint(val addressId: UUID) : SettingsServerNetworkAction

    data object RefreshAll : SettingsServerNetworkAction

    data class ProbeSingle(val addressId: UUID) : SettingsServerNetworkAction
}

@HiltViewModel
class SettingsServerNetworkViewModel
@Inject
constructor(
    private val database: ServerDatabaseDao,
    private val jellyfinApi: JellyfinApi,
    private val appPreferences: AppPreferences,
    private val serverConnectionManager: ServerConnectionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsServerNetworkState())
    val state = _state.asStateFlow()

    private val snackbarChannel = Channel<UiText>(Channel.BUFFERED)
    val snackbarMessages = snackbarChannel.receiveAsFlow()

    private var targetServerId: String = ""
    private var observationJob: Job? = null
    private var initialProbeTriggered = false
    private val reorderMutex = Mutex()

    fun load(serverId: String) {
        if (targetServerId == serverId && observationJob != null) return
        targetServerId = serverId
        initialProbeTriggered = false
        observationJob?.cancel()

        observationJob = viewModelScope.launch {
            combine(
                    database.observeServer(serverId),
                    database.observeServerAddresses(serverId),
                    appPreferences.observe(appPreferences.currentServer),
                    serverConnectionManager.state,
                ) { server, addresses, activeServerId, connState ->
                    val localAddr = addresses.firstOrNull { it.isLocal }
                    val externalAddrs = addresses.filter { !it.isLocal }.sortedBy { it.priority }
                    val isActiveServer = serverId == activeServerId
                    val currentAddr =
                        addresses.firstOrNull { it.id == server?.currentServerAddressId }
                            ?: addresses.firstOrNull()

                    SettingsServerNetworkState(
                        serverId = serverId,
                        serverName = server?.name.orEmpty(),
                        autoSwitchEnabled = server?.autoSwitchEnabled ?: true,
                        isActiveServer = isActiveServer,
                        isOffline = if (isActiveServer) connState.isOffline else false,
                        isCheckingConnection = if (isActiveServer) connState.isChecking else false,
                        activeAddressUrl =
                            if (isActiveServer) {
                                if (connState.isOffline) null
                                else connState.activeAddressUrl ?: currentAddr?.address
                            } else {
                                currentAddr?.address
                            },
                        isActiveAddressLocal =
                            if (isActiveServer) connState.isActiveAddressLocal
                            else currentAddr?.isLocal == true,
                        currentAddressId =
                            if (isActiveServer && !connState.isOffline) {
                                connState.activeAddressId ?: server?.currentServerAddressId
                            } else {
                                server?.currentServerAddressId
                            },
                        localAddress = localAddr,
                        externalAddresses = externalAddrs,
                        statuses = _state.value.statuses,
                        isLoading = false,
                    )
                }
                .collect { nextState ->
                    _state.value = nextState
                    if (!initialProbeTriggered) {
                        initialProbeTriggered = true
                        probeAllEndpoints()
                    }
                }
        }
    }

    suspend fun testEndpointUrl(url: String): Boolean {
        val serverId = targetServerId.ifBlank { null }
        return serverConnectionManager.probeEndpoint(url, serverId)
    }

    fun onAction(action: SettingsServerNetworkAction) {
        when (action) {
            is SettingsServerNetworkAction.ToggleAutoSwitch -> toggleAutoSwitch(action.enabled)
            is SettingsServerNetworkAction.SaveLocalEndpoint -> saveLocalEndpoint(action.url)
            is SettingsServerNetworkAction.ClearLocalEndpoint -> clearLocalEndpoint()
            is SettingsServerNetworkAction.UseCurrentAddressAsLocal -> useCurrentAddressAsLocal()
            is SettingsServerNetworkAction.AddExternalEndpoint -> addExternalEndpoint(action.url)
            is SettingsServerNetworkAction.EditExternalEndpoint ->
                editExternalEndpoint(action.addressId, action.url)
            is SettingsServerNetworkAction.DeleteExternalEndpoint ->
                deleteExternalEndpoint(action.addressId)
            is SettingsServerNetworkAction.ReorderExternalEndpoints ->
                reorderExternalEndpoints(action.fromIndex, action.toIndex)
            is SettingsServerNetworkAction.SelectManualEndpoint ->
                selectManualEndpoint(action.addressId)
            is SettingsServerNetworkAction.RefreshAll -> refreshAll()
            is SettingsServerNetworkAction.ProbeSingle -> probeSingle(action.addressId)
        }
    }

    private fun toggleAutoSwitch(enabled: Boolean) {
        val serverId = targetServerId.ifBlank {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            database.updateServerAutoSwitch(serverId, enabled)
            serverConnectionManager.notifyConfigChanged(serverId)
        }
    }

    private fun saveLocalEndpoint(rawUrl: String) {
        val serverId = targetServerId.ifBlank {
            return
        }
        val normalized = normalizeUrl(rawUrl)
        if (normalized.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val saved = database.setLocalServerAddress(serverId, normalized)
            serverConnectionManager.notifyConfigChanged(serverId)
            probeAddress(saved)
        }
    }

    private fun clearLocalEndpoint() {
        val serverId = targetServerId.ifBlank {
            return
        }
        val local = _state.value.localAddress ?: return
        val external = _state.value.externalAddresses

        viewModelScope.launch(Dispatchers.IO) {
            if (external.isEmpty()) {
                // Keep at least one address configured on the server by demoting it to external
                database.updateServerAddress(local.copy(isLocal = false, priority = 0))
            } else {
                val server = database.getServer(serverId)
                if (server?.currentServerAddressId == local.id) {
                    database.updateServerCurrentAddress(serverId, external.first().id)
                }
                database.deleteServerAddress(local.id)
            }
            serverConnectionManager.notifyConfigChanged(serverId)
        }
    }

    private fun useCurrentAddressAsLocal() {
        val serverId = targetServerId.ifBlank {
            return
        }
        val currentUrl =
            _state.value.activeAddressUrl
                ?: _state.value.localAddress?.address
                ?: _state.value.externalAddresses.firstOrNull()?.address
                ?: return
        saveLocalEndpoint(currentUrl)
    }

    private fun addExternalEndpoint(rawUrl: String) {
        val serverId = targetServerId.ifBlank {
            return
        }
        val normalized = normalizeUrl(rawUrl)
        if (normalized.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val existing = database.getServerAddressesOrdered(serverId)
            val duplicate = existing.firstOrNull {
                it.address.equals(normalized, ignoreCase = true)
            }
            if (duplicate != null) {
                if (duplicate.isLocal) {
                    val nextPriority =
                        (existing.filter { !it.isLocal }.maxOfOrNull { it.priority } ?: -1) + 1
                    database.updateServerAddress(
                        duplicate.copy(isLocal = false, priority = nextPriority),
                    )
                }
                serverConnectionManager.notifyConfigChanged(serverId)
                probeAddress(duplicate)
                return@launch
            }

            val nextPriority =
                (existing.filter { !it.isLocal }.maxOfOrNull { it.priority } ?: -1) + 1
            val created =
                ServerAddress(
                    id = UUID.randomUUID(),
                    serverId = serverId,
                    address = normalized,
                    isLocal = false,
                    priority = nextPriority,
                )
            database.insertServerAddress(created)
            serverConnectionManager.notifyConfigChanged(serverId)
            probeAddress(created)
        }
    }

    private fun editExternalEndpoint(addressId: UUID, rawUrl: String) {
        val serverId = targetServerId.ifBlank {
            return
        }
        val normalized = normalizeUrl(rawUrl)
        if (normalized.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val existing =
                database.getServerAddressesOrdered(serverId).firstOrNull { it.id == addressId }
                    ?: return@launch
            val updated = existing.copy(address = normalized)
            database.updateServerAddress(updated)
            serverConnectionManager.notifyConfigChanged(serverId)
            probeAddress(updated)
        }
    }

    private fun deleteExternalEndpoint(addressId: UUID) {
        val serverId = targetServerId.ifBlank {
            return
        }
        val currentState = _state.value
        val totalCount =
            (if (currentState.localAddress != null) 1 else 0) + currentState.externalAddresses.size

        if (totalCount <= 1) {
            viewModelScope.launch {
                snackbarChannel.send(
                    UiText.StringResource(SetupR.string.cannot_delete_last_address),
                )
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val all = database.getServerAddressesOrdered(serverId)
            val remaining = all.filter { it.id != addressId }
            val server = database.getServer(serverId)
            if (server?.currentServerAddressId == addressId && remaining.isNotEmpty()) {
                database.updateServerCurrentAddress(serverId, remaining.first().id)
            }
            database.deleteServerAddress(addressId)
            val reindexed =
                remaining
                    .filter { !it.isLocal }
                    .mapIndexed { index, addr -> addr.copy(priority = index) }
            if (reindexed.isNotEmpty()) {
                database.updateServerAddresses(reindexed)
            }
            serverConnectionManager.notifyConfigChanged(serverId)
        }
    }

    private fun reorderExternalEndpoints(fromIndex: Int, toIndex: Int) {
        val serverId = targetServerId.ifBlank {
            return
        }
        val currentList = _state.value.externalAddresses
        if (
            fromIndex !in currentList.indices ||
                toIndex !in currentList.indices ||
                fromIndex == toIndex
        ) {
            return
        }

        val mutable = currentList.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        val reindexed = mutable.mapIndexed { idx, item -> item.copy(priority = idx) }
        _state.update { it.copy(externalAddresses = reindexed) }

        val orderedIds = reindexed.map { it.id }
        viewModelScope.launch(Dispatchers.IO) {
            reorderMutex.withLock {
                database.reorderExternalAddresses(serverId, orderedIds)
                serverConnectionManager.notifyConfigChanged(serverId)
            }
        }
    }

    private fun selectManualEndpoint(addressId: UUID) {
        val serverId = targetServerId.ifBlank {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val address =
                database.getServerAddressesOrdered(serverId).firstOrNull { it.id == addressId }
                    ?: return@launch
            database.updateServerCurrentAddress(serverId, address.id)
            if (_state.value.isActiveServer) {
                jellyfinApi.api.update(baseUrl = address.address)
            }
            serverConnectionManager.notifyConfigChanged(serverId)
        }
    }

    private fun refreshAll() {
        viewModelScope.launch {
            if (_state.value.isActiveServer) {
                launch { serverConnectionManager.probeNow() }
            }
            probeAllEndpoints()
        }
    }

    private fun probeSingle(addressId: UUID) {
        val target =
            (_state.value.localAddress?.takeIf { it.id == addressId }
                ?: _state.value.externalAddresses.firstOrNull { it.id == addressId }) ?: return
        viewModelScope.launch { probeAddress(target) }
    }

    private fun probeAllEndpoints() {
        val current = _state.value
        val all = listOfNotNull(current.localAddress) + current.externalAddresses
        if (all.isEmpty()) return

        viewModelScope.launch {
            _state.update { state ->
                state.copy(
                    statuses =
                        state.statuses + all.associate { it.id to EndpointCheckStatus.CHECKING },
                )
            }
            coroutineScope { all.map { addr -> async { probeAddress(addr) } }.awaitAll() }
        }
    }

    private suspend fun probeAddress(address: ServerAddress) {
        _state.update {
            it.copy(statuses = it.statuses + (address.id to EndpointCheckStatus.CHECKING))
        }
        val reachable =
            serverConnectionManager.probeEndpoint(
                url = address.address,
                expectedServerId = targetServerId.ifBlank { null },
            )
        val status =
            if (reachable) EndpointCheckStatus.REACHABLE else EndpointCheckStatus.UNREACHABLE
        _state.update { it.copy(statuses = it.statuses + (address.id to status)) }
    }

    private fun normalizeUrl(raw: String): String = raw.trim().trimEnd('/')
}
