package dev.maplesu.manager.workmode.boot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedStartupPolicyTest {
    @Test
    fun unresolvedModeIsExpedited() {
        assertTrue(PairedStartupPolicy.shouldExpedite(modeResolved = false, suspended = false))
    }

    @Test
    fun suspendedEnvironmentDoesNotResumeImplicitly() {
        assertFalse(PairedStartupPolicy.shouldExpedite(modeResolved = false, suspended = true))
    }

    @Test
    fun resolvedModeDoesNotNeedAnotherDetection() {
        assertFalse(PairedStartupPolicy.shouldExpedite(modeResolved = true, suspended = false))
    }

    @Test
    fun resolvedNormalDoesNotCallUltraSdk() {
        assertFalse(PairedStartupPolicy.refreshEnvironmentIfEligible(true, false) {
            error("resolved mode must not probe")
        })
    }

    @Test
    fun existingOrUnknownKsuEvidenceDoesNotCallUltraSdk() {
        for (responded in listOf(true, null)) {
            assertFalse(PairedStartupPolicy.refreshEnvironmentIfEligible(false, responded) {
                error("KSU must be excluded first")
            })
        }
    }

    @Test
    fun stalePairingWithUnknownOrStoppedEnvironmentDoesNotProveRunning() {
        var probes = 0
        assertFalse(PairedStartupPolicy.refreshEnvironmentIfEligible(false, false) {
            probes++
            false
        })
        assertTrue(probes == 1)
    }

    @Test
    fun onlyActualRunningResultIsReportedAsReady() {
        var probes = 0
        assertTrue(PairedStartupPolicy.refreshEnvironmentIfEligible(false, false) {
            probes++
            true
        })
        assertTrue(probes == 1)
    }

    @Test
    fun sdkFailureFallsBackWithoutPretendingReady() {
        assertFalse(PairedStartupPolicy.refreshEnvironmentIfEligible(false, false) {
            error("SDK_UNAVAILABLE")
        })
    }
}
