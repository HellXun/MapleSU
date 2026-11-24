package dev.maplesu.manager.workmode.boot

import dev.maplesu.manager.workmode.ModeActivation
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.TestFixtures
import dev.maplesu.manager.workmode.detect.BootIdSource
import dev.maplesu.manager.workmode.detect.DefaultModeDetector
import dev.maplesu.manager.workmode.detect.DetectionOrigin
import dev.maplesu.manager.workmode.detect.DetectionRequest
import dev.maplesu.manager.workmode.detect.DetectionResult
import dev.maplesu.manager.workmode.detect.KsuFacts
import dev.maplesu.manager.workmode.detect.KsuKernelProbe
import dev.maplesu.manager.workmode.detect.LiteCredentialProbe
import dev.maplesu.manager.workmode.detect.LiteHandshakeProbe
import dev.maplesu.manager.workmode.detect.ModeDetector
import dev.maplesu.manager.workmode.detect.ModeState
import dev.maplesu.manager.workmode.detect.UltraEnvironmentPort
import dev.maplesu.manager.workmode.detect.UltraState
import dev.maplesu.manager.workmode.detect.UnavailableRootEvidenceReader
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 两段式判定（审计阻塞修复）：阶段一保守不激活 A；阶段二在 root 证据可用时激活一次。
 * 这组用例直接编码"Lite 设备可被激活"与"未判定不得默认 Lite"两条合同。
 */
class ModeBootstrapCoordinatorTest {

    @Before
    fun setUp() = ModeRuntime.resetForTest()

    @After
    fun tearDown() = ModeRuntime.resetForTest()

    private fun liteDetectorWith(
        handshake: Boolean?,
        credentialPresent: Boolean = true,
    ): Pair<DefaultModeDetector, CountingHandshake> {
        val counter = CountingHandshake(handshake)
        val detector = DefaultModeDetector(
            ksuProbe = KsuKernelProbe { KsuFacts() },
            liteProbe = LiteCredentialProbe { credentialPresent to null },
            liteHandshakeProbe = counter,
            ultraPort = UltraEnvironmentPort { UltraState.UNKNOWN },
            bootIdSource = BootIdSource { "boot-1" },
            rootEvidenceReader = UnavailableRootEvidenceReader,
        )
        return detector to counter
    }

    private fun liteDetector(handshake: Boolean?, credentialPresent: Boolean = true): DefaultModeDetector =
        liteDetectorWith(handshake, credentialPresent).first

    private fun productionCoordinator(
        detector: ModeDetector,
        isResolved: () -> Boolean = { ModeRuntime.active != null },
    ) = ModeBootstrapCoordinator(
        pipelineProvider = { ModeBootstrapPipeline(detector, ProductionModeRuntimePort) },
        isResolved = isResolved,
    )

    @Test
    fun phaseOneNeverAttemptsLiteHandshakeAndStaysUnresolved() = runBlocking {
        ModeRuntime.installBootIdProviderForTest { "boot-1" }
        // 即使握手"本可以"成功，阶段一按同意规则也不允许尝试（不起 root）。
        val (detector, handshakeProbe) = liteDetectorWith(handshake = true)
        val outcome = productionCoordinator(detector).runPhaseOne()
        assertTrue(outcome is BootstrapOutcome.Unresolved)
        val unresolved = outcome as BootstrapOutcome.Unresolved
        assertEquals("NO_ACTIVE_MODE", unresolved.reason)
        assertEquals(ModeState.MODE_A_INSTALLED_UNBOUND, unresolved.state)
        assertEquals(0, handshakeProbe.calls)
        assertNull(ModeRuntime.active)
    }

    @Test
    fun phaseTwoAttemptsHandshakeExactlyOnce() = runBlocking {
        ModeRuntime.installBootIdProviderForTest { "boot-1" }
        val (detector, handshakeProbe) = liteDetectorWith(handshake = true)
        val coordinator = productionCoordinator(detector)
        coordinator.runPhaseOne()
        assertEquals(0, handshakeProbe.calls)
        coordinator.runPhaseTwo()
        assertEquals(1, handshakeProbe.calls)
        assertEquals(WorkMode.LITE, ModeRuntime.active)
    }

