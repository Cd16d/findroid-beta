package dev.jdtech.jellyfin.presentation.cast.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.cast.MiniPlayerThemeColors
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme

@Composable
fun CastExpandedPlayerHeader(
    deviceName: String?,
    onClose: () -> Unit,
    onDeviceClick: () -> Unit,
    modifier: Modifier = Modifier,
    isExpandedScreen: Boolean = false,
    themeColors: MiniPlayerThemeColors? = null,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val headerHeight = if (isLandscape && !isExpandedScreen) 52.dp else 72.dp

    Box(
        modifier = modifier.fillMaxWidth().height(headerHeight).padding(horizontal = 16.dp),
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.CenterStart),
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor =
                        themeColors?.secondaryButtonContainerColor
                            ?: MaterialTheme.colorScheme.surfaceVariant,
                    contentColor =
                        themeColors?.secondaryButtonContentColor
                            ?: MaterialTheme.colorScheme.onSurfaceVariant,
                ),
        ) {
            Icon(
                painter =
                    painterResource(
                        if (isExpandedScreen) CoreR.drawable.ic_x
                        else CoreR.drawable.ic_chevron_down,
                    ),
                contentDescription =
                    stringResource(
                        if (isExpandedScreen) CoreR.string.close else CoreR.string.collapse,
                    ),
            )
        }

        // Device Pill
        Surface(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
            shape = RoundedCornerShape(24.dp),
            color = themeColors?.accentColor ?: MaterialTheme.colorScheme.primary,
            onClick = onDeviceClick,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_cast),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = themeColors?.onAccentColor ?: MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text =
                        deviceName?.ifBlank { null }
                            ?: stringResource(CoreR.string.cast_select_device),
                    modifier = Modifier.weight(1f, fill = false),
                    color = themeColors?.onAccentColor ?: MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Preview(name = "Header - Phone (Collapse Chevron)", showBackground = true)
@Composable
private fun CastExpandedPlayerHeaderPhonePreview() {
    FindroidTheme {
        CastExpandedPlayerHeader(
            deviceName = "Living Room TV",
            onClose = {},
            onDeviceClick = {},
            isExpandedScreen = false,
        )
    }
}

@Preview(name = "Header - Tablet (Close X)", showBackground = true)
@Composable
private fun CastExpandedPlayerHeaderTabletPreview() {
    FindroidTheme {
        CastExpandedPlayerHeader(
            deviceName = "Living Room TV",
            onClose = {},
            onDeviceClick = {},
            isExpandedScreen = true,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CastExpandedPlayerHeaderNoDevicePreview() {
    FindroidTheme {
        CastExpandedPlayerHeader(
            deviceName = null,
            onClose = {},
            onDeviceClick = {},
            isExpandedScreen = false,
        )
    }
}
