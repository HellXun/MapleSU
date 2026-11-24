package com.maplesu.core.ui.viewmodel

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperuserPublicationRefreshTest {
    private fun source(name: String): String = listOf(File("src/main/java/$name"), File("manager/src/main/java/$name"))
        .first { it.isFile }.readText()

    @Test fun deploymentPublishesConflatedReadOnlyStatusChanges() {
        val deploy = source("dev/maplesu/manager/runtime/RuntimeDeployment.kt")
        assertTrue(deploy.contains("private val _status = MutableStateFlow(Status())"))
        assertTrue(deploy.contains("val statusChanges: StateFlow<Status> = _status.asStateFlow()"))
        assertTrue(deploy.contains("get() = _status.value"))
        assertTrue(deploy.contains("set(value) { _status.value = value }"))
    }

    @Test fun asyncListWritersShareThePublicationRefreshLock() {
        val vm = source("com/maplesu/core/ui/viewmodel/SuperUserViewModel.kt")
        val sort = vm.substringAfter("fun updateSortConfig(").substringBefore("private fun refilterVisibleApps")
        assertTrue(sort.contains("refreshMutex.withLock"))
        val filter = vm.substringAfter("private fun refilterVisibleApps").substringBefore("fun toggleShowSystemApps")
        assertTrue(filter.contains("refreshMutex.withLock"))
        assertTrue(vm.contains("private suspend fun applySearchText(text: String) = refreshMutex.withLock"))
        val manager = vm.substringAfter("private fun withManagerSuStates(").substringBefore("private fun withLiteSuStates")
        assertTrue(manager.contains("managerSuState("))
        assertFalse(manager.contains("RootExec."))
        assertFalse(manager.contains("HookSuCompatibility.traditionalReady"))
    }
}
