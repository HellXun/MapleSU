package com.maplesu.core.ui.screen.home

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.maplesu.core.KernelVersion
import com.maplesu.core.data.repository.SettingsRepositoryImpl
import com.maplesu.core.ui.LocalUiMode
import com.maplesu.core.ui.UiMode
import com.maplesu.core.ui.theme.AppSettings
import com.maplesu.core.ui.theme.ColorMode
import com.maplesu.core.ui.theme.KernelSUTheme
import com.maplesu.core.ui.theme.LocalColorMode
import com.maplesu.core.ui.util.AppearancePreferences
import com.maplesu.core.ui.util.StatusDecoration
import com.maplesu.core.ui.util.module.LatestVersionInfo
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.math.abs

/** Exercises production cards without starting a backend; restores all touched appearance values. */
@RunWith(Parameterized::class)
class ComicStatusCardRenderTest(private val skin: UiMode, private val dark: Boolean,
    private val font: Float, private val large: Boolean) {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var prefs: SharedPreferences
    private lateinit var original: Map<String, *>
    private var originalImage: ByteArray? = null

    @Before fun prepare() {
        prefs = AppearancePreferences.prefs(context)
        original = prefs.all.toMap()
        originalImage = StatusDecoration.file(context).takeIf { it.isFile }?.readBytes()
        assertTrue(StatusDecoration.clear(context))
        SettingsRepositoryImpl().homeLargeCard = large
    }

    @After fun restore() {
        val image = StatusDecoration.file(context)
        originalImage?.let { image.writeBytes(it) } ?: image.delete()
        val editor = prefs.edit().clear()
        original.forEach { (key, value) -> when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Float -> editor.putFloat(key, value)
            is Long -> editor.putLong(key, value)
            is String -> editor.putString(key, value)
        } }
        assertTrue(editor.commit())
    }

    @Test fun checkmarkAndCustomArtworkKeepTheirLayoutAndClickAction() {
        var installs = 0
        val state = HomeUiState(KernelVersion(6, 1, 31), 32680, 1, 1, true, false, true,
            isManagerPrBuild = false, isKernelPrBuild = false, requiresNewKernel = false,
            requiresNewManager = false, isRootAvailable = true, isSafeMode = false,
             checkUpdateEnabled = false, latestVersionInfo = LatestVersionInfo(),
            currentManagerVersionCode = 50008, systemInfo = SystemInfo("6.1.31", "5.0.8", "Device", "Fingerprint", "Enforcing", 2))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalUiMode provides skin,
                LocalColorMode provides if (dark) ColorMode.DARK.value else ColorMode.LIGHT.value,
                LocalDensity provides Density(density.density, font)) {
                KernelSUTheme(AppSettings(if (dark) ColorMode.DARK else ColorMode.LIGHT,
                    0xff6750a4.toInt(), PaletteStyle.TonalSpot, ColorSpec.SpecVersion.SPEC_2025), skin) {
                    Box(Modifier.width(320.dp).testTag("statusCard")) {
                        val actions = HomeActions({ installs++ }, {})
                        if (skin == UiMode.Miuix) StatusCard(state, actions, modifier = Modifier)
                        else MaterialStatusCard(state, actions)
                    }
                }
            }
        }
        compose.onNodeWithText(STATUS_CARD_TEXT).assertIsDisplayed()
        compose.onNodeWithText("LKM").assertIsDisplayed()
        val bounds = compose.onNodeWithTag("statusCard").getUnclippedBoundsInRoot()
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        if (skin == UiMode.Miuix && large) assertTrue(abs((width - height).value) < 1f)
        else assertTrue("Compact cards stay shorter than their width", height < width)
        capture("check")
        compose.onNodeWithText(STATUS_CARD_TEXT).performClick()
        compose.runOnIdle { assertEquals(1, installs) }

        val sample = File(context.cacheDir, "comic-card-test.png")
        try {
            val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(80, 130, 210))
            sample.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            assertTrue(StatusDecoration.save(context, Uri.fromFile(sample)))
            assertNotNull(StatusDecoration.load(context, 320))
            prefs.edit().putBoolean("status_decoration_hide_text", true).apply()
            compose.waitUntil(5_000) { compose.onAllNodesWithText(STATUS_CARD_TEXT).fetchSemanticsNodes().isEmpty() }
            capture("image-only")
            prefs.edit().putBoolean("status_decoration_hide_text", false).apply()
            compose.onNodeWithText(STATUS_CARD_TEXT).assertIsDisplayed()
            capture("custom")
            assertEquals(large, SettingsRepositoryImpl().homeLargeCard)
            assertTrue(StatusDecoration.clear(context))
            assertNull(StatusDecoration.load(context, 320))
            compose.onNodeWithText(STATUS_CARD_TEXT).assertIsDisplayed()
        } finally { sample.delete() }
    }

    private fun capture(name: String) {
        val directory = File(context.getExternalFilesDir(null), "comic-preset-ui").apply { mkdirs() }
        val bitmap = compose.onNodeWithTag("statusCard").captureToImage().asAndroidBitmap()
        File(directory, "${skin.name}-dark$dark-font$font-large$large-$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-dark{1}-font{2}-large{3}")
        fun cases(): Collection<Array<Any>> = UiMode.entries.flatMap { skin ->
            listOf(false, true).flatMap { dark -> listOf(1f, 1.8f).flatMap { font ->
                (if (skin == UiMode.Miuix) listOf(false, true) else listOf(false)).map { large ->
                    arrayOf<Any>(skin, dark, font, large)
                }
            } }
        }
    }
}
