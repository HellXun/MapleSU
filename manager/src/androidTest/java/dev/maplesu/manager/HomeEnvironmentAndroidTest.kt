package dev.maplesu.manager

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maplesu.core.R
import com.maplesu.core.ui.MainActivity
import com.maplesu.core.ui.screen.home.HomeUiState
import com.maplesu.core.ui.util.AppearancePreferences
import com.maplesu.core.ui.viewmodel.HomeViewModel
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in startup/refresh; root expectation is explicit. No install is invoked. */
@RunWith(AndroidJUnit4::class)
class HomeEnvironmentAndroidTest {
    @Test fun materialHomeEndsCheckingAndRetrySettles() = verify("material")
    @Test fun miuixHomeEndsCheckingAndRetrySettles() = verify("miuix")

    @Test fun coldUltraHomeShowsVersionWithoutLeavingTab() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("verifyColdUltraHome") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = requireNotNull(arguments.getString("expectUltraVersion"))
        val label = "${context.getString(R.string.work_mode_lite)} $expected"
        val start = SystemClock.uptimeMillis()
        ActivityScenario.launch(MainActivity::class.java).use {
            val deadline = start + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (visibleTexts().any { it.contains(label) }) {
                    InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                        putLong("coldHomeVersionMillis", SystemClock.uptimeMillis() - start)
                    })
                    return
                }
                SystemClock.sleep(50)
            }
            fail("Cold home did not display $label after mode detection; no tab change was made")
        }
    }

    private fun verify(theme: String) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("verifyUnrootedHomeEnvironment") == "true" ||
            arguments.getString("verifyHomeEnvironment") == "true")
        val expectRoot = arguments.getString("expectHomeRoot") == "true"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val appearance = AppearancePreferences.prefs(context)
        val oldTheme = appearance.getString("ui_mode", null)
        val oldHideStatusText = appearance.all["status_decoration_hide_text"] as? Boolean
        val oldAnnouncement = prefs.getBoolean("announcement_never_show", false)
        appearance.edit().putString("ui_mode", theme)
            .putBoolean("status_decoration_hide_text", false).commit()
        prefs.edit().putBoolean("announcement_never_show", true).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: HomeViewModel
                scenario.onActivity { model = ViewModelProvider(it)[HomeViewModel::class.java] }
                val settled = awaitSettled(model, expectRoot)
                reportState("$theme/start", model, settled)
                assertRootExpectation(settled, expectRoot)
                assertModeExpectation(context, settled)
                assertFalse("Visible home kept its checking title", visibleTexts().any {
                    it == context.getString(R.string.home_status_checking)
                })
                val verifyUltraRefresh = arguments.getString("verifyHomeEnvironment") == "true" &&
                    expectRoot && ModeRuntime.active == WorkMode.LITE
                val ultraStatus = if (verifyUltraRefresh) VisibleUltraStatus(context) else null
                ultraStatus?.awaitInstalledCard()
                assertHomeModelShared(scenario, model)
                runBlocking {
                    // Presence is checked on IO before checking becomes true. Subscribe first
                    // so even a fast true/false publication is observed without polling races.
                    val started = CompletableDeferred<Unit>()
                    val completed = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                        model.uiState.dropWhile { !it.environmentChecking }
                            .onEach { if (it.environmentChecking) started.complete(Unit) }
                            .first { !it.environmentChecking }
                    }
                    try {
                        withTimeout(20_000) {
                            while (!completed.isCompleted) {
                                // Startup probes may still own the publish mutex after the
                                // first settled state. Refresh coalesces while they run;
                                // wait for an accepted refresh before expecting its result.
                                if (!started.isCompleted) scenario.onActivity { model.refresh() }
                                ultraStatus?.sample()
                                delay(50)
                            }
                            completed.await()
                        }
                    } finally {
                        completed.cancel()
                    }
                }
                // Observe rendered semantics throughout the real refresh, not only the model's final state.
                val refreshed = awaitSettled(model, expectRoot) { ultraStatus?.sample() }
                reportState("$theme/refresh", model, refreshed)
                assertRootExpectation(refreshed, expectRoot)
                assertModeExpectation(context, refreshed)
                if (ultraStatus != null) {
                    val deadline = SystemClock.uptimeMillis() + 1_000
                    while (SystemClock.uptimeMillis() < deadline) {
                        ultraStatus.sample()
                        SystemClock.sleep(50)
                    }
                    ultraStatus.awaitInstalledCard()
                    assertTrue("Ultra refresh had too few visible UI observations", ultraStatus.samples >= 2)
                }
            }
        } finally {
            appearance.edit().apply {
                if (oldTheme == null) remove("ui_mode") else putString("ui_mode", oldTheme)
                if (oldHideStatusText == null) remove("status_decoration_hide_text")
                else putBoolean("status_decoration_hide_text", oldHideStatusText)
            }.commit()
            prefs.edit().putBoolean("announcement_never_show", oldAnnouncement).commit()
        }
    }

    private fun assertHomeModelShared(scenario: ActivityScenario<MainActivity>, model: HomeViewModel) {
        scenario.onActivity { activity ->
            // NavDisplay creates its own owners. Check the actual populated stores so a
            // test-created Activity model cannot pass while the visible page stays stale.
            var inspected = false
            for (key in activity.viewModelStore.keys()) {
                val holder = activity.viewModelStore[key] ?: continue
                if (holder.javaClass.name != "top.yukonga.miuix.kmp.nav.state.NavEntryViewModelStores") continue
                val field = holder.javaClass.getDeclaredField("stores").apply { isAccessible = true }
                val stores = field.get(holder) as Map<*, *>
                inspected = stores.isNotEmpty()
                for (store in stores.values.filterIsInstance<androidx.lifecycle.ViewModelStore>()) {
                    for (entryKey in store.keys()) {
                        val entryModel = store[entryKey]
                        if (entryModel is HomeViewModel) {
                            assertSame("Home page and Activity must share their environment model", model, entryModel)
                        }
                    }
                }
            }
            assertTrue("Visible navigation stores were not inspected", inspected)
        }
    }

    private fun awaitSettled(model: HomeViewModel, expectRoot: Boolean, observe: () -> Unit = {}): HomeUiState {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            observe()
            val state = model.uiState.value
            if (!state.environmentChecking && !state.showLiteChecking &&
                (!expectRoot || (state.isRootAvailable && state.environmentFailure == null))) return state
            SystemClock.sleep(50)
        }
        throw AssertionError("Home environment check exceeded the 20-second UI budget: ${model.uiState.value.environmentFailure}")
    }

    private inner class VisibleUltraStatus(context: Context) {
        private val mode = context.getString(R.string.work_mode_lite)
        private val checking = context.getString(R.string.home_status_checking)
        private val failed = context.getString(R.string.home_environment_check_failed)
        // Match the localized version-line format without assuming a particular installed version.
        private val versionMarker = "__HOME_TEST_VERSION__"
        private val versionTemplate = context.getString(R.string.home_working_version, versionMarker)
        private val installedVersion = Regex(Regex.escape(versionTemplate.substringBefore(versionMarker)) +
            "[^\\n]+" + Regex.escape(versionTemplate.substringAfter(versionMarker)))
        var samples: Int = 0
            private set

        fun sample(): Boolean {
            val labels = visibleTexts()
            // The wordmark is drawn on a canvas. Observe the rendered installed
            // version line instead of expecting its pixels in accessibility text.
            if (labels.none { it.contains(mode) && installedVersion.containsMatchIn(it) }) return false
            assertFalse("Installed Ultra card was replaced by checking", checking in labels)
            assertFalse("Installed Ultra card was replaced by a failure", failed in labels)
            samples++
            return true
        }

        fun awaitInstalledCard() {
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (sample()) return
                SystemClock.sleep(50)
            }
            throw AssertionError("Installed Ultra main-card version line was not visible")
        }
    }

    private fun assertRootExpectation(state: HomeUiState, expectRoot: Boolean) {
        InstrumentationRegistry.getArguments().getString("expectHomeSELinux")?.let { expected ->
            assertEquals("Home SELinux differs from the actual root query", expected, state.systemInfo.selinuxStatus)
        }
        if (expectRoot) {
            assertTrue("Root-ready fixture did not report its actual usable root", state.isRootAvailable)
            assertNull("Root-ready environment still has a check failure", state.environmentFailure)
        } else {
            assertFalse("No-root fixture was reported as root-ready", state.isRootAvailable)
            assertNull("No-root fixture was reported as a running backend", state.ksuVersion)
        }
    }

    private fun assertModeExpectation(context: Context, state: HomeUiState) {
        val arguments = InstrumentationRegistry.getArguments()
        arguments.getString("expectHomeMode")?.let { expected ->
            val mode = requireNotNull(WorkMode.fromWire(expected))
            assertEquals("Backend and requested fixture disagree", mode, ModeRuntime.active)
            assertEquals("Home used an older mode than the backend", mode, state.environmentMode)
            assertTrue("Confirmed mode lost its installed card", state.showInstalledStatus)
            val ultraMode = context.getString(R.string.work_mode_lite)
            val deadline = SystemClock.uptimeMillis() + 10_000
            var labels: List<String>
            do {
                labels = visibleTexts()
                if (context.getString(R.string.superuser) in labels &&
                    context.getString(R.string.settings) in labels &&
                    (labels.any { it.contains(ultraMode) }) == (mode == WorkMode.LITE)) break
                SystemClock.sleep(50)
            } while (SystemClock.uptimeMillis() < deadline)
            assertTrue("Installed mode lost the superuser navigation item", context.getString(R.string.superuser) in labels)
            assertTrue("Installed mode lost the settings navigation item", context.getString(R.string.settings) in labels)
            assertEquals("Rendered mode disagrees with the backend", mode == WorkMode.LITE,
                labels.any { it.contains(ultraMode) })
            assertFalse("A running mode offered Ultra installation", context.getString(R.string.ui_d_env_install_title) in labels)
        }
    }

    private fun reportState(stage: String, model: HomeViewModel, state: HomeUiState) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("homeStage", stage)
            putString("activeMode", ModeRuntime.active?.name)
            putString("homeMode", state.environmentMode?.name)
            putBoolean("homeRoot", state.isRootAvailable)
            putString("homeSELinux", state.systemInfo.selinuxStatus)
            putString("homeEntries", model.entryFacts.value.toString())
            putStringArrayList("visibleHomeText", ArrayList(visibleTexts()))
        })
    }

    private fun visibleTexts(): List<String> {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return emptyList()
        val texts = mutableListOf<String>()
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(root)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if (node.isVisibleToUser) {
                node.text?.toString()?.let(texts::add)
                node.contentDescription?.toString()?.let(texts::add)
            }
            repeat(node.childCount) { node.getChild(it)?.let(nodes::addLast) }
        }
        return texts
    }
}
