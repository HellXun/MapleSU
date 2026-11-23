package dev.maplesu.manager.installer

import dev.maplesu.manager.runtime.ultra.UltraEnvFacts
import dev.maplesu.manager.runtime.ultra.UltraEnvUpgrade
import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.*
import org.junit.Test

class UltraDirectInstallPolicyTest {
    private val direct = UltraDirectInstallRequest(WorkMode.LITE, InstallTargetMode.ULTRA,
        InstallSourceKind.CURRENT_PARTITION, "boot")
    private val update = UltraEnvFacts(running = true, installedVersion = "5.1.0",
        bundledVersion = "5.1.0", identityKnown = true,
        installedBuildId = "r11", bundledBuildId = "r13",
        installedHelperSha256 = "a".repeat(64), bundledHelperSha256 = "b".repeat(64))

    @Test fun reportedSameVersionBuildUpdateUsesSdkWithoutOpeningPatchedBoot() {
        var sdkCalls = 0
        val result = UltraDirectInstallPolicy.dispatch(direct, { update },
            upgrade = { sdkCalls++; "SDK_UPDATED" },
            unavailable = { error("unexpected pending result") },
            patchBoot = { error("ULTRA_KERNEL_GENERATE_SITE:HOOK_ENTRY_UNSUPPORTED:do_execveat_common") })
        assertEquals("SDK_UPDATED", result)
        assertEquals(1, sdkCalls)
    }

    @Test fun missingOldBuildIdentityStillUsesVerifiedEnvironmentInstaller() {
        assertEquals("SDK", UltraDirectInstallPolicy.dispatch(direct,
            { update.copy(installedHelperSha256 = null, installedBuildId = null) },
            { "SDK" }, { "PENDING" }, { error("already patched boot must not be opened") }))
    }

    @Test fun sdkFailureOrRefusalIsReturnedWithoutFallback() {
        for (failure in listOf("FAILED", "REFUSED", "UNVERIFIED:HELPER_MISMATCH")) {
            var bootCalls = 0
            val result = UltraDirectInstallPolicy.dispatch(direct, { update }, { failure },
                { "PENDING" }, { bootCalls++; "BOOT" })
            assertEquals(failure, result)
            assertEquals(0, bootCalls)
        }
    }

    @Test fun factsOrUpgradeExceptionDoesNotOpenBoot() {
        for (failFacts in listOf(true, false)) {
            var bootCalls = 0
            val result = runCatching {
                UltraDirectInstallPolicy.dispatch(direct,
                    { if (failFacts) error("FACTS_READ_FAILED") else update },
                    { error("SDK_TRANSPORT_INTERRUPTED") }, { "PENDING" },
                    { bootCalls++; "BOOT" })
            }
            assertTrue(result.isFailure)
            assertEquals(0, bootCalls)
        }
    }

    @Test fun submittedThisBootDoesNotSubmitAgainOrPretendToSucceed() {
        var sdkCalls = 0
        var bootCalls = 0
        val result = UltraDirectInstallPolicy.dispatch(direct, { update.copy(submittedThisBoot = true) },
            { sdkCalls++; "SUCCESS" }, { decision ->
                assertTrue(decision is UltraEnvUpgrade.Rebooting)
                "WAIT_FOR_REBOOT"
            }, { bootCalls++; "BOOT" })
        assertEquals("WAIT_FOR_REBOOT", result)
        assertEquals(0, sdkCalls)
        assertEquals(0, bootCalls)
    }

    @Test fun lostRunningStateOrSuspendedEnvironmentDoesNotFallBackToPatchedBoot() {
        for (facts in listOf(UltraEnvFacts(), update.copy(running = false, installedHelperSha256 = null),
            update.copy(suspended = true), update.copy(quiescing = true))) {
            assertEquals("UNAVAILABLE", UltraDirectInstallPolicy.dispatch(
                direct.copy(environmentWasRunning = true), { facts },
                { error("not ready to upgrade") }, { "UNAVAILABLE" },
                { error("temporary probe failures must not patch the current boot") }))
        }
    }

    @Test fun busyOrIncompleteFactsNeverBecomeAFirstInstall() {
        assertEquals("UNAVAILABLE", UltraDirectInstallPolicy.dispatch(direct,
            { UltraEnvFacts(readUnknown = true) }, { error("no verified update") },
            { "UNAVAILABLE" }, { error("an incomplete read is not an absent environment") }))
    }

    @Test fun reportedIdleHelperRoutesToCheckedRecoveryBeforeOpeningPatchedBoot() {
        var checks = 0
        val result = UltraDirectInstallPolicy.dispatch(direct, { update.copy(running = false) },
            { checks++; "CHECKED_RECOVERY" }, { "UNAVAILABLE" },
            { error("An installed idle helper needs recovery preflight, not another boot patch") })
        assertEquals("CHECKED_RECOVERY", result)
        assertEquals(1, checks)
    }

