package dev.jdtech.jellyfin.core.presentation.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier

/** Standard motion tokens following Material 3 Emphasized and Standard curves. */
object FindroidMotion {
    const val DURATION_SHORT = 200
    const val DURATION_MEDIUM = 350
    const val DURATION_LONG = 700

    val EasingEnter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f) // M3 EmphasizedDecelerate
    val EasingExit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f) // M3 EmphasizedAccelerate
    val EasingStandard = CubicBezierEasing(0.2f, 0f, 0f, 1f) // M3 Standard
}

/**
 * Animated item modifier for horizontal and vertical lazy lists. New items fade in and slide into
 * place, existing items smoothly shift aside, and removed items fade out smoothly.
 */
fun LazyItemScope.animateRowItem(): Modifier =
    Modifier.animateItem(
        fadeInSpec = tween(FindroidMotion.DURATION_MEDIUM, easing = FindroidMotion.EasingEnter),
        placementSpec =
            tween(FindroidMotion.DURATION_MEDIUM, easing = FindroidMotion.EasingStandard),
        fadeOutSpec = tween(FindroidMotion.DURATION_SHORT, easing = FindroidMotion.EasingExit),
    )

/**
 * LazyListState that stays anchored to the first item when new items arrive at index 0, so newly
 * added/arrived items don't sit off-screen or cut off in the padding.
 */
@Composable
fun rememberLazyListStateAtStart(firstKey: Any?): LazyListState {
    val state = rememberLazyListState()
    DisposableEffect(firstKey) {
        if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) {
            state.requestScrollToItem(0)
        }
        onDispose {}
    }
    return state
}
