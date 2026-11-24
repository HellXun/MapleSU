package com.maplesu.core.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source contracts supplement state-policy tests for skin isolation and IO placement. */
class ThemeFlowPolishContractTest {
    private fun source(path: String) = File("src/main/java/$path").readText()

    @Test fun flowControlsRenderNativeSkinComponents() {
        val components = source("com/maplesu/core/ui/screen/host/HiddenFlowComponents.kt")
        assertTrue(components.contains("UiMode.Miuix -> top.yukonga.miuix.kmp.basic.Text("))
        assertTrue(components.contains("UiMode.Miuix -> top.yukonga.miuix.kmp.basic.TextButton("))
        assertTrue(components.contains("UiMode.Miuix -> WindowDialog("))
        assertTrue(components.contains("backgroundColor = miuixDialogColor()"))
        val host = source("com/maplesu/core/ui/screen/host/HostPickerScreen.kt")
        assertTrue(host.contains("windowInsetsPadding(WindowInsets.safeDrawing)"))
        assertTrue(host.contains("isAppearanceLightStatusBars = lightBars"))
        assertTrue(host.contains("if (!ui.isRunning) actions.onDismiss()"))
        assertFalse(host.contains("MaterialTheme"))
        assertFalse(host.contains("step.detail"))
    }

    @Test fun bothSkinsShareImageActionsAndWallpaperOwnsItsMediaActions() {
        val screen = source("com/maplesu/core/ui/screen/settings/AppearanceImagesScreen.kt")
        assertTrue(screen.contains("UiMode.Material -> ExpressiveScaffold("))
        assertTrue(screen.contains("content = rows"))
        listOf("StatusDecoration.save", "NavIcons.save", "NavIcons.clearAll").forEach {
            assertTrue(it, screen.contains(it))
        }
        assertTrue(screen.contains("withContext(Dispatchers.IO) {"))
        assertTrue(screen.contains("try { operation() }"))
        assertTrue(screen.contains("catch (e: kotlinx.coroutines.CancellationException) { throw e }"))
        assertTrue(screen.contains("TopBarBackButton"))
        assertFalse(screen.contains("Material 模式请用系统返回"))
        val wallpaper = source("com/maplesu/core/ui/screen/settings/ThemeSettingsScreens.kt")
        assertTrue(wallpaper.contains("WallpaperStore.save"))
        assertFalse(screen.contains("WallpaperStore.save"))
        val appearance = source("com/maplesu/core/ui/screen/settings/AppearanceSettingsBody.kt")
        assertTrue(appearance.contains("onClick=actions.onOpenImages"))
    }

    @Test fun ordinarySettingsNoLongerExposesInternalSelfCheck() {
        listOf("SettingsMiuix.kt", "SettingsMaterial.kt").forEach {
            assertFalse(source("com/maplesu/core/ui/screen/settings/$it").contains("WebUiSelfCheckEntry("))
        }
        val selfCheck = source("dev/maplesu/manager/workmode/ui/WebUiSelfCheckScreen.kt")
        assertTrue(selfCheck.contains("IconButton(onClick = onBack)"))
        assertTrue(selfCheck.contains("if (expanded)"))
        val vm = source("dev/maplesu/manager/workmode/ui/WebUiSelfCheckViewModel.kt")
        assertFalse(vm.contains("status.running || info != null"))
    }
}
