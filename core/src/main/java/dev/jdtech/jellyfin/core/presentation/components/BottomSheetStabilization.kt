package dev.jdtech.jellyfin.core.presentation.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * A [NestedScrollConnection] that stabilizes bottom sheets by absorbing unconsumed upward scroll
 * deltas and fling velocities.
 *
 * When an inner scrollable list (such as a LazyColumn) reaches its boundary and the user continues
 * to scroll or fling upward, unconsumed negative vertical deltas are normally propagated to the
 * bottom sheet's draggable anchor physics, causing the entire container frame to stretch, bounce,
 * or rubber-band elastically.
 *
 * Intercepting unconsumed negative vertical deltas in [onPostScroll] and [onPostFling] prevents
 * container overshooting while preserving inner list edge-effects and downward drag-to-dismiss
 * gestures.
 */
val BottomSheetOverscrollStabilizationConnection =
    object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            return if (available.y < 0f) {
                Offset(0f, available.y)
            } else {
                Offset.Zero
            }
        }

        override suspend fun onPostFling(
            consumed: Velocity,
            available: Velocity,
        ): Velocity {
            return if (available.y < 0f) {
                Velocity(0f, available.y)
            } else {
                Velocity.Zero
            }
        }
    }

/**
 * Modifier that applies [BottomSheetOverscrollStabilizationConnection] to stabilize bottom sheets
 * against elastic container overscroll during upward drags and flings.
 */
fun Modifier.stabilizeBottomSheetOverscroll(): Modifier =
    this.nestedScroll(BottomSheetOverscrollStabilizationConnection)
