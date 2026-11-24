package com.maplesu.core.ui.screen.home

import dev.maplesu.manager.litehidden.LiteHiddenState
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.service.SwitchOutcome
import dev.maplesu.manager.workmode.ui.WorkModeSwitchSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页「切换工作模式」入口判据（纯 JVM，不建 Compose、不碰 ModeRuntime/磁盘）。
 *
 * 用户实测反馈（2026-10-30 第二轮）：旧判据只看 `ModeRuntime.active`，于是
 * **未安装** 或 **隐藏/隐身开着** 的机器也画出入口（自欺欺人）；而且旧代码只在 KSU 下判
 * stealth、Ultra 强制 false。现在的判据是唯一一条：
 * `模式已判定 && 已安装/真实管理器态 && 没有任何隐藏/隐身`。
 */
class HomeWorkModeSwitchStateTest {

    @Test fun hiddenSignalDoesNotExposeHiddenModeInformation() {
        val state = homeWorkModeSwitchState(HomeWorkModeSwitchFacts(
            active = WorkMode.LITE, installed = HomeInstallSignal.INSTALLED,
            liteHidden = HomeHiddenSignal.ON))
        assertFalse(state.visible)
        assertNull(state.current)
    }

    private fun facts(
        active: WorkMode? = WorkMode.LITE,
        installed: HomeInstallSignal = HomeInstallSignal.INSTALLED,
        liteHidden: HomeHiddenSignal = HomeHiddenSignal.OFF,
        ksuStealth: HomeHiddenSignal = HomeHiddenSignal.OFF,
        pending: Boolean = false,
    ) = HomeWorkModeSwitchFacts(
        active = active,
        installed = installed,
        liteHidden = liteHidden,
        ksuStealth = ksuStealth,
        switchPending = pending,
    )

    // ── 已安装 + 无隐藏 → 显示且可用（A/B 一致） ──────────────────────────

    @Test
    fun installedLiteShowsEnabledEntry() {
        val s = homeWorkModeSwitchState(facts(active = WorkMode.LITE))
        assertTrue("Ultra 已安装且无隐藏 → 首页必须画入口", s.visible)
        assertTrue("无未完成切换 → 可点", s.enabled)
        assertEquals(WorkMode.LITE, s.current)
        assertEquals(HomeWorkModeSwitchBlock.NONE, s.blocked)
    }

    @Test
    fun installedKsuShowsEnabledEntry() {
        val s = homeWorkModeSwitchState(facts(active = WorkMode.LITE))
        assertTrue("Normal 已安装且无隐身 → 首页同样必须画入口", s.visible)
        assertTrue(s.enabled)
        assertEquals(WorkMode.LITE, s.current)
        assertEquals(HomeWorkModeSwitchBlock.NONE, s.blocked)
    }

    // ── 未安装 / 判定中 → 不显示 ─────────────────────────────────────────

    @Test
    fun notInstalledHidesEntry() {
        val s = homeWorkModeSwitchState(
            facts(active = WorkMode.LITE, installed = HomeInstallSignal.NOT_INSTALLED),
        )
        assertFalse("未安装不许画入口（旧缺陷：模式已判定就当已安装）", s.visible)
        assertEquals(HomeWorkModeSwitchBlock.NOT_INSTALLED, s.blocked)
        assertNull("不可见时不得残留当前模式信息", s.current)
    }

    @Test
    fun installUndeterminedHidesEntry() {
        val s = homeWorkModeSwitchState(
            facts(active = WorkMode.LITE, installed = HomeInstallSignal.UNKNOWN),
        )
        assertFalse("安装证据读不到（判定中/读失败）→ 不画入口", s.visible)
        assertEquals(HomeWorkModeSwitchBlock.NOT_INSTALLED, s.blocked)
    }

    @Test
    fun modeUndeterminedHidesEntry() {
        val s = homeWorkModeSwitchState(facts(active = null, installed = HomeInstallSignal.INSTALLED))
        assertFalse("模式判定中 → 不显示（不是显示一个灰按钮）", s.visible)
        assertEquals(HomeWorkModeSwitchBlock.MODE_UNDETERMINED, s.blocked)
        assertNull(s.current)
    }

    @Test
    fun installUndeterminedWinsOverPendingSwitch() {
        val s = homeWorkModeSwitchState(
            facts(installed = HomeInstallSignal.UNKNOWN, pending = true),
        )
        assertFalse("连是否已安装都不确定时，不能把入口画出来", s.visible)
        assertEquals(HomeWorkModeSwitchBlock.NOT_INSTALLED, s.blocked)
    }

    // ── 隐藏/隐身（A/B 同一判据）→ 不显示 ───────────────────────────────

