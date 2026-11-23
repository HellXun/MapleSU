package dev.maplesu.manager.installer

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.maplesu.installer.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compile-verified instrumentation coverage for the W07b manager wiring. These do not write any
 * partition and do not require root; they assert the AIDL surface, gate codes and pure policies.
 */
@RunWith(AndroidJUnit4::class)
class InstallerModeSwitchAndroidTest {
    private val oldMethods = setOf("observeBootChain", "openBootImage", "prepareBootInstall",
        "commitBootInstall", "cancelBootInstall", "getBootInstall", "verifyBootInstall",
        "restoreBootInstall", "rebootAfterBootInstall")

    private val modeSwitchMethods = setOf("preflightModeSwitch", "beginModeSwitch",
        "snapshotModeSwitch", "openSwitchImage", "submitSwitchCandidate", "commitModeSwitch",
        "rollbackModeSwitch", "abortModeSwitch", "getModeSwitch", "verifyModeSwitch",
        "rebootAfterModeSwitch", "openSwitchLog")

    @Test
    fun aidlKeepsTheNineLegacyMethods() {
        val names = IInstallerService::class.java.methods.map { it.name }.toSet()
        assertTrue("missing legacy methods: ${oldMethods - names}", names.containsAll(oldMethods))
    }

    @Test
    fun aidlAppendsTheModeSwitchSurface() {
        val names = IInstallerService::class.java.methods.map { it.name }.toSet()
        assertTrue("missing mode switch methods: ${modeSwitchMethods - names}",
            names.containsAll(modeSwitchMethods))
        val bundle = Bundle::class.java
        val preflight = IInstallerService::class.java.getMethod(
            "preflightModeSwitch", String::class.java, bundle)
        assertEquals(Bundle::class.java, preflight.returnType)
    }

    @Test
    fun legacyBootInstallSignaturesAreUnchanged() {
        val prepare = IInstallerService::class.java.getMethod("prepareBootInstall",
            android.os.ParcelFileDescriptor::class.java, android.os.ParcelFileDescriptor::class.java,
            Bundle::class.java, String::class.java, String::class.java)
        assertEquals(Bundle::class.java, prepare.returnType)
        assertEquals(Boolean::class.javaPrimitiveType,
            IInstallerService::class.java.getMethod("rebootAfterBootInstall", String::class.java).returnType)
    }

    @Test
    fun gateRefusalCodesAreStable() {
        assertEquals("INSTALLER_JAILBREAK_PARTITION_WRITE_REFUSED", InstallerModeGate.REFUSED)
        // The origin/kernel-proof codes were deleted together with the checks that produced them;
        // only the jailbreak refusal survives (see InstallerModeGatePolicyTest).
    }

    @Test
    fun pendingSwitchStatesBlockOrdinaryWrites() {
        assertTrue(InstallerPending.isPendingSwitchState("REBOOT_REQUIRED"))
        assertTrue(InstallerPending.isPendingSwitchState("ROLLING_BACK"))
        assertFalse(InstallerPending.isPendingSwitchState("VERIFIED"))
    }

}
