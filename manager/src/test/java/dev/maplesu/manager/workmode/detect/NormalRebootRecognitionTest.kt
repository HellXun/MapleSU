package dev.maplesu.manager.workmode.detect

import dev.maplesu.manager.workmode.ActivationSource
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.boot.BootstrapOutcome
import dev.maplesu.manager.workmode.boot.ModeBootstrapCoordinator
import dev.maplesu.manager.workmode.boot.ModeBootstrapPipeline
import dev.maplesu.manager.workmode.boot.ModeRuntimePort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Host audit: real resolver -> projection -> bootstrap; native/boot/prefs ports are fixtures. */
class NormalRebootRecognitionTest {
    private class Probes(val su: Boolean, val ultra: String? = null, val ksu: Boolean? = true) : EnvironmentProbes {
        val calls = mutableListOf<String>()
        override fun suPresent(): Boolean { calls += "su"; return su }
        override fun ultraFingerprint(): String? { calls += "ultra"; return ultra }
        override fun ksuNormal(): Boolean? { calls += "ksu"; return ksu }
        override fun rootShellAvailable(): Boolean = error("root shell must not run")
    }

    private class Runtime : ModeRuntimePort {
        val calls = mutableListOf<String>()
        var active: WorkMode? = null
        var boot: String? = null
        override fun recordDetection(result: DetectionResult) { calls += "record:${result.state}" }
        override fun completeBootstrap(result: DetectionResult) { calls += "complete" }
        override fun activate(mode: WorkMode, bootId: String, source: ActivationSource): Boolean {
            assertEquals(ActivationSource.BOOT_DETECTION, source)
            calls += "activate"
            active = mode
            boot = bootId
            return true
        }
    }

    private fun normal(boot: String = "same-boot", dirty: Boolean = false) = EnvironmentSnapshot(
        mode = WorkMode.LITE, suAvailable = true, ultraEnvFingerprint = null,
        bootId = boot, checkedAtMillis = 1, dirty = dirty,
    )

    private fun store(initial: EnvironmentSnapshot? = null) =
        EnvironmentStateStore(InMemoryEnvironmentStatePersistence(initial))

    private suspend fun check(store: EnvironmentStateStore, probes: Probes, boot: String = "same-boot") =
        EnvironmentCheckRunner(EnvironmentResolver(probes, store), store, bootIdSource = { boot }).check()

    private suspend fun start(check: EnvironmentCheckOutcome): Runtime {
        val runtime = Runtime()
        val pipeline = ModeBootstrapPipeline(
            detector = object : ModeDetector {
                override suspend fun detect(request: DetectionRequest): DetectionResult =
                    error("terminal precheck must bypass the root-dependent detector")
            },
            runtime = runtime,
            environmentOverride = { EnvironmentDetectionProjection.override(check) },
        )
        val coordinator = ModeBootstrapCoordinator({ pipeline }, { runtime.active != null })
        val result = coordinator.runPhaseOne()
        if (check.mode == WorkMode.LITE) {
            assertTrue(result is BootstrapOutcome.Activated)
            assertEquals(WorkMode.LITE, runtime.active)
            assertEquals(check.bootId, runtime.boot)
            assertEquals(listOf("record:MODE_B_KERNEL_ONLY", "complete", "activate"), runtime.calls)
            assertNull("Normal must skip phase-two root detection", coordinator.runPhaseTwo())
        } else {
            assertTrue(result is BootstrapOutcome.Unresolved)
            assertEquals(ModeState.NOT_INSTALLED, (result as BootstrapOutcome.Unresolved).state)
            assertNull(runtime.active)
            assertEquals(listOf("record:NOT_INSTALLED", "complete"), runtime.calls)
        }
        return runtime
    }

    @Test fun knownNormalSoftRebootInheritsAndSkipsResidualUltraRecords() = runBlocking {
        val store = store(normal())
        val probes = Probes(su = true, ultra = "ultra:paired")
        val check = check(store, probes)
        assertEquals(EnvironmentVerdict.INHERITED, check.verdict)
        assertEquals(listOf("su", "ksu"), probes.calls)
        start(check)
        Unit
    }

    @Test fun fullRebootLosingTemporaryNormalEndsNotInstalled() = runBlocking {
        val store = store(normal(boot = "old-boot"))
        val check = check(store, Probes(su = false, ksu = false), boot = "new-boot")
        assertEquals(EnvironmentVerdict.NOT_INSTALLED, check.verdict)
        assertNull(store.snapshot()?.mode)
        start(check)
        Unit
    }

    @Test fun missingNativeObservationPreservesTheLastNormalSnapshot() = runBlocking {
        val previous = normal(boot = "old-boot")
        val store = store(previous)
        val check = check(store, Probes(su = true, ksu = null), boot = "new-boot")
        assertEquals(EnvironmentVerdict.UNKNOWN, check.verdict)
        assertEquals(previous, store.snapshot())
        assertEquals(WorkMode.LITE, check.mode)
        assertNull(EnvironmentDetectionProjection.override(check))
    }

    @Test fun runningUltraIsStillRecognizedAfterRecheckingAnOldCredentialSnapshot() = runBlocking {
        val store = store(normal().copy(mode = WorkMode.LITE, ultraEnvFingerprint = "ultra:paired"))
        val outcome = check(store, Probes(su = true, ultra = "ultra:running", ksu = false))
        assertEquals(EnvironmentVerdict.ULTRA, outcome.verdict)
        assertEquals(WorkMode.LITE, outcome.mode)
        assertEquals("ultra:running", store.snapshot()?.ultraEnvFingerprint)
    }

}
