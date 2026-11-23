package dev.maplesu.manager.workmode.ui

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.ModeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W08 切换可用性矩阵（纯 JVM 表驱动）。 */
class SwitchAvailabilityPolicyTest {

    private val readyBackend = SwitchBackendSnapshot(
        available = true,
        baselineId = "baseline-1",
    )

    private fun evaluate(
        state: ModeState = ModeState.MODE_A_ACTIVE,
        current: WorkMode? = WorkMode.LITE,
        stealth: Boolean = false,
        pending: Boolean = false,
        backend: SwitchBackendSnapshot = readyBackend,
    ) = SwitchAvailabilityPolicy.evaluate(state, current, stealth, pending, backend)

    @Test
    fun aActiveWithBaselineCanSwitchToKsu() {
        val availability = evaluate()
        assertTrue(availability.canSwitch)
        assertEquals(WorkMode.LITE, availability.current)
        assertEquals(WorkMode.LITE, availability.target)
        assertEquals("baseline-1", availability.baselineId)
        assertTrue(availability.blockers.isEmpty())
    }

    @Test
    fun bActiveCanSwitchToLite() {
        val availability = evaluate(state = ModeState.MODE_B_ACTIVE, current = WorkMode.LITE)
        assertTrue(availability.canSwitch)
        assertEquals(WorkMode.LITE, availability.target)
    }

    @Test
    fun stealthMaskedBlocksEverything() {
        val availability = evaluate(stealth = true)
        assertFalse(availability.canSwitch)
        assertTrue(availability.blockers.any { it.code == SwitchBlockerCodes.STEALTH_MASKED })
    }

    @Test
    fun undeterminedStateIsBlocked() {
        val availability = evaluate(state = ModeState.INDETERMINATE, current = null)
        assertFalse(availability.canSwitch)
        assertTrue(availability.blockers.any { it.code == SwitchBlockerCodes.MODE_UNDETERMINED })
        assertTrue(availability.blockers.any { it.code == SwitchBlockerCodes.SAME_MODE })
    }

    @Test
    fun notInstalledIsBlocked() {
        val availability = evaluate(state = ModeState.NOT_INSTALLED, current = null)
        assertFalse(availability.canSwitch)
        assertTrue(availability.blockers.any { it.code == SwitchBlockerCodes.MODE_NOT_INSTALLED })
    }

    @Test
    fun foreignRootIsBlocked() {
        val availability = evaluate(state = ModeState.FOREIGN_SU, current = null)
        assertFalse(availability.canSwitch)
        assertTrue(availability.blockers.any { it.code == SwitchBlockerCodes.MODE_FOREIGN_SU })
    }

    @Test
    fun aUnboundAndKernelOnlyAreBlocked() {
        val unbound = evaluate(state = ModeState.MODE_A_INSTALLED_UNBOUND, current = null)
        assertTrue(unbound.blockers.any { it.code == SwitchBlockerCodes.MODE_A_UNBOUND })
        val kernelOnly = evaluate(state = ModeState.MODE_B_KERNEL_ONLY, current = null)
        assertTrue(kernelOnly.blockers.any { it.code == SwitchBlockerCodes.MODE_B_KERNEL_ONLY })
    }

    @Test
    fun pendingSwitchBlocks() {
        val availability = evaluate(pending = true)
        assertFalse(availability.canSwitch)
        assertTrue(availability.blockers.any { it.code == SwitchBlockerCodes.PENDING_SWITCH })
    }

    @Test
    fun missingBackendBlocksWithReason() {
        val availability = evaluate(backend = SwitchBackendSnapshot(available = false, reason = "not wired"))
        assertFalse(availability.canSwitch)
        val blocker = availability.blockers.first { it.code == SwitchBlockerCodes.BACKEND_UNAVAILABLE }
        assertEquals("not wired", blocker.detail)
    }

    @Test
    fun missingBaselineBlocks() {
        val availability = evaluate(
            backend = SwitchBackendSnapshot(available = true, baselineId = null),
        )
        assertFalse(availability.canSwitch)
        assertTrue(availability.blockers.any { it.code == SwitchBlockerCodes.BASELINE_MISSING })
    }

    @Test
    fun backendExtraBlockersArePropagated() {
        val availability = evaluate(
            backend = readyBackend.copy(
                extraBlockers = listOf(SwitchBlocker("SWITCH_INSTALL_PENDING")),
            ),
        )
        assertFalse(availability.canSwitch)
        assertTrue(availability.blockers.any { it.code == "SWITCH_INSTALL_PENDING" })
    }

    @Test
    fun allBlockersAccumulateInsteadOfFirstOnly() {
        val availability = evaluate(
            state = ModeState.INDETERMINATE,
            current = null,
            pending = true,
            backend = SwitchBackendSnapshot.UNAVAILABLE,
        )
        val codes = availability.blockers.map { it.code }.toSet()
        assertTrue(SwitchBlockerCodes.MODE_UNDETERMINED in codes)
        assertTrue(SwitchBlockerCodes.PENDING_SWITCH in codes)
        assertTrue(SwitchBlockerCodes.BACKEND_UNAVAILABLE in codes)
    }
}
