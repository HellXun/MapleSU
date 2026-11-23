package com.maplesu.core.ui.viewmodel

import android.system.OsConstants
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.maplesu.core.R
import com.maplesu.core.data.repository.SettingsRepository
import com.maplesu.core.data.repository.SettingsRepositoryImpl
import com.maplesu.core.ksuApp
import com.maplesu.core.Natives
import com.maplesu.core.ui.screen.settings.AppearancePrefsSnapshot
import com.maplesu.core.ui.screen.settings.SettingsSnapshotCache
import com.maplesu.core.ui.screen.settings.SettingsUiState
import com.maplesu.core.ui.screen.settings.statusRes
import com.maplesu.core.ui.security.Stealth
import com.maplesu.core.ui.theme.ColorMode
import com.maplesu.core.ui.util.NavIcons
import com.maplesu.core.ui.util.StatusDecoration
import com.maplesu.core.ui.util.ThemeLock
import dev.maplesu.manager.workmode.storage.SettingsPrefsRouter
import dev.maplesu.manager.liteweb.RecoveryWebUiControl
import dev.maplesu.manager.liteweb.RecoveryWebUiState
import dev.maplesu.manager.workmode.ui.LiteHiddenCardUi
import dev.maplesu.manager.workmode.ui.LiteHiddenRuntimeProxy
import dev.maplesu.manager.workmode.ui.LiteHiddenStatus
import dev.maplesu.manager.workmode.ui.SettingsDataSource
import dev.maplesu.manager.workmode.ui.SettingsModePolicy
import dev.maplesu.manager.workmode.ui.SettingsSection
import dev.maplesu.manager.workmode.ui.WorkModeEntryPolicy
import dev.maplesu.manager.workmode.ui.currentWorkModeEntryPolicy
import dev.maplesu.manager.workmode.ui.currentWorkModeHiddenMask

