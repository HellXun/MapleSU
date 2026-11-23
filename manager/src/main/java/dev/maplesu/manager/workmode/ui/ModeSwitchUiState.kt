package dev.maplesu.manager.workmode.ui

import androidx.compose.runtime.Immutable
import dev.maplesu.manager.workmode.WorkMode

/**
 * 切换进度页 UI 状态（02 §3.1 的 S0–S6 wire 词表；UI 不自造阶段枚举）。
 */
enum class ModeSwitchPhase {
    ATTACHING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    NEEDS_RECOVERY,
    MISSING,
}

@Immutable
data class ModeSwitchUiState(
    val switchId: String = "",
    val phase: ModeSwitchPhase = ModeSwitchPhase.ATTACHING,
    val stateWire: String = "",
    val stage: String = "",
    val step: String = "",
    val text: String = "",
    val from: WorkMode? = null,
    val to: WorkMode? = null,
    val code: String = "",
    val detail: String = "",
    val pending: Boolean = false,
    val rebootRequired: Boolean = false,
    val canRollback: Boolean = false,
    val canResumeReboot: Boolean = false,
    val requiresExternalRecovery: Boolean = false,
    val verifyFailures: Int = 0,
    val actionRunning: Boolean = false,
    val hasServiceRecord: Boolean = false,
) {
    /**
     * 进行中/接管中禁止返回（路由级 + 手势级），不以超时或读失败推断事务已结束。
     * MISSING / SUCCEEDED / FAILED / NEEDS_RECOVERY 不拦截。
     * 空 id 无法 attach，不应留在一个永久拦截返回的接管页。
     */
    val blocksBack: Boolean
        get() = actionRunning || when (phase) {
            ModeSwitchPhase.ATTACHING -> switchId.isNotBlank()
            ModeSwitchPhase.RUNNING -> true
            else -> false
        }

    val canRetry: Boolean get() = phase == ModeSwitchPhase.FAILED && !actionRunning
    val canAbort: Boolean get() = phase == ModeSwitchPhase.FAILED && hasServiceRecord && !requiresExternalRecovery && !actionRunning
}

/** 进度页动作（皮肤共用）。 */
@Immutable
data class ModeSwitchActions(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onAbort: () -> Unit,
    val onRollback: () -> Unit,
    val onReboot: () -> Unit,
    val onOpenRecovery: () -> Unit,
    val onSaveLog: () -> Unit,
)
