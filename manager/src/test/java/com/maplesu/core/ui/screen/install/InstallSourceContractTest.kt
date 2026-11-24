package com.maplesu.core.ui.screen.install

import com.maplesu.core.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards unified installation, automatic stock recovery and the root-free file-export path. */
class InstallSourceContractTest {
    private fun source(name: String) = File("src/main/java/com/maplesu/core/ui/screen/install/$name.kt").readText()

    @Test fun installationHasNoActionSelectorOrRestoreOnlyNavigation() {
        val screen = source("InstallScreen")
        assertTrue(screen.contains("val ultraAction = UltraInstallAction.AUTOMATIC"))
        assertFalse(screen.contains("FlashIt.FlashRestore"))
        assertFalse(screen.contains("onSelectUltraAction"))
        assertFalse(source("InstallUiState").contains("onSelectUltraAction"))
        val steps = source("InstallSteps")
        assertFalse(steps.contains("R.string.install_ultra_action"))
        assertFalse(steps.contains("R.string.settings_restore_stock_image"))
        assertTrue(steps.contains("R.string.install_ultra_current_carrier"))
        for (theme in listOf("InstallMaterial", "InstallMiuix")) {
            assertTrue(source(theme).contains("InstallUltraStatusSection(uiState"))
            assertFalse(source(theme).contains("InstallUltraActionSection("))
        }
        val settings = File("src/main/java/com/maplesu/core/ui/screen/settings/UltraEnvironmentSettings.kt").readText()
        assertTrue(settings.contains("fun removeEnvironment() = uninstall(permanent = true)"))
    }

    @Test
    fun currentPartitionIsAvailableBeforeRootAndRequestsAccessOnlyAfterSelection() {
        val screen = source("InstallScreen")
        val options = screen.substringAfter("val installMethodOptions").substringBefore("fun showMessage")
        assertTrue(options.contains("add(InstallMethod.DirectInstall)"))
        assertFalse(options.contains("installerTarget != null"))
        val access = screen.substringAfter("LaunchedEffect(sourceRequest,").substringBefore("val selectFileTip")
        assertTrue(access.contains("installMethod != InstallMethod.DirectInstall || !existingRootConflictAccepted"))
        assertTrue(access.contains("StockInstallPreflight.discoverTarget(context, targetMode)"))
        assertFalse(access.contains("InstallerRootSession.open"))
        assertTrue(access.contains("withContext(Dispatchers.IO)"))
    }

    @Test
    fun directInstallDoesNotResolveStockBeforeAnExplicitRestore() {
        val screen = source("InstallScreen")
        assertFalse(screen.contains("StockInstallPreflight.inspect("))
        assertFalse(screen.contains("restoreOriginalForInstall"))
        assertTrue(screen.contains("installerTarget = discovered"))
        assertFalse(screen.contains("StockImageUnavailableDialog("))
        assertFalse(screen.contains("CurrentPartitionConflictDialog("))
        assertTrue(screen.contains("restoreOriginalImage = restoreOriginalImage"))
    }

    @Test
    fun filePatchingNeedsNoRootOrStockRestorationPreflight() {
        val screen = source("InstallScreen")
        assertFalse(screen.contains("filePreflightRequested"))
        assertFalse(screen.contains("showConflictForFile"))
        val selected = screen.substringAfter("val request = when (method)")
            .substringBefore("InstallMethod.DirectInstall ->")
        assertTrue(selected.contains("boot = image"))
        assertFalse(selected.contains("restoreOriginalImage ="))
        assertFalse(selected.contains("originalManagerPackage ="))
        val next = screen.substringAfter("onNext = {").substringBefore("onRestoreOriginalImageChange")
        assertTrue(next.contains("onInstall()"))
        assertFalse(next.contains("StockInstallPreflight"))
    }

    @Test
    fun restorationChoicesUseInstalledManagerNamesWithoutLaunchingOrInferringTheActiveOne() {
        val managers = source("OriginalRootManager")
        assertTrue(managers.contains("MigrationPaths.ALL_MANAGER_PACKAGES"))
        assertTrue(managers.contains("getApplicationInfo(packageName, 0)"))
        assertFalse(managers.contains("startActivity"))
        val screen = source("InstallScreen")
        assertFalse(screen.contains("restoreOriginalImage = managerInstalled != true || migrationEntry"))
        assertTrue(screen.contains("restoreOriginalImage = false"))
        assertTrue(screen.contains("restoreOriginalImage = restoreOriginalImage"))
        assertTrue(screen.contains("originalManagerPackage = originalManagerPackage"))
        assertFalse(screen.contains("openOriginalRootManager"))
    }

    @Test
    fun bothThemesUseAdvancedRestoreOptionsAndRemoveTheResumeTaskCard() {
        listOf("InstallMaterial", "InstallMiuix").forEach { name ->
            val theme = source(name)
            assertFalse(theme.contains("InstallOriginalImageSection(uiState, actions)"))
            assertTrue(theme.contains("InstallSourceStatusSection(uiState)"))
            assertTrue(theme.contains("stringResource(state.directInstallLabel)"))
            assertFalse(theme.contains("InstallerTransactionEntry"))
        }
        val advanced = source("InstallAdvancedOptions")
        assertTrue(advanced.contains("if (state.installMethod == InstallMethod.DirectInstall)"))
        assertTrue(advanced.contains("OriginalImageOptions("))
        val dialogs = source("InstallSourceDialogs")
        assertTrue(dialogs.contains("SegmentedSwitchItem("))
        assertTrue(dialogs.contains("SwitchPreference("))
        assertTrue(dialogs.contains("SegmentedDropdownItem("))
        assertTrue(dialogs.contains("OverlayDropdownPreference("))
    }

    @Test
    fun directInstallKeepsOneNameForFirstInstallsUpdatesAndLegacyMigrationRoutes() {
        for (installed in listOf(null, false, true)) for (migration in listOf(false, true)) {
            val state = InstallUiState(InstallMethod.DirectInstall, listOf(InstallMethod.DirectInstall),
                managerInstalled = installed, migrationEntry = migration)
            assertEquals(R.string.direct_install, state.directInstallLabel)
        }
        val screen = source("InstallScreen")
        assertTrue(screen.contains("when (readInstallSignal(ModeRuntime.active))"))
        assertTrue(screen.contains("managerInstalled = managerInstalled"))
    }

    @Test fun homeNoLongerOffersASeparateMigrationCard() {
        for (name in listOf("HomeScreen", "HomeMiuix", "HomeMaterial")) {
            val home = File("src/main/java/com/maplesu/core/ui/screen/home/$name.kt").readText()
            assertFalse(home.contains("MigrationEntryCard("))
            assertFalse(home.contains("onMigrationOpenPage"))
        }
    }
}
