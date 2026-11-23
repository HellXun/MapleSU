// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.workmode.backend

import dev.maplesu.manager.workmode.ui.WorkModeSwitchSession

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.maplesu.core.data.repository.ModuleRepositoryImpl
import dev.maplesu.manager.ksu.CachingKsuPathProbe
import dev.maplesu.manager.ksu.KsuBackendSet
import dev.maplesu.manager.ksu.KsuBackends
import dev.maplesu.manager.ksu.KsuBootReceipt
import dev.maplesu.manager.ksu.KsuBootstrap
import dev.maplesu.manager.ksu.KsuBootstrapResult
import dev.maplesu.manager.ksu.KsuCommandBackend
import dev.maplesu.manager.ksu.KsuCommands
import dev.maplesu.manager.ksu.KsuDeployReceipt
import dev.maplesu.manager.ksu.KsuLog
import dev.maplesu.manager.ksu.KsuModuleOps
import dev.maplesu.manager.ksu.KsuRootChannel
import dev.maplesu.manager.ksu.KsuRootExecutor
import dev.maplesu.manager.ksu.KsuRootShell
import dev.maplesu.manager.ksu.KsuRootShellExecutor
import dev.maplesu.manager.ksu.KsuRootCommandRunner
import dev.maplesu.manager.ksu.KsuRuntimeState
import dev.maplesu.manager.ksu.KsuSettingsFeature
import dev.maplesu.manager.ksu.KsuSettingsFeatureRead
import dev.maplesu.manager.ksu.KsuShellResult
import dev.maplesu.manager.ksu.KsuSuperuserAdapter
import dev.maplesu.manager.ksu.LibsuRootCommandRunner
import dev.maplesu.manager.ksu.RootShellBootReceiptProbe
import dev.maplesu.manager.ksu.rootShellPathProbe
import dev.maplesu.manager.modules.ModuleBackendPorts
import dev.maplesu.manager.modules.ModuleOpsPort
import dev.maplesu.manager.runtime.RootExec
import dev.maplesu.manager.runtime.RootExecutor
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** 日志 tag（文件级：外层 object 与它的嵌套类都要用）。 */
private const val TAG = "MapleSUSuKsuWire"

/**
 * **Normal（KernelSU）管理侧的最后一公里接线**（2026-11-01 收尾）。
 *
 * 这是 `src/main` 里唯一允许 import `dev.maplesu.manager.ksu` 的接线文件之一
 * （`KsuSourceIsolationTest` 白名单里的 `workmode/backend/KsuBackendRegistry.kt`）。
 * 所有 KSU 类型都止步于此：UI/仓库层只通过本对象暴露的**窄接口**
 * （[ModuleOpsPort] / [KsuFeaturePort] / [RootExecutor]）使用后端。
 *
 * ## 接线时机（谁在什么时刻注入）
 * ```
 * KernelSUApplication.onCreate
 *   ├─ KsuBackendRegistry.installLogging()                     # KsuLog → logcat
 *   ├─ RootExec.install(RootExecutionRouter.production(this))  # router 里 KSU executor 是动态读取
 *   └─ ModeBootstrap.start(context)
 *        ├─ 恢复路径已是 KSU → installProduction(...)          # 清数据/重启收敛后的补接线
 *        └─ 阶段一/二判定 → BootstrapOutcome.Activated(KSU)
 *             └─ KsuBackendRegistry.installProduction(context, KSU)   ← 唯一生产入口
 *                  ├─ KsuRootShell.install(executor, rootProbe)       # ksud 命令的 root 提权接缝
 *                  ├─ RootExec.installKsu(KsuRootExecutor)            # RootExec 在 KSU 下可用
 *                  ├─ KsuBackends.wireDeployed(/data/adb/ksud)        # 模块/设置/超管后端
 *                  ├─ ModuleBackendPorts.ksuFactory = { modulePort }  # 模块仓库的 KSU 分支
 *                  └─ 后台线程：KsuBootstrap.ensureDeployed()         # 部署 /data/adb/ksud
 * ```
 *
 * ## 失败语义
 * 任何一步失败都留下**显式状态**（[install] 的返回值、[bootstrapResult]、[lastError]），
 * 绝不把"没接线"伪装成"可用"：未接线时 [modulePort] / [featurePort] 返回 null，
 * 调用方必须显式失败或走 LITE 分支。
 */
