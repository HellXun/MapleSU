package com.maplesu.core.ui.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.maplesu.core.R
import com.maplesu.core.ksuApp
import dev.maplesu.manager.litehidden.LiteHiddenCodePolicy
import dev.maplesu.manager.litehidden.LiteHiddenOutcome
import dev.maplesu.manager.litehidden.LiteHiddenPaths
import dev.maplesu.manager.litehidden.LiteHiddenPolicy
import dev.maplesu.manager.litehidden.LiteHiddenRateLimiter
import dev.maplesu.manager.litehidden.LiteHiddenState
import dev.maplesu.manager.litehidden.LiteHiddenStateReader
import dev.maplesu.manager.litehidden.LiteHiddenTraceItem
import dev.maplesu.manager.litehidden.LiteHiddenTracePolicy
import dev.maplesu.manager.litehidden.LiteHiddenTraceResult
import dev.maplesu.manager.litehidden.LiteHiddenVisibility
import dev.maplesu.manager.runtime.RootBackend
import dev.maplesu.manager.runtime.RootExec
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.storage.ModePaths
import java.io.File

/**
 * Lite 隐藏模式的进程内缓存（UI 热路径只读内存，绝不每次组合都起 root）。
 *
 * 与 KSU stealth **完全独立**：这里只缓存 `/data/adb/maplesu/lite_hidden` 的 App 级状态。
 *
 * 冷启动语义（2026-10-30 第二轮修复）：首次访问先读一次**中性 prefs 的 last-known 种子**
 * （保存确定的 ON/OFF，首次无记录仍待确认）。旧实现只信内存默认值 OFF：隐藏开启后进程被杀，
 * 下一次冷启动就把"隐藏中"的机器当正常机器 → 首页又画出切换模式入口（用户实测的
 * "隐藏态仍显示入口"）。UNKNOWN 保留上次确定值，不确认、不覆盖。
 */
object LiteHiddenRuntime {

    /** 中性 prefs 里的 last-known 键；键不存在与已确认 OFF 分开。 */
    private const val PREF_LAST_ON = "lite_hidden_last_on"

    /** 真实读取最多尝试次数（启动早期 root/Ultra 通道可能尚未就绪）。 */
    private const val MAX_REAL_READ_ATTEMPTS = 3

    @Volatile
    private var state: LiteHiddenState = LiteHiddenState.OFF

    /** 进程内是否已拿到可信状态（prefs 种子命中或一次真实 root 读取）。 */
    @Volatile
    private var observed = false

    /** 本进程是否已经做过**真实 root 读取**（prefs 种子不算）。 */
    @Volatile
    private var realObserved = false

    /** 本进程已尝试真实读取的次数（失败可重试，避免一次启动失败永久 UNKNOWN）。 */
    @Volatile
    private var realReadAttempts = 0

    /** 是否还值得再读一次（最多 3 次：启动早期 root/Ultra 通道可能尚未就绪）。 */
    fun needsRealRead(): Boolean = realReadAttempts < MAX_REAL_READ_ATTEMPTS

    fun markRealReadAttempted() {
        realReadAttempts += 1
    }

    /** last-known「明确隐藏 ON」种子（与 [state] 分开：UNKNOWN 不覆盖它）。 */
    @Volatile
    private var lastKnownSeed = false

    @Volatile
    private var loaded = false

    fun hasRealObservation(): Boolean {
        ensureLoaded()
        return realObserved
    }

    /**
     * 当前隐藏状态；首次访问读一次 last-known 种子（很小的 prefs 读，不发起 root）。
     * 读不到种子 / 读失败 → 仍是默认 OFF 且 [hasObservation] 为 false，调用方必须按 UNKNOWN 处理。
     */
    fun currentState(): LiteHiddenState {
        ensureLoaded()
        return state
    }

