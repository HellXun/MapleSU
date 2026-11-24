package com.maplesu.core.ui.screen.home

import com.maplesu.core.KernelVersion
import com.maplesu.core.ui.util.module.LatestVersionInfo
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.SuReadiness
import org.junit.Assert.*
import org.junit.Test

class HomeActivationRefreshTest {
    private fun state(mode: WorkMode? = null) = HomeUiState(
        kernelVersion = KernelVersion(6, 12, 58), ksuVersion = null,
        managerUAPIVersion = 0, kernelUAPIVersion = null, lkmMode = null,
        isLkmBundled = false, isManager = false, isManagerPrBuild = false,
        isKernelPrBuild = false, requiresNewKernel = false, requiresNewManager = false,
        isRootAvailable = false, isSafeMode = false,
        checkUpdateEnabled = false, latestVersionInfo = LatestVersionInfo(),
        currentManagerVersionCode = 50108,
        systemInfo = SystemInfo("6.12.58", "5.1.8", "fixture", "fixture", "Unknown", 0),
        environmentMode = mode, liteInstalled = mode == WorkMode.LITE,
        environmentChecking = true,
    )

    @Test fun activationSurvivesAnInterruptedFirstRootRead() {
        for (mode in WorkMode.entries) {
            val refreshed = state().afterEnvironmentProbeFailure(state(mode))
            assertEquals(mode, refreshed.environmentMode)
            assertTrue(refreshed.showInstalledStatus)
            assertFalse(refreshed.environmentChecking)
            assertFalse(refreshed.isRootAvailable)
            assertFalse(refreshed.isManager)
            assertNull(refreshed.rootReadiness)
        }
    }

    @Test fun anUnresolvedOrMissingEnvironmentDoesNotBecomeActivated() {
        val refreshed = state().afterEnvironmentProbeFailure(state())
        assertNull(refreshed.environmentMode)
        assertFalse(refreshed.showInstalledStatus)
        assertFalse(refreshed.isRootAvailable)
        assertEquals(HomeEnvironmentFailure.FAILED, refreshed.environmentFailure)
        assertTrue(refreshed.showEnvironmentStatusCard)
    }

    @Test fun aFailedObservationRetainsInstallationWithoutReusingAnOldRootVerdict() {
        val previous = state(WorkMode.LITE).copy(isRootAvailable = true,
            rootReadiness = SuReadiness.USABLE)
        val refreshed = previous.afterEnvironmentProbeFailure(state(WorkMode.LITE))
        assertTrue(refreshed.showInstalledStatus)
        assertFalse(refreshed.isRootAvailable)
        assertNull(refreshed.rootReadiness)
        assertEquals(HomeEnvironmentFailure.FAILED, refreshed.environmentFailure)
        for (mode in listOf(null, WorkMode.LITE)) {
            val changed = previous.afterEnvironmentProbeFailure(state(mode))
            assertFalse(changed.isRootAvailable)
            assertNull(changed.rootReadiness)
        }
    }

    @Test fun currentHiddenFactsKeepPriorityAfterActivation() {
        val local = state(WorkMode.LITE).copy(hiddenConfirming = true)
        val refreshed = state().afterEnvironmentProbeFailure(local)
        assertTrue(refreshed.hiddenConfirming)
        assertFalse(refreshed.showInstalledStatus)
        assertFalse(refreshed.withHiddenFacts(HomeHiddenFacts(true, false)).showInstalledStatus)
    }
}
