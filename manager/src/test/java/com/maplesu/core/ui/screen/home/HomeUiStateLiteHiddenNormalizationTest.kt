package com.maplesu.core.ui.screen.home

import com.maplesu.core.KernelVersion
import com.maplesu.core.ui.util.module.LatestVersionInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A（Lite 隐藏）生效时首页的「未安装」归一化（纯 JVM）。
 *
 * 要求（2026-10-30 第二轮）：隐藏开启时首页/设置里的隐藏入口、切换模式入口全部不显示，
 * App 整体呈"未安装"——沿用现有 `asStealthKernelNotReady` 渲染路径，并把 `isManager` 也
 * 置 false（Lite 隐藏是 App 级伪装，没有内核事实要保留）。
 */
class HomeUiStateLiteHiddenNormalizationTest {

    private fun installed() = HomeUiState(
        kernelVersion = KernelVersion(6, 1, 31),
        ksuVersion = 32513,
        managerUAPIVersion = 6,
        kernelUAPIVersion = 6,
        lkmMode = null,
        isLkmBundled = false,
        isManager = true,
        isManagerPrBuild = true,
        isKernelPrBuild = false,
        requiresNewKernel = false,
        requiresNewManager = false,
        isRootAvailable = true,
        isSafeMode = false,
        checkUpdateEnabled = true,
        latestVersionInfo = LatestVersionInfo(),
        currentManagerVersionCode = 2L,
        systemInfo = SystemInfo("6.1.31", "0.2.0 (2)", "device", "fingerprint", "Enforcing", 0),
        liteInstalled = true,
        liteRootFailure = true,
        litePending = false,
    )

    @Test
    fun hiddenNormalizationLooksExactlyUninstalled() {
        val normalized = installed().asLiteHiddenNotInstalled()
        assertFalse("隐藏生效 ⇒ 不再显示成已安装（ksuVersion 必须清空）", normalized.ksuVersion != null)
        assertFalse("隐藏生效 ⇒ 不再是管理器", normalized.isManager)
        assertFalse("隐藏生效 ⇒ 不得保留 Lite 已安装标记", normalized.liteInstalled)
        assertFalse("隐藏生效 ⇒ 不得保留 Lite 失败提示", normalized.liteRootFailure)
        assertFalse("隐藏生效 ⇒ root 不可用（未安装姿态）", normalized.isRootAvailable)
        assertTrue("隐藏生效 ⇒ 未认主文案路径", normalized.managerNotRecognized)
        assertFalse(normalized.showLiteRootFailure)
        assertFalse(normalized.showLiteChecking)
        assertFalse(normalized.showManagerPrBuildWarning)
        assertFalse(normalized.showRootWarning)
        assertFalse("更新检查必须关闭（伪装期少一条痕迹）", normalized.checkUpdateEnabled)
    }

    @Test
    fun hiddenNormalizationMatchesStealthPathExceptManager() {
        val base = installed()
        val stealth = base.asStealthKernelNotReady()
        val hidden = base.asLiteHiddenNotInstalled()
        // 与 KSU 隐身同一条渲染分支：除了 isManager（Lite 没有内核事实可保留）以外完全一致。
        assertTrue("KSU 隐身保持内核真实 isManager", stealth.isManager)
        assertFalse("A 隐藏额外把 isManager 置 false", hidden.isManager)
        assertTrue(hidden.copy(isManager = true) == stealth)
    }
}
