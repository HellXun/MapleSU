package dev.maplesu.manager.workmode.migration

import dev.maplesu.manager.ModuleArchiveInspector
import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 05 §5.2/§5.3：逐模块预检三态 / 导入 / KSU 直恢复 / repack（T-25、T-26）。 */
class ModuleMigratorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun moduleDir(
        id: String = "test_module",
        propId: String = id,
        extra: Map<String, String> = emptyMap(),
        includeProp: Boolean = true,
    ): File {
        val dir = tmp.newFolder(id.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        if (includeProp) {
            File(dir, "module.prop").writeText(
                "id=$propId\nname=Test Module\nversion=1.0\nversionCode=1\n",
            )
        }
        extra.forEach { (path, content) ->
            val file = File(dir, path)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
        return dir
    }

    private fun migrator(port: ModuleInstallPort? = null): ModuleMigrator =
        ModuleMigrator(tmp.newFolder("work"), port)

    private fun candidate(
        dir: File,
        id: String = "test_module",
        origin: ModuleOrigin = ModuleOrigin.KSU,
        disabled: Boolean = false,
    ) = ModuleCandidate(id, origin, dir, disabled = disabled)

    @Test
    fun passingModuleIsImportedWithReportedCode() {
        val dir = moduleDir(extra = mapOf("customize.sh" to "echo ready\n"))
        val result = migrator { _, _ -> ModuleInstallOutcome(0, needsReboot = true) }
            .migrate(listOf(candidate(dir)), WorkMode.LITE)
        val item = result.items.single()
        assertEquals(ModuleDecision.IMPORT_LITE, item.decision)
        assertEquals(0, item.installCode)
        assertEquals(true, item.needsReboot)
        assertNotNull(item.archiveSha256)
        assertEquals(1, result.importedCount)
    }

    @Test
    fun preflightWithoutPortStillDecidesImport() {
        val dir = moduleDir()
        val result = migrator().migrate(listOf(candidate(dir)), WorkMode.LITE)
        assertEquals(ModuleDecision.IMPORT_LITE, result.items.single().decision)
        assertEquals(null, result.items.single().installCode)
    }

    @Test
    fun initRcRequirementIsPassedToTheInstaller() {
        val dir = moduleDir(extra = mapOf("initrc/service.rc" to "on init\n"))
        var called = false
        val result = migrator { _, report ->
            called = true
            assertTrue(ModuleArchiveInspector.Feature.INIT_RC in report.features)
            ModuleInstallOutcome(0)
        }.migrate(listOf(candidate(dir)), WorkMode.LITE)
        val item = result.items.single()
        assertTrue(called)
        assertEquals(ModuleDecision.IMPORT_LITE, item.decision)
        assertEquals(0, result.unsupportedCount)
    }

    @Test
    fun scriptRequirementsPreserveTheActualInstallerResult() {
        val dir = moduleDir(extra = mapOf("customize.sh" to "setenforce 0\n"))
        var called = false
        val result = migrator { _, _ ->
            called = true
            ModuleInstallOutcome(27, message = "fixture installer result")
        }.migrate(listOf(candidate(dir)), WorkMode.LITE)
        val item = result.items.single()
        assertTrue(called)
        assertEquals(ModuleDecision.SKIP_FAILED, item.decision)
        assertEquals(27, item.installCode)
        assertEquals("fixture installer result", item.reason)
    }

    @Test
    fun invalidModuleIdIsReportedNotThrown() {
        val dir = moduleDir(propId = "1bad-id")
        val result = migrator().migrate(listOf(candidate(dir)), WorkMode.LITE)
        val item = result.items.single()
        assertEquals(ModuleDecision.SKIP_UNSUPPORTED, item.decision)
        assertEquals(MigrationCodes.MODULE_ID_INVALID, item.reason)
    }

    @Test
    fun missingModulePropIsReported() {
        val dir = moduleDir(includeProp = false, extra = mapOf("service.sh" to "echo hi\n"))
        val result = migrator().migrate(listOf(candidate(dir)), WorkMode.LITE)
        assertEquals(MigrationCodes.MODULE_METADATA_MISSING, result.items.single().reason)
    }

    @Test
    fun installFailureIsReportedPerItemWithoutBlockingOthers() {
        val good = moduleDir(id = "good_module")
        val bad = moduleDir(id = "bad_module")
        val result = migrator { archive, _ ->
            if (archive.name.contains("bad_module")) {
                ModuleInstallOutcome(7, message = "installer rejected")
            } else {
                ModuleInstallOutcome(0)
            }
        }.migrate(
            listOf(candidate(good, "good_module"), candidate(bad, "bad_module")),
            WorkMode.LITE,
        )
        assertEquals(1, result.importedCount)
        assertEquals(1, result.failedCount)
        assertEquals(ModuleDecision.SKIP_FAILED, result.items.single { it.moduleId == "bad_module" }.decision)
    }

    @Test
    fun sourceDisableFlagIsCarriedToReport() {
        val dir = moduleDir()
        val result = migrator().migrate(
            listOf(candidate(dir, disabled = true)),
            WorkMode.LITE,
        )
        assertTrue(result.items.single().sourceDisabled)
    }

    @Test
    fun repackIsDeterministicAndInspectable() {
        val dir = moduleDir(
            extra = mapOf("customize.sh" to "echo ready\n", "system/app/x.apk" to "payload"),
        )
        val first = ModuleRepacker.repack(dir, File(tmp.newFolder(), "first.zip"))
        val second = ModuleRepacker.repack(dir, File(tmp.newFolder(), "second.zip"))
        assertEquals(MigrationHashes.sha256(first), MigrationHashes.sha256(second))
        val report = ModuleArchiveInspector.inspect(first)
        assertEquals("test_module", report.id)
        assertEquals(ModuleArchiveInspector.ModuleType.KERNEL_SU, report.type)
    }

    @Test
    fun reportCountsAndRowsAreStable() {
        val dir = moduleDir()
        val result = migrator().migrate(listOf(candidate(dir)), WorkMode.LITE)
        val row = result.toReport().single()
        assertEquals("test_module", row["id"])
        assertEquals("IMPORT_LITE", row["decision"])
        assertFalse(row.getValue("sourceDisabled").toBoolean())
        assertTrue(row.containsKey("archiveSha256").not())
    }
}
