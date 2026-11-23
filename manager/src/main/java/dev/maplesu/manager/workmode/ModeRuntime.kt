package dev.maplesu.manager.workmode

import android.content.Context
import com.maplesu.core.BuildConfig
import dev.maplesu.manager.workmode.detect.DetectionResult
import dev.maplesu.manager.workmode.detect.ModeState
import dev.maplesu.manager.workmode.detect.ProcBootIdSource
import dev.maplesu.manager.workmode.detect.activeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/** 激活来源。只用于日志与证据，不参与裁决。 */
enum class ActivationSource { BOOT_DETECTION, RESTORE_AFTER_CLEAR, PENDING_SWITCH_VERIFIED, TEST_OVERRIDE }

/** 重载原因。区分"清 UI 缓存"与"必须整机重启"。 */
enum class ReloadReason { SETTINGS_MIRROR_RESTORED, MODE_COMMITTED, USER_REQUEST, LANGUAGE_CHANGED }

sealed interface ModeActivation {
    data object Unresolved : ModeActivation

    data class Active(
        val mode: WorkMode,
        val bootId: String,
        val source: ActivationSource,
        val detectedState: ModeState,
        val activatedAtMillis: Long,
    ) : ModeActivation
}

/** bootstrap 的返回值；bootstrap 自身不阻塞首帧（主文档:378）。 */
data class BootstrapHandle(
    val stateAtStartup: ModeActivation,
    val detection: Deferred<DetectionResult>,
)

/** 重载投递端口（清 ViewModelStore + Activity recreate）。 */
fun interface ReloadHandler {
    fun onReloadRequired(context: Context, reason: ReloadReason, processRestartRequired: Boolean)
}

/**
 * 进程内唯一模式事实：进程启动时唯一确定；运行中不翻转。
 *
 * 未判定 = [ModeActivation.Unresolved]，禁止任何模式专属任务（主文档:83）。
 * 本类不读磁盘、不起 root；数据由 `ModeDetector` 产出后经 [recordDetection] / [activate] 注入。
 *
 * 两段激活（01 §10 C11）：`bootstrap()` 只做阶段 0/1（不拿 root）→ root 取证后
 * 调 `detect(origin=AFTER_ROOT_GRANTED)` → `activate()`。
 */
object ModeRuntime {
    @Volatile
    private var current: ModeActivation = ModeActivation.Unresolved

    @Volatile
    private var lastDetection: DetectionResult? = null

    @Volatile
    private var bootIdProvider: () -> String? = { ProcBootIdSource.read() }

    @Volatile
    private var clockProvider: () -> Long = { System.currentTimeMillis() }

    @Volatile
    private var reloadHandler: ReloadHandler? = null

    @Volatile
    private var logger: (String) -> Unit = {}

    @Volatile
    private var bootstrapHandle: BootstrapHandle? = null

    private var activated = CompletableDeferred<WorkMode?>()
    @Volatile
    private var detectionSettled = CompletableDeferred<Unit>()
    private val _detectionRevision = MutableStateFlow(0L)
    val detectionRevision = _detectionRevision.asStateFlow()

    @Volatile
    var detectionFailure: String? = null
        private set

    val detectionComplete: Boolean get() = detectionSettled.isCompleted

    @Volatile
    var capabilities: ModeCapabilities = ModeCapabilities.NONE
        private set

    /** 已激活模式；null = 未判定。**调用方必须处理 null**。 */
    val active: WorkMode? get() = (current as? ModeActivation.Active)?.mode

    val activation: ModeActivation get() = current

    /** 最近一次判定结果（未判定阶段为 null）。 */
    val latestDetection: DetectionResult? get() = lastDetection

    /**
     * 未判定阶段的唯一入口：只做本地只读提示 + 启动后台探测，**不起 root**。
     * 返回的 handle 允许 UI 先渲染"未判定"骨架；detection 由探测编排者完成后
     * 经 [completeBootstrap] 完成（本对象不自己调度协程，避免隐式线程）。
     */
    @Suppress("UNUSED_PARAMETER")
    fun bootstrap(context: Context): BootstrapHandle {
        val deferred = CompletableDeferred<DetectionResult>()
        val handle = BootstrapHandle(current, deferred)
        bootstrapHandle = handle
        return handle
    }

    /** 探测编排者把 bootstrap 阶段的结果送回；幂等（重复完成不会抛）。 */
    fun completeBootstrap(result: DetectionResult) {
        recordDetection(result)
        bootstrapHandle?.detection?.let { deferred ->
            if (deferred is CompletableDeferred<DetectionResult>) deferred.complete(result)
        }
    }

    /** 记录一次判定事实（不激活）。 */
    fun recordDetection(result: DetectionResult) {
        lastDetection = result
        detectionFailure = null
    }

    /** A completed check may legitimately leave the mode unresolved. */
    fun finishDetection() {
        detectionSettled.complete(Unit)
        _detectionRevision.update { it + 1 }
    }

    fun beginDetection() {
        detectionSettled = CompletableDeferred()
        detectionFailure = null
    }

    fun failBootstrap(code: String) {
        detectionFailure = code
        finishDetection()
        (bootstrapHandle?.detection as? CompletableDeferred<DetectionResult>)
            ?.completeExceptionally(IllegalStateException(code))
    }

    suspend fun awaitDetectionSettled(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis.coerceAtLeast(1)) {
            detectionSettled.await()
            true
        } ?: false

