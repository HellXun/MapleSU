package dev.maplesu.manager.workmode.boot

import dev.maplesu.manager.workmode.ActivationSource
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.*
import dev.maplesu.manager.installer.*
import dev.maplesu.manager.runtime.ultra.UltraEnvFacts
import dev.maplesu.manager.runtime.ultra.UltraAutoPair
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentInstaller
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real resolver → detector → startup → persisted home-state regression. */
class UltraStartupRegressionTest {
    private class Fixture(
        val activate: Boolean = true,
        val readiness: SuReadiness = SuReadiness.USABLE,
        initial: EnvironmentSnapshot? = EnvironmentSnapshot(null, false, null, BOOT, 1),
        val ksuFacts: KsuFacts = KsuFacts(),
        var ultraState: UltraState = UltraState.UNKNOWN,
        val ultraKernelRootVerified: Boolean = false,
        val ultraProbeFailed: Boolean = false,
    ) {
        val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence(initial))
        var active: WorkMode? = null
        private val probes = object : EnvironmentProbes {
            override fun suPresent() = readiness == SuReadiness.USABLE
            override fun suReadiness() = readiness
            override fun ultraFingerprint(): String? {
                if (ultraProbeFailed) error("ULTRA_RECOVERY_PROBE_UNKNOWN")
                return null
            }
            override fun ksuNormal(): Boolean = false
        }
        val coordinator = ModeBootstrapCoordinator(
            pipelineProvider = {
                ModeBootstrapPipeline(
                    detector = DefaultModeDetector(
                        ksuProbe = KsuKernelProbe { ksuFacts },
                        ultraPort = object : UltraEnvironmentPort {
                            override fun state() = ultraState
                            override fun observe(allowRefresh: Boolean) =
                                UltraEnvironmentObservation(ultraState, ultraKernelRootVerified)
                        },
                        bootIdSource = BootIdSource { BOOT },
                    ),
                    runtime = object : ModeRuntimePort {
                        override fun recordDetection(result: DetectionResult) {}
                        override fun completeBootstrap(result: DetectionResult) {}
                        override fun activate(mode: WorkMode, bootId: String, source: ActivationSource): Boolean {
                            if (activate) active = mode
                            return activate
                        }
                    },
                    environmentOverride = {
                        EnvironmentDetectionProjection.override(EnvironmentResolver(probes, store).resolve(BOOT))
                    },
                    environmentStore = store,
                )
            },
            isResolved = { active != null },
        )
        suspend fun start() {
            coordinator.runPhaseOne()
            coordinator.runPhaseTwo()
        }
    }

    @Test fun failedActivationDoesNotPersistAnInstalledMode() = runBlocking {
        val f = Fixture(activate = false, ultraState = UltraState.RUNNING)
        f.start()
        assertNull(f.active)
        assertNull(f.store.snapshot()?.mode)
    }

    @Test fun usableForeignSuAloneDoesNotActivateUltra() = runBlocking {
        val f = Fixture()
        f.start()
        assertNull(f.active)
        assertNull(f.store.snapshot()?.mode)
    }

    @Test fun noSuStaysNotInstalled() = runBlocking {
        val f = Fixture(readiness = SuReadiness.NOT_USABLE)
        f.start()
        assertNull(f.active)
        assertTrue(EnvironmentHomeProjection.decide(
            EnvironmentHomeProjection.Facts(f.store.snapshot(), BOOT),
        ).confirmedNotInstalled)
    }

    @Test fun anUltraEnvironmentCompletedAfterStartupCanActivateWithoutRestartingTheProcess() = runBlocking {
        val f = Fixture(readiness = SuReadiness.NOT_USABLE)
        f.start()
        assertNull(f.active)
        f.ultraState = UltraState.RUNNING
        f.store.markDirty(EnvironmentInvalidation.FLASHED)
        f.coordinator.runPhaseTwo()
        assertEquals(WorkMode.LITE, f.active)
        assertEquals(WorkMode.LITE, f.store.snapshot()?.mode)
        assertFalse(f.store.isDirty())
    }

    @Test fun liveUltraReplacesQuickNotInstalledBeforeHomeCanReuseIt() = runBlocking {
        val f = Fixture(readiness = SuReadiness.NOT_USABLE, ultraState = UltraState.RUNNING)
        f.start()
        assertEquals(WorkMode.LITE, f.active)
        assertEquals("ultra:running", f.store.snapshot()?.ultraEnvFingerprint)
        assertEquals(WorkMode.LITE, f.store.snapshot()?.mode)
        assertFalse(EnvironmentHomeProjection.decide(
            EnvironmentHomeProjection.Facts(f.store.snapshot(), BOOT, SuReadiness.UNKNOWN),
        ).confirmedNotInstalled)
    }

    @Test fun liveUltraReplacesEarlierBootsRecordAfterProcessRecreation() = runBlocking {
        val f = Fixture(readiness = SuReadiness.NOT_USABLE,
            initial = EnvironmentSnapshot(null, false, null, "old-boot", 1), ultraState = UltraState.RUNNING)
        f.start()
        assertEquals(WorkMode.LITE, f.active)
        assertEquals(BOOT, f.store.snapshot()?.bootId)
        assertEquals(WorkMode.LITE, f.store.snapshot()?.mode)
    }

    @Test fun currentBootProtectionPersistsUltraWithoutClaimingWorkingSu() = runBlocking {
        val f = Fixture(readiness = SuReadiness.NOT_USABLE, ultraState = UltraState.FAULT)
        f.start()
        assertEquals(WorkMode.LITE, f.active)
        assertEquals(WorkMode.LITE, f.store.snapshot()?.mode)
        assertEquals("ultra:fault", f.store.snapshot()?.ultraEnvFingerprint)
        assertEquals(false, f.store.snapshot()?.suAvailable)
    }

    @Test fun rejectedUltraActivationDoesNotPublishAnInstalledRecord() = runBlocking {
        val f = Fixture(activate = false,
            readiness = SuReadiness.NOT_USABLE, ultraState = UltraState.RUNNING)
        f.start()
        assertNull(f.active)
        assertNull(f.store.snapshot()?.mode)
    }

    @Test fun earlierBootsUltraAndUnknownSdkNeverActivateThisBoot() = runBlocking {
        val previous = EnvironmentSnapshot(WorkMode.LITE, true, "ultra:running", "old-boot", 1)
        val f = Fixture(readiness = SuReadiness.UNKNOWN, initial = previous, ultraProbeFailed = true)
        f.start()
        assertNull(f.active)
        assertEquals(previous, f.store.snapshot())
        assertNull(EnvironmentHomeProjection.decide(
            EnvironmentHomeProjection.Facts(f.store.snapshot(), BOOT),
        ).cachedMode)
    }

    @Test fun redmiVerifiedKernelWithEmptyBootReceiptsKeepsUltraWithoutClaimingSuReady() = runBlocking {
        val f = Fixture(readiness = SuReadiness.NOT_USABLE,
            ultraState = UltraState.UNKNOWN, ultraKernelRootVerified = true)
        f.start()
        assertEquals(WorkMode.LITE, f.active)
        assertEquals("ultra:kernel", f.store.snapshot()?.ultraEnvFingerprint)
        assertEquals(WorkMode.LITE, f.store.snapshot()?.mode)
        assertEquals(false, f.store.snapshot()?.suAvailable)
        assertFalse(EnvironmentHomeProjection.decide(
            EnvironmentHomeProjection.Facts(f.store.snapshot(), BOOT),
        ).confirmedNotInstalled)
        val action = UltraDirectInstallPolicy.dispatch(
            UltraDirectInstallRequest(f.active, InstallTargetMode.ULTRA, InstallSourceKind.CURRENT_PARTITION, "boot"),
            facts = { UltraEnvFacts(running = false, installedVersion = "5.1.0",
                installedHelperSha256 = "a".repeat(64)) },
            upgrade = { "CHECKED_ENVIRONMENT_REPAIR" }, unavailable = { "NOT_READY" },
            patchBoot = { error("INSTALLER_MODE_SWITCH_PENDING: must not patch an already active Ultra kernel") },
        )
        assertEquals("CHECKED_ENVIRONMENT_REPAIR", action)
    }

    /** Redmagic NP06J, 2026-11-11: live SDK uid 0, NotInstalled helper, backend preparation failed. */
    @Test fun rejectedKernelOnlyActivationNeverPersistsAnInstalledRecord() = runBlocking {
        val f = Fixture(activate = false, readiness = SuReadiness.NOT_USABLE,
            ultraKernelRootVerified = true)
        f.start()
        assertNull(f.active)
        assertNull(f.store.snapshot()?.mode)
    }

    private companion object { const val BOOT = "current-boot" }
}
