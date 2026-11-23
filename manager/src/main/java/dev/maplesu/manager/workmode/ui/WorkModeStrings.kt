package dev.maplesu.manager.workmode.ui

import androidx.annotation.StringRes
import com.maplesu.core.R
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.ModeState
import dev.maplesu.manager.workmode.switch.ModeSwitchCodes

/**
 * 工作模式 UI 的字符串映射（04 §2.5 / §5.2）。
 *
 * 页面只允许经本文件取状态文案；`when` 必须写全（7 个 `ModeState` / 2 个 `WorkMode`），
 * **禁止** `else ->` 兜底（04 §6.1）。新增状态时编译器会强制补分支。
 */
object WorkModeStrings {

    @StringRes
    fun stateTitleRes(state: ModeState): Int = when (state) {
        ModeState.MODE_A_ACTIVE -> R.string.work_mode_state_a_active_title
        ModeState.MODE_B_ACTIVE -> R.string.work_mode_state_b_active_title
        ModeState.MODE_B_KERNEL_ONLY -> R.string.work_mode_state_b_kernel_only_title
        ModeState.MODE_A_INSTALLED_UNBOUND -> R.string.work_mode_state_a_unbound_title
        ModeState.FOREIGN_SU -> R.string.work_mode_state_foreign_title
        ModeState.NOT_INSTALLED -> R.string.work_mode_state_none_title
        ModeState.INDETERMINATE -> R.string.work_mode_state_indeterminate_title
    }

    @StringRes
    fun stateSummaryRes(state: ModeState): Int = when (state) {
        ModeState.MODE_A_ACTIVE -> R.string.work_mode_state_a_active_summary
        ModeState.MODE_B_ACTIVE -> R.string.work_mode_state_b_active_summary
        ModeState.MODE_B_KERNEL_ONLY -> R.string.work_mode_state_b_kernel_only_summary
        ModeState.MODE_A_INSTALLED_UNBOUND -> R.string.work_mode_state_a_unbound_summary
        ModeState.FOREIGN_SU -> R.string.work_mode_state_foreign_summary
        ModeState.NOT_INSTALLED -> R.string.work_mode_state_none_summary
        ModeState.INDETERMINATE -> R.string.work_mode_state_indeterminate_summary
    }

    @StringRes
    fun modeNameRes(mode: WorkMode): Int = when (mode) {
        WorkMode.LITE -> R.string.work_mode_lite
        WorkMode.KSU -> R.string.work_mode_ksu
    }

    /** 该判定态是否提供"去安装页修复"的次 CTA（04 §2.5 表格）。 */
    fun offersInstallShortcut(state: ModeState): Boolean = when (state) {
        ModeState.MODE_B_KERNEL_ONLY,
        ModeState.MODE_A_INSTALLED_UNBOUND,
        ModeState.NOT_INSTALLED,
        ModeState.INDETERMINATE -> true

        ModeState.MODE_A_ACTIVE,
        ModeState.MODE_B_ACTIVE,
        ModeState.FOREIGN_SU -> false
    }

