package com.maplesu.core.ui.screen.home

import com.maplesu.core.KernelVersion
import com.maplesu.core.ui.component.maple.homeUltraBrandFacts
import com.maplesu.core.ui.component.maple.shouldShowUltraBrand
import com.maplesu.core.ui.util.module.LatestVersionInfo
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.ModeState
import dev.maplesu.manager.workmode.detect.SuReadiness
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Both themes must render the current top-bar result, independently of a refresh or root capability. */
class HomeUltraStatusRefreshTest {
    @Test fun verifiedUltraIdentityDoesNotAdvertiseExternalRootMigrationWhenStateIsUnknown() {
        val current = state(root = true).copy(environmentMode = WorkMode.LITE)
        assertFalse(current.isManager)
        assertFalse(current.showMigrationEntry)
        assertTrue(current.isRootAvailable)
    }

    @Test fun recognizedKernelIdentityDoesNotAdvertiseExternalRootMigration() {
        assertFalse(state(root = true).copy(environmentMode = WorkMode.LITE).showMigrationEntry)
    }

    @Test fun externalRootStillOffersMigration() {
        assertTrue(state(root = true).showMigrationEntry)
        assertFalse(state(root = false).showMigrationEntry)
        assertFalse(state(root = true).copy(isSafeMode = true).showMigrationEntry)
        assertFalse(state(root = true).copy(isManager = true).showMigrationEntry)
    }

    private fun state(root: Boolean = false) = HomeUiState(
        kernelVersion = KernelVersion(6, 1, 31),
        ksuVersion = null,
        managerUAPIVersion = 1,
        kernelUAPIVersion = null,
        lkmMode = null,
        isLkmBundled = false,
        isManager = false,
        isManagerPrBuild = false,
        isKernelPrBuild = false,
        requiresNewKernel = false,
        requiresNewManager = false,
        isRootAvailable = root,
        isSafeMode = false,
        isLateLoadMode = false,
        checkUpdateEnabled = false,
        latestVersionInfo = LatestVersionInfo(),
        currentManagerVersionCode = 1,
        systemInfo = SystemInfo("6.1.31", "1 (1)", "device", "fingerprint", "Enforcing", 0),
    )

    private fun project(
        state: HomeUiState,
        mode: WorkMode? = WorkMode.LITE,
        installed: HomeInstallSignal = HomeInstallSignal.INSTALLED,
        masking: Boolean = false,
        switchPending: Boolean = false,
    ): HomeUiState {
        val switch = homeWorkModeSwitchState(HomeWorkModeSwitchFacts(
            active = mode,
            installed = installed,
            liteHidden = HomeHiddenSignal.of(masking),
            switchPending = switchPending,
        ))
        val visible = shouldShowUltraBrand(homeUltraBrandFacts(switch, masking, state.hiddenConfirming))
        return state.copy(ultraBrandVisible = visible)
    }

    @Test fun verifiedUltraSurvivesRefreshChecking() {
        val initial = project(state(root = true))
        assertTrue(initial.ultraBrandVisible)
        assertTrue(initial.showInstalledStatus)
        val refreshing = project(initial.copy(environmentChecking = true, litePending = true))
        assertFalse("A refresh must not replace the Ultra card with a spinner", refreshing.showLiteChecking)
        assertFalse(refreshing.showEnvironmentStatusCard)
        assertTrue(refreshing.showInstalledStatus)
        assertTrue(refreshing.isRootAvailable)
    }

    @Test fun verifiedUltraSurvivesEveryRefreshFailureAndRetainsTheDiagnostic() {
        for (failure in HomeEnvironmentFailure.entries) {
            val failed = project(state(root = true).copy(environmentFailure = failure))
            assertFalse("$failure must not replace the verified Ultra card", failed.showEnvironmentStatusCard)
            assertTrue(failed.showInstalledStatus)
            assertTrue("$failure must remain visible as a refresh warning", failed.showEnvironmentRefreshWarning)
            assertEquals(failure, failed.environmentFailure)
        }
    }

    @Test fun successfulRetryClearsOnlyTheRefreshWarning() {
        val failed = project(state().copy(environmentFailure = HomeEnvironmentFailure.TIMED_OUT))
        assertTrue(failed.showEnvironmentRefreshWarning)
        val completed = project(failed.copy(environmentFailure = null))
        assertFalse(completed.showEnvironmentRefreshWarning)
        assertTrue(completed.showInstalledStatus)
    }

