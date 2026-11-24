package com.maplesu.core.ui.screen.home

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 「已安装/真实管理器态」的组合判据（纯 JVM）。
 *
 * 用户判据：入口的显示条件里，"已安装"**禁止**由"模式已判定"推导；只能用真实事实
 * （Natives.isManager / Ultra Running / 本地 Lite 安装证据）。本测试锁定：
 *  - `active = LITE/KSU` 本身**不算**已安装；
 *  - 本地 Lite 证据只在 LITE 模式算数（KSU 下残留证据不得翻成已安装）；
 *  - 关键读取失败（isManager 读不到）→ UNKNOWN（按"不能确认"处理）。
 */
class HomeInstallSignalTest {

    private fun signal(
        active: WorkMode? = WorkMode.LITE,
        manager: Boolean? = false,
        ultraRunning: Boolean = false,
        liteEvidence: Boolean = false,
        detectedA: Boolean = false,
        lastKnown: Boolean = false,
    ) = homeInstallSignal(
        active = active,
        manager = manager,
        ultraRunning = ultraRunning,
        liteEvidence = liteEvidence,
        detectedLiteState = detectedA,
        lastKnownLiteInstalled = lastKnown,
    )

    @Test
    fun modeDecisionAloneIsNotInstallEvidence() {
        for (mode in WorkMode.entries) {
            assertEquals(
                "${mode.name}：模式已判定 ≠ 已安装",
                HomeInstallSignal.NOT_INSTALLED,
                signal(active = mode),
            )
        }
    }

    @Test
    fun realManagerMeansInstalled() {
        for (mode in WorkMode.entries) {
            assertEquals(HomeInstallSignal.INSTALLED, signal(active = mode, manager = true))
        }
    }

    @Test
    fun ultraRunningMeansInstalled() {
        for (mode in WorkMode.entries) {
            assertEquals(HomeInstallSignal.INSTALLED, signal(active = mode, ultraRunning = true))
        }
    }

    @Test
    fun liteEvidenceOnlyCountsInLiteMode() {
        assertEquals(
            "LITE + 本地凭据 → 已安装",
            HomeInstallSignal.INSTALLED,
            signal(active = WorkMode.LITE, liteEvidence = true),
        )
    }

    @Test
    fun detectedAStateAndLastKnownSeedAreLiteEvidence() {
        assertEquals(
            HomeInstallSignal.INSTALLED,
            signal(active = WorkMode.LITE, detectedA = true),
        )
        assertEquals(
            HomeInstallSignal.INSTALLED,
            signal(active = WorkMode.LITE, lastKnown = true),
        )
    }

    @Test
    fun managerReadFailureIsUnknown() {
        assertEquals(
            "isManager 读失败且没有任何正向证据 → 不能确认已安装（不画入口）",
            HomeInstallSignal.UNKNOWN,
            signal(active = WorkMode.LITE, manager = null),
        )
    }

    @Test
    fun positiveEvidenceWinsOverManagerReadFailure() {
        // 2026-10-31 真机：Ultra 已 Running 但 isManager 一次读失败 → 旧实现把切换入口永久藏掉。
        assertEquals(
            "Ultra Running 足以证明已安装",
            HomeInstallSignal.INSTALLED,
            signal(active = WorkMode.LITE, manager = null, ultraRunning = true),
        )
        assertEquals(
            "本地 Lite 证据足以证明已安装",
            HomeInstallSignal.INSTALLED,
            signal(active = WorkMode.LITE, manager = null, liteEvidence = true),
        )
    }

    @Test
    fun undeterminedModeIsUnknown() {
        assertEquals(
            HomeInstallSignal.UNKNOWN,
            signal(active = null, manager = true),
        )
    }

    @Test
    fun notInstalledSignalNeverOpensTheEntry() {
        val state = homeWorkModeSwitchState(
            HomeWorkModeSwitchFacts(
                active = WorkMode.LITE,
                installed = signal(active = WorkMode.LITE),
            ),
        )
        assertFalse("组合判据与入口裁决必须一致", state.visible)
        assertEquals(HomeWorkModeSwitchBlock.NOT_INSTALLED, state.blocked)
    }
}
