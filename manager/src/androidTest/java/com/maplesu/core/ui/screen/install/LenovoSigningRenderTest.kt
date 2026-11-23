package com.maplesu.core.ui.screen.install

import android.graphics.Bitmap
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.maplesu.core.R
import com.maplesu.core.ui.LocalUiMode
import com.maplesu.core.ui.UiMode
import com.maplesu.core.ui.navigation3.LocalNavigator
import com.maplesu.core.ui.navigation3.Route
import com.maplesu.core.ui.navigation3.rememberNavigator
import com.maplesu.core.ui.theme.AppSettings
import com.maplesu.core.ui.theme.ColorMode
import com.maplesu.core.ui.theme.KernelSUTheme
import com.maplesu.core.ui.theme.LocalColorMode
import com.maplesu.core.ui.theme.LocalEnableBlur
import com.maplesu.core.ui.util.AppearancePreferences
import dev.maplesu.manager.installer.InstallPartition
import dev.maplesu.manager.installer.InstallTargetMode
import dev.maplesu.manager.installer.LenovoSigningOptions
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/** Isolated presentation only: no probe, root service, image patch or partition write. */
@RunWith(Parameterized::class)
class LenovoSigningRenderTest(private val skin: UiMode, private val dark: Boolean) {
    @get:Rule val compose = createComposeRule()
    private val selected = InstallMethod.SelectFile(summary = "boot.img", uri = Uri.parse("content://fixture/boot.img"))
    private val ui = mutableStateOf(InstallUiState(installMethod = selected, installMethodOptions = listOf(selected),
        targetMode = InstallTargetMode.ULTRA, partition = InstallPartition.BOOT))
    private val dialog = mutableStateOf(false)
    private val draft = mutableStateOf(false)
    private lateinit var appearance: SharedPreferences
    private lateinit var originalAppearance: Map<String, *>

    @Before fun useDefaultAppearanceInTheIsolatedPackage() {
        appearance = AppearancePreferences.prefs(InstrumentationRegistry.getInstrumentation().targetContext)
        originalAppearance = appearance.all.toMap()
        assertTrue(appearance.edit().clear().putBoolean("migrated_resolved_v2", true)
            .putString("theme_document", "{\"isBackgroundEnabled\":false}").commit())
    }

    @After fun restoreAppearance() {
        val editor = appearance.edit().clear()
        originalAppearance.forEach { (key, value) -> when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Float -> editor.putFloat(key, value)
            is Long -> editor.putLong(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        assertTrue(editor.commit())
    }

    @Test fun expansionAndCollapseAnimateTheIndependentCard() {
        render()
        val card = compose.onNodeWithTag("lenovoAdvanced")
        val closed = card.fetchSemanticsNode().boundsInRoot.height
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(text(R.string.install_advanced_title)).performClick()
        compose.mainClock.advanceTimeBy(100)
        val midway = card.fetchSemanticsNode().boundsInRoot.height
        compose.mainClock.advanceTimeBy(600)
        val opened = card.fetchSemanticsNode().boundsInRoot.height
        assertTrue("Expansion has intermediate frames", midway > closed && midway < opened)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText(text(R.string.install_lenovo_title)).assertIsDisplayed()
        capture("expanded")
        compose.onNodeWithText(text(R.string.install_advanced_title)).performClick()
        compose.onNodeWithText(text(R.string.install_lenovo_title)).assertDoesNotExist()
    }

    @Test fun aospIsAppliedOnlyOnConfirmationAndRequestsTheThreeCompanions() {
        ui.value = ui.value.copy(advancedExpanded = true)
        render()
        compose.onNodeWithText(text(R.string.install_lenovo_title)).performClick()
        compose.onNodeWithText(text(R.string.install_lenovo_aosp)).performClick()
        compose.runOnIdle { assertFalse(ui.value.lenovoSigning.enabled) }
        capture("aosp-dialog")
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        compose.runOnIdle { assertTrue(ui.value.lenovoSigning.enabled); assertTrue(ui.value.lenovoSigning.aosp) }
        listOf("recovery", "vbmeta_system", "vbmeta").forEach { name ->
            compose.onNodeWithText(text(R.string.install_lenovo_select_image, name)).assertExists()
        }
        capture("aosp-files")
    }

    @Test fun cancelDoesNotEnableAndInitBootRequestsOnlyVbmetaEvenWithAospSelected() {
        ui.value = ui.value.copy(advancedExpanded = true)
        render()
        compose.onNodeWithText(text(R.string.install_lenovo_title)).performClick()
        compose.onNodeWithText(text(android.R.string.cancel)).performClick()
        compose.runOnIdle {
            assertFalse(ui.value.lenovoSigning.enabled)
            ui.value = ui.value.copy(partition = InstallPartition.INIT_BOOT,
                lenovoSigning = LenovoSigningOptions(enabled = true, aosp = true))
        }
        compose.onNodeWithText(text(R.string.install_lenovo_select_image, "vbmeta")).assertExists()
        compose.onNodeWithText(text(R.string.install_lenovo_select_image, "recovery")).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.install_lenovo_select_image, "vbmeta_system")).assertDoesNotExist()
        capture("init-boot-files")
    }

    private fun render() {
        compose.setContent {
            CompositionLocalProvider(LocalUiMode provides skin, LocalNavigator provides rememberNavigator(Route.Install),
                LocalColorMode provides if (dark) ColorMode.DARK.value else ColorMode.LIGHT.value,
                LocalEnableBlur provides false) {
                KernelSUTheme(AppSettings(if (dark) ColorMode.DARK else ColorMode.LIGHT,
                    0xff6750a4.toInt(), PaletteStyle.TonalSpot, ColorSpec.SpecVersion.SPEC_2025), skin) {
                    val content: @Composable () -> Unit = {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                            InstallAdvancedOptions(ui.value, InstallScreenActions(onBack = {}, onSelectMethod = {},
                                onSelectBootImage = {}, onSelectTargetMode = {}, onSelectPartition = {}, onNext = {},
                                onToggleAdvanced = { ui.value = ui.value.copy(advancedExpanded = !ui.value.advancedExpanded) },
                                onLenovoSigningChange = { enabled ->
                                    if (enabled) { draft.value = ui.value.lenovoSigning.aosp; dialog.value = true }
                                    else ui.value = ui.value.copy(lenovoSigning = ui.value.lenovoSigning.copy(enabled = false))
                                }), Modifier.testTag("lenovoAdvanced"))
                        }
                        LenovoSigningDialog(dialog.value, draft.value, { draft.value = it }, {
                            ui.value = ui.value.copy(lenovoSigning = ui.value.lenovoSigning.copy(enabled = true, aosp = draft.value))
                            dialog.value = false
                        }, { dialog.value = false })
                    }
                    if (skin == UiMode.Material) androidx.compose.material3.Scaffold { content() }
                    else top.yukonga.miuix.kmp.basic.Scaffold { content() }
                }
            }
        }
    }

    private fun text(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
    private fun capture(state: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Platform dialog-window fades use wall time, not the Compose test clock.
        android.os.SystemClock.sleep(300)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "lenovo-signing-ui").apply { mkdirs() }
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "${skin.name.lowercase()}_${if (dark) "dark" else "light"}_$state.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-dark={1}") fun cases() =
            UiMode.entries.flatMap { skin -> listOf(false, true).map { dark -> arrayOf<Any>(skin, dark) } }
    }
}
