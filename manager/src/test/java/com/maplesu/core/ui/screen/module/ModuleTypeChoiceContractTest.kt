package com.maplesu.core.ui.screen.module

import dev.maplesu.manager.modules.ModuleType
import dev.maplesu.manager.modules.ModuleTypeEvidence
import dev.maplesu.manager.workmode.WorkMode
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ModuleTypeChoiceContractTest {
    private fun source(name: String): String {
        val path = "src/main/java/com/maplesu/core/ui/screen/flash/$name"
        return listOf(File(path), File("manager/$path")).first { it.isFile }.readText()
    }

    @Test fun ambiguousChoiceIsHandledBeforeTheDisabledInstallBranch() {
        val source = source("FlashScreen.kt")
        val choice = source.indexOf("model.requiresChoice ->")
        val disabled = source.indexOf("!model.installEnabled ->")
        assertTrue(choice >= 0 && disabled > choice)
        assertTrue(source.contains("request.result.await()"))
        assertFalse(source.contains("val choosePro ="))
    }

    @Test fun dismissalHasNoImplicitInstallationType() {
        val source = source("ModuleTypeChoiceDialog.kt")
        assertTrue(source.contains("mutableStateOf<String?>(null)"))
        assertTrue(source.contains("onDismiss = { request.result.complete(null) }"))
        assertTrue(source.contains("if (confirmEnabled) request.result.complete(selected)"))
        assertTrue(source.contains("confirmEnabled = confirmEnabled"))
    }

    @Test fun eachThemeUsesItsOwnControlsAndMapleSuNaming() {
        val source = source("ModuleTypeChoiceDialog.kt")
        assertTrue(source.contains("UiMode.Material -> SegmentedColumn"))
        assertTrue(source.contains("UiMode.Miuix -> Column"))
        assertTrue(source.contains("HiddenFlowDialog("))
        assertTrue(source.contains("R.string.module_type_maplesu"))
        assertTrue(source.contains("Modifier.selectableGroup()"))
    }

    @Test fun ultraOffersBothExplicitTypes() {
        val evidence = ModuleTypeEvidence(ModuleType.AMBIGUOUS, listOf("fixture"))
        val options = ModuleTypeUi.dialogModel(evidence, WorkMode.LITE).choices
        assertEquals(setOf(ModuleType.MAPLESU, ModuleType.KERNELSU), options.filter {
            ModuleTypeUi.installEnabled(evidence, WorkMode.LITE, it)
        }.toSet())
        assertFalse(ModuleTypeUi.installEnabled(evidence, WorkMode.LITE, null))
    }
}
