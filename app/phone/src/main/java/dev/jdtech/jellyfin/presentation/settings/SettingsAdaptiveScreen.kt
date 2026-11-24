package dev.jdtech.jellyfin.presentation.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass
import dev.jdtech.jellyfin.AboutRoute
import dev.jdtech.jellyfin.DeviceRoute
import dev.jdtech.jellyfin.DownloadPresetsRoute
import dev.jdtech.jellyfin.SettingsFileEditRoute
import dev.jdtech.jellyfin.SettingsRoute
import dev.jdtech.jellyfin.SettingsServerNetworkRoute
import dev.jdtech.jellyfin.SettingsServersRoute
import dev.jdtech.jellyfin.SettingsUsersRoute
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.navigation.resumedAction
import dev.jdtech.jellyfin.navigation.truncateTo
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.jdtech.jellyfin.settings.domain.models.Preference
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceCategory
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceGroup
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceSwitch
import dev.jdtech.jellyfin.settings.presentation.settings.SettingsState

private val SETTINGS_TRANSITION_SPEC = fadeIn(tween(300)) togetherWith fadeOut(tween(300))

/**
 * Adaptive Settings host that displays a single-pane flow on compact/medium screens and a two-pane
 * list-detail layout on expanded screens (tablets and large foldables).
 */
