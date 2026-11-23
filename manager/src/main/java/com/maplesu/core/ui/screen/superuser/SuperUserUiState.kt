package com.maplesu.core.ui.screen.superuser

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import com.maplesu.core.data.model.AppInfo
import com.maplesu.core.ui.component.SearchStatus
import com.maplesu.core.ui.viewmodel.AppSortConfig
import dev.maplesu.manager.runtime.State

@Immutable
data class GroupedApps(
    val uid: Int,
    val apps: List<AppInfo>,
    val primary: AppInfo,
    val anyAllowSu: Boolean,
    val anyCustom: Boolean,
    val shouldUmount: Boolean,
    val ownerName: String? = null,
    val matchedIdentifiers: Set<String> = emptySet(),
    // Delivery/wiring state of the identity, resolved once per list load by one batched
    // root probe. Defaulted so the cache copy (built for profile navigation) never pays
    // for it.
    val suState: State = State.NOT_AUTHORIZED,
)

@Stable
data class SuperUserUiState(
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val suEnabled: Boolean? = null,
    val groupedApps: List<GroupedApps> = emptyList(),
    val recentlyInstalledResults: List<GroupedApps> = emptyList(),
    val userIds: List<Int> = emptyList(),
    val searchStatus: SearchStatus = SearchStatus(""),
    val searchResults: List<GroupedApps> = emptyList(),
    val showSystemApps: Boolean = false,
    val showOnlyPrimaryUserApps: Boolean = false,
    val sortConfig: AppSortConfig = AppSortConfig(),
    /** 模式阻断原因（未判定 / KSU 后端不可用）：非空时页面只读空态并显示原因。 */
    val blockedReason: String? = null,
    val error: Throwable? = null
)

@Immutable
data class SuperUserActions(
    val onRefresh: () -> Unit,
    val onSearchTextChange: (String) -> Unit,
    val onSearchStatusChange: (SearchStatus) -> Unit,
    val onClearSearch: () -> Unit,
    val onToggleShowSystemApps: () -> Unit,
    val onToggleShowOnlyPrimaryUserApps: () -> Unit,
    val onUpdateSortConfig: (AppSortConfig) -> Unit,
    val onOpenProfile: (GroupedApps) -> Unit,
    val onEnableSu: () -> Unit,
)

@Immutable
data class StatusMeta(
    val label: String,
    val bg: Color,
    val fg: Color
)
