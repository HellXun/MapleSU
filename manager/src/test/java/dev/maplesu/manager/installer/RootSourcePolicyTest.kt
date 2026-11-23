package dev.maplesu.manager.installer

import dev.maplesu.installer.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Pure root-source selection policy (02-switch-contract §5.3). */
class RootSourcePolicyTest {
    @Test
    fun liteModeAlwaysUsesTheRuntimeChannel() {
        assertEquals(RootSource.LITE_RUNTIME, RootSourcePolicy.sourceFor(WorkMode.LITE, false))
        assertEquals(RootSource.LITE_RUNTIME, RootSourcePolicy.sourceFor(WorkMode.LITE, true))
    }

    @Test
    fun ksuModeIsSelectedEvenWithoutKernelProof() {
        // 2026-11-01: the in-service proof (a hardcoded version=0 stub) no longer refuses the KSU
        // source; the session degrades to another transport instead of blocking the write.
        assertEquals(RootSource.KSU_NATIVE, RootSourcePolicy.sourceFor(WorkMode.LITE, true))
        assertEquals(RootSource.KSU_NATIVE, RootSourcePolicy.sourceFor(WorkMode.LITE, false))
    }

    @Test
    fun undeterminedStateOpensNoShell() {
        try {
            RootSourcePolicy.sourceFor(null, true)
            fail("undetermined mode accepted")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("STATE_UNDETERMINED"))
        }
    }

    @Test
    fun explicitSuFallbackOnlyForLiteInstall() {
        assertTrue(RootSourcePolicy.allowExplicitSuFallback(InstallerModeGate.WriteScope.LITE_INSTALL))
        assertFalse(RootSourcePolicy.allowExplicitSuFallback(InstallerModeGate.WriteScope.MODE_SWITCH))
    }

    @Test
    fun ksuChannelMustBeRegistered() {
        try {
            InstallerRootSourceRegistry.install(null)
            InstallerRootSourceRegistry.require()
            fail("unregistered KSU channel accepted")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("KSU_CHANNEL_UNAVAILABLE"))
        } finally {
            InstallerRootSourceRegistry.install(null)
        }
    }

    @Test
    fun registeredKsuChannelIsReturned() {
        val channel = NativeRootChannel { error("not invoked by this test") }
        try {
            InstallerRootSourceRegistry.install(channel)
            assertEquals(channel, InstallerRootSourceRegistry.require())
        } finally {
            InstallerRootSourceRegistry.install(null)
        }
    }
}