    @Test
    fun liteHiddenOnHidesEntry() {
        val s = homeWorkModeSwitchState(facts(liteHidden = HomeHiddenSignal.ON))
        assertFalse("Lite 隐藏开着 → App 整体按未安装，入口不许出现", s.visible)
        assertEquals(HomeWorkModeSwitchBlock.HIDDEN_ACTIVE, s.blocked)
        assertNull(s.current)
    }

    @Test
    fun liteHiddenUnknownHidesEntry() {
        val s = homeWorkModeSwitchState(facts(liteHidden = HomeHiddenSignal.UNKNOWN))
        assertFalse("隐藏状态读不到（UNKNOWN）→ 保守按开启，入口不许出现", s.visible)
        assertEquals(HomeWorkModeSwitchBlock.HIDDEN_ACTIVE, s.blocked)
    }

    @Test
    fun ksuStealthOnHidesEntry() {
        val s = homeWorkModeSwitchState(
            facts(active = WorkMode.LITE, ksuStealth = HomeHiddenSignal.ON),
        )
        assertFalse("KSU 隐身开着 → 任何模式信息都不得出现", s.visible)
        assertEquals(HomeWorkModeSwitchBlock.STEALTH_MASKED, s.blocked)
    }

    @Test
    fun ksuStealthUnknownHidesEntry() {
        val s = homeWorkModeSwitchState(
            facts(active = WorkMode.LITE, ksuStealth = HomeHiddenSignal.UNKNOWN),
        )
        assertFalse("KSU stealth 读不到 → 保守按开启（含 UNKNOWN）", s.visible)
        assertEquals(HomeWorkModeSwitchBlock.STEALTH_MASKED, s.blocked)
    }

    @Test
    fun hiddenOffAndInstalledShowsEntry() {
        for (mode in WorkMode.entries) {
            val s = homeWorkModeSwitchState(
                facts(active = mode, liteHidden = HomeHiddenSignal.OFF, ksuStealth = HomeHiddenSignal.OFF),
            )
            assertTrue("${mode.name}：隐藏关闭且已安装 → 显示", s.visible)
            assertTrue(s.enabled)
        }
    }

    @Test
    fun sameJudgeForBothModes() {
        // "KSU 与 Ultra 同一判据"：同一组隐藏信号在两种模式下结论一致，不再有模式特例。
        for (mode in WorkMode.entries) {
            for (hidden in HomeHiddenSignal.entries) {
                val s = homeWorkModeSwitchState(facts(active = mode, liteHidden = hidden))
                assertEquals(
                    "${mode.name}/liteHidden=${hidden.name}：隐藏判据必须与模式无关",
                    hidden.masks,
                    !s.visible,
                )
            }
            for (stealth in HomeHiddenSignal.entries) {
                val s = homeWorkModeSwitchState(facts(active = mode, ksuStealth = stealth))
                assertEquals(
                    "${mode.name}/ksuStealth=${stealth.name}：隐身判据必须与模式无关",
                    stealth.masks,
                    !s.visible,
                )
            }
        }
    }

    @Test
    fun hiddenWinsOverPendingSwitch() {
        val s = homeWorkModeSwitchState(
            facts(active = WorkMode.LITE, liteHidden = HomeHiddenSignal.ON, pending = true),
        )
        assertFalse("隐藏优先级最高", s.visible)
        assertEquals(HomeWorkModeSwitchBlock.HIDDEN_ACTIVE, s.blocked)
    }

    @Test
    fun hiddenEntryNeverCarriesCurrentMode() {
        // 入口没画时，状态里不得残留"当前是 Ultra/Normal"（防止将来有渲染方单独读它）。
        val hiddenStates = listOf(
            homeWorkModeSwitchState(facts(liteHidden = HomeHiddenSignal.ON)),
            homeWorkModeSwitchState(facts(ksuStealth = HomeHiddenSignal.UNKNOWN)),
            homeWorkModeSwitchState(facts(installed = HomeInstallSignal.NOT_INSTALLED)),
            homeWorkModeSwitchState(facts(active = null)),
        )
        hiddenStates.forEach { assertNull(it.current) }
    }

    // ── 切换进行中：显示但禁用 ───────────────────────────────────────────

    @Test
    fun pendingSwitchKeepsEntryVisibleButDisabled() {
        val s = homeWorkModeSwitchState(facts(pending = true))
        assertTrue("切换进行中入口仍要看得见（否则用户找不到进度/恢复）", s.visible)
        assertFalse("切换进行中必须禁用，避免打乱状态机", s.enabled)
        assertEquals(HomeWorkModeSwitchBlock.SWITCH_PENDING, s.blocked)
        assertEquals(WorkMode.LITE, s.current)
    }

    @Test
    fun visibleEntryAlwaysCarriesCurrentMode() {
        for (mode in WorkMode.entries) {
            assertTrue(homeWorkModeSwitchState(facts(active = mode)).current != null)
            assertTrue(homeWorkModeSwitchState(facts(active = mode, pending = true)).current != null)
        }
    }

