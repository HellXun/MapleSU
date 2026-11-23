package dev.maplesu.manager.workmode.switch

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.UltraState
import dev.maplesu.manager.workmode.service.AndroidUltraEnvironmentPort

/**
 * P0：Ultra 环境 × 双模式切换互操作策略（host 纯函数）。
 *
 * 背景：Ultra 环境 Running 时，模式 A 的活跃能力（Ultra 服务/模块/su_auth/`/data/<rootKey16>/`）
 * 若在切到 B 后继续存活，会违反"两模式互不干扰"；B→A 时又必须保证 Ultra 环境可恢复。
 *
 * 判定（P0-1 第 2 项，2026-11-01 改：**Ultra 环境不再前置阻断切换**）：
 * - A(LITE)→B(KSU)：RUNNING / FAULT / UNKNOWN / NOT_INSTALLED 一律 `Allowed`。
 *   RUNNING 只需 `ULTRA_STOP_VIA_STOCK_BOOT`：commit 写回 stock boot，重启即没有 Ultra 内核，
 *   不需要一个"先暂停 Ultra 环境"的用户动作；FAULT/UNKNOWN 不猜也不挡（UNKNOWN 是探针缓存的
 *   默认值，`UltraEnvironmentProbe.kt:45-46`，正是过去"莫名其妙 blocker"的来源），交给
 *   preflight / 实际执行（回读校验）用真实失败报错。
 * - B(KSU)→A(LITE)：缺 Ultra 资产同样不阻断，改为「走安装/修补流程」的决策
 *   （`PREPARE_ULTRA_RESTORE_BEFORE_SWITCH` + `RESTORE_ULTRA_AFTER_VERIFY`，见 `Allowed.actions`）。
 * - A→B 的 `NO_KEY` 仍阻断：源环境依赖 Ultra 根通道；B→A 的 `NO_KEY` 是首次准备
 *   Ultra 凭据的正常状态，根通道来自已确认的 Normal，由候选生产与重启后验证负责校验。
 * - 同模式 → `ULTRA_INPUT_INVALID`（输入错误，不是环境限制）。
 *
 * 最小 diff：`MustSuspendUltraFirst` / `MustPrepareUltraRestore` 两个类型**保留**（编译面 0 破坏，
 * 见 `ModeSwitchFacade` 的 when 分支）；`evaluate` 不再产出它们，
 * `UltraEnvSwitchCoordinator.beforeSwitch` 的对应分支成为不可达分支，P2 再清。
 */
sealed interface UltraSwitchDecision {
    data class Allowed(val actions: List<UltraSwitchAction>) : UltraSwitchDecision

    data class MustSuspendUltraFirst(val blocker: UltraSwitchBlocker) : UltraSwitchDecision

    data class MustPrepareUltraRestore(val blocker: UltraSwitchBlocker) : UltraSwitchDecision

    data class Block(val blocker: UltraSwitchBlocker) : UltraSwitchDecision
}

enum class UltraSwitchAction {
    /** A→B 前：暂停/停用 Ultra 环境并确认其写者静默（P0-1 后不再需要：见 [ULTRA_STOP_VIA_STOCK_BOOT]）。 */
    SUSPEND_ULTRA_BEFORE_COMMIT,

    /**
     * A→B：不单独停 Ultra；commit 写回 stock boot，下次启动即无 Ultra 内核（纯说明性 action，
     * `Allowed.actions` 目前没有消费者，见 `ModeSwitchFacade.kt:185`）。
     */
    ULTRA_STOP_VIA_STOCK_BOOT,

    /** B→A 前：确认 Ultra 环境可恢复（Running 或可 installEnvironment(Boot)）。 */
    PREPARE_ULTRA_RESTORE_BEFORE_SWITCH,

    /** B→A 后：恢复 Ultra 环境（或提示 UI 安装/激活）。 */
    RESTORE_ULTRA_AFTER_VERIFY,
}

data class UltraSwitchBlocker(val code: String, val detail: String)

object UltraSwitchCodes {
    const val ULTRA_ENV_MUST_SUSPEND = "ULTRA_ENV_MUST_SUSPEND"
    const val ULTRA_ENV_MUST_PREPARE_RESTORE = "ULTRA_ENV_MUST_PREPARE_RESTORE"
    const val ULTRA_ENV_STATE_UNKNOWN = "ULTRA_ENV_STATE_UNKNOWN"
    const val ULTRA_ENV_NO_KEY = "ULTRA_ENV_NO_KEY"
    const val ULTRA_ENV_FAULT = "ULTRA_ENV_FAULT"
    const val ULTRA_SUSPEND_FAILED = "ULTRA_SUSPEND_FAILED"
    const val ULTRA_RESTORE_FAILED = "ULTRA_RESTORE_FAILED"
    const val ULTRA_RESTORE_UNSUPPORTED = "ULTRA_RESTORE_UNSUPPORTED"
    const val ULTRA_SUSPEND_UNSUPPORTED = "ULTRA_SUSPEND_UNSUPPORTED"
    const val ULTRA_INPUT_INVALID = "ULTRA_INPUT_INVALID"
}

