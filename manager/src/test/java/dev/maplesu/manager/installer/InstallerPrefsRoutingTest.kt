package dev.maplesu.manager.installer

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.storage.ModePaths
import dev.maplesu.manager.workmode.storage.SettingsPrefsPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 host tests: installer prefs 模式隔离（纯路由策略，无 Android）。
 *
 * A 默认行为必须逐字节等价（同一文件 `installer`、同 key）；B 独立 `installer_B`；
 * 未判定禁止默认 A（复用 SettingsPrefsRouter 的裁决，只做前缀映射）。
 */
class InstallerPrefsRoutingTest {
    private fun name(active: WorkMode?, decision: String) = InstallerPrefsPolicy.name(active, decision)

    @Test
    fun liteKeepsLegacyInstallerFileName() {
        // A（LITE）必须仍是旧文件 "installer"：改名等于清掉已持久化的 lastTaskId。
        assertEquals("installer", name(WorkMode.LITE, SettingsPrefsPolicy.B_NAME))
        assertEquals(InstallerPrefsPolicy.LEGACY_NAME, name(WorkMode.LITE, ModePaths.NEUTRAL_PREFS))
    }

    @Test
    fun ksuUsesSeparateInstallerFileName() {
        assertEquals("installer_B", name(WorkMode.LITE, SettingsPrefsPolicy.A_NAME))
        assertNotEquals(InstallerPrefsPolicy.LEGACY_NAME, name(WorkMode.LITE, ""))
    }

    @Test
    fun resolvedModeIgnoresSettingsDecision() {
        // 已判定时，Settings 总仓的 decision 不参与 installer 路由。
        assertEquals("installer", name(WorkMode.LITE, "settings"))
        assertEquals("installer", name(WorkMode.LITE, "settings_B"))
        assertEquals("installer_B", name(WorkMode.LITE, "settings"))
        assertEquals("installer_B", name(WorkMode.LITE, "settings_B"))
    }

    @Test
    fun unresolvedWithLiteEvidenceKeepsLegacy() {
        // 未判定 + 设置总仓判定为 A（no-backup 凭据证据/上次模式 LITE）→ 保持 A 等价。
        assertEquals("installer", name(null, SettingsPrefsPolicy.A_NAME))
    }

    @Test
    fun unresolvedWithKsuHintIsolated() {
        assertEquals("installer_B", name(null, SettingsPrefsPolicy.B_NAME))
    }

    @Test
    fun unresolvedWithoutEvidenceUsesNeutralPrefsNotA() {
        val neutral = name(null, ModePaths.NEUTRAL_PREFS)
        assertEquals(ModePaths.NEUTRAL_PREFS, neutral)
        assertNotEquals("installer", neutral)
        assertNotEquals("installer_B", neutral)
    }

    @Test
    fun installerPrefsIsInTheSingleModePathsTable() {
        val entry = ModePaths.PREFS_TABLE.firstOrNull { it.legacyName == InstallerPrefsPolicy.LEGACY_NAME }
        assertTrue("installer must be declared in ModePaths.PREFS_TABLE", entry != null)
        assertEquals("installer", entry!!.liteName)
        assertEquals("installer_B", entry.ksuName)
        assertEquals("installer", ModePaths.prefsNameFor("installer", WorkMode.LITE))
        assertEquals("installer_B", ModePaths.prefsNameFor("installer", WorkMode.LITE))
    }

    @Test
    fun modeSwitchNeverSharesTheSamePrefsFile() {
        val lite = name(WorkMode.LITE, "unused")
        val ksu = name(WorkMode.LITE, "unused")
        assertNotEquals(lite, ksu)
        // 未判定的两个出口也不得等于另一模式的专属文件（中性/或证据明确一侧）。
        assertFalse(name(null, SettingsPrefsPolicy.A_NAME) == ksu)
        assertFalse(name(null, SettingsPrefsPolicy.B_NAME) == lite)
    }

    @Test
    fun settingsDecisionConstantsAreTheContract() {
        assertEquals("settings", SettingsPrefsPolicy.A_NAME)
        assertEquals("settings_B", SettingsPrefsPolicy.B_NAME)
        assertEquals("workmode_state", ModePaths.NEUTRAL_PREFS)
    }

    @Test
    fun legacyNameIsStable() {
        // 任何重构都不得改变 A 的文件名，否则旧安装状态会失联。
        assertEquals("installer", InstallerPrefsPolicy.LEGACY_NAME)
    }
}
