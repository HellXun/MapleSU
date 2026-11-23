package dev.maplesu.manager.modules

import dev.maplesu.manager.recovery.BootHookModule
import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 模块类型识别：Ultra 描述符 ELF / KSU 脚本 / 混合 / 未知 / 损坏；与 BootHookModule 一致。 */
class ModuleTypeDetectorTest {
    private val elf = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0, 0, 0, 0)

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun text(value: String) = value.toByteArray(Charsets.UTF_8)

    @Test
    fun ultraDescriptorAtRootIsMapleSuEvenWithoutModuleProp() {
        val evidence = ModuleTypeDetector.detect(
            zip(
                "libmodule_maplesu_hook.so" to elf,
                "service.sh" to text("#!/system/bin/sh"),
                "webroot/index.html" to text("<html/>"),
            ),
        )
        assertEquals(ModuleType.MAPLESU, evidence.type)
        assertTrue(evidence.reasons.any { it.startsWith("ultra-descriptor-elf:") })
        assertTrue(evidence.reasons.any { it.startsWith("ultra-companions:") })
    }

    @Test
    fun ksuModulePropOnlyIsKernelsu() {
        val evidence = ModuleTypeDetector.detect(zip("module.prop" to text("id=demo\nname=demo\n")))
        assertEquals(ModuleType.KERNELSU, evidence.type)
        assertTrue(evidence.reasons.contains("module.prop"))
    }

    @Test
    fun ksuScriptOnlyIsKernelsu() {
        assertEquals(
            ModuleType.KERNELSU,
            ModuleTypeDetector.detect(zip("service.sh" to text("#!/system/bin/sh\n"))).type,
        )
        assertEquals(
            ModuleType.KERNELSU,
            ModuleTypeDetector.detect(zip("post-fs-data.sh" to text("#!/system/bin/sh\n"))).type,
        )
        assertEquals(
            ModuleType.KERNELSU,
            ModuleTypeDetector.detect(zip("customize.sh" to text("#!/system/bin/sh\n"))).type,
        )
    }

    @Test
    fun mixedFeaturesAreAmbiguousNotGuessed() {
        val evidence = ModuleTypeDetector.detect(
            zip("libmodule_demo.so" to elf, "module.prop" to text("id=demo\n")),
        )
        assertEquals(ModuleType.AMBIGUOUS, evidence.type)
        assertTrue(evidence.reasons.contains("module.prop"))
    }

    @Test
    fun invalidElfWithKsuFeaturesFallsBackToKernelsu() {
        val evidence = ModuleTypeDetector.detect(
            zip("libmodule_demo.so" to text("not-an-elf"), "module.prop" to text("id=demo\n")),
        )
        assertEquals(ModuleType.KERNELSU, evidence.type)
        assertTrue(evidence.reasons.contains("libmodule-present-but-not-elf"))
    }

    @Test
    fun emptyOrGarbageArchiveIsUnknown() {
        assertEquals(ModuleType.UNKNOWN, ModuleTypeDetector.detect(ByteArray(0)).type)
        assertEquals(ModuleType.UNKNOWN, ModuleTypeDetector.detect(text("not a zip")).type)
        assertEquals(ModuleType.UNKNOWN, ModuleTypeDetector.detect(zip("random.txt" to text("x"))).type)
    }

    @Test
    fun nonRootUltraDescriptorDoesNotMakePro() {
        val evidence = ModuleTypeDetector.detect(
            zip("sub/libmodule_demo.so" to elf),
        )
        assertEquals(ModuleType.UNKNOWN, evidence.type)
        assertTrue(evidence.reasons.toString(), evidence.reasons.contains("ultra-descriptor-not-at-root"))
    }

    @Test
    fun bootHookModuleIsRecognizedAsKernelsu() {
        val evidence = ModuleTypeDetector.detect(BootHookModule.buildZip())
        assertEquals(ModuleType.KERNELSU, evidence.type)
        assertTrue(evidence.reasons.contains("module.prop"))
    }

    @Test
    fun detectorFromFileMatchesBytes() {
        val file = java.nio.file.Files.createTempFile("module-type", ".zip").toFile()
        try {
            file.writeBytes(zip("module.prop" to text("id=demo\n")))
            assertEquals(ModuleType.KERNELSU, ModuleTypeDetector.detect(file).type)
        } finally {
            file.delete()
        }
    }
}

