package com.maplesu.core.ui.screen.home

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Guard the production callers shared by Material, Miuix and bottom navigation. */
class HomeRuntimeRecognitionWiringTest {
    private fun source(path: String): String = listOf(File("src/main/java/$path"), File("manager/src/main/java/$path"))
        .first { it.isFile }.readText()

    @Test fun aKeyOrPairingRecordDoesNotProveThatUltraIsRunning() {
        val adapter = source("dev/maplesu/manager/workmode/detect/probes/EnvironmentProbesAdapter.kt")
        assertFalse(adapter.contains("UltraKeyPairedStore.isPaired("))
        assertFalse(adapter.contains("MapleSuCredentialStore("))
        assertTrue(adapter.contains("UltraEnvironmentProbe.State.Running"))
    }

    @Test fun navigationUsesTheSameInstalledProjectionAsTheHomeCard() {
        val activity = source("com/maplesu/core/ui/MainActivity.kt")
        assertTrue(activity.contains("hasInstalledVersion = homeUiState.showInstalledStatus"))
    }

    @Test fun newerPanelRefreshAndInstallationCancelOlderObservations() {
        val panel = source("com/maplesu/core/ui/screen/settings/UltraEnvironmentSettings.kt")
            .substringAfter("class UltraEnvironmentInstallPanel")
        val refresh = panel.substringAfter("fun refresh(").substringBefore("fun act(")
        assertTrue(refresh.contains("refreshJob?.cancel()"))
        assertTrue(refresh.contains("refreshJob = scope.launch"))
        val install = panel.substringAfter("fun install()").substringBefore("internal fun environmentReadinessSummaryResId")
        assertTrue(install.indexOf("refreshJob?.cancel()") in 0 until install.indexOf("busy = true"))
    }

    @Test fun busyInstallationReconcilesModeAndProbeChangesWithoutDiscardingRepairActions() {
        val panel = source("com/maplesu/core/ui/screen/settings/UltraEnvironmentSettings.kt")
            .substringAfter("class UltraEnvironmentInstallPanel")
        val install = panel.substringAfter("fun install()").substringBefore("internal fun environmentReadinessSummaryResId")
        assertTrue(install.contains("val modeBeforeInstall = ModeRuntime.active"))
        assertTrue(install.contains("val stateBeforeInstall = UltraEnvironmentProbe.currentSnapshot(context).state"))
        assertTrue(install.contains("localIdentity() != keyBeforeInstall"))
        assertTrue(install.contains("UltraEnvironmentInstaller.Intent.InstallToBundledBuild"))
        assertTrue(install.contains("ERR_MODULE_NO_BOOT"))
        assertTrue(install.contains("ERR_MODULE_ROOT_KEY"))
        val completion = install.substringAfter("finally {")
        assertTrue(completion.contains("busy = false"))
        assertTrue(completion.contains("ModeRuntime.active != modeBeforeInstall ||"))
        assertTrue(completion.contains("UltraEnvironmentProbe.currentSnapshot(context).state != stateBeforeInstall) refresh()"))
        assertFalse(completion.contains("refresh(modeBeforeInstall)"))
    }
}
