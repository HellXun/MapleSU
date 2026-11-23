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
import com.maplesu.core.ui.screen.settings.NormalHiddenUi
import com.maplesu.core.ui.screen.settings.NormalHiddenFailure
import com.maplesu.core.ui.security.Stealth
import com.maplesu.core.ui.theme.ColorMode
import com.maplesu.core.ui.util.NavIcons
import com.maplesu.core.ui.util.StatusDecoration
import com.maplesu.core.ui.util.ThemeLock
import com.maplesu.core.ui.util.WebAdminCli
import dev.maplesu.manager.workmode.storage.SettingsPrefsRouter
import dev.maplesu.manager.litehidden.NormalHiddenController
import dev.maplesu.manager.litehidden.NormalHiddenEnv
import dev.maplesu.manager.liteweb.RecoveryWebUiControl
import dev.maplesu.manager.liteweb.RecoveryWebUiState
import dev.maplesu.manager.workmode.ui.BFeatureCards
import dev.maplesu.manager.workmode.ui.BFeaturePolicy
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
    private var normalHiddenController: NormalHiddenController? = null
    private var normalHiddenUrl: String? = null
    private var normalHiddenRevision = 0L
    private var normalHiddenPreparation: Job? = null

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
            normalHidden = NormalHiddenUi(),
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
                    !hiddenCard.hidden && !current.bFeatures.stealth.enabled,
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
            val suCompatStatus = ""
            val isSuEnabled = false
            val suCompatMode = 2
            val ksuExtras: KsuSettingsExtras? = null
            val bFeatures: BFeatureCards = BFeatureCards.NONE

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

            val isLkmMode = false

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
                    bFeatures = if (acceptPreferences) bFeatures else it.bFeatures,
                    liteHiddenCard = if (hiddenMasked) LiteHiddenCardUi.HIDDEN else liteHiddenCard,
                    // 隐藏生效（Ultra Lite 隐藏 / Normal KSU stealth 明确 ON）后自检入口消失。
                    showWebUiSelfCheck = policy.shows(SettingsSection.WEBUI_SELF_CHECK) && !hiddenMasked &&
                        !liteHiddenCard.hidden && !bFeatures.stealth.enabled,
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
                    suCompatStatus = if (acceptPreferences) suCompatStatus else it.suCompatStatus,
                    suCompatMode = if (acceptPreferences) suCompatMode else it.suCompatMode,
                    isSuEnabled = if (acceptPreferences) isSuEnabled else it.isSuEnabled,
                    useSoftReboot = if (acceptPreferences) useSoftReboot else it.useSoftReboot,
                    isKernelUmountEnabled = if (!acceptPreferences) it.isKernelUmountEnabled else if (false) {
                        ksuExtras?.kernelUmount ?: prev.isKernelUmountEnabled
                    } else {
                        false
                    },
                    isSelinuxHideEnabled = if (!acceptPreferences) it.isSelinuxHideEnabled else if (false) {
                        ksuExtras?.selinuxHide ?: prev.isSelinuxHideEnabled
                    } else {
                        false
                    },
                    isSulogEnabled = if (!acceptPreferences) it.isSulogEnabled else if (false) {
                        ksuExtras?.sulog ?: prev.isSulogEnabled
                    } else {
                        false
                    },
                    isAdbRootEnabled = if (!acceptPreferences) it.isAdbRootEnabled else if (false) {
                        ksuExtras?.adbRoot ?: prev.isAdbRootEnabled
                    } else {
                        false
                    },
                    isShellSuAdbOnlyEnabled = if (!acceptPreferences) it.isShellSuAdbOnlyEnabled else if (false) {
                        ksuExtras?.shellSuAdbOnly ?: prev.isShellSuAdbOnlyEnabled
                    } else {
                        false
                    },
                    isDefaultUmountModules = if (!acceptPreferences) it.isDefaultUmountModules else if (false) {
                        ksuExtras?.defaultUmount ?: prev.isDefaultUmountModules
                    } else {
                        false
                    },
                    isLkmMode = isLkmMode,
                    isLateLoadMode = ksuExtras?.lateLoad ?: prev.isLateLoadMode,
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
        if (dev.maplesu.manager.workmode.ModeRuntime.active != dev.maplesu.manager.workmode.WorkMode.KSU) return
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
                    if (dev.maplesu.manager.workmode.ModeRuntime.active != dev.maplesu.manager.workmode.WorkMode.KSU) {
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

    fun setStealthEnabled(enabled: Boolean) {
        if (enabled) {
            openNormalHidden()
            return
        }
        if (_uiState.value.normalHidden.busy) return
        val cards = _uiState.value.bFeatures
        if (!cards.stealth.show || !cards.stealth.operable) return
        applyToggle("stealth", enabled, { it.bFeatures.stealth.enabled }, { state, value ->
            state.copy(bFeatures = state.bFeatures.copy(stealth = state.bFeatures.stealth.copy(enabled = value)))
        }) { Stealth.setEnabledReporting(false) }
    }

    fun openNormalHidden() {
        val state = _uiState.value
        val card = state.bFeatures.stealth
        if (dev.maplesu.manager.workmode.ModeRuntime.active != dev.maplesu.manager.workmode.WorkMode.KSU ||
            !card.show || !card.operable || card.enabled || state.normalHidden.visible) return
        val revision = ++normalHiddenRevision
        val previousPreparation = normalHiddenPreparation
        _uiState.update { it.copy(normalHidden = NormalHiddenUi(visible = true, checkingWebUi = true,
            portText = state.normalHidden.portText)) }
        normalHiddenPreparation = viewModelScope.launch {
            try {
                previousPreparation?.join()
                if (revision != normalHiddenRevision || !_uiState.value.normalHidden.visible) return@launch
                val port = withContext(Dispatchers.IO) { NormalHiddenEnv.configuredPort() }
                if (revision != normalHiddenRevision || !_uiState.value.normalHidden.visible) return@launch
                _uiState.update { it.copy(normalHidden = it.normalHidden.copy(
                    checkingWebUi = false,
                    portText = port?.toString() ?: it.normalHidden.portText)) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("NormalHidden", "Configured-port probe failed", error)
            } finally {
                if (revision == normalHiddenRevision) _uiState.update {
                    it.copy(normalHidden = it.normalHidden.copy(checkingWebUi = false))
                }
            }
        }
    }

    fun setNormalHiddenPort(raw: String) {
        val current = _uiState.value.normalHidden
        val updated = current.withPort(raw)
        if (current == updated) return
        normalHiddenRevision++
        normalHiddenController?.cancel()
        normalHiddenUrl = null
        _uiState.update { it.copy(normalHidden = updated) }
    }

    fun prepareNormalHidden() {
        val ui = _uiState.value.normalHidden
        if (!ui.canPrepare || !ui.portValid ||
            dev.maplesu.manager.workmode.ModeRuntime.active != dev.maplesu.manager.workmode.WorkMode.KSU) return
        val revision = ++normalHiddenRevision
        val previousPreparation = normalHiddenPreparation
        normalHiddenUrl = null
        _uiState.update { it.copy(normalHidden = NormalHiddenUi(visible = true, preparing = true,
            webUiState = ui.webUiState, portText = ui.portText)) }
        normalHiddenPreparation = viewModelScope.launch {
            try {
                // Cancellation invalidates the session but does not interrupt root preparation mid-write.
                previousPreparation?.join()
                if (revision != normalHiddenRevision || !_uiState.value.normalHidden.visible) return@launch
                val controller = normalHiddenController ?: withContext(Dispatchers.IO) { NormalHiddenEnv.create(ksuApp) }
                if (revision != normalHiddenRevision || !_uiState.value.normalHidden.visible) {
                    controller?.cancel()
                    return@launch
                }
                normalHiddenController = controller
                val (result, webUiState) = withContext(Dispatchers.IO) {
                    val prepared = runCatching { controller?.prepare(ui.portText.toInt()) }.getOrNull()
                    prepared to if (prepared?.ok == true) RecoveryWebUiState.RUNNING else RecoveryWebUiControl().status().state
                }
                if (revision != normalHiddenRevision || !_uiState.value.normalHidden.visible) {
                    controller?.cancel()
                    return@launch
                }
                val ready = result?.ok == true && !result.recoveryUrl.isBlank() &&
                    dev.maplesu.manager.workmode.ModeRuntime.active == dev.maplesu.manager.workmode.WorkMode.KSU
                normalHiddenUrl = result?.recoveryUrl?.takeIf { ready }
                if (!ready) {
                    controller?.cancel()
                    android.util.Log.w("NormalHidden", "Preparation rejected: ${result?.code ?: "NORMAL_RECOVERY_UNAVAILABLE"}")
                }
                _uiState.update { it.copy(normalHidden = NormalHiddenUi(visible = true,
                    portText = ui.portText,
                    linkAvailable = ready,
                    webUiState = webUiState,
                    failure = if (ready) null else NormalHiddenFailure.PREPARE)) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                normalHiddenController?.cancel()
                normalHiddenUrl = null
                android.util.Log.w("NormalHidden", "Preparation failed", error)
            } finally {
                if (revision == normalHiddenRevision && _uiState.value.normalHidden.preparing) {
                    _uiState.update { it.copy(normalHidden = it.normalHidden.copy(
                        preparing = false, failure = NormalHiddenFailure.PREPARE)) }
                }
            }
        }
    }

    fun dismissNormalHidden() {
        if (_uiState.value.normalHidden.activating) return
        normalHiddenRevision++
        normalHiddenController?.cancel()
        normalHiddenUrl = null
        _uiState.update { it.copy(normalHidden = NormalHiddenUi(portText = it.normalHidden.portText)) }
    }

    fun copyNormalHiddenLink() {
        val ui = _uiState.value.normalHidden
        val url = normalHiddenUrl
        if (!ui.visible || ui.busy || ui.completed || !ui.linkAvailable || url.isNullOrBlank()) return
        val copied = WebAdminCli.copyUrl(ksuApp, url)
        _uiState.update { it.copy(normalHidden = it.normalHidden.copy(
            linkAccessed = it.normalHidden.linkAccessed || copied,
            failure = if (copied) null else NormalHiddenFailure.COPY)) }
        if (copied) Toast.makeText(ksuApp, R.string.work_mode_webadmin_copied, Toast.LENGTH_SHORT).show()
    }

    fun openNormalHiddenLink() {
        val ui = _uiState.value.normalHidden
        val url = normalHiddenUrl
        if (!ui.visible || ui.busy || ui.completed || !ui.linkAvailable || url.isNullOrBlank()) return
        val opened = WebAdminCli.openInBrowser(ksuApp, url) == null
        _uiState.update { it.copy(normalHidden = it.normalHidden.copy(
            linkAccessed = it.normalHidden.linkAccessed || opened,
            failure = if (opened) null else NormalHiddenFailure.OPEN)) }
    }

    fun saveNormalHiddenLink() {
        if (!_uiState.value.normalHidden.canSaveLink) return
        _uiState.update { it.copy(normalHidden = it.normalHidden.copy(linkSaved = true, failure = null)) }
    }

    fun confirmNormalHidden() {
        val controller = normalHiddenController ?: return
        if (!_uiState.value.normalHidden.canConfirm || normalHiddenUrl.isNullOrBlank() ||
            dev.maplesu.manager.workmode.ModeRuntime.active != dev.maplesu.manager.workmode.WorkMode.KSU) return
        val revision = ++normalHiddenRevision
        localRevision.incrementAndGet()
        activeToggleWrites.incrementAndGet()
        _uiState.update { it.copy(normalHidden = it.normalHidden.copy(activating = true, failure = null)) }
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { runCatching { controller.activate() }.getOrNull() }
            } finally {
                activeToggleWrites.decrementAndGet()
                localRevision.incrementAndGet()
            }
            if (revision != normalHiddenRevision) return@launch
            normalHiddenUrl = null
            if (result?.ok == true) {
                _uiState.update { it.copy(normalHidden = NormalHiddenUi(visible = true, completed = true,
                    portText = it.normalHidden.portText),
                    hiddenMasked = true, workModeEntry = WorkModeEntryPolicy.NONE,
                    showWebUiSelfCheck = false, bFeatures = it.bFeatures.copy(
                        stealth = it.bFeatures.stealth.copy(enabled = true))) }
            } else {
                controller.cancel()
                _uiState.update { it.copy(normalHidden = NormalHiddenUi(visible = true,
                    portText = it.normalHidden.portText,
                    failure = NormalHiddenFailure.ACTIVATE)) }
            }
        }
    }

    override fun onCleared() {
        normalHiddenRevision++
        normalHiddenController?.cancel()
        normalHiddenUrl = null
        super.onCleared()
    }

    fun setWebAdminEnabled(enabled: Boolean) {
        val cards = _uiState.value.bFeatures
        if (!cards.webAdmin.show || !cards.webAdmin.operable) return
        applyToggle("webAdmin", enabled, { it.bFeatures.webAdmin.enabled }, { state, value ->
            state.copy(bFeatures = state.bFeatures.copy(webAdmin = state.bFeatures.webAdmin.copy(enabled = value)))
        }) {
            if (WebAdminCli.setEnabled(enabled)) {
                repo.webAdminEnabled = enabled
                null
            } else ksuApp.getString(R.string.work_mode_webadmin_failed)
        }
    }

    /** 复制网页管理器访问地址（含一次性 key，只走内存，不进 state/日志）。 */
    fun copyWebAdminUrl() {
        viewModelScope.launch {
            val url = withContext(Dispatchers.IO) { runCatching { WebAdminCli.url() }.getOrDefault("") }
            val message = if (url.isBlank()) {
                R.string.work_mode_webadmin_failed
            } else if (WebAdminCli.copyUrl(ksuApp, url)) {
                R.string.work_mode_webadmin_copied
            } else {
                R.string.work_mode_webadmin_copy_failed
            }
            Toast.makeText(ksuApp, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** 用浏览器打开网页管理器（地址含 key）。 */
    fun openWebAdminUrl() {
        viewModelScope.launch {
            val url = withContext(Dispatchers.IO) { runCatching { WebAdminCli.url() }.getOrDefault("") }
            if (url.isBlank()) {
                Toast.makeText(ksuApp, R.string.work_mode_webadmin_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val error = WebAdminCli.openInBrowser(ksuApp, url)
            if (error != null) {
                Toast.makeText(ksuApp, error, Toast.LENGTH_LONG).show()
            }
        }
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

    fun setSuCompatMode(mode: Int) {
        if (mode != 0 && mode != 2) return
        val enabled = mode == 0
        applyToggle("suCompat", enabled, { it.isSuEnabled }, { state, value ->
            state.copy(isSuEnabled = value, suCompatMode = if (value) 0 else 2)
        }) {
            if (repo.setSuEnabled(enabled)) {
                repo.setSuCompatModePref(mode)
                if (!enabled) withContext(Dispatchers.Main) {
                    Toast.makeText(ksuApp, R.string.maplesu_su_off_keeps_delivered, Toast.LENGTH_LONG).show()
                }
                null
            } else ksuApp.getString(R.string.ui_polish_setting_failed)
        }
    }

    fun setKernelUmountEnabled(enabled: Boolean) = applyToggle("kernelUmount", enabled,
        { it.isKernelUmountEnabled }, { state, value -> state.copy(isKernelUmountEnabled = value) }) {
        if (repo.setKernelUmountEnabled(enabled)) { repo.execKsudFeatureSave(); null }
        else ksuApp.getString(R.string.ui_polish_setting_failed)
    }

    fun setSelinuxHideEnabled(enabled: Boolean) = applyToggle("selinuxHide", enabled,
        { it.isSelinuxHideEnabled }, { state, value -> state.copy(isSelinuxHideEnabled = value) }) {
        when (val result = repo.setSelinuxHideEnabled(enabled)) {
            0, -OsConstants.EAGAIN -> {
                repo.execKsudFeatureSave()
                withContext(Dispatchers.Main) {
                    val message = if (result == -OsConstants.EAGAIN) R.string.settings_selinux_hide_reboot_required
                        else R.string.settings_selinux_hide_applied
                    Toast.makeText(ksuApp, message, Toast.LENGTH_LONG).show()
                }
                null
            }
            else -> ksuApp.getString(R.string.ui_polish_setting_failed)
        }
    }

    fun setUseSoftReboot(enabled: Boolean) {
        localRevision.incrementAndGet()
        repo.useSoftReboot = enabled
        _uiState.update { it.copy(useSoftReboot = enabled) }
    }

    fun setSulogEnabled(enabled: Boolean) = applyToggle("sulog", enabled,
        { it.isSulogEnabled }, { state, value -> state.copy(isSulogEnabled = value) }) {
        if (repo.setSulogEnabled(enabled)) { repo.execKsudFeatureSave(); null }
        else ksuApp.getString(R.string.ui_polish_setting_failed)
    }

    fun setShellSuAdbOnlyEnabled(enabled: Boolean) = applyToggle("shellSuAdbOnly", enabled,
        { it.isShellSuAdbOnlyEnabled }, { state, value -> state.copy(isShellSuAdbOnlyEnabled = value) }) {
        if (repo.setShellSuAdbOnlyEnabled(enabled)) { repo.execKsudFeatureSave(); null }
        else ksuApp.getString(R.string.ui_polish_setting_failed)
    }

    fun setAdbRootEnabled(enabled: Boolean) = applyToggle("adbRoot", enabled,
        { it.isAdbRootEnabled }, { state, value -> state.copy(isAdbRootEnabled = value) }) {
        if (repo.setAdbRootEnabled(enabled)) { repo.execKsudFeatureSave(); null }
        else ksuApp.getString(R.string.ui_polish_setting_failed)
    }

    fun setDefaultUmountModules(enabled: Boolean) = applyToggle("defaultUmount", enabled,
        { it.isDefaultUmountModules }, { state, value -> state.copy(isDefaultUmountModules = value) }) {
        if (repo.setDefaultUmountModules(enabled)) null else ksuApp.getString(R.string.ui_polish_setting_failed)
    }

}


/**
 * W15 六项 KSU 设置行的聚合读取结果（04-ui-contract §3.4）。
 * 只在 B（能力位 `supportsKernelUmount` 等为 true）时构造；A 下为 null ⇒ 零新增 IO。
 */
private data class KsuSettingsExtras(
    val kernelUmount: Boolean,
    val selinuxHide: Boolean,
    val lateLoad: Boolean,
    val sulog: Boolean,
    val adbRoot: Boolean,
    val shellSuAdbOnly: Boolean,
    val defaultUmount: Boolean,
)
