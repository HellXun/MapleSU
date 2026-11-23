// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.modules

import com.maplesu.core.R
import com.maplesu.core.data.model.Module
import com.maplesu.core.data.repository.ModuleBackendUnavailableException
import com.maplesu.core.data.repository.ModuleModeUnresolvedException
import com.maplesu.core.ui.screen.module.ModuleUiState
import com.maplesu.core.ui.screen.module.withModuleLoadResult
import com.maplesu.core.ui.screen.module.withModulePending
import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T10（D4 冷启动竞态）状态机测试：**纯 JVM**，不依赖 Android framework、协程或线程。
 *
 * 缺陷复现前提：模块列表在 Activity 组合期预取，早于 `ModeBootstrap` 的 KSU 后端接线；
 * 旧实现把 `Unresolved`/`Unavailable` 当终态失败（`loadError` + `hasLoaded=true`）
 * ⇒ 第一次失败即"永久空态"，只有手动切页才好。
 */
class ModuleStartupGateTest {

    private val fixture = Module(
        id = "fixture",
        name = "Fixture",
        author = "MapleSU",
        version = "1",
        versionCode = 1,
        description = "",
        enabled = true,
        update = false,
        remove = false,
        updateJson = "",
        hasWebUi = false,
        hasActionScript = false,
        metamodule = false,
        actionIconPath = null,
        webUiIconPath = null,
    )

    /**
     * 与 `ModuleViewModel` 同构的最小驱动器：请求 → （等待 | 取）→ 一次性事件 → 自动重取 → 终态。
     */
    private class Harness(initial: ModuleRuntimeFacts) {
        var facts: ModuleRuntimeFacts = initial
        val gate = ModuleStartupGate { facts }
        var state = ModuleUiState()
        var fetches = 0
        var automaticRefetches = 0

        /** @return true = 进入了"启动中"（本次**没有**取列表） */
        fun requestLoad(automatic: Boolean = false): Boolean =
            when (val decision = gate.onLoadRequested()) {
                ModuleLoadDecision.Fetch -> {
                    fetches++
                    if (automatic) automaticRefetches++
                    false
                }

                is ModuleLoadDecision.Wait -> {
                    state = state.withModulePending(decision.reason)
                    true
                }
            }

        /** 一次性就绪事件；true = 真的触发了自动重取一次。 */
        fun environmentReady(): Boolean {
            if (!gate.onEnvironmentReady()) return false
            requestLoad(automatic = true)
            return true
        }

        fun publish(result: Result<List<Module>>) {
            state = state.withModuleLoadResult(result)
            gate.onTerminal()
        }
    }

    @Test
    fun unresolvedShowsPendingThenActivationRefetchesOnceIntoARealList() {
        val harness = Harness(ModuleRuntimeFacts(mode = null, ksuBackendWired = false))

        // 1) 未判定：进入"启动中"，不是失败、不是空列表，也绝不先取一次。
        assertTrue(harness.requestLoad())
        assertEquals(0, harness.fetches)
        val pending = harness.state
        assertTrue(pending.pending)
        assertEquals("MODE_UNRESOLVED", pending.pendingReason)
        assertNull("等待接线不是失败", pending.loadError)
        assertFalse("hasLoaded 只在终态为 true", pending.hasLoaded)
        assertEquals(R.string.module_environment_pending, pending.emptyStateMessage)
        assertNotEquals(R.string.module_load_failed, pending.emptyStateMessage)

        // 2) 激活 + 接线完成：一次性事件 ⇒ 允许自动重取一次。
        harness.facts = ModuleRuntimeFacts(mode = WorkMode.LITE, ksuBackendWired = true)
        assertTrue(harness.environmentReady())
        assertEquals(1, harness.fetches)
        assertEquals(1, harness.automaticRefetches)
        assertNull(harness.gate.pending)

        // 3) 自动重取成功 ⇒ 列表非空 + hasLoaded=true（终态）。
        harness.publish(Result.success(listOf(fixture)))
        val loaded = harness.state
        assertFalse(loaded.pending)
        assertNull(loaded.pendingReason)
        assertTrue(loaded.hasLoaded)
        assertEquals(listOf(fixture), loaded.modules)
        assertNull(loaded.loadError)
        assertNull("非空列表不显示任何空态文案", loaded.emptyStateMessage)
    }

