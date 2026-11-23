package dev.maplesu.manager.workmode

import dev.maplesu.manager.workmode.ModeCapabilities
import dev.maplesu.manager.workmode.ModuleBackendKind
import dev.maplesu.manager.workmode.SuBackendKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ultra-only 能力矩阵。 */
class ModeCapabilitiesTest {
    @Test
    fun ultraMatrix() {
        val ultra = ModeCapabilities.forMode(WorkMode.LITE)
        assertEquals(SuBackendKind.LITE_RUNTIME, ultra.suBackend)
        assertEquals(ModuleBackendKind.LITE_SCRIPT, ultra.moduleBackend)
        assertFalse(ultra.supportsKernelUmount)
        assertFalse(ultra.supportsSelinuxHide)
        assertFalse(ultra.supportsSulog)
        assertFalse(ultra.supportsAdbRoot)
        assertFalse(ultra.supportsShellSuAdbOnly)
        assertFalse(ultra.supportsStealth)
        assertFalse(ultra.supportsWebAdmin)
        assertFalse(ultra.supportsAppLock)
        assertTrue(ultra.supportsUltraEnvironment)
        assertTrue(ultra.supportsUltraModules)
    }

    @Test
    fun noneAllFalse() {
        val none = ModeCapabilities.NONE
        assertFalse(none.supportsKernelUmount)
        assertFalse(none.supportsSelinuxHide)
        assertFalse(none.supportsSulog)
        assertFalse(none.supportsAdbRoot)
        assertFalse(none.supportsShellSuAdbOnly)
        assertFalse(none.supportsStealth)
        assertFalse(none.supportsWebAdmin)
        assertFalse(none.supportsAppLock)
        assertFalse(none.supportsUltraEnvironment)
        assertFalse(none.supportsUltraModules)
    }

    @Test
    fun workModeWireParsingIsExplicit() {
        assertEquals(WorkMode.LITE, WorkMode.fromWire("a"))
        assertEquals(WorkMode.LITE, WorkMode.fromWire(" LITE "))
        assertEquals(null, WorkMode.fromWire("b"))
        assertEquals(null, WorkMode.fromWire("ksu"))
        assertEquals(null, WorkMode.fromWire(null))
        assertEquals(null, WorkMode.fromWire(""))
        assertEquals(null, WorkMode.fromWire("NONE"))
        assertEquals(WorkMode.LITE, WorkMode.fromSuffix("A"))
        assertEquals(null, WorkMode.fromSuffix("C"))
        assertEquals(null, WorkMode.fromSuffix(null))
    }
}
