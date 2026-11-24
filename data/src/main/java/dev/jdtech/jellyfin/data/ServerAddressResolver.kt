package dev.jdtech.jellyfin.data

import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress

/**
 * Resolves the ordered list of candidate [ServerAddress] endpoints to probe for [server] given the
 * current network transport capabilities.
 */
object ServerAddressResolver {
    fun resolveCandidates(
        server: Server,
        addresses: List<ServerAddress>,
        canReachLocalNetwork: Boolean,
    ): List<ServerAddress> {
        if (addresses.isEmpty()) return emptyList()

        if (!server.autoSwitchEnabled) {
            val pinned =
                addresses.firstOrNull { it.id == server.currentServerAddressId }
                    ?: addresses.first()
            return listOf(pinned)
        }

        val localAddresses = addresses.filter { it.isLocal }.sortedBy { it.priority }
        val externalAddresses = addresses.filter { !it.isLocal }.sortedBy { it.priority }

        return when {
            canReachLocalNetwork -> localAddresses + externalAddresses
            externalAddresses.isNotEmpty() -> externalAddresses
            else -> localAddresses
        }
    }
}