    @Test
    fun unavailableBackendIsANamedTerminalErrorNeverAnEmptyListAndNeverPending() {
        // 环境已就绪（模式已判定、端口已接线），但端口自己不可用（ksud 缺失等）：
        // 这是**终态**的具名失败，必须可见。
        val harness = Harness(ModuleRuntimeFacts(mode = WorkMode.LITE, ksuBackendWired = true))
        assertFalse(harness.requestLoad())
        assertEquals(1, harness.fetches)

        val failure = ModuleBackendUnavailableException("ksu backend unavailable")
        harness.publish(Result.failure(failure))
        val state = harness.state

        assertFalse("不可用不得冒充 pending", state.pending)
        assertNull(state.pendingReason)
        assertTrue(state.hasLoaded)
        assertSame(failure, state.loadError)
        assertTrue(
            "失败必须带具名 code",
            state.loadError?.message.orEmpty().startsWith("BACKEND_UNAVAILABLE"),
        )
        assertEquals(R.string.module_load_failed, state.emptyStateMessage)
        assertNotEquals("不得用空列表伪装成功", R.string.module_empty, state.emptyStateMessage)
        assertNotEquals(R.string.module_environment_pending, state.emptyStateMessage)
    }

    @Test
    fun terminalSuccessNeverRearmsTheAutomaticRefetch() {
        val harness = Harness(ModuleRuntimeFacts(mode = null, ksuBackendWired = false))
        assertTrue(harness.requestLoad())
        harness.facts = ModuleRuntimeFacts(mode = WorkMode.LITE, ksuBackendWired = true)
        assertTrue(harness.environmentReady())
        harness.publish(Result.success(listOf(fixture)))
        assertEquals(1, harness.fetches)

        // 终态之后：手动刷新照常可取的，但**绝不**再自动重取（幂等）。
        assertFalse(harness.environmentReady())
        assertEquals(1, harness.automaticRefetches)
        assertFalse(harness.requestLoad())
        assertEquals(2, harness.fetches)
        assertEquals(1, harness.automaticRefetches)

        // 即使事实又变回未判定（进程内不会发生），也不会再进入"启动中"。
        harness.facts = ModuleRuntimeFacts(mode = null, ksuBackendWired = false)
        assertEquals(ModuleLoadDecision.Fetch, harness.gate.onLoadRequested())
        assertNull(harness.gate.pending)
    }

    @Test
    fun theKsuWiringRaceStaysPendingAndNeverFetchesOnce() {
        // D4 的精确窗口：已判定 KSU，但 Activated 观察者还没把端口接上。
        val harness = Harness(ModuleRuntimeFacts(mode = WorkMode.LITE, ksuBackendWired = false))
        assertTrue(harness.requestLoad())

        assertEquals("先取再把 Unavailable 当失败，正是 D4 的根因", 0, harness.fetches)
        assertEquals("KSU_BACKEND_UNWIRED", harness.state.pendingReason)
        assertTrue(harness.state.pending)
        assertNull(harness.state.loadError)
        assertFalse(harness.state.hasLoaded)
        assertEquals(R.string.module_environment_pending, harness.state.emptyStateMessage)

        // 一次性的等待**幂等**：重复请求仍是同一个等待，不会重复订阅。
        assertEquals(
            ModuleLoadDecision.Wait(ModuleWaitReason.KSU_BACKEND_UNWIRED),
            harness.gate.onLoadRequested(),
        )
    }

    @Test
    fun aTimedOutWaitStillProducesAVisibleFailureInsteadOfAnEndlessStartupState() {
        val harness = Harness(ModuleRuntimeFacts(mode = null, ksuBackendWired = false))
        assertTrue(harness.requestLoad())

        // 超时：允许做一次"如实失败"的取列表，而不是无限停在"启动中"。
        assertTrue(harness.gate.onWaitTimedOut())
        assertFalse(harness.requestLoad())
        assertEquals(1, harness.fetches)

        harness.publish(Result.failure(ModuleModeUnresolvedException()))
        val state = harness.state
        assertFalse(state.pending)
        assertTrue(state.hasLoaded)
        assertTrue(state.loadError is ModuleModeUnresolvedException)
        assertEquals(R.string.module_load_failed, state.emptyStateMessage)
    }
}
