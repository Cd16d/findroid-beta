package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings

/**
 * Reusable Material 3 empty-state placeholder displayed when a section requires server connectivity
 * or no offline content is available.
 */
@Composable
fun OfflinePlaceholder(
    title: String,
    subtitle: String,
    buttonText: String,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
    isChecking: Boolean = false,
    secondaryButtonText: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(MaterialTheme.spacings.default),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_server_off),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.large))
        Button(onClick = onRetryClick, enabled = !isChecking) {
            if (isChecking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
            }
            Text(text = buttonText)
        }
        if (secondaryButtonText != null && onSecondaryClick != null) {
            Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
            OutlinedButton(onClick = onSecondaryClick) { Text(text = secondaryButtonText) }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OfflinePlaceholderPreview() {
    FindroidTheme(darkTheme = true) {
        Surface {
            OfflinePlaceholder(
                title = stringResource(CoreR.string.offline_title),
                subtitle = stringResource(CoreR.string.offline_library_subtitle),
                buttonText = stringResource(CoreR.string.try_again),
                onRetryClick = {},
                secondaryButtonText = stringResource(CoreR.string.go_to_downloads),
                onSecondaryClick = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OfflinePlaceholderCheckingPreview() {
    FindroidTheme(darkTheme = true) {
        Surface {
            OfflinePlaceholder(
                title = stringResource(CoreR.string.offline_title),
                subtitle = stringResource(CoreR.string.offline_home_empty_subtitle),
                buttonText = stringResource(CoreR.string.try_again),
                isChecking = true,
                onRetryClick = {},
            )
        }
    }
}
