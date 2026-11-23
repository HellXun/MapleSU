package dev.maplesu.manager.installer

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.storage.ModePaths
import dev.maplesu.manager.workmode.storage.SettingsPrefsPolicy

/**
 * P1：installer 持久化状态的 prefs 路由策略（纯函数，主机可测）。
 *
 * 背景：`InstallerServiceClient` 原先固定 `getSharedPreferences("installer")`，A/B 共用同一文件，
 * 切模式后 `lastTaskId`/退役标记会串。规则与 `workmode/storage/SettingsPrefsRouter` **同一份裁决**：
 * - 已判定：LITE → `installer`（A 原名，逐字节等价）；
 * - 未判定：不再默认 A。把 "设置总仓应使用哪个文件" 的结论（[SettingsPrefsPolicy] 的
 *   A/B/中性）按同一映射换到 installer 前缀；中性结论 → 中性 prefs（`workmode_state`），
 *   由 `SettingsPrefsRouter` 内部的 lastKnown/证据规则决定，本类不复制该逻辑。
 */
internal object InstallerPrefsPolicy {
    /** A 的既有文件名：不得改名（01 §3.2 / D9），否则等于清掉已持久化任务。 */
    const val LEGACY_NAME = "installer"

    /** 已判定模式下的 installer prefs 名（表驱动：installer / installer_B）。 */
    fun nameForResolved(mode: WorkMode): String =
        requireNotNull(ModePaths.prefsNameFor(LEGACY_NAME, mode)) {
            "INSTALLER_PREFS_UNSUPPORTED: $mode"
        }

    /**
     * 未判定时：沿用 [SettingsPrefsPolicy]/`SettingsPrefsRouter` 对"设置总仓"的结论，
     * 只做前缀映射（A→installer，B→installer_B，中性→中性 prefs）。
     */
    fun nameForUnresolved(settingsDecision: String): String = when (settingsDecision) {
        SettingsPrefsPolicy.A_NAME -> LEGACY_NAME
        SettingsPrefsPolicy.B_NAME -> "${LEGACY_NAME}_B"
        // 中性 prefs（ModePaths.NEUTRAL_PREFS）：不落在 A 文件上，也不新建模式文件。
        else -> settingsDecision
    }

    fun name(active: WorkMode?, settingsDecision: String): String =
        active?.let(::nameForResolved) ?: nameForUnresolved(settingsDecision)

    /** Task receipts may have been written before the current mode became active. */
    fun targetRecordNames(currentName: String): List<String> = listOf(
        "installer-routing", currentName, ModePaths.NEUTRAL_PREFS,
        LEGACY_NAME, "${LEGACY_NAME}_B",
    ).distinct()
}