object KsuBackendRegistry {

    /** 接线结果（显式，不用布尔）。 */
    sealed interface InstallResult {
        /** 已接线；[ready] = 认主 + ksud 可执行。 */
        data class Wired(val ready: Boolean) : InstallResult

        /** 当前模式不是 KSU：什么都没做（**显式**跳过，不是失败）。 */
        data object Skipped : InstallResult

        /** 模式是 KSU 但接线失败；[code] 是具名错误码。 */
        data class Failed(val code: String, val detail: String) : InstallResult
    }

    data class FeatureReadReceipt(val code: Int, val out: List<String>, val err: List<String>)

    data class FeatureReadSnapshot(
        val suCompatStatus: String,
        val values: Map<String, Long?>,
        val receipts: Map<String, FeatureReadReceipt> = emptyMap(),
    )

    /** 设置页需要的 feature 面（KSU 适配后的窄接口，不含任何 `dev.maplesu.manager.ksu` 类型）。 */
    interface KsuFeaturePort {
        val readRevision: Long? get() = 0L
        fun status(feature: String): String
        fun value(feature: String): Long?
        fun persistValue(feature: String): Long?
        fun set(feature: String, enabled: Boolean): Boolean
        fun save(): Boolean

        fun readSettings(includeExtras: Boolean): FeatureReadSnapshot = FeatureReadSnapshot(
            status("su_compat"), KsuSettingsFeatureRead.features(includeExtras).associateWith(::value),
        )
    }

    /** 接线所需的生产件；主机测试注入 fake（不需要 `Context`）。 */
    interface WiringPort {
        /** 装配后端（不得起长进程）。 */
        fun wire(): Wired

        /** 首次 userland 部署（`ksud install`）；可耗时，生产在后台线程调用。 */
        fun deploy(): KsuBootstrapResult
    }

    /** [WiringPort.wire] 的产物。 */
    data class Wired(
        val rootExecutor: RootExecutor?,
        val moduleOps: KsuModuleOps?,
        val command: KsuCommandBackend?,
        val settingsFeature: KsuSettingsFeature?,
        val superuser: KsuSuperuserAdapter?,
        /** `ksud` 命令面的提权接缝（生产 = `KsuRootChannel`；测试 = fake）。 */
        val rootShellExecutor: KsuRootShellExecutor? = null,
        val backendSet: KsuBackendSet? = null,
    ) {
        val ready: Boolean get() = backendSet?.isReady() == true
    }

    @Volatile
    private var wiredSet: Wired? = null

    @Volatile
    private var modulePortCache: ModuleOpsPort? = null

    @Volatile
    private var featurePortCache: KsuFeaturePort? = null

    @Volatile
    private var deployResultCache: KsuBootstrapResult? = null

    @Volatile
    private var lastErrorText: String? = null

    @Volatile
    private var wiredModeCache: WorkMode? = null

    @Volatile
    private var rootShellExecutorCache: KsuRootShellExecutor? = null

    @Volatile
    private var deployPort: WiringPort? = null

    /** T3：运行时自检的短 TTL 缓存（`bootEpoch`/root shell 都贵）。 */
    @Volatile
    private var runtimeStateCache: Pair<Long, KsuRuntimeState>? = null

    /** 测试接缝：整体替换回执（生产为 null = 走 root shell 探针）。 */
    @Volatile
    private var runtimeReceiptFactory: (() -> KsuBootReceipt)? = null

    private val deployStarted = AtomicBoolean(false)

    // ── 状态查询（UI/仓库/诊断）────────────────────────────────────────

    fun isWired(): Boolean = wiredSet != null

    fun wiredMode(): WorkMode? = wiredModeCache

    fun rootExecutor(): RootExecutor? = wiredSet?.rootExecutor

    fun modulePort(): ModuleOpsPort? = modulePortCache

    fun featurePort(): KsuFeaturePort? = featurePortCache

    fun superuser(): KsuSuperuserAdapter? = wiredSet?.superuser

    fun backendSet(): KsuBackendSet? = wiredSet?.backendSet

    /** 首次部署状态；`null` = 尚未开始（尚未激活为 KSU 或还在排队）。 */
    fun bootstrapResult(): KsuBootstrapResult? = deployResultCache

