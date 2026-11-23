package dev.maplesu.manager.workmode.ui

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页可见性矩阵（Ultra-only）。
 *
 * 纯 JVM：只依赖 [SettingsModePolicy] 与 `WorkMode`，不触碰 Android/Compose。
 */
class SettingsModePolicyTest {

    private val ultra = SettingsModePolicy.forMode(WorkMode.LITE)
    private val unresolved = SettingsModePolicy.forMode(null)

    @Test
    fun ultraIsCommonPlusUltraOnly() {
        assertEquals(
            SettingsModePolicy.COMMON_SECTIONS + SettingsModePolicy.ULTRA_ONLY_SECTIONS,
            ultra.sections,
        )
        assertEquals(
            SettingsModePolicy.COMMON_ROWS + SettingsModePolicy.ULTRA_ONLY_ROWS,
            ultra.rows,
        )
        assertTrue(ultra.resolved)
        assertEquals(WorkMode.LITE, ultra.mode)
    }

    @Test
    fun unresolvedModeSeesOnlyCommonEntries() {
        assertEquals(SettingsModePolicy.COMMON_SECTIONS, unresolved.sections)
        assertEquals(SettingsModePolicy.COMMON_ROWS, unresolved.rows)
        assertFalse(unresolved.resolved)
        assertEquals(null, unresolved.mode)
    }

    @Test
    fun ultraHasExclusiveEntriesBeyondCommon() {
        assertTrue(SettingsModePolicy.ULTRA_ONLY_SECTIONS.isNotEmpty())
        assertTrue(SettingsModePolicy.ULTRA_ONLY_ROWS.isNotEmpty())
        assertTrue((SettingsModePolicy.ULTRA_ONLY_SECTIONS intersect SettingsModePolicy.COMMON_SECTIONS).isEmpty())
        assertTrue((SettingsModePolicy.ULTRA_ONLY_ROWS intersect SettingsModePolicy.COMMON_ROWS).isEmpty())
    }

    @Test
    fun everyUltraSectionContributesItsRowsAndDataSources() {
        SettingsModePolicy.ULTRA_ONLY_SECTIONS.forEach { section ->
            assertTrue(section.name, ultra.shows(section))
            settingsRowsOf(section).forEach { row ->
                assertTrue("$section/$row", ultra.shows(row))
            }
            settingsDataSourcesOf(section).forEach { source ->
                assertTrue("$section/$source", ultra.mayRead(source))
            }
        }
    }

    @Test
    fun unresolvedModeReadsNoUltraDataSource() {
        SettingsModePolicy.ULTRA_ONLY_SECTIONS.forEach { section ->
            assertFalse(unresolved.shows(section))
            settingsDataSourcesOf(section).forEach { source ->
                assertFalse("$section/$source", unresolved.mayRead(source))
            }
        }
    }
}
