package com.maplesu.core.theme

import android.graphics.Bitmap
import com.maplesu.core.R
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.toSize
import androidx.test.platform.app.InstrumentationRegistry
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.maplesu.core.ui.LocalUiMode
import com.maplesu.core.ui.UiMode
import com.maplesu.core.ui.navigation3.LocalNavigator
import com.maplesu.core.ui.navigation3.Route
import com.maplesu.core.ui.navigation3.rememberNavigator
import com.maplesu.core.ui.screen.settings.*
import com.maplesu.core.ui.theme.AppSettings
import com.maplesu.core.ui.theme.ColorMode
import com.maplesu.core.ui.theme.KernelSUTheme
import com.maplesu.core.ui.theme.LocalColorMode
import com.maplesu.core.ui.LocalMainPagerState
import com.maplesu.core.ui.component.bottombar.rememberMainPagerState
import com.maplesu.core.ui.screen.home.*
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.ui.WorkModeStrings
import com.maplesu.core.KernelVersion
import com.maplesu.core.ui.util.module.LatestVersionInfo
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Assume.assumeTrue
import org.junit.rules.TestName
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody

/** Production screens/dialogs, real semantics, no root bootstrap, both native skins and day/night. */
@RunWith(Parameterized::class)
class ThemePresentationDeviceTest(private val initialSkin: UiMode, private val dark: Boolean) {
    @get:Rule val compose = createComposeRule()
    @get:Rule val testName = TestName()
    private val page = mutableStateOf("wallpaper")
    private val aboutVisible = mutableStateOf(false)
    private val aboutShowsModes = mutableStateOf(true)
    private val skin = mutableStateOf(initialSkin)
    private var store: ThemeStoreViewModel? = null
    private lateinit var operations: ThemeOperations
    private var backgroundSelection = ThemeBackgroundSelection()
    private var mediaPhoto: ThemePhoto? = null
    private var runtimeOwner: androidx.lifecycle.LifecycleOwner? = null
    private var importedAppearance = false
    private var renderedWallpaperStyle: ThemeWallpaperStyle? = null
    private val counts = mutableStateOf(ThemeHomeCounts(7,11))
    private val rootReady = mutableStateOf(true)

