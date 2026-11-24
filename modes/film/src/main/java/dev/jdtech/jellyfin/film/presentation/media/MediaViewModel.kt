package dev.jdtech.jellyfin.film.presentation.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.network.ServerConnectionManager
import dev.jdtech.jellyfin.repository.JellyfinRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class MediaViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val serverConnectionManager: ServerConnectionManager,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            MediaState(
                isOffline = serverConnectionManager.state.value.isOffline,
                isCheckingConnection = serverConnectionManager.state.value.isChecking,
            ),
        )
    val state = _state.asStateFlow()

    private var lastOffline: Boolean? = null

    init {
        viewModelScope.launch {
            serverConnectionManager.state.collect { connState ->
                _state.update {
                    it.copy(
                        isOffline = connState.isOffline,
                        isCheckingConnection = connState.isChecking,
                    )
                }
                val becameOnline = lastOffline == true && !connState.isOffline
                lastOffline = connState.isOffline
                if (becameOnline) {
                    loadData()
                }
            }
        }
    }

    fun loadData() {
        viewModelScope.launch {
            if (serverConnectionManager.state.value.isOffline) {
                _state.update { it.copy(isLoading = false, error = null) }
                return@launch
            }
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val libraries = repository.getLibraries()
                _state.update { it.copy(libraries = libraries, isLoading = false) }
            } catch (e: Exception) {
                serverConnectionManager.onNetworkRequestFailure()
                _state.update { it.copy(error = e, isLoading = false) }
            }
        }
    }

    fun onAction(action: MediaAction) {
        when (action) {
            is MediaAction.OnRetryClick -> {
                viewModelScope.launch {
                    if (serverConnectionManager.state.value.isOffline) {
                        serverConnectionManager.probeNow()
                    }
                    loadData()
                }
            }
            else -> Unit
        }
    }
}
