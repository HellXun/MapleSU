package dev.maplesu.manager.workmode.boot

import dev.maplesu.manager.workmode.detect.DetectionOrigin
import dev.maplesu.manager.workmode.detect.DetectionRequest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/** 启动判定的两个阶段（04 §1.3 / 01 §2.5 同意规则）。 */
enum class BootstrapPhase(val wire: String) {
    /** 阶段一：本地只读 + 非特权探针，**不起 root**。 */
    LOCAL_ONLY("phase1"),

    /** 阶段二：UI 可见后、root 证据可安全获取时执行，未判定时允许重试。 */
    AFTER_ROOT_GRANTED("phase2"),
}

/**
 * 两段式启动判定编排（可主机测试；Android 接线见 [ModeBootstrap]）。
 *
 * 为什么需要阶段二（审计结论）：Lite 设备的可用性证据只有"凭据 → UID0 握手"与 root 会话，
 * 阶段一按同意规则 `allowRootProbe=false / allowLiteExecutor=false` **不允许**尝试它们，
 * 于是判定只能落到 `MODE_A_INSTALLED_UNBOUND`（activeMode=null）。若没有阶段二，
 * Lite 会被自己的门控整体关死（activate 永不发生）。阶段二在 UI 可见后以
 * `AFTER_ROOT_GRANTED + allowRootProbe + allowLiteExecutor` 重新判定并激活一次。
 *
 * 幂等约束：
 * - 阶段一只跑一次；阶段二同一时刻只执行一次，临时失败后可重试；
 * - 阶段二在已激活（isResolved）时直接跳过，不重复探测 root；
 * - 两阶段都走同一个 [ModeBootstrapPipeline]（recordDetection → completeBootstrap → activate）。
 */
class ModeBootstrapCoordinator(
    private val pipelineProvider: () -> ModeBootstrapPipeline?,
    private val isResolved: () -> Boolean = { false },
    private val observer: (BootstrapPhase, BootstrapOutcome?) -> Unit = { _, _ -> },
) {
    private val phaseOneClaimed = AtomicBoolean(false)
    private val phaseTwoClaimed = AtomicBoolean(false)

    /** 阶段一请求：不拿 root、不试 Lite executor（启动期同意规则）。 */
    val phaseOneRequest: DetectionRequest = LOCAL_ONLY_REQUEST

    /** 阶段二请求：UI 可见后允许 root 取证 + Lite 握手。 */
    val phaseTwoRequest: DetectionRequest = AFTER_ROOT_GRANTED_REQUEST

    fun tryClaimPhaseOne(): Boolean = phaseOneClaimed.compareAndSet(false, true)

    fun tryClaimPhaseTwo(): Boolean = phaseTwoClaimed.compareAndSet(false, true)

    /** 阶段一：只会得到"已可激活的 B 态"或"未判定"（A 设备必然未判定，等待阶段二）。 */
    suspend fun runPhaseOne(): BootstrapOutcome? {
        if (!tryClaimPhaseOne()) return null
        return run(phaseOneRequest, BootstrapPhase.LOCAL_ONLY)
    }

    /**
     * 阶段二：激活成功后只执行一次，未判定/临时失败释放执行权。
     * - 已激活（例如阶段一已按 B 内核激活）→ 返回 null，不重复探测；
     * - 未激活 → 用 root 证据 + Lite 握手重新判定，成功则激活一次。
     */
    suspend fun runPhaseTwo(): BootstrapOutcome? {
        if (!tryClaimPhaseTwo()) return null
        var completed = false
        try {
            if (runCatching { isResolved() }.getOrDefault(false)) {
                completed = true
                notify(BootstrapPhase.AFTER_ROOT_GRANTED, null)
                return null
            }
            val outcome = run(phaseTwoRequest, BootstrapPhase.AFTER_ROOT_GRANTED)
            completed = outcome is BootstrapOutcome.Activated
            return outcome
        } finally {
            if (!completed) phaseTwoClaimed.set(false)
        }
    }

    private suspend fun run(request: DetectionRequest, phase: BootstrapPhase): BootstrapOutcome? {
        val outcome = try {
            val pipeline = pipelineProvider()
            pipeline?.run(request) ?: BootstrapOutcome.Failed("MODE_PIPELINE_UNAVAILABLE", "environment detector unavailable")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            BootstrapOutcome.Failed("MODE_BOOTSTRAP_FAILED", error.message.orEmpty())
        }
        notify(phase, outcome)
        return outcome
    }

    private fun notify(phase: BootstrapPhase, outcome: BootstrapOutcome?) {
        runCatching { observer(phase, outcome) }
    }

    companion object {
        /** 阶段一：启动期不拿 root、未排除 KSU 冲突前不试 Lite executor（主文档:204/378）。 */
        val LOCAL_ONLY_REQUEST: DetectionRequest = DetectionRequest(
            origin = DetectionOrigin.STARTUP,
            allowRootProbe = false,
            allowLiteExecutor = false,
        )

        /**
         * 阶段二：UI 可见后取证（主文档:378-382 的"获得 root 后再判定"）。
         * 只检查已经建立的通道；不会运行提权或创建新会话。
         */
        val AFTER_ROOT_GRANTED_REQUEST: DetectionRequest = DetectionRequest(
            origin = DetectionOrigin.AFTER_ROOT_GRANTED,
            allowRootProbe = true,
            allowLiteExecutor = true,
        )
    }
}
