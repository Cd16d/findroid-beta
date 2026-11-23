package dev.jdtech.jellyfin.utils

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Pre-warms DNS resolution and OkHttp TCP/TLS connection pool sockets for the active Jellyfin
 * server during application startup so initial REST and image requests avoid handshake latency.
 */
@Singleton
class NetworkPrewarmer
@Inject
constructor(
    private val okHttpClient: OkHttpClient,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
) {
    suspend fun prewarm() {
        withContext(Dispatchers.IO) {
            runCatching {
                val serverId =
                    appPreferences.getValue(appPreferences.currentServer) ?: return@withContext
                val address =
                    database.getServerCurrentAddress(serverId)?.address ?: return@withContext
                val baseUrl = address.trim().trimEnd('/')
                val parsedUrl = baseUrl.toHttpUrlOrNull() ?: return@withContext

                // Warm DNS cache first
                okHttpClient.dns.lookup(parsedUrl.host)

                // Warm TCP + TLS handshake into the shared ConnectionPool with a lightweight HEAD
                val prewarmClient =
                    okHttpClient.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
                val request = Request.Builder().url("$baseUrl/System/Info/Public").head().build()
                prewarmClient.newCall(request).execute().close()
            }
                .onFailure { e -> Timber.d(e, "Network prewarm skipped or failed") }
        }
    }
}
