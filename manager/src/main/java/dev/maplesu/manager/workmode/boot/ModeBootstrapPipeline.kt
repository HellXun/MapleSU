package dev.maplesu.manager.workmode.boot

import dev.maplesu.manager.workmode.ActivationSource
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.EnvironmentProbeResult
import dev.maplesu.manager.workmode.EnvironmentProbeRunner
import dev.maplesu.manager.workmode.detect.DetectionRequest
import dev.maplesu.manager.workmode.detect.DetectionResult
import dev.maplesu.manager.workmode.detect.ModeDetector
import dev.maplesu.manager.workmode.detect.ModeState
import dev.maplesu.manager.workmode.detect.DetectionRules
import dev.maplesu.manager.workmode.detect.EnvironmentSnapshot
import dev.maplesu.manager.workmode.detect.EnvironmentStateStore
import dev.maplesu.manager.workmode.detect.activeMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 启动判定闭环的可测编排（04 §1.3 / 01 §1.2 两段激活的可编码形式）：
 *
 * ```
 * 未判定（Unresolved）
 *   → detector.detect(request)            // 阶段 0/1；失败保持未判定
 *   → runtime.recordDetection(result)     // 记录判定事实
 *   → runtime.completeBootstrap(result)   // 完成 bootstrap gate（UI 骨架释放）
 *   → runtime.activate(state.activeMode)  // **只激活一次**；失败保持未判定
 *   → Activated / Unresolved / Failed
 * ```
 *
 * 顺序敏感：先 record 再 complete 再 activate；任何一步失败都**不得**回落到 LITE
 * （主文档:786、01 §0.2 硬约束 1）。本类不碰 Android，探测结果与运行时全部经端口注入，
 * 因此主机可测（[ModeRuntimePort] fake）。
 */
sealed interface BootstrapOutcome {
    data class Activated(
        val mode: WorkMode,
        val bootId: String,
        val state: ModeState,
    ) : BootstrapOutcome

    /** 未判定：state.activeMode == null、bootId 未知，或激活被拒（冲突/陈旧 boot）。 */
    data class Unresolved(
        val state: ModeState,
        val reason: String,
    ) : BootstrapOutcome

    data class Failed(val code: String, val detail: String) : BootstrapOutcome
}

/** 判定过程中的阶段（供 mode.gating 日志/诊断；不参与裁决）。 */
enum class BootstrapStage {
    DETECT_START,
    DETECT_OK,
    DETECT_FAILED,
    RECORDED,
    BOOTSTRAP_COMPLETED,
    ACTIVATED,
    UNRESOLVED,
    ACTIVATION_REJECTED,
}

/** [ModeRuntime] 的可替换端口：主机测试注入 fake，生产直接转发单例。 */
interface ModeRuntimePort {
    fun recordDetection(result: DetectionResult)
    fun completeBootstrap(result: DetectionResult)
    fun activate(mode: WorkMode, bootId: String, source: ActivationSource): Boolean
    fun failBootstrap(code: String) {}
}

/** 生产实现：逐项转发 [ModeRuntime]（不包装任何策略，策略在 pipeline 内）。 */
object ProductionModeRuntimePort : ModeRuntimePort {
    override fun recordDetection(result: DetectionResult) = ModeRuntime.recordDetection(result)
    override fun completeBootstrap(result: DetectionResult) = ModeRuntime.completeBootstrap(result)
    override fun activate(mode: WorkMode, bootId: String, source: ActivationSource): Boolean =
        ModeRuntime.activate(mode, bootId, source)
    override fun failBootstrap(code: String) = ModeRuntime.failBootstrap(code)
}