/** 安装路由：A（LITE）Ultra/Lite 脚本；B（KSU）统一 KSU；不支持/未知/需确认显式失败。 */
class ModuleInstallRouterTest {
    @Test
    fun liteRoutesUltraToMaplesuBackend() {
        val routing = ModuleInstallRouter.route(WorkMode.LITE, ModuleType.MAPLESU)
        assertTrue(routing.ok)
        assertEquals(ModuleInstallBackend.MAPLESU_ULTRA, routing.backend)
    }

    @Test
    fun liteRoutesKsuToLiteScriptBackend() {
        val routing = ModuleInstallRouter.route(WorkMode.LITE, ModuleType.KERNELSU)
        assertTrue(routing.ok)
        assertEquals(ModuleInstallBackend.LITE_SCRIPT, routing.backend)
    }

    @Test
    fun ksuModeRoutesKsuDaemonAndRejectsPro() {
        val ksu = ModuleInstallRouter.route(WorkMode.LITE, ModuleType.KERNELSU)
        assertTrue(ksu.ok)
        assertEquals(ModuleInstallBackend.KSU_DAEMON, ksu.backend)
        val ultra = ModuleInstallRouter.route(WorkMode.LITE, ModuleType.MAPLESU)
        assertFalse(ultra.ok)
        assertEquals(ModuleInstallRouter.UNSUPPORTED_IN_MODE, ultra.code)
        assertTrue(ultra.reason.contains("not supported"))
    }

    @Test
    fun unresolvedModeNeverDefaultsToLite() {
        val routing = ModuleInstallRouter.route(null, ModuleType.KERNELSU)
        assertFalse(routing.ok)
        assertEquals(ModuleInstallRouter.MODE_UNRESOLVED, routing.code)
    }

    @Test
    fun unknownTypeIsRejected() {
        assertEquals(
            ModuleInstallRouter.UNKNOWN_TYPE,
            ModuleInstallRouter.route(WorkMode.LITE, ModuleType.UNKNOWN).code,
        )
    }

    @Test
    fun ambiguousAlwaysRequiresConfirmationNeverAutoPicks() {
        val routing = ModuleInstallRouter.route(WorkMode.LITE, ModuleType.AMBIGUOUS)
        assertFalse(routing.ok)
        assertEquals(ModuleInstallRouter.CONFIRM_REQUIRED, routing.code)
        assertEquals(
            ModuleInstallRouter.CONFIRM_REQUIRED,
            ModuleInstallRouter.route(WorkMode.LITE, ModuleType.AMBIGUOUS, ambiguousConfirmed = true).code,
        )
    }

    @Test
    fun presentationExposesTypeEvidenceAndRouting() {
        val evidence = ModuleTypeEvidence(
            ModuleType.KERNELSU, listOf("module.prop", "ksu-script:service.sh"),
        )
        val presentation = ModuleInstallPresentation.of(evidence, WorkMode.LITE)
        assertEquals(ModuleType.KERNELSU, presentation.type)
        assertEquals("KernelSU 脚本模块", presentation.typeLabel)
        assertTrue(presentation.evidence.contains("module.prop"))
        assertEquals(ModuleInstallBackend.LITE_SCRIPT, presentation.routing.backend)
        assertFalse(presentation.requiresUserConfirmation)
        assertTrue(ModuleInstallPresentation.of(
            ModuleTypeEvidence(ModuleType.AMBIGUOUS, listOf("module.prop", "ultra-descriptor-elf:x")),
            WorkMode.LITE,
        ).requiresUserConfirmation)
    }
}
