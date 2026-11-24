package dev.jdtech.jellyfin.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyServer
import dev.jdtech.jellyfin.core.presentation.dummy.dummyServerAddress
import dev.jdtech.jellyfin.core.presentation.dummy.dummyUser
import dev.jdtech.jellyfin.core.presentation.theme.CentralShapes
import dev.jdtech.jellyfin.core.presentation.theme.EmeraldGreen
import dev.jdtech.jellyfin.presentation.settings.components.SettingsActionPill
import dev.jdtech.jellyfin.presentation.settings.components.SettingsPillAction
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.plus
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.presentation.servers.SettingsServerEntry
import dev.jdtech.jellyfin.setup.presentation.servers.SettingsServersAction
import dev.jdtech.jellyfin.setup.presentation.servers.SettingsServersEvent
import dev.jdtech.jellyfin.setup.presentation.servers.SettingsServersState
import dev.jdtech.jellyfin.setup.presentation.servers.SettingsServersViewModel
import dev.jdtech.jellyfin.utils.ObserveAsEvents

@Composable
fun SettingsServersScreen(
    navigateToServerNetwork: (serverId: String) -> Unit,
    navigateToServerUsers: (serverId: String) -> Unit,
    onAddServerClick: () -> Unit,
    onServerActivated: () -> Unit,
    navigateBack: () -> Unit,
    viewModel: SettingsServersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadServers() }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is SettingsServersEvent.ServerActivated -> onServerActivated()
            is SettingsServersEvent.OpenUsers -> navigateToServerUsers(event.serverId)
        }
    }

    SettingsServersLayout(
        state = state,
        navigateToServerNetwork = navigateToServerNetwork,
        navigateToServerUsers = navigateToServerUsers,
        onAddServerClick = onAddServerClick,
        navigateBack = navigateBack,
        onAction = viewModel::onAction,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsServersLayout(
    state: SettingsServersState,
    navigateToServerNetwork: (serverId: String) -> Unit,
    navigateToServerUsers: (serverId: String) -> Unit,
    onAddServerClick: () -> Unit,
    navigateBack: () -> Unit,
    onAction: (SettingsServersAction) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var serverToDelete by remember { mutableStateOf<SettingsServerEntry?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(SetupR.string.servers)) },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_arrow_left),
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onAddServerClick) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_plus),
                            contentDescription =
                                stringResource(SetupR.string.servers_btn_add_server),
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
            if (!state.isLoading && state.servers.isEmpty()) {
                item(key = "empty") {
                    Surface(
                        shape = CentralShapes.GroupSingle,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(SetupR.string.servers_no_servers),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(MaterialTheme.spacings.medium),
                        )
                    }
                }
            }

            items(items = state.servers, key = { it.server.id }) { entry ->
                ServerSettingsGroupCard(
                    entry = entry,
                    onSwitchClick = {
                        onAction(SettingsServersAction.SwitchServer(entry.server.id))
                    },
                    onDeleteClick = { serverToDelete = entry },
                    onNetworkClick = { navigateToServerNetwork(entry.server.id) },
                    onUsersClick = { navigateToServerUsers(entry.server.id) },
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                )
            }
        }
    }

    serverToDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { serverToDelete = null },
            title = { Text(text = stringResource(SetupR.string.remove_server_dialog)) },
            text = {
                Text(
                    text =
                        stringResource(
                            SetupR.string.remove_server_dialog_text,
                            entry.server.name,
                        ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAction(SettingsServersAction.DeleteServer(entry.server.id))
                        serverToDelete = null
                    },
                ) {
                    Text(
                        text = stringResource(SetupR.string.confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { serverToDelete = null }) {
                    Text(text = stringResource(SetupR.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ServerSettingsGroupCard(
    entry: SettingsServerEntry,
    onSwitchClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onNetworkClick: () -> Unit,
    onUsersClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val addressCount = entry.addresses.size
    val networkSummary =
        when {
            entry.server.autoSwitchEnabled && addressCount == 1 ->
                stringResource(SetupR.string.auto_switch_summary_single)
            entry.server.autoSwitchEnabled ->
                stringResource(SetupR.string.auto_switch_summary_format, addressCount)
            addressCount == 1 -> stringResource(SetupR.string.manual_addresses_summary_single)
            else -> stringResource(SetupR.string.manual_addresses_summary, addressCount)
        }

    val userCount = entry.users.size
    val usersSummary =
        when {
            entry.activeUser != null && userCount == 1 ->
                stringResource(
                    SetupR.string.server_active_user_summary_single,
                    entry.activeUser!!.name,
                )
            entry.activeUser != null ->
                stringResource(
                    SetupR.string.server_active_user_summary,
                    entry.activeUser!!.name,
                    userCount,
                )
            userCount == 0 -> stringResource(SetupR.string.no_saved_accounts)
            userCount == 1 -> stringResource(SetupR.string.saved_accounts_count_single)
            else -> stringResource(SetupR.string.saved_accounts_count, userCount)
        }

    Column(modifier = modifier) {
        // Row 1: Server header
        Surface(
            shape = CentralShapes.GroupTop,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier.size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (entry.isActiveServer) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_server),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint =
                            if (entry.isActiveServer) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.width(MaterialTheme.spacings.medium))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                    ) {
                        Text(
                            text = entry.server.name,
                            style =
                                MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (entry.isActiveServer) {
                            Surface(
                                shape = CentralShapes.Chip,
                                color =
                                    if (entry.isOffline) MaterialTheme.colorScheme.errorContainer
                                    else EmeraldGreen.copy(alpha = 0.16f),
                            ) {
                                Text(
                                    text =
                                        stringResource(
                                            if (entry.isOffline) CoreR.string.offline
                                            else SetupR.string.active,
                                        ),
                                    style =
                                        MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                    color =
                                        if (entry.isOffline)
                                            MaterialTheme.colorScheme.onErrorContainer
                                        else EmeraldGreen,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = entry.activeUrl ?: stringResource(SetupR.string.server_unreachable),
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val switchLabel = stringResource(SetupR.string.switch_server)
                val deleteLabel = stringResource(SetupR.string.remove_server_dialog)
                val serverActions = buildList {
                    if (!entry.isActiveServer) {
                        add(
                            SettingsPillAction(
                                iconRes = CoreR.drawable.ic_arrow_left_right,
                                contentDescription = switchLabel,
                                onClick = onSwitchClick,
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                    add(
                        SettingsPillAction(
                            iconRes = CoreR.drawable.ic_trash,
                            contentDescription = deleteLabel,
                            onClick = onDeleteClick,
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    )
                }

                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                SettingsActionPill(actions = serverActions)
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Row 2: Network & addresses
        Surface(
            shape = CentralShapes.GroupMiddle,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable(onClick = onNetworkClick)
                        .padding(MaterialTheme.spacings.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_globe),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.default))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(SetupR.string.network_and_addresses),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = networkSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    painter = painterResource(CoreR.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Row 3: Users
        Surface(
            shape = CentralShapes.GroupBottom,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable(onClick = onUsersClick)
                        .padding(MaterialTheme.spacings.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_user),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.default))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(SetupR.string.users),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = usersSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    painter = painterResource(CoreR.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@PreviewScreenSizes
@Composable
private fun SettingsServersLayoutPreview() {
    FindroidTheme(darkTheme = true) {
        SettingsServersLayout(
            state =
                SettingsServersState(
                    isLoading = false,
                    servers =
                        listOf(
                            SettingsServerEntry(
                                server = dummyServer,
                                addresses =
                                    listOf(
                                        dummyServerAddress.copy(
                                            address = "http://192.168.1.100:8096",
                                            isLocal = true,
                                        ),
                                        dummyServerAddress.copy(
                                            address = "https://jellyfin.example.com",
                                        ),
                                    ),
                                users = listOf(dummyUser),
                                activeUser = dummyUser,
                                isActiveServer = true,
                                isOffline = false,
                                activeUrl = "http://192.168.1.100:8096",
                            ),
                            SettingsServerEntry(
                                server = dummyServer.copy(id = "backup", name = "Backup Homelab"),
                                addresses = listOf(dummyServerAddress),
                                users = emptyList(),
                                activeUser = null,
                                isActiveServer = false,
                                isOffline = false,
                                activeUrl = "https://backup.example.com",
                            ),
                        ),
                ),
            navigateToServerNetwork = {},
            navigateToServerUsers = {},
            onAddServerClick = {},
            navigateBack = {},
            onAction = {},
        )
    }
}

@PreviewScreenSizes
@Composable
private fun SettingsServersLayoutOfflinePreview() {
    FindroidTheme(darkTheme = true) {
        SettingsServersLayout(
            state =
                SettingsServersState(
                    isLoading = false,
                    servers =
                        listOf(
                            SettingsServerEntry(
                                server = dummyServer,
                                addresses = listOf(dummyServerAddress),
                                users = listOf(dummyUser),
                                activeUser = dummyUser,
                                isActiveServer = true,
                                isOffline = true,
                                activeUrl = null,
                            ),
                        ),
                ),
            navigateToServerNetwork = {},
            navigateToServerUsers = {},
            onAddServerClick = {},
            navigateBack = {},
            onAction = {},
        )
    }
}