    fun lastError(): String? = lastErrorText

    /** KSU 模块列表 JSON；未接线 → `null`（调用方必须显式失败，不得回退 LITE）。 */
    fun moduleListJson(): String? {
        val port = modulePortCache ?: return null
        return if (port.isAvailable()) port.listJson() else null
    }

    /**
     * KSU 模块安装（`ksud module install <zip>`）。
     *
     * `null` = KSU 后端未接线/不可用 → 调用方必须回到自己的显式失败路径，
     * **不得**静默改用 Lite 脚本后端（审计 §D0 的"安装走 Ultra 实现"）。
     */
    fun installModule(zip: File): Boolean? {
        val port = modulePortCache ?: return null
        if (!runCatching { port.isAvailable() }.getOrDefault(false)) return null
        return installModuleResult(zip)?.code == 0
    }

    /** KSU 类型止步于接线边界；输出按捕获的行保留，包含空行。 */
    data class ModuleInstallResult(
        val code: Int,
        val out: List<String>,
        val err: List<String>,
        val needsReboot: Boolean = true,
    )

    /** `null` = 未接线；已接线后保留真实退出码或明确的启动前失败原因。 */
    fun installModuleResult(
        zip: File,
        onStdout: (String) -> Unit = {}, onStderr: (String) -> Unit = {},
    ): ModuleInstallResult? {
        val ops = wiredSet?.moduleOps ?: return null
        return try {
            val result = ops.installResult(zip, onStdout, onStderr)
            ModuleInstallResult(result.code, result.out, result.err, result.code == 0 && ops.needsReboot(zip))
        } finally {
            ModuleRepositoryImpl.invalidateModuleList()
        }
    }

    /**
     * KSU 模块 action（`ksud module action <id>`，**无 `-g`**，global-mnt root shell）。
     *
     * 返回值语义：`null` = 未接线（调用方必须显式失败）；`true/false` = 脚本退出码是否为 0。
     * stdout/stderr **原样透传**给 [onStdout]/[onStderr]（含失败时的具名 `MODULE_ACTION_FAILED:<code>`），
     * 不再回吐伪造的 `MODULE_ACTION_STARTED:`（开发文档 §2.3 A2 / §10.3 D4）。
     */
    fun runModuleAction(
        moduleId: String,
        onStdout: (String) -> Unit,
        onStderr: (String) -> Unit,
    ): Boolean? {
        val ops = wiredSet?.moduleOps ?: return null
        if (!runCatching { ops.isAvailable() }.getOrDefault(false)) return null
        val result = runCatching { ops.action(moduleId, onStdout, onStderr) }.getOrElse { error ->
            onStderr("MODULE_ACTION_FAILED:EXCEPTION:${error.javaClass.simpleName}")
            return false
        }
        if (!result.ok) onStderr("MODULE_ACTION_FAILED:${result.code}")
        return result.ok
    }

    /** AppProfile：KSU 后端的授权读取（`allowSu`）；`null` = 未接线/不可用。 */
    fun appProfileAllowSu(packageName: String, uid: Int): Boolean? {
        val adapter = wiredSet?.superuser ?: return null
        if (!runCatching { adapter.available() }.getOrDefault(false)) return null
        return runCatching { adapter.getProfile(packageName, uid)?.allowSu }.getOrNull()
    }

    /** AppProfile：KSU 后端的授权写入（只改 `allowSu`，其余字段原样回写）。 */
    fun setAppProfileAllowSu(packageName: String, uid: Int, allow: Boolean): Boolean =
        runCatching { wiredSet?.superuser?.setAllow(packageName, uid, allow) ?: false }
            .getOrDefault(false)

    /** 模板入口是否可用（KSU 模式下由 ksud `profile` 子命令提供）。 */
    fun templatesAvailable(): Boolean = wiredSet != null

    /** 当前是否真的在 KSU 模式下接线完成。 */
    fun isActiveForKsu(): Boolean =
        ModeRuntime.active == WorkMode.KSU && wiredSet != null

