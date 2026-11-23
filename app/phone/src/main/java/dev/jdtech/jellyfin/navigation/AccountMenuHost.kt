package dev.jdtech.jellyfin.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.zIndex
import dev.jdtech.jellyfin.AboutRoute
import dev.jdtech.jellyfin.LoginRoute
import dev.jdtech.jellyfin.Navigator
import dev.jdtech.jellyfin.SettingsRoute
import dev.jdtech.jellyfin.UsersRoute
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.account.AccountMenuWrapper
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme

/**
 * Navigation-aware wrapper around [AccountMenuWrapper] that wires all account menu navigation
 * actions directly to [navigator], keeping [dev.jdtech.jellyfin.NavigationRoot] clean.
 */
@Composable
fun AccountMenuHost(
    isMenuOpen: Boolean,
    onCloseMenu: () -> Unit,
    navigator: Navigator,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AccountMenuWrapper(
        isMenuOpen = isMenuOpen,
        onCloseMenu = onCloseMenu,
        onNavigateToSettings = {
            navigator.navigate(SettingsRoute(indexes = listOf(CoreR.string.title_settings)))
            onCloseMenu()
        },
        onNavigateToAbout = {
            navigator.navigate(AboutRoute)
            onCloseMenu()
        },
        onAddUser = {
            navigator.navigate(LoginRoute())
            onCloseMenu()
        },
        onManageAccounts = {
            navigator.navigate(UsersRoute)
            onCloseMenu()
        },
        onSwitchUser = {
            navigator.navigateHome()
            onCloseMenu()
        },
        modifier = modifier.zIndex(if (isMenuOpen) 10f else 0f),
        content = content,
    )
}

@Preview(showBackground = true)
@Composable
private fun AccountMenuHostClosedPreview() {
    FindroidTheme(darkTheme = true) {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "AccountMenuHost Content",
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
