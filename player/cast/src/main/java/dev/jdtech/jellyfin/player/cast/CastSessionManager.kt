package dev.jdtech.jellyfin.player.cast

import androidx.mediarouter.media.MediaRouter
import dev.jdtech.jellyfin.player.cast.models.CastConnectionState
import dev.jdtech.jellyfin.player.cast.models.Device
import kotlinx.coroutines.flow.StateFlow

interface CastSessionManager {
    val isSupported: Boolean
    val isCastReady: StateFlow<Boolean>
    val isCastAvailable: StateFlow<Boolean>
    val connectionState: StateFlow<CastConnectionState>
    val availableDevices: StateFlow<List<Device>>
    val connectedDevice: StateFlow<Device?>

    fun init()

    fun updateDiscovery(flags: Int = MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)

    fun resolveCastBaseUrl(): String?

    fun connect(device: Device)

    fun disconnect()

    fun release()
}
