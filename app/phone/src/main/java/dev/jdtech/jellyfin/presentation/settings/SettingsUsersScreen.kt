package dev.jdtech.jellyfin.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.components.UserAvatar
import dev.jdtech.jellyfin.core.presentation.dummy.dummyUsers
import dev.jdtech.jellyfin.core.presentation.theme.CentralShapes
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.presentation.settings.components.SettingsActionPill
import dev.jdtech.jellyfin.presentation.settings.components.SettingsPillAction
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.plus
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.presentation.users.SettingsUserEntry
import dev.jdtech.jellyfin.setup.presentation.users.SettingsUsersEvent
import dev.jdtech.jellyfin.setup.presentation.users.SettingsUsersState
import dev.jdtech.jellyfin.setup.presentation.users.SettingsUsersViewModel
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import java.util.UUID

@Composable
fun SettingsUsersScreen(
    serverId: String?,
    navigateToHome: () -> Unit,
    onAddUserClick: () -> Unit,
    onPublicUserClick: (username: String) -> Unit,
    navigateBack: () -> Unit,
    viewModel: SettingsUsersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(serverId) { viewModel.loadUsers(serverId) }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is SettingsUsersEvent.NavigateToHome -> navigateToHome()
        }
    }

    SettingsUsersLayout(
        state = state,
        onUserClick = viewModel::switchToUser,
        onDeleteUser = viewModel::deleteUser,
        onAddUserClick = { viewModel.prepareServerForLogin(onAddUserClick) },
        onPublicUserClick = { username ->
            viewModel.prepareServerForLogin { onPublicUserClick(username) }
        },
        navigateBack = navigateBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsUsersLayout(
    state: SettingsUsersState,
    onUserClick: (UUID) -> Unit,
    onDeleteUser: (UUID) -> Unit,
    onAddUserClick: () -> Unit,
    onPublicUserClick: (String) -> Unit,
    navigateBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var userToDelete by remember { mutableStateOf<User?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = stringResource(SetupR.string.users))
                        if (state.serverName.isNotBlank()) {
                            Text(
                                text = state.serverName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_arrow_left),
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onAddUserClick, enabled = !state.isOffline) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_plus),
                            contentDescription = stringResource(SetupR.string.users_btn_add_user),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(MaterialTheme.spacings.default) + innerPadding,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "saved_accounts") {
                Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    Text(
                        text = stringResource(SetupR.string.saved_accounts),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = MaterialTheme.spacings.medium),
                    )
                    Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))

                    if (state.savedUsers.isEmpty() && !state.isLoading) {
                        Surface(
                            shape = CentralShapes.GroupSingle,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(SetupR.string.users_no_users),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(MaterialTheme.spacings.medium),
                            )
                        }
                    } else {
                        state.savedUsers.forEachIndexed { index, entry ->
                            Surface(
                                shape = CentralShapes.getGroupShape(index, state.savedUsers.size),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .clickable { onUserClick(entry.user.id) }
                                            .padding(MaterialTheme.spacings.medium),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    UserAvatar(
                                        name = entry.user.name,
                                        imageUrl = entry.avatarModel,
                                        size = 40.dp,
                                    )
                                    Spacer(modifier = Modifier.width(MaterialTheme.spacings.medium))
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement =
                                            Arrangement.spacedBy(MaterialTheme.spacings.small),
                                    ) {
                                        Text(
                                            text = entry.user.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (entry.isActive) {
                                            Surface(
                                                shape = CentralShapes.Chip,
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                            ) {
                                                Text(
                                                    text = stringResource(SetupR.string.active),
                                                    style =
                                                        MaterialTheme.typography.labelSmall.copy(
                                                            fontWeight = FontWeight.SemiBold,
                                                        ),
                                                    color =
                                                        MaterialTheme.colorScheme
                                                            .onPrimaryContainer,
                                                    modifier =
                                                        Modifier.padding(
                                                            horizontal = 6.dp,
                                                            vertical = 2.dp,
                                                        ),
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                                    SettingsActionPill(
                                        actions =
                                            listOf(
                                                SettingsPillAction(
                                                    iconRes = CoreR.drawable.ic_trash,
                                                    contentDescription =
                                                        stringResource(
                                                            SetupR.string.remove_user_dialog,
                                                        ),
                                                    onClick = { userToDelete = entry.user },
                                                    contentColor = MaterialTheme.colorScheme.error,
                                                ),
                                            ),
                                    )
                                }
                            }
                            if (index < state.savedUsers.lastIndex) {
                                Spacer(modifier = Modifier.height(2.dp))
                            }
                        }
                    }
                }
            }

            if (!state.isOffline && state.publicUsers.isNotEmpty()) {
                item(key = "public_users") {
                    Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                        Text(
                            text = stringResource(SetupR.string.public_users),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = MaterialTheme.spacings.medium),
                        )
                        Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))

                        state.publicUsers.forEachIndexed { index, entry ->
                            Surface(
                                shape = CentralShapes.getGroupShape(index, state.publicUsers.size),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .clickable { onPublicUserClick(entry.user.name) }
                                            .padding(MaterialTheme.spacings.medium),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    UserAvatar(
                                        name = entry.user.name,
                                        imageUrl = entry.avatarModel,
                                        size = 40.dp,
                                    )
                                    Spacer(modifier = Modifier.width(MaterialTheme.spacings.medium))
                                    Text(
                                        text = entry.user.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Icon(
                                        painter = painterResource(CoreR.drawable.ic_chevron_right),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (index < state.publicUsers.lastIndex) {
                                Spacer(modifier = Modifier.height(2.dp))
                            }
                        }
                    }
                }
            }

            if (state.isOffline) {
                item(key = "offline_add_user_note") {
                    Text(
                        text = stringResource(SetupR.string.offline_add_user_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                    )
                }
            }
        }
    }

    userToDelete?.let { user ->
        AlertDialog(
            onDismissRequest = { userToDelete = null },
            title = { Text(text = stringResource(SetupR.string.remove_user_dialog)) },
            text = {
                Text(
                    text =
                        stringResource(
                            SetupR.string.remove_user_dialog_text,
                            user.name,
                        ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteUser(user.id)
                        userToDelete = null
                    },
                ) {
                    Text(
                        text = stringResource(SetupR.string.confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { userToDelete = null }) {
                    Text(text = stringResource(SetupR.string.cancel))
                }
            },
        )
    }
}

@PreviewScreenSizes
@Composable
private fun SettingsUsersLayoutPreview() {
    FindroidTheme(darkTheme = true) {
        SettingsUsersLayout(
            state =
                SettingsUsersState(
                    serverId = "server",
                    serverName = "Home Jellyfin",
                    savedUsers =
                        dummyUsers.mapIndexed { idx, user ->
                            SettingsUserEntry(user = user, avatarModel = null, isActive = idx == 0)
                        },
                    publicUsers =
                        listOf(
                            SettingsUserEntry(
                                user = dummyUsers.last().copy(name = "Guest"),
                                avatarModel = null,
                                isActive = false,
                            ),
                        ),
                    isOffline = false,
                    isLoading = false,
                ),
            onUserClick = {},
            onDeleteUser = {},
            onAddUserClick = {},
            onPublicUserClick = {},
            navigateBack = {},
        )
    }
}

@PreviewScreenSizes
@Composable
private fun SettingsUsersLayoutOfflinePreview() {
    FindroidTheme(darkTheme = true) {
        SettingsUsersLayout(
            state =
                SettingsUsersState(
                    serverId = "server",
                    serverName = "Home Jellyfin",
                    savedUsers =
                        listOf(
                            SettingsUserEntry(
                                user = dummyUsers.first(),
                                avatarModel = null,
                                isActive = true,
                            ),
                        ),
                    isOffline = true,
                    isLoading = false,
                ),
            onUserClick = {},
            onDeleteUser = {},
            onAddUserClick = {},
            onPublicUserClick = {},
            navigateBack = {},
        )
    }
}