    /**
     * 启动接线：把 KSU 后端的事件流接到 `android.util.Log`。
     *
     * `KsuLog` 默认只写内存环形缓冲（主机单测没有 `android.util.Log`）；Application
     * 启动时挂一次 sink，设备上就能看到 `ksu.unavailable` / `ksu.rejected` 这些事实。
     * 与模式无关且不触碰任何后端（LITE 下只是把日志转发出去）。
     */
    fun installLogging() {
        runCatching { KsuLog.sink = { line -> Log.i("MapleSUKsu", line) } }
    }

    // ── 装配 ───────────────────────────────────────────────────────────

    /**
     * 唯一的**测试/纯逻辑**装配入口：不碰 `Context`，不排后台线程。
     *
     * 副作用（与生产完全一致的部分）：`RootExec.installKsu`、
     * [KsuRootShell] 接缝、`ModuleBackendPorts.ksuFactory`、[featurePort]、[modulePort]。
     */
    fun install(mode: WorkMode?, port: WiringPort?): InstallResult {
        if (mode != WorkMode.KSU) return InstallResult.Skipped
        if (port == null) {
            val detail = "KSU wiring port unavailable"
            lastErrorText = detail
            // T10：接线失败是**一次性事件**的终点；等待中的模块列表必须尽快得到可见错误，
            // 不能一直停在"正在确认运行环境"。
            ModuleBackendPorts.markWiringFailed(CODE_WIRING_UNAVAILABLE)
            return InstallResult.Failed(CODE_WIRING_UNAVAILABLE, detail)
        }
        return runCatching {
            val fresh = port.wire()
            adopt(fresh)
            // 部署端口与接线是同一件生产件的两个阶段；这里一起登记，后台线程只读它。
            deployPort = port
            InstallResult.Wired(fresh.ready)
        }.getOrElse { error ->
            val detail = "${error.javaClass.simpleName}: ${error.message}"
            lastErrorText = detail
            note("wiring failed: $detail")
            ModuleBackendPorts.markWiringFailed(CODE_WIRING_FAILED)
            InstallResult.Failed(CODE_WIRING_FAILED, detail)
        }
    }

    /**
     * 生产入口：只在 `ModeRuntime.active == KSU` 时由 `ModeBootstrap` 调用（幂等）。
     *
     * 后端装配是同步的（便宜：只是构造对象 + 注入），`ksud install` 部署排到后台线程。
     */
    fun installProduction(context: Context, mode: WorkMode?): InstallResult {
        if (mode != WorkMode.KSU) return InstallResult.Skipped
        val application = context.applicationContext
        val port = ProductionWiringPort(application)
        val result = install(mode, port)
        if (result is InstallResult.Wired) scheduleDeployment(mode)
        return result
    }

    /** 首次部署（幂等；只有 KSU 模式会真的执行）。 */
    fun scheduleDeployment(mode: WorkMode?) {
        if (mode != WorkMode.KSU) {
            deployResultCache = KsuBootstrapResult.skipped()
            return
        }
        if (WorkModeSwitchSession.executing) return
        if (!deployStarted.compareAndSet(false, true)) return
        Thread({
            val port = deployPort
            if (port == null) {
                deployResultCache = KsuBootstrapResult.failed(
                    CODE_WIRING_UNAVAILABLE, "deployment port missing",
                )
                return@Thread
            }
            val outcome = runCatching { port.deploy() }.getOrElse { error ->
                KsuBootstrapResult.failed(
                    KsuBootstrap.FAILURE_CODE,
                    "${error.javaClass.simpleName}: ${error.message}",
                )
            }
            deployResultCache = outcome
            if (!outcome.ok) {
                lastErrorText = "${outcome.failureCode}: ${outcome.detail}"
                note("ksud deployment failed: ${outcome.failureCode} ${outcome.detail}")
            }
        }, "maplesu-ksu-bootstrap").apply { isDaemon = true }.start()
    }

    /** Historical recovery markers survive reboot; only a live switch delays deployment. */
    internal fun backgroundDeployCommand(command: String): String {
        check(!WorkModeSwitchSession.executing) { "KSU_BOOTSTRAP_SWITCH_BUSY" }
        return command
    }

    /**
     * 记录一条接线事实。
     *
     * 走 [KsuLog]（Android-free）而不是直接 `android.util.Log`：这些路径在主机单测里
     * 也会执行，而 android.jar 的 stub 会抛 `RuntimeException: Stub!`。
     * 设备上 `installLogging()` 已经把 [KsuLog.sink] 接到了 logcat，所以可见性不变。
     */
    private fun note(message: String) {
        runCatching { KsuLog.record("wire", message) }
    }

