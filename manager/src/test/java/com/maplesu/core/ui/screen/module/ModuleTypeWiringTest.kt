package com.maplesu.core.ui.screen.module

import dev.maplesu.manager.modules.ModuleInstallBackend
import dev.maplesu.manager.modules.ModuleType
import dev.maplesu.manager.modules.ModuleTypeDetector
import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 接线测试：真实 `ModuleTypeDetector` evidence → ModuleTypeUi 弹窗/路由分支
 * （Ultra/KSU、AMBIGUOUS 选择、UNKNOWN 禁用+真实原因）。
 */
class ModuleTypeWiringTest {
    private val elf = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0)

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun ultraScript(): Map<String, ByteArray> = mapOf(
        "libmodule_demo.so" to elf,
    )

    private fun ksuScript(): Map<String, ByteArray> = mapOf(
        "module.prop" to "id=demo\nname=Demo\n".toByteArray(),
        "service.sh" to "#!/system/bin/sh\n".toByteArray(),
    )

    private fun ambiguous(): Map<String, ByteArray> = ultraScript() + ksuScript()

    @Test
    fun detectorEvidenceDrivesUltraDialogAndBackend() {
        val evidence = ModuleTypeDetector.detect(zip(ultraScript()))
        assertEquals(ModuleType.MAPLESU, evidence.type)
        val model = ModuleTypeUi.dialogModel(evidence, WorkMode.LITE)
        assertTrue(model.installEnabled)
        assertEquals(ModuleInstallBackend.MAPLESU_ULTRA, ModuleTypeUi.routeWithChoice(evidence, WorkMode.LITE).backend)
    }

    @Test
    fun detectorEvidenceDrivesKsuDialogAndBackend() {
        val evidence = ModuleTypeDetector.detect(zip(ksuScript()))
        assertEquals(ModuleType.KERNELSU, evidence.type)
        val model = ModuleTypeUi.dialogModel(evidence, WorkMode.LITE)
        assertTrue(model.installEnabled)
        assertEquals(ModuleInstallBackend.LITE_SCRIPT, ModuleTypeUi.routeWithChoice(evidence, WorkMode.LITE).backend)
    }

    @Test
    fun ambiguousEvidenceRequiresChoiceInDialog() {
        val evidence = ModuleTypeDetector.detect(zip(ambiguous()))
        assertEquals(ModuleType.AMBIGUOUS, evidence.type)
        val model = ModuleTypeUi.dialogModel(evidence, WorkMode.LITE)
        assertFalse(model.installEnabled)
        assertTrue(model.requiresChoice)
        assertEquals(listOf(ModuleType.MAPLESU, ModuleType.KERNELSU), model.choices)
        assertTrue(model.evidence.isNotEmpty())
    }

    @Test
    fun ambiguousUserChoiceSelectsBackend() {
        val evidence = ModuleTypeDetector.detect(zip(ambiguous()))
        assertEquals(
            ModuleInstallBackend.MAPLESU_ULTRA,
            ModuleTypeUi.routeWithChoice(evidence, WorkMode.LITE, ModuleType.MAPLESU).backend,
        )
        assertEquals(
            ModuleInstallBackend.LITE_SCRIPT,
            ModuleTypeUi.routeWithChoice(evidence, WorkMode.LITE, ModuleType.KERNELSU).backend,
        )
    }

    @Test
    fun ambiguousInUltraModeAcceptsBothExplicitTypes() {
        val evidence = ModuleTypeDetector.detect(zip(ambiguous()))
        assertTrue(ModuleTypeUi.routeWithChoice(evidence, WorkMode.LITE, ModuleType.KERNELSU).ok)
        assertTrue(ModuleTypeUi.routeWithChoice(evidence, WorkMode.LITE, ModuleType.MAPLESU).ok)
        assertFalse(ModuleTypeUi.dialogModel(evidence, WorkMode.LITE).installEnabled)
    }

    @Test
    fun unknownArchiveDisablesInstallWithRealReason() {
        val evidence = ModuleTypeDetector.detect(zip(mapOf("notes.txt" to "hi".toByteArray())))
        assertEquals(ModuleType.UNKNOWN, evidence.type)
        val model = ModuleTypeUi.dialogModel(evidence, WorkMode.LITE)
        assertFalse(model.installEnabled)
        assertFalse(model.requiresChoice)
        val reason = ModuleTypeUi.unknownReason(evidence)
        assertTrue(reason, reason.isNotBlank())
        assertTrue(reason, evidence.reasons.contains(reason))
        assertTrue(reason, !reason.contains("not-wired"))
        assertEquals(evidence.reasons, model.evidence)
    }

    @Test
    fun invalidZipBecomesUnknownWithReadFailureReason() {
        val evidence = ModuleTypeDetector.detect(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(ModuleType.UNKNOWN, evidence.type)
        assertTrue(evidence.reasons.isNotEmpty())
        assertFalse(ModuleTypeUi.dialogModel(evidence, WorkMode.LITE).installEnabled)
    }
}
