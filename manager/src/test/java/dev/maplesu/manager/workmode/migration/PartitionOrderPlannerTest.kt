package dev.maplesu.manager.workmode.migration

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 05 §3：最终镜像计划 / 顺序 / residual / 相交误序拒绝（T-16…T-19）。 */
class PartitionOrderPlannerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val bootSize = 64L * 1024 * 1024
    private val initBootSize = 8L * 1024 * 1024

    private fun pre(): Map<String, ImageRef> = mapOf(
        "boot" to Fixtures.image("backup/images/pre-boot.img", bootSize, "pre-boot"),
        "init_boot" to Fixtures.image("backup/images/pre-init_boot.img", initBootSize, "pre-init"),
    )

    @Test
    fun ksuToLiteRestoresExternalPartitionBeforeWritingCandidate() {
        val input = Fixtures.planInput(
            pre = pre(),
            clean = mapOf("init_boot" to Fixtures.image("backup/images/clean-init_boot.img", initBootSize, "clean-init")),
            patched = setOf("init_boot"),
            family = Family.KSU_UPSTREAM,
            target = WorkMode.LITE,
        )
        val plan = PartitionOrderPlanner.compose(input)
        assertEquals(setOf("init_boot"), plan.restoreSet)
        assertEquals(setOf("boot"), plan.targetSet)
        assertEquals(listOf("init_boot", "boot"), plan.writeOrder)
        assertEquals(listOf("boot", "init_boot"), plan.rollbackOrder)
        assertEquals(PartitionAction.RESTORE_EXTERNAL, plan.partition("init_boot").action)
        assertEquals(PartitionAction.WRITE_CANDIDATE, plan.partition("boot").action)
        // 非相交目标分区：pre 就是 stock，可直接作为 producer 输入
        assertEquals("pre-boot", plan.partition("boot").producerInput?.sha256?.let(::labelOf))
        assertFalse(plan.residual)
    }

    @Test
    fun magiskBootLayoutToLiteRestoresBootThenWritesCandidate() {
        val input = Fixtures.planInput(
            pre = pre(),
            clean = mapOf("boot" to Fixtures.image("clean-boot", bootSize, "clean-boot")),
            patched = setOf("boot"),
            family = Family.MAGISK,
            target = WorkMode.LITE,
        )
        val plan = PartitionOrderPlanner.compose(input)
        // 相交：boot 只有一条候选 entry，producer 输入是 clean
        assertEquals(PartitionAction.WRITE_CANDIDATE, plan.partition("boot").action)
        assertEquals(ResidueMode.NONE, plan.partition("boot").residue)
        assertEquals(listOf("boot"), plan.writeOrder)
        assertEquals(2, plan.entries.size)
    }

    @Test
    fun knownFamilyWithoutCleanIntersectingImageIsBlocked() {
        val input = Fixtures.planInput(
            pre = pre(),
            clean = emptyMap(),
            patched = setOf("init_boot"),
            family = Family.KSU_UPSTREAM,
            target = WorkMode.LITE,
        )
        val failure = assertThrows(MigrationFailure::class.java) { PartitionOrderPlanner.compose(input) }
        assertEquals(MigrationCodes.CLEAN_IMAGE_UNAVAILABLE, failure.code)
    }

    @Test
    fun unknownFamilyWithoutCleanImageLeavesResidualInsteadOfBlocking() {
        val input = Fixtures.planInput(
            pre = pre(),
            patched = setOf("init_boot"),
            family = Family.UNKNOWN,
            target = WorkMode.LITE,
        )
        val plan = PartitionOrderPlanner.compose(input)
        assertTrue(plan.residual)
        assertEquals(PartitionAction.LEAVE_UNCHANGED, plan.partition("init_boot").action)
        assertEquals(ResidueMode.UNKNOWN_MANAGER, plan.partition("init_boot").residue)
        assertEquals(listOf("boot"), plan.writeOrder)
    }

    @Test
    fun missingPreImageIsRejected() {
        val input = Fixtures.planInput(
            pre = mapOf("boot" to Fixtures.image("pre-boot", bootSize, "pre-boot")),
            patched = setOf("init_boot"),
            family = Family.KSU_UPSTREAM,
            target = WorkMode.LITE,
            clean = mapOf("init_boot" to Fixtures.image("clean-init", initBootSize, "clean-init")),
        )
        val failure = assertThrows(MigrationFailure::class.java) { PartitionOrderPlanner.compose(input) }
        assertEquals(MigrationCodes.PRE_IMAGE_MISSING, failure.code)
    }

    @Test
    fun writeBeforeRestoreIsRejectedAsOrderViolation() {
        val candidate = PlannedPartition(
            name = "boot",
            action = PartitionAction.WRITE_CANDIDATE,
            pre = Fixtures.image("pre-boot", bootSize, "pre-boot"),
            final = Fixtures.image("candidate-boot", bootSize, "candidate-boot", ImageSource.CANDIDATE),
            producerInput = null,
            residue = ResidueMode.NONE,
        )
        val restore = PlannedPartition(
            name = "init_boot",
            action = PartitionAction.RESTORE_EXTERNAL,
            pre = Fixtures.image("pre-init", initBootSize, "pre-init"),
            final = Fixtures.image("clean-init", initBootSize, "clean-init"),
            producerInput = null,
            residue = ResidueMode.NONE,
        )
        val failure = assertThrows(MigrationFailure::class.java) {
            PartitionOrderPlanner.validateOrder(listOf(candidate, restore), listOf("boot", "init_boot"))
        }
        assertEquals(MigrationCodes.ORDER_VIOLATION, failure.code)
    }

    @Test
    fun duplicateOrderEntryIsRejected() {
        val restore = PlannedPartition(
            name = "init_boot",
            action = PartitionAction.RESTORE_EXTERNAL,
            pre = Fixtures.image("pre-init", initBootSize, "pre-init"),
            final = Fixtures.image("clean-init", initBootSize, "clean-init"),
            producerInput = null,
            residue = ResidueMode.NONE,
        )
        val failure = assertThrows(MigrationFailure::class.java) {
            PartitionOrderPlanner.validateOrder(listOf(restore), listOf("init_boot", "init_boot"))
        }
        assertEquals(MigrationCodes.ORDER_VIOLATION, failure.code)
    }

    @Test
    fun twoCandidatesAreRejected() {
        val first = PlannedPartition(
            name = "boot",
            action = PartitionAction.WRITE_CANDIDATE,
            pre = Fixtures.image("pre-boot", bootSize, "pre-boot"),
            final = null,
            producerInput = null,
            residue = ResidueMode.NONE,
        )
        val second = first.copy(name = "init_boot", pre = Fixtures.image("pre-init", initBootSize, "pre-init"))
        val failure = assertThrows(MigrationFailure::class.java) {
            PartitionOrderPlanner.validateOrder(listOf(first, second), listOf("boot", "init_boot"))
        }
        assertEquals(MigrationCodes.PLAN_INVALID, failure.code)
    }

    @Test
    fun candidateLengthMustEqualPartitionSize() {
        val short = File(tmp.newFolder(), "candidate.img").apply {
            writeBytes(ByteArray(4096))
        }
        val failure = assertThrows(MigrationFailure::class.java) {
            CandidateImages.validate(short, 8192)
        }
        assertEquals(MigrationCodes.CANDIDATE_LENGTH_INVALID, failure.code)
    }

    @Test
    fun verifyFinalReportsMismatchedPartitions() {
        val input = Fixtures.planInput(
            pre = pre(),
            clean = mapOf("init_boot" to Fixtures.image("clean-init", initBootSize, "clean-init")),
            patched = setOf("init_boot"),
            family = Family.KSU_UPSTREAM,
            target = WorkMode.LITE,
            candidates = mapOf("boot" to Fixtures.image("candidate-boot", bootSize, "candidate-boot", ImageSource.CANDIDATE)),
        )
        val plan = PartitionOrderPlanner.compose(input)
        val mismatched = PartitionOrderPlanner.verifyFinal(plan) { name ->
            when (name) {
                "boot" -> Fixtures.sha("candidate-boot")
                else -> Fixtures.sha("wrong")
            }
        }
        assertEquals(listOf("init_boot"), mismatched)
    }

    private fun labelOf(sha: String): String = LABELS.entries.firstOrNull { it.value == sha }?.key ?: sha

    private companion object {
        val LABELS: Map<String, String> = listOf(
            "pre-boot", "pre-init", "clean-init", "clean-boot", "candidate-boot",
        ).associateWith { Fixtures.sha(it) }
    }
}