    /** 采纳装配结果（同一个对象也提供给部署线程）。 */
    private fun adopt(fresh: Wired) {
        wiredSet = fresh
        wiredModeCache = WorkMode.KSU
        lastErrorText = null
        RootExec.installKsu(fresh.rootExecutor)
        // root shell 接缝必须在任何 ksud 命令之前装好：后端的 daemon 探针依赖它读 /data/adb。
        installRootShellExecutor(fresh.rootShellExecutor)
        modulePortCache = fresh.moduleOps?.let { ModuleBackendPortsAdapter(it) }
        featurePortCache = fresh.settingsFeature?.let { SettingsFeatureAdapter(it) }
        ModuleBackendPorts.ksuFactory = { wiredSet?.moduleOps?.let { ModuleBackendPortsAdapter(it) } }
    }

    /** 当前进程的 root shell 接缝（生产 = `KsuRootChannel`；测试可注入）。 */
    fun installRootShellExecutor(executor: KsuRootShellExecutor?) {
        rootShellExecutorCache = executor
        KsuRootShell.install(
            executor,
            executor?.let { runCatching { rootShellPathProbe(it) }.getOrNull() },
        )
    }

    fun rootShellExecutor(): KsuRootShellExecutor? = rootShellExecutorCache

    // ── T3 启动链回执（runtimeStatus）──────────────────────────────────
    //
    // 判据与状态机在 `src/ksu`（`KsuBootReceipt`，纯逻辑，主机可离线单测）；这里只做三件事：
    // 模式门（LITE 零探针）、接缝注入（生产 = 已认证 root shell）、短 TTL 缓存。

    /** 生产探针用的 root shell；未装配时**显式失败**（绝不 app uid 执行、绝不静默 PASS）。 */
    private val unwiredRootShell: KsuRootShellExecutor = object : KsuRootShellExecutor {
        override fun exec(commandLine: String): KsuShellResult = KsuShellResult(
            -1, emptyList(), listOf("${KsuRootShell.UNAVAILABLE}:not wired"),
        )
    }

    /**
     * Normal 模式的 **KSU 运行时状态**（开发文档 T3；UI 经 `Natives.ksuRuntimeStatus` 读）。
     *
     * 三条与开发文档 §9.4 对齐的硬语义：
     * 1. `mode == LITE` → [KsuRuntimeState.NotApplicable]，且**不创建/不调用任何探针**
     *    （断言 3：零 ksud 调用）；
     * 2. 未接线 → [KsuRuntimeState.Unknown]（断言 4：**不得**是 `Ready`）；
     * 3. 探针异常/证据不足 → `Unknown`（`KsuBootReceipt` 内部保证，禁止静默成功）。
     *
     * 结果按 [RUNTIME_STATE_TTL_MS] 缓存：本方法会被首页反复读，而一次评估要开若干条
     * root shell（每条都 `debug su`），不缓存会拖慢首帧。
     */
    fun runtimeStatus(): KsuRuntimeState {
        // ① LITE 门必须在最前：零探针（连探针对象都不构造）。
        if (ModeRuntime.active == WorkMode.LITE) return KsuRuntimeState.NotApplicable
        // ② 未接线：不是"正常"，也不是"启动链没跑"——是"还不知道"。
        if (wiredSet == null) return KsuRuntimeState.Unknown
        val now = runCatching { System.nanoTime() / 1_000_000L }.getOrDefault(0L)
        runtimeStateCache?.let { (atMs, state) ->
            if (now - atMs in 0..RUNTIME_STATE_TTL_MS) return state
        }
        val state = evaluateRuntimeStatus()
        runtimeStateCache = now to state
        return state
    }

    /** 跳过 TTL 强制重算（"点此修复"后刷新用）。 */
    fun refreshRuntimeStatus(): KsuRuntimeState {
        runtimeStateCache = null
        return runtimeStatus()
    }

