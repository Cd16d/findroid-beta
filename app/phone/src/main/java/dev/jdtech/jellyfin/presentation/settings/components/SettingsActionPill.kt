package dev.jdtech.jellyfin.presentation.settings.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
 * @property iconRes Drawable resource displayed inside the circular icon button.
 * @property contentDescription Accessibility label for the icon button.
 * @property onClick Callback invoked when the button is clicked.
 * @property contentColor Optional tint override; defaults to `onSurfaceVariant`.
 * @property enabled Whether the button responds to clicks.
 */
data class SettingsPillAction(
    @DrawableRes val iconRes: Int,
    val contentDescription: String,
    val onClick: () -> Unit,
    val contentColor: Color? = null,
    val enabled: Boolean = true,
)

/** Row of circular tonal icon buttons for settings list rows. */
@Composable
fun SettingsActionPill(
    actions: List<SettingsPillAction>,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return

    val containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val defaultContentColor = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action ->
            FilledIconButton(
                onClick = action.onClick,
                enabled = action.enabled,
                shape = CircleShape,
                colors =
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = containerColor,
                        contentColor = action.contentColor ?: defaultContentColor,
                    ),
                modifier = Modifier.size(36.dp),
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
