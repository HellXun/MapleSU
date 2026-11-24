package com.maplesu.core.ui.screen.home

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.maplesu.core.KernelVersion
import com.maplesu.core.ui.LocalUiMode
import com.maplesu.core.ui.UiMode
import com.maplesu.core.ui.theme.AppSettings
import com.maplesu.core.ui.theme.ColorMode
import com.maplesu.core.ui.theme.KernelSUTheme
import com.maplesu.core.ui.theme.LocalColorMode
import com.maplesu.core.ui.theme.LocalEnableBlur
import com.maplesu.core.ui.util.module.LatestVersionInfo
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold

@RunWith(Parameterized::class)
class HomeReleaseBannerRenderTest(private val mode: UiMode, private val dark: Boolean) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun betaAndUpdateBannersStayIndependentAndKeepSeparateActions() {
        val state = mutableStateOf(fixture())
        var feedbackClicks = 0
        var openedUrl = ""
        val actions = HomeActions(onInstallClick = {}, onOpenUrl = { openedUrl = it },
            onFeedbackClick = { feedbackClicks++ })
        compose.setContent {
            CompositionLocalProvider(LocalUiMode provides mode,
                LocalColorMode provides if (dark) ColorMode.DARK.value else ColorMode.LIGHT.value,
                LocalEnableBlur provides false) {
                KernelSUTheme(AppSettings(if (dark) ColorMode.DARK else ColorMode.LIGHT,
                    0xff6750a4.toInt(), PaletteStyle.TonalSpot, ColorSpec.SpecVersion.SPEC_2025), mode) {
                    val content: @androidx.compose.runtime.Composable () -> Unit = {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            HomeBetaBanner(actions.onFeedbackClick)
                            if (state.value.checkUpdateEnabled) {
                                if (mode == UiMode.Miuix) HomeManagerUpdateBannerMiuix(state.value, actions)
                                else HomeManagerUpdateBannerMaterial(state.value, actions)
                            }
                        }
                    }
                    if (mode == UiMode.Miuix) MiuixScaffold { content() } else content()
                }
            }
        }
        val beta = compose.onNodeWithTag("home_public_beta_banner")
        val update = compose.onNodeWithTag("home_manager_update_banner")
        beta.assertIsDisplayed()
        update.assertIsDisplayed()
        assertTrue(beta.fetchSemanticsNode().boundsInRoot.bottom <= update.fetchSemanticsNode().boundsInRoot.top)
        beta.performClick()
        compose.runOnIdle { assertEquals(1, feedbackClicks); assertEquals("", openedUrl) }
        update.performClick()
        compose.runOnIdle { assertEquals(DOWNLOAD, openedUrl); assertEquals(1, feedbackClicks) }
        for (code in listOf(50109, 50108)) {
            compose.runOnIdle { state.value = fixture().copy(latestVersionInfo = LatestVersionInfo(code, DOWNLOAD)) }
            update.assertDoesNotExist()
            beta.assertIsDisplayed()
        }
        compose.runOnIdle { state.value = fixture().copy(checkUpdateEnabled = false) }
        update.assertDoesNotExist()
        beta.assertIsDisplayed()
    }

    @Test fun feedbackUsesBrowserSelectorAndExactTelegramUrl() {
        var opened: Intent? = null
        val context = object : ContextWrapper(compose.activity as Context) {
            override fun startActivity(intent: Intent) { opened = intent }
        }
        openFeedbackInBrowser(context)
        assertEquals(Intent.ACTION_VIEW, opened?.action)
        assertEquals("https://t.me/MapleSU_Debug_Bot", opened?.dataString)
        assertTrue(opened?.selector?.hasCategory(Intent.CATEGORY_APP_BROWSER) == true)
    }

    private fun fixture() = HomeUiState(
        kernelVersion = KernelVersion(6, 1, 0), ksuVersion = null, lkmMode = null,
        isLkmBundled = false, isManager = false, isManagerPrBuild = false, isKernelPrBuild = false,
        requiresNewKernel = false, requiresNewManager = false, isRootAvailable = false,
        isSafeMode = false,  checkUpdateEnabled = true,
        latestVersionInfo = LatestVersionInfo(50110, DOWNLOAD), currentManagerVersionCode = 50109,
        kernelUAPIVersion = null, managerUAPIVersion = 1,
        systemInfo = SystemInfo("6.1.0", "5.1.9 (50109)", "fixture", "fixture", "Enforcing", 0),
    )

    companion object {
        private const val DOWNLOAD = "https://github.com/FengQiuFQ/MapleSU/releases/download/v5.1.10/MapleSU.apk"
        @JvmStatic @Parameterized.Parameters(name = "{0}-dark{1}")
        fun parameters(): List<Array<Any>> = UiMode.entries.flatMap { mode ->
            listOf(false, true).map { dark -> arrayOf<Any>(mode, dark) }
        }
    }
}
