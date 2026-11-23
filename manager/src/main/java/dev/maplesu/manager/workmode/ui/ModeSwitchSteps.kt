package dev.maplesu.manager.workmode.ui

import dev.maplesu.manager.workmode.switch.SwitchStateNames

/**
 * 切换进度的人话步骤：把 stage / step / state wire 值映射成固定的步骤序列。
 *
 * wire 词表由切换服务定义，这里只做投影；不认识的值返回 null，界面回退到通用文案，
 * 不把原始值展示给用户。纯 Kotlin，主机可测。
 */
enum class ModeSwitchStep {
    PREPARE,
    BACKUP,
    BUILD,
    WRITE,
    RESTORE,
    REBOOT,
    VERIFY,
}

enum class StepStatus { DONE, ACTIVE, PENDING, FAILED }

data class ModeSwitchStepRow(val step: ModeSwitchStep, val status: StepStatus)

/** 粒度从细到粗：step → stage → state。三者都不认识时返回 null。 */
fun modeSwitchStepOf(state: String, stage: String, step: String): ModeSwitchStep? =
    stepOfStepWire(step) ?: stepOfStageWire(stage) ?: stepOfStateWire(state)

private fun stepOfStepWire(step: String): ModeSwitchStep? = when (step) {
    "begin" -> ModeSwitchStep.PREPARE
    "snapshot", "4b" -> ModeSwitchStep.BACKUP
    "produce" -> ModeSwitchStep.BUILD
    "4a", "4c", "4d" -> ModeSwitchStep.WRITE
    "4e", "4f", "4g" -> ModeSwitchStep.RESTORE
    "S5" -> ModeSwitchStep.REBOOT
    "6.1", "6.2", "6.3", "6.4" -> ModeSwitchStep.VERIFY
    else -> null
}

private fun stepOfStageWire(stage: String): ModeSwitchStep? = when (stage) {
    "S0", "S1" -> ModeSwitchStep.PREPARE
    "S2" -> ModeSwitchStep.BACKUP
    "S3" -> ModeSwitchStep.BUILD
    "S4" -> ModeSwitchStep.WRITE
    "S5" -> ModeSwitchStep.REBOOT
    "S6" -> ModeSwitchStep.VERIFY
    else -> null
}

private fun stepOfStateWire(state: String): ModeSwitchStep? = when (state) {
    SwitchStateNames.BEGIN -> ModeSwitchStep.PREPARE
    SwitchStateNames.SNAPSHOT -> ModeSwitchStep.BACKUP
    SwitchStateNames.PRODUCE -> ModeSwitchStep.BUILD
    SwitchStateNames.COMMIT -> ModeSwitchStep.WRITE
    SwitchStateNames.REBOOT_REQUIRED -> ModeSwitchStep.REBOOT
    SwitchStateNames.VERIFYING, SwitchStateNames.VERIFIED -> ModeSwitchStep.VERIFY
    else -> null
}

/**
 * 步骤列表的完成度。
 *
 * [current] 未知时只把第一步标为进行中；[failed] 时当前步标红；[finished] 时全部完成。
 */
fun modeSwitchStepRows(
    current: ModeSwitchStep?,
    failed: Boolean = false,
    finished: Boolean = false,
): List<ModeSwitchStepRow> {
    val activeIndex = current?.ordinal ?: 0
    return ModeSwitchStep.entries.map { step ->
        val status = when {
            finished -> StepStatus.DONE
            step.ordinal < activeIndex -> StepStatus.DONE
            step.ordinal > activeIndex -> StepStatus.PENDING
            failed -> StepStatus.FAILED
            else -> StepStatus.ACTIVE
        }
        ModeSwitchStepRow(step, status)
    }
}

/** 进度页标题态。 */
enum class ModeSwitchHeadline {
    ATTACHING,
    RUNNING,
    ROLLING_BACK,
    VERIFYING,
    AWAITING_REBOOT,
    DONE,
    ROLLED_BACK,
    FAILED,
    NEEDS_RECOVERY,
    MISSING,
}

fun modeSwitchHeadline(phase: ModeSwitchPhase, stateWire: String, rebootRequired: Boolean): ModeSwitchHeadline =
    when (phase) {
        ModeSwitchPhase.ATTACHING -> ModeSwitchHeadline.ATTACHING
        ModeSwitchPhase.MISSING -> ModeSwitchHeadline.MISSING
        ModeSwitchPhase.NEEDS_RECOVERY -> ModeSwitchHeadline.NEEDS_RECOVERY
        ModeSwitchPhase.SUCCEEDED ->
            if (rebootRequired) ModeSwitchHeadline.AWAITING_REBOOT else ModeSwitchHeadline.DONE

        ModeSwitchPhase.FAILED ->
            if (stateWire == SwitchStateNames.ROLLED_BACK) ModeSwitchHeadline.ROLLED_BACK else ModeSwitchHeadline.FAILED

        ModeSwitchPhase.RUNNING -> when (stateWire) {
            SwitchStateNames.ROLLING_BACK -> ModeSwitchHeadline.ROLLING_BACK
            SwitchStateNames.VERIFYING -> ModeSwitchHeadline.VERIFYING
            else -> ModeSwitchHeadline.RUNNING
        }
    }
