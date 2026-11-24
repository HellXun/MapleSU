package dev.maplesu.manager.workmode.boot

import dev.maplesu.manager.workmode.ActivationSource
import dev.maplesu.manager.workmode.TestFixtures
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.DetectionOrigin
import dev.maplesu.manager.workmode.detect.DetectionRequest
import dev.maplesu.manager.workmode.detect.DetectionResult
import dev.maplesu.manager.workmode.detect.ModeDetector
import dev.maplesu.manager.workmode.detect.ModeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 启动判定闭环：未判定 → 探测 → record/complete → 激活一次 → 门控。 */
class ModeBootstrapPipelineTest {

    private class FakeDetector(
        private val result: DetectionResult = TestFixtures.detection(ModeState.MODE_A_ACTIVE),
        private val error: Throwable? = null,
    ) : ModeDetector {
        var received: DetectionRequest? = null
        override suspend fun detect(request: DetectionRequest): DetectionResult {
            received = request
            error?.let { throw it }
            return result
        }
    }

    private class FakeRuntime(private val activateResult: Boolean = true) : ModeRuntimePort {
        val calls = mutableListOf<String>()
        var detection: DetectionResult? = null
        var mode: WorkMode? = null
        var bootId: String? = null
        var source: ActivationSource? = null

        override fun recordDetection(result: DetectionResult) {
            detection = result
            calls += "record:${result.state.name}"
        }

        override fun completeBootstrap(result: DetectionResult) {
            calls += "complete:${result.bootId}"
        }

        override fun activate(mode: WorkMode, bootId: String, source: ActivationSource): Boolean {
            calls += "activate:${mode.suffix}"
            this.mode = mode
            this.bootId = bootId
            this.source = source
            return activateResult
        }
    }

    private val request = DetectionRequest(
        origin = DetectionOrigin.STARTUP,
        allowRootProbe = false,
        allowLiteExecutor = false,
    )

    @Test
    fun activatedRunsExactlyOnceInOrder() = runBlocking {
        val runtime = FakeRuntime()
        val detector = FakeDetector(TestFixtures.detection(ModeState.MODE_A_ACTIVE, bootId = "boot-1"))
        val outcome = ModeBootstrapPipeline(detector, runtime).run(request)
        assertTrue(outcome is BootstrapOutcome.Activated)
        val activated = outcome as BootstrapOutcome.Activated
        assertEquals(WorkMode.LITE, activated.mode)
        assertEquals("boot-1", activated.bootId)
        assertEquals(ModeState.MODE_A_ACTIVE, activated.state)
        assertEquals(
            listOf("record:MODE_A_ACTIVE", "complete:boot-1", "activate:A"),
            runtime.calls,
        )
        assertEquals(WorkMode.LITE, runtime.mode)
        assertEquals("boot-1", runtime.bootId)
        assertEquals(ActivationSource.BOOT_DETECTION, runtime.source)
        assertEquals(request, detector.received)
    }

    @Test
    fun unresolvedStateDoesNotActivate() = runBlocking {
        val runtime = FakeRuntime()
        val outcome = ModeBootstrapPipeline(
            FakeDetector(TestFixtures.detection(ModeState.NOT_INSTALLED)),
            runtime,
        ).run(request)
        assertTrue(outcome is BootstrapOutcome.Unresolved)
        assertEquals(ModeState.NOT_INSTALLED, (outcome as BootstrapOutcome.Unresolved).state)
        assertEquals("NO_ACTIVE_MODE", outcome.reason)
        // ModeState.wire = enum name（NOT_INSTALLED 无 MODE_ 前缀）：记录格式与 wire 名一致。
        assertEquals(listOf("record:NOT_INSTALLED", "complete:boot-1"), runtime.calls)
        assertNull(runtime.mode)
    }

    @Test
    fun foreignSuDoesNotActivate() = runBlocking {
        val runtime = FakeRuntime()
        val outcome = ModeBootstrapPipeline(
            FakeDetector(TestFixtures.detection(ModeState.FOREIGN_SU)),
            runtime,
        ).run(request)
        assertTrue(outcome is BootstrapOutcome.Unresolved)
        assertTrue(runtime.calls.none { it.startsWith("activate") })
    }

    @Test
    fun blankBootIdDoesNotActivate() = runBlocking {
        val runtime = FakeRuntime()
        val outcome = ModeBootstrapPipeline(
            FakeDetector(TestFixtures.detection(ModeState.MODE_A_ACTIVE, bootId = "")),
            runtime,
        ).run(request)
        assertTrue(outcome is BootstrapOutcome.Unresolved)
        assertEquals("BOOT_ID_UNKNOWN", (outcome as BootstrapOutcome.Unresolved).reason)
        assertTrue(runtime.calls.none { it.startsWith("activate") })
    }

