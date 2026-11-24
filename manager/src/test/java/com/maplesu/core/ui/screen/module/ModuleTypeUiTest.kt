package com.maplesu.core.ui.screen.module

import dev.maplesu.manager.modules.ModuleInstallBackend
import dev.maplesu.manager.modules.ModuleInstallRouter
import dev.maplesu.manager.modules.ModuleType
import dev.maplesu.manager.modules.ModuleTypeEvidence
import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模块类型 UI 纯映射（JVM）：弹窗模型、AMBIGUOUS 选择、UNKNOWN 禁用、B 模式不支持、列表 tag。 */
class ModuleTypeUiTest {
    private fun evidence(type: ModuleType, reasons: List<String> = listOf("test")) =
        ModuleTypeEvidence(type, reasons)

    // ------------------------------------------------------------------ 弹窗模型 / 路由

    @Test
    fun liteUltraRoutesToMapleSuBackend() {
        val routing = ModuleTypeUi.routeWithChoice(evidence(ModuleType.MAPLESU), WorkMode.LITE)
        assertTrue(routing.ok)
        assertEquals(ModuleInstallBackend.MAPLESU_ULTRA, routing.backend)
    }

    @Test
    fun liteKsuRoutesToLiteScriptBackend() {
        val routing = ModuleTypeUi.routeWithChoice(evidence(ModuleType.KERNELSU), WorkMode.LITE)
        assertTrue(routing.ok)
        assertEquals(ModuleInstallBackend.LITE_SCRIPT, routing.backend)
    }

    @Test
    fun ultraModeSupportsMaplesuModuleAndDialogEnabled() {
        val ev = evidence(ModuleType.MAPLESU)
        val routing = ModuleTypeUi.routeWithChoice(ev, WorkMode.LITE)
        assertTrue(routing.ok)
        assertEquals(ModuleInstallBackend.MAPLESU_ULTRA, routing.backend)
        val model = ModuleTypeUi.dialogModel(ev, WorkMode.LITE)
        assertTrue(model.installEnabled)
        assertFalse(ModuleTypeUi.unsupportedInMode(ev, WorkMode.LITE))
    }

    @Test
    fun ambiguousRequiresExplicitChoiceAndDisablesInstall() {
        val ev = evidence(ModuleType.AMBIGUOUS, listOf("ultra-descriptor-elf:libmodule_x.so", "module.prop"))
        val model = ModuleTypeUi.dialogModel(ev, WorkMode.LITE)
        assertFalse(model.installEnabled)
        assertTrue(model.requiresChoice)
        assertEquals(listOf(ModuleType.MAPLESU, ModuleType.KERNELSU), model.choices)
        assertEquals(ModuleInstallRouter.CONFIRM_REQUIRED, model.blockerCode)
        assertEquals(2, model.evidence.size)
    }

    @Test
    fun ambiguousUserChoiceRoutesToChosenBackend() {
        val ev = evidence(ModuleType.AMBIGUOUS)
        assertEquals(
            ModuleInstallBackend.LITE_SCRIPT,
            ModuleTypeUi.routeWithChoice(ev, WorkMode.LITE, ModuleType.KERNELSU).backend,
        )
        assertEquals(
            ModuleInstallBackend.MAPLESU_ULTRA,
            ModuleTypeUi.routeWithChoice(ev, WorkMode.LITE, ModuleType.MAPLESU).backend,
        )
        // Ultra-only：两种显式类型都可用（KSU 兼容格式走 LiteScript 后端）。
        assertTrue(ModuleTypeUi.routeWithChoice(ev, WorkMode.LITE, ModuleType.KERNELSU).ok)
        assertTrue(ModuleTypeUi.routeWithChoice(ev, WorkMode.LITE, ModuleType.MAPLESU).ok)
    }

    @Test
    fun unknownTypeIsDisabledWithReason() {
        val ev = evidence(ModuleType.UNKNOWN, listOf("no-module.prop-no-scripts-no-ultra-elf"))
        val model = ModuleTypeUi.dialogModel(ev, WorkMode.LITE)
        assertFalse(model.installEnabled)
        assertFalse(model.requiresChoice)
        assertEquals(ModuleInstallRouter.UNKNOWN_TYPE, model.blockerCode)
        assertEquals("no-module.prop-no-scripts-no-ultra-elf", ModuleTypeUi.unknownReason(ev))
        assertEquals("", ModuleTypeUi.unknownReason(evidence(ModuleType.KERNELSU)))
    }

    @Test
    fun unresolvedModeDisablesInstall() {
        val model = ModuleTypeUi.dialogModel(evidence(ModuleType.KERNELSU), null)
        assertFalse(model.installEnabled)
        assertEquals(ModuleInstallRouter.MODE_UNRESOLVED, model.blockerCode)
        assertFalse(ModuleTypeUi.installEnabled(evidence(ModuleType.KERNELSU), null))
    }

    // ------------------------------------------------------------------ 已安装列表 tag

    @Test
    fun installedTypeUsesMaplesuIdConvention() {
        assertEquals(ModuleType.MAPLESU, ModuleTypeUi.installedType("maplesu:12"))
        assertEquals(ModuleType.KERNELSU, ModuleTypeUi.installedType("tricky_store"))
        assertEquals(ModuleType.UNKNOWN, ModuleTypeUi.installedType(""))
    }

    @Test
    fun tagKeysMatchModuleType() {
        assertEquals("MAPLESU", ModuleTypeUi.tagKey(ModuleType.MAPLESU))
        assertEquals("KSU", ModuleTypeUi.tagKey(ModuleType.KERNELSU))
        assertNull(ModuleTypeUi.tagKey(ModuleType.AMBIGUOUS))
        assertNull(ModuleTypeUi.tagKey(ModuleType.UNKNOWN))
        assertNull(ModuleTypeUi.tagKey(null))
    }

    @Test
    fun dialogCarriesLocalizedLabelKeyAndEvidence() {
        val ev = evidence(ModuleType.MAPLESU, listOf("ultra-descriptor-elf:libmodule_maplesu.so"))
        val model = ModuleTypeUi.dialogModel(ev, WorkMode.LITE)
        assertTrue(model.typeLabelResKey.contains("MapleSU"))
        assertEquals(listOf("ultra-descriptor-elf:libmodule_maplesu.so"), model.evidence)
        assertTrue(model.installEnabled)
    }

    @Test
    fun ksuDialogModelHasKsuLabel() {
        val model = ModuleTypeUi.dialogModel(evidence(ModuleType.KERNELSU), WorkMode.LITE)
        assertTrue(model.typeLabelResKey.contains("KernelSU"))
        assertTrue(model.installEnabled)
    }

    @Test
    fun ambiguousInKsuModeStillRequiresChoiceNotAutoPro() {
        val ev = evidence(ModuleType.AMBIGUOUS)
        val model = ModuleTypeUi.dialogModel(ev, WorkMode.LITE)
        assertFalse(model.installEnabled)
        assertTrue(model.requiresChoice)
        // 未选择时不能默认走 Ultra（B 模式不支持）。
        assertFalse(ModuleTypeUi.routeWithChoice(ev, WorkMode.LITE).ok)
    }
}
