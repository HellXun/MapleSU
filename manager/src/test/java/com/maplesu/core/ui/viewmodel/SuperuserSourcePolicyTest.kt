package com.maplesu.core.ui.viewmodel

import dev.maplesu.manager.runtime.State
import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 超管页模式分派表驱动测试（W13/W14：两模式互不干扰）。
 *
 * 断言：LITE 才允许 Lite runtime 方法；KSU 只走 KSU 后端；未判定/后端不可用不发起任何调用；
 * 共享授权意图在 KSU 行上的状态映射正确。
 */
class SuperuserSourcePolicyTest {

    @Test
    fun liteModeUsesLiteRuntimePath() {
        val source = SuperuserSourcePolicy.resolve(WorkMode.LITE, ksuBackendAvailable = false)
        assertEquals(SuperuserSource.LITE_RUNTIME, source)
        assertTrue(SuperuserSourcePolicy.allowsLiteRuntimeCalls(source))
        assertTrue(SuperuserSourcePolicy.allowsBackendCalls(source))
    }

    @Test
    fun liteModeIgnoresKsuAvailabilityFlag() {
        assertEquals(
            SuperuserSource.LITE_RUNTIME,
            SuperuserSourcePolicy.resolve(WorkMode.LITE, ksuBackendAvailable = true),
        )
    }

    @Test
    fun ksuModeUsesKsuBackendAndNeverLiteRuntime() {
        val source = SuperuserSourcePolicy.resolve(WorkMode.LITE, ksuBackendAvailable = true)
        assertEquals(SuperuserSource.KSU_BACKEND, source)
        assertFalse(
            "KSU mode must not call Lite runtime (DirectPolicy/statesFor/SuDelivery)",
            SuperuserSourcePolicy.allowsLiteRuntimeCalls(source),
        )
        assertTrue(SuperuserSourcePolicy.allowsBackendCalls(source))
    }

    @Test
    fun ksuModeWithUnavailableBackendIsExplicitlyBlocked() {
        val source = SuperuserSourcePolicy.resolve(WorkMode.LITE, ksuBackendAvailable = false)
        assertEquals(SuperuserSource.BLOCKED_BACKEND_UNAVAILABLE, source)
        assertFalse(SuperuserSourcePolicy.allowsLiteRuntimeCalls(source))
        assertFalse(SuperuserSourcePolicy.allowsBackendCalls(source))
    }

    @Test
    fun unresolvedModeBlocksEverything() {
        for (ksu in listOf(false, true)) {
            val source = SuperuserSourcePolicy.resolve(null, ksuBackendAvailable = ksu)
            assertEquals(SuperuserSource.BLOCKED_UNRESOLVED, source)
            assertFalse(SuperuserSourcePolicy.allowsLiteRuntimeCalls(source))
            assertFalse(SuperuserSourcePolicy.allowsBackendCalls(source))
        }
    }

    @Test
    fun ksuGrantedRowMapsToWired() {
        assertEquals(
            State.WIRED,
            SuperuserSourcePolicy.ksuSuState(SuperuserSource.KSU_BACKEND, allowSu = true),
        )
    }

    @Test
    fun ksuUngrantedRowMapsToNotAuthorized() {
        assertEquals(
            State.NOT_AUTHORIZED,
            SuperuserSourcePolicy.ksuSuState(SuperuserSource.KSU_BACKEND, allowSu = false),
        )
    }

    @Test
    fun blockedSourcesMakeNoGrantAssertion() {
        for (source in listOf(
            SuperuserSource.BLOCKED_UNRESOLVED,
            SuperuserSource.BLOCKED_BACKEND_UNAVAILABLE,
        )) {
            assertNull(SuperuserSourcePolicy.ksuSuState(source, allowSu = true))
            assertNull(SuperuserSourcePolicy.ksuSuState(source, allowSu = false))
        }
    }

    @Test
    fun liteSourceDoesNotUseKsuStateMapping() {
        // LITE 行状态由 statesFor 给出；KSU 映射函数必须拒绝被误用。
        assertNull(SuperuserSourcePolicy.ksuSuState(SuperuserSource.LITE_RUNTIME, allowSu = true))
    }

    @Test
    fun liteCallsAllowedOnlyForLiteSource() {
        for (source in SuperuserSource.entries) {
            assertEquals(
                "lite runtime calls only for LITE_RUNTIME (source=$source)",
                source == SuperuserSource.LITE_RUNTIME,
                SuperuserSourcePolicy.allowsLiteRuntimeCalls(source),
            )
        }
    }
}