object UltraEnvSwitchPolicy {

    fun evaluate(fromMode: WorkMode, toMode: WorkMode, ultraState: UltraState): UltraSwitchDecision {
        if (fromMode == toMode) {
            return UltraSwitchDecision.Block(UltraSwitchBlocker(UltraSwitchCodes.ULTRA_INPUT_INVALID, "same mode"))
        }
        return when (fromMode) {
            WorkMode.LITE -> when (ultraState) {
                // 写回 stock boot 即停 Ultra 内核（commit 写的是下次启动用的分区），无需独立 suspend。
                UltraState.RUNNING -> UltraSwitchDecision.Allowed(
                    listOf(UltraSwitchAction.ULTRA_STOP_VIA_STOCK_BOOT),
                )
                // FAULT/UNKNOWN 不猜也不挡（UNKNOWN = 探针缓存默认值，是假阻断的真凶）；
                // 真正的失败交给 preflight / 实际执行回读暴露。
                UltraState.FAULT, UltraState.UNKNOWN, UltraState.NOT_INSTALLED ->
                    UltraSwitchDecision.Allowed(emptyList())
                // NO_KEY：真实硬前提（无 rootKey ⇒ Ultra 根通道不可用）。
                UltraState.NO_KEY -> noKeyBlock()
            }
        }
    }

    /**
     * Ultra 源环境的 `NO_KEY` 阻断：没有 Ultra rootKey ⇒ Ultra 没有 Ultra 根通道，
     * 放行只会让用户点完后拿到一个更差的错误。文案问题见 08 报告（`SwitchFlow.kt:176`
     * 把 `ULTRA_ENV_NO_KEY` 映射成「Ultra 环境状态未知」，需 P2 补专用字符串）。
     */
    private fun noKeyBlock(): UltraSwitchDecision = UltraSwitchDecision.Block(
        UltraSwitchBlocker(UltraSwitchCodes.ULTRA_ENV_NO_KEY, "ultra rootKey absent"),
    )
}

data class UltraSwitchResult(
    val ok: Boolean,
    val code: String = "",
    val detail: String = "",
    /** 执行期失败是否需要人工恢复（暂停/恢复 Ultra 环境不是可静默重试的分区写）。 */
    val recoveryRequired: Boolean = false,
) {
    companion object {
        val OK = UltraSwitchResult(true)
        fun failed(code: String, detail: String, recoveryRequired: Boolean = false) =
            UltraSwitchResult(false, code, detail, recoveryRequired)
    }
}

/** Ultra 环境能力端口；具体实现由 Ultra runtime owner 提供，不可用必须显式失败。 */
interface UltraEnvironmentPort {
    fun state(): UltraState

    /** Current boot and key only; persisted pairing and no-key su are not proof. */
    fun kernelRootVerified(): Boolean = false

    /** A→B 前暂停/停用 Ultra 环境（含写者静默）；不可用 → 返回明确错误。 */
    fun suspend(): HookResult

    /** B→A 后恢复 Ultra 环境（Running 或提示 UI 安装激活）；不可用 → 返回明确错误。 */
    fun restore(): HookResult
}

/**
 * 执行器：把 [UltraEnvSwitchPolicy] 落到生命周期动作。
 *
 * P0-1 第 2 项后：
 * - `beforeSwitch`：`evaluate` 只可能返回 `Allowed` / `Block(NO_KEY)`，所以**不再调用
 *   `port.suspend()`**。`Must*` 两个分支保留但不可达（生产 `suspend()` 恒失败，
 *   `ProductionUltraEnvironmentPort.kt:37-40`）。
 * - `afterVerified`（wave4 / S-2 起）：**方向感知 + 新鲜探针**，见 [afterVerified]。
 */