@Composable
fun SettingsAdaptiveScreen(
    navigateToAddServer: () -> Unit,
    navigateToLogin: (username: String?) -> Unit,
    navigateToHome: () -> Unit,
    navigateBack: () -> Unit,
) {
    val isExpanded =
        currentWindowAdaptiveInfo()
            .windowSizeClass
            .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    val detailBackStack = rememberNavBackStack(SettingsServersRoute)
    var hasUserSelectedDetail by rememberSaveable { mutableStateOf(false) }

    val saveableStateHolderDecorator = rememberSaveableStateHolderNavEntryDecorator<NavKey>()
    val viewModelStoreDecorator = rememberViewModelStoreNavEntryDecorator<NavKey>()
    val detailEntryDecorators =
        remember(saveableStateHolderDecorator, viewModelStoreDecorator) {
            listOf(saveableStateHolderDecorator, viewModelStoreDecorator)
        }

    fun selectRootCategory(route: NavKey) {
        hasUserSelectedDetail = true
        if (detailBackStack.firstOrNull() == route) {
            detailBackStack.truncateTo(1)
        } else {
            detailBackStack.clear()
            detailBackStack.add(route)
        }
    }

    fun pushDetail(route: NavKey) {
        detailBackStack.add(route)
    }

    fun popDetail() {
        if (detailBackStack.size > 1) {
            detailBackStack.removeLastOrNull()
        } else if (!isExpanded) {
            hasUserSelectedDetail = false
        }
    }

    BackHandler(enabled = !isExpanded && hasUserSelectedDetail && detailBackStack.size == 1) {
        hasUserSelectedDetail = false
    }

    val detailEntryProvider = entryProvider {
        entry<SettingsRoute> { key ->
            SettingsScreen(
                indexes = key.indexes,
                showBack = !isExpanded || !isRootDetailRoute(key),
                navigateToSettings =
                    resumedAction { indexes -> pushDetail(SettingsRoute(indexes)) },
                navigateToSettingsFileEdit =
                    resumedAction { filePath -> pushDetail(SettingsFileEditRoute(filePath)) },
                navigateToServers = dropUnlessResumed { pushDetail(SettingsServersRoute) },
                navigateToUsers = dropUnlessResumed { pushDetail(SettingsUsersRoute()) },
                navigateToAbout = {},
                navigateToDevice = dropUnlessResumed { pushDetail(DeviceRoute) },
                navigateToDownloadPresets = dropUnlessResumed { pushDetail(DownloadPresetsRoute) },
                navigateBack = dropUnlessResumed { popDetail() },
            )
        }
        entry<SettingsServersRoute> {
            SettingsServersScreen(
                showBack = !isExpanded,
                navigateToServerNetwork =
                    resumedAction { serverId -> pushDetail(SettingsServerNetworkRoute(serverId)) },
                navigateToServerUsers =
                    resumedAction { serverId -> pushDetail(SettingsUsersRoute(serverId)) },
                onAddServerClick = dropUnlessResumed { navigateToAddServer() },
                onServerActivated = dropUnlessResumed { navigateToHome() },
                navigateBack = dropUnlessResumed { popDetail() },
            )
        }
        entry<SettingsServerNetworkRoute> { key ->
            SettingsServerNetworkScreen(
                serverId = key.serverId,
                navigateBack = dropUnlessResumed { popDetail() },
            )
        }
        entry<SettingsUsersRoute> { key ->
            SettingsUsersScreen(
                serverId = key.serverId,
                navigateToHome = dropUnlessResumed { navigateToHome() },
                onAddUserClick = dropUnlessResumed { navigateToLogin(null) },
                onPublicUserClick = resumedAction { username -> navigateToLogin(username) },
                navigateBack = dropUnlessResumed { popDetail() },
            )
        }
        entry<SettingsFileEditRoute> { key ->
            SettingsFileEditScreen(
                filePath = key.filePath,
                navigateBack = dropUnlessResumed { popDetail() },
            )
        }
        entry<DeviceRoute> {
            DeviceScreen(
                showBack = !isExpanded,
                navigateBack = dropUnlessResumed { popDetail() },
            )
        }
        entry<DownloadPresetsRoute> {
            DownloadPresetsScreen(
                handleStartInsets = !isExpanded,
                navigateBack = dropUnlessResumed { popDetail() },
            )
        }
    }

    if (isExpanded) {
        val selectedCategoryId = activeCategoryResId(detailBackStack.firstOrNull())
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.width(340.dp).fillMaxHeight().recalculateWindowInsets(),
            ) {
                SettingsScreen(
                    indexes = listOf(CoreR.string.title_settings),
                    showBack = true,
                    selectedCategoryId = selectedCategoryId,
                    isSidebar = true,
                    navigateToSettings =
                        resumedAction { indexes -> selectRootCategory(SettingsRoute(indexes)) },
                    navigateToSettingsFileEdit =
                        resumedAction { filePath ->
                            selectRootCategory(SettingsFileEditRoute(filePath))
                        },
                    navigateToServers =
                        dropUnlessResumed { selectRootCategory(SettingsServersRoute) },
                    navigateToUsers =
                        dropUnlessResumed { selectRootCategory(SettingsUsersRoute()) },
                    navigateToAbout = {},
                    navigateToDevice = dropUnlessResumed { selectRootCategory(DeviceRoute) },
                    navigateToDownloadPresets =
                        dropUnlessResumed { selectRootCategory(DownloadPresetsRoute) },
                    navigateBack = dropUnlessResumed { navigateBack() },
                )
            }
            VerticalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight().recalculateWindowInsets(),
            ) {
                NavDisplay(
                    backStack = detailBackStack,
                    onBack = { popDetail() },
                    entryDecorators = detailEntryDecorators,
                    entryProvider = detailEntryProvider,
                    transitionSpec = { SETTINGS_TRANSITION_SPEC },
                    popTransitionSpec = { SETTINGS_TRANSITION_SPEC },
                    predictivePopTransitionSpec = { _ -> SETTINGS_TRANSITION_SPEC },
                )
            }
        }
    } else {
        AnimatedContent(
            targetState = hasUserSelectedDetail,
            transitionSpec = { SETTINGS_TRANSITION_SPEC },
            label = "settings_compact_pane",
        ) { showDetail ->
            if (showDetail) {
                NavDisplay(
                    backStack = detailBackStack,
                    onBack = { popDetail() },
                    entryDecorators = detailEntryDecorators,
                    entryProvider = detailEntryProvider,
                    transitionSpec = { SETTINGS_TRANSITION_SPEC },
                    popTransitionSpec = { SETTINGS_TRANSITION_SPEC },
                    predictivePopTransitionSpec = { _ -> SETTINGS_TRANSITION_SPEC },
                )
            } else {
                SettingsScreen(
                    indexes = listOf(CoreR.string.title_settings),
                    showBack = true,
                    navigateToSettings =
                        resumedAction { indexes -> selectRootCategory(SettingsRoute(indexes)) },
                    navigateToSettingsFileEdit =
                        resumedAction { filePath ->
                            selectRootCategory(SettingsFileEditRoute(filePath))
                        },
                    navigateToServers =
                        dropUnlessResumed { selectRootCategory(SettingsServersRoute) },
                    navigateToUsers =
                        dropUnlessResumed { selectRootCategory(SettingsUsersRoute()) },
                    navigateToAbout = {},
                    navigateToDevice = dropUnlessResumed { selectRootCategory(DeviceRoute) },
                    navigateToDownloadPresets =
                        dropUnlessResumed { selectRootCategory(DownloadPresetsRoute) },
                    navigateBack = dropUnlessResumed { navigateBack() },
                )
            }
        }
    }
}

