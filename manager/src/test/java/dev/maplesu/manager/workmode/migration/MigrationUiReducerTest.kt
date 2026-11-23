package dev.maplesu.manager.workmode.migration

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 迁移 UI 状态机表驱动测试（05 §6；≥12 用例）。 */
class MigrationUiReducerTest {

    private fun info(
        family: Family = Family.KSU_UPSTREAM,
        patched: Set<String> = setOf("init_boot"),
        clean: Set<String> = setOf("init_boot"),
        backups: Map<String, StockBackupRef> = mapOf(
            "init_boot" to StockBackupRef("init_boot", "ab".repeat(20), "/data/adb/ksu/ksu_backup_" + "ab".repeat(20), 100, "a".repeat(64), BackupState.USABLE),
        ),
        managers: List<String> = listOf("me.weishu.kernelsu"),
        bestEffort: Boolean = !family.isKnown,
        supported: Boolean = family.isKnown,
        version: String? = "KernelSU 0.9.5 (uapi: 6)",
        uapi: Int? = 6,
    ) = ExternalRootInfo(
        family = family,
        version = version,
        uapi = uapi,
        managerPackages = managers,
        patchedPartitions = patched,
        stockBackups = backups,
        restoreStrategy = RestoreStrategy.KSU_STOCK_COPY,
        supported = supported,
        bestEffort = bestEffort,
        cleanImagePartitions = clean,
    )

    private fun eligibility(
        enabled: Boolean = true,
        recommended: WorkMode? = WorkMode.LITE,
        targetAllowed: Map<WorkMode, Boolean> = mapOf(WorkMode.LITE to true, WorkMode.LITE to true),
        blockers: List<Blocker> = emptyList(),
    ) = MigrationEligibility(
        enabled = enabled,
        blockers = blockers,
        recommendedTarget = recommended,
        recommendedReason = "r",
        space = SpaceEstimate(0, 0, 0, 0, adequate = true),
        targetAllowed = targetAllowed,
    )

    private fun detected(
        info: ExternalRootInfo = info(),
        eligibility: MigrationEligibility = eligibility(),
    ): MigrationUiSnapshot = MigrationUiReducer.reduce(
        MigrationUiSnapshot(),
        MigrationUiEvent.Detected(info, eligibility),
    )

    private fun moduleResult(
        moduleId: String = "m",
        decision: ModuleDecision = ModuleDecision.IMPORT_LITE,
    ) = MigrationModuleResultUi(moduleId, decision, "")

    private fun verifiedResult(
        modules: List<MigrationModuleResultUi> = listOf(moduleResult()),
        residual: Boolean = false,
    ) = MigrationResultUi(
        kind = MigrationResultKind.VERIFIED,
        modules = modules,
        auth = MigrationAuthResultUi(intents = 3, stale = 1, applied = 2, pending = 1),
        residual = residual,
        residues = if (residual) listOf("EXTERNAL_RESIDUE:/data/adb/ksu") else emptyList(),
        rebootRequired = false,
    )

    // 1) 家族 → 文案 key
    @Test
    fun familyLabelsAreTableDriven() {
        val table = listOf(
            Family.KSU_UPSTREAM to MigrationFamilyKeys.KSU,
            Family.KSU_7K to MigrationFamilyKeys.MAPLESU,
            Family.MAGISK to MigrationFamilyKeys.MAGISK,
            Family.APATCH to MigrationFamilyKeys.APATCH,
            Family.UNKNOWN to MigrationFamilyKeys.UNKNOWN,
        )
        for ((family, key) in table) {
            val state = detected(info(family = family))
            assertEquals("family=$family", key, state.detection!!.familyKey)
        }
    }