    @Test
    fun activationRejectedStaysUnresolved() = runBlocking {
        val runtime = FakeRuntime(activateResult = false)
        val outcome = ModeBootstrapPipeline(
            FakeDetector(TestFixtures.detection(ModeState.MODE_A_ACTIVE)),
            runtime,
        ).run(request)
        assertTrue(outcome is BootstrapOutcome.Unresolved)
        assertEquals("ACTIVATION_REJECTED", (outcome as BootstrapOutcome.Unresolved).reason)
        assertEquals(1, runtime.calls.count { it.startsWith("activate") })
    }

    @Test
    fun detectorThrowIsFailedAndDoesNotTouchRuntime() = runBlocking {
        val runtime = FakeRuntime()
        val outcome = ModeBootstrapPipeline(
            FakeDetector(error = IllegalStateException("probe died")),
            runtime,
        ).run(request)
        assertTrue(outcome is BootstrapOutcome.Failed)
        assertEquals("MODE_DETECT_FAILED", (outcome as BootstrapOutcome.Failed).code)
        assertTrue(runtime.calls.isEmpty())
    }

    @Test
    fun observerSeesOrderedStagesAndCannotBreakFlow() = runBlocking {
        val stages = mutableListOf<BootstrapStage>()
        val outcome = ModeBootstrapPipeline(
            detector = FakeDetector(TestFixtures.detection(ModeState.MODE_A_ACTIVE)),
            runtime = FakeRuntime(),
            observer = { stage, _ ->
                stages += stage
                error("observer must not break the pipeline")
            },
        ).run(request)
        assertTrue(outcome is BootstrapOutcome.Activated)
        assertEquals(BootstrapStage.DETECT_START, stages.first())
        assertEquals(BootstrapStage.ACTIVATED, stages.last())
        assertTrue(stages.indexOf(BootstrapStage.RECORDED) < stages.indexOf(BootstrapStage.BOOTSTRAP_COMPLETED))
        assertTrue(stages.indexOf(BootstrapStage.BOOTSTRAP_COMPLETED) < stages.indexOf(BootstrapStage.ACTIVATED))
    }

    @Test
    fun startupRequestNeverAllowsRootProbe() = runBlocking {
        val detector = FakeDetector()
        ModeBootstrapPipeline(detector, FakeRuntime()).run(request)
        val received = detector.received!!
        assertFalse(received.allowRootProbe)
        assertFalse(received.allowLiteExecutor)
        assertEquals(DetectionOrigin.STARTUP, received.origin)
    }

    @Test
    fun manualBootInstallKeepsKernelProbeThroughTheNotInstalledFallback() = runBlocking {
        val runtime = FakeRuntime()
        val quickNotInstalled = TestFixtures.detection(ModeState.NOT_INSTALLED).copy(
            trace = listOf(dev.maplesu.manager.workmode.detect.ProbeTrace(
                "E1", dev.maplesu.manager.workmode.detect.Strength.S, false, "ENV_NOT_INSTALLED", 0)),
        )
        val detector = dev.maplesu.manager.workmode.detect.DefaultModeDetector(
            ksuProbe = dev.maplesu.manager.workmode.detect.KsuKernelProbe {
                dev.maplesu.manager.workmode.detect.KsuFacts()
            },
            bootIdSource = dev.maplesu.manager.workmode.detect.BootIdSource { "boot-1" },
        )
        val outcome = ModeBootstrapPipeline(detector, runtime,
            environmentOverride = { quickNotInstalled },
        ).run(ModeBootstrapCoordinator.AFTER_ROOT_GRANTED_REQUEST)
        assertTrue(outcome is BootstrapOutcome.Unresolved)
        val detection = requireNotNull(runtime.detection)
        assertEquals(ModeState.NOT_INSTALLED, detection.state)
        val calls = mutableListOf<String>()
        val warmup = dev.maplesu.manager.runtime.ultra.UltraStartupWarmup.run(
            dev.maplesu.manager.runtime.ultra.UltraStartupWarmup.Input(
                activeMode = null, detectionSettled = true,
                ksuResponded = detection.trace.firstOrNull { it.id == "S1" }?.ok,
                pendingInstallCredential = true, suspended = false, quiescing = false,
                switchPending = false, conflictingRoot = false,
            ),
            setupEnvironment = { calls += "setup" },
            pairEnvironment = { calls += "pair"; true },
            retryDetection = { calls += "detect" },
        )
        assertEquals("Keep the completed kernel probe so an exported boot can initialize", false,
            detection.trace.firstOrNull { it.id == "S1" }?.ok)
        assertTrue(warmup.ran)
        assertEquals(listOf("setup", "pair", "detect"), calls)
        assertNull("Pending identity alone must never activate a backend", runtime.mode)
    }
}