    private fun evaluateRuntimeStatus(): KsuRuntimeState = runCatching {
        val receipt = runtimeReceiptFactory?.invoke() ?: KsuBootReceipt(
            probe = RootShellBootReceiptProbe(rootShellExecutorCache ?: unwiredRootShell),
            // 内核侧读不到 → null → `KsuBootReceipt` 判 UNKNOWN（绝不当 PASS）。
            kernelVersion = { KsuBackends.natives().version.takeIf { it >= 0 } },
            kernelUapi = { KsuBackends.natives().kernelUAPIVersion.takeIf { it >= 0 } },
            bootEpochMs = { bootEpochMs() },
            clock = { System.currentTimeMillis() },
        )
        receipt.evaluate()
    }.getOrElse { error ->
        runCatching {
            KsuLog.record(
                "runtime-status",
                "error ${error.javaClass.simpleName}: ${error.message}",
            )
        }
        KsuRuntimeState.Unknown
    }

    /**
     * 开机时刻 = `now - elapsedRealtime`（开发文档 T3；判据侧另有 ±90 s 容差）。
     *
     * `elapsedRealtime` 读不到时返回 `-1`（→ 判据 UNKNOWN），**不**退化成 `now`
     * ——那会让所有 mtime 判据都变成"没刷新"，制造假故障。
     */
    fun bootEpochMs(): Long {
        val elapsed = runCatching { SystemClock.elapsedRealtime() }.getOrNull() ?: return -1L
        return System.currentTimeMillis() - elapsed
    }

    /** 测试接缝：整体替换回执（生产不走这里）。 */
    internal fun installRuntimeReceiptForTest(factory: (() -> KsuBootReceipt)?) {
        runtimeReceiptFactory = factory
        runtimeStateCache = null
    }

    /** 测试/切换收尾：恢复未接线状态。 */
    internal fun resetForTest() {
        wiredSet = null
        modulePortCache = null
        featurePortCache = null
        deployResultCache = null
        lastErrorText = null
        wiredModeCache = null
        rootShellExecutorCache = null
        deployPort = null
        runtimeStateCache = null
        runtimeReceiptFactory = null
        deployStarted.set(false)
        RootExec.installKsu(null)
        ModuleBackendPorts.resetForTest()
        KsuRootShell.resetForTest()
    }

    const val CODE_WIRING_UNAVAILABLE = "KSU_WIRING_UNAVAILABLE"
    const val CODE_WIRING_FAILED = "KSU_WIRING_FAILED"
    const val CODE_LIBADBROOT_MISSING = "KSU_BOOTSTRAP_LIBADBROOT_MISSING"

    /** T3：`runtimeStatus()` 的缓存窗口（一次评估要开若干条 root shell）。 */
    const val RUNTIME_STATE_TTL_MS = 10_000L

    /** 诊断用：一次拿到全部接线事实（不触发任何 root 调用）。 */
    fun snapshot(): Map<String, String> = mapOf(
        "wired" to (wiredSet != null).toString(),
        "mode" to (wiredModeCache?.name ?: "NONE"),
        "ready" to (wiredSet?.ready?.toString() ?: "false"),
        "rootExecutor" to (wiredSet?.rootExecutor?.backend?.name ?: "NONE"),
        "modulePort" to (modulePortCache != null).toString(),
        "featurePort" to (featurePortCache != null).toString(),
        "rootShell" to (rootShellExecutorCache != null).toString(),
        "bootstrap" to (deployResultCache?.status?.name ?: "PENDING"),
        "bootstrapDetail" to (deployResultCache?.detail ?: ""),
        "lastError" to (lastErrorText ?: ""),
    )

    /** 让测试能看到 root shell 的真实结果形状。 */
    fun execRootLine(line: String): KsuShellResult? =
        rootShellExecutorCache?.let { runCatching { it.exec(line) }.getOrNull() }

    // ── 生产装配 ───────────────────────────────────────────────────────

    /**
     * 生产 [WiringPort]：`KsuRootChannel`（root shell）+ 部署后的 `/data/adb/ksud`。
     *
     * 顺序很讲究：**先**装 root shell 接缝，**再**构造命令后端 —— 后端的
     * `daemonExists()` 探针依赖 root shell 才能读 `/data/adb`（0700 root）。
     */
    class ProductionWiringPort(context: Context) : WiringPort {
        private val application = context.applicationContext

