package dev.maplesu.manager.workmode.boot

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.maplesu.core.BuildConfig
import dev.maplesu.manager.ultrakernel.UltraKernelCredential
import dev.maplesu.manager.workmode.ModeActivation
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.EnvironmentProbeResult
import dev.maplesu.manager.workmode.EnvironmentProbeRunner
import dev.maplesu.manager.workmode.detect.DefaultModeDetector
import dev.maplesu.manager.workmode.detect.DetectionResult
import dev.maplesu.manager.workmode.detect.EnvironmentDetectionProjection
import dev.maplesu.manager.workmode.detect.probes.AndroidRootShellOpener
import dev.maplesu.manager.workmode.detect.probes.BootIdSourceAdapter
import dev.maplesu.manager.workmode.detect.probes.EnvironmentCheckProduction
import dev.maplesu.manager.workmode.detect.probes.KsuKernelProbeAdapter
import dev.maplesu.manager.workmode.detect.probes.LiteCredentialProbeAdapter
import dev.maplesu.manager.workmode.detect.probes.UltraEnvironmentAdapter
import dev.maplesu.manager.workmode.detect.probes.RootEvidenceReaderAdapter
import dev.maplesu.manager.workmode.gating.ModeComponentIds
import dev.maplesu.manager.workmode.gating.ModeGate
import dev.maplesu.manager.workmode.gating.ModeGateFormat
import dev.maplesu.manager.workmode.service.ModeSwitchRuntime
import dev.maplesu.manager.workmode.switch.SwitchStateNames
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 启动门控的接线点：**两段式判定**，由 [ModeBootstrapCoordinator] 编排。
 *
 * 时序（审计阻塞修复，01 §1.2 / C11）：
 * `Application.onCreate → start()`
 * `  ├─ 阶段一（后台线程）：STARTUP，allowRootProbe=false/allowLiteExecutor=false`
 * `  │    → recordDetection → completeBootstrap → 可激活的 B 态则 activate 一次 → ModeGate`
 * `  └─ detectAfterRootGranted(延迟 ~2.5s，主线程 post，后台线程执行)：`
 * `       已激活 → 跳过；否则 AFTER_ROOT_GRANTED(allowRootProbe=true, allowLiteExecutor=true)`
 * `       → 重新判定 → recordDetection → completeBootstrap → activate 一次 → ModeGate`
 *
 * 为什么必须两段：阶段一不能拿 root/不能试 Lite executor（主文档:204/378），Lite 设备的
 * 可用性证据（凭据→UID0 握手、控制面 root 会话）只能在阶段二获得；只做阶段一会让 Lite
 * 永远停在 `MODE_A_INSTALLED_UNBOUND`（activeMode=null），把 Lite 整个关死。
 *
 * 幂等：阶段一只跑一次；阶段二互斥执行，未判定时有限重试，激活后停止探测。
 * 失败/未判定一律保持"未判定"，**绝不默认 LITE**（主文档:786）。
 */
object ModeBootstrap {

    private const val TAG = "MapleSUMode"

    /** 阶段二延迟：让首帧/解锁完成后再拿 root 证据，不阻塞启动。 */
    const val SECOND_PHASE_DELAY_MS = 2_500L
    private const val MAX_SECOND_PHASE_ATTEMPTS = 3
    private const val SECOND_PHASE_RETRY_DELAY_MS = 750L

    private val started = AtomicBoolean(false)
    private val secondPhaseScheduled = AtomicBoolean(false)
    private val firstFrameHandled = AtomicBoolean(false)
    private val secondPhaseRetry = ModeBootstrapRetryState(MAX_SECOND_PHASE_ATTEMPTS, SECOND_PHASE_RETRY_DELAY_MS)
    private val firstPhaseComplete = CompletableDeferred<Unit>()
    private val detectionProbeRunner = EnvironmentProbeRunner("maplesu-mode-detect")
    private val preparationProbeRunner = EnvironmentProbeRunner("maplesu-mode-prepare")

