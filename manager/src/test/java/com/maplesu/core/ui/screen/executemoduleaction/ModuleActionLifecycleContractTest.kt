package com.maplesu.core.ui.screen.executemoduleaction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source wiring contracts, not Android lifecycle instrumentation. */
class ModuleActionLifecycleContractTest {
    private val root = File("src/main/java/com/maplesu/core/ui/screen/executemoduleaction")

    @Test fun aViewModelOwnsTheTaskInsteadOfTheSkinComposition() {
        val screen = File(root, "ExecuteModuleActionScreen.kt").readText()
        assertTrue(screen.contains("viewModel<ExecuteModuleActionViewModel>()"))
        assertTrue(screen.contains("collectAsStateWithLifecycle()"))
        assertFalse(screen.contains("ExecuteModuleActionEffect("))
    }

    @Test fun thereIsOneReturnHandlerWithFeedbackRatherThanAnEmptyHandlerInEachSkin() {
        val screen = File(root, "ExecuteModuleActionScreen.kt").readText()
        assertTrue(screen.contains("NavigationBackHandler("))
        assertTrue(screen.contains("onBackCompleted = requestBack"))
        assertTrue(screen.contains("onBack = requestBack"))
        assertTrue(screen.contains("module_action_wait"))
        for (name in listOf("ExecuteModuleActionMiuix.kt", "ExecuteModuleActionMaterial.kt")) {
            assertFalse(File(root, name).readText().contains("BackHandler("))
        }
    }

    @Test fun aRestoredRunningActionDoesNotRunTheCommandAgain() {
        val model = File(root, "ExecuteModuleActionViewModel.kt").readText()
        assertTrue(model.contains("SavedStateHandle"))
        assertTrue(model.contains("ModuleActionStart.RESTORED_RUNNING"))
        assertTrue(model.contains("messages.interrupted"))
        assertTrue(model.contains("messages.restoredComplete"))
        val screen = File(root, "ExecuteModuleActionScreen.kt").readText()
        for (message in listOf("module_action_failed_message", "module_action_interrupted",
            "module_action_restored_complete")) assertTrue(screen.contains(message))
        assertFalse(model.contains("if (text.isNotEmpty())"))
    }

    @Test fun logWritesAreOffMainAndReadFromASnapshot() {
        val utils = File(root, "ExecuteModuleActionUtils.kt").readText()
        assertTrue(utils.contains("logSnapshot: () -> String"))
        assertTrue(utils.contains("withContext(Dispatchers.IO)"))
        assertTrue(utils.contains("file.writeText(snapshot)"))
    }

    @Test fun stdoutStderrAndExportUseTheSameWholeRecordLock() {
        val model = File(root, "ExecuteModuleActionViewModel.kt").readText()
        val flat = model.replace("\r\n", "\n")
        assertTrue(flat.contains("@Synchronized\n    fun logSnapshot()"))
        assertTrue(flat.contains("@Synchronized\n    private fun append(line: String)"))
    }
}
