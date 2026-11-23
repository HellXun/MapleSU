package dev.maplesu.manager.workmode.ui

import androidx.compose.runtime.Immutable
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.ModeState
import dev.maplesu.manager.workmode.switch.BaselineErrors
import dev.maplesu.manager.workmode.switch.ModeSwitchCodes
import dev.maplesu.manager.workmode.service.ModeSwitchFacade
import dev.maplesu.manager.workmode.service.ModeSwitchRuntime
import dev.maplesu.manager.workmode.service.SwitchBlocker as BackendBlocker
import dev.maplesu.manager.workmode.service.uiSupport

/**
 * 切换 UI 的可用性/阻断模型（r3 `ModeSwitchFacade` 的 UI 投影；纯 Kotlin，主机可测）。
 *
 * 所有判断条件都来自已有事实：ModeState/current/masked/backend uiSupport。
 * 未判定/外来 Root/无 baseline/W19 未就绪/pending 等一律给出明确阻断码，
 * 页面按码给文案，**不**猜测、不回退 LITE（04 §6.1）。
 */
@Immutable
data class SwitchBlocker(val code: String, val detail: String = "")

@Immutable
data class SwitchBackendSnapshot(
    val available: Boolean = false,
    val reason: String = "",
    val baselineId: String? = null,
    val extraBlockers: List<SwitchBlocker> = emptyList(),
) {
    companion object {
        val UNAVAILABLE = SwitchBackendSnapshot(available = false, reason = "switch backend not wired")
    }
}

@Immutable
data class SwitchAvailability(
    val canSwitch: Boolean = false,
    val current: WorkMode? = null,
    val target: WorkMode? = null,
    val baselineId: String? = null,
    val blockers: List<SwitchBlocker> = emptyList(),
) {
    companion object {
        val BLOCKED = SwitchAvailability()
    }
}

object SwitchBlockerCodes {
    const val STEALTH_MASKED = "STEALTH_MASKED"
    const val MODE_UNDETERMINED = "MODE_UNDETERMINED"
    const val MODE_NOT_INSTALLED = "MODE_NOT_INSTALLED"
    const val MODE_FOREIGN_SU = "MODE_FOREIGN_SU"
    const val MODE_A_UNBOUND = "MODE_A_UNBOUND"
    const val MODE_B_KERNEL_ONLY = "MODE_B_KERNEL_ONLY"
    const val SAME_MODE = "SAME_MODE"
    const val PENDING_SWITCH = "PENDING_SWITCH"
    const val PENDING_INSTALL = "PENDING_INSTALL"
    const val BACKEND_UNAVAILABLE = "BACKEND_UNAVAILABLE"
    const val BASELINE_MISSING = "BASELINE_MISSING"
    const val W19_IMPLANT_MISSING = "W19_IMPLANT_MISSING"
    const val EXPORT_FAILED = "EXPORT_FAILED"
    const val BATTERY_LOW = "BATTERY_LOW"
    const val SPACE_INSUFFICIENT = "SPACE_INSUFFICIENT"
    const val SELINUX_NOT_ENFORCING = "SELINUX_NOT_ENFORCING"

    /** Ultra→Normal：Ultra 环境仍在运行，必须先暂停/还原 Ultra 环境（当前未支持，明确告知用户）。 */
    const val ULTRA_ENV_MUST_SUSPEND = "ULTRA_ENV_MUST_SUSPEND"

    /** Normal→Ultra：需要先准备 Ultra 环境恢复材料（当前未支持）。 */
    const val ULTRA_ENV_MUST_RESTORE = "ULTRA_ENV_MUST_RESTORE"

    /** Ultra 环境状态读不到/故障：拒绝切换（fail-closed）。 */
    const val ULTRA_ENV_STATE_UNKNOWN = "ULTRA_ENV_STATE_UNKNOWN"