    @Test fun installedUltraNeverInventsRootOrKernelVersion() {
        val installed = project(state().copy(liteInstalled = true, liteRootFailure = true,
            rootReadiness = SuReadiness.NOT_USABLE))
        assertTrue(installed.showInstalledStatus)
        assertFalse(installed.isRootAvailable)
        assertNull(installed.ksuVersion)
        assertTrue(installed.showLiteRootFailure)
    }

    @Test fun anInstalledHintWithoutTheTopBarResultStillShowsTheCheck() {
        val initial = project(state().copy(liteInstalled = true, environmentChecking = true),
            installed = HomeInstallSignal.UNKNOWN)
        assertFalse(initial.ultraBrandVisible)
        assertTrue(initial.showEnvironmentStatusCard)
        assertFalse(initial.showInstalledStatus)
    }

    @Test fun unverifiedOrUninstalledEnvironmentStillShowsTheRealFailure() {
        for (signal in listOf(HomeInstallSignal.UNKNOWN, HomeInstallSignal.NOT_INSTALLED)) {
            for (failure in HomeEnvironmentFailure.entries) {
                val failed = project(state().copy(environmentFailure = failure), installed = signal)
                assertTrue(failed.showEnvironmentStatusCard)
                assertFalse(failed.showInstalledStatus)
                assertFalse(failed.showEnvironmentRefreshWarning)
                assertFalse(failed.isRootAvailable)
            }
        }
    }

    @Test fun newBootOrModeChangeDoesNotReuseThePreviousUltraResult() {
        val failed = project(state().copy(environmentFailure = HomeEnvironmentFailure.FAILED))
        assertTrue(failed.showInstalledStatus)
        for (mode in listOf(null, WorkMode.LITE)) {
            val changed = project(failed, mode = mode)
            assertFalse(changed.ultraBrandVisible)
            assertFalse(changed.showInstalledStatus)
            assertTrue(changed.showEnvironmentStatusCard)
            assertFalse(changed.showEnvironmentRefreshWarning)
        }
    }

    @Test fun hiddenConfirmationAndNormalizationRemainHigherPriority() {
        val installed = project(state())
        val confirming = project(installed.copy(hiddenConfirming = true, environmentChecking = true))
        assertFalse(confirming.ultraBrandVisible)
        assertTrue(confirming.showEnvironmentStatusCard)
        for (hidden in listOf(installed.asLiteHiddenNotInstalled(), installed.asStealthKernelNotReady())) {
            assertFalse(hidden.ultraBrandVisible)
            assertFalse(hidden.showInstalledStatus)
            assertFalse(hidden.showEnvironmentRefreshWarning)
        }
        assertFalse(project(installed, masking = true).ultraBrandVisible)
    }

    @Test fun pendingSwitchKeepsTheSameTopBarResultWithoutClaimingRoot() {
        val pending = project(state().copy(environmentChecking = true), switchPending = true)
        assertTrue(pending.ultraBrandVisible)
        assertTrue(pending.showInstalledStatus)
        assertFalse(pending.showEnvironmentStatusCard)
        assertFalse(pending.isRootAvailable)
    }

    @Test fun normalKsuKeepsItsOriginalInstalledBranch() {
        val normal = project(state(root = true).copy(ksuVersion = 123), mode = WorkMode.LITE)
        assertFalse(normal.ultraBrandVisible)
        assertTrue(normal.showInstalledStatus)
        assertFalse(normal.showEnvironmentStatusCard)
        assertTrue(normal.isRootAvailable)
    }

    @Test fun indeterminateAfterReinstallKeepsIdentityUnconfirmed() {
        val completed = state().copy(environmentChecking = true).afterEnvironmentCheck(
            modeResolved = false,
            detectionComplete = true,
            detectionFailure = null,
            detectedState = ModeState.INDETERMINATE,
        )
        val visible = project(completed, mode = null, installed = HomeInstallSignal.UNKNOWN)
        assertEquals(HomeEnvironmentFailure.UNCONFIRMED, visible.environmentFailure)
        assertTrue(visible.showEnvironmentStatusCard)
        assertFalse(visible.environmentChecking)
        assertFalse(visible.showLiteChecking)
        assertFalse(visible.showInstalledStatus)
        assertFalse(visible.liteInstalled)
        assertFalse(visible.isRootAvailable)
    }

    @Test fun confirmedNotInstalledStillUsesTheUninstalledCard() {
        val completed = state().copy(environmentChecking = true).afterEnvironmentCheck(
            modeResolved = false,
            detectionComplete = true,
            detectionFailure = null,
            detectedState = ModeState.NOT_INSTALLED,
        )
        val visible = project(completed, mode = null, installed = HomeInstallSignal.NOT_INSTALLED)
        assertNull(visible.environmentFailure)
        assertFalse(visible.showEnvironmentStatusCard)
        assertFalse(visible.showInstalledStatus)
        assertTrue(visible.kernelVersion.isGKI())
        assertFalse(visible.environmentChecking)
        assertFalse(visible.liteInstalled)
        assertFalse(visible.isRootAvailable)
    }