    /** 进程内是否已拿到可信状态；false = 还没读过（入口/文案一律按 UNKNOWN 保守处理）。 */
    fun hasObservation(): Boolean {
        ensureLoaded()
        return observed
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            // 只在**读成功**时落 loaded：ksuApp 尚未就绪 / prefs 暂时读不到都不能被当成
            // "已经确认没有隐藏"（那会让隐藏中的机器露出入口）。读失败 = hasObservation 仍 false。
            val read = runCatching {
                val prefs = ksuApp.getSharedPreferences(ModePaths.NEUTRAL_PREFS, Context.MODE_PRIVATE)
                if (prefs.contains(PREF_LAST_ON)) prefs.getBoolean(PREF_LAST_ON, false) else null
            }
            if (read.isSuccess) {
                lastKnownSeed = read.getOrNull() == true
                state = if (lastKnownSeed) LiteHiddenState.ON else LiteHiddenState.OFF
                observed = read.getOrNull() != null
                loaded = true
            }
        }
    }

    internal fun update(value: LiteHiddenState) {
        // 失败不是新事实：保留上次 ON/OFF，也保留首次读取的重试机会。
        if (value == LiteHiddenState.UNKNOWN) return
        loaded = true
        observed = true
        realObserved = true
        realReadAttempts = MAX_REAL_READ_ATTEMPTS
        state = value
        lastKnownSeed = value == LiteHiddenState.ON
        persist(value)
        HiddenFactsSignal.notifyChanged()
    }

    /**
     * 只持久化明确读取到的状态：
     * - ON → 写标记（下次冷启动首帧就知道要藏）；
     * - OFF → 写 false（真实读到关闭才允许恢复入口）；
     * - UNKNOWN → **不改写**（读失败不能算"已关闭"，也不能拿它覆盖 last-known）。
     */
    private fun persist(value: LiteHiddenState) {
        runCatching {
            val prefs = ksuApp.getSharedPreferences(ModePaths.NEUTRAL_PREFS, Context.MODE_PRIVATE)
            when (value) {
                LiteHiddenState.ON -> {
                    lastKnownSeed = true
                    prefs.edit().putBoolean(PREF_LAST_ON, true).apply()
                }
                LiteHiddenState.OFF -> {
                    lastKnownSeed = false
                    prefs.edit().putBoolean(PREF_LAST_ON, false).apply()
                }
                LiteHiddenState.UNKNOWN -> Unit
            }
        }
    }

    /** 上次已知「明确隐藏开着」的种子（prefs；UNKNOWN 不会覆盖它）。 */
    fun lastKnownOn(): Boolean {
        ensureLoaded()
        return lastKnownSeed
    }

    /**
     * 当前是否处在"Lite App 级隐藏"生效态。
     *
     * 判据见 [HiddenStartupConfirm.masks]：
     *  - **模式未判定（null）也按隐藏处理**：冷启动首帧 `ModeRuntime.active` 还是 null，
     *    旧实现要求 `== LITE` 才隐藏 → 隐藏机器首帧被画成「正在运行」，随后才翻「未安装」，
     *    这段时间就是自曝（2026-10-31 用户实测）。
     *  - **UNKNOWN（读失败）只有在 last-known 种子为 ON 时才伪装**：否则一次启动 root 读失败
     *    就会让正常机器整页变成「未安装」。
     */
    fun isMaskingLite(): Boolean =
        HiddenStartupConfirm.masks(currentState(), lastKnownOn(), ModeRuntime.active)

    fun visibility(): LiteHiddenVisibility = LiteHiddenPolicy.evaluate(ModeRuntime.active, currentState())

    fun resetForTest() {
        observed = false
        realObserved = false
        realReadAttempts = 0
        lastKnownSeed = false
        loaded = false
        state = LiteHiddenState.OFF
    }
}

/**
 * 解析状态文件读取结果（纯函数，主机可测）：
 * - null（root 不可用 / 命令失败）→ UNKNOWN；
 * - 文件不存在（`LITE_MISSING`）→ OFF；
 * - 末行明确 `1` / `0` → ON / OFF；
 * - 其它（空、坏写、夹杂错误输出）→ UNKNOWN（保守按 ON）。
 *
 * 取**最后一行**而不是首字符：root 包装脚本可能先打印警告/回显，只有最后一行才是结果。
 */
internal fun parseLiteHiddenStateOutput(raw: String?): LiteHiddenState = LiteHiddenStateReader.parse(raw)

