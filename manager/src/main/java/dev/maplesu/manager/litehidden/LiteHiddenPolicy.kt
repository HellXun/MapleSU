package dev.maplesu.manager.litehidden

import dev.maplesu.manager.workmode.WorkMode

/**
 * Lite 隐藏模式对 UI 的唯一裁决（纯函数，主机表驱动测试）。
 *
 * 规则：
 * - **仅 LITE 模式启用**（B 用 KSU stealth 内核通道；未判定不展示此卡）；
 * - OFF → 返回**基线值**（全部可见、不伪装），等价于现有行为；
 * - ON/UNKNOWN（保守）→ 隐藏所有会暴露 root 的 Lite 入口，首页伪装"未安装"；
 * - 桌面图标（LauncherAlias）**永不由本策略禁用**（图标隐藏已废弃，避免死图标锁死）。
 */
object LiteHiddenPolicy {

    /** OFF / B / 未判定时的基线：逐字节等价于现有 UI 行为。 */
    val BASELINE: LiteHiddenVisibility = LiteHiddenVisibility(
        cardVisible = false,
        disguisedAsNotInstalled = false,
        showSuperUserEntry = true,
        showModuleEntry = true,
        showRootSettings = true,
        showDirectInstall = true,
        showUltraEnvironment = true,
        showModuleActions = true,
        showRecoveryEntry = true,
        allowLauncherAliasToggle = false,
    )

    fun evaluate(mode: WorkMode?, state: LiteHiddenState): LiteHiddenVisibility {
        // 只有 Lite（A）才有"本 App 级隐藏"概念；B/未判定不显示此卡，也不改变 B 的可见性。
        if (mode != WorkMode.LITE) {
            return BASELINE.copy(cardVisible = false)
        }
        val on = state.isEffectivelyOn
        if (!on) {
            return BASELINE.copy(cardVisible = true)
        }
        return LiteHiddenVisibility(
            cardVisible = true,
            disguisedAsNotInstalled = true,
            showSuperUserEntry = false,
            showModuleEntry = false,
            showRootSettings = false,
            showDirectInstall = false,
            showUltraEnvironment = false,
            showModuleActions = false,
            showRecoveryEntry = false,
            allowLauncherAliasToggle = false,
        )
    }

    /** ON 时是否允许某功能（用于安装/模块/Ultra/恢复页等入口的 gating）。 */
    fun allowsRootFeature(mode: WorkMode?, state: LiteHiddenState, feature: RootFeature): Boolean {
        val visibility = evaluate(mode, state)
        if (!visibility.disguisedAsNotInstalled) return true
        return when (feature) {
            RootFeature.SUPERUSER -> visibility.showSuperUserEntry
            RootFeature.MODULES -> visibility.showModuleEntry
            RootFeature.ROOT_SETTINGS -> visibility.showRootSettings
            RootFeature.DIRECT_INSTALL -> visibility.showDirectInstall
            RootFeature.ULTRA_ENVIRONMENT -> visibility.showUltraEnvironment
            RootFeature.MODULE_ACTIONS -> visibility.showModuleActions
            RootFeature.RECOVERY -> visibility.showRecoveryEntry
        }
    }

    /** 首页/安装页是否应按"未安装（点击安装）"渲染。 */
    fun masksAsNotInstalled(mode: WorkMode?, state: LiteHiddenState): Boolean =
        evaluate(mode, state).disguisedAsNotInstalled
}

enum class RootFeature {
    SUPERUSER,
    MODULES,
    ROOT_SETTINGS,
    DIRECT_INSTALL,
    ULTRA_ENVIRONMENT,
    MODULE_ACTIONS,
    RECOVERY,
}