    @Test fun completedRunningUltraKeepsItsInstalledCardAndRealRootResult() {
        val completed = state(root = true).copy(liteInstalled = true, environmentChecking = true)
            .afterEnvironmentCheck(
                modeResolved = true,
                detectionComplete = true,
                detectionFailure = null,
                detectedState = ModeState.MODE_A_ACTIVE,
            )
        val visible = project(completed)
        assertNull(visible.environmentFailure)
        assertFalse(visible.showEnvironmentStatusCard)
        assertFalse(visible.environmentChecking)
        assertTrue(visible.ultraBrandVisible)
        assertTrue(visible.showInstalledStatus)
        assertTrue(visible.liteInstalled)
        assertTrue(visible.isRootAvailable)
    }

    @Test fun conclusiveRetryClearsAnEarlierUnconfirmedResult() {
        val unconfirmed = state().afterEnvironmentCheck(
            modeResolved = false,
            detectionComplete = true,
            detectionFailure = null,
            detectedState = ModeState.INDETERMINATE,
        )
        assertEquals(HomeEnvironmentFailure.UNCONFIRMED, unconfirmed.environmentFailure)
        val completed = unconfirmed.afterEnvironmentCheck(
            modeResolved = false,
            detectionComplete = true,
            detectionFailure = null,
            detectedState = ModeState.NOT_INSTALLED,
        )
        assertNull(completed.environmentFailure)
        assertFalse(completed.showEnvironmentStatusCard)
        assertFalse(completed.showInstalledStatus)
        assertFalse(completed.isRootAvailable)
    }

    @Test fun incompleteOrFailedDetectionRetainsPriorityOverIndeterminateState() {
        for ((complete, reason, expected) in listOf(
            Triple(false, null, HomeEnvironmentFailure.UNCONFIRMED),
            Triple(true, "MODE_DETECT_TIMEOUT", HomeEnvironmentFailure.TIMED_OUT),
            Triple(true, "MODE_DETECT_FAILED", HomeEnvironmentFailure.FAILED),
        )) {
            val completed = state().afterEnvironmentCheck(
                modeResolved = false,
                detectionComplete = complete,
                detectionFailure = reason,
                detectedState = ModeState.INDETERMINATE,
            )
            assertEquals(expected, completed.environmentFailure)
            assertTrue(completed.showEnvironmentStatusCard)
            assertFalse(completed.environmentChecking)
            assertFalse(completed.showLiteChecking)
            assertFalse(completed.showInstalledStatus)
            assertFalse(completed.isRootAvailable)
        }
    }

    @Test fun bothThemesConsumeTheSameProjectionWithoutChangingTheTopBarDetector() {
        val screen = source("screen/home/HomeScreen.kt")
        assertTrue(screen.contains("val ultraVisible = shouldShowUltraBrand(brandFacts)"))
        assertTrue(screen.contains("displayState.copy(ultraBrandVisible = ultraVisible)"))
        assertEquals(2, "state = statusState".toRegex().findAll(screen).count())
        val viewModel = source("viewmodel/HomeViewModel.kt")
        assertTrue(viewModel.contains("checked.value.state.afterEnvironmentCheck("))
        assertTrue(viewModel.contains("detectedState = ModeRuntime.latestDetection?.state"))
        for (theme in listOf("HomeMaterial.kt", "HomeMiuix.kt")) {
            val source = source("screen/home/$theme")
            assertTrue(source.contains("state.showEnvironmentStatusCard"))
            assertTrue(source.contains("state.showInstalledStatus"))
            assertTrue("$theme must display the retained refresh diagnostic", source.contains("state.showEnvironmentRefreshWarning"))
        }
    }

    @Test fun viewModelNeverOverridesARealRootProbeWithAnInstalledFlag() {
        val source = source("viewmodel/HomeViewModel.kt")
        assertTrue(source.contains("val isRootAvailable = probeRoot && !confirmedNotInstalled && liveReadiness == SuReadiness.USABLE"))
        assertFalse(source.contains("ultraKernelVerified -> true"))
        assertFalse("Installation and Pro identity do not prove root capability",
            source.contains("isRootAvailable = true"))
    }

    private fun source(path: String): String {
        val relative = "src/main/java/com/maplesu/core/ui/$path"
        return listOf(File(relative), File("manager/$relative")).first { it.isFile }.readText()
    }
}
