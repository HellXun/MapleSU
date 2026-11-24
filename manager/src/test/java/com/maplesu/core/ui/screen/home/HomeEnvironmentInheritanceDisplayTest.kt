package com.maplesu.core.ui.screen.home

import com.maplesu.core.KernelVersion
import com.maplesu.core.ui.util.module.LatestVersionInfo
import com.maplesu.core.ui.component.maple.homeUltraBrandFacts
import com.maplesu.core.ui.component.maple.shouldShowUltraBrand
import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.*
import org.junit.Test

class HomeEnvironmentInheritanceDisplayTest {
    private fun state(mode: WorkMode? = null) = HomeUiState(
        kernelVersion = KernelVersion(6, 6, 89), ksuVersion = null,
        managerUAPIVersion = 1, kernelUAPIVersion = null, lkmMode = null,
        isLkmBundled = false, isManager = false, isManagerPrBuild = false,
        isKernelPrBuild = false, requiresNewKernel = false, requiresNewManager = false,
        isRootAvailable = false, isSafeMode = false,
        checkUpdateEnabled = false, latestVersionInfo = LatestVersionInfo(),
        currentManagerVersionCode = 50003,
        systemInfo = SystemInfo("6.6.89", "5.0.3", "device", "fixture", "Unknown", 0),
        environmentMode = mode, environmentChecking = true,
    )

    @Test fun ultraSafeModeKeepsTheModuleListButHidesInstallation() {
        val modules = com.maplesu.core.ui.screen.module.ModuleUiState(isUltra = true, isSafeMode = true)
        assertTrue(modules.recoveryOnly)
        assertFalse(modules.installButtonVisible)
        assertFalse(modules.copy(isSafeMode = false).recoveryOnly)
        assertFalse(modules.copy(isUltra = false).recoveryOnly)
    }

    @Test fun installedModesRemainVisibleDuringSilentChecksWithoutGrantingRoot() {
        for (mode in WorkMode.entries) {
            val visible = state(mode).withHiddenFacts(HomeHiddenFacts(false, false))
            assertTrue(visible.showInstalledStatus)
            assertFalse(visible.showEnvironmentStatusCard)
            assertFalse(visible.isManager)
            assertFalse(visible.isRootAvailable)
            assertNull(visible.ksuVersion)
        }
    }

    @Test fun missingSuKeepsTheCachedModeWhileTheEnvironmentIsCheckedSilently() {
        for (mode in WorkMode.entries) {
            val checking = state(mode).checkingAfterSuMissing()
            assertEquals(mode, checking.environmentMode)
            assertTrue(checking.showInstalledStatus)
            assertFalse(checking.showEnvironmentStatusCard)
            assertFalse(checking.isRootAvailable)
        }
    }

    @Test fun missingSuWithoutACachedModeStillStartsChecking() {
        val checking = state().checkingAfterSuMissing()
        assertNull(checking.environmentMode)
        assertFalse(checking.showInstalledStatus)
        assertTrue(checking.showEnvironmentStatusCard)
    }

    @Test fun knownHiddenAlwaysBeatsConfirmationAndSlowVisibleResults() {
        for (mode in WorkMode.entries) {
            val hidden = state(mode).withHiddenFacts(HomeHiddenFacts(true, true))
            assertFalse(hidden.showInstalledStatus)
            assertFalse(hidden.showEnvironmentStatusCard)
            assertFalse(hidden.hiddenConfirming)
            assertNull(hidden.environmentMode)
        }
    }

    @Test fun firstUnknownHiddenStateDoesNotExposeTheModeOrManager() {
        val checking = state(WorkMode.LITE).copy(isManager = true, ksuVersion = 123)
            .withHiddenFacts(HomeHiddenFacts(false, true))
        assertFalse(checking.showInstalledStatus)
        assertFalse(checking.isManager)
        assertNull(checking.environmentMode)
        assertTrue(checking.showEnvironmentStatusCard)
    }

    @Test fun completedFailedHiddenReadKeepsEntriesClosedWithoutAnEndlessSpinner() {
        val settled = state(WorkMode.LITE).copy(environmentChecking = false)
            .withHiddenFacts(HomeHiddenFacts(false, true))
        assertFalse(settled.showInstalledStatus)
        assertFalse(settled.showEnvironmentStatusCard)
        assertTrue(settled.hiddenConfirming)
    }

    @Test fun confirmedOffReleasesTheModeOnlyAfterTheHiddenFactsArrive() {
        val ready = state(WorkMode.LITE).copy(hiddenConfirming = true)
            .withHiddenFacts(HomeHiddenFacts(false, false))
        assertFalse(ready.hiddenConfirming)
        assertTrue(ready.showInstalledStatus)
        assertFalse(ready.showEnvironmentStatusCard)
    }

    @Test fun completedUnresolvedProbeNeverRetainsStartupPending() {
        val settled = state().copy(litePending = true).afterEnvironmentCheck(
            modeResolved = false, detectionComplete = false, detectionFailure = null, detectedState = null,
        )
        assertFalse(settled.litePending)
        assertTrue(settled.showEnvironmentStatusCard)
        assertEquals(HomeEnvironmentFailure.UNCONFIRMED, settled.environmentFailure)
    }

    @Test fun cachedBrandWaitsForHiddenConfirmationNotRuntimeActivation() {
        for (mode in WorkMode.entries) {
            for (hidden in listOf(false, true)) {
                for (confirming in listOf(false, true)) {
                    val facts = homeUltraBrandFacts(HomeWorkModeSwitchState.HIDDEN, hidden, confirming, mode)
                    assertEquals(mode == WorkMode.LITE && !hidden && !confirming, shouldShowUltraBrand(facts))
                }
            }
        }
    }
}
