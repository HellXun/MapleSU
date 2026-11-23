package dev.maplesu.manager.installer

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.storage.ModePaths
import dev.maplesu.manager.workmode.storage.SettingsPrefsPolicy
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class InstallerTargetRoutingTest {
    private val task = "329fb86a-83b1-4a78-99be-b093fd729ab3"

    private fun target(active: WorkMode?, records: Map<String, Pair<String?, String?>>): InstallTargetMode? {
        val current = InstallerPrefsPolicy.name(active, ModePaths.NEUTRAL_PREFS)
        return installTargetFromRecords(task, InstallerPrefsPolicy.targetRecordNames(current).map {
            records[it] ?: (null to null)
        })
    }

    @Test fun neutralTaskReceiptSurvivesUltraActivation() {
        val records = mapOf(ModePaths.NEUTRAL_PREFS to (task to "ultra"))
        assertEquals(InstallTargetMode.ULTRA, target(null, records))
        assertEquals(InstallTargetMode.ULTRA, target(WorkMode.LITE, records))
    }

    @Test fun neutralTaskReceiptSurvivesNormalActivation() {
        val records = mapOf(ModePaths.NEUTRAL_PREFS to (task to "normal"))
        assertEquals(InstallTargetMode.ULTRA, target(null, records))
        assertEquals(InstallTargetMode.ULTRA, target(WorkMode.LITE, records))
    }

    @Test fun anUnrelatedNeutralTaskDoesNotOverrideTheRequestedTask() {
        val records = mapOf(
            ModePaths.NEUTRAL_PREFS to ("another-task" to "ultra"),
            InstallerPrefsPolicy.LEGACY_NAME to (task to "normal"),
        )
        assertEquals(InstallTargetMode.ULTRA, target(WorkMode.LITE, records))
        assertNull(target(WorkMode.LITE, mapOf(ModePaths.NEUTRAL_PREFS to ("another-task" to "ultra"))))
    }

    @Test fun conflictingOrCorruptNeutralReceiptsRemainRejectedAfterActivation() {
        for (neutralMode in listOf("normal", "broken")) {
            val records = mapOf(
                ModePaths.NEUTRAL_PREFS to (task to neutralMode),
                "installer-routing" to (task to "ultra"),
            )
            assertNull(target(null, records))
            assertNull(target(WorkMode.LITE, records))
            assertNull(target(WorkMode.LITE, records))
        }
    }

    @Test fun receiptLookupDoesNotChangeWhereModeSettingsAreWritten() {
        assertEquals("installer", InstallerPrefsPolicy.name(WorkMode.LITE, SettingsPrefsPolicy.B_NAME))
        assertEquals("installer_B", InstallerPrefsPolicy.name(WorkMode.LITE, SettingsPrefsPolicy.A_NAME))
        assertEquals(ModePaths.NEUTRAL_PREFS, InstallerPrefsPolicy.name(null, ModePaths.NEUTRAL_PREFS))
    }

    @Test fun productionTargetLookupUsesTheReceiptNamespacePolicy() {
        val source = File("src/main/java/dev/maplesu/manager/installer/InstallerServiceClient.kt").readText()
        val lookup = source.substringAfter("fun installTargetOf(").substringBefore("private fun prefs(")
        assertTrue(lookup.contains("InstallerPrefsPolicy.targetRecordNames("))
        assertTrue(lookup.contains("installTargetFromRecords(taskId,"))
    }
}
