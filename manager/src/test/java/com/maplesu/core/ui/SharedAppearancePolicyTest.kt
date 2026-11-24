package com.maplesu.core.ui

import com.maplesu.core.ui.util.AppearancePreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SharedAppearancePolicyTest {
    @Test fun visualsAreSharedAcrossBackends() {
        for (key in listOf("ui_mode", "color_mode", "key_color", "snow_enabled", "wallpaper_path", "enable_blur")) {
            assertTrue(key, key in AppearancePreferences.keys)
        }
    }

    @Test fun functionalAndSensitivePreferencesStayOut() {
        for (key in listOf("intent_token", "stealth_code", "web_terminal_enabled", "enable_web_debugging", "soft_reboot", "su_compat_mode")) {
            assertFalse(key, key in AppearancePreferences.keys)
        }
    }

    @Test fun migrationKeepsLegacyFilesAndExistingSharedChoices() {
        val source = File("src/main/java/com/maplesu/core/ui/util/AppearancePreferences.kt").readText()
        assertTrue(source.contains("shared.contains(key)"))
        assertTrue(source.contains("migrated_v1"))
        assertFalse(source.contains(".clear()"))
        assertFalse(source.contains(".remove("))
    }

    @Test fun unresolvedSourceDoesNotPreemptResolvedModeAppearance() {
        val first = AppearancePreferences.migrationPlan(
            existing = emptyMap<String, Any>(),
            legacy = mapOf("ui_mode" to "miuix", "intent_token" to "secret"),
            modeResolved = false,
        )
        assertFalse(first.complete)
        assertTrue(first.values.isEmpty())
        val resolved = AppearancePreferences.migrationPlan(
            existing = first.values,
            legacy = mapOf("ui_mode" to "material", "color_mode" to 2, "intent_token" to "x"),
            modeResolved = true,
        )
        assertTrue(resolved.complete)
        assertEquals(mapOf("ui_mode" to "material", "color_mode" to 2), resolved.values)
    }

    @Test fun userChoicesWinWhileDelayedLegacySourceIsBeingRead() {
        val legacy = mapOf("ui_mode" to "material", "key_color" to 0x445566, "wallpaper_kind" to "image")
        val current = mapOf("ui_mode" to "miuix", "key_color" to 0x112233, "wallpaper_kind" to "none")
        val plan = AppearancePreferences.migrationPlan(current, legacy, modeResolved = true)
        assertTrue(plan.complete)
        assertTrue(plan.values.isEmpty())
    }

    @Test fun prematureV1MarkerDoesNotBlockMissingResolvedValues() {
        val plan = AppearancePreferences.migrationPlan(
            existing = mapOf("migrated_v1" to true, "ui_mode" to "miuix"),
            legacy = mapOf("ui_mode" to "material", "color_style" to "Vibrant"),
            modeResolved = true,
        )
        assertEquals(mapOf("color_style" to "Vibrant"), plan.values)
        assertTrue(plan.complete)
    }

    @Test fun migrationAcceptsOnlySupportedVisualTypesAndIsIdempotent() {
        val source = mapOf("color_mode" to 2, "snow_enabled" to true,
            "page_scale" to 1.1f, "key_color" to 123L, "ui_mode" to "miuix",
            "wallpaper_path" to setOf("unsupported"), "root_key" to "secret")
        val first = AppearancePreferences.migrationPlan(emptyMap<String, Any>(), source, modeResolved = true)
        assertEquals(setOf("color_mode", "snow_enabled", "page_scale", "key_color", "ui_mode"), first.values.keys)
        assertTrue(AppearancePreferences.migrationPlan(first.values, source, modeResolved = true).values.isEmpty())
    }

    @Test fun resolvedImportIsConnectedToBootstrapAndExistingObservers() {
        val prefs = File("src/main/java/com/maplesu/core/ui/util/AppearancePreferences.kt").readText()
        assertTrue(prefs.contains("migrated_resolved_v2"))
        assertTrue(prefs.contains("synchronized(this) { cached }"))
        assertTrue(prefs.contains("Handler(Looper.getMainLooper()).post(complete)"))
        assertTrue(prefs.contains("importMissing(shared, legacy, modeResolved = true)"))
        val bootstrap = File("src/main/java/dev/maplesu/manager/workmode/boot/ModeBootstrap.kt").readText()
        assertTrue(bootstrap.contains("AppearancePreferences.onModeResolved(appContext)"))
        assertTrue(bootstrap.contains("if (outcome is BootstrapOutcome.Activated)"))
        val main = File("src/main/java/com/maplesu/core/ui/viewmodel/MainActivityViewModel.kt").readText()
        assertTrue(main.contains("prefs.registerOnSharedPreferenceChangeListener(listener)"))
        val wallpaper = File("src/main/java/com/maplesu/core/ui/util/WallpaperPrefs.kt").readText()
        assertTrue(wallpaper.contains("key in AppearancePreferences.keys"))
    }

    @Test fun firstRunAndDecorationHaveRealProductionEntryPoints() {
        val root = File("src/main/java/com/maplesu/core/ui/MainActivity.kt").readText()
        assertTrue(root.contains("SkinTransitionHost(skin)"))
        assertTrue(root.contains("AppearanceIntroScreen(uiMode"))
        assertTrue(root.contains("skin.appliedScene && uiState.trollRainEnabled"))
        val home = File("src/main/java/com/maplesu/core/ui/screen/home/HomeScreen.kt").readText()
        assertTrue(home.contains("rememberUltraWordmarkState("))
        assertTrue(home.contains("isCurrentPage && navigator.current()"))
    }
    @Test fun motionStopsWithLifecycleAndUsesCachedSprites() {
        val gravity = File("src/main/java/com/maplesu/core/ui/component/GravityField.kt").readText()
        assertTrue(gravity.contains("Lifecycle.State.RESUMED"))
        assertTrue(gravity.contains("rememberMotionEnabled()"))
        assertTrue(gravity.contains("LaunchedEffect(field, running)"))
        val leaves = File("src/main/java/com/maplesu/core/ui/component/Snowfall.kt").readText()
        assertTrue(leaves.contains("flakeCount: Int = 38"))
        assertTrue(leaves.contains("withContext(Dispatchers.Default) { makeMapleLeafSprites() }"))
        assertFalse(leaves.contains("renderSnowflake"))
    }

}