/**
 * Lite 隐藏模式的 Android 门面：状态读写（root）、密令恢复、痕迹收敛。
 * 所有判定委托给 `dev.maplesu.manager.litehidden` 的纯策略。
 */
object LiteHiddenController {

    private const val TAG = "LiteHidden"
    private val rateLimiter = LiteHiddenRateLimiter()

    /**
     * 最近一次 root 命令失败的结构化原因（诊断用）。
     *
     * 来自 [RootExec] / `RootExecutionRouter` 的 detail（`ROOT_BACKEND_UNAVAILABLE` /
     * `ULTRA_ROOT_FAILED:…` 等）——**不伪造 root，也不把失败说成"未开启"**。
     */
    @Volatile
    private var lastRootFailure: String? = null

    fun lastRootFailureReason(): String? = lastRootFailure

    fun isLiteMode(): Boolean = ModeRuntime.active == WorkMode.LITE

    /**
     * 经模式感知 [RootExec] → `RootExecutionRouter` 执行 root 脚本
     * （LITE 下 Ultra `run_root_cmd` 优先、Lite 内核凭据兜底；不再直接走旧 `getRootShell()`）。
     * 失败返回 null 并记录明确原因（调用方保守显示/失败，不伪造）。
     */
    private fun rootCommand(script: String): String? {
        // 兼容早期调用点：router 尚未由 Application 安装时，用进程 Context 现场装配一次。
        val context = runCatching { ksuApp }.getOrNull()
        val result = runCatching { RootExec.execute(context, script) }.getOrElse { error ->
            lastRootFailure = "ROOT_EXEC_EXCEPTION:${error.javaClass.simpleName}"
            return null
        }
        if (!result.ok) {
            lastRootFailure = result.detail.ifEmpty { "${result.backend}:code=${result.code}" }
            return null
        }
        lastRootFailure = null
        return result.output
    }

    /** 当前根通道（Settings 卡「根通道：…」）。 */
    fun rootBackend(): RootBackend = RootExec.preferredBackend()

    fun rootBackendLabel(): String = when (RootExec.capability().backend) {
        RootBackend.LITE_KERNEL -> RootBackend.LITE_KERNEL.label
        RootBackend.ULTRA_ENV -> RootBackend.ULTRA_ENV.label
        RootBackend.KSU -> RootBackend.KSU.label
        RootBackend.NONE -> RootBackend.NONE.label
    }

    /** Lite 根可用语义：Ultra 环境严格 Running（不再走 libsu shell）。 */
    fun rootAvailableForLite(): Boolean = RootExec.rootAvailableForLite()

    /** 模式感知 prefs（经 SettingsPrefsRouter：A=settings / B=settings_B）。 */
    private fun litPrefs() = dev.maplesu.manager.workmode.storage.SettingsPrefsRouter(ksuApp).prefs()

    /** 读状态（需 root）：文件不存在 → OFF；读失败/无 root → UNKNOWN（保守 ON）。 */
    @Synchronized
    fun readState(): LiteHiddenState {
        val context = runCatching { ksuApp }.getOrNull()
        val result = LiteHiddenStateReader.read(execute = { RootExec.execute(context, it) })
        lastRootFailure = result.failure
        if (result.failure != null) android.util.Log.w(TAG, "hidden state read failed: ${result.failure}")
        LiteHiddenRuntime.update(result.state)
        return result.state
    }

    fun refresh(): LiteHiddenState = readState()

    /**
     * 开启/关闭 Lite 隐藏（root 写状态文件；成功回读校验）。
     * 定稿：Lite 隐藏不再有拨号密令，因此不写 code 文件；恢复唯一入口是 WebUI。
     */
    @Synchronized
    fun setEnabled(enable: Boolean): LiteHiddenOutcome {
        if (ModeRuntime.active != WorkMode.LITE) return LiteHiddenOutcome.NOT_SUPPORTED
        val out = rootCommand(LiteHiddenPaths.stateScript(write = true, value = if (enable) "1" else "0"))
        val ok = out?.contains("LITE_OK") == true
        if (!ok) {
            LiteHiddenRuntime.update(LiteHiddenState.UNKNOWN)
            return LiteHiddenOutcome.FAILED
        }
        // 回读校验：写入的值必须真的生效（恢复后 UI 真恢复的判据）。
        val after = readState()
        val expected = if (enable) LiteHiddenState.ON else LiteHiddenState.OFF
        return if (after == expected) LiteHiddenOutcome.SUCCESS else LiteHiddenOutcome.FAILED
    }

