package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraStartupWarmupTest {
    private val pending = UltraStartupWarmup.Input(
        activeMode = null, detectionSettled = true, ksuResponded = false,
        pendingInstallCredential = true, suspended = false, quiescing = false,
        switchPending = false, conflictingRoot = false,
    )

    private fun run(input: UltraStartupWarmup.Input, running: Boolean = true): Pair<UltraStartupWarmup.Result, List<String>> {
        val calls = mutableListOf<String>()
        val result = UltraStartupWarmup.run(input,
            setupEnvironment = { calls += "setup" },
            pairEnvironment = { calls += "pair"; running },
            retryDetection = { calls += "detect" },
        )
        return result to calls
    }

    @Test fun firstBootInitializesBeforePairingAndRequestsRealDetection() {
        val (result, calls) = run(pending)
        assertTrue("pending Ultra install must start before mode activation", result.ran)
        assertTrue(result.paired)
        assertEquals(listOf("setup", "pair", "detect"), calls)
    }

    @Test fun firstExportKeepsItsBoundKeyForSetupBeforeTheSdkEnvironmentExists() {
        var local: String? = null
        var archived: String? = null
        var installTask: String? = null
        val policy = UltraInstallCredentialPolicy({ local }, { null }, { null },
            { "Ab1".repeat(16).toByteArray() }, { false })
        val embedded = policy.forCandidate().withMetadataDisplacement(-208)
        policy.retainForExport(embedded, { archived = it }) {
            local = it
            installTask = UltraInstallCredentials.IMAGE_EXPORT_OPERATION
            true
        }
        assertEquals(archived, local)
        assertEquals(embedded, policy.existing())
        val (result, calls) = run(pending.copy(pendingInstallCredential =
            installTask == UltraInstallCredentials.IMAGE_EXPORT_OPERATION))
        assertTrue(result.ran)
        assertEquals(listOf("setup", "pair", "detect"), calls)
    }

    @Test fun setupWithoutRunningNeverRequestsActivation() {
        val (result, calls) = run(pending, running = false)
        assertTrue(result.ran)
        assertFalse(result.paired)
        assertEquals(listOf("setup", "pair"), calls)
    }

    @Test fun aTransientUnknownDuringStartupCompletesPairingAndRequestsDetection() {
        val calls = mutableListOf<String>()
        var reads = 0
        val result = UltraStartupWarmup.run(pending,
            setupEnvironment = { calls += "setup" },
            pairEnvironment = {
                UltraEnvironmentProbe.readUntilConfirmed(false, read = {
                    calls += "state"
                    if (++reads == 1) UltraEnvironmentProbe.State.Unknown else UltraEnvironmentProbe.State.Running
                }, pause = {}) == UltraEnvironmentProbe.State.Running
            },
            retryDetection = { calls += "detect" },
        )
        assertTrue(result.paired)
        assertEquals(listOf("setup", "state", "state", "detect"), calls)
    }

    @Test fun activatedLiteKeepsNormalWarmupWithoutInstallReceipt() {
        val (result, calls) = run(pending.copy(activeMode = WorkMode.LITE, pendingInstallCredential = false))
        assertTrue(result.ran)
        assertEquals(listOf("setup", "pair"), calls)
    }

    @Test fun previouslyVerifiedUltraCanRecoverAfterRebootWithoutPendingInstall() {
        val (result, calls) = run(pending.copy(pendingInstallCredential = false, verifiedUltraIdentity = true))
        assertTrue("a paired device must not deadlock behind mode activation after reboot", result.ran)
        assertEquals(listOf("setup", "pair", "detect"), calls)
    }

    @Test fun historicalPairingNeverActivatesAnEnvironmentThatDidNotRecover() {
        val (result, calls) = run(pending.copy(pendingInstallCredential = false,
            verifiedUltraIdentity = true), running = false)
        assertTrue(result.ran)
        assertFalse(result.paired)
        assertEquals(listOf("setup", "pair"), calls)
    }

    @Test fun verifiedIdentityStillRequiresSettledDetectionAndNoConflict() {
        val verified = pending.copy(pendingInstallCredential = false, verifiedUltraIdentity = true)
        listOf(verified.copy(suspended = true), verified.copy(quiescing = true),
            verified.copy(switchPending = true), verified.copy(conflictingRoot = true),
            verified.copy(ksuResponded = true), verified.copy(ksuResponded = null),
            verified.copy(detectionSettled = false), verified.copy(activeMode = WorkMode.LITE)).forEach { input ->
            val (result, calls) = run(input)
            assertFalse("unexpected recovery for $input", result.ran)
            assertTrue(calls.isEmpty())
        }
    }

    @Test fun unresolvedInputsRequireAllLocalAndDetectionEvidence() {
        listOf(
            pending.copy(detectionSettled = false),
            pending.copy(ksuResponded = null),
            pending.copy(ksuResponded = true),
            pending.copy(pendingInstallCredential = false),
            pending.copy(activeMode = WorkMode.LITE),
        ).forEach { input ->
            val (result, calls) = run(input)
            assertFalse("unexpected warmup for $input", result.ran)
            assertTrue(calls.isEmpty())
        }
    }

    @Test fun pauseQuiescenceSwitchAndConflictsBlockBothStartupPaths() {
        listOf(pending, pending.copy(activeMode = WorkMode.LITE)).forEach { input ->
            listOf(
                input.copy(suspended = true), input.copy(quiescing = true),
                input.copy(switchPending = true), input.copy(conflictingRoot = true),
                input.copy(ksuResponded = true),
            ).forEach { blocked ->
                val (result, calls) = run(blocked)
                assertFalse("unexpected warmup for $blocked", result.ran)
                assertTrue(calls.isEmpty())
            }
        }
    }
    @Test fun committedPostBootSwitchCanInitializeWithoutAnInstallerTask() {
        val (result, calls) = run(pending.copy(pendingInstallCredential = false,
            switchPending = true, postBootUltraSwitchRecovery = true))
        assertTrue(result.ran)
        assertEquals(listOf("setup", "pair", "detect"), calls)
    }

    @Test fun postBootSwitchReceiptDoesNotBypassPauseConflictOrUnsettledDetection() {
        val recovery = pending.copy(pendingInstallCredential = false,
            switchPending = true, postBootUltraSwitchRecovery = true)
        listOf(recovery.copy(suspended = true), recovery.copy(quiescing = true),
            recovery.copy(conflictingRoot = true), recovery.copy(ksuResponded = true),
            recovery.copy(ksuResponded = null), recovery.copy(detectionSettled = false),
            recovery.copy(activeMode = WorkMode.LITE)).forEach { input ->
            val (result, calls) = run(input)
            assertFalse("unexpected setup for $input", result.ran)
            assertTrue(calls.isEmpty())
        }
    }

    @Test fun recoveryReceiptOnlyMatchesTheIdleSamePendingSwitch() {
        assertTrue(UltraStartupWarmup.matchesPostBootSwitch("switch-1", false, false, null))
        assertTrue(UltraStartupWarmup.matchesPostBootSwitch("switch-1", false, true, "switch-1"))
        assertFalse(UltraStartupWarmup.matchesPostBootSwitch("switch-1", true, true, "switch-1"))
        assertFalse(UltraStartupWarmup.matchesPostBootSwitch("switch-1", false, true, "other"))
        assertFalse(UltraStartupWarmup.matchesPostBootSwitch("switch-1", false, true, null))
        assertFalse(UltraStartupWarmup.matchesPostBootSwitch(null, false, false, null))
    }

    @Test fun postBootSwitchWithoutRunningDoesNotRequestActivation() {
        val (result, calls) = run(pending.copy(pendingInstallCredential = false,
            switchPending = true, postBootUltraSwitchRecovery = true), running = false)
        assertTrue(result.ran)
        assertFalse(result.paired)
        assertEquals(listOf("setup", "pair"), calls)
    }

    @Test fun healthyLiveEnvironmentVerifiesManagerSuBeforeRecoveryOrMaintenance() {
        for (ready in listOf(true, false)) {
            val calls = mutableListOf<String>()
            val result = UltraStartupWarmup.run(pending.copy(liveEnvironmentReady = true,
                detectionSettled = false, ksuResponded = null),
                setupEnvironment = { error("live environment needs no install") },
                recoverIdentity = { error("live identity needs no recovery") },
                pairEnvironment = { calls += "manager-su"; ready },
                retryDetection = { calls += "detect" })
            assertEquals(ready, result.paired)
            assertEquals(listOf("manager-su") + if (ready) listOf("detect") else emptyList(), calls)
            assertEquals(if (ready) "RUNNING" else "MANAGER_SU_UNVERIFIED", result.reason)
        }
    }

    @Test fun aLiveSnapshotNeverBypassesConflictOrLifecycleGuards() {
        val live = pending.copy(liveEnvironmentReady = true)
        for (input in listOf(live.copy(suspended = true), live.copy(quiescing = true),
            live.copy(switchPending = true), live.copy(conflictingRoot = true),
            live.copy(ksuResponded = true), live.copy(activeMode = WorkMode.LITE))) {
            val result = UltraStartupWarmup.run(input,
                setupEnvironment = { error("blocked") }, pairEnvironment = { error("blocked") },
                retryDetection = { error("blocked") }, recoverIdentity = { error("blocked") })
            assertFalse(result.ran)
        }
    }

}