    @Test
    fun productionPhaseTwoActivatesLite() = runBlocking {
        ModeRuntime.installBootIdProviderForTest { "boot-1" }
        val coordinator = productionCoordinator(liteDetector(handshake = true))
        assertTrue(coordinator.runPhaseOne() is BootstrapOutcome.Unresolved)
        assertNull(ModeRuntime.active)

        val outcome = coordinator.runPhaseTwo()
        assertTrue("outcome=$outcome", outcome is BootstrapOutcome.Activated)
        val activated = outcome as BootstrapOutcome.Activated
        assertEquals(WorkMode.LITE, activated.mode)
        assertEquals(ModeState.MODE_A_ACTIVE, activated.state)
        assertEquals("boot-1", activated.bootId)
        assertEquals(WorkMode.LITE, ModeRuntime.active)
        assertEquals(
            dev.maplesu.manager.workmode.ActivationSource.BOOT_DETECTION,
            (ModeRuntime.activation as ModeActivation.Active).source,
        )
        assertEquals(
            dev.maplesu.manager.workmode.ModeCapabilities.forMode(WorkMode.LITE),
            ModeRuntime.capabilities,
        )
    }

    @Test
    fun phaseTwoWithoutHandshakeStaysUnresolved() = runBlocking {
        ModeRuntime.installBootIdProviderForTest { "boot-1" }
        val outcome = productionCoordinator(liteDetector(handshake = false)).runPhaseTwo()
        assertTrue(outcome is BootstrapOutcome.Unresolved)
        assertEquals(ModeState.MODE_A_INSTALLED_UNBOUND, (outcome as BootstrapOutcome.Unresolved).state)
        // 失败的手握 ≠ 可以激活；绝不允许默认 Lite。
        assertNull(ModeRuntime.active)
    }

    @Test
    fun phaseTwoUnknownHandshakeNeverDefaultsToLite() = runBlocking {
        ModeRuntime.installBootIdProviderForTest { "boot-1" }
        val outcome = productionCoordinator(liteDetector(handshake = null)).runPhaseTwo()
        assertTrue(outcome is BootstrapOutcome.Unresolved)
        assertEquals(ModeState.MODE_A_INSTALLED_UNBOUND, (outcome as BootstrapOutcome.Unresolved).state)
        assertNull(ModeRuntime.active)
    }

    @Test
    fun phaseRequestsCarryConsentFlags() = runBlocking {
        val seen = mutableListOf<DetectionRequest>()
        val detector = object : ModeDetector {
            override suspend fun detect(request: DetectionRequest): DetectionResult {
                seen += request
                return TestFixtures.detection(ModeState.NOT_INSTALLED)
            }
        }
        val coordinator = ModeBootstrapCoordinator(
            pipelineProvider = { ModeBootstrapPipeline(detector, CountingRuntime()) },
        )
        coordinator.runPhaseOne()
        coordinator.runPhaseTwo()
        assertEquals(2, seen.size)
        assertEquals(DetectionOrigin.STARTUP, seen[0].origin)
        assertFalse(seen[0].allowRootProbe)
        assertFalse(seen[0].allowLiteExecutor)
        assertEquals(DetectionOrigin.AFTER_ROOT_GRANTED, seen[1].origin)
        assertTrue(seen[1].allowRootProbe)
        assertTrue(seen[1].allowLiteExecutor)
    }

    @Test
    fun phaseTwoRunsOnlyOnce() = runBlocking {
        val detector = CountingDetector(TestFixtures.detection(ModeState.MODE_A_ACTIVE))
        val runtime = CountingRuntime()
        val coordinator = ModeBootstrapCoordinator(
            pipelineProvider = { ModeBootstrapPipeline(detector, runtime) },
            isResolved = { false },
        )
        assertTrue(coordinator.runPhaseTwo() is BootstrapOutcome.Activated)
        assertNull(coordinator.runPhaseTwo())
        assertEquals(1, detector.calls)
        assertEquals(1, runtime.activations)
    }

    @Test
    fun phaseTwoRetriesAnUnresolvedHandshakeAndActivatesOnlyAfterLiveSuccess() = runBlocking {
        ModeRuntime.installBootIdProviderForTest { "boot-1" }
        var handshakeCalls = 0
        val detector = DefaultModeDetector(
            ksuProbe = KsuKernelProbe { KsuFacts() },
            liteProbe = LiteCredentialProbe { true to null },
            liteHandshakeProbe = LiteHandshakeProbe { ++handshakeCalls > 1 },
            ultraPort = UltraEnvironmentPort { UltraState.UNKNOWN },
            bootIdSource = BootIdSource { "boot-1" },
            rootEvidenceReader = UnavailableRootEvidenceReader,
        )
        val coordinator = productionCoordinator(detector)
        assertTrue(coordinator.runPhaseTwo() is BootstrapOutcome.Unresolved)
        assertNull(ModeRuntime.active)
        assertTrue(coordinator.runPhaseTwo() is BootstrapOutcome.Activated)
        assertEquals(WorkMode.LITE, ModeRuntime.active)
        assertEquals(2, handshakeCalls)
        assertNull(coordinator.runPhaseTwo())
        assertEquals(2, handshakeCalls)
    }