class SettingsViewModel(
    private val repo: SettingsRepository = SettingsRepositoryImpl()
) : ViewModel() {

    private val _uiState = MutableStateFlow(firstFrameState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null
    private var startupHiddenRead = false
    private val localRevision = AtomicLong()
    private val activeToggleWrites = AtomicInteger()
    private class ToggleQueue(var committed: Boolean) {
        val mutex = Mutex()
        var generation = 0L
        var pending = 0
    }
    private val toggleQueues = mutableMapOf<String, ToggleQueue>()
    private val ultraPolicyMutex = Mutex()
    private var ultraPolicyGeneration = 0L
    private var ultraPolicyRead: Job? = null
    private var ultraPolicyCommitted = dev.maplesu.manager.modules.UltraModulePolicyRepository.peek()

    init {
        // Visible screens own refresh; pager precomposition only consumes the local snapshot.
        viewModelScope.launch {
            com.maplesu.core.ui.security.HiddenFactsSignal.revision.collectLatest {
                // 只读取内存/prefs 确认事实，不等慢 root 读取才收起模式信息。
                // 不调用 refresh()：readState() 本身会发出该信号，否则会形成 root 读取循环。
                val masked = withContext(Dispatchers.IO) { mustMaskModeUi() }
                if (masked) {
                    _uiState.update { state ->
                        state.copy(hiddenMasked = true, workModeEntry = WorkModeEntryPolicy.NONE,
                            liteHiddenCard = LiteHiddenCardUi.HIDDEN, showWebUiSelfCheck = false)
                    }
                } else if (refreshJob != null) {
                    withContext(Dispatchers.IO) { publishKnownModeUi() }
                }
            }
        }
    }

    /**
     * 首帧只读本进程快照及模式内存事实，不在 ViewModel 创建的组合调用栈读 prefs/JNI。
     * 缓存不作为隐藏状态证明：在后台重新确认前，带模式字样的入口始终遮蔽。
     * 冷启动无快照时的完整结构预热仍需统一启动链路支持，不能用猜测的安全事实替代。
     */
    private fun firstFrameState(): SettingsUiState {
        val policy = SettingsModePolicy.forMode(dev.maplesu.manager.workmode.ModeRuntime.active)
        return SettingsSnapshotCache.initial(policy) {
            SettingsUiState(modePolicy = policy, capabilities = dev.maplesu.manager.workmode.ModeRuntime.capabilities)
        }.copy(
            hiddenMasked = true,
            workModeEntry = WorkModeEntryPolicy.NONE,
            showWebUiSelfCheck = false,
            liteHiddenCard = LiteHiddenCardUi.HIDDEN,
            ultraModulePolicy = dev.maplesu.manager.modules.UltraModulePolicyRepository.peek(),
        )
    }

    /** 遮蔽优先，且不跨模式读取 Lite 隐藏事实。只能在后台调用。 */
    private fun mustMaskModeUi(): Boolean {
        val active = dev.maplesu.manager.workmode.ModeRuntime.active
        if (active == null) return true
        if (active == dev.maplesu.manager.workmode.WorkMode.LITE &&
            com.maplesu.core.ui.security.HiddenStartupConfirm.current(ksuApp)
        ) return true
        return runCatching { currentWorkModeHiddenMask() }.getOrDefault(true)
    }

    /** Reuse confirmed startup facts before slower settings reads; no root/SDK calls here. */
    private fun publishKnownModeUi(managerValid: Boolean? = null) {
        val hiddenRevision = com.maplesu.core.ui.security.HiddenFactsSignal.revision.value
        val mode = dev.maplesu.manager.workmode.ModeRuntime.active
        val policy = SettingsModePolicy.forMode(mode)
        val masked = mustMaskModeUi()
        val entry = if (masked) WorkModeEntryPolicy.NONE else currentWorkModeEntryPolicy()
        val hiddenCard = if (!masked && policy.mayRead(SettingsDataSource.LITE_HIDDEN) &&
            com.maplesu.core.ui.security.LiteHiddenRuntime.hasRealObservation()) {
            val state = LiteHiddenRuntimeProxy.currentState()
            val status = LiteHiddenStatus.of(state)
            val hidden = status == LiteHiddenStatus.ON || LiteHiddenRuntimeProxy.isMaskingLite()
            LiteHiddenCardUi(visible = !hidden, hidden = hidden, status = status)
        } else LiteHiddenCardUi.HIDDEN
        _uiState.update { current ->
            if (hiddenRevision != com.maplesu.core.ui.security.HiddenFactsSignal.revision.value ||
                mode != dev.maplesu.manager.workmode.ModeRuntime.active) current
            else current.copy(
                modePolicy = policy,
                capabilities = dev.maplesu.manager.workmode.ModeRuntime.capabilities,
                managerValid = managerValid ?: current.managerValid,
                hiddenMasked = masked,
                workModeEntry = entry,
                liteHiddenCard = hiddenCard,
                showWebUiSelfCheck = policy.shows(SettingsSection.WEBUI_SELF_CHECK) && !masked &&
                    !hiddenCard.hidden,
            )
        }
    }

    /** 「界面与外观」的 prefs 部分（内存读）；失败保留 [prev] 的对应值。 */
    private fun readAppearanceLocal(prev: AppearancePrefsSnapshot): AppearancePrefsSnapshot =
        runCatching {
            prev.copy(
                homeLargeCard = repo.homeLargeCard,
                snowEnabled = repo.snowEnabled,
                snowSize = repo.snowSize,
                snowSpeed = repo.snowSpeed,
                trollRainEnabled = repo.trollRainEnabled,
                trollRainSize = repo.trollRainSize,
                trollRainSpeed = repo.trollRainSpeed,
                gravityEnabled = repo.gravityEnabled,
                gravityMode = repo.gravityMode,
                translucent = repo.uiTranslucent,
                translucentAlpha = repo.uiTranslucentAlpha,
                wallpaperDim = repo.wallpaperDim,
                wallpaperBlur = repo.wallpaperBlur,
                decorationAnchor = repo.statusDecorationAnchor,
                decorationDim = repo.statusDecorationDim,
                decorationHideText = repo.statusDecorationHideText,
                decorationZoom = repo.statusDecorationZoom,
                navIconCircle = repo.navIconCircle,
                wallpaperKind = repo.wallpaperKind,
                wallpaperLandKind = repo.wallpaperLandKind,
            )
        }.getOrDefault(prev)

    /** 只在后台调用：除 prefs 外还有 [StatusDecoration] / [NavIcons] 的文件存在性判断。 */
    private fun readAppearance(prev: AppearancePrefsSnapshot): AppearancePrefsSnapshot =
        runCatching {
            readAppearanceLocal(prev).copy(
                hasDecoration = StatusDecoration.hasCustom(ksuApp),
                wallpaperBroken = com.maplesu.core.ui.util.WallpaperStore.looksBroken(
                    com.maplesu.core.ui.util.WallpaperStore.Slot.PORTRAIT),
                wallpaperLandBroken = com.maplesu.core.ui.util.WallpaperStore.looksBroken(
                    com.maplesu.core.ui.util.WallpaperStore.Slot.LANDSCAPE),
                customNavIcons = NavIcons.keys.filterTo(LinkedHashSet()) { NavIcons.has(ksuApp, it) },
            )
        }.getOrDefault(prev)

    /**
     * 后台刷新（全程 IO）。并发调用合并：模式判定与「页面变为当前页」可能前后脚触发，
     * 不需要把 root 读取跑两遍。
     */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshUltraModulePolicy()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            // Read preferences and MapleSU runtime state independently so a failed query
            // retains its previous value without preventing the settings page from opening.
            val revision = localRevision.get()
            val prev = _uiState.value
            val checkUpdate = runCatching { repo.checkUpdate }.getOrDefault(prev.checkUpdate)
            val checkModuleUpdate = runCatching { repo.checkModuleUpdate }.getOrDefault(prev.checkModuleUpdate)
            val themeMode = runCatching { repo.themeMode }.getOrDefault(prev.themeMode)
            val miuixMonet = runCatching { repo.miuixMonet }.getOrDefault(prev.miuixMonet)
            val keyColor = runCatching { repo.keyColor }.getOrDefault(prev.keyColor)
            val enablePredictiveBack = runCatching { repo.enablePredictiveBack }.getOrDefault(prev.enablePredictiveBack)
            val enableSwipeDismiss = runCatching { repo.enableSwipeDismiss }.getOrDefault(prev.enableSwipeDismiss)
            val pagerInterceptionMode = runCatching { repo.pagerInterceptionMode }.getOrDefault(prev.pagerInterceptionMode)
            val enableBlur = runCatching { repo.enableBlur }.getOrDefault(prev.enableBlur)
            val enableFloatingBottomBar = runCatching { repo.enableFloatingBottomBar }.getOrDefault(prev.enableFloatingBottomBar)
            val enableFloatingBottomBarBlur = runCatching { repo.enableFloatingBottomBarBlur }.getOrDefault(prev.enableFloatingBottomBarBlur)
            val enableNavigationBadge = runCatching { repo.enableNavigationBadge }.getOrDefault(prev.enableNavigationBadge)
            val pageScale = runCatching { repo.pageScale }.getOrDefault(prev.pageScale)
            val moduleDescriptionMaxLines = runCatching { repo.moduleDescriptionMaxLines }.getOrDefault(prev.moduleDescriptionMaxLines)
            val enableWebDebugging = runCatching { repo.enableWebDebugging }.getOrDefault(prev.enableWebDebugging)
            val colorStyle = runCatching { repo.colorStyle }.getOrDefault(prev.colorStyle)
            val colorSpec = runCatching { repo.colorSpec }.getOrDefault(prev.colorSpec)

            val uiMode = runCatching { repo.uiMode }.getOrDefault(prev.uiMode)
            val useSoftReboot = runCatching { repo.useSoftReboot }.getOrDefault(prev.useSoftReboot)
            // Publish cheap local preferences before any slower root feature reads.
            val localAppearance = readAppearanceLocal(prev.appearance)
            if (revision == localRevision.get() && activeToggleWrites.get() == 0) {
                _uiState.update { it.copy(
                    uiMode = uiMode, themeMode = themeMode, miuixMonet = miuixMonet,
                    keyColor = keyColor, checkUpdate = checkUpdate, checkModuleUpdate = checkModuleUpdate,
                    enablePredictiveBack = enablePredictiveBack, enableSwipeDismiss = enableSwipeDismiss,
                    pagerInterceptionMode = pagerInterceptionMode, enableBlur = enableBlur,
                    enableFloatingBottomBar = enableFloatingBottomBar, enableFloatingBottomBarBlur = enableFloatingBottomBarBlur,
                    enableNavigationBadge = enableNavigationBadge, pageScale = pageScale,
                    moduleDescriptionMaxLines = moduleDescriptionMaxLines, enableWebDebugging = enableWebDebugging,
                    colorStyle = colorStyle, colorSpec = colorSpec, useSoftReboot = useSoftReboot,
                    appearance = localAppearance,
                ) }
            }


            // ── 2026-10-31 模式独立性：先取唯一裁决，再按许可读取 ──────────────
            // 未判定 → 只有通用项；Ultra 不读任何 KSU 数据；Normal 不读任何 Ultra/Lite 数据。
            // 这些读取会起 root shell / JNI / Ultra SDK 探测，所以不是"渲染时再隐藏"。
            dev.maplesu.manager.workmode.ModeRuntime.awaitPageMode(5_000)
            val policy = SettingsModePolicy.forMode(dev.maplesu.manager.workmode.ModeRuntime.active)
            val managerValid = runCatching { Natives.isManager }.getOrDefault(prev.managerValid)
            publishKnownModeUi(managerValid)

            // Ultra-only：Normal（KSU）设置面已从仓库层与后端整体删除，
            // 这些字段恒为「不支持」，设置页据此不渲染对应条目。
            val capabilities = dev.maplesu.manager.workmode.ModeRuntime.capabilities
            // H3/H7：隐藏「设置项」（Ultra 专属；Normal 的隐藏语义是上面的 KSU stealth）。
            // Normal/未判定**不调用** LiteHiddenRuntimeProxy（不是隐藏视觉，是根本不读）。
            val liteHiddenCard = if (policy.mayRead(SettingsDataSource.LITE_HIDDEN)) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val state = if (startupHiddenRead) LiteHiddenRuntimeProxy.readState() else {
                            com.maplesu.core.ui.security.HiddenStartupConfirm.ensureConfirmedOnce(ksuApp)
                            startupHiddenRead = true
                            if (com.maplesu.core.ui.security.LiteHiddenRuntime.hasRealObservation()) {
                                LiteHiddenRuntimeProxy.currentState()
                            } else dev.maplesu.manager.litehidden.LiteHiddenState.UNKNOWN
                        }
                        val status = LiteHiddenStatus.of(state)
                        val hidden = status == LiteHiddenStatus.ON || LiteHiddenRuntimeProxy.isMaskingLite()
                        LiteHiddenCardUi(visible = !hidden, hidden = hidden, status = status)
                    }.getOrNull()
                } ?: prev.liteHiddenCard
            } else {
                LiteHiddenCardUi.HIDDEN
            }

            // readState() 会通知观察者；从本次真实读取完成后的修订号开始守卫，避免自触发循环。
            val hiddenRevision = com.maplesu.core.ui.security.HiddenFactsSignal.revision.value

            // 管理器可用性与工作模式入口（旧实现在组合期/主线程读 JNI）。
            val hiddenMasked = mustMaskModeUi()
            val workModeEntry = runCatching { currentWorkModeEntryPolicy() }
                .getOrDefault(WorkModeEntryPolicy.NONE)
            val appearance = readAppearance(prev.appearance)
            if (hiddenRevision != com.maplesu.core.ui.security.HiddenFactsSignal.revision.value ||
                false) return@launch

            _uiState.update {
                if (hiddenRevision != com.maplesu.core.ui.security.HiddenFactsSignal.revision.value ||
                    false) return@update it
                val acceptPreferences = revision == localRevision.get() && activeToggleWrites.get() == 0
                it.copy(
                    modePolicy = policy,
                    capabilities = capabilities,
                    managerValid = managerValid,
                    hiddenMasked = hiddenMasked,
                    workModeEntry = if (hiddenMasked) WorkModeEntryPolicy.NONE else workModeEntry,
                    appearance = if (acceptPreferences) appearance else it.appearance,
                    liteHiddenCard = if (hiddenMasked) LiteHiddenCardUi.HIDDEN else liteHiddenCard,
                    // 隐藏生效（Ultra Lite 隐藏 / Normal KSU stealth 明确 ON）后自检入口消失。
                    showWebUiSelfCheck = policy.shows(SettingsSection.WEBUI_SELF_CHECK) && !hiddenMasked &&
                        !liteHiddenCard.hidden,
                    uiMode = if (acceptPreferences) uiMode else it.uiMode,
                    checkUpdate = if (acceptPreferences) checkUpdate else it.checkUpdate,
                    checkModuleUpdate = if (acceptPreferences) checkModuleUpdate else it.checkModuleUpdate,
                    themeMode = if (acceptPreferences) themeMode else it.themeMode,
                    miuixMonet = if (acceptPreferences) miuixMonet else it.miuixMonet,
                    keyColor = if (acceptPreferences) keyColor else it.keyColor,
                    enablePredictiveBack = if (acceptPreferences) enablePredictiveBack else it.enablePredictiveBack,
                    enableSwipeDismiss = if (acceptPreferences) enableSwipeDismiss else it.enableSwipeDismiss,
                    pagerInterceptionMode = if (acceptPreferences) pagerInterceptionMode else it.pagerInterceptionMode,
                    enableBlur = if (acceptPreferences) enableBlur else it.enableBlur,
                    enableFloatingBottomBar = if (acceptPreferences) enableFloatingBottomBar else it.enableFloatingBottomBar,
                    enableFloatingBottomBarBlur = if (acceptPreferences) enableFloatingBottomBarBlur else it.enableFloatingBottomBarBlur,
                    enableNavigationBadge = if (acceptPreferences) enableNavigationBadge else it.enableNavigationBadge,
                    pageScale = if (acceptPreferences) pageScale else it.pageScale,
                    moduleDescriptionMaxLines = if (acceptPreferences) moduleDescriptionMaxLines else it.moduleDescriptionMaxLines,
                    enableWebDebugging = if (acceptPreferences) enableWebDebugging else it.enableWebDebugging,
                    colorStyle = if (acceptPreferences) colorStyle else it.colorStyle,
                    colorSpec = if (acceptPreferences) colorSpec else it.colorSpec,
                    useSoftReboot = if (acceptPreferences) useSoftReboot else it.useSoftReboot,
                    isLateLoadMode = false,
                )
            }
            SettingsSnapshotCache.store(_uiState.value)
        }
    }

    fun setCheckUpdate(enabled: Boolean) {
        localRevision.incrementAndGet()
        repo.checkUpdate = enabled
        _uiState.update { it.copy(checkUpdate = enabled) }
    }

    private fun refreshUltraModulePolicy() {
        if (dev.maplesu.manager.workmode.ModeRuntime.active != dev.maplesu.manager.workmode.WorkMode.LITE ||
            ultraPolicyRead?.isActive == true) return
        val generation = ultraPolicyGeneration
        ultraPolicyRead = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { dev.maplesu.manager.modules.UltraModulePolicyRepository.read(ksuApp) }
            }
            if (generation != ultraPolicyGeneration ||
                dev.maplesu.manager.workmode.ModeRuntime.active != dev.maplesu.manager.workmode.WorkMode.LITE) return@launch
            result.onSuccess { state ->
                ultraPolicyCommitted = state
                _uiState.update { it.copy(ultraModulePolicy = state) }
            }.onFailure { error ->
                android.util.Log.w("MapleSUModulePolicy", "Settings read failed", error)
                _uiState.update { it.copy(ultraModulePolicy = it.ultraModulePolicy.copy(
                    loaded = true, error = "MODULE_POLICY_READ_FAILED")) }
            }
            SettingsSnapshotCache.store(_uiState.value)
        }
    }

    fun setUltraModulePolicyLoad(enabled: Boolean) {
        val current = _uiState.value.ultraModulePolicy
        if (!current.operable) return
        saveUltraModulePolicy(current.withLoad(enabled), notifyReboot = enabled)
    }

    fun setUltraModulePolicyHide(enabled: Boolean, acceptUnverified: Boolean = false) {
        val current = _uiState.value.ultraModulePolicy
        if (!current.hideOperable || !current.loadRequested) return
        if (enabled && current.hideNeedsRiskConfirmation && !acceptUnverified) return
        saveUltraModulePolicy(current.withHide(enabled, acceptUnverified), notifyReboot = enabled)
    }

    private fun saveUltraModulePolicy(target: dev.maplesu.manager.modules.UltraModulePolicyState, notifyReboot: Boolean) {
        if (dev.maplesu.manager.workmode.ModeRuntime.active != dev.maplesu.manager.workmode.WorkMode.LITE ||
            (target.requested == _uiState.value.ultraModulePolicy.requested &&
                target.unverifiedHide == _uiState.value.ultraModulePolicy.unverifiedHide)) return
        val generation = ++ultraPolicyGeneration
        localRevision.incrementAndGet()
        _uiState.update { it.copy(ultraModulePolicy = target) }
        viewModelScope.launch {
            ultraPolicyMutex.withLock {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        check(dev.maplesu.manager.workmode.ModeRuntime.active == dev.maplesu.manager.workmode.WorkMode.LITE)
                        dev.maplesu.manager.modules.UltraModulePolicyRepository.save(ksuApp, target.requested, target.unverifiedHide)
                    }
                }
                result.onSuccess { ultraPolicyCommitted = it }
                if (generation == ultraPolicyGeneration) {
                    _uiState.update { it.copy(ultraModulePolicy = ultraPolicyCommitted) }
                    if (result.isFailure) Toast.makeText(ksuApp, R.string.ui_polish_setting_failed, Toast.LENGTH_LONG).show()
                    else if (notifyReboot || ultraPolicyCommitted.backend ==
                        dev.maplesu.manager.modules.UltraModulePolicyBackend.HOOK) Toast.makeText(ksuApp,
                        when {
                            ultraPolicyCommitted.needsEnvironmentUpgrade -> R.string.ultra_module_policy_unavailable
                            ultraPolicyCommitted.backend == dev.maplesu.manager.modules.UltraModulePolicyBackend.HOOK ->
                                R.string.ultra_module_policy_hook_applied
                            else -> R.string.ultra_module_policy_reboot
                        }, Toast.LENGTH_LONG).show()
                    SettingsSnapshotCache.store(_uiState.value)
                }
            }
        }
    }

    /** Ordered per-setting writes: immediate visual target, real outcome, latest-target rollback. */
    private fun applyToggle(
        key: String,
        enabled: Boolean,
        current: (SettingsUiState) -> Boolean,
        update: (SettingsUiState, Boolean) -> SettingsUiState,
        persist: suspend () -> String?,
    ) {
        if (true) return
        localRevision.incrementAndGet()
        val queue = toggleQueues.getOrPut(key) { ToggleQueue(current(_uiState.value)) }
        if (queue.pending == 0) queue.committed = current(_uiState.value)
        val generation = ++queue.generation
        queue.pending++
        activeToggleWrites.incrementAndGet()
        _uiState.update { update(it, enabled) }
        viewModelScope.launch {
            queue.mutex.withLock {
                val failure = withContext(Dispatchers.IO) {
                    if (true) {
                        ksuApp.getString(R.string.ui_polish_setting_failed)
                    } else runCatching { persist() }.getOrElse { ksuApp.getString(R.string.ui_polish_setting_failed) }
                }
                if (failure == null) queue.committed = enabled
                queue.pending--
                activeToggleWrites.decrementAndGet()
                localRevision.incrementAndGet()
                if (failure != null && generation == queue.generation) {
                    localRevision.incrementAndGet()
                    _uiState.update { update(it, queue.committed) }
                    Toast.makeText(ksuApp, failure, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
    }

    fun setUiMode(mode: String) {
        localRevision.incrementAndGet()
        val oldMode = repo.uiMode
        val currentThemeMode = repo.themeMode

        // 主题锁定:界面风格(miuix/material)可以切,但颜色模式固定不动
        val newThemeMode = if (com.maplesu.core.ui.util.AppearancePreferences.prefs(com.maplesu.core.ksuApp).contains("theme_document")) {
            currentThemeMode
        } else if (ThemeLock.ENABLED) {
            ThemeLock.THEME_MODE
        } else when (oldMode) {
            "material" if mode == "miuix" -> {
                val colorMode = ColorMode.fromValue(currentThemeMode)
                val baseMode = if (colorMode == ColorMode.DARK_AMOLED) 2 else currentThemeMode
                if (repo.miuixMonet && !colorMode.isMonet) {
                    ColorMode.fromValue(baseMode).toMonetMode()
                } else if (!repo.miuixMonet && colorMode.isMonet) {
                    ColorMode.fromValue(baseMode).toNonMonetMode()
                } else baseMode
            }

            "miuix" if mode == "material" -> {
                val colorMode = ColorMode.fromValue(currentThemeMode)
                if (colorMode.isMonet) {
                    colorMode.toNonMonetMode()
                } else currentThemeMode
            }

            else -> currentThemeMode
        }

        repo.uiMode = mode
        repo.themeMode = newThemeMode
        _uiState.update { it.copy(
            uiMode = mode,
            themeMode = newThemeMode,
            enablePredictiveBack = repo.enablePredictiveBack,
            enableBlur = repo.enableBlur,
            enableFloatingBottomBar = repo.enableFloatingBottomBar,
            enableFloatingBottomBarBlur = repo.enableFloatingBottomBarBlur,
        ) }
    }

    fun setCheckModuleUpdate(enabled: Boolean) {
        localRevision.incrementAndGet()
        repo.checkModuleUpdate = enabled
        _uiState.update { it.copy(checkModuleUpdate = enabled) }
    }

    fun setThemeMode(mode: Int) {
        localRevision.incrementAndGet()
        // 主题锁定:忽略
        if (ThemeLock.ENABLED) return
        val currentUiMode = repo.uiMode
        val effectiveMode = if (currentUiMode == "miuix" && _uiState.value.miuixMonet) {
            mode + 3
        } else {
            mode
        }
        if (editImportedThemeColors(mapOf("nightModeFollowSys" to (mode == 0),"nightModeEnabled" to (mode == 2),"maplesu.amoled" to false))) return
        repo.themeMode = effectiveMode
        _uiState.update { it.copy(themeMode = effectiveMode) }
    }

    fun setHomeLargeCard(enabled: Boolean) {
        localRevision.incrementAndGet()
        repo.homeLargeCard = enabled
        _uiState.update { it.copy(appearance = it.appearance.copy(homeLargeCard = enabled)) }
    }

    fun applyComicPreset() {
        localRevision.incrementAndGet()
        repo.applyComicPreset(true)
        _uiState.update { it.copy(
            appearance = readAppearanceLocal(it.appearance),
            enableBlur = repo.enableBlur,
            enableFloatingBottomBar = repo.enableFloatingBottomBar,
            enableFloatingBottomBarBlur = repo.enableFloatingBottomBarBlur,
        ) }
    }

    fun setColorMode(mode: ColorMode) {
        localRevision.incrementAndGet()
        // 主题锁定:忽略
        if (ThemeLock.ENABLED) return
        if (editImportedThemeColors(mapOf("nightModeFollowSys" to mode.isSystem,"nightModeEnabled" to mode.isDark,"maplesu.amoled" to mode.isAmoled))) return
        repo.themeMode = mode.value
        _uiState.update { it.copy(themeMode = mode.value) }
    }

    fun setMiuixMonet(enabled: Boolean) {
        localRevision.incrementAndGet()
        // 主题锁定:忽略
        if (ThemeLock.ENABLED) return
        if (editImportedThemeColors(mapOf("useSystemDynamicColor" to enabled))) return
        val currentThemeMode = repo.themeMode
        val colorMode = ColorMode.fromValue(currentThemeMode)
        val newThemeMode = if (enabled) {
            if (!colorMode.isMonet) colorMode.toMonetMode() else currentThemeMode
        } else {
            if (colorMode.isMonet) colorMode.toNonMonetMode() else currentThemeMode
        }
        repo.miuixMonet = enabled
        repo.themeMode = newThemeMode
        _uiState.update { it.copy(miuixMonet = enabled, themeMode = newThemeMode) }
    }

    fun setKeyColor(color: Int) {
        localRevision.incrementAndGet()
        if (editImportedThemeColors(mapOf("useSystemDynamicColor" to (color == 0),"finasu_keyColor" to color))) return
        repo.keyColor = color
        _uiState.update { it.copy(keyColor = color) }
    }

    fun setColorStyle(style: String) {
        localRevision.incrementAndGet()
        if (editImportedThemeColors(mapOf("colorStyle" to style.replace(Regex("([a-z])([A-Z])"),"$1_$2").uppercase()))) return
        repo.colorStyle = style
        _uiState.update { it.copy(colorStyle = style) }
    }

    fun setColorSpec(spec: String) {
        localRevision.incrementAndGet()
        if (editImportedThemeColors(mapOf("colorStandard" to if (spec == "SPEC_2021") "MD3_2021" else "M3E_2025"))) return
        repo.colorSpec = spec
        _uiState.update { it.copy(colorSpec = spec) }
    }

    /** Theme edits share the import mutex and publish document/preferences as one snapshot. */
    private fun editImportedThemeColors(changes: Map<String,Any>): Boolean {
        if (!com.maplesu.core.ui.util.AppearancePreferences.prefs(ksuApp).contains("theme_document")) return false
        viewModelScope.launch {
            try { com.maplesu.core.theme.ThemeRepository(ksuApp).edit(changes); refresh() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(ksuApp,e.message ?: e.javaClass.simpleName,Toast.LENGTH_LONG).show(); refresh() }
        }
        return true
    }

    fun setEnablePredictiveBack(enabled: Boolean) {
        localRevision.incrementAndGet()
        repo.enablePredictiveBack = enabled
        _uiState.update { it.copy(enablePredictiveBack = enabled) }
    }

    fun setEnableSwipeDismiss(enabled: Boolean) {
        localRevision.incrementAndGet()
        repo.enableSwipeDismiss = enabled
        _uiState.update { it.copy(enableSwipeDismiss = enabled) }
    }

    fun setPagerInterceptionMode(mode: Int) {
        localRevision.incrementAndGet()
        repo.pagerInterceptionMode = mode
        _uiState.update { it.copy(pagerInterceptionMode = mode.coerceIn(0, 2)) }
    }

    fun setEnableBlur(enabled: Boolean) {
        localRevision.incrementAndGet()
        // 主题锁定:忽略
        if (ThemeLock.ENABLED) return
        repo.enableBlur = enabled
        _uiState.update { it.copy(enableBlur = enabled) }
    }

    fun setEnableFloatingBottomBar(enabled: Boolean) {
        localRevision.incrementAndGet()
        // 主题锁定:忽略
        if (ThemeLock.ENABLED) return
        repo.enableFloatingBottomBar = enabled
        _uiState.update { it.copy(enableFloatingBottomBar = enabled) }
    }

    fun setEnableFloatingBottomBarBlur(enabled: Boolean) {
        localRevision.incrementAndGet()
        // 主题锁定:忽略
        if (ThemeLock.ENABLED) return
        repo.enableFloatingBottomBarBlur = enabled
        _uiState.update { it.copy(enableFloatingBottomBarBlur = enabled) }
    }

    fun setEnableNavigationBadge(enabled: Boolean) {
        localRevision.incrementAndGet()
        // 主题锁定:忽略
        if (ThemeLock.ENABLED) return
        repo.enableNavigationBadge = enabled
        _uiState.update { it.copy(enableNavigationBadge = enabled) }
    }

    fun setPageScale(scale: Float) {
        localRevision.incrementAndGet()
        repo.pageScale = scale
        _uiState.update { it.copy(pageScale = scale) }
    }

    fun setModuleDescriptionMaxLines(lines: Int) {
        localRevision.incrementAndGet()
        repo.moduleDescriptionMaxLines = lines
        _uiState.update { it.copy(moduleDescriptionMaxLines = lines) }
    }

    fun setEnableWebDebugging(enabled: Boolean) {
        localRevision.incrementAndGet()
        repo.enableWebDebugging = enabled
        _uiState.update { it.copy(enableWebDebugging = enabled) }
    }

    fun setUseSoftReboot(enabled: Boolean) {
        localRevision.incrementAndGet()
        repo.useSoftReboot = enabled
        _uiState.update { it.copy(useSoftReboot = enabled) }
    }





}


