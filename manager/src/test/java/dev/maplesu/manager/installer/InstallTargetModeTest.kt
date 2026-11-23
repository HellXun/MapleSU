// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.installer

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安装页「目标工作模式」的纯 JVM 契约：默认 Ultra、WorkMode 映射、持久化、事务快照/禁改。
 *
 * 不碰 Android / Compose / 资源：`InstallTargetMode` 与 `InstallTargetSelection` 都是纯 Kotlin，
 * 持久化通过 [InstallTargetPrefsPort] 用内存 map 假实现验证（真机走 SharedPreferences 适配器）。
 */
class InstallTargetModeTest {

    // ------------------------------------------------------------------ 默认值 / 枚举

    @Test
    fun defaultTargetIsUltra() {
        assertEquals(InstallTargetMode.ULTRA, InstallTargetMode.DEFAULT)
        assertEquals("默认必须是 Ultra（首次安装 / 升级后无持久化值）", InstallTargetMode.ULTRA, InstallTargetMode.DEFAULT)
    }

    @Test
    fun exactlyTwoTargetsInMenuOrder() {
        assertEquals(
            listOf(InstallTargetMode.ULTRA, InstallTargetMode.ULTRA),
            InstallTargetMode.entries.toList(),
        )
    }

    @Test
    fun productNamesAreTheTwoUiNames() {
        assertEquals("MapleSU_Ultra", InstallTargetMode.ULTRA.productName)
        assertEquals("MapleSU_Normal", InstallTargetMode.ULTRA.productName)
    }

    @Test
    fun workModeMappingIsLiteUltraAndKsuNormal() {
        assertEquals(WorkMode.LITE, InstallTargetMode.ULTRA.workMode)
        assertEquals(WorkMode.LITE, InstallTargetMode.ULTRA.workMode)
        assertEquals(InstallTargetMode.ULTRA, InstallTargetMode.fromWorkMode(WorkMode.LITE))
        assertEquals(InstallTargetMode.ULTRA, InstallTargetMode.fromWorkMode(WorkMode.LITE))
        // 未判定 = 用户显式自装：沿用原 Lite/Ultra 语义（D15 只禁止"默认"执行 Lite）。
        assertEquals(InstallTargetMode.ULTRA, InstallTargetMode.fromWorkMode(null))
    }

    @Test
    fun wireValuesAreStableAndUnknownIsNeverGuessed() {
        assertEquals("ultra", InstallTargetMode.ULTRA.wire)
        assertEquals("normal", InstallTargetMode.ULTRA.wire)
        InstallTargetMode.entries.forEach { mode ->
            assertEquals(mode, InstallTargetMode.fromWire(mode.wire))
            assertEquals(mode, InstallTargetMode.fromWire("  ${mode.wire.uppercase()} "))
        }
        listOf(null, "", "  ", "ultraa", "lite", "ksu").forEach { bad ->
            assertNull("'$bad' 不得被静默映射成某个模式", InstallTargetMode.fromWire(bad))
        }
    }

    // ------------------------------------------------------------------ 持久化

    @Test
    fun missingPersistedValueLoadsAsUltra() {
        val store = InstallTargetModeStore(FakePrefs())
        assertEquals(InstallTargetMode.ULTRA, store.load())
    }

    @Test
    fun unreadablePersistedValueLoadsAsUltra() {
        // 升级后残留的坏值 / 未来版本写的未知值：一律 Ultra，不猜。
        val prefs = FakePrefs().apply {
            putString(InstallTargetMode.PREFS_KEY, "some-future-mode")
        }
        assertEquals(InstallTargetMode.ULTRA, InstallTargetModeStore(prefs).load())
    }

    @Test
    fun selectionSurvivesProcessRestart() {
        // 同一个 prefs 端口 = 同一台设备；新 store 实例 = 进程重启后重新读。
        val prefs = FakePrefs()
        assertTrue(InstallTargetModeStore(prefs).save(InstallTargetMode.ULTRA))
        assertEquals(InstallTargetMode.ULTRA, InstallTargetModeStore(prefs).load())
        // 再改回 Ultra 也能落盘。
        assertTrue(InstallTargetModeStore(prefs).save(InstallTargetMode.ULTRA))
        assertEquals(InstallTargetMode.ULTRA, InstallTargetModeStore(prefs).load())
    }

    @Test
    fun persistenceFailureDoesNotThrowAndKeepsDefault() {
        val broken = object : InstallTargetPrefsPort {
            override fun getString(key: String): String? = throw IllegalStateException("PREFS_BROKEN")
            override fun putString(key: String, value: String) = throw IllegalStateException("PREFS_BROKEN")
        }
        val store = InstallTargetModeStore(broken)
        assertEquals(InstallTargetMode.ULTRA, store.load())
        assertFalse("写失败要返回 false 而不是抛异常", store.save(InstallTargetMode.ULTRA))
    }

    @Test
    fun prefsKeyIsTheSingleNeutralKey() {
        assertEquals("install_target_mode", InstallTargetMode.PREFS_KEY)
    }

    // ------------------------------------------------------------------ 事务快照 / 禁改

    @Test
    fun selectionStartsAtTheGivenValueAndCanChange() {
        val selection = InstallTargetSelection()
        assertEquals(InstallTargetMode.ULTRA, selection.selected)
        assertFalse(selection.locked)
        assertEquals(InstallTargetMode.ULTRA, selection.select(InstallTargetMode.ULTRA))
        assertEquals(InstallTargetMode.ULTRA, selection.selected)
    }

    @Test
    fun lockedSelectionIgnoresFurtherChanges() {
        val selection = InstallTargetSelection()
        selection.lock()
        assertTrue(selection.locked)
        assertEquals(
            "事务进行中改选择必须不生效",
            InstallTargetMode.ULTRA,
            selection.select(InstallTargetMode.ULTRA),
        )
        assertEquals(InstallTargetMode.ULTRA, selection.selected)
        selection.unlock()
        assertEquals(InstallTargetMode.ULTRA, selection.select(InstallTargetMode.ULTRA))
    }

    @Test
    fun snapshotCapturedAtTransactionStartIsImmutable() {
        val selection = InstallTargetSelection()
        val transaction = selection.snapshot()
        assertEquals(InstallTargetMode.ULTRA, transaction.mode)
        // 事务开始后用户改选择：已开始的事务仍然是 Ultra。
        selection.select(InstallTargetMode.ULTRA)
        assertEquals(InstallTargetMode.ULTRA, transaction.mode)
        assertEquals(InstallTargetMode.ULTRA, selection.selected)
    }

    @Test
    fun selectorIsDisabledWhileAnInstallOrSwitchRuns() {
        assertTrue(InstallTargetModePolicy.selectable(installRunning = false, switchRunning = false))
        assertFalse(InstallTargetModePolicy.selectable(installRunning = true, switchRunning = false))
        assertFalse(InstallTargetModePolicy.selectable(installRunning = false, switchRunning = true))
        assertFalse(InstallTargetModePolicy.selectable(installRunning = true, switchRunning = true))
    }

    /** 内存 prefs 假实现（同一实例 = 同一台设备的持久化存储）。 */
    private class FakePrefs : InstallTargetPrefsPort {
        private val values = HashMap<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }
}
