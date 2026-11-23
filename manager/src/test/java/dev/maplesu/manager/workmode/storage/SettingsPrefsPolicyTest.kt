package dev.maplesu.manager.workmode.storage

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** P0 配置路由：Ultra 单模式的 prefs 策略 + 证据判定。 */
class SettingsPrefsPolicyTest {

    @Test
    fun activeLiteUsesOriginalFileName() {
        assertEquals("settings", SettingsPrefsPolicy.nameFor(WorkMode.LITE))
        // 行为逐字节等价：文件名与 key 都不变。
        assertEquals("settings", SettingsPrefsPolicy.A_NAME)
    }

    @Test
    fun unresolvedWithoutHintsIsNullThenFallbacksAreExplicit() {
        assertNull(SettingsPrefsPolicy.nameFor(null))
        assertEquals(
            SettingsPrefsPolicy.A_NAME,
            SettingsPrefsPolicy.unresolvedName(lastKnown = WorkMode.LITE, liteEvidencePresent = false),
        )
        assertEquals(
            SettingsPrefsPolicy.A_NAME,
            SettingsPrefsPolicy.unresolvedName(lastKnown = null, liteEvidencePresent = true),
        )
    }

    @Test
    fun unresolvedWithoutAnyEvidenceNeverDefaultsToA() {
        val name = SettingsPrefsPolicy.unresolvedName(lastKnown = null, liteEvidencePresent = false)
        assertEquals(ModePaths.NEUTRAL_PREFS, name)
        assertNotEquals(SettingsPrefsPolicy.A_NAME, name)
    }

    @Test
    fun hasLiteEvidenceReadsCredentialFiles() {
        val directory = Files.createTempDirectory("maplesu-settings-evidence-").toFile()
        try {
            assertFalse(SettingsPrefsPolicy.hasLiteEvidence(directory))
            File(directory, "maplesu-credential.bin").writeBytes(byteArrayOf(1))
            assertTrue(SettingsPrefsPolicy.hasLiteEvidence(directory))
            File(directory, "maplesu-credential.bin").delete()
            File(directory, dev.maplesu.manager.compat.StoredInputs.CREDENTIAL_FILE).writeBytes(byteArrayOf(1))
            assertTrue(SettingsPrefsPolicy.hasLiteEvidence(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun liteWritesLandInTheLegacySettingsFile() {
        val name = SettingsPrefsPolicy.nameFor(WorkMode.LITE)!!
        assertEquals("settings", name)
        // 路由只换文件，不换 key：同名文件、同名字段就是逐字节兼容。
        assertEquals(SettingsPrefsPolicy.A_NAME, name)
    }
}
