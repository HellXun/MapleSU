package dev.maplesu.manager.installer

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.service.ClientPartition
import dev.maplesu.manager.workmode.service.ClientResult
import dev.maplesu.manager.workmode.service.ClientStatus
import dev.maplesu.manager.workmode.service.ModeSwitchClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * P0-1 第 4 项：`InstallCommitCoordinator` 的机器证明（主机 JVM，无 Android/root/真机）。
 *
 * 本类钉住的四件安全不变量：
 * 1. **不谎报成功**：只有 `commit` + `REBOOT_REQUIRED` + 回读一致才返回 `AwaitingReboot`；
 * 2. **写前有 PRE、写后有回读**：顺序固定为 `begin → snapshot → submit → commit`，
 *    且写盘之前一定已经 snapshot（PRE 由后端 `snapshot` 落）；
 * 3. **源载体恢复或配置搬移失败 → 写之前显式失败**（零分区写）；
 * 4. **失败逆序回滚**：先回滚分区（客户端内部/显式 rollback），再回滚配置。
 */
class InstallCommitCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val size = 4096L
    private val preSha = "1".repeat(64)
    private val candidateSha = "a".repeat(64)
    private val baselineId = "bl-a-deadbeef-001122334455"

    // ------------------------------------------------------------------ fixtures

    private fun candidate(
        partition: String = "init_boot",
        declaredSize: Long = size,
    ): InstallCandidateImage {
        val image = File(tmp.root, "candidate-${System.nanoTime()}.img")
        image.writeBytes(ByteArray(size.toInt()) { 7 })
        return InstallCandidateImage(image, candidateSha, declaredSize, partition)
    }

    private fun statusOf(
        state: String,
        observed: String = candidateSha,
        switchId: String = SWITCH_ID,
    ): ClientStatus = ClientStatus(
        switchId = switchId,
        state = state,
        stage = "S4",
        step = "4g",
        fromMode = "LITE",
        toMode = "KSU",
        error = "",
        errorDetail = "",
        planSha256 = "p".repeat(64),
        rebootRequired = state == "REBOOT_REQUIRED",
        bootObserved = false,
        canRollback = state in setOf("COMMIT", "REBOOT_REQUIRED", "RECOVERY_REQUIRED"),
        canResumeReboot = state == "REBOOT_REQUIRED",
        verifyFailures = 0,
        partitions = listOf(
            ClientPartition("boot", "RESTORE_BASELINE", size, preSha, candidateSha, "", "RESTORED"),
            ClientPartition("init_boot", "WRITE_CANDIDATE", size, preSha, candidateSha,
                observed, "WRITTEN"),
        ),
    )

    private class FakeClient(private val log: MutableList<String>) : ModeSwitchClient {
        val calls = mutableListOf<String>()
        var preflightResult = ClientResult(true)
        var beginResult = ClientResult(true)
        var snapshotResult = ClientResult(true)
        var submitResult = ClientResult(true)
        var commitResult: ClientResult = ClientResult(true, status = null)
        var rollbackResult = ClientResult(true)
        var abortResult = ClientResult(true)
        var currentStatus: ClientStatus? = null
        var markRebootCalls = 0
        val beginFields = mutableListOf<Map<String, String>>()

        private fun note(call: String) {
            calls += call
            log += call
        }

        override suspend fun preflight(switchId: String, planFields: Map<String, String>): ClientResult {
            note("preflight")
            return preflightResult
        }

        override suspend fun begin(
            switchId: String,
            planFields: Map<String, String>,
            superuserJson: File,
            settingsMirrorJson: File,
        ): ClientResult {
            note("begin")
            beginFields += planFields
            return beginResult
        }

        override suspend fun snapshot(switchId: String): ClientResult {
            note("snapshot")
            return snapshotResult
        }

        override suspend fun exportImage(
            switchId: String,
            kind: String,
            partition: String,
            destination: File,
        ): Boolean {
            note("exportImage")
            return true
        }

        override suspend fun submitCandidate(
            switchId: String,
            partition: String,
            candidate: File,
            candidateSha256: String,
        ): ClientResult {
            note("submit")
            return submitResult
        }

        override suspend fun commit(switchId: String): ClientResult {
            note("commit")
            commitResult.status?.let { currentStatus = it }
            return commitResult
        }

        override suspend fun rollback(switchId: String): ClientResult {
            note("client.rollback")
            return rollbackResult
        }

        override suspend fun abort(switchId: String): ClientResult {
            note("abort")
            return abortResult
        }

        override fun status(switchId: String): ClientStatus? = currentStatus

        override suspend fun verify(switchId: String): ClientResult {
            note("verify")
            return ClientResult(true)
        }

        override suspend fun markRebootRequested(switchId: String): Boolean {
            note("markReboot")
            markRebootCalls++
            return true
        }

        override suspend fun exportLog(switchId: String, destination: File): File? {
            note("exportLog")
            return destination
        }
    }

    private class FakeMover(
        private val log: MutableList<String>,
        var outResult: InstallMoveOutcome = InstallMoveOutcome.OK,
        var inResult: InstallMoveOutcome = InstallMoveOutcome.OK,
        var materializeResult: InstallMoveOutcome = InstallMoveOutcome.OK,
        var rollbackResult: InstallMoveOutcome = InstallMoveOutcome.OK,
    ) : InstallConfigMover {
        val calls = mutableListOf<String>()

        /** S3：每次 rollback 收到的"已执行阶段"集合（用来证明只撤做过的方向）。 */
        val rollbackPhases = mutableListOf<Set<InstallMovePhase>>()

        private fun note(call: String) {
            calls += call
            log += "config.$call"
        }

        override fun moveOut(switchId: String, from: WorkMode, to: WorkMode): InstallMoveOutcome {
            note("out")
            return outResult
        }

        override fun moveIn(switchId: String, from: WorkMode, to: WorkMode): InstallMoveOutcome {
            note("in")
            return inResult
        }

        override fun materialize(switchId: String, from: WorkMode, to: WorkMode): InstallMoveOutcome {
            note("materialize")
            return materializeResult
        }

        override fun rollback(
            switchId: String,
            from: WorkMode,
            to: WorkMode,
            completed: Set<InstallMovePhase>,
        ): InstallMoveOutcome {
            rollbackPhases += completed
            note("rollback")
            return rollbackResult
        }
    }

    private fun coordinator(
        client: ModeSwitchClient,
        log: MutableList<String>,
        mover: FakeMover? = null,
        backendMovesConfig: Boolean = true,
        afterMaterialize: (WorkMode) -> InstallMoveOutcome = { InstallMoveOutcome.OK },
    ): InstallCommitCoordinator = InstallCommitCoordinator(
        client = client,
        moverProvider = { mover },
        backendMovesConfig = { backendMovesConfig },
        newSwitchId = { SWITCH_ID },
        afterMaterialize = afterMaterialize,
    )

    private fun run(
        coordinator: InstallCommitCoordinator,
        candidate: InstallCandidateImage = candidate(),
    ): InstallCommitOutcome = runBlocking {
        coordinator.commit(
            candidate = candidate,
            from = WorkMode.LITE,
            to = WorkMode.LITE,
            baselineId = baselineId,
            superuserJson = File(tmp.root, "su.json").apply { writeText("{}") },
            settingsMirrorJson = File(tmp.root, "settings.json").apply { writeText("{}") },
        )
    }

    // ------------------------------------------------------------------ 写之前失败：零分区写

    @Test fun bootUserlandIsVerifiedAfterInboundAndBeforeReboot() {
        for (backendMoves in listOf(false, true)) {
            val log = mutableListOf<String>()
            val client = FakeClient(log).apply { commitResult = ClientResult(true, status = statusOf("REBOOT_REQUIRED")) }
            val mover = FakeMover(log)
            val result = run(coordinator(client, log, mover, backendMoves, afterMaterialize = { mode ->
                assertEquals(WorkMode.LITE, mode)
                log += "normal-userland"
                InstallMoveOutcome.OK
            }))
            assertTrue(result.toString(), result is InstallCommitOutcome.AwaitingReboot)
            assertTrue(log.indexOf("commit") < log.indexOf("normal-userland"))
            if (!backendMoves) assertTrue(log.indexOf("config.materialize") < log.indexOf("normal-userland"))
            assertTrue(log.indexOf("normal-userland") < log.indexOf("markReboot"))
        }
    }

    @Test fun failedBootUserlandRestoresPartitionsThenInboundData() {
        for (backendMoves in listOf(false, true)) {
            val log = mutableListOf<String>()
            val client = FakeClient(log).apply { commitResult = ClientResult(true, status = statusOf("REBOOT_REQUIRED")) }
            val mover = FakeMover(log)
            val result = run(coordinator(client, log, mover, backendMoves, afterMaterialize = {
                InstallMoveOutcome.failed("KSUD_DIGEST_MISMATCH")
            })) as InstallCommitOutcome.Rejected
            assertEquals(InstallCommitCodes.MATERIALIZE_FAILED, result.code)
            assertTrue(result.detail.contains("KSUD_DIGEST_MISMATCH"))
            assertTrue(result.rolledBack)
            assertFalse(result.requiresRecovery)
            assertEquals(0, client.markRebootCalls)
            assertTrue("client.rollback" in log)
            if (!backendMoves) assertTrue(log.indexOf("client.rollback") < log.indexOf("config.rollback"))
        }
    }

    @Test
    fun commitUsesBeginWithoutCallingCompatibilityPreflight() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            preflightResult = ClientResult(false, "SWITCH_ROOT_UNAVAILABLE", "id -u failed")
            commitResult = ClientResult(true, status = statusOf("REBOOT_REQUIRED"))
        }
        val outcome = run(coordinator(client, log))
        assertTrue(outcome is InstallCommitOutcome.AwaitingReboot)
        assertEquals(listOf("begin", "snapshot", "submit", "commit", "markReboot"), client.calls)
    }

    @Test
    fun beginFailureAbortsAndNeverSnapshots() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            beginResult = ClientResult(false, "SWITCH_BASELINE_REQUIRED", "no baseline")
        }
        val outcome = run(coordinator(client, log))
        assertEquals("SWITCH_BASELINE_REQUIRED", (outcome as InstallCommitOutcome.Rejected).code)
        assertEquals(listOf("begin", "abort"), client.calls)
        assertFalse(client.calls.contains("snapshot"))
        assertFalse(client.calls.contains("commit"))
    }

    @Test
    fun unwiredMoverAbortsAfterBeginWhenBackendDoesNotMove() {
        val log = mutableListOf<String>()
        val client = FakeClient(log)
        // begin 选择后端后才判断配置搬移归属；缺实现时清理已开始的任务，零分区写。
        val outcome = run(coordinator(client, log, mover = null, backendMovesConfig = false))
        val rejected = outcome as InstallCommitOutcome.Rejected
        assertEquals(InstallCommitCodes.MOVE_STAGE_UNWIRED, rejected.code)
        assertEquals(listOf("begin", "abort"), client.calls)
        assertTrue(rejected.rolledBack)
        assertFalse(rejected.requiresRecovery)
    }

    @Test
    fun unwiredMoverWithUnconfirmedAbortRequiresRecovery() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply { abortResult = ClientResult(false, "ABORT_FAILED") }
        val outcome = run(coordinator(client, log, mover = null, backendMovesConfig = false))
        val rejected = outcome as InstallCommitOutcome.Rejected
        assertEquals(InstallCommitCodes.MOVE_STAGE_UNWIRED, rejected.code)
        assertEquals(listOf("begin", "abort"), client.calls)
        assertFalse(rejected.rolledBack)
        assertTrue(rejected.requiresRecovery)
    }

    @Test
    fun moveOutFailureAbortsBeforeAnyPartitionWrite() {
        val log = mutableListOf<String>()
        val client = FakeClient(log)
        val mover = FakeMover(
            log,
            outResult = InstallMoveOutcome.failed("MOVE_DEST_EXISTS", "/data/adb/modules"),
        )
        val outcome = run(coordinator(client, log, mover, backendMovesConfig = false))
        val rejected = outcome as InstallCommitOutcome.Rejected
        assertEquals(InstallCommitCodes.MOVE_OUT_FAILED, rejected.code)
        assertTrue(client.calls.contains("submit"))
        assertFalse(client.calls.contains("commit"))
        assertTrue(client.calls.contains("abort"))
        // 出仓失败后必须撤掉已完成的出仓步骤（配置回滚）。
        assertEquals(listOf("out", "rollback"), mover.calls)
    }

    // ------------------------------------------------------------------ 只提交不算成功

    @Test
    fun commitFailureRollsBackConfigAndReportsRolledBack() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            commitResult = ClientResult(false, "SWITCH_COMMIT_VERIFY_FAILED", "init_boot", statusOf("ROLLED_BACK"))
        }
        val mover = FakeMover(log)
        val outcome = run(coordinator(client, log, mover, backendMovesConfig = false))
        val rejected = outcome as InstallCommitOutcome.Rejected
        assertEquals("SWITCH_COMMIT_VERIFY_FAILED", rejected.code)
        assertTrue(rejected.rolledBack)
        assertFalse(rejected.requiresRecovery)
        assertEquals(listOf("out", "rollback"), mover.calls)
        assertFalse(client.calls.contains("client.rollback"))
        assertFalse(client.calls.contains("markReboot"))
    }

    @Test
    fun commitFailureWithRecoveryRequiredIsNotRolledBack() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            commitResult = ClientResult(false, "SWITCH_ROLLBACK_FAILED", "boot",
                statusOf("RECOVERY_REQUIRED"))
        }
        val outcome = run(coordinator(client, log, backendMovesConfig = true))
        val rejected = outcome as InstallCommitOutcome.Rejected
        assertTrue(rejected.requiresRecovery)
        assertFalse(rejected.rolledBack)
    }

    @Test
    fun commitOkWithoutRebootRequiredIsNotConfirmed() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            // 后端说 commit 成功，但状态还停在 SNAPSHOT：不能当成功。
            commitResult = ClientResult(true, status = statusOf("SNAPSHOT"))
        }
        val outcome = run(coordinator(client, log))
        val rejected = outcome as InstallCommitOutcome.Rejected
        assertEquals(InstallCommitCodes.COMMIT_NOT_CONFIRMED, rejected.code)
        assertTrue(client.calls.contains("client.rollback"))
        assertFalse(client.calls.contains("markReboot"))
    }

    @Test
    fun readbackMismatchIsRejected() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            commitResult = ClientResult(true, status = statusOf("REBOOT_REQUIRED", observed = "b".repeat(64)))
        }
        val outcome = run(coordinator(client, log))
        val rejected = outcome as InstallCommitOutcome.Rejected
        assertEquals(InstallCommitCodes.COMMIT_NOT_CONFIRMED, rejected.code)
        assertTrue(rejected.detail.contains("readback"))
        assertTrue(client.calls.contains("client.rollback"))
    }

    @Test
    fun noStatusAfterCommitIsNotConfirmed() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            commitResult = ClientResult(true, status = null)
            currentStatus = null
        }
        val outcome = run(coordinator(client, log))
        assertEquals(
            InstallCommitCodes.COMMIT_NOT_CONFIRMED,
            (outcome as InstallCommitOutcome.Rejected).code,
        )
    }

    // ------------------------------------------------------------------ 顺序与成功

    @Test
    fun successRunsFixedOrderAndOnlyAfterCommitReadback() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            commitResult = ClientResult(true, status = statusOf("REBOOT_REQUIRED"))
        }
        val mover = FakeMover(log)
        val outcome = run(coordinator(client, log, mover, backendMovesConfig = false))
        assertTrue("expected AwaitingReboot, got $outcome", outcome is InstallCommitOutcome.AwaitingReboot)
        assertEquals(SWITCH_ID, (outcome as InstallCommitOutcome.AwaitingReboot).switchId)
        assertEquals(
            listOf("begin", "snapshot", "submit", "commit", "markReboot"),
            client.calls,
        )
        // 候选先接收，4b 在唯一写分区的 commit 之前；4e/4f 在 commit 之后。
        val outIndex = log.indexOf("config.out")
        val submitIndex = log.indexOf("submit")
        val commitIndex = log.indexOf("commit")
        val inIndex = log.indexOf("config.in")
        val materializeIndex = log.indexOf("config.materialize")
        assertTrue("submit must precede 4b", submitIndex in 0 until outIndex)
        assertTrue("4b must precede commit", outIndex < commitIndex)
        assertTrue("4e must follow commit", inIndex > commitIndex)
        assertTrue("4f must follow 4e", materializeIndex > inIndex)
        assertEquals(1, client.markRebootCalls)
    }

    @Test
    fun backendThatMovesConfigItselfNeverInvokesMover() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            commitResult = ClientResult(true, status = statusOf("REBOOT_REQUIRED"))
        }
        val mover = FakeMover(log).apply {
            // 如果被调用就会失败 ⇒ 用来证明"服务端 AIDL 后端不该重复搬"。
            outResult = InstallMoveOutcome.failed("MOVE_DEST_EXISTS", "")
        }
        val outcome = run(coordinator(client, log, mover, backendMovesConfig = true))
        assertTrue(outcome is InstallCommitOutcome.AwaitingReboot)
        assertTrue(mover.calls.isEmpty())
        assertEquals(
            listOf("begin", "snapshot", "submit", "commit", "markReboot"),
            client.calls,
        )
    }

    @Test
    fun moveInFailureRollsBackPartitionsBeforeConfig() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            commitResult = ClientResult(true, status = statusOf("REBOOT_REQUIRED"))
        }
        val mover = FakeMover(log).apply {
            inResult = InstallMoveOutcome.failed(InstallCommitCodes.MOVE_IN_FAILED, "DEST_EXISTS")
        }
        val outcome = run(coordinator(client, log, mover, backendMovesConfig = false))
        val rejected = outcome as InstallCommitOutcome.Rejected
        assertEquals(InstallCommitCodes.MOVE_IN_FAILED, rejected.code)
        val partitionRollback = log.indexOf("client.rollback")
        val configRollback = log.indexOf("config.rollback")
        assertTrue("分区回滚必须存在", partitionRollback >= 0)
        assertTrue("先回滚分区、再回滚配置", partitionRollback < configRollback)
        assertFalse(client.calls.contains("markReboot"))
        // 分区回滚成功 + 配置回滚成功 ⇒ 已回滚，不需要人工恢复。
        assertTrue(rejected.rolledBack)
        assertFalse(rejected.requiresRecovery)
    }

    @Test
    fun materializeFailureRollsBackEverything() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            commitResult = ClientResult(true, status = statusOf("REBOOT_REQUIRED"))
        }
        val mover = FakeMover(log).apply {
            materializeResult = InstallMoveOutcome.failed(InstallCommitCodes.MATERIALIZE_FAILED, "store-remnant")
        }
        val outcome = run(coordinator(client, log, mover, backendMovesConfig = false))
        assertEquals(
            InstallCommitCodes.MATERIALIZE_FAILED,
            (outcome as InstallCommitOutcome.Rejected).code,
        )
        assertTrue(log.indexOf("client.rollback") < log.indexOf("config.rollback"))
        assertFalse(client.calls.contains("markReboot"))
    }

    // ------------------------------------------------------------------ 输入护栏

    @Test
    fun unsupportedPartitionIsRejectedBeforeAnyClientCall() {
        val log = mutableListOf<String>()
        val client = FakeClient(log)
        val outcome = run(coordinator(client, log), candidate(partition = "vendor_boot"))
        assertEquals(
            InstallCommitCodes.PARTITION_INVALID,
            (outcome as InstallCommitOutcome.Rejected).code,
        )
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun ksuBootPartitionIsAllowedAndForwardedToPlan() {
        // Normal 安装允许显式选择 boot（与 KernelSU --partition 一致），plan 必须收到同一个分区。
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            commitResult = ClientResult(true, status = statusOf("REBOOT_REQUIRED"))
        }
        val outcome = run(coordinator(client, log), candidate(partition = "boot"))
        assertTrue("KSU boot 不应在输入护栏被拒：$outcome", outcome !is InstallCommitOutcome.Rejected)
        assertEquals("boot", client.beginFields.single()["targetPartition"])
    }

    @Test
    fun resizedCandidateIsRejectedBeforeAnyClientCall() {
        val log = mutableListOf<String>()
        val client = FakeClient(log)
        val outcome = run(coordinator(client, log), candidate(declaredSize = size + 1))
        assertEquals(
            InstallCommitCodes.CANDIDATE_CHANGED,
            (outcome as InstallCommitOutcome.Rejected).code,
        )
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun nonCanonicalSwitchIdIsRejected() {
        val log = mutableListOf<String>()
        val client = FakeClient(log)
        val coordinator = InstallCommitCoordinator(
            client = client,
            newSwitchId = { "switch-uuid-1" },
        )
        val outcome = run(coordinator)
        assertEquals(
            InstallCommitCodes.SWITCH_ID_INVALID,
            (outcome as InstallCommitOutcome.Rejected).code,
        )
        assertTrue(client.calls.isEmpty())
    }

    // ------------------------------------------------------------------ S1：回滚结果必须参与判定

    @Test
    fun rollbackFailureIsNeverReportedAsRolledBack() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            // 状态不可信（commit 说成功，但状态还停在 SNAPSHOT）⇒ 走显式回滚分支。
            commitResult = ClientResult(true, status = statusOf("SNAPSHOT"))
            // 分区回滚**失败**：root 通道抖动/序列被中断。
            rollbackResult = ClientResult(false, "SWITCH_ROOT_UNAVAILABLE", "ultra root channel lost")
        }
        // mover = null（后端自己在 commit 内搬配置）⇒ 原实现的 rolledBack 恒为 true。
        val outcome = run(coordinator(client, log, mover = null, backendMovesConfig = true))
        val rejected = outcome as InstallCommitOutcome.Rejected
        assertEquals(InstallCommitCodes.PARTITION_ROLLBACK_FAILED, rejected.code)
        assertFalse("分区没回滚就绝不能说已还原: $rejected", rejected.rolledBack)
        assertTrue("回滚失败必须要求人工恢复: $rejected", rejected.requiresRecovery)
        assertTrue(client.calls.contains("client.rollback"))
    }

    @Test
    fun configRollbackFailureAlsoRequiresRecovery() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            commitResult = ClientResult(true, status = statusOf("SNAPSHOT"))
        }
        val mover = FakeMover(log).apply {
            rollbackResult = InstallMoveOutcome.failed("MOVE_RENAME_FAILED", "/data/adb/modules")
        }
        val outcome = run(coordinator(client, log, mover, backendMovesConfig = false))
        val rejected = outcome as InstallCommitOutcome.Rejected
        assertEquals(InstallCommitCodes.MOVE_ROLLBACK_FAILED, rejected.code)
        assertFalse(rejected.rolledBack)
        assertTrue(rejected.requiresRecovery)
    }

    @Test
    fun rollbackConfirmedByStateOnlyIsAcceptedAsRestored() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            commitResult = ClientResult(true, status = statusOf("SNAPSHOT"))
            // 后端没给 ok，但明确报 ROLLED_BACK ⇒ 这是"已回滚"的合法证据。
            rollbackResult = ClientResult(false, "", status = statusOf("ROLLED_BACK"))
        }
        val outcome = run(coordinator(client, log, mover = null, backendMovesConfig = true))
        val rejected = outcome as InstallCommitOutcome.Rejected
        assertEquals(InstallCommitCodes.COMMIT_NOT_CONFIRMED, rejected.code)
        assertTrue(rejected.rolledBack)
        assertFalse(rejected.requiresRecovery)
    }

    // ------------------------------------------------------------------ S3：只撤执行过的阶段

    @Test
    fun moveOutFailureOnlyRollsBackTheExecutedOutPhase() {
        val log = mutableListOf<String>()
        val client = FakeClient(log)
        val mover = FakeMover(
            log,
            outResult = InstallMoveOutcome.failed("MOVE_DEST_EXISTS", "/data/adb/modules"),
        )
        val outcome = run(coordinator(client, log, mover, backendMovesConfig = false))
        assertEquals(
            InstallCommitCodes.MOVE_OUT_FAILED,
            (outcome as InstallCommitOutcome.Rejected).code,
        )
        // 4b 失败 ⇒ 只允许撤 outbound（store→live）；绝不反向搬 inbound（会把 live 搬进目标槽）。
        assertEquals(listOf(setOf(InstallMovePhase.OUT)), mover.rollbackPhases)
    }

    @Test
    fun moveInFailureRollsBackBothExecutedPhases() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            commitResult = ClientResult(true, status = statusOf("REBOOT_REQUIRED"))
        }
        val mover = FakeMover(log).apply {
            inResult = InstallMoveOutcome.failed(InstallCommitCodes.MOVE_IN_FAILED, "DEST_EXISTS")
        }
        val outcome = run(coordinator(client, log, mover, backendMovesConfig = false))
        assertEquals(
            InstallCommitCodes.MOVE_IN_FAILED,
            (outcome as InstallCommitOutcome.Rejected).code,
        )
        assertEquals(
            listOf(setOf(InstallMovePhase.OUT, InstallMovePhase.IN)),
            mover.rollbackPhases,
        )
    }

    // ------------------------------------------------------------------ M1：能力求值 fail-closed

    @Test
    fun capabilityUnknownAbortsAfterBegin() {
        val log = mutableListOf<String>()
        val client = FakeClient(log)
        val coordinator = InstallCommitCoordinator(
            client = client,
            newSwitchId = { SWITCH_ID },
            backendMovesConfig = { throw IllegalStateException("capability probe blew up") },
        )
        val outcome = run(coordinator)
        val rejected = outcome as InstallCommitOutcome.Rejected
        assertEquals(InstallCommitCodes.MOVE_STAGE_UNWIRED, rejected.code)
        assertTrue(rejected.detail.contains("cannot determine"))
        assertEquals(listOf("begin", "abort"), client.calls)
        assertTrue(rejected.rolledBack)
        assertFalse(rejected.requiresRecovery)
    }

    @Test
    fun capabilityUnknownWithUnconfirmedAbortRequiresRecovery() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            abortResult = ClientResult(false, InstallCommitCodes.ROOT_OPERATION_UNCERTAIN)
        }
        val coordinator = InstallCommitCoordinator(
            client = client,
            newSwitchId = { SWITCH_ID },
            backendMovesConfig = { throw IllegalStateException("capability probe blew up") },
        )
        val rejected = run(coordinator) as InstallCommitOutcome.Rejected
        assertEquals(InstallCommitCodes.MOVE_STAGE_UNWIRED, rejected.code)
        assertEquals(listOf("begin", "abort"), client.calls)
        assertFalse(rejected.rolledBack)
        assertTrue(rejected.requiresRecovery)
    }

    @Test
    fun capabilityAndMoverAreEvaluatedOnlyAfterBeginSelectsTheBackend() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            commitResult = ClientResult(true, status = statusOf("REBOOT_REQUIRED"))
        }
        val mover = FakeMover(log)
        val coordinator = InstallCommitCoordinator(
            client = client,
            newSwitchId = { SWITCH_ID },
            backendMovesConfig = { log += "capability"; false },
            moverProvider = { log += "mover"; mover },
        )
        assertTrue(run(coordinator) is InstallCommitOutcome.AwaitingReboot)
        assertEquals(listOf("begin", "capability", "mover", "snapshot"), log.take(4))
    }

    @Test
    fun capabilityFlipBeforeCommitFailsWithoutPartitionWrite() {
        val log = mutableListOf<String>()
        val client = FakeClient(log)
        val mover = FakeMover(log)
        var evaluation = 0
        val coordinator = InstallCommitCoordinator(
            client = client,
            moverProvider = { mover },
            newSwitchId = { SWITCH_ID },
            // 第 1 次（begin 之后）：后端会自己搬 ⇒ mover=null；第 2 次（commit 前）：翻成不会搬。
            backendMovesConfig = { evaluation++; evaluation == 1 },
        )
        val outcome = run(coordinator)
        val rejected = outcome as InstallCommitOutcome.Rejected
        assertEquals(InstallCommitCodes.MOVE_STAGE_UNWIRED, rejected.code)
        assertFalse("翻转后绝不许写分区", client.calls.contains("commit"))
        // 分区压根没写过 ⇒ 已回滚（不需要人工恢复）。
        assertTrue(rejected.rolledBack)
        assertFalse(rejected.requiresRecovery)
    }

    @Test
    fun capabilityFlipWithUnconfirmedAbortDoesNotReportCleanRollback() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply { abortResult = ClientResult(false, "ABORT_FAILED") }
        val mover = FakeMover(log)
        var evaluation = 0
        val coordinator = InstallCommitCoordinator(
            client = client,
            moverProvider = { mover },
            newSwitchId = { SWITCH_ID },
            backendMovesConfig = { evaluation++; evaluation != 1 },
        )
        val rejected = run(coordinator) as InstallCommitOutcome.Rejected
        assertEquals(InstallCommitCodes.MOVE_STAGE_UNWIRED, rejected.code)
        assertEquals(listOf("out", "rollback"), mover.calls)
        assertTrue(client.calls.contains("abort"))
        assertFalse(client.calls.contains("commit"))
        assertFalse(rejected.rolledBack)
        assertTrue(rejected.requiresRecovery)
    }

    // ------------------------------------------------------------------ M2 / M3

    @Test
    fun stateOnlyConfirmationIsMarkedInTheOutcome() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            // SERVICE 通道：不报写后回读摘要，只有 state。
            commitResult = ClientResult(true, status = statusOf("REBOOT_REQUIRED", observed = ""))
        }
        val outcome = run(coordinator(client, log)) as InstallCommitOutcome.AwaitingReboot
        assertEquals(InstallConfirmationEvidence.STATE_ONLY, outcome.evidence)
    }

    @Test
    fun readbackConfirmationIsMarkedAsStateAndReadback() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            commitResult = ClientResult(true, status = statusOf("REBOOT_REQUIRED"))
        }
        val outcome = run(coordinator(client, log)) as InstallCommitOutcome.AwaitingReboot
        assertEquals(InstallConfirmationEvidence.STATE_AND_READBACK, outcome.evidence)
    }

    @Test
    fun emptyBaselineIdUsesBackendCarrierSnapshotsAndConfirmsTheCommit() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            commitResult = ClientResult(true, status = statusOf("REBOOT_REQUIRED"))
        }
        val outcome = runBlocking {
            coordinator(client, log).commit(
                candidate = candidate(),
                from = WorkMode.LITE,
                to = WorkMode.LITE,
                baselineId = "",
                superuserJson = File(tmp.root, "su.json").apply { writeText("{}") },
                settingsMirrorJson = File(tmp.root, "settings.json").apply { writeText("{}") },
            )
        }
        assertTrue("unexpected: $outcome", outcome is InstallCommitOutcome.AwaitingReboot)
        assertEquals("", client.beginFields.single()["baselineId"])
        assertEquals(listOf("begin", "snapshot", "submit", "commit", "markReboot"), client.calls)
    }

    @Test
    fun sourceRecoveryFailureWithEmptyIdRetainsTheBackendCauseAndDoesNotCommit() = runBlocking {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            snapshotResult = ClientResult(false, "SOURCE_RECOVERY_UNAVAILABLE", "ultra:boot")
        }
        val result = coordinator(client, log).commit(candidate(), WorkMode.LITE, WorkMode.LITE, "",
            File(tmp.root, "su.json"), File(tmp.root, "settings.json")) as InstallCommitOutcome.Rejected
        assertEquals("SOURCE_RECOVERY_UNAVAILABLE", result.code)
        assertEquals(listOf("begin", "snapshot", "abort"), client.calls)
    }

    @Test fun rejectedSubmitDoesNotTouchOrRollbackSourceData() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply { submitResult = ClientResult(false, "SUBMIT_FAILED") }
        val mover = FakeMover(log)
        val result = run(coordinator(client, log, mover, backendMovesConfig = false)) as InstallCommitOutcome.Rejected
        assertEquals("SUBMIT_FAILED", result.code)
        assertTrue(mover.calls.isEmpty())
        assertEquals(listOf("begin", "snapshot", "submit", "abort"), client.calls)
    }

    @Test fun uncertainTeardownPreservesItsCodeAndDoesNotAbortOrRecover() {
        val log = mutableListOf<String>()
        val client = FakeClient(log)
        val mover = FakeMover(log, outResult = InstallMoveOutcome.failed(InstallCommitCodes.ROOT_OPERATION_UNCERTAIN))
        val result = run(coordinator(client, log, mover, backendMovesConfig = false)) as InstallCommitOutcome.Rejected
        assertEquals(InstallCommitCodes.ROOT_OPERATION_UNCERTAIN, result.code)
        assertTrue(result.requiresRecovery)
        assertFalse(result.rolledBack)
        assertEquals(listOf("out"), mover.calls)
        assertFalse(client.calls.contains("abort"))
        assertFalse(client.calls.contains("client.rollback"))
        assertFalse(client.calls.contains("commit"))
    }

    @Test fun uncertainSubmitDoesNotAbortOrStartTeardown() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply { submitResult = ClientResult(false, InstallCommitCodes.ROOT_OPERATION_UNCERTAIN) }
        val mover = FakeMover(log)
        val result = run(coordinator(client, log, mover, backendMovesConfig = false)) as InstallCommitOutcome.Rejected
        assertEquals(InstallCommitCodes.ROOT_OPERATION_UNCERTAIN, result.code)
        assertTrue(result.requiresRecovery)
        assertTrue(mover.calls.isEmpty())
        assertFalse(client.calls.contains("abort"))
    }

    @Test fun uncertainPartitionCommitDoesNotRunAnotherMutation() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply { commitResult = ClientResult(false, InstallCommitCodes.ROOT_OPERATION_UNCERTAIN) }
        val mover = FakeMover(log)
        val result = run(coordinator(client, log, mover, backendMovesConfig = false)) as InstallCommitOutcome.Rejected
        assertEquals(InstallCommitCodes.ROOT_OPERATION_UNCERTAIN, result.code)
        assertTrue(result.requiresRecovery)
        assertEquals(listOf("out"), mover.calls)
        assertFalse(client.calls.contains("abort"))
        assertFalse(client.calls.contains("client.rollback"))
    }

    @Test fun uncertainInboundDoesNotRacePartitionRollback() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply { commitResult = ClientResult(true, status = statusOf("REBOOT_REQUIRED")) }
        val mover = FakeMover(log, inResult = InstallMoveOutcome.failed(InstallCommitCodes.ROOT_OPERATION_UNCERTAIN))
        val result = run(coordinator(client, log, mover, backendMovesConfig = false)) as InstallCommitOutcome.Rejected
        assertEquals(InstallCommitCodes.ROOT_OPERATION_UNCERTAIN, result.code)
        assertTrue(result.requiresRecovery)
        assertEquals(listOf("out", "in"), mover.calls)
        assertFalse(client.calls.contains("client.rollback"))
    }

    @Test fun failedCommitWithUnrestoredPartitionsKeepsSourceDataFrozen() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            commitResult = ClientResult(false, "WRITE_FAILED", status = statusOf("COMMIT"))
            rollbackResult = ClientResult(false, "ROLLBACK_FAILED", status = statusOf("RECOVERY_REQUIRED"))
        }
        val mover = FakeMover(log)
        val result = run(coordinator(client, log, mover, backendMovesConfig = false)) as InstallCommitOutcome.Rejected
        assertEquals(InstallCommitCodes.PARTITION_ROLLBACK_FAILED, result.code)
        assertTrue(result.requiresRecovery)
        assertEquals(listOf("out"), mover.calls)
        assertTrue(client.calls.contains("client.rollback"))
    }

    @Test fun unconfirmedWriteWithFailedPartitionRollbackKeepsSourceDataFrozen() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            commitResult = ClientResult(true, status = statusOf("SNAPSHOT"))
            rollbackResult = ClientResult(false, "ROLLBACK_FAILED")
        }
        val mover = FakeMover(log)
        val result = run(coordinator(client, log, mover, backendMovesConfig = false)) as InstallCommitOutcome.Rejected
        assertEquals(InstallCommitCodes.PARTITION_ROLLBACK_FAILED, result.code)
        assertEquals(listOf("out"), mover.calls)
        assertTrue(result.requiresRecovery)
    }

    @Test fun failedInboundWithFailedPartitionRollbackKeepsBothDataStagesFrozen() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            commitResult = ClientResult(true, status = statusOf("REBOOT_REQUIRED"))
            rollbackResult = ClientResult(false, "ROLLBACK_FAILED")
        }
        val mover = FakeMover(log, inResult = InstallMoveOutcome.failed("IN_FAILED"))
        val result = run(coordinator(client, log, mover, backendMovesConfig = false)) as InstallCommitOutcome.Rejected
        assertEquals(InstallCommitCodes.PARTITION_ROLLBACK_FAILED, result.code)
        assertEquals(listOf("out", "in"), mover.calls)
        assertTrue(result.requiresRecovery)
    }

    @Test fun failedMaterializationWithFailedPartitionRollbackKeepsBothDataStagesFrozen() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            commitResult = ClientResult(true, status = statusOf("REBOOT_REQUIRED"))
            rollbackResult = ClientResult(false, "ROLLBACK_FAILED")
        }
        val mover = FakeMover(log, materializeResult = InstallMoveOutcome.failed("MATERIALIZE_FAILED"))
        val result = run(coordinator(client, log, mover, backendMovesConfig = false)) as InstallCommitOutcome.Rejected
        assertEquals(InstallCommitCodes.PARTITION_ROLLBACK_FAILED, result.code)
        assertEquals(listOf("out", "in", "materialize"), mover.calls)
        assertTrue(result.requiresRecovery)
    }

    @Test fun recoveryRequiredCommitDoesNotStartConfigurationRollback() {
        val log = mutableListOf<String>()
        val client = FakeClient(log).apply {
            commitResult = ClientResult(false, "WRITE_FAILED", status = statusOf("RECOVERY_REQUIRED"))
        }
        val mover = FakeMover(log)
        val result = run(coordinator(client, log, mover, backendMovesConfig = false)) as InstallCommitOutcome.Rejected
        assertEquals(InstallCommitCodes.PARTITION_ROLLBACK_FAILED, result.code)
        assertTrue(result.requiresRecovery)
        assertEquals(listOf("out"), mover.calls)
        assertFalse(client.calls.contains("client.rollback"))
    }

    private companion object {
        const val SWITCH_ID = "0f8fad5b-d9cb-469f-a165-70867728950e"
    }
}