    /** Page reads end when detection settles, including a completed unresolved result. */
    suspend fun awaitPageMode(timeoutMillis: Long): WorkMode? {
        active?.let { return it }
        return withTimeoutOrNull(timeoutMillis.coerceAtLeast(1L)) {
            // Phase two can replace the per-attempt Deferred after a page starts waiting.
            detectionRevision.first { active != null || detectionComplete }
            active
        }
    }

    /**
     * 激活一次。规则（顺序敏感）：
     * 1) 已激活且 mode 相同 → true（幂等，不覆盖 activatedAt）；
     * 2) 已激活且 mode 不同 → false + MODE_ACTIVATION_CONFLICT（不翻转）；
     * 3) bootId 与当前 /proc 值不符 → false + MODE_ACTIVATION_STALE_BOOT；
     * 4) 未激活 → 置 Active，完成 gate，记日志。
     *
     * 附加守卫（01 §1.2 的必要条件）：必须有与 [mode] 一致的判定结果，
     * 即 `latestDetection.state.activeMode == mode`，否则拒绝并记 MODE_ACTIVATION_NO_DETECTION
     * （未判定态禁止默认 Lite）。
     */
    @Synchronized
    fun activate(mode: WorkMode, bootId: String, source: ActivationSource): Boolean {
        val snapshot = current
        if (snapshot is ModeActivation.Active) {
            if (snapshot.mode == mode) return true
            logger("MODE_ACTIVATION_CONFLICT current=${snapshot.mode.suffix} requested=${mode.suffix}")
            return false
        }
        val currentBootId = runCatching { bootIdProvider() }.getOrNull()?.trim().orEmpty()
        if (currentBootId.isEmpty() || currentBootId != bootId.trim()) {
            logger("MODE_ACTIVATION_STALE_BOOT expected=$bootId current=$currentBootId")
            return false
        }
        val detection = lastDetection
        if (detection == null || detection.state.activeMode != mode) {
            logger(
                "MODE_ACTIVATION_NO_DETECTION requested=${mode.suffix} " +
                    "state=${detection?.state?.name ?: "NONE"}",
            )
            return false
        }
        current = ModeActivation.Active(
            mode = mode,
            bootId = bootId.trim(),
            source = source,
            detectedState = detection.state,
            activatedAtMillis = runCatching { clockProvider() }.getOrDefault(System.currentTimeMillis()),
        )
        capabilities = ModeCapabilities.forMode(mode)
        activated.complete(mode)
        finishDetection()
        logger("mode.gating activated=${mode.suffix} source=$source state=${detection.state.name}")
        return true
    }

    /** 挂起等待激活（BOOT_COMPLETED/恢复页用），超时返回 null，**超时不得当成 LITE**。 */
    suspend fun awaitActivated(timeoutMillis: Long): WorkMode? {
        val timeout = timeoutMillis.coerceAtLeast(1L)
        return withTimeoutOrNull(timeout) { activated.await() }
    }

    /** 注册重载投递端口（UI 接线；主机测试可注入）。 */
    fun installReloadHandler(handler: ReloadHandler) {
        reloadHandler = handler
    }

    /**
     * 重载：清 ViewModelStore + Activity recreate；**不代替设备重启**（主文档:187、:408）。
     * 模式变化时必须重启进程 → processRestartRequired = reason == MODE_COMMITTED。
     */
    fun requireReload(context: Context, reason: ReloadReason) {
        val handler = reloadHandler ?: return
        handler.onReloadRequired(context, reason, reason == ReloadReason.MODE_COMMITTED)
    }

    /** 单次执行的模式门控；未激活时**不执行** block 并返回 null。 */
    inline fun <T> runIfLite(crossinline block: () -> T): T? =
        if (active == WorkMode.LITE) block() else null

    inline fun <T> runIfKsu(crossinline block: () -> T): T? =
        if (false) block() else null

    /** 必须在已激活模式下执行，否则抛 MODE_NOT_RESOLVED。用于不可静默跳过的路径。 */
    fun requireMode(expected: WorkMode) {
        val actual = active
        if (actual != expected) {
            throw IllegalStateException(
                "MODE_NOT_RESOLVED expected=${expected.suffix} actual=${actual?.suffix ?: "UNRESOLVED"}",
            )
        }
    }

    // ---------------------------------------------------------------------
    // 测试注入（internal；prod 代码不得调用）
    // ---------------------------------------------------------------------

    /** TEST_OVERRIDE 只在 debug 构建可见（01 §8.1 守卫用例）。 */
    internal fun testOverrideAllowed(debugBuild: Boolean): Boolean = debugBuild

    internal fun overrideForTest(activation: ModeActivation) {
        check(testOverrideAllowed(BuildConfig.DEBUG)) { "MODE_TEST_OVERRIDE_FORBIDDEN" }
        current = activation
        capabilities = (activation as? ModeActivation.Active)
            ?.let { ModeCapabilities.forMode(it.mode) } ?: ModeCapabilities.NONE
        if (activation is ModeActivation.Active) activated.complete(activation.mode)
    }

    internal fun installBootIdProviderForTest(provider: () -> String?) {
        bootIdProvider = provider
    }

    internal fun installClockForTest(provider: () -> Long) {
        clockProvider = provider
    }

    internal fun installLoggerForTest(sink: (String) -> Unit) {
        logger = sink
    }

    internal fun resetForTest() {
        current = ModeActivation.Unresolved
        lastDetection = null
        capabilities = ModeCapabilities.NONE
        bootIdProvider = { ProcBootIdSource.read() }
        clockProvider = { System.currentTimeMillis() }
        reloadHandler = null
        logger = {}
        bootstrapHandle = null
        activated = CompletableDeferred()
        detectionSettled = CompletableDeferred()
        detectionFailure = null
        _detectionRevision.value = 0
    }
}
