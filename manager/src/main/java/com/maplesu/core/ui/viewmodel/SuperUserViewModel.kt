package com.maplesu.core.ui.viewmodel

import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.maplesu.core.BuildConfig
import com.maplesu.core.Natives
import com.maplesu.core.R
import com.maplesu.core.data.repository.SettingsRepository
import com.maplesu.core.data.repository.SettingsRepositoryImpl
import com.maplesu.core.data.repository.SuperUserRepository
import com.maplesu.core.data.repository.SuperUserRepositoryImpl
import com.maplesu.core.ksuApp
import com.maplesu.core.ui.component.SearchStatus
import com.maplesu.core.ui.screen.superuser.GroupedApps
import com.maplesu.core.ui.screen.superuser.SuperUserUiState
import com.maplesu.core.ui.util.PinyinUtil
import com.maplesu.core.ui.util.ownerNameForUid
import com.maplesu.core.ui.util.pickPrimary
import dev.maplesu.manager.runtime.State
import dev.maplesu.manager.runtime.RuntimeDeployment
import dev.maplesu.manager.runtime.managerSuState
import dev.maplesu.manager.runtime.authorization.DirectPolicy
import dev.maplesu.manager.runtime.authorization.ManagerAuthorizations
import dev.maplesu.manager.runtime.ultra.UltraAuthorization
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.statesFor
import dev.maplesu.manager.workmode.ModeActivation
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import java.text.Collator
import java.util.Locale
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

internal const val RECENTLY_INSTALLED_WINDOW_MILLIS = 60 * 60 * 1000L

enum class AppSortType {
    NAME, PACKAGE_NAME, INSTALL_TIME, UPDATE_TIME;

    companion object {
        fun fromOrdinal(ordinal: Int): AppSortType =
            entries.getOrElse(ordinal) { NAME }
    }
}

data class AppSortConfig(
    val sortType: AppSortType = AppSortType.NAME,
    val reversed: Boolean = false,
) {
    fun toInt(): Int = sortType.ordinal * 2 + if (reversed) 1 else 0

    fun withType(type: AppSortType): AppSortConfig = copy(sortType = type)
    fun toggleReversed(): AppSortConfig = copy(reversed = !reversed)

    companion object {
        fun fromInt(value: Int): AppSortConfig = AppSortConfig(
            sortType = AppSortType.fromOrdinal(value / 2),
            reversed = value % 2 != 0,
        )
    }
}

internal fun buildRecentlyInstalledGroups(
    groups: List<GroupedApps>,
    nowMillis: Long = System.currentTimeMillis(),
): List<GroupedApps> {
    val cutoffMillis = nowMillis - RECENTLY_INSTALLED_WINDOW_MILLIS
    val collator = Collator.getInstance(Locale.getDefault())

    return groups.mapNotNull { group ->
        val latestInstallTime = group.apps.maxOfOrNull { it.packageInfo.firstInstallTime } ?: return@mapNotNull null
        if (latestInstallTime < cutoffMillis) {
            null
        } else {
            group to latestInstallTime
        }
    }.sortedWith(
        compareByDescending<Pair<GroupedApps, Long>> { it.second }
            .thenBy(collator) { it.first.primary.label }
    ).map { it.first }
}

