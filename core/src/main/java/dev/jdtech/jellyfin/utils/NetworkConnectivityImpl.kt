package dev.jdtech.jellyfin.utils

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Connectivity check backed by [ConnectivityManager]. Reports online when there is an active
 * network that advertises internet or local/VPN transport capability.
 */
class NetworkConnectivityImpl @Inject constructor(private val application: Application) :
    NetworkConnectivity {
    override fun isOnline(): Boolean {
        val connectivityManager =
            application.getSystemService(ConnectivityManager::class.java) ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            hasLocalTransport(capabilities)
    }

    override fun canReachLocalNetwork(): Boolean {
        val connectivityManager =
            application.getSystemService(ConnectivityManager::class.java) ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return hasLocalTransport(capabilities)
    }

    override fun observeNetworkChanges(): Flow<Unit> = callbackFlow {
        val connectivityManager =
            application.getSystemService(ConnectivityManager::class.java)
                ?: run {
                    close()
                    return@callbackFlow
                }

        var lastSignature: NetworkSignature? = null
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    lastSignature =
                        connectivityManager.getNetworkCapabilities(network)?.toSignature()
                    trySend(Unit)
                }

                override fun onLost(network: Network) {
                    lastSignature = null
                    trySend(Unit)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    val signature = networkCapabilities.toSignature()
                    if (signature != lastSignature) {
                        lastSignature = signature
                        trySend(Unit)
                    }
                }
            }

        connectivityManager.registerDefaultNetworkCallback(callback)
        awaitClose { runCatching { connectivityManager.unregisterNetworkCallback(callback) } }
    }

    override fun isMetered(): Boolean {
        val connectivityManager =
            application.getSystemService(ConnectivityManager::class.java) ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    override fun isRoaming(): Boolean {
        val connectivityManager =
            application.getSystemService(ConnectivityManager::class.java) ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
    }

    private fun hasLocalTransport(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    private fun NetworkCapabilities.toSignature(): NetworkSignature =
        NetworkSignature(
            hasInternet = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            hasWifi = hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            hasEthernet = hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            hasVpn = hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            hasCellular = hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        )

    private data class NetworkSignature(
        val hasInternet: Boolean,
        val hasWifi: Boolean,
        val hasEthernet: Boolean,
        val hasVpn: Boolean,
        val hasCellular: Boolean,
    )
}
