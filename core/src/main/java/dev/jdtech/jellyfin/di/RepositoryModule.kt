package dev.jdtech.jellyfin.di

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.cache.HomeCacheStore
import dev.jdtech.jellyfin.cache.UserAvatarStore
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.network.ServerConnectionManager
import dev.jdtech.jellyfin.repository.DelegatingJellyfinRepository
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.JellyfinRepositoryImpl
import dev.jdtech.jellyfin.repository.JellyfinRepositoryOfflineImpl
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Singleton
    @Provides
    fun provideJellyfinRepositoryImpl(
        application: Application,
        jellyfinApi: JellyfinApi,
        serverDatabase: ServerDatabaseDao,
        appPreferences: AppPreferences,
    ): JellyfinRepositoryImpl {
        println("Creating new jellyfinRepositoryImpl")
        return JellyfinRepositoryImpl(application, jellyfinApi, serverDatabase, appPreferences)
    }

    @Singleton
    @Provides
    fun provideJellyfinRepositoryOfflineImpl(
        application: Application,
        jellyfinApi: JellyfinApi,
        serverDatabase: ServerDatabaseDao,
        appPreferences: AppPreferences,
    ): JellyfinRepositoryOfflineImpl {
        println("Creating new jellyfinRepositoryOfflineImpl")
        return JellyfinRepositoryOfflineImpl(
            application,
            jellyfinApi,
            serverDatabase,
            appPreferences,
        )
    }

    @Singleton
    @Provides
    fun provideJellyfinRepository(
        jellyfinRepositoryImpl: JellyfinRepositoryImpl,
        jellyfinRepositoryOfflineImpl: JellyfinRepositoryOfflineImpl,
        serverConnectionManager: ServerConnectionManager,
    ): JellyfinRepository {
        return DelegatingJellyfinRepository(
            online = jellyfinRepositoryImpl,
            offline = jellyfinRepositoryOfflineImpl,
            connectionManager = serverConnectionManager,
        )
    }

    @Singleton
    @Provides
    fun provideHomeCacheStore(
        application: Application,
        appPreferences: AppPreferences,
        serverDatabase: ServerDatabaseDao,
    ): HomeCacheStore = HomeCacheStore(application, appPreferences, serverDatabase)

    @Singleton
    @Provides
    fun provideUserAvatarStore(
        application: Application,
        okHttpClient: OkHttpClient,
        serverDatabase: ServerDatabaseDao,
        appPreferences: AppPreferences,
    ): UserAvatarStore = UserAvatarStore(application, okHttpClient, serverDatabase, appPreferences)
}
