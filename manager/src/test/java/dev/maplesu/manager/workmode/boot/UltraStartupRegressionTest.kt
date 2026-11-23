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
        var hookReady: Boolean = true,
        val activate: Boolean = true,
        val readiness: SuReadiness = SuReadiness.USABLE,
        initial: EnvironmentSnapshot? = EnvironmentSnapshot(null, false, null, BOOT, 1),
        val ksuFacts: KsuFacts = KsuFacts(),
        val ultraState: UltraState = UltraState.UNKNOWN,
        val ultraKernelRootVerified: Boolean = false,
        val ultraProbeFailed: Boolean = false,
    ) {
        val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence(initial))
        var active: WorkMode? = null
        var hookReads = 0
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
                        jailbreakLease = JailbreakLeaseProbe { hookReads++; hookReady },
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

    @Test fun liveHookReplacesOldNotInstalledStateAfterProcessRecreation() = runBlocking {
        val f = Fixture()
        f.start()
        assertEquals(WorkMode.LITE, f.active)
        assertEquals(1, f.hookReads)
        assertEquals(WorkMode.LITE, f.store.snapshot()?.mode)
        assertEquals("hook:ready", f.store.snapshot()?.ultraEnvFingerprint)
        assertFalse(EnvironmentHomeProjection.decide(
            EnvironmentHomeProjection.Facts(f.store.snapshot(), BOOT),
        ).confirmedNotInstalled)
    }

    @Test fun liveHookReplacesAnEarlierBootsNotInstalledState() = runBlocking {
        val f = Fixture(initial = EnvironmentSnapshot(null, false, null, "old-boot", 1))
        f.start()
        assertEquals(WorkMode.LITE, f.active)
        assertEquals(BOOT, f.store.snapshot()?.bootId)
        assertEquals(WorkMode.LITE, f.store.snapshot()?.mode)
    }

    @Test fun failedActivationDoesNotPersistAnInstalledMode() = runBlocking {
        val f = Fixture(activate = false)
        f.start()
        assertNull(f.active)
        assertNull(f.store.snapshot()?.mode)
    }

    @Test fun absentHookDoesNotTurnUsableForeignSuIntoUltra() = runBlocking {
        val f = Fixture(hookReady = false)
        f.start()
        assertNull(f.active)
        assertNull(f.store.snapshot()?.mode)
    }

    @Test fun ordinarySuNeverActivatesCachedUltraAfterItsOwnProvidersAreExcluded() = runBlocking {
        for (boot in listOf(BOOT, "old-boot")) for (su in listOf(SuReadiness.USABLE, SuReadiness.UNKNOWN)) {
            val f = Fixture(hookReady = false, readiness = su, ultraState = UltraState.NOT_INSTALLED,
                initial = EnvironmentSnapshot(WorkMode.LITE, true, "ultra:running", boot, 1))
            f.start()
            assertNull("an external manager's root is not Ultra", f.active)
            assertNull(f.store.snapshot()?.mode)
            assertEquals(BOOT, f.store.snapshot()?.bootId)
        }
    }

    @Test fun noSuAndNoHookStayNotInstalled() = runBlocking {
        val f = Fixture(hookReady = false, readiness = SuReadiness.NOT_USABLE)
        f.start()
        assertNull(f.active)
        assertTrue(EnvironmentHomeProjection.decide(
            EnvironmentHomeProjection.Facts(f.store.snapshot(), BOOT),
        ).confirmedNotInstalled)
    }

    @Test fun liveHookIsCheckedEvenWhenTheQuickSuProbeReturnedNotUsable() = runBlocking {
        val f = Fixture(readiness = SuReadiness.NOT_USABLE)
        f.start()
        assertEquals(WorkMode.LITE, f.active)
        assertEquals(1, f.hookReads)
        assertEquals("hook:ready", f.store.snapshot()?.ultraEnvFingerprint)
    }

    @Test fun aHookCompletedAfterStartupCanActivateWithoutRestartingTheProcess() = runBlocking {
        val f = Fixture(hookReady = false, readiness = SuReadiness.NOT_USABLE)
        f.start()
        assertNull(f.active)
        f.hookReady = true
        f.store.markDirty(EnvironmentInvalidation.JAILBREAK_COMPLETED)
        f.coordinator.runPhaseTwo()
        assertEquals(WorkMode.LITE, f.active)
        assertEquals(WorkMode.LITE, f.store.snapshot()?.mode)
        assertFalse(f.store.isDirty())
    }

    @Test fun quickNotInstalledDoesNotHideConfirmedForeignKsuEvidence() = runBlocking {
        val f = Fixture(readiness = SuReadiness.NOT_USABLE,
            ksuFacts = KsuFacts(responded = true, version = 100, uapi = 8, isManager = false))
        f.coordinator.runPhaseOne()
        val outcome = f.coordinator.runPhaseTwo()
        assertNull(f.active)
        assertEquals(ModeState.FOREIGN_SU, (outcome as BootstrapOutcome.Unresolved).state)
    }

    @Test fun liveUltraReplacesQuickNotInstalledBeforeHomeCanReuseIt() = runBlocking {
        val f = Fixture(hookReady = false, readiness = SuReadiness.NOT_USABLE, ultraState = UltraState.RUNNING)
        f.start()
        assertEquals(WorkMode.LITE, f.active)
        assertEquals("ultra:running", f.store.snapshot()?.ultraEnvFingerprint)
        assertEquals(WorkMode.LITE, f.store.snapshot()?.mode)
        assertFalse(EnvironmentHomeProjection.decide(
            EnvironmentHomeProjection.Facts(f.store.snapshot(), BOOT, SuReadiness.UNKNOWN),
        ).confirmedNotInstalled)
    }

    @Test fun liveUltraReplacesEarlierBootsRecordAfterProcessRecreation() = runBlocking {
        val f = Fixture(hookReady = false, readiness = SuReadiness.NOT_USABLE,
            initial = EnvironmentSnapshot(null, false, null, "old-boot", 1), ultraState = UltraState.RUNNING)
        f.start()
        assertEquals(WorkMode.LITE, f.active)
        assertEquals(BOOT, f.store.snapshot()?.bootId)
        assertEquals(WorkMode.LITE, f.store.snapshot()?.mode)
    }

    @Test fun currentBootProtectionPersistsUltraWithoutClaimingWorkingSu() = runBlocking {
        val f = Fixture(hookReady = false, readiness = SuReadiness.NOT_USABLE, ultraState = UltraState.FAULT)
        f.start()
        assertEquals(WorkMode.LITE, f.active)
        assertEquals(WorkMode.LITE, f.store.snapshot()?.mode)
        assertEquals("ultra:fault", f.store.snapshot()?.ultraEnvFingerprint)
        assertEquals(false, f.store.snapshot()?.suAvailable)
    }

    @Test fun rejectedUltraActivationDoesNotPublishAnInstalledRecord() = runBlocking {
        val f = Fixture(hookReady = false, activate = false,
            readiness = SuReadiness.NOT_USABLE, ultraState = UltraState.RUNNING)
        f.start()
        assertNull(f.active)
        assertNull(f.store.snapshot()?.mode)
    }

    @Test fun earlierBootsUltraAndUnknownSdkNeverActivateThisBoot() = runBlocking {
        val previous = EnvironmentSnapshot(WorkMode.LITE, true, "ultra:running", "old-boot", 1)
        val f = Fixture(hookReady = false, readiness = SuReadiness.UNKNOWN, initial = previous, ultraProbeFailed = true)
        f.start()
        assertNull(f.active)
        assertEquals(previous, f.store.snapshot())
        assertNull(EnvironmentHomeProjection.decide(
            EnvironmentHomeProjection.Facts(f.store.snapshot(), BOOT),
        ).cachedMode)
    }

    @Test fun redmiVerifiedKernelWithEmptyBootReceiptsKeepsUltraWithoutClaimingSuReady() = runBlocking {
        val f = Fixture(hookReady = false, readiness = SuReadiness.NOT_USABLE,
            ultraState = UltraState.UNKNOWN, ultraKernelRootVerified = true)
        f.start()
        assertEquals(WorkMode.LITE, f.active)
        assertEquals(0, f.hookReads)
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
    @Test fun redmagicPostFlashPreparationFailureDoesNotEraseLiveKernelIdentityOrPairing() = runBlocking {
        val helper = UltraEnvironmentProbe.State.NotInstalled
        val kernelProof = UltraEnvironmentProbe.verifyKernelRoot(helper, true) { 0 }
        val observation = UltraEnvironmentProbe.Snapshot(helper, kernelRootVerified = kernelProof)
        val f = Fixture(hookReady = false, readiness = SuReadiness.USABLE,
            ultraState = UltraState.NOT_INSTALLED, ultraKernelRootVerified = kernelProof)
        f.start()
        assertEquals(WorkMode.LITE, f.active)
        assertEquals(0, f.hookReads)
        assertEquals("ultra:kernel", f.store.snapshot()?.ultraEnvFingerprint)
        assertFalse(EnvironmentHomeProjection.decide(
            EnvironmentHomeProjection.Facts(f.store.snapshot(), BOOT),
        ).confirmedNotInstalled)

        var paired = false
        val pairing = UltraAutoPair.complete(observation, current = { true }, alreadyPaired = { false },
            persist = { paired = true }, verifyManagerSu = { true }, maintain = {})
        assertTrue(pairing.paired)
        assertTrue(paired)
        assertEquals(helper, pairing.state)
        val install = UltraEnvironmentInstaller.install(object : UltraEnvironmentInstaller.InstallPort {
            override fun environmentState() = dev.maplesu.manager.runtime.ultra.NativeReceipt.parse(
                """{"backend":"maplesu_ultra","ok":true,"sdkError":"OK","data":{"state":"NotInstalled"}}""")!!
            override fun submitInstall(): dev.maplesu.manager.runtime.ultra.NativeReceipt =
                error("failed backend preparation must not submit a reboot-capable helper")
        }, UltraEnvironmentInstaller.Intent.ReuseRunning, UltraEnvironmentInstaller.Attachments(
            whitelist = { error("no helper was submitted") }, backup = { null },
            prepareInstall = { "FIXTURE_PREPARATION_FAILURE" },
        ))
        assertFalse(install.ok)
        assertEquals("POLICY_BACKEND_PREPARATION_FAILED", install.sdkError)
        assertTrue("failed userspace setup must not revoke proven kernel Root", observation.rootAvailable)
        assertEquals(WorkMode.LITE, f.active)
        assertEquals("ultra:kernel", f.store.snapshot()?.ultraEnvFingerprint)
    }

    @Test fun rejectedKernelOnlyActivationNeverPersistsAnInstalledRecord() = runBlocking {
        val f = Fixture(hookReady = false, activate = false, readiness = SuReadiness.NOT_USABLE,
            ultraKernelRootVerified = true)
        f.start()
        assertNull(f.active)
        assertNull(f.store.snapshot()?.mode)
    }

    private companion object { const val BOOT = "current-boot" }
}