    // 2) 检测摘要：已改分区/干净镜像/备份计数/管理器计数
    @Test
    fun detectionSummaryProjection() {
        val state = detected(
            info(
                patched = setOf("init_boot", "boot"),
                clean = setOf("init_boot"),
                backups = mapOf(
                    "init_boot" to StockBackupRef("init_boot", null, "/a", 1, "b".repeat(64), BackupState.USABLE),
                    "boot" to StockBackupRef("boot", null, "/b", 1, "c".repeat(64), BackupState.MISSING),
                ),
                version = "KernelSU 1.0 (uapi: 7)",
                uapi = 7,
            ),
        )
        val detection = state.detection!!
        assertEquals(listOf("boot", "init_boot"), detection.patchedPartitions)
        assertEquals(listOf("init_boot"), detection.cleanImagePartitions)
        assertEquals(1, detection.usableStockBackups)
        assertEquals(2, detection.totalStockBackups)
        assertEquals(1, detection.managerPackageCount)
        assertTrue(detection.willUninstallManager())
        assertEquals("KernelSU 1.0 (uapi: 7)", detection.versionLabel)
        assertEquals(7, detection.uapi)
        assertEquals(listOf("boot"), detection.restorePartitionsFor(WorkMode.LITE))
    }

    // 3) 目标分区还原关系：KSU→Lite 需还原 init_boot；KSU→KSU 相交无需还原
    @Test
    fun restorePartitionsDependOnTarget() {
        val state = detected(info(patched = setOf("init_boot")))
        assertEquals(listOf("init_boot"), state.detection!!.restorePartitionsFor(WorkMode.LITE))
        assertEquals(emptyList<String>(), state.detection!!.restorePartitionsFor(WorkMode.LITE))
        assertEquals(listOf("init_boot"), MigrationUiReducer.reduce(
            state, MigrationUiEvent.TargetSelected(WorkMode.LITE),
        ).restorePartitions)
    }

    // 4) 推荐目标成为默认选中；无推荐时选第一个可用
    @Test
    fun recommendedTargetBecomesDefaultSelection() {
        assertEquals(WorkMode.LITE, detected(eligibility = eligibility(recommended = WorkMode.LITE)).selectedTarget)
        val noRecommendation = detected(
            eligibility = eligibility(recommended = null, targetAllowed = mapOf(WorkMode.LITE to true, WorkMode.LITE to false)),
        )
        assertEquals(WorkMode.LITE, noRecommendation.selectedTarget)
    }

    // 5) 未知家族：best-effort 提示 + 仍可开始（D16 不阻断）
    @Test
    fun unknownFamilyIsBestEffortButStartable() {
        val state = detected(info(family = Family.UNKNOWN, supported = false, bestEffort = true, version = null, uapi = null))
        assertTrue(state.showBestEffortNotice)
        assertTrue(state.canStart)
        assertEquals(WorkMode.LITE, state.selectedTarget)
        assertEquals(MigrationFamilyKeys.UNKNOWN, state.detection!!.familyKey)
    }

    // 6) 阻断原因 → inline key；同时禁止开始
    @Test
    fun blockersAreMappedAndDisableStart() {
        val table = listOf(
            Blocker.NoRoot to MigrationBlockerKeys.NO_ROOT,
            Blocker.NoSpace(10, 1) to MigrationBlockerKeys.NO_SPACE,
            Blocker.PendingTransaction to MigrationBlockerKeys.PENDING,
            Blocker.LayoutUnsupported to MigrationBlockerKeys.LAYOUT,
            Blocker.CandidateUnproducible("boot") to MigrationBlockerKeys.CANDIDATE,
            Blocker.CleanImageUnavailable(listOf("init_boot")) to MigrationBlockerKeys.CLEAN_IMAGE,
            Blocker.OwnManagerInstalled to MigrationBlockerKeys.OWN_MANAGER,
        )
        for ((blocker, key) in table) {
            val state = detected(eligibility = eligibility(enabled = false, blockers = listOf(blocker)))
            assertEquals("blocker=$blocker", listOf(key), state.detection!!.blockerKeys)
            assertFalse("blocker=$blocker must disable start", state.canStart)
        }
    }

