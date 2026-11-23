package com.maplesu.core.ui.viewmodel

import android.os.Build
import android.system.Os
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.maplesu.core.BuildConfig
import com.maplesu.core.KernelVersion
import com.maplesu.core.Natives
import com.maplesu.core.data.repository.SettingsRepository
import com.maplesu.core.data.repository.SettingsRepositoryImpl
import com.maplesu.core.getKernelVersion
import com.maplesu.core.ksuApp
import com.maplesu.core.ui.screen.home.HomeEntryFacts
import com.maplesu.core.ui.screen.home.HomeHiddenFacts
import com.maplesu.core.ui.screen.home.HomeUiState
import com.maplesu.core.ui.screen.home.HomeWorkModeSwitchRuntime
import com.maplesu.core.ui.screen.home.ManagerVersion
import com.maplesu.core.ui.screen.home.SystemInfo
import com.maplesu.core.ui.screen.home.asStealthKernelNotReady
import com.maplesu.core.ui.screen.home.getManagerVersion
import com.maplesu.core.ui.screen.home.homeEntryFacts
import com.maplesu.core.ui.screen.home.mergedOver
import com.maplesu.core.ui.screen.home.withUltraSafety
import com.maplesu.core.ui.screen.home.afterEnvironmentCheck
import com.maplesu.core.ui.screen.home.afterEnvironmentProbeFailure
import com.maplesu.core.ui.screen.home.checkingAfterSuMissing
import com.maplesu.core.ui.screen.home.asLiteHiddenNotInstalled
import com.maplesu.core.ui.security.HiddenFactsSignal
import com.maplesu.core.ui.security.HiddenStartupConfirm
import com.maplesu.core.ui.security.LiteHiddenRuntime
import com.maplesu.core.ui.util.checkNewVersion
import com.maplesu.core.ui.util.getSELinuxStatusRaw
import com.maplesu.core.ui.util.peekCachedSELinuxStatus
import com.maplesu.core.ui.util.module.LatestVersionInfo
import com.maplesu.core.ui.util.resolveDeviceName
import dev.maplesu.manager.litehidden.HomeLiteHintUpdate
import dev.maplesu.manager.litehidden.HomeLiteInstalledHintStore
import dev.maplesu.manager.litehidden.LiteStartupFacts
import dev.maplesu.manager.litehidden.LiteStartupStatusPolicy
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.EnvironmentProbeResult
import dev.maplesu.manager.workmode.EnvironmentProbeRunner
import dev.maplesu.manager.workmode.boot.ModeBootstrap
import dev.maplesu.manager.workmode.detect.EnvironmentHomeProjection
import dev.maplesu.manager.workmode.detect.ProcBootIdSource
import dev.maplesu.manager.workmode.detect.SuReadiness
import dev.maplesu.manager.workmode.detect.probes.EnvironmentCheckProduction
import dev.maplesu.manager.workmode.detect.probes.EnvironmentProbesAdapter
import com.maplesu.core.ui.screen.home.HomeEnvironmentFailure
import dev.maplesu.manager.workmode.storage.SettingsPrefsPolicy
import com.maplesu.core.ui.util.KsuCli
import dev.maplesu.manager.runtime.MapleSuRuntime
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.ultra.UltraKeyPairedStore

/*
 * P0（Lite 模式启动状态）两个时序常量：
 *  - 模式判定是**两段式**的（阶段二约 2.5s 后才 activate）。未判定窗口里 root 探测本身被
 *    启动门控挡住，这一次失败不能当"Lite 启动失败"，所以要等判定落定后再判；
 *  - 真失败 → 有限重试。root shell 自带 3s 起步的重建退避（见 KsuCli），
 *    所以重试间隔按 3s、6s 递增，别在冷却期里空转。
 */
private const val LITE_MODE_SETTLE_TIMEOUT_MS = 8_000L
private const val LITE_ROOT_RETRY_STEP_MS = 3_000L

/** 等模式激活后重算一次入口事实的上限；超时后由回前台/切页继续兜底。 */
private const val ENTRY_FACTS_MODE_WAIT_MS = 15_000L
private const val HOME_ENVIRONMENT_PROBE_TIMEOUT_MS = 8_000L
private val homeEnvironmentProbeRunner = EnvironmentProbeRunner("maplesu-home-environment")
private val homeDeviceNameProbeRunner = EnvironmentProbeRunner("maplesu-home-device-name")
private val homeSELinuxProbeRunner = EnvironmentProbeRunner("maplesu-home-selinux")