    @Volatile
    private var coordinator: ModeBootstrapCoordinator? = null

    private var startupContext: Context? = null
    private var managerProcess = false

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        startupContext = appContext
        managerProcess = android.app.Application.getProcessName() == appContext.packageName

        ModeGate.skip(ModeComponentIds.APP_MODE_BOOTSTRAP)
        runCatching { ModeRuntime.bootstrap(appContext) }
            .onFailure { Log.w(TAG, "mode.bootstrap failed (ignored)", it) }

        // 恢复路径（清数据后 restore / 重启收敛已验证）可能在本进程里**已经**是 Ultra，
        // 那种情况下阶段一/二的 Activated 不会再触发一次。这里补一次幂等接线。
        runCatching {
            if (ModeRuntime.active == WorkMode.LITE) {
                dev.maplesu.manager.modules.LiteModuleBootService.request(appContext)
            }
        }.onFailure { Log.w(TAG, "restored Ultra wiring failed (explicit)", it) }

        val coordinator = ModeBootstrapCoordinator(
            pipelineProvider = { runCatching { newPipeline(appContext) }.getOrNull() },
            isResolved = { ModeRuntime.active != null },
            observer = { phase, outcome ->
                recordGate(phase, outcome)
                if (outcome is BootstrapOutcome.Activated) {
                    runCatching { com.maplesu.core.ui.util.AppearancePreferences.onModeResolved(appContext) }
                        .onFailure { Log.w(TAG, "appearance migration deferred", it) }
                    if (outcome.mode == WorkMode.LITE) {
                        // Activation invalidates any in-flight unresolved pairing scope.
                        // Queue a fresh pass through the existing serialized recovery owner.
                        runCatching { (appContext as? com.maplesu.core.KernelSUApplication)?.requestUltraRecovery() }
                            .onFailure { Log.w(TAG, "Ultra recovery after mode activation deferred", it) }
                        // BOOT_COMPLETED may precede mode detection. Dispatch after the
                        // live mode proof too; epoch receipts deduplicate repeated starts.
                        runCatching { dev.maplesu.manager.modules.LiteModuleBootService.request(appContext) }
                            .onFailure { Log.w(TAG, "Ultra module startup deferred", it) }
                    }
                }
            },
        ).also { coordinator = it }

        startDetectionThread("mode-bootstrap-phase-one") {
            val startedAt = SystemClock.elapsedRealtime()
            startupTrace("stage=phase_one event=begin")
            try {
                runBlocking { coordinator.runPhaseOne() }
            } finally {
                firstPhaseComplete.complete(Unit)
                startupTrace("stage=phase_one event=end elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
            }
        }
        // 阶段二由 Application 启动路径**显式**调用 [detectAfterRootGranted]（幂等；
        // 审计要求可见的 AFTER_ROOT_GRANTED 触发点），本方法内不再隐式调度。
    }

    /**
     * 阶段二的保底延迟触发入口（已配对用户另可在首帧后提前触发）。
     *
     * 在 UI 可见后调用：执行一次 `AFTER_ROOT_GRANTED` 判定（allowRootProbe/allowLiteExecutor
     * 均为 true），成功后激活 Lite/KSU 一次。重复调用合并；
     * 已激活时不做任何探测。失败时保持未判定（由门控点显式拒绝），不会默认 LITE。
     */
    @Suppress("UNUSED_PARAMETER")
    fun detectAfterRootGranted(context: Context, delayMillis: Long = SECOND_PHASE_DELAY_MS) {
        if (!started.get() || !managerProcess) return
        schedulePhaseTwo(delayMillis)
    }

    /** A visible retry reuses the same detector and bounded startup attempts. */
    fun retryEnvironmentDetection() {
        if (!started.get() || !managerProcess || ModeRuntime.active != null) return
        if (secondPhaseRetry.requestRetry()) schedulePhaseTwo(0)
    }

