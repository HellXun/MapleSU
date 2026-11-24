package dev.maplesu.manager.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure part of the origin gate: only an untrusted transient is refused, in both write scopes.
 * No /proc and no JNI involved.
 */
class InstallerModeGatePolicyTest {
    private val scopes = listOf(
        InstallerModeGate.WriteScope.LITE_INSTALL,
        InstallerModeGate.WriteScope.MODE_SWITCH,
    )

    @Test
    fun classifiesLiteMarkers() {
        assertEquals(InstallerModeGate.Origin.LITE,
            InstallerModeGate.classify(listOf("/system/bin/maplesu-runtime")))
        assertEquals(InstallerModeGate.Origin.LITE,
            InstallerModeGate.classify(listOf("/system/bin/sh", "/data/local/tmp/libmaplesu_runtime.so")))
    }

    @Test
    fun classifiesKsuMarkers() {
        assertEquals(InstallerModeGate.Origin.KSU,
            InstallerModeGate.classify(listOf("/data/adb/ksud --late-load")))
        assertEquals(InstallerModeGate.Origin.KSU,
            InstallerModeGate.classify(listOf("/system/bin/sh", "/data/adb/kernelsu/bin")))
    }

    @Test
    fun untrustedWinsOverAnyOtherMarker() {
        assertEquals(InstallerModeGate.Origin.UNTRUSTED,
            InstallerModeGate.classify(listOf("/data/adb/ksud", "/data/local/tmp/libmaplesu_root_consumer.so")))
    }

    @Test
    fun unknownChainStaysUnknown() {
        assertEquals(InstallerModeGate.Origin.UNKNOWN,
            InstallerModeGate.classify(listOf("/system/bin/sh", "/init")))
        assertEquals(InstallerModeGate.Origin.UNKNOWN, InstallerModeGate.classify(emptyList()))
    }

    /** Every trusted origin is accepted, in every scope: "user tapped it, so flash it". */
    @Test
    fun onlyUntrustedIsEverRefused() {
        for (scope in scopes) {
            assertNull(scope.name, InstallerModeGate.refusal(InstallerModeGate.Origin.LITE, scope))
            assertNull(scope.name, InstallerModeGate.refusal(InstallerModeGate.Origin.KSU, scope))
            assertNull(scope.name, InstallerModeGate.refusal(InstallerModeGate.Origin.UNKNOWN, scope))
        }
    }

    /** The untrusted refusal must stay real: not a constant that every origin also returns. */
    @Test
    fun untrustedIsRefusedInBothScopes() {
        for (scope in scopes) {
            assertEquals(scope.name, InstallerModeGate.REFUSED,
                InstallerModeGate.refusal(InstallerModeGate.Origin.UNTRUSTED, scope))
        }
    }

    /** The matrix must not be reachable with a kernel-proof escape hatch any more. */
    @Test
    fun refusalMatrixNoLongerTakesKernelProof() {
        val threeArg = InstallerModeGate::class.java.methods
            .filter { it.name.startsWith("refusal") && it.parameterTypes.size == 3 }
        assertTrue("refusal must not accept a kernel-proof flag: $threeArg", threeArg.isEmpty())
    }

    /**
     * A: the production kernel proof is the hardcoded stub (`KernelProof.inService()` ⇒ version 0 ⇒
     * `usable == false`). With that proof unavailable, `MODE_SWITCH` writes from LITE / KSU / UNKNOWN
     * origins must still be allowed — Normal→Ultra must not be refused for a missing proof.
     */
    @Test
    fun modeSwitchIsAllowedForEveryOriginWhileKernelProofIsUnavailable() {
        assertFalse("the production proof stub must stay unusable", KernelProof.inService().usable)
        for (origin in listOf(
            InstallerModeGate.Origin.LITE,
            InstallerModeGate.Origin.KSU,
            InstallerModeGate.Origin.UNKNOWN,
        )) {
            assertNull(origin.name,
                InstallerModeGate.refusal(origin, InstallerModeGate.WriteScope.MODE_SWITCH))
        }
    }

    @Test
    fun refusalCodesAreStable() {
        assertEquals("INSTALLER_UNTRUSTED_PARTITION_WRITE_REFUSED", InstallerModeGate.REFUSED)
    }
}