    fun applyTrace(context: Context, selection: Map<LiteHiddenTraceItem, Boolean>): List<LiteHiddenTraceResult> {
        val filesRoot = context.filesDir.absolutePath
        return LiteHiddenTraceItem.entries.map { item ->
            if (selection[item] != true) {
                LiteHiddenTraceResult(item, ok = true, reason = "not selected")
            } else {
                runTraceItem(context, filesRoot, item)
            }
        }
    }

    private fun runTraceItem(
        context: Context,
        filesRoot: String,
        item: LiteHiddenTraceItem,
    ): LiteHiddenTraceResult {
        if (!LiteHiddenTracePolicy.hasPersistentArtifact(item)) {
            return LiteHiddenTraceResult(item, ok = true, reason = "no persistent artifact")
        }
        return when (item) {
            LiteHiddenTraceItem.DEBUG_EXPORTS -> deleteDirTree(filesRoot, "$filesRoot/diagnostics", item)
            LiteHiddenTraceItem.LOGCAT_TAGS -> {
                // 尽力而为：清 main/crash 两个 buffer 中的本 App 关键日志（-s 过滤在 -c 下不可用）。
                val out = rootCommand(
                    "logcat -b main -c 2>/dev/null; logcat -b crash -c 2>/dev/null; echo LITE_OK",
                )
                if (out?.contains("LITE_OK") == true) {
                    LiteHiddenTraceResult(item, true)
                } else {
                    LiteHiddenTraceResult(item, false, "root backend unavailable")
                }
            }

            LiteHiddenTraceItem.BACKGROUND_WRITERS -> runCatching {
                // 复用现有 service 生命周期：停掉模块启动服务（不杀 App 进程）。
                context.stopService(
                    Intent(context, dev.maplesu.manager.modules.LiteModuleBootService::class.java),
                )
                LiteHiddenTraceResult(item, true)
            }.getOrElse { LiteHiddenTraceResult(item, false, it.message.orEmpty()) }

            LiteHiddenTraceItem.INTENT_ALIASES,
            LiteHiddenTraceItem.RECENT_INSTALL_LOG,
            -> LiteHiddenTraceResult(item, true, "no persistent artifact")
        }
    }

    private fun deleteDirTree(
        filesRoot: String,
        path: String,
        item: LiteHiddenTraceItem,
    ): LiteHiddenTraceResult {
        if (!LiteHiddenTracePolicy.canDelete(filesRoot, path)) {
            return LiteHiddenTraceResult(item, ok = false, reason = "forbidden path")
        }
        return runCatching {
            File(path).deleteRecursively()
            LiteHiddenTraceResult(item, true)
        }.getOrElse { LiteHiddenTraceResult(item, false, it.message.orEmpty()) }
    }

