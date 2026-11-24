package com.maplesu.core.ui.screen.install

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DirectInstallContractTest {
    private fun source(path: String) = File("src/main/java/$path").readText()
    private fun ui(path: String) = source("com/maplesu/core/ui/$path")

    @Test fun directInstallIsOfferedOnlyAfterTheActualRootCheck() {
        val screen = ui("screen/install/InstallScreen.kt")
        assertTrue(screen.contains("var rootGranted by remember { mutableStateOf(false) }"))
        val rootCheck = screen.substringAfter("rootGranted = withContext(Dispatchers.IO)")
            .substringBefore("if ((!rootGranted")
        assertTrue(rootCheck.contains("!directInstallSuppressed && managerRootAvailable("))
        assertTrue(rootCheck.contains("mode = ModeRuntime.active"))
        assertTrue(rootCheck.contains("ultraIdentity = UltraEnvironmentProbe.usesUltraBackend(context)"))
        assertTrue(rootCheck.contains("RootExec.current()?.capability(forceRefresh = true) ?: RootExec.capability(context)"))
        assertTrue(rootCheck.contains("legacyShellRoot = { EnvironmentProbesAdapter(context).suPresent() }"))
        assertTrue(screen.contains("val hasRoot = rootGranted && !directInstallSuppressed"))
        assertTrue(screen.contains("remember(selectFileTip, hasRoot)"))
        assertTrue(screen.contains("if (hasRoot) add(InstallMethod.DirectInstall)"))
        assertTrue(screen.contains("!hasRoot || !pageResumed"))
    }

    @Test fun backPreviewKeepsTheConfirmedDisplayButNeverGrantsInstallationAccess() {
        val screen = ui("screen/install/InstallScreen.kt")
        val pause = screen.substringAfter("LifecycleResumeEffect(context, detectionRevision, hiddenRevision) {")
            .substringAfter("onPauseOrDispose {")
            .substringBefore("LaunchedEffect(sourceRequest")
        assertTrue(pause.contains("job.cancel()"))
        assertFalse("A lifecycle transition is not evidence of lost SU", pause.contains("rootGranted = false"))
        assertFalse(pause.contains("hasRoot = false"))
        assertTrue(screen.contains("if (!pageResumed || installInFlight || WorkModeSwitchSession.executing) return@onInstall"))
        assertTrue(screen.contains("directAccessReady = hasRoot && pageResumed &&"))
    }

    @Test fun hiddenFactsSuppressTheOptionBeforeTheBackgroundProbeCompletes() {
        val screen = ui("screen/install/InstallScreen.kt")
        assertTrue(screen.contains("HiddenFactsSignal.revision.collectAsStateWithLifecycle()"))
        assertTrue(screen.contains("remember(context, detectionRevision, hiddenRevision)"))
        assertTrue(screen.contains("HiddenStartupConfirm.masking(context)"))
        assertTrue(screen.contains("HiddenStartupConfirm.isPending(context)"))
        assertTrue(screen.contains("mapleModeKnown && HiddenStartupConfirm.current(context)"))
        assertTrue(screen.contains("!ModeRuntime.detectionComplete ||"))
        assertTrue(screen.contains("LifecycleResumeEffect(context, detectionRevision, hiddenRevision)"))
    }

    @Test fun directInstallationShowsItsLogAndUsesItsOwnVerifiedCandidate() {
        val screen = ui("screen/flash/FlashScreen.kt")
        assertTrue(screen.contains("val showLog = !patchOnly"))
        assertTrue(screen.contains("directInstall = directInstall"))
        assertTrue(screen.contains("UltraKernelBootExport.hasVerifiedCandidate(progress.result?.bootCandidate)"))
        assertTrue(screen.contains("UltraKernelBootExport.save(context.applicationContext, bootCandidate)"))
        val export = ui("util/UltraKernelInstallExport.kt")
        assertFalse("Saving must not search for an unrelated old image", export.contains("sortedByDescending"))
        assertTrue(export.contains("candidate.sha256"))
        assertTrue(export.contains("candidate.size"))
    }

    @Test fun sdkChangesRecheckAccessWithoutDisposingTheSelectedBootSource() {
        val screen = ui("screen/install/InstallScreen.kt")
        val check = screen.substringAfter("LaunchedEffect(pageResumed, detectionRevision, hiddenRevision, sdkRootAvailable) {")
            .substringBefore("LaunchedEffect(sourceRequest")
        assertTrue(check.contains("if (!pageResumed) return@LaunchedEffect"))
        assertTrue(check.contains("rootGranted = withContext(Dispatchers.IO)"))
        assertTrue(check.contains("if ((!rootGranted || directInstallSuppressed) && installMethod == InstallMethod.DirectInstall)"))
        assertFalse(check.contains("pageResumed = false"))
        assertFalse(screen.contains("LifecycleResumeEffect(context, detectionRevision, hiddenRevision, sdkRootAvailable)"))
    }

    @Test fun successfulDirectInstallHasOnlyFixedBottomSaveAndRebootActions() {
        for (theme in listOf("FlashMaterial.kt", "FlashMiuix.kt")) {
            val screen = ui("screen/flash/$theme")
            assertTrue(theme, screen.contains("DirectInstallActions(state, actions, Modifier.align(Alignment.BottomCenter))"))
            val actions = screen.substringAfter("private fun DirectInstallActions(")
                .substringBefore("private fun FlashResultActions(")
            assertTrue(theme, actions.contains("Arrangement.SpaceBetween"))
            assertTrue(theme, actions.contains("state.canSaveBoot"))
            assertTrue(theme, actions.contains("state.showRebootAction"))
            assertTrue(theme, actions.contains("FloatingActionButton("))
            assertTrue(theme, actions.contains("actions.onReboot"))
            assertFalse(theme, actions.contains("install_boot_action_flash"))
            assertFalse(theme, actions.contains("actions.onRestore"))
            assertTrue(theme, screen.contains("if (state.directInstall && state.flashingStatus == FlashingStatus.SUCCESS) return"))
        }
    }
}