    // 7) 目标选项：不可用目标带原因是禁用；选择被拒绝
    @Test
    fun disabledTargetOptionCannotBeSelected() {
        val state = detected(
            eligibility = eligibility(targetAllowed = mapOf(WorkMode.LITE to true, WorkMode.LITE to false)),
        )
        val ksu = state.targetOptions.single { it.mode == WorkMode.LITE }
        assertFalse(ksu.enabled)
        assertEquals(MigrationBlockerKeys.CLEAN_IMAGE, ksu.disabledReasonKey)
        val after = MigrationUiReducer.reduce(state, MigrationUiEvent.TargetSelected(WorkMode.LITE))
        assertEquals(WorkMode.LITE, after.selectedTarget)
    }

    // 8) 确认需要 canStart；取消回到 DETECTED
    @Test
    fun confirmRequiresStartableStateAndCanBeDismissed() {
        val ready = detected()
        val ignored = MigrationUiReducer.reduce(
            detected(eligibility = eligibility(enabled = false, blockers = listOf(Blocker.NoRoot))),
            MigrationUiEvent.ConfirmRequested,
        )
        assertEquals(MigrationStage.DETECTED, ignored.stage)
        val confirming = MigrationUiReducer.reduce(ready, MigrationUiEvent.ConfirmRequested)
        assertEquals(MigrationStage.CONFIRMING, confirming.stage)
        assertTrue(confirming.confirming)
        assertEquals(MigrationStage.DETECTED, MigrationUiReducer.reduce(confirming, MigrationUiEvent.ConfirmDismissed).stage)
    }

    // 9) 开始迁移：进入备份阶段 1/5；不可开始时忽略
    @Test
    fun startEntersBackupStage() {
        val started = MigrationUiReducer.reduce(detected(), MigrationUiEvent.StartRequested)
        assertEquals(MigrationStage.BACKING_UP, started.stage)
        assertEquals(1, started.progress.stepIndex)
        assertEquals(MigrationStageKeys.PROGRESS_STAGES.size, started.progress.stepCount)
        assertEquals(MigrationStageKeys.M1, started.progress.stepKey)
        val ignored = MigrationUiReducer.reduce(
            detected(eligibility = eligibility(enabled = false, blockers = listOf(Blocker.NoSpace(1, 0)))),
            MigrationUiEvent.StartRequested,
        )
        assertEquals(MigrationStage.DETECTED, ignored.stage)
    }

    // 10) 进度阶段表：M1..M5 → step 序号；终态后迟到进度被忽略
    @Test
    fun progressStagesAreMappedAndTerminalIsSticky() {
        val table = listOf(
            MigrationStage.BACKING_UP to 1,
            MigrationStage.RESTORING_EXTERNAL to 2,
            MigrationStage.WRITING_TARGET to 3,
            MigrationStage.DEPLOYING to 4,
            MigrationStage.PENDING_REBOOT to 5,
        )
        var state = MigrationUiReducer.reduce(detected(), MigrationUiEvent.StartRequested)
        for ((stage, step) in table) {
            state = MigrationUiReducer.reduce(state, MigrationUiEvent.Progress(stage = stage))
            assertEquals("stage=$stage", step, state.progress.stepIndex)
            assertEquals(MigrationStageKeys.of(stage), state.progress.stepKey)
            assertTrue("stage=$stage blocks back", state.stage.blocksBack)
        }
        val completed = MigrationUiReducer.reduce(
            state, MigrationUiEvent.Completed(verifiedResult()),
        )
        val late = MigrationUiReducer.reduce(completed, MigrationUiEvent.Progress(stage = MigrationStage.WRITING_TARGET))
        assertEquals(MigrationStage.COMPLETED, late.stage)
    }