    /** 来自 r3 S0 预检/后端快照的错误码（SWITCH_* 等）原样透传，UI 用通用文案展示。 */
    const val BACKEND_BLOCKED = "BACKEND_BLOCKED"
}

/**
 * 切换可用性纯裁决（保留给主机表驱动测试与页面态预判）：
 * `ModeState + current + stealth + pendingSwitch + back-end snapshot → SwitchAvailability`。
 */
object SwitchAvailabilityPolicy {

    fun evaluate(
        state: ModeState,
        current: WorkMode?,
        stealthLooksStealthy: Boolean,
        pendingSwitch: Boolean,
        backend: SwitchBackendSnapshot,
    ): SwitchAvailability {
        if (stealthLooksStealthy) {
            return SwitchAvailability(
                canSwitch = false,
                current = current,
                target = targetOf(current),
                blockers = listOf(SwitchBlocker(SwitchBlockerCodes.STEALTH_MASKED)),
            )
        }

        val target = targetOf(current)
        val blockers = mutableListOf<SwitchBlocker>()

        when (state) {
            ModeState.MODE_A_ACTIVE, ModeState.MODE_B_ACTIVE -> Unit
            ModeState.MODE_B_KERNEL_ONLY -> blockers += SwitchBlocker(SwitchBlockerCodes.MODE_B_KERNEL_ONLY)
            ModeState.MODE_A_INSTALLED_UNBOUND -> blockers += SwitchBlocker(SwitchBlockerCodes.MODE_A_UNBOUND)
            ModeState.FOREIGN_SU -> blockers += SwitchBlocker(SwitchBlockerCodes.MODE_FOREIGN_SU)
            ModeState.NOT_INSTALLED -> blockers += SwitchBlocker(SwitchBlockerCodes.MODE_NOT_INSTALLED)
            ModeState.INDETERMINATE -> blockers += SwitchBlocker(SwitchBlockerCodes.MODE_UNDETERMINED)
        }

        if (current == null || target == null) {
            blockers += SwitchBlocker(SwitchBlockerCodes.SAME_MODE)
        }
        if (pendingSwitch) {
            blockers += SwitchBlocker(SwitchBlockerCodes.PENDING_SWITCH, backend.reason)
        }
        when {
            !backend.available -> blockers += SwitchBlocker(
                SwitchBlockerCodes.BACKEND_UNAVAILABLE,
                backend.reason,
            )

            backend.baselineId.isNullOrBlank() -> blockers += SwitchBlocker(SwitchBlockerCodes.BASELINE_MISSING)
        }
        blockers += backend.extraBlockers

        return SwitchAvailability(
            canSwitch = blockers.isEmpty(),
            current = current,
            target = target,
            baselineId = backend.baselineId,
            blockers = blockers,
        )
    }

    fun targetOf(current: WorkMode?): WorkMode? = when (current) {
        WorkMode.LITE -> WorkMode.KSU
        WorkMode.KSU -> WorkMode.LITE
        null -> null
    }
}

/**
 * r3 切换门面的注入点（W08 集成）：
 * `ModeSwitchRuntime.initialize(ctx)` 之后由 `ModeSwitchRuntime.facadeOrNull()` 返回实例。
 * 未初始化/装配失败 → null：页面显示"后端未接入"，**绝不伪造进度**。
 */
object SwitchFacadeProvider {
    @Volatile
    var factory: (() -> ModeSwitchFacade?)? = { ModeSwitchRuntime.facadeOrNull() }

    fun current(): ModeSwitchFacade? = runCatching { factory?.invoke() }.getOrNull()
}

/** r3 阻断码 → UI 阻断码（页面文案见 `WorkModeStrings.blockerRes`）。 */
fun mapBackendBlockers(blockers: List<BackendBlocker>): List<SwitchBlocker> = blockers.map { blocker ->
    SwitchBlocker(code = mapBackendCode(blocker.code), detail = blocker.detail)
}