    @Test
    fun hiddenDefaultDrawsNothing() {
        assertFalse(HomeWorkModeSwitchState.HIDDEN.visible)
        assertFalse(HomeWorkModeSwitchState.HIDDEN.enabled)
        assertNull(HomeWorkModeSwitchState.HIDDEN.current)
    }

    // ── 信号映射（数据源注入的边界） ─────────────────────────────────────

    @Test
    fun liteHiddenSignalOnlyOffMeansVisible() {
        assertEquals(HomeHiddenSignal.OFF, HomeHiddenSignal.ofLiteHidden(LiteHiddenState.OFF, observed = true))
        assertEquals(HomeHiddenSignal.ON, HomeHiddenSignal.ofLiteHidden(LiteHiddenState.ON, observed = true))
        assertEquals(
            HomeHiddenSignal.UNKNOWN,
            HomeHiddenSignal.ofLiteHidden(LiteHiddenState.UNKNOWN, observed = true),
        )
        assertEquals(
            "进程内还没读到可信状态 → 必须按 UNKNOWN（保守按开启）",
            HomeHiddenSignal.UNKNOWN,
            HomeHiddenSignal.ofLiteHidden(LiteHiddenState.OFF, observed = false),
        )
    }

    @Test
    fun hiddenSignalMasksEverythingButOff() {
        assertFalse(HomeHiddenSignal.OFF.masks)
        assertTrue(HomeHiddenSignal.ON.masks)
        assertTrue("UNKNOWN 保守按开启", HomeHiddenSignal.UNKNOWN.masks)
        assertEquals(HomeHiddenSignal.ON, HomeHiddenSignal.of(true))
        assertEquals(HomeHiddenSignal.OFF, HomeHiddenSignal.of(false))
    }

    // ── 可注入数据源（不依赖 Android 运行时） ────────────────────────────

    @Test
    fun completedWriteAwaitingRebootDoesNotDisableAnotherUserOperation() {
        val runtime = HomeWorkModeSwitchRuntime
        val oldFacts = runtime.factsSource
        try {
            runtime.factsSource = HomeWorkModeSwitchFactsSource {
                facts(active = WorkMode.LITE, installed = HomeInstallSignal.INSTALLED)
            }
            WorkModeSwitchSession.finish(SwitchOutcome.AwaitingReboot("previous-switch", null))
            assertTrue(WorkModeSwitchSession.pending)
            assertFalse(WorkModeSwitchSession.executing)
            assertTrue(runtime.current().enabled)
            WorkModeSwitchSession.begin()
            assertFalse(runtime.current().enabled)
        } finally {
            runtime.factsSource = oldFacts
            WorkModeSwitchSession.finish(SwitchOutcome.ImagesRequired(WorkMode.LITE, false))
        }
    }

    @Test
    fun runtimeUsesInjectedFactsSource() {
        val runtime = HomeWorkModeSwitchRuntime
        val oldFacts = runtime.factsSource
        val oldPending = runtime.pendingSwitchSource
        try {
            runtime.factsSource = HomeWorkModeSwitchFactsSource {
                facts(active = WorkMode.LITE, installed = HomeInstallSignal.INSTALLED)
            }
            runtime.pendingSwitchSource = HomeSwitchPendingSource { true }
            val pending = runtime.current()
            assertTrue(pending.visible)
            assertFalse(pending.enabled)
            assertEquals(HomeWorkModeSwitchBlock.SWITCH_PENDING, pending.blocked)

            runtime.pendingSwitchSource = HomeSwitchPendingSource { false }
            runtime.factsSource = HomeWorkModeSwitchFactsSource {
                facts(
                    active = WorkMode.LITE,
                    installed = HomeInstallSignal.INSTALLED,
                    liteHidden = HomeHiddenSignal.UNKNOWN,
                )
            }
            val hidden = runtime.current()
            assertFalse("注入的隐藏信号必须生效（A 通道）", hidden.visible)
            assertEquals(HomeWorkModeSwitchBlock.HIDDEN_ACTIVE, hidden.blocked)
        } finally {
            runtime.factsSource = oldFacts
            runtime.pendingSwitchSource = oldPending
        }
    }

    @Test
    fun runtimeFallsBackToHiddenWhenFactsSourceThrows() {
        val runtime = HomeWorkModeSwitchRuntime
        val oldFacts = runtime.factsSource
        try {
            runtime.factsSource = HomeWorkModeSwitchFactsSource { error("boom") }
            val s = runtime.current()
            assertFalse("事实源异常 → 不显示（绝不默认显示）", s.visible)
        } finally {
            runtime.factsSource = oldFacts
        }
    }
}
