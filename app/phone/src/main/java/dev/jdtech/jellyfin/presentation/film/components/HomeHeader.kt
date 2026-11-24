package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.components.UserAvatar
import dev.jdtech.jellyfin.core.presentation.dummy.dummyServer
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings

private enum class HeaderActionMode {
    ERROR,
    OFFLINE,
    LOADING,
    PULLING,
}

@Composable
fun HomeHeader(
    serverName: String,
    userImageUrl: Any?,
    isLoading: Boolean,
    isError: Boolean,
    onServerClick: () -> Unit,
    onErrorClick: () -> Unit,
    onRetryClick: () -> Unit,
    onUserClick: () -> Unit,
    modifier: Modifier = Modifier,
    userName: String = "",
    pullProgress: Float = 0f,
    isOffline: Boolean = false,
    isCheckingConnection: Boolean = false,
    onOfflineClick: () -> Unit = onRetryClick,
) {
    val effectiveLoading = isLoading || isCheckingConnection
    val effectiveError = isError && !isOffline
    val isPulling = pullProgress > 0.02f && !effectiveLoading && !effectiveError && !isOffline
    val currentMode =
        when {
            effectiveLoading -> HeaderActionMode.LOADING
            isOffline -> HeaderActionMode.OFFLINE
            effectiveError -> HeaderActionMode.ERROR
            isPulling -> HeaderActionMode.PULLING
            else -> null
        }
    var displayedMode by remember { mutableStateOf(HeaderActionMode.LOADING) }
    var displayedPullProgress by remember { mutableFloatStateOf(0f) }
    if (currentMode != null) {
        displayedMode = currentMode
    }
    if (isPulling) {
        displayedPullProgress = pullProgress
    }

    val showActionSlot = currentMode != null
    val rawPullScale = (0.1f + pullProgress * 0.9f).coerceIn(0.1f, 1.15f)
    val actionScale by
        animateFloatAsState(
            targetValue = if (isPulling) rawPullScale else 1f,
            animationSpec =
                if (isPulling) {
                    snap()
                } else {
                    spring(stiffness = Spring.StiffnessMediumLow)
                },
            label = "headerRefreshScale",
        )

    Row(
        modifier = modifier.fillMaxWidth().height(56.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Surface(
            onClick = onServerClick,
            modifier = Modifier.fillMaxHeight().weight(1f, fill = false),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = MaterialTheme.spacings.medium),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_logo),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = Color.Unspecified,
                )
                Text(
                    text = serverName,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.animateContentSize(),
                )
            }
        }

        Spacer(Modifier.width(MaterialTheme.spacings.medium))

        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium)) {
            AnimatedVisibility(visible = effectiveError, enter = fadeIn(), exit = fadeOut()) {
                Surface(
                    onClick = onErrorClick,
                    modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Box {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_alert_circle),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = showActionSlot,
                enter = fadeIn(animationSpec = tween(durationMillis = 120)),
                exit =
                    fadeOut(animationSpec = tween(durationMillis = 260)) +
                        scaleOut(targetScale = 0f, animationSpec = tween(durationMillis = 260)),
            ) {
                val isOfflinePill = displayedMode == HeaderActionMode.OFFLINE
                Surface(
                    onClick = { if (isOfflinePill) onOfflineClick() else onRetryClick() },
                    modifier =
                        Modifier.fillMaxHeight()
                            .graphicsLayer {
                                scaleX = actionScale
                                scaleY = actionScale
                            }
                            .animateContentSize()
                            .then(if (isOfflinePill) Modifier else Modifier.aspectRatio(1f)),
                    enabled = effectiveError || isOfflinePill,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        when (displayedMode) {
                            HeaderActionMode.ERROR -> {
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_rotate_ccw),
                                    contentDescription = null,
                                )
                            }
                            HeaderActionMode.OFFLINE -> {
                                Row(
                                    modifier =
                                        Modifier.padding(
                                            horizontal = MaterialTheme.spacings.medium,
                                        ),
                                    horizontalArrangement =
                                        Arrangement.spacedBy(MaterialTheme.spacings.small),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        painter = painterResource(CoreR.drawable.ic_server_off),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = stringResource(CoreR.string.offline),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                    )
                                }
                            }
                            HeaderActionMode.LOADING -> {
                                Box(modifier = Modifier.size(32.dp)) { CircularProgressIndicator() }
                            }
                            HeaderActionMode.PULLING -> {
                                Box(modifier = Modifier.size(32.dp)) {
                                    CircularProgressIndicator(
                                        progress = { displayedPullProgress.coerceIn(0f, 1f) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            UserAvatar(
                name = userName,
                imageUrl = userImageUrl,
                size = 56.dp,
                onClick = onUserClick,
            )
        }
    }
}

@Composable
@Preview(showBackground = true)
private fun HomeHeaderIdlePreview() {
    FindroidTheme(darkTheme = true) {
        HomeHeader(
            serverName = dummyServer.name,
            userName = "Bob",
            userImageUrl = null,
            isLoading = false,
            isError = false,
            pullProgress = 0f,
            onServerClick = {},
            onErrorClick = {},
            onRetryClick = {},
            onUserClick = {},
        )
    }
}

@Composable
@Preview(showBackground = true)
private fun HomeHeaderPullingPreview() {
    FindroidTheme(darkTheme = true) {
        HomeHeader(
            serverName = dummyServer.name,
            userName = "Bob",
            userImageUrl = null,
            isLoading = false,
            isError = false,
            pullProgress = 0.65f,
            onServerClick = {},
            onErrorClick = {},
            onRetryClick = {},
            onUserClick = {},
        )
    }
}

@Composable
@Preview(showBackground = true)
private fun HomeHeaderLoadingPreview() {
    FindroidTheme(darkTheme = true) {
        HomeHeader(
            serverName = dummyServer.name,
            userName = "Bob",
            userImageUrl = null,
            isLoading = true,
            isError = false,
            onServerClick = {},
            onErrorClick = {},
            onRetryClick = {},
            onUserClick = {},
        )
    }
}

@Composable
@Preview(showBackground = true)
private fun HomeHeaderErrorPreview() {
    FindroidTheme(darkTheme = true) {
        HomeHeader(
            serverName = dummyServer.name,
            userName = "Bob",
            userImageUrl = null,
            isLoading = false,
            isError = true,
            onServerClick = {},
            onErrorClick = {},
            onRetryClick = {},
            onUserClick = {},
        )
    }
}

@Composable
@Preview(showBackground = true)
private fun HomeHeaderOfflinePreview() {
    FindroidTheme(darkTheme = true) {
        HomeHeader(
            serverName = dummyServer.name,
            userName = "Bob",
            userImageUrl = null,
            isLoading = false,
            isError = false,
            isOffline = true,
            onServerClick = {},
            onErrorClick = {},
            onRetryClick = {},
            onUserClick = {},
        )
    }
}

@Composable
@Preview(showBackground = true)
private fun HomeHeaderOfflineCheckingPreview() {
    FindroidTheme(darkTheme = true) {
        HomeHeader(
            serverName = dummyServer.name,
            userName = "Bob",
            userImageUrl = null,
            isLoading = false,
            isError = false,
            isOffline = true,
            isCheckingConnection = true,
            onServerClick = {},
            onErrorClick = {},
            onRetryClick = {},
            onUserClick = {},
        )
    }
}
