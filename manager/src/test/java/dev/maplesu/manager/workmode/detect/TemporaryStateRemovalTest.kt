package dev.maplesu.manager.workmode.detect

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TemporaryStateRemovalTest {
    @Test fun legacyTemporaryRecordIsDiscardedInsteadOfInherited() {
        val values = EnvironmentSnapshotCodec.encode(
            EnvironmentSnapshot(null, true, null, "boot", 1L),
        ) + ("env.suTemp" to "1")
        assertNull(EnvironmentSnapshotCodec.decode(values))
    }

    @Test fun realSuWithoutMapleModeNeverPersistsAnInstalledState() {
        val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence())
        val probes = object : EnvironmentProbes {
            override fun suPresent() = true
            override fun ultraFingerprint(): String? = null
            override fun ksuNormal() = false
        }
        val outcome = EnvironmentResolver(probes, store).resolve("boot")
        assertEquals(EnvironmentVerdict.UNKNOWN, outcome.verdict)
        assertNull(outcome.mode)
        assertNull(store.snapshot()?.mode)
        assertEquals(listOf("ultraCurrent", "su", "ultra"), outcome.probes)
    }

    @Test fun directInstallationDoesNotDependOnModeRootRouting() {
        val screen = File("src/main/java/com/maplesu/core/ui/screen/install/InstallScreen.kt").readText()
        val check = screen.substringAfter("rootGranted = withContext(Dispatchers.IO)")
            .substringBefore("managerInstalled = withContext")
        assertTrue(check.contains("EnvironmentProbesAdapter(context).suPresent()"))
        assertFalse(check.contains("rootAvailable()"))
    }

    @Test fun missingMapleModeDoesNotWaitForeverForMapleHiddenState() {
        val screen = File("src/main/java/com/maplesu/core/ui/screen/install/InstallScreen.kt").readText()
        val suppression = screen.substringAfter("val directInstallSuppressed = remember(")
            .substringBefore("val hasRoot")
        assertTrue(suppression.contains("val mapleModeKnown"))
        assertTrue(suppression.contains("HiddenStartupConfirm.masking(context)"))
        assertTrue(suppression.contains("HiddenStartupConfirm.isPending(context)"))
        assertTrue(suppression.contains("mapleModeKnown && HiddenStartupConfirm.current(context)"))
    }
}