    // 11) 结果分类：verified/failed/rolled_back/recovery_required
    @Test
    fun resultClassificationTable() {
        val table = listOf(
            MigrationResultKind.VERIFIED to MigrationStage.COMPLETED,
            MigrationResultKind.FAILED to MigrationStage.FAILED,
            MigrationResultKind.ROLLED_BACK to MigrationStage.ROLLED_BACK,
            MigrationResultKind.RECOVERY_REQUIRED to MigrationStage.RECOVERY_REQUIRED,
        )
        for ((kind, stage) in table) {
            val state = MigrationUiReducer.reduce(
                detected(),
                MigrationUiEvent.Completed(MigrationResultUi(kind = kind)),
            )
            assertEquals("kind=$kind", stage, state.stage)
            assertTrue(state.stage.isTerminal)
            assertEquals(stage, MigrationUiReducer.classifyResult(MigrationResultUi(kind = kind)))
        }
    }

    // 12) 模块三态逐项计数
    @Test
    fun moduleResultCountsAreDerived() {
        val result = verifiedResult(
            modules = listOf(
                moduleResult("a", ModuleDecision.IMPORT_LITE),
                moduleResult("b", ModuleDecision.RESTORE_DIRECT),
                moduleResult("c", ModuleDecision.SKIP_UNSUPPORTED),
                moduleResult("d", ModuleDecision.SKIP_FAILED),
                moduleResult("e", ModuleDecision.IMPORT_DISABLED),
            ),
        )
        assertEquals(3, result.importedModules)
        assertEquals(1, result.unsupportedModules)
        assertEquals(1, result.failedModules)
        assertEquals(listOf("c"), result.modulesForDecision(ModuleDecision.SKIP_UNSUPPORTED).map { it.moduleId })
        assertEquals("migration_module_unsupported", result.modules.single { it.moduleId == "c" }.decisionKey)
    }

    // 13) residual=true 必须产生显式警告
    @Test
    fun residualResultsAreFlagged() {
        val clean = MigrationUiReducer.reduce(detected(), MigrationUiEvent.Completed(verifiedResult(residual = false)))
        assertFalse(clean.residualWarning)
        val residual = MigrationUiReducer.reduce(detected(), MigrationUiEvent.Completed(verifiedResult(residual = true)))
        assertTrue(residual.residualWarning)
        assertEquals(1, residual.result!!.residues.size)
    }

    // 14) 失败事件：rolledBack / recoveryRequired / 普通失败
    @Test
    fun failureEventsSelectStage() {
        val plain = MigrationUiReducer.reduce(
            detected(),
            MigrationUiEvent.Failed(MigrationFailureUi("MIGRATION_FAILED", MigrationStage.WRITING_TARGET)),
        )
        assertEquals(MigrationStage.FAILED, plain.stage)
        val rolledBack = MigrationUiReducer.reduce(
            detected(),
            MigrationUiEvent.Failed(MigrationFailureUi("WRITE_FAILED", MigrationStage.WRITING_TARGET, rolledBack = true)),
        )
        assertEquals(MigrationStage.ROLLED_BACK, rolledBack.stage)
        val recovery = MigrationUiReducer.reduce(
            detected(),
            MigrationUiEvent.Failed(
                MigrationFailureUi("WRITE_ROLLBACK_FAILED", MigrationStage.WRITING_TARGET, recoveryRequired = true),
            ),
        )
        assertEquals(MigrationStage.RECOVERY_REQUIRED, recovery.stage)
    }

    // 15) 中止：写分区前回到检测态；写分区后进恢复
    @Test
    fun abortBeforeWriteReturnsToDetection() {
        var state = MigrationUiReducer.reduce(detected(), MigrationUiEvent.StartRequested)
        state = MigrationUiReducer.reduce(state, MigrationUiEvent.Aborted)
        assertEquals(MigrationStage.DETECTED, state.stage)
        assertEquals("", state.migrationId)
        assertNull(state.backup)
    }

    @Test
    fun abortAfterWriteRequiresRecovery() {
        var state = MigrationUiReducer.reduce(detected(), MigrationUiEvent.StartRequested)
        state = MigrationUiReducer.reduce(state, MigrationUiEvent.Progress(stage = MigrationStage.WRITING_TARGET))
        state = MigrationUiReducer.reduce(state, MigrationUiEvent.Aborted)
        assertEquals(MigrationStage.RECOVERY_REQUIRED, state.stage)
        assertEquals(MigrationUiReducer.MIGRATION_ABORT_AFTER_WRITE, state.failure!!.code)
        assertTrue(state.failure!!.recoveryRequired)
    }

