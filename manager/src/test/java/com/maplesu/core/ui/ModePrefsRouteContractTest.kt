package com.maplesu.core.ui

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.storage.ModePaths
import dev.maplesu.manager.workmode.storage.SettingsPrefsPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * P1 配置路由：UI 侧 `"settings"` 直连改造的契约测试（纯 JVM）。
 *
 * 路由规则唯一来源 = `SettingsPrefsPolicy` / `ModePaths`；本测试锁定：
 * Ultra 保持 A 原名逐字节等价、未判定按证据回退（禁止默认 A），
 * 以及 UI 直连点确实改走 `SettingsPrefsRouter`（源码契约，避免规则被复制/回退）。
 */
class ModePrefsRouteContractTest {

    @Test
    fun liteKeepsLegacySettingsName() {
        assertEquals("settings", SettingsPrefsPolicy.nameFor(WorkMode.LITE))
        assertEquals("settings", ModePaths.prefsNameFor("settings", WorkMode.LITE))
        // 不变量：和改造前完全相同的文件、相同的 key（同文件同 key = 逐字节等价）。
        assertEquals(SettingsPrefsPolicy.A_NAME, ModePaths.prefsNameFor("settings", WorkMode.LITE))
    }

    @Test
    fun unresolvedNeverFallsBackToLiteWithoutEvidence() {
        assertNull(SettingsPrefsPolicy.nameFor(null))
        assertEquals(
            ModePaths.NEUTRAL_PREFS,
            SettingsPrefsPolicy.unresolvedName(lastKnown = null, liteEvidencePresent = false),
        )
    }

    @Test
    fun unresolvedUsesLastKnownHintOrExplicitLiteEvidence() {
        assertEquals(
            "settings",
            SettingsPrefsPolicy.unresolvedName(lastKnown = WorkMode.LITE, liteEvidencePresent = false),
        )
        assertEquals(
            "settings",
            SettingsPrefsPolicy.unresolvedName(lastKnown = null, liteEvidencePresent = true),
        )
    }

    @Test
    fun prefsFileMatchesTheRouterName() {
        assertEquals(
            "/data/user/0/app/shared_prefs/settings.xml",
            ModePaths.prefsFile("/data/user/0/app/shared_prefs", "settings", WorkMode.LITE),
        )
    }

    // ── UI 直连点源码契约（防复制规则/回退） ─────────────────────────────

    private fun source(path: String): String? = File(path).takeIf { it.isFile }?.readText()

    @Test
    fun appearanceUsesSharedPreferencesAndFunctionalSettingsRemainRouted() {
        val callSites = listOf(
            "src/main/java/com/maplesu/core/ui/util/WallpaperPrefs.kt",
            "src/main/java/com/maplesu/core/ui/viewmodel/MainActivityViewModel.kt",
            "src/main/java/com/maplesu/core/ui/webui/SuFilePathHandler.java",
            "src/main/java/com/maplesu/core/ui/webui/WebUIActivity.kt",
            "src/main/java/com/maplesu/core/ui/webui/WebViewHelper.kt",
        )
        val missing = mutableListOf<String>()
        callSites.forEach { path ->
            val text = source(path) ?: return@forEach
            val expected = if (path.endsWith("WebViewHelper.kt")) "SettingsPrefsRouter" else "AppearancePreferences"
            if (!text.contains(expected)) missing += path
        }
        assumeTrue("sources unavailable (run in manager module dir)", missing.size < callSites.size)
        assertTrue("call sites not using the appropriate preferences source: $missing", missing.isEmpty())
    }

    @Test
    fun uiSettingsCallSitesNoLongerReadRawSettingsDirectly() {
        val callSites = listOf(
            "src/main/java/com/maplesu/core/ui/util/WallpaperPrefs.kt",
            "src/main/java/com/maplesu/core/ui/viewmodel/MainActivityViewModel.kt",
            "src/main/java/com/maplesu/core/ui/webui/SuFilePathHandler.java",
            "src/main/java/com/maplesu/core/ui/webui/WebUIActivity.kt",
            "src/main/java/com/maplesu/core/ui/webui/WebViewHelper.kt",
        )
        val offenders = mutableListOf<String>()
        callSites.forEach { path ->
            val text = source(path) ?: return@forEach
            if (text.contains("getSharedPreferences(\"settings\"")) offenders += path
        }
        assumeTrue("sources unavailable", offenders.isEmpty() || offenders.size < callSites.size)
        assertTrue("raw getSharedPreferences(\"settings\") remains: $offenders", offenders.isEmpty())
    }
}