    /**
     * 切换阻断码 → 文案。阻断码是**开放字符串集合**（后端 S0 错误码原样透传），
     * 未知码回退到通用说明（这不是模式分派，不违反 04 §6.1 的 enum 穷尽要求）。
     */
    @StringRes
    fun blockerRes(code: String): Int = when (code) {
        SwitchBlockerCodes.STEALTH_MASKED -> R.string.work_mode_blocker_stealth
        SwitchBlockerCodes.MODE_UNDETERMINED -> R.string.work_mode_blocked_undetermined
        SwitchBlockerCodes.MODE_NOT_INSTALLED -> R.string.work_mode_blocker_not_installed
        SwitchBlockerCodes.MODE_FOREIGN_SU -> R.string.work_mode_blocker_foreign
        SwitchBlockerCodes.MODE_A_UNBOUND -> R.string.work_mode_blocker_a_unbound
        SwitchBlockerCodes.MODE_B_KERNEL_ONLY -> R.string.work_mode_blocker_b_kernel_only
        SwitchBlockerCodes.SAME_MODE -> R.string.work_mode_blocker_same_mode
        SwitchBlockerCodes.PENDING_SWITCH -> R.string.work_mode_blocker_pending_switch
        SwitchBlockerCodes.BACKEND_UNAVAILABLE -> R.string.work_mode_blocker_backend
        SwitchBlockerCodes.BASELINE_MISSING -> R.string.work_mode_blocker_baseline
        SwitchBlockerCodes.PENDING_INSTALL -> R.string.work_mode_blocker_pending_install
        SwitchBlockerCodes.W19_IMPLANT_MISSING -> R.string.work_mode_blocker_w19
        SwitchBlockerCodes.EXPORT_FAILED -> R.string.work_mode_failure_export
        SwitchBlockerCodes.BATTERY_LOW -> R.string.work_mode_blocker_battery
        SwitchBlockerCodes.SPACE_INSUFFICIENT -> R.string.work_mode_blocker_space
        SwitchBlockerCodes.SELINUX_NOT_ENFORCING -> R.string.work_mode_blocker_selinux
        SwitchBlockerCodes.ULTRA_ENV_MUST_SUSPEND -> R.string.work_mode_blocker_ultra_env_suspend
        SwitchBlockerCodes.ULTRA_ENV_MUST_RESTORE -> R.string.work_mode_blocker_ultra_env_restore
        SwitchBlockerCodes.ULTRA_ENV_STATE_UNKNOWN -> R.string.work_mode_blocker_ultra_env_unknown
        SwitchBlockerCodes.BACKEND_BLOCKED -> R.string.work_mode_blocker_backend_blocked
        else -> R.string.work_mode_blocker_generic
    }

    /**
     * 切换失败码 → 一句人话。失败码同样是开放集合：写入/产物/备份/校验各归一类，
     * 其余按阻断码映射，最后回退到通用文案。码本身只进日志。
     */
    @StringRes
    fun failureRes(code: String): Int = when {
        code.startsWith("WRITE_") -> R.string.work_mode_failure_write
        code.startsWith("PRODUCE_") -> R.string.work_mode_failure_produce
        code == ModeSwitchCodes.SNAPSHOT_FAILED -> R.string.work_mode_failure_snapshot
        code.contains("MOVE_") || code.contains("MATERIALIZE") -> R.string.work_mode_failure_move
        code.startsWith("VERIFY_") || code.startsWith("SUPERUSER_") -> R.string.work_mode_failure_verify
        code == ModeSwitchCodes.ROOT_UNAVAILABLE -> R.string.work_mode_failure_root
        else -> when (val mapped = mapBackendCode(code)) {
            SwitchBlockerCodes.BACKEND_BLOCKED -> R.string.work_mode_failure_generic
            else -> blockerRes(mapped)
        }
    }

    /** 模式一句话定位。 */
    @StringRes
    fun modeTaglineRes(mode: WorkMode): Int = when (mode) {
        WorkMode.LITE -> R.string.work_mode_lite_tagline
        WorkMode.KSU -> R.string.work_mode_ksu_tagline
    }

    /** 模式介绍的三段文案（实现 / 模块 / 适合）。 */
    fun modeIntroRes(mode: WorkMode): List<Int> = when (mode) {
        WorkMode.LITE -> listOf(
            R.string.work_mode_intro_lite_impl,
            R.string.work_mode_intro_lite_modules,
            R.string.work_mode_intro_lite_fit,
        )

        WorkMode.KSU -> listOf(
            R.string.work_mode_intro_ksu_impl,
            R.string.work_mode_intro_ksu_modules,
            R.string.work_mode_intro_ksu_fit,
        )
    }

    /** 切换步骤文案；未知步骤回退到通用文案。 */
    @StringRes
    fun stepRes(step: ModeSwitchStep?): Int = when (step) {
        ModeSwitchStep.PREPARE -> R.string.work_mode_step_prepare
        ModeSwitchStep.BACKUP -> R.string.work_mode_step_backup
        ModeSwitchStep.BUILD -> R.string.work_mode_step_build
        ModeSwitchStep.WRITE -> R.string.work_mode_step_write
        ModeSwitchStep.RESTORE -> R.string.work_mode_step_restore
        ModeSwitchStep.REBOOT -> R.string.work_mode_step_reboot
        ModeSwitchStep.VERIFY -> R.string.work_mode_step_verify
        null -> R.string.work_mode_step_generic
    }
}
