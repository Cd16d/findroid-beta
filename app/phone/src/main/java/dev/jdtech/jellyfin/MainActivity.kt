package dev.jdtech.jellyfin

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.cache.HomeCacheStore
import dev.jdtech.jellyfin.player.cast.CastSessionManager
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import dev.jdtech.jellyfin.viewmodels.MainViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var castSessionManager: Lazy<CastSessionManager>

    @Inject lateinit var homeCacheStore: HomeCacheStore

    @Volatile private var castInitStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null && homeCacheStore.onFreshActivityLaunch()) {
            lifecycleScope.launch { homeCacheStore.preloadActiveUserCache() }
        }

        enableEdgeToEdge()

        var insetsReceived = ViewCompat.getRootWindowInsets(window.decorView) != null
        var safetyTimeoutReached = false

        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
            if (!insetsReceived) {
                insetsReceived = true
                view.invalidate()
            }
            insets
        }
        ViewCompat.requestApplyInsets(window.decorView)

        splashScreen.setKeepOnScreenCondition {
            if (safetyTimeoutReached) return@setKeepOnScreenCondition false
            val mainState = viewModel.state.value
            val isSetupComplete =
                !mainState.isLoading &&
                    mainState.hasServers &&
                    mainState.hasCurrentServer &&
                    mainState.hasCurrentUser
            val cacheReady = !isSetupComplete || homeCacheStore.isPreloadComplete.value
            !insetsReceived || mainState.isLoading || !cacheReady
        }

        splashScreen.setOnExitAnimationListener { splashScreenViewProvider ->
            val rootView = splashScreenViewProvider.view
            val iconView = runCatching { splashScreenViewProvider.iconView }.getOrNull()
            val interpolator = AccelerateDecelerateInterpolator()

            iconView
                ?.animate()
                ?.scaleX(1.08f)
                ?.scaleY(1.08f)
                ?.alpha(0f)
                ?.setDuration(220L)
                ?.setInterpolator(interpolator)
                ?.start()

            rootView
                .animate()
                .alpha(0f)
                .setDuration(220L)
                .setInterpolator(interpolator)
                .setListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            runCatching { splashScreenViewProvider.remove() }
                        }

                        override fun onAnimationCancel(animation: Animator) {
                            runCatching { splashScreenViewProvider.remove() }
                        }
                    },
                )
                .start()
        }

        lifecycleScope.launch {
            delay(2000L)
            safetyTimeoutReached = true
            window.decorView.invalidate()
        }
        lifecycleScope.launch { viewModel.state.collect { window.decorView.invalidate() } }
        lifecycleScope.launch {
            homeCacheStore.isPreloadComplete.collect { ready ->
                if (ready) window.decorView.invalidate()
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val manager = castSessionManager.get()
            withContext(Dispatchers.Main) {
                castInitStarted = true
                manager.init()
            }
        }

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()

            FindroidTheme(dynamicColor = state.isDynamicColors) {
                if (!state.isLoading) {
                    CompositionLocalProvider(
                        LocalOfflineMode provides state.isOfflineMode,
                        LocalVolumeKeyEvents provides viewModel.volumeKeyEvents,
                    ) {
                        NavigationRoot(
                            hasServers = state.hasServers,
                            hasCurrentServer = state.hasCurrentServer,
                            hasCurrentUser = state.hasCurrentUser,
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        if (castInitStarted) {
            castSessionManager.get().release()
        }
    }

    private var lastVolumeDownTime = 0L

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (viewModel.isCastPlayerOpen.value) {
            val action = event.action
            val keyCode = event.keyCode
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (action == android.view.KeyEvent.ACTION_DOWN) {
                        val isRepeat = event.repeatCount > 0
                        val now = android.os.SystemClock.uptimeMillis()
                        if (!isRepeat || now - lastVolumeDownTime >= 50L) {
                            lastVolumeDownTime = now
                            viewModel.emitVolumeKeyEvent(isUp = true, isRepeat = isRepeat)
                        }
                    }
                    return true
                }
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (action == android.view.KeyEvent.ACTION_DOWN) {
                        val isRepeat = event.repeatCount > 0
                        val now = android.os.SystemClock.uptimeMillis()
                        if (!isRepeat || now - lastVolumeDownTime >= 50L) {
                            lastVolumeDownTime = now
                            viewModel.emitVolumeKeyEvent(isUp = false, isRepeat = isRepeat)
                        }
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