class HomeViewModel(
    private val settingsRepo: SettingsRepository = SettingsRepositoryImpl()
) : ViewModel() {

    // ⚠️ 2026-09-19 修(启动首帧:ANR + 闪退):
    //    这里以前是 `MutableStateFlow(buildState())`。HomeViewModel 是**在主线程上构造**的,
    //    而 buildState() 会同步去建 root shell(最多 2 个候选、每次探测超时 1.5s)
    //    和 SELinux shell → 首帧白屏甚至 ANR;里面任何一处抛异常还会直接闪退。
    //    现在:首帧只算**不碰 shell / JNI** 的本地快照,
    //    root 和普通信息分别在后台读取,各自完成后刷新现有 UI 字段。
    //    界面结构、字段、默认值展示方式都不变,只是「有没有 root / SELinux 状态」
    //    这两个字段先显示默认值(false / Unknown),后台算完自动刷新。
    //
    // ⚠️ 2026-10-30 修(进 App 先闪「未安装」):
    //    首帧把 `modeSettled` 传进去(= `ModeRuntime.active != null`)。模式没判定时
    //    `buildState` 会先读**持久化 last-known 种子**(`home_lite_installed_hint`,中性 prefs,
    //    一次很小的读):命中 → 首帧直接「已安装」;没命中 → `litePending` = loading 占位,
    //    **不再**先渲染「未安装」。判定落定(activate / 8s 超时)后由后台那次重算纠正。
    private val _uiState = MutableStateFlow(
        buildState(probeRoot = false, modeSettled = ModeRuntime.active != null)
            .state
            .copy(environmentChecking = true),
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // 组合期不读 JNI / prefs / 文件：首页入口的事实都在这里。
    //
    // 首帧快照只含隐藏事实（内存 + 上面 buildState 已加载的中性 prefs 种子），入口一律不可见；
    // 隐藏优先，所以"还没读到事实"永远不会露出入口。
    private val _entryFacts = MutableStateFlow(HomeEntryFacts.beforeFirstRead(readHiddenFacts()))
    val entryFacts: StateFlow<HomeEntryFacts> = _entryFacts.asStateFlow()
    private val entryFactsTick = MutableStateFlow(0L)

    /**
     * 环境探测按轮次排队；普通信息读取不占用这条队列。
     *
     * init 的两段探测和 [refresh] 以前并行跑、后写者赢：模式判定前开始的慢探测可能晚于判定后的
     * 结果落地，把正确状态盖回旧状态（界面一会对一会不对）。现在一次只跑一轮，且每轮在持锁后才
     * 读 `ModeRuntime.active`。publishMutex 只登记轮次和发布完成的结果。
     */
    private val environmentMutex = Mutex()
    private val publishMutex = Mutex()
    private val refreshFlight = HomeRefreshSingleFlight()
    private var publishedDetectionRevision = -1L
    private var environmentOwner = 0L
    private var informationOwner = 0L
    private var deviceNameInformationJob: Job? = null
    private var selinuxInformationJob: Job? = null
    private var informationContext: HomeInformationOwner? = null

    private data class HomeStateRead(val state: HomeUiState, val readSELinux: Boolean)
    private data class HomeInformationOwner(
        val id: Long,
        val revision: Long,
        val activation: WorkMode?,
        val bootId: String,
    )

    private fun HomeInformationOwner.isCurrent(currentOwner: Long): Boolean =
        id == currentOwner && revision == ModeRuntime.detectionRevision.value &&
            activation == ModeRuntime.active

    init {
        viewModelScope.launch {
            var previous = UltraEnvironmentProbe.current()
            var recovery: Job? = null
            UltraEnvironmentProbe.states.collect { state ->
                _uiState.update { it.withCurrentUltraSafety() }
                if (state == UltraEnvironmentProbe.State.Fault || state == UltraEnvironmentProbe.State.NoKey ||
                    state == UltraEnvironmentProbe.State.NotInstalled) recovery?.cancel()
                if (state == UltraEnvironmentProbe.State.Running && previous != state &&
                    ModeRuntime.active != WorkMode.KSU && recovery?.isActive != true) {
                    recovery = viewModelScope.launch {
                        if (ModeRuntime.active == null) {
                            ModeBootstrap.retryEnvironmentDetection()
                            ModeRuntime.awaitDetectionSettled(LITE_MODE_SETTLE_TIMEOUT_MS)
                        }
                        // A recovered SDK observation releases safety, but real root
                        // and page capabilities still need a fresh successful read.
                        probeAndPublish(rootProbeSettled = ModeRuntime.detectionComplete,
                            modeSettled = ModeRuntime.detectionComplete)
                    }
                }
                previous = state
            }
        }
        // 隐藏信号要尽快生效：不走主线程调度，主线程忙时入口也能先收起。
        // collectLatest 保证新信号会作废正在进行的读取，迟到的"可见"结果写不进来。
        viewModelScope.launch(Dispatchers.Default) {
            merge(entryFactsTick, HiddenFactsSignal.revision, dev.maplesu.manager.workmode.ui.WorkModeSwitchSession.pendingFlow.map { if (it) 1L else 0L }).collectLatest { publishEntryFacts() }
        }
        if (ModeRuntime.active == null) {
            viewModelScope.launch {
                ModeRuntime.awaitActivated(ENTRY_FACTS_MODE_WAIT_MS)
                refreshEntryFacts()
            }
        }
        viewModelScope.launch {
            ModeRuntime.detectionRevision.drop(1).collect {
                probeAndPublish(rootProbeSettled = true, modeSettled = ModeRuntime.detectionComplete,
                    reuseCompletedDetection = true)
            }
        }
        // Ordinary information can start when the backend activates; it does not
        // wait for a queued Root/environment refresh or for detection to settle.
        viewModelScope.launch {
            if (ModeRuntime.active == null) ModeRuntime.awaitActivated(LITE_MODE_SETTLE_TIMEOUT_MS)
            refreshInformation()
        }
        // 后台补上 root / SELinux / Ultra 探测(IO 线程,不再堵主线程)。
        // 算不出来就保持首帧的默认值,绝不让异常冒出去。
        viewModelScope.launch {
            // Render the local checking state immediately; probe once after the backend is known.
            if (ModeRuntime.active == null) ModeRuntime.awaitDetectionSettled(LITE_MODE_SETTLE_TIMEOUT_MS)
            var state = probeAndPublish(rootProbeSettled = true, modeSettled = ModeRuntime.detectionComplete,
                reuseCompletedDetection = true)
            refreshUpdate(state.checkUpdateEnabled)

            // P0：非 Lite（KSU / 判定落定后仍无任何 Lite 证据）→ 完全沿用原行为，不做追加探测。
            if (!state.liteInstalled) return@launch

            // P0：确实失败 → 有限重试；成功的那一次会把 liteRootFailure 清掉（提示行消失）。
            var attemptsDone = 0
            while (
                attemptsDone < LiteStartupStatusPolicy.ROOT_RETRY_ATTEMPTS &&
                (LiteStartupStatusPolicy.shouldRetryRoot(
                    liteInstalled = state.liteInstalled,
                    liteRootFailure = state.liteRootFailure,
                    attemptsDone = attemptsDone,
                ) || (ModeRuntime.active == WorkMode.LITE && LiteHiddenRuntime.needsRealRead()))
            ) {
                attemptsDone++
                delay(LITE_ROOT_RETRY_STEP_MS * attemptsDone)
                state = probeAndPublish(rootProbeSettled = true, modeSettled = ModeRuntime.detectionComplete)
            }
        }
    }

    /** Every probe ends in a visible result; failed reads must not preserve the first-frame spinner. */
    private suspend fun probeAndPublish(
        rootProbeSettled: Boolean,
        modeSettled: Boolean = ModeRuntime.active != null,
        showChecking: Boolean = false,
        reuseCompletedDetection: Boolean = false,
    ): HomeUiState = coroutineScope {
        refreshInformation()
        environmentMutex.withLock {
        val revision = ModeRuntime.detectionRevision.value
        if (reuseCompletedDetection && publishedDetectionRevision == revision &&
            ModeRuntime.detectionComplete) return@withLock _uiState.value
        val local = buildState(probeRoot = false, rootProbeSettled = false, modeSettled = modeSettled)
        val bootId = runCatching { ProcBootIdSource.read() }.getOrDefault("")
        val owner = publishMutex.withLock {
            HomeInformationOwner(++environmentOwner, revision, ModeRuntime.active, bootId).also {
                _uiState.update { previous -> previous.copy(
                    environmentChecking = showChecking || previous.environmentChecking,
                    environmentFailure = if (showChecking) null else previous.environmentFailure,
                ) }
            }
        }
        val checked = homeEnvironmentProbeRunner.run(HOME_ENVIRONMENT_PROBE_TIMEOUT_MS) {
            // 确认窗口：上次已知隐藏 ON 且本进程还没真实读过 → 先读一次（root 通道）。
            // 读完再构建状态，避免"先画正在运行、过一会翻未安装"的自曝窗口。
            runCatching { HiddenStartupConfirm.ensureConfirmedOnce(ksuApp) }
            buildState(
                probeRoot = true,
                rootProbeSettled = rootProbeSettled,
                modeSettled = modeSettled,
            )
        }
        currentCoroutineContext().ensureActive()
        // Activation can complete while the worker builds its old snapshot.
        // Use current local facts until the queued detection refresh observes Root again.
        val detectionChanged = revision != ModeRuntime.detectionRevision.value
        val state = if (detectionChanged) {
            resolvedFailure(HomeEnvironmentFailure.BUSY, "detection_revision_changed")
        } else when (checked) {
            is EnvironmentProbeResult.Completed -> {
                checked.value.state.afterEnvironmentCheck(
                    modeResolved = ModeRuntime.active != null,
                    detectionComplete = ModeRuntime.detectionComplete,
                    detectionFailure = ModeRuntime.detectionFailure,
                    detectedState = ModeRuntime.latestDetection?.state,
                )
            }
            EnvironmentProbeResult.TimedOut -> resolvedFailure(
                HomeEnvironmentFailure.TIMED_OUT, "timeout")
            EnvironmentProbeResult.Busy -> resolvedFailure(
                HomeEnvironmentFailure.BUSY, "busy")
            is EnvironmentProbeResult.Failed -> {
                Log.w(TAG, "Home environment check failed", checked.cause)
                resolvedFailure(HomeEnvironmentFailure.FAILED, checked.cause?.javaClass?.simpleName ?: "failed")
            }
        }
        publishState(state, owner)
        if (!local.readSELinux && checked is EnvironmentProbeResult.Completed && checked.value.readSELinux) {
            refreshInformation(confirmedSELinuxOwner = owner)
        }
        if (checked is EnvironmentProbeResult.Completed && ModeRuntime.detectionComplete &&
            owner.isCurrent(environmentOwner)) publishedDetectionRevision = revision
        _uiState.value
    } }

    private suspend fun refreshInformation(confirmedSELinuxOwner: HomeInformationOwner? = null) {
        if (ModeRuntime.active == null) return
        val revision = ModeRuntime.detectionRevision.value
        val activation = ModeRuntime.active
        val bootId = runCatching { ProcBootIdSource.read() }.getOrDefault("")
        val local = buildState(probeRoot = false, rootProbeSettled = false,
            modeSettled = ModeRuntime.detectionComplete)
        val shouldReadSELinux = local.readSELinux || confirmedSELinuxOwner?.let {
            it.revision == revision && it.activation == activation && it.bootId == bootId
        } == true
        publishMutex.withLock {
            // A redundant Root refresh must not retire the still-running SELinux
            // read, publish Busy as Unknown, and discard the original completion.
            val currentOwner = informationContext
            val sameContext = currentOwner?.let {
                it.revision == revision && it.activation == activation && it.bootId == bootId
            } == true
            if (currentOwner != null && sameContext &&
                (deviceNameInformationJob?.isActive == true || selinuxInformationJob?.isActive == true)) {
                // A completed environment read can make SELinux readable while
                // the device-name read is still active. Add only that producer.
                if (shouldReadSELinux && selinuxInformationJob == null) {
                    selinuxInformationJob = viewModelScope.launch { readSELinux(currentOwner) }
                }
                return
            }
            if (!sameContext) {
                val cached = runCatching { peekCachedSELinuxStatus() }.getOrNull() ?: "Unknown"
                _uiState.update { it.copy(systemInfo = it.systemInfo.copy(selinuxStatus = cached)) }
            }
            val owner = HomeInformationOwner(++informationOwner, revision, activation, bootId)
            informationContext = owner
            deviceNameInformationJob = viewModelScope.launch { readDeviceName(owner) }
            selinuxInformationJob = if (shouldReadSELinux) {
                viewModelScope.launch { readSELinux(owner) }
            } else null
        }
    }

    private suspend fun readDeviceName(owner: HomeInformationOwner) {
        val result = homeDeviceNameProbeRunner.run(HOME_ENVIRONMENT_PROBE_TIMEOUT_MS) { resolveDeviceName() }
        val name = (result as? EnvironmentProbeResult.Completed)?.value ?: Build.MODEL
        publishInformation(owner) { it.copy(deviceModel = name) }
    }

    private suspend fun readSELinux(owner: HomeInformationOwner) {
        val ultra = runCatching {
            UltraEnvironmentProbe.usesUltraBackend(ksuApp) || UltraEnvironmentProbe.isRunning()
        }.getOrDefault(false)
        val result = homeSELinuxProbeRunner.run(HOME_ENVIRONMENT_PROBE_TIMEOUT_MS) { getSELinuxStatusRaw() }
        val raw = (result as? EnvironmentProbeResult.Completed)?.value ?: "Unknown"
        // A Ultra reply is reusable only while the Checker's own identity/boot cache is current.
        val status = if (ultra && raw != "Unknown") {
            runCatching { peekCachedSELinuxStatus() }.getOrNull() ?: "Unknown"
        } else raw
        publishInformation(owner) { it.copy(selinuxStatus = status) }
    }

    private suspend fun publishInformation(
        owner: HomeInformationOwner,
        update: (SystemInfo) -> SystemInfo,
    ) {
        currentCoroutineContext().ensureActive()
        val sameBoot = runCatching { ProcBootIdSource.read() == owner.bootId }.getOrDefault(false)
        publishMutex.withLock {
            if (sameBoot && owner.isCurrent(informationOwner)) {
                _uiState.update { it.copy(systemInfo = update(it.systemInfo)) }
            }
        }
    }

    /** A failed observation keeps installation facts and exposes its actual failure. */
    private fun resolvedFailure(failure: HomeEnvironmentFailure, reason: String): HomeUiState {
        val mode = ModeRuntime.active
        Log.i(TAG, "home environment probe ${failure.name.lowercase()}/$reason: mode=${mode ?: "unresolved"}")
        // A slow Root read can fail after activation. Re-read only local facts so
        // an old not-installed card cannot survive a newly verified mode.
        val local = buildState(probeRoot = false, rootProbeSettled = false,
            modeSettled = ModeRuntime.detectionComplete).state
        return _uiState.value.afterEnvironmentProbeFailure(local, failure).withCurrentUltraSafety()
    }

    private suspend fun publishState(state: HomeUiState, owner: HomeInformationOwner) {
        val hidden = readHiddenFacts()
        publishMutex.withLock {
            if (!owner.isCurrent(environmentOwner)) return@withLock
            if (hidden.suppressEntries) _entryFacts.value = HomeEntryFacts.beforeFirstRead(hidden)
            _uiState.update { previous ->
                state.copy(systemInfo = state.systemInfo.copy(
                    deviceModel = previous.systemInfo.deviceModel,
                    selinuxStatus = previous.systemInfo.selinuxStatus,
                )).mergedOver(previous).withCurrentUltraSafety()
            }
        }
        refreshEntryFacts()
    }

    /** Pure cache/prefs reads only; keep KSU safety and the screen's existing hidden projection. */
    private fun HomeUiState.withCurrentUltraSafety(): HomeUiState = withUltraSafety(
        UltraEnvironmentProbe.isSafeMode(ksuApp), ModeRuntime.active ?: environmentMode,
    )

    /** 重算首页入口事实（回前台、切页、探测完成时调用；幂等、不阻塞调用方）。 */
    fun refreshEntryFacts() {
        entryFactsTick.update { it + 1 }
    }

    private fun readHiddenFacts(): HomeHiddenFacts = HomeHiddenFacts(
        masking = runCatching { HiddenStartupConfirm.masking(ksuApp) }.getOrDefault(true),
        confirming = runCatching { HiddenStartupConfirm.current(ksuApp) }.getOrDefault(true),
    )

    /**
     * 隐藏事实先行：收紧方向不等 IO，当场发布。放行方向要等 IO 读完，
     * 读完再核对一次隐藏事实，读取期间隐藏生效时不把"可见"写出去。
     */
    private suspend fun publishEntryFacts() {
        val hidden = readHiddenFacts()
        if (hidden.suppressEntries || _uiState.value.environmentMode == null) {
            _entryFacts.value = HomeEntryFacts.beforeFirstRead(hidden)
            return
        }
        val facts = withContext(Dispatchers.IO) {
            homeEntryFacts(
                hidden = hidden,
                entry = { dev.maplesu.manager.workmode.ui.currentWorkModeEntryPolicy() },
                switch = { HomeWorkModeSwitchRuntime.current() },
            )
        }
        val latest = readHiddenFacts()
        _entryFacts.value = if (latest.suppressEntries || _uiState.value.environmentMode == null) {
            HomeEntryFacts.beforeFirstRead(latest)
        } else {
            facts.copy(hidden = latest)
        }
    }

    /** A verified restore must queue behind an older probe, never reuse or skip its masked result. */
    fun refreshAfterManagerRestore() {
        viewModelScope.launch {
            if (ModeRuntime.active == WorkMode.KSU) return@launch
            // Restored credentials can answer before a cached not-installed snapshot expires.
            // Refresh the SDK observation before consulting that snapshot's projection.
            withContext(Dispatchers.IO) {
                runCatching { UltraEnvironmentProbe.refresh(ksuApp, force = true) }
                    .onFailure { Log.w(TAG, "Restore environment probe failed; retaining observed state", it) }
            }
            checkEnvironmentPresence()
            if (ModeRuntime.active == null) {
                ModeBootstrap.retryEnvironmentDetection()
                ModeRuntime.awaitDetectionSettled(LITE_MODE_SETTLE_TIMEOUT_MS)
            }
            probeAndPublish(
                rootProbeSettled = ModeRuntime.detectionComplete,
                modeSettled = ModeRuntime.detectionComplete,
                showChecking = true,
            )
        }
    }

    fun refresh() {
        refreshFlight.launch(viewModelScope) {
            refreshInformation()
            ksuApp.requestUltraRecovery()
            // P0：刷新也要守"模式未判定"这条时序 —— 未判定窗口内 root 探测被启动门控挡住，
            // 那一次失败不是 Lite 启动失败（否则健康设备每次冷启动都会先闪一行失败提示）。
            // `modeSettled` 同步传：未判定窗口内没有种子命中时界面保持 loading，不回落成未安装。
            checkEnvironmentPresence()
            if (ModeRuntime.active == null) {
                ModeBootstrap.retryEnvironmentDetection()
                ModeRuntime.awaitDetectionSettled(LITE_MODE_SETTLE_TIMEOUT_MS)
            }
            val baseState = probeAndPublish(
                rootProbeSettled = ModeRuntime.detectionComplete,
                modeSettled = ModeRuntime.detectionComplete,
                showChecking = true,
            )
            refreshUpdate(baseState.checkUpdateEnabled)
        }
    }

    /** 已安装期间不打断展示；明确缺少 SU 后才显示检查进度，随后复用共享单飞检查器。 */
    private suspend fun checkEnvironmentPresence() = withContext(Dispatchers.IO) {
        val suPresent = runCatching { EnvironmentProbesAdapter(ksuApp).suPresent() }.getOrNull()
        if (suPresent == false) _uiState.update { it.checkingAfterSuMissing() }
        runCatching { EnvironmentCheckProduction.runner(ksuApp).check() }
            .onFailure { Log.w(TAG, "Silent environment check failed; retaining confirmed state", it) }
        Unit
    }

    private fun refreshUpdate(enabled: Boolean) {
        if (!enabled) return
        viewModelScope.launch {
            val latest = withContext(Dispatchers.IO) {
                runCatching { checkNewVersion() }.getOrNull()
            } ?: return@launch
            _uiState.update { if (it.checkUpdateEnabled) it.copy(latestVersionInfo = latest) else it }
        }
    }

    /**
     * @param probeRoot 是否读取 root 能力；设备名与 SELinux 由本轮独立读取。
     *                  主线程首帧传 false(绝不阻塞),IO 线程刷新传 true。
     * @param rootProbeSettled root 探测结论是否已经等到"模式判定落定"(见 [LiteStartupFacts])。
     *                  未判定窗口内传 false —— 那一次失败不作为 Lite 启动失败提示。
     * @param modeSettled 模式判定是否已经落定(activate 或 8s 超时)。
     *                    false = 判定中 → 无种子时界面渲染 loading 占位,不渲染「未安装」。
     */
    private fun buildState(
        probeRoot: Boolean,
        rootProbeSettled: Boolean = true,
        modeSettled: Boolean = ModeRuntime.active != null,
    ): HomeStateRead {
        val environment = runCatching { EnvironmentCheckProduction.store(ksuApp).snapshot() }.getOrNull()
        // 未安装记录跨 boot 后先重探；SU 能力不作为已安装模式展示。
        val currentBootId = runCatching { ProcBootIdSource.read() }.getOrDefault("")
        val liveReadiness = if (probeRoot) {
            runCatching { EnvironmentProbesAdapter(ksuApp).suReadiness() }
                .onFailure { Log.w(TAG, "su readiness probe failed; keeping previous state", it) }
                .getOrNull()
        } else {
            null
        }
        val projection = EnvironmentHomeProjection.decide(
            EnvironmentHomeProjection.Facts(
                snapshot = environment,
                currentBootId = currentBootId,
                liveReadiness = liveReadiness,
            ),
        )
        // 模式门控（主文档 §3.3）：MapleSuRuntime/KsuCli/Ultra SDK 都是 A（LITE）专属路径；
        // KSU/未判定不得起 Lite 一次性 root 进程，也不得调用 Ultra SDK。
        if (probeRoot && !projection.confirmedNotInstalled) {
            dev.maplesu.manager.workmode.ModeRuntime.runIfLite {
                // Ultra 环境探测只在 IO 线程(probeRoot = true)上跑;主线程首帧只读缓存,绝不碰 JNI。
                val ultraRunning = runCatching {
                    UltraEnvironmentProbe.refresh(ksuApp, force = false) == UltraEnvironmentProbe.State.Running
                }
                    .onFailure { Log.w(TAG, "系统环境探测失败,保持缓存值: ", it) }
                    .getOrDefault(false)
                if (!ultraRunning && !UltraEnvironmentProbe.usesUltraBackend(ksuApp) &&
                    !UltraEnvironmentProbe.isSafeMode(ksuApp) && MapleSuRuntime.refresh(ksuApp)) {
                    KsuCli.onRuntimeReady()
                }
            }
        }
        val ultraEnvironmentRunning =
            ModeRuntime.active != WorkMode.KSU && UltraEnvironmentProbe.currentSnapshot(ksuApp).state == UltraEnvironmentProbe.State.Running
        // 本次 SDK 已确认运行时，启动早期的未安装快照不再是当前结论。
        val ultraSafeMode = UltraEnvironmentProbe.isSafeMode(ksuApp)
        val ultraKernelVerified = UltraEnvironmentProbe.isKernelRootVerified(ksuApp)
        val environmentMode = if (ultraEnvironmentRunning || ultraSafeMode || ultraKernelVerified) WorkMode.LITE
            else ModeRuntime.active ?: projection.cachedMode
        val confirmedNotInstalled = projection.confirmedNotInstalled && !ultraEnvironmentRunning && !ultraSafeMode && !ultraKernelVerified
        // ⚠️ 2026-09-19 修:下面每一个 runCatching 都是"防闪退" ——
        //    Natives 是 JNI(可能 UnsatisfiedLinkError),读设置走磁盘,Os.uname 也可能抛。
        //    以前任何一个抛了,HomeViewModel 的构造 / 刷新协程就炸;现在各自兜底,
        //    最坏只是某个字段显示默认值,页面照样打开。
        val kernelVersion = runCatching { getKernelVersion() }
            .getOrElse { KernelVersion(-1, -1, -1) }
        val isManager = if (probeRoot) runCatching { Natives.isManager }.getOrDefault(false) else false
        // isManager 为 false 有两种原因,不能混着提示(详见 HomeUiState.managerNotRecognized 的注释):
        //   ① 隐身模式   → stealthState() 能读到 1(内核仍然认我们)
        //   ② 内核没认主 → 读不到(返回 -1)→ 只要重启一次设备即可,不要引导用户去刷机
        val observedStealth = if (probeRoot) runCatching { Natives.stealthState() }.getOrDefault(-1) else -1
        if (probeRoot && environmentMode == WorkMode.KSU) {
            HiddenStartupConfirm.rememberNormalState(ksuApp, observedStealth)
        }
        val stealthState = if (observedStealth in 0..1) observedStealth else if (environmentMode == WorkMode.KSU) {
            HiddenStartupConfirm.normalState(ksuApp)
        } else -1
        val ksuVersionRaw = if (probeRoot) runCatching { Natives.version }.getOrDefault(-1) else -1
        val managerNotRecognized = !isManager && ksuVersionRaw >= 0 && stealthState != 1
        // 隐身模式(内核 stealthState == 1):内核其实仍然认我们,只是不上报 MANAGER 标志。
        // 界面要把首页伪装成「未安装」那一支(详见 HomeUiState.asStealthKernelNotReady)。
        val stealthOn = stealthState == 1
        val kernelUapiRaw = if (probeRoot) runCatching { Natives.kernelUAPIVersion }.getOrDefault(0) else 0
        val managerUapiRaw = if (probeRoot) runCatching { Natives.managerUAPIVersion }.getOrDefault(0) else 0
        val ksuVersion = if (isManager) ksuVersionRaw else null
        val kernelUAPIVersion = if (isManager) kernelUapiRaw else null
        val managerUAPIVersion = managerUapiRaw
        // Lite patches the kernel binary; KSU's GKI/LKM installation modes do not describe it.
        val lkmMode: Boolean? = null
        // 下面这两个都要起 shell(最慢、最可能抛)→ 只在 probeRoot = true(IO 线程)时做
        // Only the actual standard-SU verdict proves Manager SU; SDK Root is a separate capability.
        val isRootAvailable = probeRoot && !confirmedNotInstalled && liveReadiness == SuReadiness.USABLE

        val managerVersion = runCatching { getManagerVersion(ksuApp) }
            .getOrElse { ManagerVersion("未知", 0L) }

        // P0：Lite 已安装判据（首帧即已安装，不等 Ultra 探测/root）。
        // 证据 = 模式已判定为 LITE、本地 Lite 凭据（no-backup），或启动期六态判定已认出 A 态
        //（工作模式页显示"已配对/已安装（未绑定）"用的就是它 —— 首页必须与设置页一致，
        //  否则模式激活失败时会出现"设置页已配对、首页一直未安装"，即本次 P0 缺陷）。
        // ⚠️ 证据只在"不是 KSU"时参与：KSU 模式下残留的 Lite 凭据/判定痕迹不得把首页翻成
        //    Lite 已安装（用户要求 4：KSU 行为不变），见 LiteStartupStatusPolicy.liteInstalled。
        val activeMode = ModeRuntime.active
        val liteMode = activeMode == WorkMode.LITE
        val ksuMode = activeMode == WorkMode.KSU
        val liteEvidence = runCatching {
            SettingsPrefsPolicy.hasLiteEvidence(ksuApp.noBackupFilesDir)
        }.getOrDefault(false)
        // 判定痕迹是纯内存读（ModeRuntime.latestDetection），不 IO、不起进程，主线程首帧也安全。
        val detectedState = runCatching { ModeRuntime.latestDetection?.state }.getOrNull()

        // P0（闪烁修复）：首帧同步读**持久化 last-known 种子**。
        // 只在判定未落定时读 —— 判定已落定就以本次结果为准，种子一律不参与
        //（LiteStartupStatusPolicy.hasInstalledEvidence 里也有同一道闸）。
        // 是一次很小的 prefs 读（中性 prefs，只查三个键），用户明确要求"首帧同步读取"；
        // 读失败/损坏当"没有种子"，绝不因此影响首帧。
        val lastKnownInstalled = if (!modeSettled && projection.cachedMode == WorkMode.LITE) {
            runCatching { HomeLiteInstalledHintStore.isInstalled(ksuApp) }.getOrDefault(false)
        } else {
            false
        }
        // Ultra「配对」标记是 prefs 读 → 只在 IO 线程取。主线程首帧的 Ultra 缓存本来还是 Unknown
        //（UltraEnvironmentProbe 首帧只读缓存、不碰 JNI），这个值也用不上。
        val ultraPaired = if (probeRoot) {
            runCatching { UltraKeyPairedStore.isPaired(ksuApp) }.getOrDefault(false)
        } else {
            false
        }
        val liteFacts = LiteStartupFacts(
            liteMode = liteMode,
            liteEvidence = liteEvidence,
            ultraEnvironmentRunning = ultraEnvironmentRunning,
            rootAvailable = isRootAvailable,
            probeRoot = probeRoot,
            stealthOn = stealthOn,
            ksuMode = ksuMode,
            rootProbeSettled = rootProbeSettled && liveReadiness != null && liveReadiness != SuReadiness.UNKNOWN,
            detectedState = detectedState,
            ultraPaired = ultraPaired,
            lastKnownInstalled = lastKnownInstalled,
            modeSettled = modeSettled,
            // 既有已安装通道（KSU 认主 / Ultra Running）→ 不需要 Lite 判定，也不该进 loading。
            nonLiteInstalled = ksuVersion != null || ultraEnvironmentRunning,
        )
        val liteStatus = LiteStartupStatusPolicy.evaluate(liteFacts)

        // P0：结果确定后把 last-known 种子落盘（写/清都在 IO 线程，首帧绝不写磁盘）。
        // 隐身由策略侧判成 NONE（不留新痕迹）；任何异常都只记日志，不影响首页。
        if (probeRoot && !confirmedNotInstalled) {
            runCatching { persistLiteHint(liteFacts) }
                .onFailure { Log.w(TAG, "Ultra 已安装种子更新失败(忽略): ", it) }
        }

        val state = HomeUiState(
            kernelVersion = kernelVersion,
            ksuVersion = ksuVersion,
            lkmMode = lkmMode,
            isLkmBundled = lkmMode == true &&
                runCatching { Natives.isLkmBundled }.getOrDefault(false),
            isManager = isManager,
            managerNotRecognized = managerNotRecognized,
            isManagerPrBuild = BuildConfig.IS_PR_BUILD,
            isKernelPrBuild = probeRoot && runCatching { Natives.isPrBuild }.getOrDefault(false),
            requiresNewKernel = isManager && managerUapiRaw > kernelUapiRaw,
            // 内核 UAPI 比管理器新(5 -> 6)时不再报"需要更新管理器":
            // UAPI 升级是向后兼容的,旧管理器可以直接用;否则内核先行更新就会误报,
            // 还会顺带把界面降级(见 isFullFeatured)。
            requiresNewManager = false,
            kernelUAPIVersion = kernelUAPIVersion,
            managerUAPIVersion = managerUAPIVersion,
            isRootAvailable = isRootAvailable,
            rootReadiness = liveReadiness,
            isSafeMode = ultraSafeMode || probeRoot && runCatching { Natives.isSafeMode }.getOrDefault(false),
            isLateLoadMode = probeRoot && runCatching { Natives.isLateLoadMode }.getOrDefault(false),
            checkUpdateEnabled = runCatching { settingsRepo.checkUpdate }.getOrDefault(false),
            latestVersionInfo = LatestVersionInfo(),
            currentManagerVersionCode = managerVersion.versionCode,
            systemInfo = SystemInfo(
                kernelVersion = runCatching { Os.uname().release }.getOrDefault("未知"),
                managerVersion = "${managerVersion.versionName} (${managerVersion.versionCode})",
                deviceModel = Build.MODEL,
                fingerprint = Build.FINGERPRINT,
                selinuxStatus = "Unknown",
                seccompStatus = runCatching {
                    Os.prctl(21 /* PR_GET_SECCOMP */, 0, 0, 0, 0)
                }.getOrDefault(-1),
            ),
            liteInstalled = liteStatus.liteInstalled,
            environmentMode = environmentMode,
            liteRootFailure = liteStatus.liteRootFailure,
            // P0：判定中且无任何证据 → 界面画 loading 占位（绝不先渲染「未安装」）。
            litePending = if (!probeRoot && !liteStatus.liteInstalled) true else liteStatus.litePending,
            // 2026-10-31：隐藏/寄生的「确认窗口」——在寄生结果与运行环境都确认前，
            // 首页只画既有提示（正在确认 MapleSU 运行环境，请稍候），不先报「正在运行」。
            hiddenConfirming = runCatching {
                HiddenStartupConfirm.current(ksuApp)
            }.getOrDefault(true),
        )

        // P0：Lite 已安装 → 追加"已安装/正常"语义（首帧即可）；root 失败只置提示行，
        // **绝不**把已安装回退成未安装（KSU/未判定保持原判定）。
        val liteForced = if (liteStatus.liteInstalled) {
            state.copy(
                isManager = true,
                managerNotRecognized = false,
                ksuVersion = state.ksuVersion ?: if (probeRoot) runCatching { Natives.version }.getOrDefault(ksuVersionRaw) else ksuVersionRaw,
                liteInstalled = true,
                liteRootFailure = liteStatus.liteRootFailure,
                // 已安装 ⇒ 一定不在 loading（策略侧本来也不会同时给，这里再钉一次不变量）。
                litePending = false,
            )
        } else {
            state
        }

        // Ultra Running 补齐安装/管理器事实；root 能力仍只采用上面的实际命令探测结果。
        val effectiveState = if (ultraEnvironmentRunning || ultraSafeMode) {
            liteForced.copy(
                ksuVersion = if (probeRoot) runCatching { Natives.version }.getOrDefault(ksuVersionRaw) else ksuVersionRaw,
                isManager = true,
                managerNotRecognized = false,
                // Ultra Running 就是"已安装"的既有通道 → 不进 loading（否则会从绿卡退化成转圈）。
                litePending = false,
            )
        } else {
            liteForced
        }

        // 🔒 隐身:把整个状态归一化成「未安装」那一支(照抄没刷内核时首页的样子)。
        //    界面层一行不用改就会走进那条已存在的分支(见 HomeUiState.asStealthKernelNotReady)。
        //    顺手把"更新检查"也关掉 —— 状态里 checkUpdateEnabled 为 false 时
        //    refresh() 不会再联网查版本,伪装期少一条痕迹。
        val projected = if (confirmedNotInstalled) {
            // 后台已确认 SU 和 Ultra 都不存在，旧进程模式/种子不再参与首页展示。
            effectiveState.asLiteHiddenNotInstalled()
        } else if (stealthOn) {
            // 隐身伪装：不暴露 Lite 已安装/失败提示（与伪装目标一致）。
            // 同时把 isManager 还原成内核给的**原始**值：上面的 Lite 强制/Ultra 兜底都会把它改成
            // true，而 asStealthKernelNotReady() 按约定不动 isManager —— 不还原就会漏馅
            // （底栏 / 导航 / 闸门都以 isManager 为准）。
            // litePending 也一起清：隐身时画 loading 占位等于暴露"正在判定 Lite 状态"。
            effectiveState.asStealthKernelNotReady().copy(
                isManager = isManager,
                liteInstalled = false,
                liteRootFailure = false,
                litePending = false,
            )
        } else {
            effectiveState
        }
        return HomeStateRead(projected, readSELinux = !confirmedNotInstalled)
    }

    /**
     * P0：维护"上次已知 Lite 已安装"种子（只在 IO 线程调用）。
     *
     * 写/清/不动的判据全在 [LiteStartupStatusPolicy.hintUpdate]（纯函数、JVM 可测）：
     * - 模式或 Ultra 结果确定 → 写（证据类型：模式 / 六态 A 态 / 本地凭据 / Ultra 配对）；
     * - 判定落定却没有任何证据（含 KSU）→ 清，避免下次冷启动继续误报已安装；
     * - 判定中且无证据 / 隐身 → 不动。
     * 只写布尔 + 时间戳 + 证据枚举名，**不写**凭据、key、路径或包名。
     */
    private fun persistLiteHint(facts: LiteStartupFacts) {
        when (LiteStartupStatusPolicy.hintUpdate(facts)) {
            HomeLiteHintUpdate.WRITE -> {
                val evidence = LiteStartupStatusPolicy.hintEvidence(facts) ?: return
                HomeLiteInstalledHintStore.write(ksuApp, evidence)
            }

            HomeLiteHintUpdate.CLEAR -> HomeLiteInstalledHintStore.clear(ksuApp)
            HomeLiteHintUpdate.NONE -> Unit
        }
    }
}
