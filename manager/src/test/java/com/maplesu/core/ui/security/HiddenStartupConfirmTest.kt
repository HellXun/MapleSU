package com.maplesu.core.ui.security

import com.maplesu.core.ui.screen.home.HomeUiState
import com.maplesu.core.ui.screen.home.asLiteHiddenNotInstalled
import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隐藏/寄生的「确认窗口」：在寄生结果与运行环境确认前，首页只显示既有确认提示
 * （正在确认 MapleSU 运行环境，请稍候），不先报「正在运行」再翻「未安装」。
 */
class HiddenStartupConfirmTest {

    @Test
    fun confirmingUntilParasiteAndEnvironmentAreBothKnown() {
        // 事务进行中 → 确认中（哪怕已有旧状态）。
        assertTrue(HiddenStartupConfirm.confirming(true, true, false, true))
        // 还没拿到任何隐藏状态 → 确认中（绝不先画运行）。
        assertTrue(HiddenStartupConfirm.confirming(false, false, false, false))
        // 上次已知隐藏 ON，但本进程还没真实读过 → 确认中（先画运行就是自曝）。
        assertTrue(HiddenStartupConfirm.confirming(false, true, true, false))
        // 已知隐藏 ON 且真实读回 → 已确认（交给隐藏伪装路径画「未安装」）。
        assertFalse(HiddenStartupConfirm.confirming(false, true, true, true))
        // 正常机器（种子 OFF、已观测）→ 直接画运行。
        assertFalse(HiddenStartupConfirm.confirming(false, true, false, false))
    }

    @Test
    fun unresolvedModeStillMasksAConfirmedHiddenDevice() {
        // 冷启动首帧 mode=null：隐藏机器不能先画成运行。
        assertTrue(HiddenStartupConfirm.masksWithMode(null, hiddenOn = true))
        assertTrue(HiddenStartupConfirm.masksWithMode(WorkMode.LITE, hiddenOn = true))
        assertFalse(HiddenStartupConfirm.masksWithMode(null, hiddenOn = false))
    }

    @Test
    fun unknownOnlyMasksWhenLastKnownStateWasOn() {
        val lite = dev.maplesu.manager.workmode.WorkMode.LITE
        // 读失败（UNKNOWN）但没有"上次隐藏 ON"证据 → 不伪装（否则正常机器会白屏未安装）。
        assertFalse(
            HiddenStartupConfirm.masks(
                dev.maplesu.manager.litehidden.LiteHiddenState.UNKNOWN, lastKnownOn = false, active = lite,
            ),
        )
        // 读失败但上次明确隐藏 ON → 保守伪装。
        assertTrue(
            HiddenStartupConfirm.masks(
                dev.maplesu.manager.litehidden.LiteHiddenState.UNKNOWN, lastKnownOn = true, active = lite,
            ),
        )
        // 明确读到 ON → 伪装（模式未判定也伪装）；明确 OFF → 无论种子都不伪装。
        assertTrue(
            HiddenStartupConfirm.masks(
                dev.maplesu.manager.litehidden.LiteHiddenState.ON, lastKnownOn = false, active = null,
            ),
        )
        assertFalse(
            HiddenStartupConfirm.masks(
                dev.maplesu.manager.litehidden.LiteHiddenState.OFF, lastKnownOn = true, active = lite,
            ),
        )
        // 明确 KSU：走内核 stealth 判据，不按 Lite 隐藏。
        assertFalse(
            HiddenStartupConfirm.masks(
                dev.maplesu.manager.litehidden.LiteHiddenState.ON,
                lastKnownOn = true,
                active = dev.maplesu.manager.workmode.WorkMode.LITE,
            ),
        )
    }

    @Test
    fun homeShowsTheExistingCheckingHintWhileConfirming() {
        // 复用既有 showLiteChecking 渲染（home_status_checking /
        // home_status_checking_summary「正在确认 MapleSU 运行环境，请稍候」）。
        val base = HomeUiState(
            kernelVersion = com.maplesu.core.KernelVersion(1, 0, 0),
            ksuVersion = 123,
            managerUAPIVersion = 1,
            kernelUAPIVersion = 1,
            lkmMode = true,
            isLkmBundled = false,
            isManager = true,
            isManagerPrBuild = false,
            isKernelPrBuild = false,
            requiresNewKernel = false,
            requiresNewManager = false,
            isRootAvailable = true,
            isSafeMode = false,
            checkUpdateEnabled = false,
            latestVersionInfo = com.maplesu.core.ui.util.module.LatestVersionInfo(),
            currentManagerVersionCode = 1,
            systemInfo = com.maplesu.core.ui.screen.home.SystemInfo(
                kernelVersion = "6.6.89",
                managerVersion = "x",
                deviceModel = "d",
                fingerprint = "f",
                selinuxStatus = "Enforcing",
                seccompStatus = 2,
            ),
            liteInstalled = true,
            hiddenConfirming = true,
            environmentChecking = true,
        )
        assertTrue("确认窗口必须画既有确认提示", base.showLiteChecking)
        // 已确认隐藏 → 归一化成「未安装」的同时清掉确认态，不再画确认提示。
        val hidden = base.asLiteHiddenNotInstalled()
        assertFalse(hidden.hiddenConfirming)
        assertFalse(hidden.showLiteChecking)
        // 确认完成（非隐藏）→ 正常状态卡。
        assertFalse(base.copy(hiddenConfirming = false).showLiteChecking)
    }
}
