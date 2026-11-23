package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.workmode.WorkMode
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class UltraStartupIdentityRecoveryTest {
    private val absent = UltraStartupWarmup.Input(
        activeMode = null, detectionSettled = true, ksuResponded = false,
        pendingInstallCredential = false, suspended = false, quiescing = false,
        switchPending = false, conflictingRoot = false,
    )

    @Test fun aFreshManagerRecoversBeforeDecidingThatNoInstallIdentityExists() {
        val calls = mutableListOf<String>()
        val result = UltraStartupWarmup.run(absent,
            setupEnvironment = { calls += "setup" },
            pairEnvironment = { calls += "pair"; true },
            retryDetection = { calls += "detect" },
            recoverIdentity = { calls += "recover"; true })
        assertTrue(result.ran)
        assertTrue(result.paired)
        assertEquals(listOf("recover", "setup", "pair", "detect"), calls)
    }

    @Test fun failedRecoveryDoesNotCreateRootOrSubmitAnEnvironmentInstall() {
        val calls = mutableListOf<String>()
        val result = UltraStartupWarmup.run(absent,
            setupEnvironment = { error("no SDK identity") },
            pairEnvironment = { error("no SDK identity") },
            retryDetection = { error("no root proof") },
            recoverIdentity = { calls += "recover"; false })
        assertFalse(result.ran)
        assertFalse(result.paired)
        assertEquals("NO_PENDING_INSTALL", result.reason)
        assertEquals(listOf("recover"), calls)
    }

    @Test fun restoredBootSdkStartsSetupWithoutAPermanentInstallIdentity() {
        for (recovery in listOf("BOOT_SDK_CURRENT", "BOOT_SDK_RESTORED")) {
            val calls = mutableListOf<String>()
            val result = UltraStartupWarmup.run(absent,
                setupEnvironment = { calls += "setup" },
                pairEnvironment = { calls += "pair"; false },
                retryDetection = { error("pairing was not completed") },
                recoverIdentity = { UltraStartupWarmup.identityRecovered(recovery) })
            assertTrue("$recovery must enter setup", result.ran)
            assertFalse(result.paired)
            assertEquals(listOf("setup", "pair"), calls)
        }
    }

    @Test fun unsuccessfulRecoveryDoesNotBecomeAnEnvironmentIdentity() {
        for (recovery in listOf("RECOVERY_SUSPENDED", "BOOT_SDK_RECOVERY_UNREADABLE",
            "RECOVERY_ROOT_NOT_VERIFIED", "RECOVERY_SCOPE_CHANGED", "RECOVERY_STORE_FAILED")) {
            assertFalse(recovery, UltraStartupWarmup.identityRecovered(recovery))
        }
        for (recovery in listOf("RECOVERED", "RECOVERY_LOCAL_KEY_VERIFIED")) {
            assertTrue(recovery, UltraStartupWarmup.identityRecovered(recovery))
        }
    }

    @Test fun bootSessionReadinessControlsDetectionWithoutPairingAPermanentKey() {
        for (ready in listOf(false, true)) {
            val calls = mutableListOf<String>()
            val result = UltraStartupWarmup.run(absent.copy(detectionSettled = false, ksuResponded = null),
                setupEnvironment = { calls += "setup" },
                pairEnvironment = { error("temporary owner must not read or pair a permanent key") },
                retryDetection = { calls += "detect" },
                recoverIdentity = { true },
                bootSessionReady = { calls += "verify-boot-session"; ready })
            assertTrue(result.ran)
            assertEquals(ready, result.paired)
            assertEquals(listOf("setup", "verify-boot-session") + if (ready) listOf("detect") else emptyList(), calls)
        }
    }

    @Test fun absentBootSessionUsesThePermanentPairingFlow() {
        val calls = mutableListOf<String>()
        val result = UltraStartupWarmup.run(absent,
            setupEnvironment = { calls += "setup" },
            pairEnvironment = { calls += "pair"; true },
            retryDetection = { calls += "detect" },
            recoverIdentity = { true },
            bootSessionReady = { calls += "check-boot-session"; null })
        assertTrue(result.paired)
        assertEquals(listOf("setup", "check-boot-session", "pair", "detect"), calls)
    }

    @Test fun aLiveVerifiedSdkIdentityDoesNotWaitForAnUnknownKsuProbe() {
        val calls = mutableListOf<String>()
        val result = UltraStartupWarmup.run(absent.copy(ksuResponded = null),
            setupEnvironment = { calls += "setup" },
            pairEnvironment = { calls += "pair"; true },
            retryDetection = { calls += "detect" },
            recoverIdentity = { calls += "recover"; true })
        assertTrue(result.paired)
        assertEquals(listOf("recover", "setup", "pair", "detect"), calls)
    }

    @Test fun liveSdkIdentitySetsUpBeforeUnsettledDetectionThenRequestsRealDetection() {
        val calls = mutableListOf<String>()
        val result = UltraStartupWarmup.run(absent.copy(detectionSettled = false, ksuResponded = null),
            setupEnvironment = { calls += "setup" },
            pairEnvironment = { calls += "pair"; true },
            retryDetection = { calls += "detect" },
            recoverIdentity = { calls += "recover"; true })
        assertTrue(result.ran)
        assertTrue(result.paired)
        assertEquals(listOf("recover", "setup", "pair", "detect"), calls)
    }

    @Test fun unsettledDetectionWithoutLiveIdentityDoesNotSubmitSetup() {
        var recoveries = 0
        val result = UltraStartupWarmup.run(absent.copy(detectionSettled = false),
            setupEnvironment = { error("no live identity") },
            pairEnvironment = { error("no live identity") }, retryDetection = { error("not paired") },
            recoverIdentity = { recoveries++; false })
        assertFalse(result.ran)
        assertEquals("DETECTION_PENDING", result.reason)
        assertEquals(1, recoveries)
    }

    @Test fun knownUltraAndHistoricalInstallEvidenceRecoverTheLiveKeyBeforeSetup() {
        for (input in listOf(absent.copy(activeMode = WorkMode.LITE),
            absent.copy(pendingInstallCredential = true), absent.copy(verifiedUltraIdentity = true),
            absent.copy(postBootUltraSwitchRecovery = true))) {
            val calls = mutableListOf<String>()
            val result = UltraStartupWarmup.run(input,
                setupEnvironment = { calls += "setup" }, pairEnvironment = { calls += "pair"; true },
                retryDetection = {}, recoverIdentity = { calls += "recover"; true })
            assertTrue(result.paired)
            assertEquals(listOf("recover", "setup", "pair"), calls)
        }
    }

    @Test fun explicitOwnershipAndActiveTransactionsStopBeforeAnyRecoveryRead() {
        for (input in listOf(absent.copy(activeMode = WorkMode.LITE), absent.copy(suspended = true),
            absent.copy(quiescing = true), absent.copy(switchPending = true),
            absent.copy(conflictingRoot = true), absent.copy(ksuResponded = true))) {
            val result = UltraStartupWarmup.run(input,
                setupEnvironment = { error("blocked setup") }, pairEnvironment = { error("blocked pairing") },
                retryDetection = { error("blocked detection") },
                recoverIdentity = { error("blocked recovery") },
                bootSessionReady = { error("blocked boot session") })
            assertFalse(result.ran)
        }
    }

    @Test fun recoveredIdentityAloneDoesNotAnnounceSuccessfulPairing() {
        var detections = 0
        val result = UltraStartupWarmup.run(absent, setupEnvironment = {}, pairEnvironment = { false },
            retryDetection = { detections++ }, recoverIdentity = { true })
        assertTrue(result.ran)
        assertFalse(result.paired)
        assertEquals(0, detections)
    }

    @Test fun interruptedRecoveryDoesNotContinueToSetup() {
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            UltraStartupWarmup.run(absent,
                setupEnvironment = { error("cancelled setup") }, pairEnvironment = { error("cancelled pairing") },
                retryDetection = { error("cancelled detection") },
                recoverIdentity = { throw java.util.concurrent.CancellationException() })
        }
    }

    @Test fun productionWarmupRecoversLiveIdentityAndOnlyBlocksAnExecutingSwitch() {
        val source = listOf(File("src/main/java/com/maplesu/core/KernelSUApplication.kt"),
            File("manager/src/main/java/com/maplesu/core/KernelSUApplication.kt")).first { it.isFile }.readText()
        val warmup = source.substringAfter("val warmup = dev.maplesu.manager.runtime.ultra.UltraStartupWarmup.run(")
            .substringBefore("Log.i(TAG, \"Ultra startup warmup:")
        assertTrue(warmup.contains("switchPending = dev.maplesu.manager.workmode.ui.WorkModeSwitchSession.executing"))
        assertFalse(warmup.contains("switchPending = dev.maplesu.manager.workmode.ui.WorkModeSwitchSession.pending"))
        val recovery = warmup.substringAfter("recoverIdentity = {")
        assertTrue(recovery.contains("UltraIdentityRecovery.recover(this)"))
        assertTrue(recovery.contains("UltraStartupWarmup.identityRecovered(result)"))
    }
}
