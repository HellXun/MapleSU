package dev.maplesu.manager.workmode.ui

import androidx.compose.runtime.Immutable
import dev.maplesu.manager.workmode.ModeCapabilities
import dev.maplesu.manager.workmode.UltraOnlyPolicy
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.ModeState
import dev.maplesu.manager.workmode.gating.ModeGate
import dev.maplesu.manager.workmode.gating.ModeComponentIds
import dev.maplesu.manager.workmode.gating.ModeGateFormat

/**
 * 三入口 + 页面可达性的唯一裁决函数。UI 只消费结果，页面里不得各自 if。
 *
 * 判定表（逐行照 04 §2.2）：隐身 > 未完成切换 > 判定态；`INDETERMINATE`/`NOT_INSTALLED`
 * 不显示双模式入口（对未安装用户无意义），`FOREIGN_SU` 首页卡隐藏、由迁移提示取代。
 */
@Immutable
data class WorkModeEntryPolicy(
    val showHomeCard: Boolean,
    val showInstallCard: Boolean,
    val showSettingsRow: Boolean,
) {
    companion object {
        val NONE = WorkModeEntryPolicy(false, false, false)
        val ALL = WorkModeEntryPolicy(true, true, true)

        /** 仅设置页可见（FOREIGN_SU：首页由迁移提示取代）。 */
        val SETTINGS_ONLY = WorkModeEntryPolicy(false, true, true)
    }
}

fun decideWorkModeEntry(
    state: ModeState,
    /** `Stealth.looksStealthy()`：不是明确读到 0（读失败也算"可能隐身"，04 §2.2）。 */
    stealthLooksStealthy: Boolean,
    /** 存在未完成切换 journal（本版无切换服务，固定 false；接线后由 App 侧查询提供）。 */
    pendingSwitch: Boolean,
): WorkModeEntryPolicy {
    // Ultra-only：没有第二个模式可切，首页卡与设置行不再出现；
    // 仅在尚未安装时保留安装卡，保证首次部署 Ultra 的入口还在。
    if (UltraOnlyPolicy.ENABLED) {
        return WorkModeEntryPolicy(
            showHomeCard = false,
            showInstallCard = state == ModeState.NOT_INSTALLED,
            showSettingsRow = false,
        )
    }
    if (stealthLooksStealthy) return WorkModeEntryPolicy.NONE
    if (pendingSwitch) return WorkModeEntryPolicy.ALL
    return when (state) {
        ModeState.MODE_A_ACTIVE,
        ModeState.MODE_B_ACTIVE,
        ModeState.MODE_B_KERNEL_ONLY,
        ModeState.MODE_A_INSTALLED_UNBOUND -> WorkModeEntryPolicy.ALL

        ModeState.FOREIGN_SU -> WorkModeEntryPolicy.SETTINGS_ONLY

        ModeState.NOT_INSTALLED,
        ModeState.INDETERMINATE -> WorkModeEntryPolicy.NONE
    }
}

/**
 * 从运行时读取当前入口可见性（UI 组合期用）。
 *
 * `pendingSwitch` 目前固定 false：切换服务（W08）与 App 侧查询未接线（04 §2.7 的数据源契约
 * 由切换服务实现）。接线后只改这一处，三入口自动获得恢复横幅。
 */
/**
 * 「当前模式是否必须按隐身/隐藏处理」——**按模式取信号**，不能一把梭 `Stealth.looksStealthy()`。
 *
 * 缺陷（2026-10-31 真机）：`Stealth.looksStealthy()` 在 Ultra（LITE）下恒为 true（KSU stealth
 * 在该分支不存在 → 读不到 → UNKNOWN），于是 Ultra 首页入口被永久隐藏、工作模式页显示
 * “隐身模式下不显示工作模式信息” —— 用户从没开过隐身。UNKNOWN 只有在**该模式真的有隐身**
 * 时才能保守按 ON：
 *  - LITE：只看 Lite 隐藏（`/data/adb/maplesu/lite_hidden`）；已观测到 ON 才算隐藏；
 *  - KSU：看内核 stealth，ON / 读不到（UNKNOWN）都按隐藏；
 *  - 未判定：不按隐藏（由 `INDETERMINATE` 自己表达）。
 */