        private fun rootShell(): KsuRootShellExecutor = object : KsuRootShellExecutor {
            override fun exec(commandLine: String): KsuShellResult =
                KsuRootChannel().exec(application, commandLine)

            override fun exec(commandLine: String, timeoutMs: Long): KsuShellResult =
                    KsuRootChannel().exec(application, commandLine, globalMnt = false, timeoutMs = timeoutMs)

            override fun readFeatureBatch(commandLines: List<String>, timeoutMs: Long): List<KsuShellResult> {
                if (commandLines.isEmpty()) return emptyList()
                val activation = ModeRuntime.activation
                val boot = dev.maplesu.manager.runtime.ultra.UltraEnvironmentAutoSetup.currentBootId()
                val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(1L) * 1_000_000L
                fun current(): Boolean = !Thread.currentThread().isInterrupted &&
                    ModeRuntime.active == WorkMode.KSU && ModeRuntime.activation === activation &&
                    !WorkModeSwitchSession.executing && rootShellExecutorCache === this &&
                    boot != null && dev.maplesu.manager.runtime.ultra.UltraEnvironmentAutoSetup.currentBootId() == boot
                fun failed(reason: String) = KsuShellResult(-1, emptyList(), listOf(reason))
                if (!current()) return commandLines.map { failed("FEATURE_READ_SCOPE_CHANGED") }
                val results = ArrayList<KsuShellResult>(commandLines.size)
                try {
                    KsuRootChannel().openShell(application).use { shell ->
                        var stopped: String? = null
                        commandLines.forEach { command ->
                            val remaining = (deadline - System.nanoTime()) / 1_000_000L
                            val reason = stopped ?: when {
                                !current() -> "FEATURE_READ_SCOPE_CHANGED"
                                remaining <= 0L -> "FEATURE_READ_TIMEOUT"
                                !shell.isRoot || !shell.isAlive -> "FEATURE_READ_CHANNEL_UNAVAILABLE"
                                else -> null
                            }
                            results += if (reason != null) {
                                stopped = reason
                                failed(reason)
                            } else try {
                                KsuRootChannel.execOn(shell, command, remaining)
                            } catch (error: Exception) {
                                if (error is InterruptedException) Thread.currentThread().interrupt()
                                stopped = "FEATURE_READ_FAILED:${error.javaClass.simpleName}"
                                failed(checkNotNull(stopped))
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    repeat(commandLines.size - results.size) {
                        results += failed("FEATURE_READ_CHANNEL_UNAVAILABLE:${error.javaClass.simpleName}")
                    }
                }
                return results
            }

            /**
             * 全局挂载命名空间：`Shell.Builder.build(libksud_ksu.so, "debug", "su", "-g")`。
             *
             * 对应上游 `withNewRootShell(true)`；`ksud module action` 必须在它里面跑，
             * 否则模块脚本看到的是管理器自己的挂载视图（开发文档 §10.3 D3）。
             */
            override fun execInGlobalMount(commandLine: String): KsuShellResult =
                KsuRootChannel().exec(application, commandLine, globalMnt = true)

            override fun execInGlobalMount(commandLine: String, timeoutMs: Long): KsuShellResult =
                    KsuRootChannel().exec(application, commandLine, globalMnt = true, timeoutMs = timeoutMs)

            override fun execStreaming(
                commandLine: String, timeoutMs: Long, globalMnt: Boolean,
                onStdout: (String) -> Unit, onStderr: (String) -> Unit,
            ): KsuShellResult = KsuRootChannel().execStreaming(
                application, commandLine, globalMnt, timeoutMs, onStdout, onStderr,
            )
        }

        override fun wire(): Wired {
            val shell = rootShell()
            installRootShellExecutor(shell)
            val daemon = KsuCommands.DEPLOYED_DAEMON
            val probe = CachingKsuPathProbe(rootShellPathProbe(shell), ttlMs = 5_000L)
            val set = KsuBackends.wireDeployed(daemon, probe)
            return Wired(
                rootExecutor = KsuRootExecutor(application),
                moduleOps = set.modules,
                command = set.command,
                settingsFeature = set.settingsFeature,
                superuser = set.superuser,
                rootShellExecutor = shell,
                backendSet = set,
            )
        }

        override fun deploy(): KsuBootstrapResult {
            val libDir = application.applicationInfo.nativeLibraryDir
            val libadbroot = File(libDir, KsuBootstrap.LIBADBROOT_NAME)
            if (!libadbroot.isFile) {
                // APK 里没有 libadbroot.so = 构建缺陷，显式失败（不是"用不上就跳过"）。
                return KsuBootstrapResult.failed(
                    CODE_LIBADBROOT_MISSING, "libadbroot.so missing in $libDir",
                )
            }
            val launcher = File(libDir, KsuCommands.DAEMON_LIB_NAME)
            val shell = rootShellExecutorCache ?: rootShell()
            // T1 内容寻址：APK 内 `libksud_ksu.so` 的期望摘要（6.6 MB）。
            // `lazy` + `bundledKsudSha256` 的进程级缓存 ⇒ 部署线程只算一次。
            val expectedKsudSha256: String? by lazy { KsuBootstrap.bundledKsudSha256(launcher) }
            val bootstrap = KsuBootstrap(
                daemonPath = launcher.absolutePath,
                // install 的 runner 用已认证 root shell（`ksud debug su`）。
                runner = KsuRootCommandRunner { command, timeout ->
                    LibsuRootCommandRunner(launcher.absolutePath).run(backgroundDeployCommand(command), timeout)
                },
                probe = rootShellPathProbe(shell),
                digest = KsuBootstrap.rootShellDigest(shell),
                // T1：摘要是权威，版本与 busybox 是另外两项硬判据（A11）。
                expectedKsudSha256 = expectedKsudSha256,
                versionProbe = KsuBootstrap.rootShellVersionProbe(shell),
                busyboxProbe = KsuBootstrap.rootShellBusyboxProbe(shell),
                // 回执落在 App 私有目录，**不写** `/data/adb/ksu`（那是 ksud 的安装树）。
                receiptFile = File(application.filesDir, KsuDeployReceipt.FILE_NAME),
            )
            return bootstrap.ensureDeployed(
                libadbroot,
                onStdout = { line -> Log.i(TAG, "ksu-bootstrap: $line") },
                onStderr = { line -> Log.w(TAG, "ksu-bootstrap: $line") },
            )
        }
    }

    /** [KsuModuleOps] → 模式无关端口（仓库/门面唯一可见形状）。 */
    private class ModuleBackendPortsAdapter(private val ops: KsuModuleOps) : ModuleOpsPort {
        override fun isAvailable(): Boolean = runCatching { ops.isAvailable() }.getOrDefault(false)

        override fun listJson(): String = ops.listJson()

        override fun toggle(id: String, enable: Boolean): Boolean = ops.toggle(id, enable)

        override fun uninstall(id: String): Boolean = ops.uninstall(id)

        override fun undoUninstall(id: String): Boolean = ops.undoUninstall(id)

        override fun install(zip: File): Boolean = ops.install(zip)
    }

    /** [KsuSettingsFeature] → 设置仓库可见的窄接口。 */
    private class SettingsFeatureAdapter(
        private val feature: KsuSettingsFeature,
    ) : KsuFeaturePort {
        private val revision = java.util.concurrent.atomic.AtomicLong()
        private val writes = java.util.concurrent.atomic.AtomicInteger()
        override val readRevision: Long? get() = revision.get().takeIf { writes.get() == 0 }

        private inline fun write(operation: () -> Boolean): Boolean {
            writes.incrementAndGet()
            revision.incrementAndGet()
            return try { operation() } finally {
                revision.incrementAndGet()
                writes.decrementAndGet()
            }
        }

        override fun readSettings(includeExtras: Boolean): FeatureReadSnapshot {
            val result = feature.readSettings(includeExtras)
            return FeatureReadSnapshot(result.suCompatStatus, result.values.toMap(), result.receipts.mapValues { (_, receipt) ->
                FeatureReadReceipt(receipt.code, receipt.out.toList(), receipt.err.toList())
            })
        }

        override fun status(feature: String): String = this.feature.status(feature)

        override fun value(feature: String): Long? = this.feature.value(feature)

        override fun persistValue(feature: String): Long? = this.feature.persistValue(feature)

        override fun set(feature: String, enabled: Boolean): Boolean = write {
            // 即时生效 + 持久化是一对：只 set 不 save 会在重启后丢（审计 §7）。
            this.feature.set(feature, enabled) && this.feature.save()
        }

        override fun save(): Boolean = write { this.feature.save() }
    }
}
