package com.maplesu.core.ui.webui

import com.maplesu.core.data.model.Module
import com.maplesu.core.data.model.ModuleUpdateInfo
import com.maplesu.core.data.repository.ModuleRepository
import dev.maplesu.manager.modules.ModuleBackendPorts
import dev.maplesu.manager.workmode.ActivationSource
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.TestFixtures
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.ModeState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WebUiModulesTest {
    @Before fun before() {
        ModeRuntime.resetForTest()
        ModuleBackendPorts.resetForTest()
        ModeRuntime.installBootIdProviderForTest { "webui-boot" }
    }

    @After fun after() {
        ModeRuntime.resetForTest()
        ModuleBackendPorts.resetForTest()
    }

    private fun activate(mode: WorkMode) {
        ModeRuntime.recordDetection(TestFixtures.detection(
            if (mode == WorkMode.LITE) ModeState.MODE_B_ACTIVE else ModeState.MODE_A_ACTIVE))
        assertTrue(ModeRuntime.activate(mode, "webui-boot", ActivationSource.BOOT_DETECTION))
    }

    private class Repository : ModuleRepository {
        var reads = 0
        override suspend fun getModules(): Result<List<Module>> {
            reads++
            return Result.success(emptyList())
        }
        override suspend fun checkUpdate(module: Module) = Result.success(ModuleUpdateInfo.Empty)
    }

    @Test fun closingTheWebUiCancelsReadinessWithoutStartingRootOrReadingModules() = runBlocking {
        val repository = Repository()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { loadWebUiModules(repository) }
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertEquals(0, repository.reads)
        assertNull(ModeRuntime.active)
    }
}
