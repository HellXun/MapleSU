package dev.maplesu.manager.modules

import dev.maplesu.manager.workmode.ActivationSource
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.TestFixtures
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.ModeState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ModuleEnvironmentWatchTest {
    @Before fun before() {
        ModeRuntime.resetForTest()
        ModuleBackendPorts.resetForTest()
        ModeRuntime.installBootIdProviderForTest { "boot-1" }
    }

    @After fun after() {
        ModuleBackendPorts.resetForTest()
        ModeRuntime.resetForTest()
    }

    private fun activate(mode: WorkMode) {
        ModeRuntime.recordDetection(TestFixtures.detection(
            if (mode == WorkMode.LITE) ModeState.MODE_A_ACTIVE else ModeState.MODE_B_ACTIVE))
        assertTrue(ModeRuntime.activate(mode, "boot-1", ActivationSource.BOOT_DETECTION))
    }

    @Test fun completedUnresolvedDetectionEndsThePageWaitImmediately() = runBlocking {
        ModeRuntime.finishDetection()
        assertFalse(withTimeout(1_000) {
            ModuleEnvironmentWatch.awaitReady(ModuleWaitReason.MODE_UNRESOLVED, 15_000)
        })
        assertNull(ModeRuntime.active)
    }

    @Test fun finishingDetectionReleasesAnAlreadyWaitingPage() = runBlocking {
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            ModuleEnvironmentWatch.awaitReady(ModuleWaitReason.MODE_UNRESOLVED, 15_000)
        }
        assertFalse(pending.isCompleted)
        ModeRuntime.finishDetection()
        assertFalse(withTimeout(1_000) { pending.await() })
    }

    @Test fun detectionFailureReleasesThePageAndPreservesTheReason() = runBlocking {
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            ModuleEnvironmentWatch.awaitReady(ModuleWaitReason.MODE_UNRESOLVED, 15_000)
        }
        ModeRuntime.failBootstrap("MODE_DETECT_FAILED")
        assertFalse(withTimeout(1_000) { pending.await() })
        assertEquals("MODE_DETECT_FAILED", ModeRuntime.detectionFailure)
    }

    @Test fun liteActivationReleasesTheColdPageWithoutCallingTheKsuFactory() = runBlocking {
        ModuleBackendPorts.ksuFactory = { error("LITE never selects KSU") }
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            ModuleEnvironmentWatch.awaitReady(ModuleWaitReason.MODE_UNRESOLVED, 15_000)
        }
        activate(WorkMode.LITE)
        assertTrue(withTimeout(1_000) { pending.await() })
    }

    @Test fun ksuActivationWaitsForWiringWithoutExecutingTheBackend() = runBlocking {
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            ModuleEnvironmentWatch.awaitReady(ModuleWaitReason.MODE_UNRESOLVED, 15_000)
        }
        activate(WorkMode.LITE)
        assertFalse(pending.isCompleted)
        ModuleBackendPorts.ksuFactory = { error("Readiness does no backend I/O") }
        assertTrue(withTimeout(1_000) { pending.await() })
    }

    @Test fun failedKsuWiringIsNeverReportedAsReady() = runBlocking {
        activate(WorkMode.LITE)
        ModuleBackendPorts.markWiringFailed("MODULE_WIRING_FAILED")
        assertFalse(ModuleEnvironmentWatch.awaitReady(ModuleWaitReason.KSU_BACKEND_UNWIRED, 15_000))
        assertEquals("MODULE_WIRING_FAILED", ModuleBackendPorts.wiringFailureCode)
    }

    @Test fun aLaterDetectionAttemptCanActivateAfterTheFirstPageWaitEnded() = runBlocking {
        ModeRuntime.finishDetection()
        assertFalse(withTimeout(1_000) {
            ModuleEnvironmentWatch.awaitReady(ModuleWaitReason.MODE_UNRESOLVED, 15_000)
        })
        ModeRuntime.beginDetection()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            ModuleEnvironmentWatch.awaitReady(ModuleWaitReason.MODE_UNRESOLVED, 15_000)
        }
        assertFalse(pending.isCompleted)
        activate(WorkMode.LITE)
        assertTrue(withTimeout(1_000) { pending.await() })
    }

    @Test fun disposingThePageCancelsItsWaitWithoutChangingDetection() = runBlocking {
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            ModuleEnvironmentWatch.awaitReady(ModuleWaitReason.MODE_UNRESOLVED, 15_000)
        }
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertFalse(ModeRuntime.detectionComplete)
        assertNull(ModeRuntime.active)
    }
}