    @Test fun verifiedRetiredKernelMustActuallyUpdateBoot() {
        assertEquals("BOOT", UltraDirectInstallPolicy.dispatch(direct, { update },
            { error("An SDK-only update would leave the kernel unchanged") }, { "UNAVAILABLE" },
            { "BOOT" }, requiresKernelUpdate = { true }))
        assertEquals("SDK", UltraDirectInstallPolicy.dispatch(direct, { update },
            { "SDK" }, { "UNAVAILABLE" }, { error("The existing kernel filter is verified") },
            requiresKernelUpdate = { false }))
    }

    @Test fun installedIdleKernelCanBeUpdatedBeforeAnotherSdkOnlyRepair() {
        assertEquals("BOOT", UltraDirectInstallPolicy.dispatch(direct, { update.copy(running = false) },
            { error("A retired kernel must be replaced before reinstalling its helper") }, { "UNAVAILABLE" },
            { "BOOT" }, requiresKernelUpdate = { true }))
    }

    @Test fun absentSuspendedSwitchingAndSubmittedEnvironmentsDoNotStartAKernelUpdateProbe() {
        for (facts in listOf(update.copy(running = false, installedVersion = null), update.copy(suspended = true),
            update.copy(quiescing = true), update.copy(submittedThisBoot = true))) {
            UltraDirectInstallPolicy.dispatch(direct, { facts }, { "SDK_OR_RECOVERY" }, { "UNAVAILABLE" },
                { "FIRST_INSTALL" }, requiresKernelUpdate = { error("not ready for a boot read") })
        }
    }

    @Test fun normalMigrationFileDownloadVariantSigningAndBootOptionsKeepTheirBootPath() {
        val requests = listOf(
            direct.copy(target = InstallTargetMode.ULTRA),
            direct.copy(activeMode = WorkMode.LITE),
            direct.copy(activeMode = null),
            direct.copy(source = InstallSourceKind.IMAGE),
            direct.copy(source = InstallSourceKind.DOWNLOADED_BOOT),
            direct.copy(defaultKernel = false),
            direct.copy(signing = true),
            direct.copy(bootOptions = true),
        )
        for (request in requests) {
            var bootCalls = 0
            assertEquals("BOOT", UltraDirectInstallPolicy.dispatch(request,
                { error("must not probe SDK for $request") }, { error("must not upgrade $request") },
                { error("must not intercept $request") }, { bootCalls++; "BOOT" }))
            assertEquals(1, bootCalls)
        }
    }

    @Test fun sameModeInitBootUsesTheSameEnvironmentUpgradeConsumer() {
        assertEquals("SDK", UltraDirectInstallPolicy.dispatch(direct.copy(partition = "init_boot"),
            { update }, { "SDK" }, { error("unexpected pending result") }, { error("unnecessary image write") }))
    }

    @Test fun explicitRepatchAlwaysRebuildsTheSelectedCarrier() {
        for (partition in listOf("boot", "init_boot")) {
            var patches = 0
            assertEquals("REPATCHED", UltraDirectInstallPolicy.dispatch(
                direct.copy(partition = partition, forceRepatch = true),
                { error("Explicit repatch must not probe an environment-only update") },
                { error("Explicit repatch must not become an environment-only update") },
                { error("Explicit repatch must reach the image producer") },
                { patches++; "REPATCHED" }))
            assertEquals(1, patches)
        }
    }

    @Test fun firstInstallAndNoUpdateRetainTheExistingPath() {
        for (facts in listOf(UltraEnvFacts(), update.copy(installedHelperSha256 = update.bundledHelperSha256))) {
            assertEquals("BOOT", UltraDirectInstallPolicy.dispatch(direct, { facts },
                { error("no environment update") }, { error("not submitted") }, { "BOOT" }))
        }
    }

    @Test fun sameModeUltraPreservesModulesAndNormalToUltraKeepsTheChoice() {
        assertFalse(UltraDirectInstallPolicy.requiresModuleChoice(InstallTargetMode.ULTRA, WorkMode.LITE))
        assertTrue(UltraDirectInstallPolicy.requiresModuleChoice(InstallTargetMode.ULTRA, WorkMode.LITE))
        assertTrue(UltraDirectInstallPolicy.requiresModuleChoice(InstallTargetMode.ULTRA, null))
        assertFalse(UltraDirectInstallPolicy.requiresModuleChoice(InstallTargetMode.ULTRA, WorkMode.LITE))
    }
}