private fun isRootDetailRoute(route: NavKey): Boolean =
    when (route) {
        is SettingsServersRoute,
        is DeviceRoute -> true
        is SettingsRoute -> route.indexes.size <= 1
        else -> false
    }

private fun activeCategoryResId(rootRoute: NavKey?): Int? =
    when (rootRoute) {
        is SettingsServersRoute -> SettingsR.string.settings_category_servers
        is DeviceRoute -> SettingsR.string.settings_category_device
        is SettingsUsersRoute -> SettingsR.string.users
        is AboutRoute -> SettingsR.string.about
        is SettingsRoute -> rootRoute.indexes.firstOrNull()
        else -> null
    }

@Composable
private fun SettingsAdaptivePreviewContent(
    selectedCategoryId: Int,
    detailTitleRes: Int,
    showDetailBack: Boolean,
) {
    val sidebarState =
        SettingsState(
            preferenceGroups =
                listOf(
                    PreferenceGroup(
                        preferences =
                            listOf(
                                PreferenceCategory(
                                    nameStringResource = SettingsR.string.settings_category_servers,
                                    iconDrawableId = SettingsR.drawable.ic_server,
                                ),
                            ),
                    ),
                    PreferenceGroup(
                        preferences =
                            listOf(
                                PreferenceCategory(
                                    nameStringResource =
                                        SettingsR.string.settings_category_interface,
                                    iconDrawableId = SettingsR.drawable.ic_layout_dashboard,
                                ),
                            ),
                    ),
                    PreferenceGroup(
                        preferences =
                            listOf(
                                PreferenceCategory(
                                    nameStringResource = SettingsR.string.settings_category_player,
                                    iconDrawableId = SettingsR.drawable.ic_play,
                                ),
                            ),
                    ),
                    PreferenceGroup(
                        preferences =
                            listOf(
                                PreferenceCategory(
                                    nameStringResource = SettingsR.string.settings_category_device,
                                    iconDrawableId = SettingsR.drawable.ic_smartphone,
                                ),
                            ),
                    ),
                ),
        )
    val detailState =
        SettingsState(
            preferenceGroups =
                listOf(
                    PreferenceGroup(
                        nameStringResource = SettingsR.string.home,
                        preferences =
                            listOf(
                                PreferenceSwitch(
                                    nameStringResource = SettingsR.string.home_suggestions,
                                    backendPreference = Preference("preview_suggestions", true),
                                    value = true,
                                ),
                                PreferenceSwitch(
                                    nameStringResource = SettingsR.string.home_continue_watching,
                                    backendPreference = Preference("preview_continue", true),
                                    value = true,
                                ),
                            ),
                    ),
                ),
        )

    Row(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.width(340.dp).fillMaxHeight()) {
            SettingsScreenLayout(
                title = CoreR.string.title_settings,
                state = sidebarState,
                onAction = {},
                showBack = true,
                selectedCategoryId = selectedCategoryId,
                isSidebar = true,
            )
        }
        VerticalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            SettingsScreenLayout(
                title = detailTitleRes,
                state = detailState,
                onAction = {},
                showBack = showDetailBack,
            )
        }
    }
}

@Preview(widthDp = 1100, heightDp = 700, showBackground = true)
@Composable
private fun SettingsAdaptiveExpandedPrimaryPreview() {
    FindroidTheme(darkTheme = true) {
        SettingsAdaptivePreviewContent(
            selectedCategoryId = SettingsR.string.settings_category_interface,
            detailTitleRes = SettingsR.string.settings_category_interface,
            showDetailBack = false,
        )
    }
}

@Preview(widthDp = 1100, heightDp = 700, showBackground = true)
@Composable
private fun SettingsAdaptiveExpandedNestedPreview() {
    FindroidTheme(darkTheme = true) {
        SettingsAdaptivePreviewContent(
            selectedCategoryId = SettingsR.string.settings_category_player,
            detailTitleRes = SettingsR.string.mpv_options,
            showDetailBack = true,
        )
    }
}

@Preview(widthDp = 1100, heightDp = 700, showBackground = true)
@Composable
private fun SettingsAdaptiveExpandedLightPreview() {
    FindroidTheme(darkTheme = false) {
        SettingsAdaptivePreviewContent(
            selectedCategoryId = SettingsR.string.settings_category_interface,
            detailTitleRes = SettingsR.string.settings_category_interface,
            showDetailBack = false,
        )
    }
}