internal fun workModeHiddenMask(
    active: WorkMode?,
    liteHiddenOn: Boolean,
    ksuStealth: com.maplesu.core.ui.security.Stealth.State?,
): Boolean = when (active) {
    WorkMode.LITE -> liteHiddenOn
    else -> false
}

/** 读取当前模式真实的隐藏/隐身信号（组合期可调用：只读内存/缓存，不起 root）。 */
internal fun currentWorkModeHiddenMask(): Boolean {
    val active = dev.maplesu.manager.workmode.ModeRuntime.active
    // 寄生/隐藏事务进行中：按隐藏处理（这段时间不该显示任何模式信息）。
    val pending = runCatching {
        com.maplesu.core.ui.security.HiddenStartupConfirm.isPending(com.maplesu.core.ksuApp)
    }.getOrDefault(false)
    if (pending) return true
    // 2026-10-31 模式独立性：Lite 隐藏只在 LITE 读；Normal 读它属于跨模式探测。
    // 判据必须与首页一致：**UNKNOWN（读失败）只有在上次明确隐藏 ON 时才算隐藏**，
    // 否则一次启动 root 读失败就会让工作模式页谎称“隐身模式”（真机已复现）。
    val liteHiddenOn = if (active == WorkMode.LITE) {
        runCatching {
            com.maplesu.core.ui.security.HiddenStartupConfirm.masks(
                state = com.maplesu.core.ui.security.LiteHiddenRuntime.currentState(),
                lastKnownOn = com.maplesu.core.ui.security.LiteHiddenRuntime.lastKnownOn(),
                active = active,
            )
        }.getOrDefault(true)
    } else {
        false
    }
    val ksuStealth = if (false) {
        runCatching { com.maplesu.core.ui.security.Stealth.state() }.getOrNull()
    } else {
        null
    }
    return workModeHiddenMask(active, liteHiddenOn, ksuStealth)
}

fun currentWorkModeEntryPolicy(): WorkModeEntryPolicy {
    val state = ModeRuntimeState.currentStateOrIndeterminate()
    // Ultra-only：入口结果与隐藏态无关，跳过 currentWorkModeHiddenMask()
    // —— 它会读 Lite 隐藏状态，属于组合期的额外 IO/root 探测。
    if (UltraOnlyPolicy.ENABLED) return decideWorkModeEntry(state, false, pendingSwitch = false)
    val hidden = runCatching { currentWorkModeHiddenMask() }.getOrDefault(false)
    return decideWorkModeEntry(state, hidden, pendingSwitch = false)
}

/** UI 侧读取 ModeRuntime 的薄适配（不修改核心包）。 */
internal object ModeRuntimeState {
    fun currentStateOrIndeterminate(): ModeState =
        dev.maplesu.manager.workmode.ModeRuntime.latestDetection?.state ?: ModeState.INDETERMINATE

    fun capabilitiesOrNone(): ModeCapabilities =
        dev.maplesu.manager.workmode.ModeRuntime.capabilities
}

/** 入口可见性变化时记日志（`ui.workmode.entry`）；只在检测到可见时 allow，否则 skip/deny。 */
fun logWorkModeEntry(policy: WorkModeEntryPolicy) {
    if (policy == WorkModeEntryPolicy.NONE) {
        ModeGate.skip(
            ModeComponentIds.UI_WORKMODE_ENTRY,
            ModeGateFormat.Reason.STEALTH_MASKED,
        )
    } else {
        ModeGate.allow(ModeComponentIds.UI_WORKMODE_ENTRY)
    }
}

/** 工作模式名字符串资源（产品名只在 strings_workmode.xml 出现，代码不写死）。 */
fun workModeNameRes(mode: WorkMode): Int = when (mode) {
    WorkMode.LITE -> com.maplesu.core.R.string.work_mode_lite
}
