package com.maplesu.core.ui.screen.home

import com.maplesu.core.KernelVersion
import com.maplesu.core.ui.util.module.LatestVersionInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.maplesu.manager.workmode.detect.SuReadiness

/**
 * P0（首页启动状态）在 **UiState 层**的判据：两套皮肤画不画「MapleSU_Ultra模式启动失败！」这一行，
 * 以及隐身归一化之后不许再露出任何 Lite 痕迹。
 *
 * 纯 JVM：只碰 data class / copy / 派生属性，不建 Compose、不碰 JNI、不读磁盘。
 */
class HomeUiStateLiteStatusTest {

    @Test
    fun installedFirstFrameDoesNotInventASuFailure() {
        val pending = state(ksuVersion = -1, isManager = true, liteInstalled = true)
        assertFalse("No SU result yet is not a failed SU command", pending.showRootWarning)
    }

    @Test
    fun onlyAnActualFailedSuShowsRootWarning() {
        val installed = state(ksuVersion = -1, isManager = true, liteInstalled = true)
        assertFalse(installed.copy(rootReadiness = SuReadiness.UNKNOWN).showRootWarning)
        assertFalse(installed.copy(rootReadiness = SuReadiness.USABLE).showRootWarning)
        assertTrue(installed.copy(rootReadiness = SuReadiness.NOT_USABLE).showRootWarning)
        assertFalse(installed.copy(rootReadiness = SuReadiness.NOT_USABLE, isRootAvailable = true).showRootWarning)
    }

    @Test
    fun unknownSuDoesNotBecomeAnUltraStartupFailure() {
        val installed = state(liteInstalled = true, liteRootFailure = true)
        assertFalse(installed.copy(rootReadiness = SuReadiness.UNKNOWN).showLiteRootFailure)
        assertFalse(installed.copy(rootReadiness = null).showLiteRootFailure)
    }

    private fun state(
        ksuVersion: Int? = null,
        isManager: Boolean = false,
        isRootAvailable: Boolean = false,
        liteInstalled: Boolean = false,
        liteRootFailure: Boolean = false,
        litePending: Boolean = false,
    ) = HomeUiState(
        kernelVersion = KernelVersion(6, 1, 31),
        ksuVersion = ksuVersion,
        managerUAPIVersion = 1,
        kernelUAPIVersion = null,
        lkmMode = null,
        isLkmBundled = false,
        isManager = isManager,
        isManagerPrBuild = false,
        isKernelPrBuild = false,
        requiresNewKernel = false,
        requiresNewManager = false,
        isRootAvailable = isRootAvailable,
        rootReadiness = if (liteRootFailure) SuReadiness.NOT_USABLE else null,
        isSafeMode = false,
        checkUpdateEnabled = false,
        latestVersionInfo = LatestVersionInfo(),
        currentManagerVersionCode = 0L,
        systemInfo = SystemInfo("6.1.31", "1 (1)", "device", "fingerprint", "Enforcing", 0),
        liteInstalled = liteInstalled,
        liteRootFailure = liteRootFailure,
        litePending = litePending,
    )

    @Test
    fun liteInstalledWithRootFailureShowsTheLine() {
        val s = state(liteInstalled = true, liteRootFailure = true)
        assertTrue("Lite 已安装 + 后台 root 失败 → 画那一行", s.showLiteRootFailure)
        // 安装态不回退：状态卡仍走"已安装"分支。
        assertTrue(s.liteInstalled)
    }

    @Test
    fun liteInstalledWithoutFailureShowsNoLine() {
        assertFalse(state(liteInstalled = true, liteRootFailure = false).showLiteRootFailure)
    }

    @Test
    fun failureFlagAloneNeverDrawsTheLine() {
        // 只有"已安装"才允许有这一行：KSU / 未判定即使带着脏标记也不许露出来。
        assertFalse(state(liteRootFailure = true).showLiteRootFailure)
    }

    @Test
    fun stealthNormalizationClearsLiteFields() {
        val normalized = state(
            ksuVersion = 32513,
            isManager = true,
            isRootAvailable = true,
            liteInstalled = true,
            liteRootFailure = true,
        ).asStealthKernelNotReady()
        assertFalse("隐身伪装下不许说 Lite 已安装", normalized.liteInstalled)
        assertFalse("隐身伪装下不许有 Lite 失败行", normalized.liteRootFailure)
        assertFalse(normalized.showLiteRootFailure)
    }

