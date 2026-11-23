// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.workmode.detect

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.activeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T14：环境结论 → 启动判定覆盖（纯映射）。
 *
 * 覆盖只允许发生在两个终态（KSU / 未安装）；LITE、ULTRA、UNKNOWN 必须返回 null，
 * 否则会绕过既有 LITE 握手语义或把一个"读不到"写成结论。
 */
class EnvironmentDetectionProjectionTest {

    private fun outcome(
        verdict: EnvironmentVerdict,
        mode: WorkMode?,
        trigger: EnvironmentCheckTrigger? = EnvironmentCheckTrigger.NO_STATE,
        detail: String = "TEST",
        bootId: String = "boot-1",
    ) = EnvironmentCheckOutcome(
        verdict = verdict,
        mode = mode,
        snapshot = null,
        trigger = trigger,
        detail = detail,
        bootId = bootId,
        elapsedMillis = 12L,
        probes = listOf("su", "ultra"),
    )

    @Test
    fun notInstalledIsTerminalWithoutAnyMode() {
        val detection = EnvironmentDetectionProjection.override(
            outcome(EnvironmentVerdict.NOT_INSTALLED, null, detail = EnvironmentCheckDetail.SU_ABSENT_NO_ULTRA),
        )
        assertNotNull(detection)
        assertEquals(ModeState.NOT_INSTALLED, detection?.state)
        assertNull(detection?.state?.activeMode)
    }

    @Test
    fun ultraAndUnknownNeverOverride() {
        assertNull(
            EnvironmentDetectionProjection.override(outcome(EnvironmentVerdict.ULTRA, WorkMode.LITE)),
        )
        assertNull(
            EnvironmentDetectionProjection.override(
                outcome(EnvironmentVerdict.UNKNOWN, null, detail = EnvironmentCheckDetail.DEADLINE_EXCEEDED),
            ),
        )
    }
}