    /** 恢复成功后拉起管理器（不依赖桌面图标）；失败只记日志。 */
    fun launchManager() {
        runCatching {
            val intent = Intent(ksuApp, com.maplesu.core.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ksuApp.startActivity(intent)
        }
    }

    // ── 最终形态：一个按钮（开启隐藏模式）────────────────────────────────

    /** 恢复端口（默认 18427；仅保存，开启时应用）。 */
    fun preferredPort(): Int = runCatching {
        litPrefs().getInt(LiteHiddenPaths.PREF_PORT, dev.maplesu.manager.litehidden.PortInput.DEFAULT)
    }.getOrDefault(dev.maplesu.manager.litehidden.PortInput.DEFAULT)

    fun setPreferredPort(port: Int): Boolean {
        val min = dev.maplesu.manager.litehidden.PortInput.MIN
        val max = dev.maplesu.manager.litehidden.PortInput.MAX
        if (port < min || port > max) return false
        return runCatching {
            litPrefs().edit().putInt(LiteHiddenPaths.PREF_PORT, port).apply()
            true
        }.getOrDefault(false)
    }

    /** WebUI 恢复链接（仅内存缓存；未启动过 → null，不伪造）。 */
    fun webUiRecoveryLink(): String? = runCatching {
        dev.maplesu.manager.liteweb.LiteWebAdminController(ksuApp).url()
    }.getOrNull()

    /** 恢复密令是否已显式配置（默认 70707 仍可用于拨号恢复）。 */
    fun failureMessageRes(failure: dev.maplesu.manager.litehidden.HiddenFailure): Int = when (failure) {
        dev.maplesu.manager.litehidden.HiddenFailure.BACKEND_UNAVAILABLE ->
            R.string.lite_hidden_reason_backend_unavailable

        dev.maplesu.manager.litehidden.HiddenFailure.ROOT_UNAVAILABLE ->
            R.string.lite_hidden_reason_root_unavailable

        dev.maplesu.manager.litehidden.HiddenFailure.WEBUI_NOT_STARTED ->
            R.string.lite_hidden_reason_webui

        dev.maplesu.manager.litehidden.HiddenFailure.PORT_IN_USE ->
            R.string.lite_hidden_reason_port

        dev.maplesu.manager.litehidden.HiddenFailure.NO_RECOVERY_CHANNEL ->
            R.string.lite_hidden_reason_recovery

        dev.maplesu.manager.litehidden.HiddenFailure.INVALID_PORT ->
            R.string.lite_hidden_reason_port_invalid

        dev.maplesu.manager.litehidden.HiddenFailure.VERIFY_FAILED ->
            R.string.lite_hidden_reason_verify

        dev.maplesu.manager.litehidden.HiddenFailure.UNKNOWN ->
            R.string.lite_hidden_reason_unknown

        // 新增失败码（wm-core 扩展）统一回退"未知错误"，不猜原因。
        else -> R.string.lite_hidden_reason_unknown
    }

    /**
     * 开启隐藏模式（一个按钮的完整编排）：校验 → 保存端口 → 调后端（启用 WebUI + 状态 +
     * 伪卸载 + 回读）→ 回读验证。后端未注入即 BACKEND_UNAVAILABLE（fail-closed，保持未隐藏）。
     */
    suspend fun enableHidden(port: Int): dev.maplesu.manager.litehidden.HiddenEnableResult {
        if (!isLiteMode()) {
            return dev.maplesu.manager.litehidden.HiddenEnableResult(
                success = false,
                failure = dev.maplesu.manager.litehidden.HiddenFailure.BACKEND_UNAVAILABLE,
            )
        }
        if (LiteHiddenRuntime.currentState().isEffectivelyOn) {
            return dev.maplesu.manager.litehidden.HiddenEnableResult(
                success = false,
                failure = dev.maplesu.manager.litehidden.HiddenFailure.VERIFY_FAILED,
            )
        }
        val input = dev.maplesu.manager.litehidden.HiddenModePolicy.validatePort(port.toString())
        if (!input.valid) {
            return dev.maplesu.manager.litehidden.HiddenEnableResult(
                success = false,
                failure = dev.maplesu.manager.litehidden.HiddenFailure.INVALID_PORT,
            )
        }
        if (!rootAvailableForLite()) {
            return dev.maplesu.manager.litehidden.HiddenEnableResult(
                success = false,
                failure = dev.maplesu.manager.litehidden.HiddenFailure.ROOT_UNAVAILABLE,
            )
        }
        setPreferredPort(port)
        val result = dev.maplesu.manager.litehidden.HiddenModeBackends.enableHidden(port)
        if (!result.success) return result
        // 回读验证：后端说成功但状态不是 ON → 仍按失败（不假装隐藏）。
        if (readState() != LiteHiddenState.ON) {
            return dev.maplesu.manager.litehidden.HiddenEnableResult(
                success = false,
                failure = dev.maplesu.manager.litehidden.HiddenFailure.VERIFY_FAILED,
            )
        }
        return result
    }

    @Suppress("unused")
    private fun unusedUri(): Uri? = null
}
