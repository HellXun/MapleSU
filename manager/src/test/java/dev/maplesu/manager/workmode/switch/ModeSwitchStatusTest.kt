package dev.maplesu.manager.workmode.switch

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** 恢复 UI/外部恢复指引数据接口（02 §7.1/§7.4/§7.5）。 */
class ModeSwitchStatusTest {

    private val switchId = UUID.randomUUID().toString()

    private fun record(state: String = SwitchStateNames.RECOVERY_REQUIRED) = SwitchRecord(
        switchId = switchId,
        planSha256 = "a".repeat(64),
        state = state,
        stage = "S4",
        step = SwitchSteps.WRITE_CANDIDATE,
        fromMode = "LITE",
        toMode = "KSU",
        baselineId = "bl-1",
        startBootId = "boot-1",
        error = SwitchRecordError(
            ModeSwitchCodes.WRITE_ROLLBACK_FAILED, "S4", SwitchSteps.WRITE_CANDIDATE, "restore failed", 5,
        ),
        device = SwitchRecordDevice("_a", "fw-1", "5.10.198-android13-8", "android13-5.10"),
        partitions = listOf(
            SwitchRecordPartition("boot", "RESTORE_BASELINE", 4096, "b".repeat(64), "c".repeat(64),
                "d".repeat(64), "DONE", "c".repeat(64)),
            SwitchRecordPartition("init_boot", "WRITE_CANDIDATE", 8192, "e".repeat(64), "f".repeat(64),
                "1".repeat(64), "FAILED", "9".repeat(64)),
        ),
        artifacts = mapOf(
            "pre-boot" to "/data/adb/maplesu-workmode/boot/work/$switchId/pre-boot.img",
            "base-init_boot" to "/data/adb/maplesu-workmode/boot/work/$switchId/base-init_boot.img",
        ),
    )

    @Test
    fun waitingForRebootStatusOffersRollbackAndResume() {
        val status = requireNotNull(
            ModeSwitchStatusProjector.project(
                record(SwitchStateNames.REBOOT_REQUIRED).copy(error = SwitchRecordError()),
            ),
        )
        assertTrue(status.pending)
        assertTrue(status.rebootRequired)
        assertTrue(status.canRollback)
        assertTrue(status.canResumeReboot)
        assertTrue(status.canExport)
        assertFalse(status.requiresExternalRecovery)
        assertNull(status.externalRecovery)
        assertEquals(WorkMode.LITE, status.fromMode)
        assertEquals(WorkMode.LITE, status.toMode)
    }

    @Test
    fun observedRebootDisablesResumeButKeepsVerificationData() {
        val status = ModeSwitchStatusProjector.project(
            record(SwitchStateNames.REBOOT_REQUIRED).copy(error = SwitchRecordError()), bootIdChanged = true,
        )!!
        assertFalse(status.canResumeReboot)
        assertTrue(status.bootObserved)
    }

    @Test
    fun recoveryRequiredCarriesCopyableExternalGuide() {
        val status = ModeSwitchStatusProjector.project(record())!!
        assertTrue(status.requiresExternalRecovery)
        val guide = requireNotNull(status.externalRecovery)
        assertEquals(switchId, guide.switchId)
        assertEquals(ModeSwitchCodes.WRITE_ROLLBACK_FAILED, guide.code)
        // 恢复顺序 = 写序逆序（先目标后源）。
        assertEquals(listOf("init_boot", "boot"), guide.recoveryOrder)
        assertEquals(2, guide.partitions.size)
        assertTrue(guide.images.any { it.startsWith("pre-boot=") })
        assertTrue(guide.instructions.contains("dd if="))
        assertTrue(guide.instructions.contains("fastboot flash"))
        assertTrue(guide.instructions.contains("不自动重启"))
        assertFalse(guide.autoRetry)
    }

    @Test
    fun recoveryUiStateProjectsSameDataAndIsNullWithoutRecord() {
        val ui = requireNotNull(ModeSwitchStatusProjector.recoveryUiState(record(), bootIdChanged = false))
        assertEquals(SwitchStateNames.RECOVERY_REQUIRED, ui.state)
        assertTrue(ui.requiresExternalRecovery)
        assertEquals(2, ui.partitions.size)
        assertNotNull(ui.externalRecovery)
        assertFalse(ui.canResumeReboot)
        assertNull(ModeSwitchStatusProjector.project(null))
        assertNull(ModeSwitchStatusProjector.recoveryUiState(null))
    }

    @Test
    fun verifiedStatusIsTerminalAndDoesNotBlockWrites() {
        val verified = record(SwitchStateNames.VERIFIED).copy(error = SwitchRecordError())
        val status = ModeSwitchStatusProjector.project(verified)!!
        assertFalse(status.pending)
        assertFalse(status.requiresExternalRecovery)
        assertFalse(status.canResumeReboot)
    }
}
