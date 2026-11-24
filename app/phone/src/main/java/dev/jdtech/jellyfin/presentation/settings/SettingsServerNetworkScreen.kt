package dev.jdtech.jellyfin.presentation.settings

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyServerAddress
import dev.jdtech.jellyfin.core.presentation.theme.CentralShapes
import dev.jdtech.jellyfin.core.presentation.theme.EmeraldGreen
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.network.EndpointCheckStatus
import dev.jdtech.jellyfin.presentation.settings.components.SettingsActionPill
import dev.jdtech.jellyfin.presentation.settings.components.SettingsPillAction
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.plus
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.presentation.addresses.SettingsServerNetworkAction
import dev.jdtech.jellyfin.setup.presentation.addresses.SettingsServerNetworkState
import dev.jdtech.jellyfin.setup.presentation.addresses.SettingsServerNetworkViewModel
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import java.util.UUID
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private sealed interface NetworkEndpointDialogMode {
    data object EditLocal : NetworkEndpointDialogMode

    data object AddExternal : NetworkEndpointDialogMode

    data class EditExternal(val address: ServerAddress, val index: Int) : NetworkEndpointDialogMode
}

@Composable
fun SettingsServerNetworkScreen(
    serverId: String,
    navigateBack: () -> Unit,
    viewModel: SettingsServerNetworkViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(serverId) { viewModel.load(serverId) }

    ObserveAsEvents(viewModel.snackbarMessages) { message ->
        scope.launch { snackbarHostState.showSnackbar(message.asString(context.resources)) }
    }

    SettingsServerNetworkLayout(
        state = state,
        snackbarHostState = snackbarHostState,
        onTestUrl = viewModel::testEndpointUrl,
        navigateBack = navigateBack,
        onAction = viewModel::onAction,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsServerNetworkLayout(
    state: SettingsServerNetworkState,
    snackbarHostState: SnackbarHostState,
    onTestUrl: suspend (String) -> Boolean,
    navigateBack: () -> Unit,
    onAction: (SettingsServerNetworkAction) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var dialogMode by remember { mutableStateOf<NetworkEndpointDialogMode?>(null) }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val itemHeights = remember { mutableStateMapOf<UUID, Int>() }
    val density = LocalDensity.current
    val spacerPx = remember(density) { with(density) { 2.dp.toPx() } }
    val defaultRowHeightPx = remember(density) { with(density) { 64.dp.toPx() } }
    val haptics = LocalHapticFeedback.current

    fun stepPx(list: List<ServerAddress>, idx: Int): Float {
        val height = itemHeights[list[idx].id]?.toFloat() ?: defaultRowHeightPx
        return height + spacerPx
    }

    fun computeTargetIndex(list: List<ServerAddress>, from: Int, offset: Float): Int {
        if (list.isEmpty()) return 0
        var cursor = from
        if (offset > 0f) {
            var remaining = offset
            while (cursor < list.lastIndex) {
                val nextStep = stepPx(list, cursor + 1)
                if (remaining >= nextStep * 0.5f) {
                    remaining -= nextStep
                    cursor++
                } else {
                    break
                }
            }
        } else if (offset < 0f) {
            var remaining = -offset
            while (cursor > 0) {
                val prevStep = stepPx(list, cursor - 1)
                if (remaining >= prevStep * 0.5f) {
                    remaining -= prevStep
                    cursor--
                } else {
                    break
                }
            }
        }
        return cursor
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = stringResource(SetupR.string.network_title))
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
                    IconButton(
                        onClick = { onAction(SettingsServerNetworkAction.RefreshAll) },
                    ) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_rotate_ccw),
                            contentDescription = stringResource(SetupR.string.refresh_connections),
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
            // 1. Current server address
            item(key = "current_address") {
                val activeUrl = state.activeAddressUrl
                val isHttps = activeUrl?.startsWith("https", ignoreCase = true) == true
                Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    NetworkSectionLabel(
                        iconRes = if (isHttps) CoreR.drawable.ic_lock else CoreR.drawable.ic_globe,
                        title = stringResource(SetupR.string.current_server_address),
                    )
                    Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
                    Surface(
                        shape = CentralShapes.GroupSingle,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier =
                                Modifier.fillMaxWidth().padding(MaterialTheme.spacings.medium),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            when {
                                state.isCheckingConnection -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                                state.isOffline || activeUrl == null -> {
                                    Icon(
                                        painter = painterResource(CoreR.drawable.ic_server_off),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                else -> {
                                    Box(
                                        modifier =
                                            Modifier.size(20.dp)
                                                .clip(CircleShape)
                                                .background(EmeraldGreen),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            painter = painterResource(CoreR.drawable.ic_check),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.surface,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(MaterialTheme.spacings.medium))
                            Text(
                                text =
                                    activeUrl ?: stringResource(SetupR.string.server_unreachable),
                                style =
                                    MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                color =
                                    if (state.isOffline || activeUrl == null)
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (!state.isOffline && activeUrl != null) {
                                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                                Surface(
                                    shape = CentralShapes.Chip,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    Text(
                                        text =
                                            stringResource(
                                                if (state.isActiveAddressLocal)
                                                    SetupR.string.endpoint_local_badge
                                                else SetupR.string.endpoint_external_badge,
                                            ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier =
                                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Automatic URL switching toggle
            item(key = "auto_switch_toggle") {
                Surface(
                    onClick = {
                        onAction(
                            SettingsServerNetworkAction.ToggleAutoSwitch(!state.autoSwitchEnabled),
                        )
                    },
                    shape = CentralShapes.GroupSingle,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(SetupR.string.automatic_url_switching),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
                            Text(
                                text =
                                    stringResource(SetupR.string.automatic_url_switching_summary),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(MaterialTheme.spacings.default))
                        Switch(
                            checked = state.autoSwitchEnabled,
                            onCheckedChange = {
                                onAction(SettingsServerNetworkAction.ToggleAutoSwitch(it))
                            },
                        )
                    }
                }
            }

            // 3. Local network card
            item(key = "local_network") {
                val localAddr = state.localAddress
                val localStatus =
                    localAddr?.let { state.statuses[it.id] } ?: EndpointCheckStatus.UNKNOWN
                val isLocalActive =
                    localAddr != null && localAddr.id == state.currentAddressId && !state.isOffline

                Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    NetworkSectionLabel(
                        iconRes = CoreR.drawable.ic_home,
                        title = stringResource(SetupR.string.local_network),
                    )
                    Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))

                    Surface(
                        shape = CentralShapes.GroupTop,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(SetupR.string.local_network_info),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(MaterialTheme.spacings.medium),
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Surface(
                        shape = CentralShapes.GroupMiddle,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .then(
                                        if (!state.autoSwitchEnabled && localAddr != null) {
                                            Modifier.clickable {
                                                onAction(
                                                    SettingsServerNetworkAction
                                                        .SelectManualEndpoint(
                                                            localAddr.id,
                                                        ),
                                                )
                                            }
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .padding(MaterialTheme.spacings.medium),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier =
                                    Modifier.size(32.dp).clip(CircleShape).clickable(
                                        enabled = localAddr != null,
                                    ) {
                                        localAddr?.let {
                                            onAction(
                                                SettingsServerNetworkAction.ProbeSingle(it.id),
                                            )
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                EndpointStatusIcon(status = localStatus)
                            }
                            Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement =
                                        Arrangement.spacedBy(MaterialTheme.spacings.small),
                                ) {
                                    Text(
                                        text = stringResource(SetupR.string.local_endpoint),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    if (isLocalActive) {
                                        ActiveEndpointChip()
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text =
                                        localAddr?.address
                                            ?: stringResource(
                                                SetupR.string.local_endpoint_not_configured,
                                            ),
                                    style =
                                        if (localAddr != null) {
                                            MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = FontFamily.Monospace,
                                            )
                                        } else {
                                            MaterialTheme.typography.bodyMedium
                                        },
                                    color =
                                        if (localAddr != null) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            val editLocalLabel = stringResource(SetupR.string.edit_endpoint)
                            val deleteLocalLabel = stringResource(CoreR.string.delete)
                            val localActions = buildList {
                                add(
                                    SettingsPillAction(
                                        iconRes = CoreR.drawable.ic_edit,
                                        contentDescription = editLocalLabel,
                                        onClick = {
                                            dialogMode = NetworkEndpointDialogMode.EditLocal
                                        },
                                    ),
                                )
                                if (localAddr != null) {
                                    add(
                                        SettingsPillAction(
                                            iconRes = CoreR.drawable.ic_trash,
                                            contentDescription = deleteLocalLabel,
                                            onClick = {
                                                onAction(
                                                    SettingsServerNetworkAction.ClearLocalEndpoint,
                                                )
                                            },
                                            contentColor = MaterialTheme.colorScheme.error,
                                        ),
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                            SettingsActionPill(actions = localActions)
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Surface(
                        shape = CentralShapes.GroupBottom,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(
                            modifier =
                                Modifier.fillMaxWidth().padding(MaterialTheme.spacings.medium),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    onAction(
                                        SettingsServerNetworkAction.UseCurrentAddressAsLocal,
                                    )
                                },
                                enabled =
                                    state.activeAddressUrl != null ||
                                        state.externalAddresses.isNotEmpty(),
                                shape = CircleShape,
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                            ) {
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_home),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                                Text(
                                    text = stringResource(SetupR.string.use_current_server_address),
                                )
                            }
                        }
                    }
                }
            }

            // 4. External network card (drag-and-drop priority list)
            item(key = "external_network") {
                val externalList = state.externalAddresses
                val totalCount = (if (state.localAddress != null) 1 else 0) + externalList.size

                Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    NetworkSectionLabel(
                        iconRes = CoreR.drawable.ic_globe,
                        title = stringResource(SetupR.string.external_network),
                    )
                    Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))

                    Surface(
                        shape = CentralShapes.GroupTop,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(SetupR.string.external_network_info),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(MaterialTheme.spacings.medium),
                        )
                    }

                    if (externalList.isEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Surface(
                            shape = CentralShapes.GroupMiddle,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(SetupR.string.no_external_endpoints),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(MaterialTheme.spacings.medium),
                            )
                        }
                    } else {
                        val currentExternalList by rememberUpdatedState(externalList)
                        val activeTargetIndex = draggingIndex?.let { from ->
                            computeTargetIndex(externalList, from, dragOffsetY)
                        }
                        val draggedStepPx =
                            draggingIndex?.let { from -> stepPx(externalList, from) } ?: 0f

                        externalList.forEachIndexed { index, address ->
                            key(address.id) {
                                Spacer(modifier = Modifier.height(2.dp))
                                val isDragged = draggingIndex == index
                                val currentIndex by rememberUpdatedState(index)
                                val status =
                                    state.statuses[address.id] ?: EndpointCheckStatus.UNKNOWN
                                val isActiveEndpoint =
                                    address.id == state.currentAddressId && !state.isOffline
                                val targetShift =
                                    if (
                                        draggingIndex != null &&
                                            activeTargetIndex != null &&
                                            !isDragged
                                    ) {
                                        val from = draggingIndex!!
                                        when {
                                            from < activeTargetIndex &&
                                                index in (from + 1)..activeTargetIndex ->
                                                -draggedStepPx
                                            from > activeTargetIndex &&
                                                index in activeTargetIndex until from ->
                                                draggedStepPx
                                            else -> 0f
                                        }
                                    } else {
                                        0f
                                    }
                                val siblingTranslationY by
                                    animateFloatAsState(
                                        targetValue = targetShift,
                                        animationSpec =
                                            if (draggingIndex != null) {
                                                spring(stiffness = Spring.StiffnessMediumLow)
                                            } else {
                                                snap()
                                            },
                                        label = "external_reorder_shift",
                                    )

                                Surface(
                                    shape = CentralShapes.GroupMiddle,
                                    color =
                                        if (isDragged) {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainer
                                        },
                                    shadowElevation = if (isDragged) 6.dp else 0.dp,
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .zIndex(if (isDragged) 2f else 0f)
                                            .graphicsLayer {
                                                translationY =
                                                    if (isDragged) dragOffsetY
                                                    else siblingTranslationY
                                            }
                                            .onSizeChanged { size ->
                                                if (size.height > 0) {
                                                    itemHeights[address.id] = size.height
                                                }
                                            },
                                ) {
                                    Row(
                                        modifier =
                                            Modifier.fillMaxWidth()
                                                .clickable {
                                                    if (!state.autoSwitchEnabled) {
                                                        onAction(
                                                            SettingsServerNetworkAction
                                                                .SelectManualEndpoint(address.id),
                                                        )
                                                    } else {
                                                        dialogMode =
                                                            NetworkEndpointDialogMode.EditExternal(
                                                                address = address,
                                                                index = currentIndex,
                                                            )
                                                    }
                                                }
                                                .padding(
                                                    start = MaterialTheme.spacings.medium,
                                                    end =
                                                        if (externalList.size > 1) {
                                                            MaterialTheme.spacings.small
                                                        } else {
                                                            MaterialTheme.spacings.medium
                                                        },
                                                    top = 12.dp,
                                                    bottom = 12.dp,
                                                ),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier =
                                                Modifier.size(32.dp).clip(CircleShape).clickable {
                                                    onAction(
                                                        SettingsServerNetworkAction.ProbeSingle(
                                                            address.id,
                                                        ),
                                                    )
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            EndpointStatusIcon(status = status)
                                        }

                                        Spacer(
                                            modifier = Modifier.width(MaterialTheme.spacings.small),
                                        )

                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement =
                                                    Arrangement.spacedBy(
                                                        MaterialTheme.spacings.small,
                                                    ),
                                            ) {
                                                Text(
                                                    text = address.address,
                                                    style =
                                                        MaterialTheme.typography.bodyMedium.copy(
                                                            fontFamily = FontFamily.Monospace,
                                                        ),
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false),
                                                )
                                                if (isActiveEndpoint) {
                                                    ActiveEndpointChip()
                                                }
                                            }
                                        }

                                        val editExternalLabel =
                                            stringResource(SetupR.string.edit_endpoint)
                                        val deleteExternalLabel =
                                            stringResource(CoreR.string.delete)
                                        val externalActions = buildList {
                                            add(
                                                SettingsPillAction(
                                                    iconRes = CoreR.drawable.ic_edit,
                                                    contentDescription = editExternalLabel,
                                                    onClick = {
                                                        dialogMode =
                                                            NetworkEndpointDialogMode.EditExternal(
                                                                address = address,
                                                                index = currentIndex,
                                                            )
                                                    },
                                                ),
                                            )
                                            if (totalCount > 1) {
                                                add(
                                                    SettingsPillAction(
                                                        iconRes = CoreR.drawable.ic_trash,
                                                        contentDescription = deleteExternalLabel,
                                                        onClick = {
                                                            onAction(
                                                                SettingsServerNetworkAction
                                                                    .DeleteExternalEndpoint(
                                                                        address.id,
                                                                    ),
                                                            )
                                                        },
                                                        contentColor =
                                                            MaterialTheme.colorScheme.error,
                                                    ),
                                                )
                                            }
                                        }

                                        Spacer(
                                            modifier = Modifier.width(MaterialTheme.spacings.small),
                                        )
                                        SettingsActionPill(actions = externalActions)

                                        if (externalList.size > 1) {
                                            Box(
                                                modifier =
                                                    Modifier.size(44.dp).pointerInput(address.id) {
                                                        var lastReportedTarget = currentIndex
                                                        detectDragGestures(
                                                            onDragStart = {
                                                                haptics.performHapticFeedback(
                                                                    HapticFeedbackType.LongPress,
                                                                )
                                                                val startIdx = currentIndex
                                                                draggingIndex = startIdx
                                                                dragOffsetY = 0f
                                                                lastReportedTarget = startIdx
                                                            },
                                                            onDrag = { change, dragAmount ->
                                                                change.consume()
                                                                val from =
                                                                    draggingIndex
                                                                        ?: return@detectDragGestures
                                                                val list = currentExternalList
                                                                val minOffset =
                                                                    -(0 until from)
                                                                        .sumOf {
                                                                            stepPx(list, it)
                                                                                .toDouble()
                                                                        }
                                                                        .toFloat()
                                                                val maxOffset =
                                                                    ((from + 1) until list.size)
                                                                        .sumOf {
                                                                            stepPx(list, it)
                                                                                .toDouble()
                                                                        }
                                                                        .toFloat()
                                                                val updatedOffset =
                                                                    (dragOffsetY + dragAmount.y)
                                                                        .coerceIn(
                                                                            minOffset,
                                                                            maxOffset,
                                                                        )
                                                                dragOffsetY = updatedOffset
                                                                val target =
                                                                    computeTargetIndex(
                                                                        list,
                                                                        from,
                                                                        updatedOffset,
                                                                    )
                                                                if (target != lastReportedTarget) {
                                                                    lastReportedTarget = target
                                                                    haptics.performHapticFeedback(
                                                                        HapticFeedbackType
                                                                            .TextHandleMove,
                                                                    )
                                                                }
                                                            },
                                                            onDragEnd = {
                                                                val from = draggingIndex
                                                                val to = from?.let {
                                                                    computeTargetIndex(
                                                                        currentExternalList,
                                                                        it,
                                                                        dragOffsetY,
                                                                    )
                                                                }
                                                                draggingIndex = null
                                                                dragOffsetY = 0f
                                                                if (
                                                                    from != null &&
                                                                        to != null &&
                                                                        to != from
                                                                ) {
                                                                    onAction(
                                                                        SettingsServerNetworkAction
                                                                            .ReorderExternalEndpoints(
                                                                                from,
                                                                                to,
                                                                            ),
                                                                    )
                                                                }
                                                            },
                                                            onDragCancel = {
                                                                draggingIndex = null
                                                                dragOffsetY = 0f
                                                            },
                                                        )
                                                    },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    painter =
                                                        painterResource(
                                                            CoreR.drawable.ic_drag_handle,
                                                        ),
                                                    contentDescription = null,
                                                    tint =
                                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Surface(
                        shape = CentralShapes.GroupBottom,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(
                            modifier =
                                Modifier.fillMaxWidth().padding(MaterialTheme.spacings.medium),
                        ) {
                            OutlinedButton(
                                onClick = { dialogMode = NetworkEndpointDialogMode.AddExternal },
                                shape = CircleShape,
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                            ) {
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_plus),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                                Text(text = stringResource(SetupR.string.add_endpoint))
                            }
                        }
                    }
                }
            }
        }
    }

    dialogMode?.let { mode ->
        val initialValue =
            when (mode) {
                NetworkEndpointDialogMode.EditLocal -> state.localAddress?.address.orEmpty()
                NetworkEndpointDialogMode.AddExternal -> ""
                is NetworkEndpointDialogMode.EditExternal -> mode.address.address
            }
        val title =
            when (mode) {
                NetworkEndpointDialogMode.EditLocal -> stringResource(SetupR.string.local_endpoint)
                NetworkEndpointDialogMode.AddExternal -> stringResource(SetupR.string.add_endpoint)
                is NetworkEndpointDialogMode.EditExternal ->
                    stringResource(SetupR.string.edit_endpoint)
            }
        val hint =
            when (mode) {
                NetworkEndpointDialogMode.EditLocal ->
                    stringResource(SetupR.string.local_endpoint_hint)
                NetworkEndpointDialogMode.AddExternal,
                is NetworkEndpointDialogMode.EditExternal ->
                    stringResource(SetupR.string.external_endpoint_hint)
            }

        EndpointEditDialog(
            title = title,
            hint = hint,
            initialUrl = initialValue,
            canClear = mode == NetworkEndpointDialogMode.EditLocal && state.localAddress != null,
            canMoveUp = mode is NetworkEndpointDialogMode.EditExternal && mode.index > 0,
            canMoveDown =
                mode is NetworkEndpointDialogMode.EditExternal &&
                    mode.index < state.externalAddresses.lastIndex,
            onTestUrl = onTestUrl,
            onMoveUp = {
                if (mode is NetworkEndpointDialogMode.EditExternal) {
                    onAction(
                        SettingsServerNetworkAction.ReorderExternalEndpoints(
                            mode.index,
                            mode.index - 1,
                        ),
                    )
                    dialogMode = null
                }
            },
            onMoveDown = {
                if (mode is NetworkEndpointDialogMode.EditExternal) {
                    onAction(
                        SettingsServerNetworkAction.ReorderExternalEndpoints(
                            mode.index,
                            mode.index + 1,
                        ),
                    )
                    dialogMode = null
                }
            },
            onClear = {
                onAction(SettingsServerNetworkAction.ClearLocalEndpoint)
                dialogMode = null
            },
            onSave = { url ->
                when (mode) {
                    NetworkEndpointDialogMode.EditLocal ->
                        onAction(SettingsServerNetworkAction.SaveLocalEndpoint(url))
                    NetworkEndpointDialogMode.AddExternal ->
                        onAction(SettingsServerNetworkAction.AddExternalEndpoint(url))
                    is NetworkEndpointDialogMode.EditExternal ->
                        onAction(
                            SettingsServerNetworkAction.EditExternalEndpoint(mode.address.id, url),
                        )
                }
                dialogMode = null
            },
            onDismiss = { dialogMode = null },
        )
    }
}

@Composable
private fun NetworkSectionLabel(
    iconRes: Int,
    title: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(start = MaterialTheme.spacings.medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActiveEndpointChip(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CentralShapes.Chip,
        color = EmeraldGreen.copy(alpha = 0.16f),
    ) {
        Text(
            text = stringResource(SetupR.string.active),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = EmeraldGreen,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun EndpointStatusIcon(
    status: EndpointCheckStatus,
    modifier: Modifier = Modifier,
) {
    when (status) {
        EndpointCheckStatus.CHECKING -> {
            CircularProgressIndicator(
                modifier = modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        }
        EndpointCheckStatus.REACHABLE -> {
            Box(
                modifier = modifier.size(20.dp).clip(CircleShape).background(EmeraldGreen),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        EndpointCheckStatus.UNREACHABLE -> {
            Icon(
                painter = painterResource(CoreR.drawable.ic_alert_circle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = modifier.size(20.dp),
            )
        }
        EndpointCheckStatus.UNKNOWN -> {
            Icon(
                painter = painterResource(CoreR.drawable.ic_globe),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun EndpointEditDialog(
    title: String,
    hint: String,
    initialUrl: String,
    canClear: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onTestUrl: suspend (String) -> Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onClear: () -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var urlText by remember(initialUrl) { mutableStateOf(initialUrl) }
    var isTesting by remember { mutableStateOf(false) }
    var showInvalidSyntax by remember { mutableStateOf(false) }
    var unreachableConfirmed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
                OutlinedTextField(
                    value = urlText,
                    onValueChange = {
                        urlText = it
                        showInvalidSyntax = false
                        unreachableConfirmed = false
                    },
                    placeholder = { Text(text = hint) },
                    singleLine = true,
                    isError = showInvalidSyntax,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    supportingText =
                        when {
                            showInvalidSyntax -> {
                                { Text(text = stringResource(SetupR.string.endpoint_invalid_url)) }
                            }
                            unreachableConfirmed -> {
                                {
                                    Text(
                                        text =
                                            stringResource(
                                                SetupR.string.endpoint_unreachable_warning,
                                            ),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            else -> null
                        },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (canMoveUp || canMoveDown || canClear) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                    ) {
                        if (canMoveUp) {
                            TextButton(onClick = onMoveUp) {
                                Text(text = stringResource(SetupR.string.move_up))
                            }
                        }
                        if (canMoveDown) {
                            TextButton(onClick = onMoveDown) {
                                Text(text = stringResource(SetupR.string.move_down))
                            }
                        }
                        if (canClear) {
                            TextButton(onClick = onClear) {
                                Text(
                                    text = stringResource(SetupR.string.clear_local_endpoint),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isTesting,
                onClick = {
                    val trimmed = urlText.trim().trimEnd('/')
                    val parsed = trimmed.toHttpUrlOrNull()
                    if (parsed == null) {
                        showInvalidSyntax = true
                        return@Button
                    }
                    if (unreachableConfirmed) {
                        onSave(trimmed)
                        return@Button
                    }
                    isTesting = true
                    scope.launch {
                        val reachable = onTestUrl(trimmed)
                        isTesting = false
                        if (reachable) {
                            onSave(trimmed)
                        } else {
                            unreachableConfirmed = true
                        }
                    }
                },
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                }
                Text(
                    text =
                        stringResource(
                            if (unreachableConfirmed) SetupR.string.save_anyway
                            else SetupR.string.save,
                        ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(SetupR.string.cancel)) }
        },
    )
}

@PreviewScreenSizes
@Composable
private fun SettingsServerNetworkLayoutPreview() {
    val local =
        dummyServerAddress.copy(
            id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            address = "http://192.168.178.100:8096",
            isLocal = true,
        )
    val ext1 =
        dummyServerAddress.copy(
            id = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            address = "https://jellyfin.example.com",
            isLocal = false,
            priority = 0,
        )
    val ext2 =
        dummyServerAddress.copy(
            id = UUID.fromString("00000000-0000-0000-0000-000000000003"),
            address = "http://100.64.0.10:8096",
            isLocal = false,
            priority = 1,
        )

    FindroidTheme(darkTheme = true) {
        SettingsServerNetworkLayout(
            state =
                SettingsServerNetworkState(
                    serverId = "server",
                    serverName = "Home Jellyfin",
                    autoSwitchEnabled = true,
                    isActiveServer = true,
                    isOffline = false,
                    activeAddressUrl = local.address,
                    isActiveAddressLocal = true,
                    currentAddressId = local.id,
                    localAddress = local,
                    externalAddresses = listOf(ext1, ext2),
                    statuses =
                        mapOf(
                            local.id to EndpointCheckStatus.REACHABLE,
                            ext1.id to EndpointCheckStatus.REACHABLE,
                            ext2.id to EndpointCheckStatus.UNREACHABLE,
                        ),
                    isLoading = false,
                ),
            snackbarHostState = remember { SnackbarHostState() },
            onTestUrl = { true },
            navigateBack = {},
            onAction = {},
        )
    }
}

@PreviewScreenSizes
@Composable
private fun SettingsServerNetworkOfflinePreview() {
    val ext1 =
        dummyServerAddress.copy(
            id = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            address = "https://jellyfin.example.com",
            isLocal = false,
            priority = 0,
        )

    FindroidTheme(darkTheme = true) {
        SettingsServerNetworkLayout(
            state =
                SettingsServerNetworkState(
                    serverId = "server",
                    serverName = "Home Jellyfin",
                    autoSwitchEnabled = false,
                    isActiveServer = true,
                    isOffline = true,
                    activeAddressUrl = null,
                    currentAddressId = ext1.id,
                    localAddress = null,
                    externalAddresses = listOf(ext1),
                    statuses = mapOf(ext1.id to EndpointCheckStatus.UNREACHABLE),
                    isLoading = false,
                ),
            snackbarHostState = remember { SnackbarHostState() },
            onTestUrl = { false },
            navigateBack = {},
            onAction = {},
        )
    }
}
