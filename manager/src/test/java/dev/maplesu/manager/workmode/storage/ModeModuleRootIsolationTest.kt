// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.workmode.storage

import dev.maplesu.manager.modules.LiteModuleModePolicy
import dev.maplesu.manager.modules.ModuleTree
import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Standard executable layout is shared; catalog and configuration stay app-private. */
class ModeModuleRootIsolationTest {

    private val filesDir = "/data/user/0/dev.maplesu.manager/files"

    @Test
    fun ultraUsesTheStandardKsuLayout() {
        val root = ModePaths.moduleRoot(WorkMode.LITE, filesDir)
        assertNotNull(root)
        assertEquals(ModePaths.ModuleOwner.LITE, root!!.owner)
        assertEquals("/data/adb/modules", root.path)
        assertTrue(root.sharedWithOtherMode)
    }

    @Test
    fun unresolvedModeHasNoModuleRoot() {
        assertNull(ModePaths.moduleRoot(null, filesDir))
        // 未判定 = 两个方向都不允许读（绝不默认 LITE）
        assertFalse(ModePaths.allowsModulePath(null, "/data/adb/modules/x", filesDir))
        assertFalse(ModePaths.allowsModulePath(null, "$filesDir/module-runtime/x", filesDir))
    }

    @Test
    fun ultraMayReadTheStandardTreeAndItsPrivateCatalog() {
        assertTrue(ModePaths.allowsModulePath(WorkMode.LITE, "/data/adb/modules", filesDir))
        assertTrue(ModePaths.allowsModulePath(WorkMode.LITE, "/data/adb/modules/demo", filesDir))
        assertTrue(ModePaths.allowsModulePath(WorkMode.LITE, "$filesDir/module-runtime", filesDir))
        assertTrue(
            ModePaths.allowsModulePath(WorkMode.LITE, "$filesDir/module-runtime/revisions/demo", filesDir),
        )
        assertTrue(ModePaths.allowsModulePath(WorkMode.LITE, "$filesDir/module-catalog", filesDir))
    }

    @Test
    fun prefixSiblingIsNotTreatedAsTheModuleRoot() {
        // "…/module-runtime-old" 不是 "…/module-runtime" 的子路径。
        assertFalse(
            ModePaths.allowsModulePath(WorkMode.LITE, "$filesDir/module-runtime-old/x", filesDir),
        )
        assertFalse(ModePaths.allowsModulePath(WorkMode.LITE, "/data/adb/modules-old/x", filesDir))
    }

    @Test
    fun moduleTreePublishIsLiteOnly() {
        assertTrue(ModuleTree.allowsPublish(WorkMode.LITE))
        assertFalse("未判定绝不默认 LITE", ModuleTree.allowsPublish(null))
        // 单一事实来源：两边必须是同一个路径常量。
        assertEquals(ModePaths.KSU_MODULE_ROOT, ModuleTree.ROOT)
    }

    @Test
    fun liteModuleBootIsLiteOnly() {
        assertTrue(LiteModuleModePolicy.allowsRun(WorkMode.LITE))
        assertFalse("未判定绝不默认 LITE", LiteModuleModePolicy.allowsRun(null))
        assertNull(LiteModuleModePolicy.rejectionReason(WorkMode.LITE))
        assertTrue(LiteModuleModePolicy.rejectionReason(null)!!.contains("MODE_UNRESOLVED"))
    }

    @Test
    fun prefsAndFilesKeepTheirLegacyNames() {
        assertEquals("settings", ModePaths.prefsNameFor("settings", WorkMode.LITE))
        assertEquals("module-catalog", ModePaths.filesSubdir("module-catalog", WorkMode.LITE))
    }
}
