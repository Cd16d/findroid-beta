package dev.jdtech.jellyfin.presentation.settings.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme

/**
 * Action descriptor rendered inside [SettingsActionPill].
 *
 * @property iconRes Drawable resource displayed inside the pill segment.
 * @property contentDescription Accessibility label for the icon button.
 * @property onClick Callback invoked when the segment is clicked.
 * @property contentColor Optional tint override; defaults to `onSurfaceVariant`.
 * @property enabled Whether the segment responds to clicks.
 */
data class SettingsPillAction(
    @DrawableRes val iconRes: Int,
    val contentDescription: String,
    val onClick: () -> Unit,
    val contentColor: Color? = null,
    val enabled: Boolean = true,
)

/**
 * Connected Material 3 Expressive split-pill button group for settings list rows, matching the Cast
 * expanded player control pill with animated inner corners on press.
 */
@Composable
fun SettingsActionPill(
    actions: List<SettingsPillAction>,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return

    val containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val defaultContentColor = MaterialTheme.colorScheme.onSurfaceVariant

    if (actions.size == 1) {
        val action = actions.first()
        FilledIconButton(
            onClick = action.onClick,
            enabled = action.enabled,
            shape = CircleShape,
            colors =
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = containerColor,
                    contentColor = action.contentColor ?: defaultContentColor,
                ),
            modifier = modifier.size(36.dp),
        ) {
            Icon(
                painter = painterResource(action.iconRes),
                contentDescription = action.contentDescription,
                modifier = Modifier.size(18.dp),
            )
        }
        return
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        actions.forEachIndexed { index, action ->
            if (index > 0) {
                Spacer(modifier = Modifier.width(2.5.dp))
            }

            val interactionSource = remember(index) { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            val innerCorner by
                animateDpAsState(
                    targetValue = if (isPressed) 50.dp else 3.dp,
                    animationSpec = tween(durationMillis = 200),
                    label = "settings_pill_corner_$index",
                )

            val shape =
                when (index) {
                    0 ->
                        RoundedCornerShape(
                            topStart = 50.dp,
                            bottomStart = 50.dp,
                            topEnd = innerCorner,
                            bottomEnd = innerCorner,
                        )
                    actions.lastIndex ->
                        RoundedCornerShape(
                            topStart = innerCorner,
                            bottomStart = innerCorner,
                            topEnd = 50.dp,
                            bottomEnd = 50.dp,
                        )
                    else -> RoundedCornerShape(innerCorner)
                }

            FilledIconButton(
                onClick = action.onClick,
                enabled = action.enabled,
                shape = shape,
                interactionSource = interactionSource,
                colors =
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = containerColor,
                        contentColor = action.contentColor ?: defaultContentColor,
                    ),
                modifier = Modifier.width(38.dp).height(36.dp),
            ) {
                Icon(
                    painter = painterResource(action.iconRes),
                    contentDescription = action.contentDescription,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsActionPillEditDeletePreview() {
    FindroidTheme(darkTheme = true) {
        SettingsActionPill(
            actions =
                listOf(
                    SettingsPillAction(
                        iconRes = CoreR.drawable.ic_edit,
                        contentDescription = "Edit",
                        onClick = {},
                    ),
                    SettingsPillAction(
                        iconRes = CoreR.drawable.ic_trash,
                        contentDescription = "Delete",
                        onClick = {},
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsActionPillSwitchDeletePreview() {
    FindroidTheme(darkTheme = true) {
        SettingsActionPill(
            actions =
                listOf(
                    SettingsPillAction(
                        iconRes = CoreR.drawable.ic_arrow_left_right,
                        contentDescription = "Switch",
                        onClick = {},
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    SettingsPillAction(
                        iconRes = CoreR.drawable.ic_trash,
                        contentDescription = "Delete",
                        onClick = {},
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsActionPillSinglePreview() {
    FindroidTheme(darkTheme = true) {
        SettingsActionPill(
            actions =
                listOf(
                    SettingsPillAction(
                        iconRes = CoreR.drawable.ic_edit,
                        contentDescription = "Edit",
                        onClick = {},
                    ),
                ),
        )
    }
}