class SuperUserViewModel(
    private val repo: SuperUserRepository = SuperUserRepositoryImpl(),
    private val settingsRepo: SettingsRepository = SettingsRepositoryImpl(),
    observePublication: Boolean = true,
) : ViewModel() {

    companion object {
        private const val TAG = "SuperUserViewModel"

        private val appsLock = Any()
        private var cachedApps: List<AppInfo> = emptyList()
        private var cachedUserIds: List<Int> = emptyList()
        private var cachedAppsActivation: ModeActivation? = null
        private val groupedAppsLock = Any()
        private var cachedGroupedApps: List<GroupedApps> = emptyList()
        private var cachedGroupedAppsActivation: ModeActivation? = null

        val apps: List<AppInfo>
            get() = synchronized(appsLock) {
                if (ModeRuntime.active != null && cachedAppsActivation === ModeRuntime.activation) cachedApps
                else emptyList()
            }

        suspend fun loadApps(
            useCache: Boolean,
            repo: SuperUserRepository = SuperUserRepositoryImpl(),
        ): Result<Pair<List<AppInfo>, List<Int>>> {
            val mode = ModeRuntime.active
            val activation = ModeRuntime.activation
            synchronized(appsLock) {
                if (useCache && mode != null && ModeRuntime.active == mode &&
                    ModeRuntime.activation === activation && cachedAppsActivation === activation && cachedApps.isNotEmpty()) {
                    return Result.success(cachedApps to cachedUserIds)
                }
            }
            // The repository joins its existing bounded scan; cache publication stays mode-bound.
            return repo.getAppList().mapCatching { (apps, ids) ->
                synchronized(appsLock) {
                    check(mode != null && ModeRuntime.active == mode && ModeRuntime.activation === activation) {
                        "PAGE_QUERY_CONTEXT_CHANGED"
                    }
                    cachedApps = apps
                    cachedUserIds = ids
                    cachedAppsActivation = activation
                }
                apps to ids
            }
        }

        @JvmStatic
        fun getGroupedApp(uid: Int): GroupedApps? {
            return synchronized(groupedAppsLock) {
                if (ModeRuntime.active != null && cachedGroupedAppsActivation === ModeRuntime.activation)
                    cachedGroupedApps.find { it.uid == uid } else null
            }
        }
    }

    typealias AppInfo = com.maplesu.core.data.model.AppInfo

    private val _uiState = MutableStateFlow(SuperUserUiState())
    val uiState: StateFlow<SuperUserUiState> = _uiState.asStateFlow()

    private val refreshMutex = Mutex()
    private val searchQuery = MutableStateFlow("")
    private var sortJob: Job? = null
    private var loadJob: Job? = null
    private val appListRevision = AtomicLong()
    private val forceRequestRevision = AtomicLong(-1L)
    private val startupLoader = SuperuserStartupLoader(
        currentMode = { ModeRuntime.active },
        awaitMode = { ModeRuntime.awaitPageMode(15_000L) },
    )
    var isNeedRefresh = false
        private set

    init {
        viewModelScope.launchSearchQueryCollector(searchQuery, ::applySearchText)
        if (observePublication) {
            viewModelScope.launch {
                RuntimeDeployment.statusChanges.collect { refreshEntryStates() }
            }
        }
    }

    /** Publication can finish after profile/list reads; rebind existing rows without Root work. */
    private suspend fun refreshEntryStates() = refreshMutex.withLock {
        if (!_uiState.value.hasLoaded || ModeRuntime.active != WorkMode.LITE) return@withLock
        val entryReady = withContext(Dispatchers.IO) {
            // Ultra-only：不再有 hook 传统入口需要等待，管理器授权一旦成立即可用。
            if (!ManagerAuthorizations.usesManagerAuthority(ksuApp)) null else true
        } ?: return@withLock
        if (ModeRuntime.active != WorkMode.LITE) return@withLock
        _uiState.update {
            it.copy(
                groupedApps = withManagerSuStates(it.groupedApps, entryReady),
                recentlyInstalledResults = withManagerSuStates(it.recentlyInstalledResults, entryReady),
                searchResults = withManagerSuStates(it.searchResults, entryReady),
            )
        }
    }

    fun markNeedRefresh() {
        appListRevision.incrementAndGet()
        isNeedRefresh = true
    }

    private val suStateRevision = AtomicLong()

    fun refreshSuState() {
        // 未判定：只读空态，不发起任何后端调用（suEnabled=null 表示"不知道"）。
        if (ModeRuntime.active == null) {
            _uiState.update { it.copy(suEnabled = null) }
            return
        }
        val revision = suStateRevision.get()
        val requestedMode = ModeRuntime.active
        viewModelScope.launch(Dispatchers.IO) {
            val probe = dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
            val enabled = if (requestedMode == WorkMode.LITE && probe.usesUltraBackend(ksuApp) &&
                !dev.maplesu.manager.runtime.authorization.ManagerAuthorizations.usesManagerAuthority(ksuApp)) {
                probe.suEnabledState(probe.refresh(ksuApp, force = false))
            } else settingsRepo.isSuEnabled()
            _uiState.update {
                if (suStateRevision.get() == revision && ModeRuntime.active == requestedMode) it.copy(suEnabled = enabled) else it
            }
        }
    }

    fun enableSu() {
        // su 兼容开关是 LITE 语义（软撤销/投递）；KSU 下按 App Profile 授权，不在这里改。
        if (ModeRuntime.active != WorkMode.LITE) return
        suStateRevision.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            if (settingsRepo.setSuEnabled(true)) {
                // Discard reads begun before or during this committed write.
                suStateRevision.incrementAndGet()
                _uiState.update { it.copy(suEnabled = true) }
            }
        }
    }

    fun initializePreferences() {
        _uiState.update {
            it.copy(
                showSystemApps = settingsRepo.superuserShowSystemApps,
                showOnlyPrimaryUserApps = settingsRepo.superuserShowOnlyPrimaryUserApps,
                sortConfig = AppSortConfig.fromInt(settingsRepo.superuserSortOption),
            )
        }
    }

    fun updateSortConfig(config: AppSortConfig): Job {
        settingsRepo.superuserSortOption = config.toInt()
        _uiState.update { it.copy(sortConfig = config) }
        sortJob?.cancel()
        return viewModelScope.launch {
            refreshMutex.withLock {
                val current = _uiState.value.groupedApps
                if (current.isEmpty()) return@withLock
                updateVisibleApps(current)
            }
        }.also { sortJob = it }
    }

    private fun refilterVisibleApps(): Job = viewModelScope.launch {
        refreshMutex.withLock {
            // Re-filter when a filter setting changes
            val grouped = withContext(Dispatchers.IO) {
                withSuStates(buildGroups(filterApps(apps)))
            }
            updateVisibleApps(grouped)
        }
    }

    fun toggleShowSystemApps(): Job {
        val newValue = !_uiState.value.showSystemApps
        settingsRepo.superuserShowSystemApps = newValue
        _uiState.update { it.copy(showSystemApps = newValue) }
        return refilterVisibleApps()
    }

    fun toggleShowOnlyPrimaryUserApps(): Job {
        val newValue = !_uiState.value.showOnlyPrimaryUserApps
        settingsRepo.superuserShowOnlyPrimaryUserApps = newValue
        _uiState.update { it.copy(showOnlyPrimaryUserApps = newValue) }
        return refilterVisibleApps()
    }

    fun updateSearchStatus(status: SearchStatus) {
        val previous = _uiState.value.searchStatus
        _uiState.update { it.copy(searchStatus = status) }
        if (previous.searchText != status.searchText) {
            searchQuery.value = status.searchText
        }
    }

    fun updateSearchText(text: String) {
        updateSearchStatus(_uiState.value.searchStatus.copy(searchText = text))
    }

    private fun filterSearchResults(groups: List<GroupedApps>, text: String): List<GroupedApps> {
        if (text.isEmpty()) return emptyList()

        return groups.mapNotNull { group ->
            val matchedIdentifiers = group.apps.filter {
                it.label.contains(text, true) ||
                        it.displayIdentifier.contains(text, true) ||
                        PinyinUtil.toPinyin(it.label).contains(text, true)
            }.mapTo(linkedSetOf()) { it.displayIdentifier }

            if (matchedIdentifiers.isEmpty()) {
                null
            } else {
                val sortedApps = group.apps.sortedWith(
                    compareByDescending { it.displayIdentifier in matchedIdentifiers }
                )
                group.copy(
                    apps = sortedApps,
                    matchedIdentifiers = matchedIdentifiers,
                )
            }
        }
    }

    private suspend fun applySearchText(text: String) = refreshMutex.withLock {
        _uiState.update {
            it.copy(
                searchStatus = it.searchStatus.copy(
                    resultStatus = searchLoadingStatusFor(text)
                )
            )
        }

        if (text.isEmpty()) {
            _uiState.update { state ->
                state.copy(
                    searchResults = emptyList(),
                    searchStatus = state.searchStatus.copy(resultStatus = SearchStatus.ResultStatus.DEFAULT)
                )
            }
            return@withLock
        }

        val result = withContext(Dispatchers.IO) {
            filterSearchResults(_uiState.value.groupedApps, text)
        }

        _uiState.update {
            it.copy(
                searchResults = result,
                searchStatus = it.searchStatus.copy(resultStatus = searchResultStatusFor(text, result.isEmpty()))
            )
        }
    }

    private fun updateCachedGroupedApps(grouped: List<GroupedApps>, activation: ModeActivation) {
        synchronized(groupedAppsLock) {
            cachedGroupedApps = grouped
            cachedGroupedAppsActivation = activation
        }
    }

    private data class VisibleApps(
        val sorted: List<GroupedApps>,
        val searchResults: List<GroupedApps>,
        val recentlyInstalledResults: List<GroupedApps>,
        val searchText: String,
    )

    private suspend fun projectVisibleApps(grouped: List<GroupedApps>, resort: Boolean = true): VisibleApps {
        val sortConfig = _uiState.value.sortConfig
        val searchText = _uiState.value.searchStatus.searchText
        return withContext(Dispatchers.IO) {
            val sorted = if (resort) sortGroups(grouped, sortConfig) else grouped
            VisibleApps(sorted, filterSearchResults(sorted, searchText), buildRecentlyInstalledGroups(sorted), searchText)
        }
    }

    private fun SuperUserUiState.withVisibleApps(visible: VisibleApps): SuperUserUiState = copy(
        groupedApps = visible.sorted,
        recentlyInstalledResults = visible.recentlyInstalledResults,
        searchResults = visible.searchResults,
        searchStatus = searchStatus.copy(
            resultStatus = searchResultStatusFor(visible.searchText, visible.searchResults.isEmpty())
        ),
    )

    private suspend fun updateVisibleApps(grouped: List<GroupedApps>, resort: Boolean = true) {
        val visible = projectVisibleApps(grouped, resort)
        _uiState.update { it.withVisibleApps(visible) }
    }

    /**
     * Attaches the delivery/wiring state every row displays.
     *
     * <p>Must stay off the main thread: [SuDelivery.statesFor] starts one root process for the
     * whole screen. Asking root per row is what hung this list before, so the identities are
     * resolved in a single call and only the granted rows are probed.
     */
    /**
     * 超管行状态按模式显式分派（主文档 §3.3；W14）：
     * - LITE → [withLiteSuStates]（原实现：Ultra 白名单 / DirectPolicy / SuDelivery.statesFor）；
     * - KSU → 只读 KSU profile 的 allowSu（`Natives.getAppProfile` 经 KSU 分派），
     *   **不调用任何 Lite runtime 方法**（无一次性 root 进程、无 libsu RootService）；
     * - 未判定 / KSU 后端不可用 → 不给断言（默认 NOT_AUTHORIZED），只读空态。
     */
    private fun withSuStates(groups: List<GroupedApps>): List<GroupedApps> {
        if (groups.isEmpty()) return groups
        val mode = ModeRuntime.active
        val ksuBackendAvailable = mode == WorkMode.KSU &&
            runCatching { Natives.version > 0 && Natives.isManager }.getOrDefault(false)
        val source = SuperuserSourcePolicy.resolve(mode, ksuBackendAvailable)
        if (!SuperuserSourcePolicy.allowsLiteRuntimeCalls(source)) {
            return groups.map { group ->
                val state = SuperuserSourcePolicy.ksuSuState(source, group.anyAllowSu)
                group.copy(suState = state ?: State.NOT_AUTHORIZED)
            }
        }
        if (dev.maplesu.manager.runtime.authorization.ManagerAuthorizations.usesManagerAuthority(ksuApp)) {
            // Read publication once for the page; no root process or target launch per row.
            // Ultra-only：没有 hook 传统入口的过渡态，授权成立即可用。
            return withManagerSuStates(groups, entryReady = true)
        }
        return withLiteSuStates(groups)
    }

    private fun withManagerSuStates(groups: List<GroupedApps>, entryReady: Boolean): List<GroupedApps> =
        groups.map { group ->
            group.copy(suState = managerSuState(
                granted = group.anyAllowSu,
                stale = group.apps.any { it.authorizationStale },
                pending = group.apps.any { it.authorizationPending },
                entryReady = entryReady,
            ))
        }

    private fun withLiteSuStates(groups: List<GroupedApps>): List<GroupedApps> {
        if (groups.isEmpty()) return groups
        // 🟢 Ultra 环境：用户看到的「已授权/未授权」直接来自 SDK 的 su_auth 白名单
        //    （profile.allowSu 由 `ManagerAuthorizations.profile` 从 [UltraAuthorization] 缓存读出）。
        //    KerneSU 的投递/PATH 状态机在 Ultra 下不存在，而 `DirectPolicy` / `SuDelivery`
        //    每一步都会起 root 进程（Ultra 内核上会重启系统），所以这里不进那两条路径。
        //    映射：在白名单 = WIRED（内核在请求时即放行），否则 NOT_AUTHORIZED。
        if (UltraEnvironmentProbe.usesUltraBackend(ksuApp)) {
            // A transient SDK probe does not change which backend owns these cached rows.
            if (!UltraEnvironmentProbe.isRunning()) return groups
            return groups.map {
                it.copy(suState = if (it.anyAllowSu) State.WIRED else State.NOT_AUTHORIZED)
            }
        }
        return runCatching {
            val identities = groups.associate { it.primary.packageName to it.anyAllowSu }
            // A record can outlive the installation it was made for. Those rows must say
            // "authorize again" instead of silently doing nothing; the record is the only
            // evidence left, because nothing is delivered to a mismatched identity.
            val stale: Set<String> = runCatching {
                // DirectPolicy reads the same store through the one-shot tool: a uid with a
                // record whose identity no longer matches answers "not granted" here.
                DirectPolicy.grantedUids(ksuApp).orEmpty()
                    .filter { DirectPolicy.hasGrantStrict(ksuApp, it) == false }.mapNotNull { uid ->
                    groups.firstOrNull { it.primary.uid == uid }?.primary?.packageName
                }.toSet()
            }.getOrDefault(emptySet())
            val states = statesFor(ksuApp, identities, stale)
            groups.map {
                it.copy(suState = states[it.primary.packageName] ?: State.NOT_AUTHORIZED)
            }
        }.onFailure {
            // A failed probe must not hide the rows: they keep the default "not authorized"
            // state, which is the only claim the flags can still support.
            Log.w(TAG, "su state lookup failed", it)
        }.getOrDefault(groups)
    }

    private fun filterApps(list: List<AppInfo>): List<AppInfo> {
        val currentState = _uiState.value

        return list.filter {
            if (it.packageName == ksuApp.packageName) return@filter false
            // UID 1053 is the single system-wide WebView zygote (primary user only).
            if (it.isWebViewZygote) return@filter true
            if (it.allowSu || it.hasCustomProfile) {
                return@filter true
            }
            val userFilter = !currentState.showOnlyPrimaryUserApps || it.uid / 100000 == 0
            // 🟠（v2.19）`applicationInfo` 是平台类型、会被系统裁剪，`!!` 一碰就 NPE。
            // 读不到按 flags=0 算"不是系统应用"（比 -1 安全：-1 的 FLAG_SYSTEM 位是 1，
            // 会把应用误判成系统应用从而被过滤掉），与 AppInfo.kt 的兜底思路一致。
            val isSystemApp = (it.packageInfo.applicationInfo?.flags ?: 0).and(ApplicationInfo.FLAG_SYSTEM) != 0
            val typeFilter = it.uid == 2000
                    || currentState.showSystemApps
                    || !isSystemApp
            userFilter && typeFilter
        }
    }

    private fun buildCachedGroups(apps: List<AppInfo>): List<GroupedApps> {
        return buildGroups(apps.filter { it.packageName != ksuApp.packageName })
    }

    private fun buildGroups(
        apps: List<AppInfo>,
        umount: (Int) -> Boolean = { Natives.uidShouldUmount(it) },
    ): List<GroupedApps> {
        val collator = Collator.getInstance(Locale.getDefault())
        val comparator = compareBy<AppInfo> {
            when {
                it.allowSu -> 0
                it.hasCustomProfile -> 1
                else -> 2
            }
        }.thenBy(collator) { it.label }
        return apps.groupBy { it.uid }.map { (uid, list) ->
            val sorted = list.sortedWith(comparator)
            val primary = pickPrimary(sorted)
            val shouldUmount = umount(uid)
            val ownerName = if (sorted.size > 1) ownerNameForUid(uid, sorted) else null

            GroupedApps(
                uid = uid,
                apps = sorted,
                primary = primary,
                anyAllowSu = sorted.any { it.allowSu },
                anyCustom = sorted.any { it.hasCustomProfile },
                shouldUmount = shouldUmount,
                ownerName = ownerName
            )
        }
    }

    private fun groupRank(group: GroupedApps): Int = when {
        group.anyAllowSu -> 0
        group.anyCustom -> 1
        group.apps.size > 1 -> 2
        else -> 3
    }

    private fun sortGroups(groups: List<GroupedApps>, config: AppSortConfig): List<GroupedApps> {
        val collator = Collator.getInstance(Locale.getDefault())
        val base: Comparator<GroupedApps> = when (config.sortType) {
            AppSortType.PACKAGE_NAME -> compareBy { it.primary.displayIdentifier }
            AppSortType.INSTALL_TIME -> compareBy { it.primary.packageInfo.firstInstallTime }
            AppSortType.UPDATE_TIME -> compareBy { it.primary.packageInfo.lastUpdateTime }
            AppSortType.NAME -> Comparator { a, b -> collator.compare(a.primary.label, b.primary.label) }
        }
        val secondary = if (config.reversed) base.reversed() else base

        return groups.sortedWith(Comparator { a, b ->
            val ra = groupRank(a)
            val rb = groupRank(b)
            if (ra != rb) ra - rb else secondary.compare(a, b)
        })
    }

    private fun currentListRequest(mode: WorkMode?, revision: Long, activation: ModeActivation): Boolean =
        ModeRuntime.active == mode && ModeRuntime.activation === activation && appListRevision.get() == revision

    private fun discardListRequest(revision: Long) {
        isNeedRefresh = true
        if (forceRequestRevision.get() <= revision) _uiState.update { it.copy(isRefreshing = false) }
    }

    suspend fun fetchAppList() = fetchAppList(ModeRuntime.active, appListRevision.get(), ModeRuntime.activation)

    private suspend fun fetchAppList(mode: WorkMode?, revision: Long, activation: ModeActivation) {
        refreshMutex.withLock {
            if (!currentListRequest(mode, revision, activation)) {
                discardListRequest(revision)
                return@withLock
            }
            val startupAt = android.os.SystemClock.elapsedRealtime()
            if (BuildConfig.DEBUG) Log.i("MapleSUStartup", "superusers_fetch begin")
            _uiState.update { it.copy(isRefreshing = true, error = null) }

            repo.getAppList().onSuccess { (newApps, ids) ->
                val (cachedGroups, grouped) = withContext(Dispatchers.IO) {
                    val cached = buildCachedGroups(newApps)
                    val umountByUid = cached.associate { it.uid to it.shouldUmount }
                    val visible = buildGroups(filterApps(newApps)) { umountByUid[it] ?: Natives.uidShouldUmount(it) }
                    cached to withSuStates(visible)
                }

                val visible = projectVisibleApps(grouped)
                if (!currentListRequest(mode, revision, activation)) {
                    discardListRequest(revision)
                    return@onSuccess
                }
                // Commit only after the last projection suspension.
                synchronized(appsLock) {
                    cachedApps = newApps
                    cachedUserIds = ids
                    cachedAppsActivation = activation
                }
                updateCachedGroupedApps(cachedGroups, activation)
                if (BuildConfig.DEBUG) Log.i("MapleSUStartup",
                    "superusers_fetch ready elapsedMs=${android.os.SystemClock.elapsedRealtime() - startupAt} appsCount=${newApps.size}")
                _uiState.update {
                    it.withVisibleApps(visible).copy(
                        userIds = ids,
                        isRefreshing = false,
                        hasLoaded = true,
                        error = null,
                        blockedReason = null,
                    )
                }
            }.onFailure { e ->
                if (BuildConfig.DEBUG) Log.i("MapleSUStartup",
                    "superusers_fetch failed elapsedMs=${android.os.SystemClock.elapsedRealtime() - startupAt}")
                Log.e(TAG, "fetchAppList failed", e)
                if (currentListRequest(mode, revision, activation)) recordLoadFailure(e) else discardListRequest(revision)
            }

            if (currentListRequest(mode, revision, activation) && _uiState.value.error == null) isNeedRefresh = false
        }
    }

    private fun recordLoadFailure(error: Throwable) {
        val blocked = when (error) {
            is com.maplesu.core.data.repository.SuperUserModeUnresolvedException ->
                ksuApp.getString(R.string.work_mode_superuser_unresolved)
            is com.maplesu.core.data.repository.SuperUserKsuBackendUnavailableException ->
                ksuApp.getString(R.string.work_mode_superuser_unavailable)
            else -> null
        }
        // Preserve both a successful previous list and the cold page's retryability.
        isNeedRefresh = true
        _uiState.update { it.copy(isRefreshing = false, error = error, blockedReason = blocked) }
    }

    private suspend fun refreshAppList(mode: WorkMode?, revision: Long, activation: ModeActivation, resort: Boolean = true) {
        refreshMutex.withLock {
            if (!currentListRequest(mode, revision, activation)) {
                discardListRequest(revision)
                return@withLock
            }
            val currentApps = apps
            if (currentApps.isEmpty()) return

            repo.refreshProfiles(currentApps).onSuccess { updatedApps ->
                val (cachedGroups, grouped) = withContext(Dispatchers.IO) {
                    val cached = buildCachedGroups(updatedApps)
                    val umountByUid = cached.associate { it.uid to it.shouldUmount }
                    val visible = buildGroups(filterApps(updatedApps)) {
                        umountByUid[it] ?: Natives.uidShouldUmount(it)
                    }
                    val result = if (resort) {
                        visible
                    } else {
                        val byUid = visible.associateBy { it.uid }
                        _uiState.value.groupedApps.map { group ->
                            byUid[group.uid] ?: group.copy(shouldUmount = Natives.uidShouldUmount(group.uid))
                        }
                    }
                    cached to withSuStates(result)
                }
                val visible = projectVisibleApps(grouped, resort)
                if (!currentListRequest(mode, revision, activation)) {
                    discardListRequest(revision)
                    return@onSuccess
                }
                synchronized(appsLock) {
                    cachedApps = updatedApps
                    cachedAppsActivation = activation
                }
                updateCachedGroupedApps(cachedGroups, activation)
                _uiState.update { it.withVisibleApps(visible).copy(isRefreshing = false) }
                isNeedRefresh = false
            }.onFailure { error ->
                Log.e(TAG, "refreshProfiles failed", error)
                if (currentListRequest(mode, revision, activation)) recordLoadFailure(error) else discardListRequest(revision)
            }
        }
    }

    fun loadAppList(force: Boolean = false, resort: Boolean = true): Job {
        if (force) {
            forceRequestRevision.set(appListRevision.incrementAndGet())
            isNeedRefresh = true
        }
        loadJob?.takeIf { it.isActive }?.let { return it }
        // React before mode detection; a pending force remains part of this same Job.
        _uiState.update { it.copy(isRefreshing = true, error = null, blockedReason = null) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                var forcePass = force
                do {
                    val revision = appListRevision.get()
                    var requestedMode: WorkMode? = null
                    var activation = ModeRuntime.activation
                    try {
                        val loaded = startupLoader.load(
                            onWaiting = { _uiState.update { it.copy(suEnabled = null) } },
                        ) { mode ->
                            requestedMode = mode
                            activation = ModeRuntime.activation
                            initializePreferences()
                            refreshSuState()
                            if (forcePass || _uiState.value.groupedApps.isEmpty() || apps.isEmpty()) {
                                fetchAppList(mode, revision, activation)
                            } else {
                                refreshAppList(mode, revision, activation, resort)
                            }
                            val failure = _uiState.value.error
                            if (currentListRequest(mode, revision, activation) && !_uiState.value.hasLoaded &&
                                (failure is TimeoutException || failure?.message == "HOOK_AUTHORITY_UNAVAILABLE")) {
                                // Preserve the existing single startup retry; newer requests own a fresh pass.
                                delay(750L)
                                if (currentListRequest(mode, revision, activation)) fetchAppList(mode, revision, activation)
                            }
                        }
                        if (!loaded && currentListRequest(requestedMode, revision, activation)) {
                            _uiState.update {
                                it.copy(
                                    hasLoaded = false,
                                    blockedReason = ksuApp.getString(R.string.work_mode_superuser_unresolved),
                                )
                            }
                        }
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.e(TAG, "loadAppList failed", e)
                        if (currentListRequest(requestedMode, revision, activation)) recordLoadFailure(e) else discardListRequest(revision)
                    }
                    forcePass = forceRequestRevision.get() > revision
                } while (forcePass)
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
        loadJob = job
        job.start()
        return job
    }

}