    @Test
    fun phaseTwoRetriesAfterADetectorException() = runBlocking {
        var calls = 0
        val runtime = CountingRuntime()
        val detector = object : ModeDetector {
            override suspend fun detect(request: DetectionRequest): DetectionResult {
                if (++calls == 1) error("temporary probe failure")
                return TestFixtures.detection(ModeState.MODE_A_ACTIVE)
            }
        }
        val coordinator = ModeBootstrapCoordinator(
            pipelineProvider = { ModeBootstrapPipeline(detector, runtime) },
        )
        assertTrue(coordinator.runPhaseTwo() is BootstrapOutcome.Failed)
        assertTrue(coordinator.runPhaseTwo() is BootstrapOutcome.Activated)
        assertEquals(1, runtime.activations)
    }

    @Test
    fun phaseTwoKeepsConcurrentAttemptsOutOfTheLiveDetector() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val detector = object : ModeDetector {
            override suspend fun detect(request: DetectionRequest): DetectionResult {
                calls++
                entered.complete(Unit)
                release.await()
                return TestFixtures.detection(ModeState.MODE_A_ACTIVE)
            }
        }
        val coordinator = ModeBootstrapCoordinator(
            pipelineProvider = { ModeBootstrapPipeline(detector, CountingRuntime()) },
        )
        val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.runPhaseTwo() }
        entered.await()
        assertNull(coordinator.runPhaseTwo())
        assertEquals(1, calls)
        release.complete(Unit)
        assertTrue(first.await() is BootstrapOutcome.Activated)
    }

    @Test
    fun phaseOneRunsOnlyOnce() = runBlocking {
        val detector = CountingDetector(TestFixtures.detection(ModeState.NOT_INSTALLED))
        val coordinator = ModeBootstrapCoordinator(
            pipelineProvider = { ModeBootstrapPipeline(detector, CountingRuntime()) },
        )
        assertTrue(coordinator.runPhaseOne() is BootstrapOutcome.Unresolved)
        assertNull(coordinator.runPhaseOne())
        assertEquals(1, detector.calls)
    }

    @Test
    fun phaseTwoSkippedWhenAlreadyResolved() = runBlocking {
        val detector = CountingDetector(TestFixtures.detection(ModeState.MODE_A_ACTIVE))
        val coordinator = ModeBootstrapCoordinator(
            pipelineProvider = { ModeBootstrapPipeline(detector, CountingRuntime()) },
            isResolved = { true },
        )
        assertNull(coordinator.runPhaseTwo())
        assertEquals(0, detector.calls)
    }

    @Test
    fun phaseTwoDetectorFailureStaysUnresolvedWithoutActivation() = runBlocking {
        val runtime = CountingRuntime()
        val detector = object : ModeDetector {
            override suspend fun detect(request: DetectionRequest): DetectionResult = error("probe died")
        }
        val outcome = ModeBootstrapCoordinator(
            pipelineProvider = { ModeBootstrapPipeline(detector, runtime) },
        ).runPhaseTwo()
        assertTrue(outcome is BootstrapOutcome.Failed)
        assertEquals(0, runtime.activations)
    }

    private class CountingDetector(private val result: DetectionResult) : ModeDetector {
        var calls = 0
        override suspend fun detect(request: DetectionRequest): DetectionResult {
            calls++
            return result
        }
    }

    private class CountingRuntime : ModeRuntimePort {
        var records = 0
        var completions = 0
        var activations = 0
        override fun recordDetection(result: DetectionResult) { records++ }
        override fun completeBootstrap(result: DetectionResult) { completions++ }
        override fun activate(
            mode: WorkMode,
            bootId: String,
            source: dev.maplesu.manager.workmode.ActivationSource,
        ): Boolean {
            activations++
            return true
        }
    }

    /** 记录握手尝试次数：验证"阶段一 0 次 / 阶段二 1 次"。 */
    private class CountingHandshake(private val result: Boolean?) : LiteHandshakeProbe {
        var calls = 0
        override fun handshake(): Boolean? {
            calls++
            return result
        }
    }
}
