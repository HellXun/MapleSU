package dev.maplesu.manager.workmode.superuser

import dev.maplesu.manager.workmode.WorkMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModeSuperuserBackendsTest {
    @Test fun unavailableSourceBackendStillBlocksWithoutReplacingSharedData() = runBlocking {
        TestControl.create().use { control ->
            val previous = control.write(Fixtures.shared(entries = listOf(Fixtures.intent())))
            val inactivePro = FakeBackend(BackendId.A_ULTRA).apply { available = false }
            val sync = DefaultSuperuserSync(control.plane, modeSuperuserBackends(WorkMode.LITE,
                { inactivePro }, { throw AssertionError("Inactive KSU must not be loaded") }), control.clock)
            assertEquals("SUPERUSER_BACKEND_UNAVAILABLE",
                (sync.exportBeforeSwitch(WorkMode.LITE, 0) as ExportResult.Blocked).code)
            assertEquals(previous, control.read())
        }
    }
}
