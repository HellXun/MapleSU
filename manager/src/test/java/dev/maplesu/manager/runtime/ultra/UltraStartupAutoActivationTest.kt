package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.ultrakernel.UltraKernelCredential
import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.*
import org.junit.Test

/** Host transaction: production recovery, setup, installer and pairing with explicit SDK fixtures. */
class UltraStartupAutoActivationTest {
    private val input = UltraStartupWarmup.Input(
        activeMode = null, detectionSettled = true, ksuResponded = false,
        pendingInstallCredential = false, suspended = false, quiescing = false,
        switchPending = false, conflictingRoot = false,
    )
    private val live = UltraKernelCredential.of("Aa1".repeat(16)).withMetadataDisplacement(4096)
        .copySecret().toString(Charsets.US_ASCII)
    private val stale = UltraKernelCredential.of("Bb2".repeat(16)).withMetadataDisplacement(8192)
        .copySecret().toString(Charsets.US_ASCII)

    private inner class Fixture {
        var local: String? = null
        var candidate: String? = live
        var candidateUid: Int? = 0
        var storeOk = true
        var installed = false
        var failNextInstall = false
        var submits = 0
        var detections = 0
        val events = mutableListOf<String>()

        private fun receipt(state: String? = null, ok: Boolean = true) = NativeReceipt.parse(
            """{"backend":"maplesu_ultra","ok":$ok,"sdkError":"${if (ok) "OK" else "TRANSIENT_IO"}","data":{"state":"$state"}}""")!!

        fun recover(): Boolean {
            val result = UltraIdentityResolution.recover(object : UltraIdentityResolution.Port {
                override fun currentKey() = local
                override fun blocked() = false
                override fun bootId() = "boot-1"
                override fun rootUid(key: String): Int? {
                    events += if (key == live) "live-uid" else "stale-uid"
                    return if (key == live) candidateUid else null
                }
                override fun kernelKey() = candidate.also { events += "claim" }
                override fun brokerKey(): String? = null
                override fun backupKey(): String? = null
                override fun store(expected: String?, key: String): Boolean {
                    assertEquals(expected, local)
                    events += "store"
                    if (storeOk) local = key
                    return storeOk
                }
                override fun accepted(key: String) { events += "accepted" }
            })
            return result == "RECOVERED" || result == "RECOVERY_LOCAL_KEY_VERIFIED"
        }

        private fun setup() = UltraAutoSetupAttempt.run(object : UltraAutoSetupAttempt.Port {
            override fun blockedReason(): String? = null
            override fun state() = when {
                local != live || candidateUid != 0 -> UltraEnvironmentProbe.State.Unknown
                installed -> UltraEnvironmentProbe.State.Running
                else -> UltraEnvironmentProbe.State.NotInstalled
            }
            override fun recordAttempt() { events += "attempt" }
            override fun install() = UltraEnvironmentInstaller.install(object : UltraEnvironmentInstaller.InstallPort {
                override fun environmentState() = receipt(if (installed) "Running" else "NotInstalled")
                override fun submitInstall(): NativeReceipt {
                    assertEquals(live, local)
                    assertEquals(0, candidateUid)
                    submits++
                    events += "submit"
                    if (failNextInstall) {
                        failNextInstall = false
                        return receipt(ok = false)
                    }
                    installed = true
                    return receipt()
                }
            }, UltraEnvironmentInstaller.Intent.ReuseRunning, UltraEnvironmentInstaller.Attachments(
                verify = {
                    events += "verify-install"
                    IdentityObservation(InstalledIdentity.unknown(), IdentityVerification.passed(null))
                },
                persist = { events += "persist-install" },
                whitelist = {
                    events += "whitelist"
                    UltraSelfWhitelist.Result(true, added = true, alreadyPresent = false,
                        attempted = true, listVerified = true)
                },
                backup = { events += "backup"; null },
                refreshProbe = { events += "refresh" },
            ))
            override fun refresh() = Unit
        })

        fun run(startup: UltraStartupWarmup.Input = input): UltraStartupWarmup.Result = UltraStartupWarmup.run(startup,
            recoverIdentity = ::recover,
            setupEnvironment = { setup() },
            pairEnvironment = {
                // UltraAutoPair.ensure also attempts identity recovery before reading its snapshot.
                recover()
                UltraAutoPair.complete(UltraEnvironmentProbe.Snapshot(
                    state = if (installed) UltraEnvironmentProbe.State.Running else UltraEnvironmentProbe.State.NotInstalled,
                    kernelRootVerified = local == live && candidateUid == 0,
                ), current = { local == live }, alreadyPaired = { true }, persist = {}, verifyManagerSu = { true }, maintain = {}).paired
            },
            retryDetection = { detections++ },
        )
    }

