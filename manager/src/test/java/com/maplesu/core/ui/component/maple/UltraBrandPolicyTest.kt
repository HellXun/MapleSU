package com.maplesu.core.ui.component.maple

import com.maplesu.core.ui.screen.home.HomeHiddenSignal
import com.maplesu.core.ui.screen.home.HomeInstallSignal
import com.maplesu.core.ui.screen.home.HomeWorkModeSwitchFacts
import com.maplesu.core.ui.screen.home.homeWorkModeSwitchState
import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure rendering-policy tests; no JNI, runtime probes, prefs, or Compose renderer. */
class UltraBrandPolicyTest {
    private val installedUltra = UltraBrandFacts(WorkMode.LITE, HomeInstallSignal.INSTALLED)

    @Test
    fun aboutDialogHidesModesInEveryMaskedState() {
        assertFalse(hidesModeInfo(installedUltra))
        for (signal in listOf(HomeHiddenSignal.ON, HomeHiddenSignal.UNKNOWN)) {
            assertTrue(hidesModeInfo(installedUltra.copy(liteHidden = signal)))
            assertTrue(hidesModeInfo(installedUltra.copy(ksuStealth = signal)))
        }
        assertTrue(hidesModeInfo(installedUltra.copy(hiddenConfirming = true)))
    }

    @Test
    fun installedUltraShowsBrand() {
        assertTrue(shouldShowUltraBrand(installedUltra))
    }

    @Test
    fun hiddenAndUnknownHiddenOnlyShowMapleSU() {
        for (hidden in listOf(HomeHiddenSignal.ON, HomeHiddenSignal.UNKNOWN)) {
            assertFalse(shouldShowUltraBrand(installedUltra.copy(liteHidden = hidden)))
        }
    }

    @Test
    fun stealthAndUnreadableStealthOnlyShowMapleSU() {
        for (stealth in listOf(HomeHiddenSignal.ON, HomeHiddenSignal.UNKNOWN)) {
            assertFalse(shouldShowUltraBrand(installedUltra.copy(ksuStealth = stealth)))
        }
    }

    @Test
    fun hiddenConfirmingOnlyShowsMapleSU() {
        assertFalse(shouldShowUltraBrand(installedUltra.copy(hiddenConfirming = true)))
    }

    @Test
    fun undeterminedModeOnlyShowsMapleSU() {
        assertFalse(shouldShowUltraBrand(installedUltra.copy(active = null)))
    }

    @Test
    fun missingOrUnknownInstallationOnlyShowsMapleSU() {
        for (installed in listOf(HomeInstallSignal.NOT_INSTALLED, HomeInstallSignal.UNKNOWN)) {
            assertFalse(shouldShowUltraBrand(installedUltra.copy(installed = installed)))
        }
    }

    @Test
    fun currentModeWinsOverAnOlderEnvironmentSnapshotInBothDirections() {
        for ((current, previous) in listOf(WorkMode.LITE to WorkMode.LITE, WorkMode.LITE to WorkMode.LITE)) {
            val home = homeWorkModeSwitchState(HomeWorkModeSwitchFacts(current, HomeInstallSignal.INSTALLED))
            val brand = homeUltraBrandFacts(home, false, false, environmentMode = previous)
            assertEquals(current, brand.active)
            assertEquals(current == WorkMode.LITE, shouldShowUltraBrand(brand))
        }
    }

    @Test
    fun allFactCombinationsFailClosed() {
        for (mode in listOf(null, WorkMode.LITE, WorkMode.LITE)) {
            for (installed in HomeInstallSignal.entries) {
                for (hidden in HomeHiddenSignal.entries) {
                    for (stealth in HomeHiddenSignal.entries) {
                        for (confirming in listOf(false, true)) {
                            val facts = UltraBrandFacts(mode, installed, hidden, stealth, confirming)
                            val expected = mode == WorkMode.LITE && installed == HomeInstallSignal.INSTALLED &&
                                hidden == HomeHiddenSignal.OFF && stealth == HomeHiddenSignal.OFF && !confirming
                            assertEquals(facts.toString(), expected, shouldShowUltraBrand(facts))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun projectionFromExistingHomePolicyPreservesSuppression() {
        for (hidden in HomeHiddenSignal.entries) {
            for (stealth in HomeHiddenSignal.entries) {
                val home = homeWorkModeSwitchState(
                    HomeWorkModeSwitchFacts(
                        active = WorkMode.LITE,
                        installed = HomeInstallSignal.INSTALLED,
                        liteHidden = hidden,
                        ksuStealth = stealth,
                    ),
                )
                val brand = homeUltraBrandFacts(home, liteHiddenMasking = false, hiddenConfirming = false)
                assertEquals(home.visible, shouldShowUltraBrand(brand))
                assertFalse(shouldShowUltraBrand(homeUltraBrandFacts(home, false, true)))
                assertFalse(shouldShowUltraBrand(homeUltraBrandFacts(home, true, false)))
            }
        }
    }
}
