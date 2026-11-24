package com.maplesu.core.theme

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.platform.LocalContext
import androidx.test.platform.app.InstrumentationRegistry
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.maplesu.core.KernelVersion
import com.maplesu.core.ui.LocalUiMode
import com.maplesu.core.ui.UiMode
import com.maplesu.core.ui.navigation3.LocalNavigator
import com.maplesu.core.ui.navigation3.Route
import com.maplesu.core.ui.navigation3.rememberNavigator
import com.maplesu.core.ui.screen.home.*
import com.maplesu.core.ui.theme.*
import com.maplesu.core.ui.util.AppearancePreferences
import com.maplesu.core.ui.util.AppearancePrefsPolicy
import com.maplesu.core.ui.util.LocalPreparedStatusDecoration
import com.maplesu.core.ui.util.module.LatestVersionInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Startup readiness is separate from root detection and from the first AndroidView draw. */
class ThemeStartupDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allResourcesMustCommitBeforeStartupIsReleased() {
        var fontReady by mutableStateOf(false)
        var imageReady by mutableStateOf(false)
        var released = 0
        compose.setContent {
            ThemeStartupHost(onReady = { released++ }) {
                ThemeStartupResource(fontReady)
                ThemeStartupResource(imageReady)
            }
        }
        compose.runOnIdle { assertEquals(0, released); fontReady = true }
        compose.runOnIdle { assertEquals(0, released); imageReady = true }
        compose.waitUntil(5_000) { released == 1 }
        // This is a one-shot launch barrier, not a blocker for later theme edits.
        compose.runOnIdle { fontReady = false; imageReady = false }
        compose.runOnIdle { assertEquals(1, released) }
    }

    @Test fun disposedResourcesDoNotKeepTheSplashOpen() {
        var present by mutableStateOf(true)
        var released = false
        compose.setContent {
            ThemeStartupHost(onReady = { released = true }) {
                if (present) ThemeStartupResource(false)
            }
        }
        compose.runOnIdle { assertFalse(released); present = false }
        compose.waitUntil(5_000) { released }
    }

    @Test fun aLateResourceCancelsThePendingRelease() {
        var firstReady by mutableStateOf(false)
        var latePresent by mutableStateOf(false)
        var lateReady by mutableStateOf(false)
        var released = false
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ThemeStartupHost(onReady = { released = true }) {
                ThemeStartupResource(firstReady)
                if (latePresent) ThemeStartupResource(lateReady)
            }
        }
        compose.runOnIdle { firstReady = true }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { latePresent = true }
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
        compose.runOnIdle { assertFalse(released); lateReady = true }
        compose.waitUntil(5_000) { compose.mainClock.advanceTimeByFrame(); released }
    }

    @Test fun aRealImageIsDecodedBeforeTheReadyCallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = File.createTempFile("startup-", ".png", context.cacheDir)
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(android.graphics.Color.BLUE)
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            var photo: ThemePhoto? = null
            var photoAtRelease: ThemePhoto? = null
            var released = false
            compose.setContent {
                ThemeStartupHost(onReady = { photoAtRelease = photo; released = true }) {
                    val loaded = rememberThemePhoto(image)
                    SideEffect { photo = loaded }
                }
            }
            compose.waitUntil(5_000) { released }
            compose.runOnIdle { assertEquals(image, photoAtRelease?.file) }
        } finally { image.delete() }
    }

    @Test fun aDamagedImageCompletesStartupWithAnExplicitFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = File.createTempFile("startup-broken-", ".png", context.cacheDir)
            .apply { writeText("not an image") }
        try {
            var released = false
            var photo: ThemePhoto? = null
            compose.setContent {
                ThemeStartupHost(onReady = { released = true }) {
                    val loaded = rememberThemePhoto(image)
                    SideEffect { photo = loaded }
                }
            }
            compose.waitUntil(5_000) { released }
            compose.runOnIdle { assertNull(photo) }
        } finally { image.delete() }
    }

    @Test fun homeAppearanceIsPreparedWhileTheEnvironmentIsStillCheckingInBothSkinsAndAllLayouts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(context.packageName.endsWith(".themetest"))
        val repository = ThemeRepository(context)
        runBlocking { repository.reset() }
        val fixture = File(context.cacheDir, "startup-home.fpt")
        instrumentation.context.assets.open("theme/media.fpt").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        runBlocking { repository.apply(repository.prepare(android.net.Uri.fromFile(fixture))) }
        fixture.delete()
        AppearancePreferences.prefs(context).edit()
            .putString(AppearancePrefsPolicy.DECORATION_SOURCE, AppearancePrefsPolicy.DECORATION_COMIC).commit()
        val variants = UiMode.entries.flatMap { mode -> ThemeHomeLayout.entries.map { mode to it } }
        runBlocking { repository.homeLayout(variants.first().second) }
        var variant by mutableIntStateOf(0)
        var released = 0
        var art: StatusThemeArt? = null
        var decoration: androidx.compose.ui.graphics.ImageBitmap? = null
        var readyArt: StatusThemeArt? = null
        var readyDecoration: androidx.compose.ui.graphics.ImageBitmap? = null
        val state = HomeUiState(kernelVersion=KernelVersion(6,1,0),ksuVersion=null,
            managerUAPIVersion=1,kernelUAPIVersion=null,lkmMode=null,isLkmBundled=true,
            isManager=false,isManagerPrBuild=false,isKernelPrBuild=false,requiresNewKernel=false,
            requiresNewManager=false,isRootAvailable=false,isSafeMode=false,
            checkUpdateEnabled=false,latestVersionInfo=LatestVersionInfo(),currentManagerVersionCode=50100,
            systemInfo=SystemInfo("fixture","fixture","fixture","fixture","Enforcing",2),environmentChecking=true)
        assertTrue(state.showEnvironmentStatusCard)
        compose.setContent {
            val mode = variants[variant].first
            key(variant) {
                CompositionLocalProvider(LocalContext provides context, LocalUiMode provides mode,
                    LocalColorMode provides ColorMode.LIGHT.value,
                    LocalNavigator provides rememberNavigator(Route.Home)) {
                    ThemeStartupHost(onReady = {
                        readyArt = art; readyDecoration = decoration; released++
                    }) {
                        KernelSUTheme(AppSettings(ColorMode.LIGHT,0xff6750a4.toInt(),PaletteStyle.TonalSpot,
                            ColorSpec.SpecVersion.SPEC_2025), mode) {
                            ThemeHomeStatus(state,HomeActions({},{})) {
                                val preparedArt = LocalPreparedStatusThemeArt.current
                                val preparedDecoration = LocalPreparedStatusDecoration.current
                                SideEffect { art = preparedArt; decoration = preparedDecoration?.bitmap }
                            }
                        }
                    }
                }
            }
        }
        try {
            variants.forEachIndexed { index, (_, layout) ->
                runBlocking { repository.homeLayout(layout) }
                if (index != 0) compose.runOnIdle { variant = index }
                compose.waitUntil(10_000) { released == index + 1 }
                compose.runOnIdle {
                    assertEquals("focus_card_kernel_bg.png", readyArt?.photo?.file?.name)
                    assertNotNull("Comic was still showing its default check at release", readyDecoration)
                    assertTrue(state.showEnvironmentStatusCard)
                }
            }
        } finally { runBlocking { repository.reset() } }
    }

    @Test fun aRealFinaThemeReleasesStartupWithItsFontWallpaperAndCardAlreadySelected() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("themeRealAppearance") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.endsWith(".themetest"))
        val input = File(context.getExternalFilesDir(null),"real-theme-inputs/store-card-live.fpt")
        assertTrue("Provide the unmodified Fina theme package",input.isFile)
        val repository = ThemeRepository(context)
        runBlocking { repository.reset(); repository.apply(repository.prepare(android.net.Uri.fromFile(input))) }
        val variants = UiMode.entries.flatMap { mode -> ThemeHomeLayout.entries.map { mode to it } }
        runBlocking { repository.homeLayout(variants.first().second) }
        var variant by mutableIntStateOf(0)
        var released = 0
        var card: ThemePhoto? = null
        var wallpaper: ThemePhoto? = null
        var family: androidx.compose.ui.text.font.FontFamily? = null
        var atRelease = Triple<ThemePhoto?, ThemePhoto?, androidx.compose.ui.text.font.FontFamily?>(null,null,null)
        compose.setContent {
            val mode = variants[variant].first
            key(variant) {
                CompositionLocalProvider(LocalContext provides context,LocalUiMode provides mode,
                    LocalColorMode provides ColorMode.LIGHT.value,LocalNavigator provides rememberNavigator(Route.Home)) {
                    ThemeStartupHost(onReady = {
                        atRelease = Triple(card,wallpaper,family); released++
                    }) {
                        KernelSUTheme(ThemeController.getAppSettings(),mode) {
                            val art = statusThemeArt()
                            val frame = LocalThemeWallpaperFrame.current
                            val font = when (mode) {
                                UiMode.Miuix -> top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.body1.fontFamily
                                UiMode.Material -> androidx.compose.material3.MaterialTheme.typography.bodyLarge.fontFamily
                            }
                            SideEffect { card = art.photo; wallpaper = frame?.photo; family = font }
                        }
                    }
                }
            }
        }
        try {
            variants.forEachIndexed { index, (_, layout) ->
                runBlocking { repository.homeLayout(layout) }
                if (index != 0) compose.runOnIdle { variant = index }
                compose.waitUntil(15_000) { released == index + 1 }
                compose.runOnIdle {
                    assertEquals("grid_working_card_background.jpg",atRelease.first?.file?.name)
                    assertEquals("background.jpg",atRelease.second?.file?.name)
                    assertNotNull("Custom font had not reached the native typography",atRelease.third)
                    assertNotEquals(androidx.compose.ui.text.font.FontFamily.Default,atRelease.third)
                }
            }
        } finally { runBlocking { repository.reset() } }
    }
}
