package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.runtime.UltraEnvRootExecutor
import dev.maplesu.manager.runtime.UltraRootCommandResult
import dev.maplesu.manager.runtime.RootBackend
import dev.maplesu.manager.runtime.RootExecutionRouter
import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.*
import org.junit.Test

class UltraKernelPairingRegressionTest {
    @Test fun keyProofFlowsThroughPairingExecutionAndAuthorizationWriteReadback() {
        for (helper in listOf(UltraEnvironmentProbe.State.Unknown, UltraEnvironmentProbe.State.NotInstalled)) {
            val events = mutableListOf<String>()
            val observation = UltraEnvironmentProbe.Snapshot(helper, kernelRootVerified =
                UltraEnvironmentProbe.verifyKernelRoot(helper, true) { events += "uid"; 0 })
            var paired = false
            val pairing = UltraAutoPair.complete(observation, current = { true }, alreadyPaired = { paired },
                persist = { events += "pair"; paired = true }, verifyManagerSu = { true }, maintain = { events += "maintain" })
            assertTrue(pairing.paired)
            val ultra = UltraEnvRootExecutor(running = { paired && observation.rootAvailable },
                runner = { script -> events += script; UltraRootCommandResult(true, 0, "SDK_RESULT") })
            val router = RootExecutionRouter(null, ultra, modeProvider = { WorkMode.LITE }, ultraIdentity = { paired })
            assertTrue(router.rootAvailableForLite())
            val root = router.execute("key-root-operation")
            assertEquals(RootBackend.ULTRA_ENV, root.backend)
            assertEquals("SDK_RESULT", root.output)
            val packages = mutableListOf<String>()
            val authorization = UltraAuthorizationState()
            val port = object : SuAuthPort {
                override fun snapshot(): List<String> { events += "readback"; return packages.toList() }
                override fun add(packageName: String): NativeReceipt {
                    events += "grant"; packages += packageName; return testReceipt()
                }
                override fun remove(packageName: String) = error("not requested")
            }
            assertNull(authorization.setGranted(port, "fixture.app", true))
            assertEquals(setOf("fixture.app"), authorization.snapshot.packages)
            assertEquals(helper, pairing.state)
            assertEquals(listOf("uid", "pair", "maintain", "key-root-operation", "grant", "readback"), events)
        }
    }

    @Test fun aLiveKernelPairsDespiteUnconfirmedHelperAndRunsTheNormalMaintenance() {
        for (state in listOf(UltraEnvironmentProbe.State.Unknown, UltraEnvironmentProbe.State.NotInstalled)) {
            val events = mutableListOf<String>()
            val result = UltraAutoPair.complete(UltraEnvironmentProbe.Snapshot(state, kernelRootVerified = true),
                current = { events += "scope"; true }, alreadyPaired = { false },
                persist = { events += "pair" }, verifyManagerSu = { true }, maintain = { events += "maintenance" })
            assertTrue("$state: current Key-based uid 0 must pair", result.paired)
            assertEquals(state, result.state)
            assertEquals(listOf("scope", "pair", "maintenance"), events)
        }
    }

    @Test fun staleFlagsForeignRootAndFaultNeverAuthorizePairing() {
        for (state in UltraEnvironmentProbe.State.entries.filter { it != UltraEnvironmentProbe.State.Running }) {
            val result = UltraAutoPair.complete(UltraEnvironmentProbe.Snapshot(state), current = { true },
                alreadyPaired = { true }, persist = { fail("no live proof") }, verifyManagerSu = { true }, maintain = { fail("no live proof") })
            assertFalse(result.paired)
        }
        val fault = UltraAutoPair.complete(UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Fault, kernelRootVerified = true),
            current = { true }, alreadyPaired = { false }, persist = { fail() }, verifyManagerSu = { true }, maintain = { fail() })
        assertFalse(fault.paired)
    }

    @Test fun identityChangesCancelBeforePairingOrMaintenance() {
        val result = UltraAutoPair.complete(UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Running),
            current = { false }, alreadyPaired = { fail(); false }, persist = { fail() }, verifyManagerSu = { true }, maintain = { fail() })
        assertFalse(result.paired)
        assertEquals("ULTRA_KEY_CHANGED", result.reason)
    }

    @Test fun alreadyVerifiedKeyStillCompletesPairingAndSchedulesMaintenanceWithoutAnotherWrite() {
        val events = mutableListOf<String>()
        val result = UltraAutoPair.complete(UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Unknown, kernelRootVerified = true),
            current = { true }, alreadyPaired = { true }, persist = { fail() }, verifyManagerSu = { true }, maintain = { events += "maintenance" })
        assertTrue(result.paired)
        assertEquals("ALREADY_PAIRED", result.reason)
        assertEquals(listOf("maintenance"), events)
    }
}