    // 16) 无后端：诚实降级 UNAVAILABLE，不出现开始按钮
    @Test
    fun unavailableBackendDegradesHonestly() {
        val state = MigrationUiReducer.reduce(MigrationUiSnapshot(), MigrationUiEvent.DetectionUnavailable("NO_BACKEND"))
        assertEquals(MigrationStage.UNAVAILABLE, state.stage)
        assertEquals("NO_BACKEND", state.unavailableCode)
        assertFalse(state.canStart)
        assertNull(state.detection)
    }

    // 17) 备份摘要：完整/缺项/未验证
    @Test
    fun backupSummaryCompleteness() {
        val manifest = BackupManifest(
            migrationId = "3f2b0c9e-6a1d-4c47-9c2e-1b7d5a0f8e33",
            objects = listOf(
                BackupObject("a", "AUTH_ALLOWLIST", "a", "auth/a", 10, "a".repeat(64), readBack = true),
                BackupObject("b", "PARTITION_IMAGE", "b", "images/b", 20, "b".repeat(64), readBack = true),
            ),
            verified = true,
        )
        val complete = MigrationBackupSummary.of(
            manifest, BackupVerification(ok = true, checked = 2, missing = emptyList(), mismatched = emptyList()),
        )
        assertEquals(2, complete.objectCount)
        assertEquals(30L, complete.totalBytes)
        assertTrue(complete.complete)
        val missing = MigrationBackupSummary.of(
            manifest, BackupVerification(ok = false, checked = 2, missing = listOf("b"), mismatched = emptyList()),
        )
        assertFalse(missing.complete)
        assertEquals(1, missing.missing)
        val unverified = MigrationUiReducer.reduce(
            detected(),
            MigrationUiEvent.BackupCaptured(MigrationBackupSummary(objectCount = 1, verified = false)),
        )
        assertFalse(unverified.backup!!.complete)
    }

    // 18) 授权结果投影
    @Test
    fun authResultProjection() {
        val result = verifiedResult()
        assertEquals(3, result.auth.intents)
        assertEquals(1, result.auth.stale)
        assertEquals(2, result.auth.applied)
        assertEquals(1, result.auth.pending)
    }

    // 19) 结果关闭/重置回到可再次检测的状态
    @Test
    fun dismissResultReturnsToDetection() {
        val completed = MigrationUiReducer.reduce(detected(), MigrationUiEvent.Completed(verifiedResult()))
        val dismissed = MigrationUiReducer.reduce(completed, MigrationUiEvent.Dismissed)
        assertEquals(MigrationStage.DETECTED, dismissed.stage)
        assertNull(dismissed.result)
        assertEquals("", dismissed.migrationId)
        val reset = MigrationUiReducer.reduce(dismissed, MigrationUiEvent.Reset)
        assertEquals(MigrationStage.IDLE, reset.stage)
        assertNull(reset.detection)
    }

    // 20) 检测中状态与迁移 id 保留
    @Test
    fun detectStartedClearsPreviousResultAndStartedKeepsId() {
        val started = MigrationUiReducer.reduce(
            detected(), MigrationUiEvent.Started("3f2b0c9e-6a1d-4c47-9c2e-1b7d5a0f8e33"),
        )
        assertEquals("3f2b0c9e-6a1d-4c47-9c2e-1b7d5a0f8e33", started.migrationId)
        val detecting = MigrationUiReducer.reduce(
            MigrationUiReducer.reduce(started, MigrationUiEvent.Completed(verifiedResult())),
            MigrationUiEvent.DetectStarted,
        )
        assertEquals(MigrationStage.DETECTING, detecting.stage)
        assertNull(detecting.result)
        assertNull(detecting.detection)
    }
}
