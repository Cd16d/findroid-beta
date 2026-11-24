package dev.jdtech.jellyfin.network

import android.content.Context
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.data.ServerAddressResolver
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.di.ApplicationScope
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.NetworkConnectivity
import dev.jdtech.jellyfin.work.SyncWorker
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

enum class EndpointCheckStatus {
    UNKNOWN,
    CHECKING,
    REACHABLE,
    UNREACHABLE,
}

data class ServerConnectionState(
    val isOffline: Boolean = false,
    val isChecking: Boolean = false,
    val activeServerId: String? = null,
    val activeAddressId: UUID? = null,
    val activeAddressUrl: String? = null,
    val isActiveAddressLocal: Boolean = false,
)

/**
 * Monitors active network transports and server endpoint health, selecting the highest-priority
 * reachable address and transitioning automatically into Offline Mode when no endpoint responds.
 */
@Singleton
class ServerConnectionManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val jellyfinApi: JellyfinApi,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    private val networkConnectivity: NetworkConnectivity,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val _state =
        MutableStateFlow(ServerConnectionState(isOffline = !networkConnectivity.isOnline()))
    val state: StateFlow<ServerConnectionState> = _state.asStateFlow()

    private val started = AtomicBoolean(false)
    private val probeMutex = Mutex()
    private val configChangedTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    @Volatile private var lastFailureProbeElapsedMs: Long = 0L

    private val probeClient: OkHttpClient by lazy {
        okHttpClient
            .newBuilder()
            .connectTimeout(PROBE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    @OptIn(FlowPreview::class)
    fun startMonitoring() {
        if (!started.compareAndSet(false, true)) return

        appScope.launch {
            merge(
                    appPreferences.observe(appPreferences.currentServer),
                    networkConnectivity.observeNetworkChanges(),
                    configChangedTrigger,
                )
                .debounce(PROBE_DEBOUNCE_MS)
                .collect { runProbe(showChecking = false) }
        }
    }

    /** Immediately probes candidate endpoints for the active server and returns online status. */
    suspend fun probeNow(): Boolean {
        runProbe(showChecking = true)
        return !_state.value.isOffline
    }

    /**
     * Clears stale connection state immediately when the user switches to [serverId] and schedules
     * a fresh endpoint probe.
     */
    fun onActiveServerChanged(serverId: String) {
        if (_state.value.activeServerId != serverId) {
            _state.value =
                ServerConnectionState(
                    isOffline = !networkConnectivity.isOnline(),
                    isChecking = true,
                    activeServerId = serverId,
                )
        }
        configChangedTrigger.tryEmit(Unit)
    }

    /** Notifies the monitor that addresses or routing preferences changed for [serverId]. */
    fun notifyConfigChanged(serverId: String? = null) {
        val currentServerId = appPreferences.getValue(appPreferences.currentServer)
        if (serverId == null || serverId == currentServerId) {
            configChangedTrigger.tryEmit(Unit)
        }
    }

    /**
     * Triggers a rate-limited background probe when an online API request fails due to network
     * errors without an OS-level network callback.
     */
    fun onNetworkRequestFailure() {
        if (_state.value.isOffline) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFailureProbeElapsedMs < FAILURE_PROBE_COOLDOWN_MS) return
        lastFailureProbeElapsedMs = now
        appScope.launch { runProbe(showChecking = false) }
    }

    /** Probes [url] via `GET /System/Info/Public` and verifies [expectedServerId] when provided. */
    suspend fun probeEndpoint(url: String, expectedServerId: String?): Boolean {
        val normalized = url.trim().trimEnd('/')
        if (normalized.isBlank()) return false
        val httpUrl = normalized.toHttpUrlOrNull() ?: return false
        val probeUrl =
            httpUrl
                .newBuilder()
                .addPathSegment("System")
                .addPathSegment("Info")
                .addPathSegment("Public")
                .query(null)
                .fragment(null)
                .build()

        return withTimeoutOrNull(PROBE_CALL_TIMEOUT_MS + 300L) {
            withContext(Dispatchers.IO) {
                try {
                    val request =
                        Request.Builder()
                            .url(probeUrl)
                            .header("Accept", "application/json")
                            .get()
                            .build()
                    probeClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext false
                        if (expectedServerId == null) return@withContext true

                        val body = response.body.string()
                        val reportedId = JSONObject(body).optString("Id", "")
                        if (reportedId.isBlank()) return@withContext false
                        reportedId
                            .replace("-", "")
                            .equals(
                                expectedServerId.replace("-", ""),
                                ignoreCase = true,
                            )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.d(e, "Endpoint probe failed for %s", normalized)
                    false
                }
            }
        } ?: false
    }

    private suspend fun runProbe(showChecking: Boolean) {
        probeMutex.withLock {
            val serverId = appPreferences.getValue(appPreferences.currentServer)
            if (serverId == null) {
                _state.value = ServerConnectionState(isOffline = false, isChecking = false)
                return
            }

            val serverWithAddresses = runCatching {
                database.getServerWithAddresses(serverId)
            }
                .getOrNull()
            if (serverWithAddresses == null) {
                _state.value =
                    ServerConnectionState(
                        isOffline = false,
                        isChecking = false,
                        activeServerId = serverId,
                    )
                return
            }

            if (showChecking) {
                _state.update { it.copy(isChecking = true) }
            }

            if (!networkConnectivity.isOnline()) {
                transitionToOffline(serverId)
                return
            }

            val server = serverWithAddresses.server
            val candidates =
                ServerAddressResolver.resolveCandidates(
                    server = server,
                    addresses = serverWithAddresses.addresses,
                    canReachLocalNetwork = networkConnectivity.canReachLocalNetwork(),
                )

            for (candidate in candidates) {
                if (probeEndpoint(candidate.address, expectedServerId = server.id)) {
                    transitionToOnline(server.id, server.currentServerAddressId, candidate)
                    return
                }
            }

            transitionToOffline(server.id)
        }
    }

    private suspend fun transitionToOnline(
        serverId: String,
        previousAddressId: UUID?,
        winner: dev.jdtech.jellyfin.models.ServerAddress,
    ) {
        val wasOffline = _state.value.isOffline
        if (jellyfinApi.api.baseUrl != winner.address) {
            Timber.i(
                "Switching active server address to %s (local=%s)",
                winner.address,
                winner.isLocal,
            )
            jellyfinApi.api.update(baseUrl = winner.address)
        }
        if (previousAddressId != winner.id) {
            runCatching { database.updateServerCurrentAddress(serverId, winner.id) }
        }
        _state.value =
            ServerConnectionState(
                isOffline = false,
                isChecking = false,
                activeServerId = serverId,
                activeAddressId = winner.id,
                activeAddressUrl = winner.address,
                isActiveAddressLocal = winner.isLocal,
            )
        if (wasOffline) {
            enqueueSyncWorker()
        }
    }

    private fun transitionToOffline(serverId: String) {
        Timber.i("No reachable endpoints for server %s; activating Offline Mode", serverId)
        _state.value =
            ServerConnectionState(
                isOffline = true,
                isChecking = false,
                activeServerId = serverId,
                activeAddressId = null,
                activeAddressUrl = null,
                isActiveAddressLocal = false,
            )
    }

    private fun enqueueSyncWorker() {
        runCatching {
            val constraints =
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val syncWorkRequest =
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    uniqueWorkName = "syncUserData",
                    existingWorkPolicy = ExistingWorkPolicy.KEEP,
                    request = syncWorkRequest,
                )
        }
    }

    companion object {
        private const val PROBE_CONNECT_TIMEOUT_MS = 2_000L
        private const val PROBE_READ_TIMEOUT_MS = 2_500L
        private const val PROBE_CALL_TIMEOUT_MS = 3_000L
        private const val PROBE_DEBOUNCE_MS = 300L
        private const val FAILURE_PROBE_COOLDOWN_MS = 5_000L
    }
}