    @Test
    fun ksuStateStaysUnchanged() {
        val ksu = state(ksuVersion = 32513, isManager = true, isRootAvailable = true)
        assertTrue("KSU 已安装判据只看 ksuVersion", ksu.ksuVersion != null)
        assertFalse("KSU 不会出现 Lite 失败行", ksu.showLiteRootFailure)
        assertFalse("KSU 不会出现 root 警告", ksu.showRootWarning)
    }

    // ── 2026-10-30：冷启动闪烁（判定中 loading 占位）在 UiState 层的判据 ──

    @Test
    fun pendingStateDrawsLoadingAndNoFailureLine() {
        val s = state(litePending = true, liteRootFailure = true)
        assertTrue("判定中 → 画 loading 占位", s.showLiteChecking)
        assertFalse("loading 期不许画「未安装」的那套失败行", s.showLiteRootFailure)
    }

    @Test
    fun installedStateIsNeverChecking() {
        // 安全阀一：已安装时即使上游把 litePending 设错，也绝不能再画 loading。
        val s = state(liteInstalled = true, litePending = true)
        assertFalse(s.showLiteChecking)
        assertTrue("已安装优先", s.liteInstalled)
    }

    @Test
    fun ksuInstalledStateIsNeverChecking() {
        // 安全阀二：KSU 认主 / Pro Running 走 ksuVersion，冷启动必须直接是"已安装"，
        // 不能退化成转圈（否则会把既有行为改坏）。
        val s = state(ksuVersion = 32513, isManager = true, litePending = true)
        assertFalse("有既有已安装通道 → 不画 loading", s.showLiteChecking)
    }

    @Test
    fun stealthNormalizationClearsPendingToo() {
        val normalized = state(litePending = true, liteInstalled = true).asStealthKernelNotReady()
        assertFalse("隐身伪装下不许画 loading（等于暴露正在判定 Lite）", normalized.litePending)
        assertFalse(normalized.showLiteChecking)
        assertFalse(normalized.liteInstalled)
        assertFalse(normalized.showLiteRootFailure)
    }

    @Test
    fun silentEnvironmentCheckPreservesKnownInstalledStatus() {
        val initial = state(ksuVersion = 1, liteInstalled = true).copy(environmentChecking = true)
        assertFalse(initial.showLiteChecking)
        assertFalse(initial.copy(environmentChecking = false).showLiteChecking)
    }

    @Test
    fun confirmedHiddenStateClearsTheInitialEnvironmentCheck() {
        val hidden = state(liteInstalled = true).copy(environmentChecking = true)
            .asLiteHiddenNotInstalled()
        assertFalse(hidden.environmentChecking)
        assertFalse(hidden.showLiteChecking)
    }

    @Test
    fun failedCheckAlwaysEndsLoadingWithoutInventingRootOrInstallation() {
        for (failure in HomeEnvironmentFailure.entries) {
            val failed = state().copy(environmentChecking = true, litePending = true,
                hiddenConfirming = true, environmentFailure = failure)
            assertFalse(failed.showLiteChecking)
            assertFalse(failed.isRootAvailable)
            assertFalse(failed.liteInstalled)
            assertTrue(failed.ksuVersion == null)
        }
    }

    @Test
    fun failedReadPreservesPreviouslyVerifiedInstallationAndRoot() {
        val failed = state(ksuVersion = 1, liteInstalled = true, isRootAvailable = true)
            .copy(environmentFailure = HomeEnvironmentFailure.TIMED_OUT)
        assertTrue(failed.liteInstalled)
        assertTrue(failed.isRootAvailable)
        assertFalse(failed.showLiteChecking)
    }

    @Test
    fun hiddenNormalizationClearsEnvironmentFailure() {
        val hidden = state().copy(environmentFailure = HomeEnvironmentFailure.FAILED)
            .asLiteHiddenNotInstalled()
        assertTrue(hidden.environmentFailure == null)
        assertFalse(hidden.showLiteChecking)
    }
}
