package com.maplesu.core.ui.screen.install

import dev.maplesu.manager.installer.InstallPartition
import dev.maplesu.manager.installer.InstallTargetBlocker
import dev.maplesu.manager.installer.InstallTargetCodes
import dev.maplesu.manager.installer.InstallTargetDecision
import dev.maplesu.manager.installer.InstallTargetMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallUiStateAvailabilityTest {
    @Test fun automaticInstallKeepsTheObservedTargetAndStillRequiresAPatchableSource() {
        for (carrier in listOf(InstallPartition.BOOT, InstallPartition.INIT_BOOT)) {
            val current = state(partition = carrier).copy(
                partitionOptions = listOf(InstallPartition.BOOT, InstallPartition.INIT_BOOT),
                observedCarrier = carrier.wire,
                availableCarriers = setOf("boot", "init_boot"),
            )
            assertEquals(UltraInstallRoute(carrier.wire), current.ultraRoute)
            assertTrue(current.canInstall)
            assertFalse(current.copy(bootFlow = null).canInstall)
            assertFalse(current.copy(targetSelectable = false).canInstall)
            val other = if (carrier == InstallPartition.BOOT) InstallPartition.INIT_BOOT else InstallPartition.BOOT
            assertEquals(UltraInstallRoute(other.wire), current.copy(partition = other).ultraRoute)
            assertFalse(current.copy(partition = other, availableCarriers = setOf(carrier.wire)).canInstall)
        }
    }

    private fun profile(target: KernelTarget? = KernelTarget("k22")) = BootFlowProfile(
        source = "boot.img",
        release = "6.6.89-android15-8-g7e1f3c083cc6-abogki467167594-4k",
        target = target,
    )

    private fun state(
        method: InstallMethod? = InstallMethod.DirectInstall,
        target: KernelTarget? = KernelTarget("k22"),
        options: List<InstallMethod> = listOf(InstallMethod.DirectInstall),
        mode: InstallTargetMode = InstallTargetMode.ULTRA,
        partition: InstallPartition = InstallPartition.INIT_BOOT,
        targetSelectable: Boolean = true,
        directAccessReady: Boolean = true,
        sourceLoading: Boolean = false,
    ) = InstallUiState(
        installMethod = method,
        installMethodOptions = options,
        bootFlow = profile(target),
        targetMode = mode,
        partition = partition,
        partitionOptions = dev.maplesu.manager.installer.InstallPartitionPolicy.optionsFor(mode),
        targetSelectable = targetSelectable,
        directAccessReady = directAccessReady,
        sourceLoading = sourceLoading,
    )

    // ── Ultra：仍然由步骤 ③ 的 Pro 内核目标决定 ─────────────────────────────

    @Test
    fun nextIsAvailableOnceBootAndKernelVersionAreChosen() {
        assertTrue(state().canInstall)
    }

    @Test
    fun targetInformationWaitsForAnAvailableInstallationSource() {
        assertTrue(state().hasInstallSource)
        assertFalse(state(method = null).hasInstallSource)
        val emptyFile = InstallMethod.SelectFile(summary = "")
        assertFalse(state(method = emptyFile, options = listOf(emptyFile)).hasInstallSource)
        assertFalse(state(options = emptyList()).hasInstallSource)
    }

    @Test
    fun currentPartitionWaitsForRootAndStockBackupConfirmation() {
        assertFalse(state(directAccessReady = false).hasInstallSource)
        assertFalse(state(directAccessReady = false).canInstall)
        assertFalse(state(sourceLoading = true).hasInstallSource)
        assertFalse(state(sourceLoading = true).canInstall)
    }

    @Test
    fun nextIsDisabledWhileTheKernelVersionIsNotSelectable() {
        // 步骤 ③ 还没选出目标（识别中 / 修不了 / 还没选 boot）→ 不能进入修补。
        assertFalse(state(target = null).canInstall)
    }

    @Test
    fun nextIsDisabledWhenTheChosenBootHasNoUri() {
        val method = InstallMethod.SelectFile(summary = "")
        assertFalse(
            state(method = method, options = listOf(method)).canInstall,
        )
    }

    @Test
    fun nextIsDisabledWhenTheMethodIsNotOffered() {
        assertFalse(
            state(method = InstallMethod.DownloadFile(summary = "")).canInstall,
        )
    }

    // ── Normal：由"设备 KMI × 目标分区"的纯函数结论决定 ──────────────────────

    // ── 分区选择的可见性/固定语义 ───────────────────────────────────────────

    @Test
    fun ultraShowsASingleFixedPartition() {
        assertTrue(state(mode = InstallTargetMode.ULTRA).partitionFixed)
    }

}