class ModeBootstrapPipeline(
    private val detector: ModeDetector,
    private val runtime: ModeRuntimePort = ProductionModeRuntimePort,
    /** 仅用于日志/诊断；异常不得影响判定。 */
    private val observer: (BootstrapStage, BootstrapOutcome?) -> Unit = { _, _ -> },
    private val probeRunner: EnvironmentProbeRunner = EnvironmentProbeRunner("maplesu-mode-detect"),
    /**
     * T14 前置环境检查（继承 / 降级探测）；返回非 null 时**取代**本次三阶段探测。
     *
     * 只用于两个终态：`ENV_KSU_NORMAL`（激活 KSU，解除互锁）与 `ENV_NOT_INSTALLED`
     * （阶段一立即终态；阶段二仍核对已建立的通道）。LITE / ULTRA / UNKNOWN 一律返回 null，
     * 交回既有两阶段判定（Ultra 行为零改动）。异常同样返回 null（绝不伪造结论）。
     */
    private val environmentOverride: (suspend () -> DetectionResult?)? = null,
    private val environmentStore: EnvironmentStateStore? = null,
) {
    suspend fun run(request: DetectionRequest): BootstrapOutcome {
        notify(BootstrapStage.DETECT_START, null)
        val overridden = if (request.allowRootProbe) environmentOverride?.let { probe ->
            runCatching { probe() }.getOrNull()
        } else null
        // A quick SU failure can be cached while a Hook becomes ready. Phase two
        // must still check the existing channel; it never starts a new attempt.
        val checkExistingChannel = overridden?.state == ModeState.NOT_INSTALLED
        val result = overridden?.takeUnless { checkExistingChannel } ?: run {
            val checked = probeRunner.run(request.timeoutMillis) {
                runBlocking { detector.detect(request) }
            }
            when (checked) {
                is EnvironmentProbeResult.Completed ->
                    overridden?.takeIf {
                        checkExistingChannel && checked.value.rule == DetectionRules.Rule.R12_INDETERMINATE
                    }?.let { fallback ->
                        // The state stays uninstalled until a live channel succeeds. Keep
                        // the completed S1 probe for pending boot-image setup and pairing.
                        fallback.copy(trace = fallback.trace + checked.value.trace)
                    } ?: checked.value
                EnvironmentProbeResult.TimedOut -> return failed("MODE_DETECT_TIMEOUT", "environment probe timed out")
                EnvironmentProbeResult.Busy -> return failed("MODE_DETECT_BUSY", "previous environment probe is still stopping")
                is EnvironmentProbeResult.Failed -> return failed("MODE_DETECT_FAILED", checked.cause.message.orEmpty())
            }
        }
        currentCoroutineContext().ensureActive()
        notify(BootstrapStage.DETECT_OK, null)

        runtime.recordDetection(result)
        notify(BootstrapStage.RECORDED, null)
        runtime.completeBootstrap(result)
        notify(BootstrapStage.BOOTSTRAP_COMPLETED, null)

        val mode = result.state.activeMode
            ?: return unresolved(result.state, "NO_ACTIVE_MODE", BootstrapStage.UNRESOLVED)
        if (result.bootId.isBlank()) {
            return unresolved(result.state, "BOOT_ID_UNKNOWN", BootstrapStage.UNRESOLVED)
        }
        val activated = runCatching {
            runtime.activate(mode, result.bootId, ActivationSource.BOOT_DETECTION)
        }.getOrDefault(false)
        if (!activated) {
            return unresolved(result.state, "ACTIVATION_REJECTED", BootstrapStage.ACTIVATION_REJECTED)
        }
        // Every live Ultra channel supersedes the quick check's earlier snapshot.
        // Publish only after activation accepts this boot's evidence.
        val ultraFingerprint = when (result.rule) {
            DetectionRules.Rule.R5_A_HANDSHAKE -> "lite:handshake"
            DetectionRules.Rule.R6_A_ULTRA -> "ultra:running"
            DetectionRules.Rule.R6_A_ULTRA_SAFE_MODE -> "ultra:fault"
            DetectionRules.Rule.R6_A_ULTRA_KERNEL_ONLY -> "ultra:kernel"
            else -> null
        }
        if (ultraFingerprint != null) {
            val suAvailable = when (result.rule) {
                DetectionRules.Rule.R5_A_HANDSHAKE -> true
                DetectionRules.Rule.R6_A_ULTRA -> environmentStore?.snapshot()
                    ?.takeIf { it.bootId == result.bootId }?.suAvailable == true
                else -> false
            }
            environmentStore?.save(EnvironmentSnapshot(
                mode = mode,
                suAvailable = suAvailable,
                ultraEnvFingerprint = ultraFingerprint,
                bootId = result.bootId,
                checkedAtMillis = result.observedAtMillis,
            ))
        }
        val outcome = BootstrapOutcome.Activated(mode, result.bootId, result.state)
        notify(BootstrapStage.ACTIVATED, outcome)
        return outcome
    }

    private fun failed(code: String, detail: String): BootstrapOutcome.Failed {
        runtime.failBootstrap(code)
        notify(BootstrapStage.DETECT_FAILED, null)
        return BootstrapOutcome.Failed(code, detail)
    }

    private fun unresolved(state: ModeState, reason: String, stage: BootstrapStage): BootstrapOutcome.Unresolved {
        val outcome = BootstrapOutcome.Unresolved(state, reason)
        notify(stage, outcome)
        return outcome
    }

    private fun notify(stage: BootstrapStage, outcome: BootstrapOutcome?) {
        runCatching { observer(stage, outcome) }
    }
}