    private fun schedulePhaseTwo(delayMillis: Long) {
        if (!secondPhaseScheduled.compareAndSet(false, true)) return
        runCatching {
            Handler(Looper.getMainLooper()).postDelayed({
                secondPhaseScheduled.set(false)
                executePhaseTwo()
            }, delayMillis.coerceAtLeast(0L))
        }.onFailure {
            // 调度失败不得影响启动；阶段二缺失时宁可保持未判定（不得默认 LITE）。
            secondPhaseScheduled.set(false)
            Log.w(TAG, "mode phase-two scheduling failed (stay unresolved)", it)
        }
    }

    /**
     * Called after the Manager rendered its first frame. Unresolved, unsuspended startup
     * immediately schedules the same live detector and handshake.
     * The delayed schedule remains intact as a fallback.
     */
    fun onManagerFirstFrame(context: Context) {
        if (!started.get() || !firstFrameHandled.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        startDetectionThread("mode-bootstrap-paired-resume") {
            val expedite = runCatching {
                PairedStartupPolicy.shouldExpedite(
                    modeResolved = ModeRuntime.active != null,
                    suspended = dev.maplesu.manager.modules.UltraRootKeyStore.isSuspended(appContext),
                )
            }.getOrDefault(false)
            startupTrace("stage=paired_startup eligible=$expedite")
            if (expedite) executePhaseTwo {
                PairedStartupPolicy.refreshEnvironmentIfEligible(
                    modeResolved = ModeRuntime.active != null,
                    ksuResponded = ModeRuntime.latestDetection?.trace?.firstOrNull { it.id == "S1" }?.ok,
                ) {
                    val localKey = dev.maplesu.manager.modules.UltraRootKeyStore.read(appContext)
                    localKey != null && UltraKernelCredential.isValid(localKey) &&
                        UltraKernelCredential.of(localKey).sdkMetadataValid &&
                        dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe.refresh(appContext, force = false) ==
                            dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe.State.Running
                }
            }
        }
    }

    /** Scheduling and execution have separate guards, so first-frame work can win the timer. */
    private fun executePhaseTwo(beforeDetection: () -> Unit = {}) {
        if (!managerProcess) return
        val coordinator = coordinator ?: return
        if (secondPhaseRetry.tryStart() == null) return
        if (ModeRuntime.active == null) ModeRuntime.beginDetection()
        startDetectionThread("mode-bootstrap-phase-two") {
            try {
                runBlocking {
                    // Early scheduling must not race a still-running local-only observation.
                    var startedAt = SystemClock.elapsedRealtime()
                    startupTrace("stage=phase_two_wait event=begin")
                    firstPhaseComplete.await()
                    startupTrace("stage=phase_two_wait event=end elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
                    startedAt = SystemClock.elapsedRealtime()
                    startupTrace("stage=phase_two_prepare event=begin")
                    val preparation = preparationProbeRunner.run(8_000) {
                        beforeDetection()
                        startupContext?.let {
                            val observed = dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe.currentSnapshot(it)
                            val currentUltraConfirmed = observed.rootAvailable ||
                                observed.state == dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe.State.Fault ||
                                dev.maplesu.manager.runtime.ultra.UltraRootGateway.available(it)
                            if (!currentUltraConfirmed) {
                                dev.maplesu.manager.runtime.ultra.UltraInstallCredentialRecovery.recover(it)
                            }
                        }
                    }
                    if (preparation !is EnvironmentProbeResult.Completed) {
                        recordGate(BootstrapPhase.AFTER_ROOT_GRANTED, BootstrapOutcome.Failed(
                            if (preparation == EnvironmentProbeResult.TimedOut) "MODE_PREPARE_TIMEOUT" else "MODE_PREPARE_FAILED",
                            "environment preparation did not complete"))
                        return@runBlocking
                    }
                    startupTrace("stage=phase_two_prepare event=end elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
                    startedAt = SystemClock.elapsedRealtime()
                    startupTrace("stage=phase_two_detect event=begin")
                    coordinator.runPhaseTwo()
                    startupTrace("stage=phase_two_detect event=end elapsedMs=${SystemClock.elapsedRealtime() - startedAt} active=${ModeRuntime.active?.name ?: "UNKNOWN"}")
                    ModeRuntime.latestDetection?.trace?.forEach { probe ->
                        startupTrace("stage=probe id=${probe.id} elapsedMs=${probe.elapsedMillis} ok=${probe.ok}")
                    }
                }
                // Reboot convergence also runs once when phase one already resolved Normal.
                convergePendingModeSwitch()
            } catch (error: Throwable) {
                Log.w(TAG, "mode phase-two attempt failed (stay unresolved)", error)
            } finally {
                secondPhaseRetry.finish(resolved = ModeRuntime.active != null)?.let(::schedulePhaseTwo)
            }
        }
    }

    private fun newPipeline(context: Context): ModeBootstrapPipeline {
        // 同一 adapter 实例同时提供"只读存在性"与"特权握手"两个端口：
        // 阶段一只会调用前者；阶段二 allowLiteExecutor=true 时才调用后者。
        val liteProbe = LiteCredentialProbeAdapter(context)
        return ModeBootstrapPipeline(
            detector = DefaultModeDetector(
                ksuProbe = KsuKernelProbeAdapter,
                liteProbe = liteProbe,
                liteHandshakeProbe = liteProbe,
                ultraPort = UltraEnvironmentAdapter(context),
                bootIdSource = BootIdSourceAdapter,
                rootEvidenceReader = RootEvidenceReaderAdapter(AndroidRootShellOpener(context)),
            ),
            runtime = ProductionModeRuntimePort,
            probeRunner = detectionProbeRunner,
            // T14：先"继承上次状态 / 降级快速核对"；只有 KSU 与"未安装"两个终态会覆盖
            // 三阶段判定（LITE/ULTRA/UNKNOWN 一律返回 null，交回既有两阶段判定）。
            // Auxiliary SU endpoints keep local KSU/Hook wiring, without repeating the
            // Manager's executable SU probes or writing its environment preferences.
            environmentOverride = if (managerProcess) ({ environmentOverride(context) }) else null,
            environmentStore = if (managerProcess) EnvironmentCheckProduction.store(context) else null,
        )
    }

    /**
     * T14 前置环境检查（单飞 + 8 s 预算）：能继承就零探测；不能继承才走
     * 「当前 Ultra → su → Ultra 恢复 → KSU」链。返回 null = 不覆盖（照常三阶段探测）。
     */
    private suspend fun environmentOverride(context: Context): DetectionResult? {
        val startedAt = SystemClock.elapsedRealtime()
        val outcome = runCatching {
            EnvironmentCheckProduction.runner(context.applicationContext).check()
        }.getOrElse { error ->
            Log.w(TAG, "environment precheck failed (fall through to full detection)", error)
            return null
        }
        startupTrace(
            "stage=environment_check code=${outcome.code} detail=${outcome.detail} " +
                "mode=${outcome.mode?.suffix ?: "NONE"} inherited=${outcome.inherited} " +
                "probes=${outcome.probes.joinToString(",")} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
        )
        val detection = EnvironmentDetectionProjection.override(outcome) ?: return null
        startupTrace(
            "stage=environment_check event=override state=${detection.state.name} boot=${detection.bootId}",
        )
        return detection
    }

    private fun startDetectionThread(name: String, block: () -> Unit) {
        Thread(block, name).apply { isDaemon = true }.start()
    }

    private fun startupTrace(message: String) {
        if (BuildConfig.DEBUG) Log.i("MapleSUStartup", message)
    }

    /**
     * P0-1 第 5 项：重启后把"分区已写、等重启"的切换收敛成 VERIFIED。
     *
     * `ModeSwitchFacade.reopenAfterReboot()` 内部已经串好整条链：`verify` →
     * `modes.json.current`（`ControlPlaneModeCommitSink`）→ H5/Ultra 收尾 → 超管 S6.4
     * `reconcileAfterBoot`；缺的只是**调用者**（在此之前它零调用，于是装完 Normal 重启后
     * modes.json 与超管授权状态永远不会落）。
     *
     * 位置理由：必须**在模式判定完成之后**（`reconcileAfterBoot` 的工厂要求
     * `ModeRuntime.active == 目标模式`），也就是阶段二线程的末尾；激活后不再重复探测。
     * 幂等：已 VERIFIED 仅续接当前任务未完成的应用侧进度，成功清理后不再重放。
     * 失败不改任何状态（只记日志），绝不把"没验证"当成功。
     *
     * L7：只对"真的在等重启/验证"的状态收敛。`ClientStatus.pending` 把 `RECOVERY_REQUIRED`
     * 也算 pending，于是每次冷启动都会对一条必然失败的记录发 `verify`（必 `STATE_INVALID`）
     * 并刷一条 warning —— 无害但都是无意义 root 调用与噪音。
     */
    private fun convergePendingModeSwitch() {
        val facade = runCatching { ModeSwitchRuntime.facadeOrNull() }.getOrNull() ?: return
        val status = runCatching { facade.observeStatus() }.getOrNull() ?: return
        if (status.state !in CONVERGIBLE_STATES) {
            if (status.state != SwitchStateNames.VERIFIED) return
            val activation = ModeRuntime.activation as? ModeActivation.Active ?: return
            val unfinished = runCatching { facade.hasPendingFinalization(status) }
                .onFailure { Log.w(TAG, "post-boot finalization progress unavailable", it) }
                .getOrDefault(false)
            val boot = runCatching { BootIdSourceAdapter.read() }.getOrNull()
            if (!unfinished || ModeRuntime.activation !== activation || boot != activation.bootId) return
        }
        runBlocking {
            runCatching { facade.reopenAfterReboot() }
                .onFailure { Log.w(TAG, "post-boot mode-switch converge failed", it) }
        }
    }

    /** 分区事务待收敛的状态；VERIFIED 的应用侧尾处理另按现有 progress 续接。 */
    private val CONVERGIBLE_STATES = setOf(
        SwitchStateNames.REBOOT_REQUIRED,
        SwitchStateNames.VERIFYING,
        SwitchStateNames.COMMIT,
    )

    /** 两阶段共用同一套记录规则；phase 进 extras，便于审计"到底跑了哪一阶段"。 */
    private fun recordGate(phase: BootstrapPhase, outcome: BootstrapOutcome?) {
        if (phase == BootstrapPhase.AFTER_ROOT_GRANTED && outcome != null) ModeRuntime.finishDetection()
        val extras = mutableMapOf("phase" to phase.wire)
        when (outcome) {
            is BootstrapOutcome.Activated -> {
                extras["mode"] = outcome.mode.suffix.lowercase()
                ModeGate.allow(ModeComponentIds.APP_MODE_BOOTSTRAP, extras = extras)
            }
            is BootstrapOutcome.Unresolved -> {
                extras["state"] = outcome.state.name.lowercase()
                extras["reason"] = outcome.reason.lowercase()
                ModeGate.skip(ModeComponentIds.APP_MODE_BOOTSTRAP, ModeGateFormat.Reason.UNDETERMINED, extras)
            }
            is BootstrapOutcome.Failed -> {
                ModeRuntime.failBootstrap(outcome.code)
                extras["reason"] = outcome.code.lowercase()
                ModeGate.skip(ModeComponentIds.APP_MODE_BOOTSTRAP, ModeGateFormat.Reason.UNDETERMINED, extras)
            }
            // null = 阶段二因已激活/未 claim 而跳过：不追加记录，避免证据失真。
            null -> Unit
        }
    }
}
