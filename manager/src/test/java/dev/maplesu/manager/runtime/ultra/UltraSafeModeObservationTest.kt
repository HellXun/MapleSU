package dev.maplesu.manager.runtime.ultra

import com.maplesu.core.KernelVersion
import com.maplesu.core.ui.screen.home.HomeHiddenFacts
import com.maplesu.core.ui.screen.home.HomeUiState
import com.maplesu.core.ui.screen.home.SystemInfo
import com.maplesu.core.ui.screen.home.withHiddenFacts
import com.maplesu.core.ui.screen.home.withUltraSafety
import com.maplesu.core.ui.screen.module.ModuleUiState
import com.maplesu.core.ui.screen.module.withUltraSafety
import com.maplesu.core.ui.util.module.LatestVersionInfo
import dev.maplesu.manager.workmode.EnvironmentProbeResult
import dev.maplesu.manager.workmode.EnvironmentProbeRunner
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.SuReadiness
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class UltraSafeModeObservationTest {
    private val probe = UltraEnvironmentProbe
    private val before = probe.currentSnapshot()

    @After fun restoreSnapshot() { probe.publishSnapshot(before) }

    private fun publish(state: UltraEnvironmentProbe.State, tick: Long = 1) {
        probe.publishSnapshot(UltraEnvironmentProbe.Snapshot(state, tick, tick, "fixture"))
    }

    private fun safe() = probe.current() == UltraEnvironmentProbe.State.Fault

    private fun home() = HomeUiState(
        kernelVersion = KernelVersion(5, 15, 119), ksuVersion = 1,
        managerUAPIVersion = 1, kernelUAPIVersion = null, lkmMode = null,
        isLkmBundled = false, isManager = true, isManagerPrBuild = false,
        isKernelPrBuild = false, requiresNewKernel = false, requiresNewManager = false,
        isRootAvailable = true, rootReadiness = SuReadiness.USABLE,
        isSafeMode = false, isLateLoadMode = false, checkUpdateEnabled = false,
        latestVersionInfo = LatestVersionInfo(), currentManagerVersionCode = 50102,
        systemInfo = SystemInfo("5.15.119", "5.1.2", "MEIZU 20", "fixture", "Enforcing", 0),
        environmentMode = WorkMode.LITE, liteInstalled = true,
    )

    @Test fun sdkRecoveryAfterAFullPageTimeoutReleasesBothRetainedPages() = runBlocking {
        publish(UltraEnvironmentProbe.State.Fault)
        val home = MutableStateFlow(home())
        val modules = MutableStateFlow(ModuleUiState(isUltra = true))
        val homeJob = launch(start = CoroutineStart.UNDISPATCHED) {
            probe.states.collect { home.update { it.withUltraSafety(safe(), WorkMode.LITE) } }
        }
        val moduleJob = launch(start = CoroutineStart.UNDISPATCHED) {
            probe.states.collect { modules.update { it.withUltraSafety(safe(), WorkMode.LITE) } }
        }
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        try {
            assertTrue(home.value.showSafeModeBanner)
            assertTrue(modules.value.recoveryOnly)
            val result = EnvironmentProbeRunner("safe-mode-timeout-test").run(20) {
                try {
                    // A native call may finish after Future.cancel(true), as on the device.
                    while (true) {
                        try { release.await(); break } catch (_: InterruptedException) { }
                    }
                    publish(UltraEnvironmentProbe.State.Running)
                } finally { finished.countDown() }
            }
            assertEquals(EnvironmentProbeResult.TimedOut, result)
            release.countDown()
            withTimeout(2_000) {
                home.first { !it.isSafeMode }
                modules.first { !it.isSafeMode }
            }
            assertFalse(home.value.showSafeModeBanner)
            assertFalse(modules.value.recoveryOnly)
            assertTrue(modules.value.installButtonVisible)
            assertFalse("Running is not a successful Root command", home.value.isRootAvailable)
            assertNull(home.value.rootReadiness)
            publish(UltraEnvironmentProbe.State.Fault, 2)
            withTimeout(2_000) {
                home.first { it.isSafeMode }
                modules.first { it.isSafeMode }
            }
            assertTrue(home.value.showSafeModeBanner)
            assertTrue(modules.value.recoveryOnly)
            assertFalse(modules.value.installButtonVisible)
        } finally {
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            homeJob.cancel()
            moduleJob.cancel()
        }
    }

    @Test fun aNewObserverImmediatelyReceivesTheLatestSdkState() = runBlocking {
        publish(UltraEnvironmentProbe.State.Fault)
        assertEquals(UltraEnvironmentProbe.State.Fault, probe.states.first())
        publish(UltraEnvironmentProbe.State.Running)
        assertEquals(UltraEnvironmentProbe.State.Running, probe.states.first())
    }

    @Test fun lateWholePageResultsUseCurrentSafetyInBothDirections() {
        val staleFault = home().withUltraSafety(true, WorkMode.LITE)
        publish(UltraEnvironmentProbe.State.Running)
        assertFalse(staleFault.withUltraSafety(safe(), WorkMode.LITE).showSafeModeBanner)
        assertFalse(ModuleUiState(isUltra = true, isSafeMode = true)
            .withUltraSafety(safe(), WorkMode.LITE).recoveryOnly)

        publish(UltraEnvironmentProbe.State.Fault)
        val lateRunning = home().withUltraSafety(safe(), WorkMode.LITE)
        assertTrue(lateRunning.showSafeModeBanner)
        assertFalse(lateRunning.isRootAvailable)
        assertNull(lateRunning.rootReadiness)
        assertTrue(ModuleUiState(isUltra = true).withUltraSafety(safe(), WorkMode.LITE).recoveryOnly)
    }

    @Test fun metadataAndAdmissionFailuresDoNotCreateStateTransitions() = runBlocking {
        publish(UltraEnvironmentProbe.State.Fault)
        val observed = mutableListOf<UltraEnvironmentProbe.State>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { probe.states.collect { observed += it } }
        try {
            for (error in listOf("MAPLESU_SDK_BUSY", "MAPLESU_SDK_INTERRUPTED")) {
                publish(probe.stateAfterAdmission(probe.current(), UltraEnvironmentProbe.State.Unknown, error, true), 2)
                yield()
            }
            probe.invalidateAvailability()
            yield()
            assertEquals(listOf(UltraEnvironmentProbe.State.Fault), observed)
            publish(UltraEnvironmentProbe.State.Running, 3)
            yield()
            publish(UltraEnvironmentProbe.State.Running, 4)
            yield()
            assertEquals(listOf(UltraEnvironmentProbe.State.Fault, UltraEnvironmentProbe.State.Running), observed)
        } finally { job.cancel() }
    }

    @Test fun ultraChangesNeverOverwriteIndependentKsuSafety() {
        for (safeMode in listOf(false, true)) {
            val ksuHome = home().copy(environmentMode = WorkMode.LITE, isSafeMode = safeMode)
            val ksuModules = ModuleUiState(isSafeMode = safeMode)
            assertEquals(ksuHome, ksuHome.withUltraSafety(!safeMode, WorkMode.LITE))
            assertEquals(ksuModules, ksuModules.withUltraSafety(!safeMode, WorkMode.LITE))
        }
    }

    @Test fun hiddenAndConfirmingScreensContinueToSuppressSafetyAndManagerFacts() {
        for (hidden in listOf(HomeHiddenFacts(true, false), HomeHiddenFacts(false, true))) {
            val state = home().withUltraSafety(true, WorkMode.LITE).withHiddenFacts(hidden)
            assertFalse(state.showSafeModeBanner)
            assertFalse(state.showInstalledStatus)
            assertFalse(state.isManager)
            assertFalse(state.isRootAvailable)
        }
    }

    @Test fun onlyFaultRequiresSafeModeAndRecoveryDoesNotGrantRoot() {
        val protected = home().withUltraSafety(true, WorkMode.LITE)
        for (state in UltraEnvironmentProbe.State.entries) {
            publish(state)
            val next = protected.withUltraSafety(safe(), WorkMode.LITE)
            assertEquals(state == UltraEnvironmentProbe.State.Fault, next.isSafeMode)
            assertFalse(next.isRootAvailable)
            assertNull(next.rootReadiness)
        }
    }
}