    @Before fun resetFixture() {
        val arguments = InstrumentationRegistry.getArguments()
        arguments.getString("themeUiFocus")?.let { assumeTrue(testName.methodName.substringBefore('[') in it.split(',')) }
        arguments.getString("themeUiMode")?.let { assumeTrue(it == "${initialSkin.name}-${if (dark) "dark" else "light"}") }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.endsWith(".themetest"))
        runBlocking { ThemeRepository(context).reset() }
        File(context.filesDir,"themes/store").deleteRecursively()
        com.maplesu.core.ui.util.AppearancePreferences.prefs(context).edit()
            .putString("theme_store_mode","builtin").putBoolean("theme_store_intro",false)
            .putString(ThemeStoreSource.PREFERENCE,ThemeStoreSource.Fina.id).remove(ThemeStoreSource.CUSTOM_URL)
            .putString("status_decoration_source","check")
            .remove(ThemeHomeLayout.PREFERENCE).putBoolean("home_large_card",true).commit()
    }

    @After fun releaseFixture() {
        store?.let { model -> androidx.lifecycle.ViewModelStore().apply { put("fixture",model); clear() } }
    }

    @Test fun aboutFinaCreditsUseTheExistingNativeDialog() {
        openAboutDialog(showModes = true)
        showAboutLine(modeHeading(WorkMode.LITE))
        ABOUT_ULTRA_POINTS.forEach { showAboutLine(text(it)) }
        showAboutLine(modeHeading(WorkMode.LITE))
        showAboutLine(text(R.string.about_maple_normal))
        showAboutLine(modeHeading(WorkMode.LITE))
        capture("about-mode-introductions")
        ABOUT_CREDITS.forEach { showAboutLine("${it.name} · ${text(it.role)}") }
        capture("about-finasu-credit")
    }

    @Test fun aboutHiddenStateKeepsCreditsAndOmitsOnlyModeInformation() {
        openAboutDialog(showModes = false)
        showAboutLine(text(R.string.about_maple_overview_plain))
        for (mode in WorkMode.entries) {
            compose.onNodeWithText(modeHeading(mode)).assertDoesNotExist()
        }
        ABOUT_ULTRA_POINTS.forEach { compose.onNodeWithText(text(it)).assertDoesNotExist() }
        compose.onNodeWithText(text(R.string.about_maple_normal)).assertDoesNotExist()
        ABOUT_CREDITS.forEach { showAboutLine("${it.name} · ${text(it.role)}") }
        capture("about-hidden-credits")
    }

    private fun openAboutDialog(showModes: Boolean) {
        aboutShowsModes.value = showModes
        page.value = "about"
        render()
        // Freeze the decorative brand clock, not the production UI or system animation settings.
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { aboutVisible.value = true }
        compose.mainClock.advanceTimeBy(1_000)
        android.os.SystemClock.sleep(350)
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun showAboutLine(line: String) {
        compose.onNodeWithText(line).performScrollTo()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(line).assertIsDisplayed()
    }

    private fun modeHeading(mode: WorkMode): String = InstrumentationRegistry.getInstrumentation()
        .targetContext.getString(R.string.work_mode_intro_title,
            text(WorkModeStrings.modeNameRes(mode)), text(WorkModeStrings.modeTaglineRes(mode)))

    @Test fun libraryExportDraftSurvivesSkinChangeAndResetConfirmationStaysNative() {
        page.value = "local"
        render()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text(R.string.theme_package_export)))
        compose.onNodeWithText(text(R.string.theme_package_export)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.theme_name)).performTextReplacement("Fixture draft")
        capture("export-dialog")
        compose.runOnIdle { skin.value = if (initialSkin == UiMode.Miuix) UiMode.Material else UiMode.Miuix }
        compose.onNodeWithText("Fixture draft").assertIsDisplayed()
        capture("export-dialog-switched")
        compose.onNodeWithText(text(R.string.theme_cancel)).performClick()
        compose.runOnIdle { page.value = "appearance" }
        compose.onNodeWithText(text(R.string.theme_reset)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.theme_reset_message)).assertIsDisplayed()
        capture("reset-dialog")
        compose.onNodeWithText(text(R.string.theme_cancel)).performClick()
    }

    @Test fun appearanceHasOneLayoutEntryAndRestoreDraftOnlyAppliesAfterConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = com.maplesu.core.ui.util.AppearancePreferences.prefs(context)
        prefs.edit().putString("ui_mode",initialSkin.value).putFloat("page_scale",.9f).commit()
        page.value = "appearance"
        render()
        org.junit.Assert.assertEquals(1,compose.onAllNodesWithText(text(R.string.theme_layout_title)).fetchSemanticsNodes().size)
        compose.onNodeWithText(text(R.string.theme_reset)).performScrollTo().performClick()
        val before = prefs.all.toMap()
        compose.onNodeWithText(text(R.string.theme_reset_comic)).performScrollTo().performClick()
        capture("restore-comic-draft")
        compose.onNodeWithText(text(R.string.theme_cancel)).performClick()
        org.junit.Assert.assertEquals(before,prefs.all)
        compose.onNodeWithText(text(R.string.theme_reset)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.theme_reset_comic)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.theme_reset_confirm)).performClick()
        compose.waitUntil(10_000) { prefs.getString("status_decoration_source",null) == "comic" && !operations.state.value.busy }
        org.junit.Assert.assertEquals(initialSkin.value,prefs.getString("ui_mode",null))
        org.junit.Assert.assertEquals(1f,prefs.getFloat("page_scale",1f))
        assertTrue(prefs.getBoolean("home_large_card",false))
        compose.onNodeWithText(text(R.string.theme_reset)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.theme_reset_confirm)).performClick()
        compose.waitUntil(10_000) { prefs.getString("status_decoration_source",null) == "check" && !operations.state.value.busy }
        assertTrue(!prefs.getBoolean("home_large_card",true))
        org.junit.Assert.assertEquals(initialSkin.value,prefs.getString("ui_mode",null))
        capture("restore-standard")
    }

    @Test fun homeCountsStayVisibleWhileRootConnectsAndAcceptARefreshedZero() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { ThemeRepository(context).homeLayout(ThemeHomeLayout.Three) }
        rootReady.value = false
        page.value = "home"
        render()
        compose.onNode(hasText("7") and hasAnyAncestor(hasTestTag("home-counter-superuser")),useUnmergedTree=true).assertIsDisplayed()
        compose.onNode(hasText("11") and hasAnyAncestor(hasTestTag("home-counter-module")),useUnmergedTree=true).assertIsDisplayed()
        compose.runOnIdle { rootReady.value=true; counts.value=ThemeHomeCounts(0,4) }
        compose.onNode(hasText("0") and hasAnyAncestor(hasTestTag("home-counter-superuser")),useUnmergedTree=true).assertIsDisplayed()
        compose.onNode(hasText("4") and hasAnyAncestor(hasTestTag("home-counter-module")),useUnmergedTree=true).assertIsDisplayed()
        capture("home-counts-refreshed")
    }

    @Test fun realThemeImportPreviewFitsAndKeepsConfirmationVisible() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("themeRealAppearance") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.getExternalFilesDir(null),"real-theme-inputs/store-card-live.fpt")
        assertTrue(input.isFile)
        loadThemeFixture()
        importedAppearance=true
        page.value="home"
        render()
        compose.runOnIdle { operations.prepare(android.net.Uri.fromFile(input)) }
        compose.waitUntil(15_000) { operations.state.value.prepared != null && !operations.state.value.busy }
        compose.onNodeWithTag("theme-import-preview").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.theme_apply)).assertIsDisplayed()
        val preview = compose.onNodeWithTag("theme-import-preview").fetchSemanticsNode().boundsInRoot
        val confirm = compose.onNodeWithText(text(R.string.theme_apply)).fetchSemanticsNode().boundsInRoot
        assertTrue("Preview overlaps confirmation",preview.bottom < confirm.top)
        capture("real-theme-import-preview")
        compose.onNodeWithText(text(R.string.theme_cancel)).performClick()
        compose.waitUntil(5_000) { operations.state.value.prepared == null }
    }

    @Test fun fontLayoutAndMediaExposeTheSameActionsInBothDesignLanguages() {
        page.value = "font"
        render()
        compose.onNodeWithText(text(R.string.theme_font_system)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.theme_font_custom)).assertIsDisplayed()
        capture("font")
        compose.runOnIdle { page.value = "layout" }
        compose.onNodeWithText(text(R.string.theme_home_large)).assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText(text(R.string.theme_home_small)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.theme_home_three)).performScrollTo().assertIsDisplayed()
        for (layout in listOf(R.string.theme_home_small,R.string.theme_home_three,R.string.theme_home_large)) {
            compose.onNodeWithText(text(layout)).performScrollTo().performClick()
            compose.onNodeWithText(text(layout)).assertIsSelected()
        }
        compose.onNodeWithText(text(R.string.theme_advanced_media)).assertDoesNotExist()
        capture("layout")
        compose.runOnIdle { page.value = "media" }
        compose.onNodeWithText(text(R.string.theme_title_image_gif)).assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text(R.string.theme_sound)))
        compose.onAllNodesWithText(text(R.string.theme_sound))[0].performScrollTo().assertIsDisplayed()
        capture("media")
    }

    @Test fun disablingTheAdvancedTitleKeepsTheAppliedAppearanceAndOtherControlsEnabled() {
        loadThemeFixture()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val generation = ThemeRuntime.generation(context)
        val before = com.maplesu.core.ui.theme.ThemeController.getAppSettings()
        importedAppearance = true
        page.value = "media"
        render()
        compose.waitUntil(10_000) { !operations.state.value.busy && renderedWallpaperStyle != null }
        val style = renderedWallpaperStyle
        compose.onNodeWithText(text(R.string.theme_title_advanced)).performScrollTo().performClick()
        compose.waitUntil(5_000) { !ThemeRuntime.document(context).optBoolean("isAdvancedTitleStyleEnabled") }
        compose.runOnIdle {
            assertTrue(!operations.state.value.busy)
            org.junit.Assert.assertEquals(generation,ThemeRuntime.generation(context))
            org.junit.Assert.assertEquals(before,com.maplesu.core.ui.theme.ThemeController.getAppSettings())
            org.junit.Assert.assertEquals(style,renderedWallpaperStyle)
        }
        compose.onNodeWithText(text(R.string.theme_title_image_gif)).performScrollTo().assertIsEnabled()
        compose.onNodeWithText(text(R.string.theme_title_text)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { ThemeRuntime.document(context).optString("titleTextMode") == "kernelsu" }
        compose.runOnIdle {
            assertTrue(!operations.state.value.busy)
            org.junit.Assert.assertEquals(before,com.maplesu.core.ui.theme.ThemeController.getAppSettings())
            org.junit.Assert.assertEquals(generation,ThemeRuntime.generation(context))
        }
        capture("media-title-disabled-appearance-retained")
    }

    @Test fun localLibraryEmptyStateFiltersAndFileImportAreReachable() {
        page.value = "local"
        render()
        compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasText(text(R.string.theme_library_empty))).fetchSemanticsNodes().isNotEmpty() }
        val importAction = hasText(text(R.string.theme_import_file)) or hasContentDescription(text(R.string.theme_import_file))
        try { compose.waitUntil(15_000) { compose.onAllNodes(importAction).fetchSemanticsNodes().isNotEmpty() } }
        catch (error: Throwable) {
            capture("library-import-missing")
            diagnostic("library-import-missing")
            throw error
        }
        compose.onNode(importAction).assertIsDisplayed()
        capture("library-empty")
        compose.onNode(hasContentDescription(text(R.string.theme_filter_sort))).performClick()
        compose.onNodeWithText(text(R.string.theme_sort_default)).assertIsSelected()
        compose.onNodeWithText(text(R.string.theme_sort_name)).performClick().assertIsSelected()
        compose.onNodeWithText(text(R.string.theme_cancel)).performClick()
        compose.onNode(hasContentDescription(text(R.string.theme_filter_sort))).performClick()
        compose.onNodeWithText(text(R.string.theme_sort_default)).assertIsSelected()
        compose.onNodeWithText(text(R.string.theme_sort_name)).performClick()
        compose.onNodeWithText(text(R.string.theme_confirm)).performClick()
        compose.onNode(hasContentDescription(text(R.string.theme_filter_sort))).performClick()
        compose.onNodeWithText(text(R.string.theme_sort_name)).assertIsSelected()
        capture("filters-sort")
        compose.onNode(hasText(text(R.string.theme_source_all),substring=true)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.theme_author_all)).performScrollTo().assertIsDisplayed()
        capture("filters")
        compose.onNodeWithText(text(R.string.theme_cancel)).performClick()
    }

    @Test fun storeModeCancelDownloadDetailsImportDeleteAndErrorRemainNative() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val payload = FptContainer.write(org.json.JSONObject().put("meta_name","Imported fixture"),emptyMap())
        val index = """[{"id":1,"name":"Store fixture","author":"Fixture","version":"1","download_url":"https://fixture.invalid/theme.fpt"}]"""
        store = ThemeStoreViewModel(ThemeStore(context,OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val bytes = if (request.url.encodedPath.endsWith(".fpt")) payload else index.toByteArray()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(bytes.toResponseBody()).build()
        }.build()))
        page.value = "store"
        render()
        compose.onNodeWithText(text(R.string.theme_download_mode_title)).assertIsDisplayed()
        val builtin = compose.onNodeWithTag("theme-download-builtin").fetchSemanticsNode().boundsInRoot
        val browser = compose.onNodeWithTag("theme-download-compat").fetchSemanticsNode().boundsInRoot
        assertTrue(builtin.right < browser.left && kotlin.math.abs(builtin.top-browser.top) < 2f)
        assertTrue(kotlin.math.abs(builtin.height-browser.height) < 2f)
        compose.onNodeWithTag("theme-download-compat").performClick().assertIsSelected()
        val preferences = com.maplesu.core.ui.util.AppearancePreferences.prefs(context)
        org.junit.Assert.assertEquals("builtin",preferences.getString("theme_store_mode",null))
        capture("download-mode-draft")
        compose.onNodeWithText(text(R.string.theme_cancel)).performClick()
        compose.onNode(hasContentDescription(text(R.string.theme_library))).assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text(R.string.theme_download_builtin)))
        compose.onNodeWithText(text(R.string.theme_download_builtin)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.theme_download_mode)).performClick()
        compose.onNodeWithTag("theme-download-builtin").assertIsSelected()
        compose.onNodeWithTag("theme-download-compat").performClick()
        compose.onNodeWithText(text(R.string.theme_confirm)).performClick()
        org.junit.Assert.assertEquals("compat",preferences.getString("theme_store_mode",null))
        compose.onNodeWithText(text(R.string.theme_download_mode)).performClick()
        compose.onNodeWithTag("theme-download-compat").assertIsSelected()
        compose.onNodeWithTag("theme-download-builtin").performClick()
        compose.onNodeWithText(text(R.string.theme_confirm)).performClick()
        org.junit.Assert.assertEquals("builtin",preferences.getString("theme_store_mode",null))
        compose.waitUntil(15_000) { store!!.state.value.items.any { it.name == "Store fixture" } }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Store fixture") and hasClickAction())
        compose.onNode(hasText("Store fixture") and hasClickAction()).performScrollTo().performClick()
        capture("store-details")
        compose.onAllNodesWithText(text(R.string.theme_download)).onLast().performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(text(R.string.theme_download_done))).fetchSemanticsNodes().isNotEmpty() }
        val result = compose.onNodeWithTag("theme-download-result").fetchSemanticsNode().boundsInRoot
        val openLibrary = compose.onNodeWithTag("theme-open-library").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Library action must fill the result width",kotlin.math.abs(result.width-openLibrary.width) < 2f)
        capture("download-complete")
        compose.onAllNodesWithText(text(R.string.theme_apply)).onLast().performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Imported fixture")).fetchSemanticsNodes().isNotEmpty() }
        capture("import-confirmation")
        compose.runOnIdle { skin.value = if (initialSkin == UiMode.Material) UiMode.Miuix else UiMode.Material }
        compose.onNodeWithText("Imported fixture").assertIsDisplayed()
        capture("import-confirmation-switched")
        compose.onNodeWithText(text(R.string.theme_cancel)).performClick()
        compose.runOnIdle { page.value = "fixture-local" }
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(text(R.string.theme_delete))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.theme_delete)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.theme_delete_title)).assertIsDisplayed()
        capture("delete-confirmation")
        compose.onNodeWithText(text(R.string.theme_cancel)).performClick()
        compose.runOnIdle { operations.error(IllegalStateException("Fixture error")) }
        compose.onNodeWithText("Fixture error").assertIsDisplayed()
        capture("error")
        compose.onNodeWithText(text(R.string.theme_confirm)).performClick()
        compose.onNodeWithText("Fixture error").assertDoesNotExist()
    }

    @Test fun threeHomeArrangementsKeepMapleInformationAndNeverAddNavigationButtons() {
        loadThemeFixture()
        page.value = "home"
        render()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (layout in ThemeHomeLayout.entries) {
            compose.runOnIdle { skin.value = initialSkin }
            runBlocking { ThemeRepository(context).homeLayout(layout) }
            if (layout == ThemeHomeLayout.Three) {
                compose.onNodeWithTag("home-three").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("home-counter-superuser").assertHasNoClickAction()
                compose.onNodeWithTag("home-counter-module").assertHasNoClickAction()
                assertThreeCardGeometry()
            } else {
                compose.onNodeWithTag("home-original-status").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("home-three").assertDoesNotExist()
                compose.onNodeWithTag("home-counter-superuser").assertDoesNotExist()
                compose.onNodeWithTag("home-counter-module").assertDoesNotExist()
            }
            capture("home-${layout.value}")
            compose.onNodeWithText("Theme fixture manager").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Theme fixture device").performScrollTo().assertIsDisplayed()
            capture("home-${layout.value}-maple-information")
            compose.runOnIdle { skin.value = if (skin.value == UiMode.Miuix) UiMode.Material else UiMode.Miuix }
            compose.onNodeWithTag(if (layout == ThemeHomeLayout.Three) "home-three" else "home-original-status")
                .performScrollTo().assertIsDisplayed()
            if (layout == ThemeHomeLayout.Three) assertThreeCardGeometry()
            capture("home-${layout.value}-switched")
        }
    }

    private fun assertThreeCardGeometry() {
        fun bounds(tag: String, unmerged: Boolean = false): androidx.compose.ui.geometry.Rect {
            val node = compose.onNodeWithTag(tag,useUnmergedTree=unmerged).fetchSemanticsNode()
            return androidx.compose.ui.geometry.Rect(node.positionInRoot,node.size.toSize())
        }
        val hero = bounds("home-three-status",true)
        val top = bounds("home-counter-superuser")
        val bottom = bounds("home-counter-module")
        File(evidenceDirectory(),"${initialSkin.name}-${if(dark) "dark" else "light"}-three-geometry-${skin.value.name}.json")
            .writeText(org.json.JSONObject().put("mainWidthPx",hero.width).put("mainHeightPx",hero.height)
                .put("topWidthPx",top.width).put("topHeightPx",top.height).put("bottomHeightPx",bottom.height)
                .put("columnGapPx",top.left-hero.right).put("rowGapPx",bottom.top-top.bottom).toString(2))
        assertTrue(hero.left < top.left && top.bottom < bottom.top)
        assertTrue("The main tile must be square: $hero",kotlin.math.abs(hero.width-hero.height) < 2f)
        assertTrue(kotlin.math.abs(hero.width-top.width) < 2f)
        assertTrue(kotlin.math.abs(top.height-bottom.height) < 2f)
        assertTrue(kotlin.math.abs(hero.top-top.top) < 2f && kotlin.math.abs(hero.bottom-bottom.bottom) < 2f)
        assertTrue(kotlin.math.abs((top.left-hero.right)-(bottom.top-top.bottom)) < 2f)
    }

    @Test fun settingsBackgroundSelectionAndTheMasterSwitchUseTheSameRuntime() {
        loadThemeFixture()
        render()
        compose.runOnIdle { assertTrue(backgroundSelection.image?.name == "background_settings.png") }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { ThemeRepository(context).edit(mapOf("isBackgroundEnabled" to false)) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(!backgroundSelection.active) }
        // A manual wallpaper retains its cached seed while disabled, but it must not tint the app.
        com.maplesu.core.ui.util.AppearancePreferences.prefs(context).edit()
            .putBoolean("wallpaper_seed",true).putInt("wallpaper_seed_color",0xff006876.toInt()).commit()
        compose.runOnIdle { assertTrue(com.maplesu.core.ui.theme.ThemeController.getAppSettings().wallpaperColor == 0) }
        capture("background-disabled")
        runBlocking { ThemeRepository(context).edit(mapOf("isBackgroundEnabled" to true)) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(backgroundSelection.image?.name == "background_settings.png") }
        capture("background-enabled")
    }

    @Test fun musicPauseResumeAndThemeReplacementReleaseThePreviousPlayer() {
        loadThemeFixture()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { ThemeRepository(context).edit(mapOf("musicVolume" to 0f,"isLoopingEnabled" to true,"isAutoPlayEnabled" to true)) }
        page.value = "media-runtime"
        render()
        compose.waitUntil(15_000) { ThemeMusic.playing }
        compose.runOnIdle { ThemeMusic.toggle() }
        compose.waitUntil(5_000) { !ThemeMusic.playing }
        compose.runOnIdle { ThemeMusic.toggle() }
        compose.waitUntil(5_000) { ThemeMusic.playing }
        compose.runOnIdle { skin.value = if (skin.value == UiMode.Miuix) UiMode.Material else UiMode.Miuix }
        compose.waitUntil(15_000) { ThemeMusic.playing }
        runBlocking { ThemeRepository(context).reset() }
        compose.waitUntil(5_000) { !ThemeMusic.playing && !ThemeMusic.requested }
        capture("media-runtime-reset")
    }

    @Test fun defaultAndComicArtworkUseTheSameRuleInEveryHomeArrangement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        page.value = "home"
        render()
        val prefs = com.maplesu.core.ui.util.AppearancePreferences.prefs(context)
        var defaultSurface: Int? = null
        for (layout in ThemeHomeLayout.entries) {
            runBlocking { ThemeRepository(context).homeLayout(layout) }
            compose.onNodeWithTag(if (layout == ThemeHomeLayout.Three) "home-three" else "home-original-status").performScrollTo()
            compose.runOnUiThread { prefs.edit().putString("status_decoration_source","comic").commit() }
            compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("status-decoration-image"),useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("home-status-default-check",useUnmergedTree=true).assertDoesNotExist()
            if (layout == ThemeHomeLayout.Three) compose.onNodeWithTag("home-three-status-check",useUnmergedTree=true).assertDoesNotExist()
            capture("comic-${layout.value}")
            compose.runOnUiThread { prefs.edit().putString("status_decoration_source","check").commit() }
            compose.onNodeWithTag("status-decoration-image",useUnmergedTree=true).assertDoesNotExist()
            compose.onNodeWithTag("home-status-default-check",useUnmergedTree=true).performScrollTo().assertIsDisplayed()
            val pixels = compose.onNodeWithTag(if (layout == ThemeHomeLayout.Three)
                "home-three-status" else "home-original-status",useUnmergedTree=true).captureToImage().toPixelMap()
            val surface = pixels[pixels.width/2,8.coerceAtMost(pixels.height-1)].toArgb()
            defaultSurface?.let { org.junit.Assert.assertEquals("Default status surface differs for $layout",it,surface) }
            val background = androidx.compose.ui.graphics.Color(surface)
            assertTrue("Default status surface must stay green for $layout", background.green > background.red + .03f && background.green > background.blue + .03f)
            defaultSurface = surface
            capture("default-${layout.value}")
            if (layout == ThemeHomeLayout.Three) {
                val hero = compose.onNodeWithTag("home-three-status",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
                val check = compose.onNodeWithTag("home-three-status-check",useUnmergedTree=true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue(check.center.x > hero.center.x && check.center.y > hero.center.y)
                // The original KSU decoration extends past the edge and is clipped by the card.
                assertTrue(check.left < hero.right && check.right >= hero.right-2f)
                assertTrue(check.top < hero.bottom && check.bottom >= hero.bottom-2f)
            }
        }
    }

    /** Original store packages are supplied by the host so normal tests stay offline and small. */
    @Test fun realFinaStorePackagesRenderAcrossBothSkinsAndEveryHomeArrangement() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("themeRealAppearance") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inputs = File(context.getExternalFilesDir(null),"real-theme-inputs")
        val prefs = com.maplesu.core.ui.util.AppearancePreferences.prefs(context)
        val repository = ThemeRepository(context)
        importedAppearance = true
        page.value = "home"
        render()
        val evidence = org.json.JSONArray()
        for (name in listOf("store-live","store-card-live")) {
            val file = File(inputs,"$name.fpt")
            assertTrue("Missing original Fina store package: $file",file.isFile)
            runBlocking { repository.homeLayout(ThemeHomeLayout.Small) }
            compose.runOnUiThread { prefs.edit().putString("ui_mode",initialSkin.value).commit() }
            val prepared = runBlocking { repository.prepare(android.net.Uri.fromFile(file)) }
            runBlocking { repository.apply(prepared) }
            org.junit.Assert.assertEquals(initialSkin.value,prefs.getString("ui_mode",null))
            org.junit.Assert.assertEquals("small",prefs.getString(ThemeHomeLayout.PREFERENCE,null))
            compose.waitUntil(15_000) { backgroundSelection.image?.parentFile?.parentFile == ThemeRuntime.generation(context) &&
                renderedWallpaperStyle != null }
            val document = ThemeRuntime.document(context)
            val expectedDim = if (document.optBoolean("isDualBackgroundDimEnabled"))
                document.optDouble(if (dark) "backgroundNightDim" else "backgroundDayDim",.2).toFloat()
                else prefs.getFloat("wallpaper_dim",.2f)
            compose.runOnIdle {
                org.junit.Assert.assertEquals("Theme dim was replaced by a global veil",expectedDim,renderedWallpaperStyle!!.dim,.001f)
                assertTrue(renderedWallpaperStyle!!.group >= .28f)
            }
            val font = ThemeRuntime.file(context,"font")
            if (ThemeRuntime.document(context).optBoolean("isFontEnabled")) assertTrue(font?.isFile == true)
            for (layout in ThemeHomeLayout.entries) {
                runBlocking { repository.homeLayout(layout) }
                for (mode in listOf(initialSkin,if (initialSkin == UiMode.Miuix) UiMode.Material else UiMode.Miuix)) {
                    compose.runOnIdle { skin.value = mode }
                    compose.onNodeWithTag(if (layout == ThemeHomeLayout.Three) "home-three" else "home-original-status")
                        .performScrollTo().assertIsDisplayed()
                    if (name == "store-card-live") compose.waitUntil(15_000) {
                        compose.onAllNodes(androidx.compose.ui.test.hasTestTag("home-status-theme-image"),useUnmergedTree=true)
                            .fetchSemanticsNodes().isNotEmpty()
                    }
                    if (name == "store-card-live") compose.onNodeWithTag("home-status-default-check",useUnmergedTree=true)
                        .assertDoesNotExist()
                    capture("real-$name-${layout.value}-${mode.value}")
                    compose.onNodeWithText("Theme fixture manager").performScrollTo().assertIsDisplayed()
                }
            }
            val row = org.json.JSONObject().put("package",name).put("metadata",prepared.metadata.name)
                .put("bytes",file.length()).put("sha256",java.security.MessageDigest.getInstance("SHA-256")
                    .digest(file.readBytes()).joinToString("") { "%02x".format(it) })
                .put("background",backgroundSelection.image?.name).put("font",font?.name)
                .put("requestedDim",expectedDim).put("renderedDim",renderedWallpaperStyle?.dim)
                .put("cardAlpha",renderedWallpaperStyle?.group)
            evidence.put(row)
        }
        File(evidenceDirectory(),"${initialSkin.name}-${if (dark) "dark" else "light"}-real-packages.json")
            .writeText(evidence.toString(2))
        runBlocking { repository.reset() }
        compose.runOnIdle { skin.value = initialSkin }
        compose.onNodeWithTag("home-original-status").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("home-three").assertDoesNotExist()
        assertTrue(!prefs.getBoolean("home_large_card",true))
        compose.onNodeWithTag("home-status-theme-image",useUnmergedTree=true).assertDoesNotExist()
        capture("real-packages-reset")
    }

    @Test fun mapleCatalogPackagesRenderInEveryArrangementWithoutChangingTheSkin() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        page.value = "home"
        render()
        for (layout in ThemeHomeLayout.entries) {
            runBlocking { ThemeRepository(context).homeLayout(layout) }
            for (name in listOf("comic","default")) {
                loadThemeFixture("maplesu-$name")
                compose.onNodeWithTag(if (layout == ThemeHomeLayout.Three) "home-three" else "home-original-status").performScrollTo()
                val image = androidx.compose.ui.test.hasTestTag("home-status-theme-image")
                compose.waitUntil(10_000) {
                    compose.onAllNodes(image,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() == (name == "comic")
                }
                assertTrue(skin.value == initialSkin)
                capture("catalog-$name-${layout.value}")
            }
        }
    }

    @Test fun sourceSelectionKeepsTheDraftAcrossSkinsAndCommitsOnlyOnConfirm() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = ThemeStoreViewModel(ThemeStore(context,OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("Fixture").body("[]".toResponseBody()).build()
        }.build()))
        page.value = "store"
        com.maplesu.core.ui.util.AppearancePreferences.prefs(context).edit().putBoolean("theme_store_intro",true).commit()
        render()
        compose.onNodeWithText(text(R.string.theme_catalog_source)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.theme_catalog_custom)).performClick()
        compose.onNodeWithText(text(R.string.theme_catalog_url)).performTextReplacement("https://custom.invalid/themes/index.json")
        hideKeyboard()
        compose.runOnIdle { skin.value = if (initialSkin == UiMode.Miuix) UiMode.Material else UiMode.Miuix }
        compose.onNodeWithText("https://custom.invalid/themes/index.json").performScrollTo().assertIsDisplayed()
        capture("source-custom-draft")
        assertTrue(store!!.repository.source() == ThemeStoreSource.Fina)
        compose.onNodeWithText(text(R.string.theme_cancel)).performClick()
        assertTrue(store!!.repository.source() == ThemeStoreSource.Fina)
        compose.onNodeWithText(text(R.string.theme_catalog_source)).performClick()
        compose.onNodeWithText("MapleSU").performClick()
        compose.onNodeWithText("MapleSU").assertIsSelected()
        capture("source-maple-selection")
        compose.onNodeWithText(text(R.string.theme_confirm)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.theme_confirm)).performClick()
        try { compose.waitUntil(10_000) { store!!.state.value.catalog == ThemeStoreSource.Maple && !store!!.state.value.loading } }
        catch (error: Throwable) {
            capture("source-commit-missing")
            diagnostic("source-commit-missing")
            throw error
        }
        compose.onNodeWithText("MapleSU").assertIsDisplayed()
        runBlocking { ThemeRepository(context).reset() }
        assertTrue(store!!.repository.source() == ThemeStoreSource.Maple)
        capture("source-maplesu")
        compose.onNodeWithText(text(R.string.theme_catalog_source)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.theme_catalog_custom)).performClick()
        compose.onNodeWithText(text(R.string.theme_catalog_url)).performTextReplacement("http://custom.invalid/index.json")
        compose.onNodeWithText(text(R.string.theme_confirm)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.theme_catalog_url)).performTextReplacement("https://custom.invalid/themes/index.json")
        hideKeyboard()
        compose.onNodeWithText(text(R.string.theme_confirm)).performClick()
        val custom = ThemeStoreSource.custom("https://custom.invalid/themes/index.json")
        compose.waitUntil(10_000) { store!!.state.value.catalog == custom && !store!!.state.value.loading }
        assertTrue(store!!.repository.source() == custom)
        runBlocking { ThemeRepository(context).reset() }
        assertTrue(store!!.repository.source() == custom)
        capture("source-custom-committed")
        compose.onNodeWithText(text(R.string.theme_catalog_source)).performScrollTo().performClick()
        compose.onNodeWithText("FinaSU").performClick()
        compose.onNodeWithText(text(R.string.theme_confirm)).performClick()
        compose.waitUntil(10_000) { store!!.state.value.catalog == ThemeStoreSource.Fina && !store!!.state.value.loading }
        assertTrue(store!!.repository.source() == ThemeStoreSource.Fina)
        capture("source-finasu")
    }

    private fun hideKeyboard() = compose.runOnUiThread {
        androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).forEach {
                androidx.core.view.ViewCompat.getWindowInsetsController(it.window.decorView)
                    ?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
    }

    @Test fun rapidImageVideoMusicThemeAndSkinChangesRemainUsableAcrossForegroundTransitions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = ThemeRepository(context)
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        compose.runOnUiThread { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        runtimeOwner = owner
        loadThemeFixture()
        runBlocking { repository.edit(mapOf("isVideoBackgroundEnabled" to true,"isMusicEnabled" to true,
            "isAutoPlayEnabled" to true,"isLoopingEnabled" to true,"musicVolume" to 0f)) }
        page.value = "media-runtime"
        render()
        compose.waitUntil(15_000) { ThemeMusic.playing && mediaPhoto != null }
        compose.waitUntil(10_000) { hasVideoFrame() }
        capture("video-playing")
        repeat(24) { index ->
            if (index % 6 == 0) loadThemeFixture()
            runBlocking { repository.edit(mapOf("isBackgroundEnabled" to (index % 5 != 0),
                "isVideoBackgroundEnabled" to (index % 2 == 0),"isMusicEnabled" to (index % 3 != 0),
                "isAutoPlayEnabled" to true,"isLoopingEnabled" to true,"musicVolume" to 0f)) }
            compose.runOnUiThread {
                skin.value = if (index % 2 == 0) UiMode.Miuix else UiMode.Material
                owner.registry.currentState = if (index % 4 == 0) androidx.lifecycle.Lifecycle.State.CREATED else androidx.lifecycle.Lifecycle.State.RESUMED
            }
            android.os.SystemClock.sleep(20)
        }
        compose.runOnUiThread { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        runBlocking { repository.edit(mapOf("isBackgroundEnabled" to true,"isVideoBackgroundEnabled" to true,
            "isMusicEnabled" to true,"isAutoPlayEnabled" to true)) }
        compose.waitUntil(15_000) { ThemeMusic.playing && mediaPhoto?.file?.parentFile?.parentFile == ThemeRuntime.generation(context) }
        compose.waitUntil(10_000) { hasVideoFrame() }
        compose.runOnUiThread { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        compose.waitUntil(5_000) { !ThemeMusic.playing && (mediaPhoto?.drawable as? android.graphics.drawable.AnimatedImageDrawable)?.isRunning == false }
        compose.runOnUiThread { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.waitUntil(10_000) { ThemeMusic.playing && (mediaPhoto?.drawable as? android.graphics.drawable.AnimatedImageDrawable)?.isRunning == true }
        runBlocking { repository.reset() }
        compose.waitUntil(5_000) { !ThemeMusic.playing && mediaPhoto == null }
        capture("rapid-switch-reset")
    }

    private fun hasVideoFrame(): Boolean {
        var visible = false
        compose.runOnUiThread {
            val activities = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
            fun find(view: android.view.View): android.view.TextureView? {
                if (view is android.view.TextureView) return view
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                return null
            }
            val texture = activities.firstNotNullOfOrNull { find(it.window.decorView) }
            texture?.bitmap?.let { bitmap ->
                visible = bitmap.width > 0 && bitmap.height > 0 && bitmap.getPixel(bitmap.width/2,bitmap.height/2) != android.graphics.Color.BLACK
                bitmap.recycle()
            }
        }
        return visible
    }

    private fun loadThemeFixture(name: String = "media") {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir,"presentation-media.fpt")
        instrumentation.context.assets.open("theme/$name.fpt").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        runBlocking { ThemeRepository(context).apply(ThemeRepository(context).prepare(android.net.Uri.fromFile(fixture))) }
        fixture.delete()
    }

    private fun homeState() = HomeUiState(
        kernelVersion=KernelVersion(6,1,0),ksuVersion=12345,managerUAPIVersion=1,kernelUAPIVersion=1,
        lkmMode=true,isLkmBundled=true,isManager=true,isManagerPrBuild=false,isKernelPrBuild=false,
        requiresNewKernel=false,requiresNewManager=false,isRootAvailable=true,isSafeMode=false,isLateLoadMode=false,
        checkUpdateEnabled=false,latestVersionInfo=LatestVersionInfo(),currentManagerVersionCode=50008,
        systemInfo=SystemInfo("Theme fixture kernel","Theme fixture manager","Theme fixture device","fixture/device:1/test","Enforcing",2))

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun render() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.endsWith(".themetest"))
        val orientation = InstrumentationRegistry.getArguments().getString("themeUiOrientation")
        compose.runOnUiThread {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).forEach {
                    it.requestedOrientation = when (orientation) {
                        "portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                    }
                }
        }
        compose.setContent {
            CompositionLocalProvider(LocalUiMode provides skin.value,
                LocalColorMode provides if (dark) ColorMode.DARK.value else ColorMode.LIGHT.value,
                LocalMainPagerState provides rememberMainPagerState(rememberPagerState(pageCount={4})),
                LocalThemeHomeCounts provides counts.value,
                LocalNavigator provides rememberNavigator(Route.Settings)) {
                CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides
                    (runtimeOwner ?: androidx.lifecycle.compose.LocalLifecycleOwner.current)) {
                com.maplesu.core.ui.util.WallpaperPrefs.observe()
                val settings = if (importedAppearance) com.maplesu.core.ui.theme.ThemeController.getAppSettings()
                    .copy(colorMode=if (dark) ColorMode.DARK else ColorMode.LIGHT)
                else AppSettings(if (dark) ColorMode.DARK else ColorMode.LIGHT,
                    0xff6750a4.toInt(),PaletteStyle.TonalSpot,ColorSpec.SpecVersion.SPEC_2025)
                KernelSUTheme(settings,skin.value) {
                    val style = LocalThemeWallpaperStyle.current
                    androidx.compose.runtime.SideEffect { renderedWallpaperStyle = style }
                    ThemeOperationsHost {
                        operations = LocalThemeOperations.current
                        backgroundSelection = rememberThemeBackgroundSelection()
                        ThemeBackground(0,legacy={ PresentationPage() },content={ PresentationPage() })
                    }
                }
                }
            }
        }
        compose.waitForIdle()
        if (orientation != null) compose.waitUntil(5_000) {
            context.resources.configuration.orientation == if (orientation == "portrait")
                android.content.res.Configuration.ORIENTATION_PORTRAIT else android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }
    }

    @androidx.compose.runtime.Composable
    private fun PresentationPage() {
                        when (page.value) {
                            "media-runtime" -> {
                                ThemeMediaHost()
                                val photo = rememberThemePhoto(ThemeRuntime.file(InstrumentationRegistry.getInstrumentation().targetContext,"title_image"))
                                androidx.compose.runtime.SideEffect { mediaPhoto = photo }
                                ThemePhotoImage(photo,Modifier.height(120.dp))
                            }
                            "home" -> when (skin.value) {
                                UiMode.Miuix -> HomePagerMiuix(homeState().copy(isRootAvailable=rootReady.value),HomeActions({},{}),0.dp)
                                UiMode.Material -> HomePagerMaterial(homeState().copy(isRootAvailable=rootReady.value),HomeActions({},{}),0.dp)
                            }
                            "about" -> when (skin.value) {
                                UiMode.Miuix -> top.yukonga.miuix.kmp.basic.Scaffold { _ ->
                                    AboutMapleDialogMiuix(aboutVisible.value,aboutShowsModes.value) { aboutVisible.value = false }
                                }
                                UiMode.Material -> if (aboutVisible.value)
                                    AboutMapleDialogMaterial(aboutShowsModes.value) { aboutVisible.value = false }
                            }
                            "font" -> ThemeFontScreen()
                            "appearance" -> ThemePage(text(R.string.ui_d_appearance_title),{}) { padding ->
                                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
                                    AppearanceSettingsBody(SettingsUiState(uiMode=skin.value.value),SettingsScreenActions(
                                        onSetCheckUpdate={},onSetCheckModuleUpdate={},onOpenTheme={},onOpenAppearance={},
                                        onOpenImages={},onSetUiModeIndex={ skin.value=UiMode.entries[it] },
                                        onSetEnableWebDebugging={},onSetUseSoftReboot={},onOpenAbout={}))
                                }
                            }
                            "layout" -> ThemeLayoutScreen()
                            "media" -> ThemeMediaScreen()
                            "local" -> ThemeStoreScreen(localOnly=true)
                            "store" -> ThemeStoreScreen(storeViewModel=store!!)
                            "fixture-local" -> ThemeStoreScreen(localOnly=true,storeViewModel=store!!)
                            else -> WallpaperScreen()
                        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // Native dialog windows also animate outside Compose's test clock.
        android.os.SystemClock.sleep(350)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = evidenceDirectory()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val width = bitmap.width
        val height = bitmap.height
        File(directory,"${initialSkin.name}-${if(dark) "dark" else "light"}-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
        }
        bitmap.recycle()
        File(directory,"${initialSkin.name}-${if(dark) "dark" else "light"}-$name.json")
            .writeText(org.json.JSONObject().put("initialSkin",initialSkin.name).put("activeSkin",skin.value.name)
                .put("capturedAtEpochMs",System.currentTimeMillis())
                .put("dark",dark).put("capture",name).put("width",width).put("height",height)
                .put("orientation",instrumentation.targetContext.resources.configuration.orientation).toString(2))
    }

    private fun diagnostic(name: String) {
        val directory = evidenceDirectory()
        File(directory,"${initialSkin.name}-${if (dark) "dark" else "light"}-$name.txt").writeText(
            "store=${store?.state?.value}\noperations=${operations.state.value}\nmerged:\n" +
                compose.onAllNodes(isRoot()).printToString() + "\nunmerged:\n" + compose.onAllNodes(isRoot(),useUnmergedTree=true).printToString())
    }

    private fun evidenceDirectory(): File {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"theme-ui-evidence")
        val run = InstrumentationRegistry.getArguments().getString("themeEvidenceRun")
        return (if (run == null) root else File(root,run)).apply { mkdirs() }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}-dark={1}") fun modes() = listOf(
            arrayOf<Any>(UiMode.Miuix,false),arrayOf<Any>(UiMode.Miuix,true),
            arrayOf<Any>(UiMode.Material,false),arrayOf<Any>(UiMode.Material,true))
    }
}