internal fun mapBackendCode(code: String): String = when (code) {
    ModeSwitchCodes.STATE_UNDETERMINED -> SwitchBlockerCodes.MODE_UNDETERMINED
    ModeSwitchCodes.NOT_INSTALLED -> SwitchBlockerCodes.MODE_NOT_INSTALLED
    ModeSwitchCodes.FOREIGN_SU -> SwitchBlockerCodes.MODE_FOREIGN_SU
    ModeSwitchCodes.EXPORT_REQUIRED -> SwitchBlockerCodes.EXPORT_FAILED
    ModeSwitchCodes.SAME_MODE -> SwitchBlockerCodes.SAME_MODE
    ModeSwitchCodes.PENDING -> SwitchBlockerCodes.PENDING_SWITCH
    ModeSwitchCodes.INSTALL_PENDING -> SwitchBlockerCodes.PENDING_INSTALL
    ModeSwitchCodes.IMPLANT_RESTORE_MISSING -> SwitchBlockerCodes.W19_IMPLANT_MISSING
    ModeSwitchCodes.BATTERY_LOW -> SwitchBlockerCodes.BATTERY_LOW
    ModeSwitchCodes.SPACE_INSUFFICIENT -> SwitchBlockerCodes.SPACE_INSUFFICIENT
    ModeSwitchCodes.SELINUX_NOT_ENFORCING -> SwitchBlockerCodes.SELINUX_NOT_ENFORCING
    ModeSwitchCodes.ROOT_UNAVAILABLE -> SwitchBlockerCodes.BACKEND_UNAVAILABLE
    // P0-1 第 3 项：基线专属码统一给"没法自动建立基线"的文案（不是 generic
    // "前置检查未通过"），也不会被误导成"镜像损坏"这类假原因；detail 只进日志。
    // （`BaselineErrors` 与 `ModeSwitchCodes` 同包，直接引常量比写字面量更抗改名。）
    BaselineErrors.BASELINE_MISSING,
    BaselineErrors.BASELINE_STALE,
    BaselineErrors.BASELINE_CORRUPT,
    BaselineErrors.BASELINE_UNCLEAN,
    BaselineErrors.BASELINE_PROVENANCE_UNKNOWN,
    BaselineErrors.BASELINE_PROMOTION_FORBIDDEN,
    BaselineErrors.BASELINE_CAPTURE_UNSUPPORTED -> SwitchBlockerCodes.BASELINE_MISSING
    // Ultra 环境互操作（返回码来自 UltraSwitchCodes；这里按 wire 字符串映射，避免跨包依赖）。
    "ULTRA_ENV_MUST_SUSPEND" -> SwitchBlockerCodes.ULTRA_ENV_MUST_SUSPEND
    "ULTRA_ENV_MUST_PREPARE_RESTORE" -> SwitchBlockerCodes.ULTRA_ENV_MUST_RESTORE
    "ULTRA_ENV_STATE_UNKNOWN", "ULTRA_ENV_FAULT", "ULTRA_ENV_NO_KEY" -> SwitchBlockerCodes.ULTRA_ENV_STATE_UNKNOWN
    else -> SwitchBlockerCodes.BACKEND_BLOCKED
}

/**
 * 从 r3 门面读取一次可用性（含 S0b 只读预检）：
 * 隐身优先（不展示任何模式信息）；其余阻断原样映射；supported 决定 CTA 可用。
 */
suspend fun ModeSwitchFacade.availabilityFor(target: WorkMode, masked: Boolean): SwitchAvailability {
    val current = currentMode()
    if (masked) {
        return SwitchAvailability(
            canSwitch = false,
            current = current,
            target = target,
            blockers = listOf(SwitchBlocker(SwitchBlockerCodes.STEALTH_MASKED)),
        )
    }
    val support = uiSupport(target)
    return SwitchAvailability(
        canSwitch = support.supported,
        current = support.currentMode ?: current,
        target = support.target ?: target,
        blockers = mapBackendBlockers(support.blockers),
    )
}