class UltraEnvSwitchCoordinator(
    private val port: UltraEnvironmentPort,
    /**
     * 新鲜探针（S-2）：`afterVerified` 必须用**重新观测**的值，不许读缓存。
     *
     * 默认 `null` = 生产路径：识别 [AndroidUltraEnvironmentPort] 并调它已有的 `refresh()`
     * （`ProductionUltraEnvironmentPort.kt:47-49` → `UltraEnvironmentProbe.refresh(context)`；
     * 后者绕过 3s TTL 缓存并在后台线程真的问一次 SDK，`UltraEnvironmentProbe.kt:104-107`）。
     * `AndroidUltraEnvironmentPort` 不在 wave4 允许改动范围内，所以这里按类型识别而不是给它
     * 加接口方法；探针抛错/不可用时退回 `port.state()`（缓存），绝不伪造 RUNNING。
     */
    private val freshProbe: (() -> UltraState)? = null,
) {

    fun evaluate(fromMode: WorkMode, toMode: WorkMode): UltraSwitchDecision =
        UltraEnvSwitchPolicy.evaluate(fromMode, toMode, port.state())

    fun beforeSwitch(fromMode: WorkMode, toMode: WorkMode): UltraSwitchResult {
        return when (val decision = evaluate(fromMode, toMode)) {
            is UltraSwitchDecision.Block -> UltraSwitchResult.failed(decision.blocker.code, decision.blocker.detail)
            // 不可达（evaluate 不再产出）：保留以维持 sealed when 穷尽性，P2 清理。
            is UltraSwitchDecision.MustSuspendUltraFirst -> {
                val suspended = runCatching { port.suspend() }.getOrElse { error ->
                    HookResult.failed(UltraSwitchCodes.ULTRA_SUSPEND_FAILED, error.javaClass.simpleName)
                }
                if (!suspended.ok) {
                    UltraSwitchResult.failed(
                        suspended.code.ifEmpty { UltraSwitchCodes.ULTRA_SUSPEND_FAILED },
                        suspended.detail, recoveryRequired = true,
                    )
                } else {
                    UltraSwitchResult.OK
                }
            }
            // 不可达（同上）：B→A 缺资产已改为 Allowed + 走安装/修补流程。
            is UltraSwitchDecision.MustPrepareUltraRestore ->
                UltraSwitchResult.failed(decision.blocker.code, decision.blocker.detail)
            is UltraSwitchDecision.Allowed -> UltraSwitchResult.OK
        }
    }

    /**
     * 切换完成后的 Ultra 环境校验（**方向感知 + 新鲜探针**，S-2）。
     *
     * 为什么必须判：`LITE`(Ultra) 的根提供方就是 Ultra 环境（`RootExecutionRouter.kt:26-38`）。
     * wave2 的"恒 `OK`"让"切到 Ultra 但 Ultra 里没有根"也报 `Verified`（fail-open）；
     * 更早的实现调恒失败的 `port.restore()` ⇒ 假失败。现在两者都不做，改用真实探针：
     *
     * - `toMode == KSU`（A→B，Ultra→Normal）：commit 写回 stock boot，**期望 Ultra 已停**
     *   （`NOT_INSTALLED`/`FAULT` 是预期结果）。其余状态 → `OK`；仍 `RUNNING` → 失败
     *   （`ULTRA_ENV_MUST_SUSPEND`，**不**带 `recoveryRequired`：分区已写好、数据没坏，
     *   facade 会给 `Incomplete` 而不是 `RECOVERY_REQUIRED`）。
     * - `toMode == LITE`（B→A，Normal→Ultra）：当前 Key 根通道已确认即通过；
     *   `RUNNING` 或 `UNKNOWN`/`NOT_INSTALLED` + 本 boot 的 Key UID0 实测 → `OK`。
     *   helper 就绪仍单独判断；`FAULT`/`NO_KEY`/未验证通道 → 失败（`ULTRA_ENV_STATE_UNKNOWN` +
     *   `recoveryRequired = true`：Ultra 实际没起来，需要人工恢复 Ultra 环境）。
     *
     * 探针新鲜度：见构造参数 [freshProbe]。缓存值不可信 —— `UNKNOWN` 正是
     * `UltraEnvironmentProbe` 的缓存默认值（`UltraEnvironmentProbe.kt:45-46`）。
     */
    fun afterVerified(toMode: WorkMode): UltraSwitchResult {
        val state = freshState()
        return when (toMode) {
            WorkMode.LITE -> if (state == UltraState.RUNNING ||
                ((state == UltraState.UNKNOWN || state == UltraState.NOT_INSTALLED) &&
                    runCatching { port.kernelRootVerified() }.getOrDefault(false))) {
                UltraSwitchResult.OK
            } else {
                UltraSwitchResult.failed(
                    UltraSwitchCodes.ULTRA_ENV_STATE_UNKNOWN,
                    "ultra=$state after switch: Ultra environment is unavailable",
                    recoveryRequired = true,
                )
            }
        }
    }

    /**
     * 真正重新观测一次（S-2）。顺序：显式注入的探针 → 生产端口 `refresh()` → 端口 `state()`。
     * 任一步抛错都退回下一步，最终兜底 `UltraState.UNKNOWN`（不伪造 RUNNING）。
     */
    private fun freshState(): UltraState {
        freshProbe?.let { probe -> runCatching { probe() }.getOrNull()?.let { return it } }
        runCatching {
            when (val current = port) {
                is AndroidUltraEnvironmentPort -> current.refresh()
                else -> current.state()
            }
        }.getOrNull()?.let { return it }
        return runCatching { port.state() }.getOrDefault(UltraState.UNKNOWN)
    }
}