    @Test fun freshManagerClaimsVerifiesStoresInstallsAndPairsInOneStartupPass() {
        val fixture = Fixture()
        assertTrue(fixture.run().paired)
        assertEquals(live, fixture.local)
        assertEquals(1, fixture.submits)
        assertTrue(fixture.installed)
        assertEquals(1, fixture.detections)
        for ((before, after) in listOf("claim" to "live-uid", "live-uid" to "store",
            "store" to "submit", "submit" to "verify-install", "verify-install" to "persist-install",
            "persist-install" to "whitelist", "whitelist" to "backup", "backup" to "refresh")) {
            assertTrue("$before must precede $after", fixture.events.indexOf(before) < fixture.events.indexOf(after))
        }
    }

    @Test fun staleKeyAndEveryHistoricalStartupRecordStillRecoverBeforeSetup() {
        for (startup in listOf(input.copy(activeMode = WorkMode.LITE),
            input.copy(pendingInstallCredential = true), input.copy(verifiedUltraIdentity = true),
            input.copy(postBootUltraSwitchRecovery = true))) {
            for (previous in listOf(null, stale)) {
                val fixture = Fixture().apply { local = previous }
                assertTrue(fixture.run(startup).paired)
                assertEquals("startup=$startup must install with the recovered key immediately", 1, fixture.submits)
                assertTrue(fixture.installed)
                assertTrue(fixture.events.indexOf("claim") < fixture.events.indexOf("submit"))
            }
        }
    }

    @Test fun aTransientSubmissionFailureCanRecoverOnTheNextForegroundEventInTheSameBoot() {
        val fixture = Fixture().apply { failNextInstall = true }
        fixture.run()
        assertFalse(fixture.installed)
        assertEquals(1, fixture.submits)
        assertTrue(fixture.run(input.copy(verifiedUltraIdentity = true)).paired)
        assertTrue(fixture.installed)
        assertEquals(2, fixture.submits)
        fixture.run(input.copy(activeMode = WorkMode.LITE))
        assertEquals("Running reuse must not submit again", 2, fixture.submits)
    }

    @Test fun historicalInstallEvidenceWithoutAWorkingKernelStillDoesNotSubmit() {
        for (startup in listOf(input.copy(activeMode = WorkMode.LITE),
            input.copy(pendingInstallCredential = true), input.copy(verifiedUltraIdentity = true),
            input.copy(postBootUltraSwitchRecovery = true))) {
            val fixture = Fixture().apply { local = stale; candidate = null }
            assertFalse(fixture.run(startup).paired)
            assertEquals(stale, fixture.local)
            assertEquals(0, fixture.submits)
            assertEquals(0, fixture.detections)
        }
    }

    @Test fun aLiveRecoveredKernelCompletesSetupWhileTheGeneralDetectorIsUnsettled() {
        val fixture = Fixture()
        assertTrue(fixture.run(input.copy(detectionSettled = false, ksuResponded = null)).paired)
        assertEquals(1, fixture.submits)
        assertTrue(fixture.installed)
        assertEquals(1, fixture.detections)
    }

    @Test fun candidateWithoutLiveUidZeroOrAtomicStorageNeverInstalls() {
        for (scenario in 0..2) {
            val fixture = Fixture().apply {
                when (scenario) {
                    0 -> candidate = null
                    1 -> candidateUid = 10001
                    2 -> storeOk = false
                }
            }
            assertFalse(fixture.run().paired)
            assertFalse(fixture.installed)
            assertEquals(0, fixture.submits)
            assertNull(fixture.local)
        }
    }
}
