package dev.maplesu.manager.workmode.storage

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 01 §8.1 `ModePathsTest`：Ultra 单模式的路径表断言。 */
class ModePathsTest {
    @Test
    fun prefsNameMapping() {
        assertEquals("settings", ModePaths.prefsName("settings", WorkMode.LITE))
        assertEquals("installer", ModePaths.prefsName("installer", WorkMode.LITE))
        assertEquals("maplesu-ultra", ModePaths.prefsName("maplesu-ultra", WorkMode.LITE))
    }

    @Test
    fun filesSubdirMapping() {
        assertEquals("images", ModePaths.filesSubdir("images", WorkMode.LITE))
        assertEquals("module-catalog", ModePaths.filesSubdir("module-catalog", WorkMode.LITE))
    }

    @Test
    fun fullPrefsFilePaths() {
        assertEquals(
            "/data/user/0/dev.maplesu.manager/shared_prefs/settings.xml",
            ModePaths.prefsFile("/data/user/0/dev.maplesu.manager/shared_prefs", "settings", WorkMode.LITE),
        )
    }

    @Test
    fun fullFilesDirPaths() {
        assertEquals("/data/user/0/dev.maplesu.manager/files/wallpaper",
            ModePaths.filesDir("/data/user/0/dev.maplesu.manager/files", "wallpaper", WorkMode.LITE))
    }

    @Test
    fun prefsTableMatchesSpec() {
        assertEquals("settings", ModePaths.prefsNameFor("settings", WorkMode.LITE))
        assertEquals("maplesu-ultra", ModePaths.prefsNameFor("maplesu-ultra", WorkMode.LITE))
        assertEquals("ultra-auto-setup", ModePaths.prefsNameFor("ultra-auto-setup", WorkMode.LITE))
        assertEquals(listOf("maplesu-ultra", "runtime_deployment", "su_entry_mode", "ultra-auto-setup"),
            ModePaths.A_ONLY_PREFS.sorted())
        assertTrue(ModePaths.NEUTRAL_PREFS_NAMES.contains(ModePaths.NEUTRAL_PREFS))
        assertTrue(ModePaths.NEUTRAL_PREFS_NAMES.contains("migration_notice"))
    }

    @Test
    fun neutralAndNoBackupSetsAreStable() {
        assertEquals("workmode_state", ModePaths.NEUTRAL_PREFS)
        assertEquals("active_mode", ModePaths.KEY_ACTIVE_MODE)
        assertEquals("active_boot_id", ModePaths.KEY_ACTIVE_BOOT_ID)
        assertTrue(ModePaths.A_ONLY_NO_BACKUP.contains("maplesu-credential.bin"))
        assertTrue(ModePaths.FILES_SUBDIRS.contains("module-catalog"))
        assertTrue(ModePaths.NEUTRAL_FILES.contains("last_crash.txt"))
    }
}
