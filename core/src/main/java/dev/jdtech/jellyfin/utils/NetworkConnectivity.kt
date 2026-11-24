package dev.jdtech.jellyfin.utils

import kotlinx.coroutines.flow.Flow

/** Reports whether the device currently has network connectivity. */
interface NetworkConnectivity {
    /** True when there is an active network capable of reaching the internet or local LAN/VPN. */
    fun isOnline(): Boolean

    /** True when the active network transport is Wi-Fi, Ethernet, or VPN. */
    fun canReachLocalNetwork(): Boolean

    /** Emits whenever the default network is gained, lost, or changes transport capabilities. */
    fun observeNetworkChanges(): Flow<Unit>

    /**
     * True when the active network is metered (i.e. lacks
     * [android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED]). Returns false when network
     * capabilities cannot be determined.
     */
    fun isMetered(): Boolean

    /**
     * True when the active network is roaming (i.e. lacks
     * [android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING]). Requires API 28+ which matches
     * minSdk. Returns false when network capabilities cannot be determined.
     */
    fun isRoaming(): Boolean
}
