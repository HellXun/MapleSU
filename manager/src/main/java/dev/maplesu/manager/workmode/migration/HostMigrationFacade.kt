package dev.maplesu.manager.workmode.migration

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.control.AtomicControlFile
import dev.maplesu.manager.workmode.detect.BootIdSource
import dev.maplesu.manager.workmode.detect.Clock
import dev.maplesu.manager.workmode.detect.ProcBootIdSource
import dev.maplesu.manager.workmode.detect.WallClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 生产迁移 facade（W20–W22 编排；05 §1.2 / §4.1 / §4.7）。
 *
 * 流程与 fail-closed 语义：
 * 1. [detect]：只读探针 → `MigrationDetector.detect/eligibility` → 创建 migrationId + journal +
 *    `migrations/<id>/detect.json`；无 root/探针不可用 → `Unavailable`（**不猜家族**）。
 * 2. [start]：先做计划性校验（阻断/目标可用），接受后异步执行：
 *    **M1 备份（读回校验）→ M2 计划（单 entry/顺序/长度）→ M4 写入 → M5 部署 → M7 待重启**。
 *    任一步失败 → 结构化 `Failed`；写入相关失败按回滚结果落 ROLLED_BACK / RECOVERY_REQUIRED。
 * 3. [observe]：M0–M9 投影；进程重启后从 journal 重建最近观察（终态可直接展示）。
 * 4. [abort]：写分区前取消并落 ROLLED_BACK；写入已开始 → 返回 false，UI 转恢复入口。
 *
 * 本类不直接写块设备：写入全部经 [MigrationWritePort]（设备侧 = 共用分区事务）。
 */
class HostMigrationFacade(
    private val root: File,
    private val probes: MigrationProbePort,
    private val backup: MigrationBackupPort,
    private val writer: MigrationWritePort = UnavailableMigrationWritePort,
    private val clock: Clock = WallClock,
    private val bootIdSource: BootIdSource = ProcBootIdSource,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : MigrationFacade {

    private val journalWriter = FileMigrationJournal(root, clock, bootIdSource)
    private val backupStore = MigrationBackup(root, clock, bootIdSource)
    private val states = ConcurrentHashMap<String, MutableStateFlow<MigrationObservation?>>()
    private val jobs = ConcurrentHashMap<String, Job>()

    @Volatile
    private var pending: PendingDetection? = null

    override fun currentMigrationId(): String = pending?.migrationId.orEmpty()

    override suspend fun detect(): MigrationDetectionResult {
        val probed = runCatching { probes.read() }.getOrElse {
            return MigrationDetectionResult.Unavailable(PROBE_FAILED)
        }
        val probe = when (probed) {
            is MigrationProbeResult.Unavailable -> return MigrationDetectionResult.Unavailable(probed.code)
            is MigrationProbeResult.Ready -> probed.probe
        }
        val inventory = (probed as? MigrationProbeResult.Ready)?.inventory ?: PackageInventory()

        if (probe.suUid != 0 || !probe.device.externalRootAvailable) {
            return MigrationDetectionResult.Unavailable(MigrationCodes.NO_ROOT)
        }
        val info = MigrationDetector.detect(probe, inventory)
        if (info.blockers.any { it is Blocker.NoRoot }) {
            return MigrationDetectionResult.Unavailable(MigrationCodes.NO_ROOT)
        }

        val target = MigrationUiReducer.recommendTarget(
            info,
            MigrationEligibility( // 只用于推荐目标；正式 eligibility 在下面重算
                enabled = true,
                blockers = emptyList(),
                recommendedTarget = WorkMode.LITE,
                recommendedReason = null,
                space = SpaceEstimate(0, 0, 0, 0, true),
                targetAllowed = WorkMode.entries.associateWith { true },
            ),
        ) ?: WorkMode.LITE
        val space = SpaceEstimator.estimate(
            partitionSizes = probe.partitions.filterValues { it.present }.mapValues { it.value.size },
            restoreSet = info.patchedPartitions,
            targetSet = target.targetPartitions,
            externalDataBytes = 0,
            apkBytes = 0,
            freeBytesOnData = probe.device.freeBytesOnData,
        )
        val eligibility = MigrationDetector.eligibility(info, probe.device, space)

        val migrationId = UUID.randomUUID().toString()
        backupStore.ensureLayout(migrationId)
        val journal = runCatching {
            journalWriter.begin(
                migrationId = migrationId,
                source = JournalSource(
                    family = info.family.name,
                    version = info.version.orEmpty(),
                    uapi = info.uapi,
                    managerPackages = info.managerPackages,
                    restoreStrategy = info.restoreStrategy.name,
                ),
                target = JournalTarget(mode = target.name, targetSet = target.targetPartitions.toList()),
                restoreSet = info.patchedPartitions.toList(),
                kernelRelease = probe.device.kernelRelease,
                firmware = probe.device.firmware,
                slotSuffix = probe.device.slotSuffix,
            )
        }.getOrElse {
            return MigrationDetectionResult.Unavailable(JOURNAL_FAILED)
        }
        writeDetectSummary(migrationId, info) || return MigrationDetectionResult.Unavailable(JOURNAL_FAILED)
        pending = PendingDetection(
            migrationId = migrationId,
            info = info,
            probe = probe,
            eligibility = eligibility,
            journal = journal,
        )
        return MigrationDetectionResult.Ready(info, eligibility, migrationId)
    }

    override suspend fun start(target: WorkMode): MigrationStartResult {
        val detection = pending ?: return MigrationStartResult.Refused(NOT_DETECTED)
        val blocked = detection.eligibility.blockers
        if (blocked.isNotEmpty()) {
            return MigrationStartResult.Refused(
                code = blocked.first().code,
                reasons = blocked.map { it.code },
            )
        }
        if (detection.eligibility.targetAllowed[target] != true) {
            return MigrationStartResult.Refused(MigrationCodes.CANDIDATE_UNPRODUCIBLE, listOf(target.name))
        }
        val flow = states.getOrPut(detection.migrationId) { MutableStateFlow(null) }
        emit(flow, MigrationObservation.Progress(stage = MigrationStage.BACKING_UP, stepKey = MigrationStageKeys.M1))
        val job = scope.launch {
            runMigration(detection, target, flow)
        }
        jobs[detection.migrationId] = job
        return MigrationStartResult.Accepted(detection.migrationId)
    }

    override fun observe(migrationId: String): Flow<MigrationObservation> = flow {
        val active = states[migrationId]
        if (active != null) {
            emitAll(active.filterNotNull())
            return@flow
        }
        // 进程重启/页面重进：从 journal 重建最近观察（终态直接给出结果）。
        observationFromJournal(migrationId)?.let { emit(it) }
        val created = states.getOrPut(migrationId) { MutableStateFlow(null) }
        emitAll(created.filterNotNull())
    }

    override suspend fun abort(migrationId: String): Boolean {
        val job = jobs[migrationId] ?: return false
        val journal = runCatching { journalWriter.read(migrationId) }.getOrNull() ?: return false
        if (isDestructiveStarted(journal)) return false
        job.cancel(CancellationException(ABORT_REQUESTED))
        job.join()
        jobs.remove(migrationId)
        return true
    }

    // ------------------------------------------------------------------ 执行

    private suspend fun runMigration(
        detection: PendingDetection,
        target: WorkMode,
        flow: MutableStateFlow<MigrationObservation?>,
    ) {
        var journal = detection.journal
        val migrationId = detection.migrationId
        try {
            // ---- M1 备份（先备份后任何破坏性动作；红线 11）
            val backupOutcome = backup.capture(migrationId, detection.info)
            when (backupOutcome) {
                is MigrationBackupOutcome.Failed -> {
                    journal = transition(journal, MigrationState.M1_FAILED, "M1", backupOutcome.code)
                    journalWriter.recordError(journal, backupOutcome.code)
                    emit(
                        flow,
                        MigrationObservation.Failed(
                            MigrationFailureUi(
                                code = backupOutcome.code,
                                stage = MigrationStage.BACKING_UP,
                                detailKey = "migration_backup_failed",
                            ),
                        ),
                    )
                    return
                }
                is MigrationBackupOutcome.Verified -> {
                    if (!backupOutcome.summary.complete) {
                        journal = transition(journal, MigrationState.M1_FAILED, "M1", MigrationCodes.BACKUP_VERIFY_FAILED)
                        emit(
                            flow,
                            MigrationObservation.Failed(
                                MigrationFailureUi(
                                    code = MigrationCodes.BACKUP_VERIFY_FAILED,
                                    stage = MigrationStage.BACKING_UP,
                                ),
                            ),
                        )
                        return
                    }
                    emit(flow, MigrationObservation.Backup(backupOutcome.summary))
                    journal = transition(journal, MigrationState.M1_BACKED_UP, "M1")
                    journal = journalWriter.recordBackupVerified(journal, backupOutcome.manifestSha256)
                }
            }

            // ---- M2 计划（单 entry/还原先行/长度门禁；失败不写分区）
            val plan = try {
                PartitionOrderPlanner.compose(planInput(detection, target))
            } catch (failure: MigrationFailure) {
                journal = transition(journal, MigrationState.M2_FAILED, "M2", failure.code)
                journalWriter.recordError(journal, failure.code)
                emit(
                    flow,
                    MigrationObservation.Failed(
                        MigrationFailureUi(code = failure.code, stage = MigrationStage.BACKING_UP),
                    ),
                )
                return
            } catch (failure: Throwable) {
                journal = transition(journal, MigrationState.M2_FAILED, "M2", MigrationCodes.PLAN_INVALID)
                emit(
                    flow,
                    MigrationObservation.Failed(
                        MigrationFailureUi(code = MigrationCodes.PLAN_INVALID, stage = MigrationStage.BACKING_UP),
                    ),
                )
                return
            }
            journal = transition(journal, MigrationState.M2_PLANNED, "M2")
            journal = journalWriter.setPlan(
                journal,
                partitions = plan.entries.map { entry -> entry.toJournal() },
                restoreSet = plan.restoreSet.toList(),
                residuals = plan.entries
                    .filter { it.residue != ResidueMode.NONE }
                    .map { ResidueRecord("EXTERNAL_RESIDUE", it.name, it.residue.name) },
            )
            journal = transition(journal, MigrationState.M3_PRODUCED, "M3")

            // ---- M4 写入（设备侧 fail-closed）
            if (plan.restoreOnlyPartitions().isNotEmpty()) {
                emit(
                    flow,
                    MigrationObservation.Progress(
                        stage = MigrationStage.RESTORING_EXTERNAL,
                        stepKey = MigrationStageKeys.M4,
                    ),
                )
            }
            emit(
                flow,
                MigrationObservation.Progress(stage = MigrationStage.WRITING_TARGET, stepKey = MigrationStageKeys.M4),
            )
            if (!writer.available()) {
                journal = transition(journal, MigrationState.FAILED, "M4", UnavailableMigrationWritePort.CODE)
                journalWriter.recordError(journal, UnavailableMigrationWritePort.CODE)
                emit(
                    flow,
                    MigrationObservation.Failed(
                        MigrationFailureUi(
                            code = UnavailableMigrationWritePort.CODE,
                            stage = MigrationStage.WRITING_TARGET,
                            detailKey = "migration_device_write_unavailable",
                        ),
                    ),
                )
                return
            }
            journal = transition(journal, MigrationState.M4_WRITING, "M4")
            for (entry in plan.writableEntries()) {
                journalWriter.recordIntent(journal, entry.name, entry.pre.sha256)
            }
            when (val written = writer.write(migrationId, target, plan)) {
                is MigrationWriteOutcome.Failed -> {
                    emitFailureAfterWrite(
                        detection, target, plan, flow,
                        journal = transition(journal, MigrationState.ROLLING_BACK, "RB", written.code),
                        code = written.code,
                        rolledBack = true,
                    )
                    return
                }
                is MigrationWriteOutcome.RolledBack -> {
                    val rolled = transition(journal, MigrationState.ROLLING_BACK, "RB", written.code)
                    journal = transition(rolled, MigrationState.ROLLED_BACK, "RB")
                    emit(
                        flow,
                        MigrationObservation.Completed(
                            result(
                                kind = MigrationResultKind.ROLLED_BACK,
                                plan = plan,
                                backup = null,
                                errorCode = written.code,
                            ),
                        ),
                    )
                    return
                }
                is MigrationWriteOutcome.RecoveryRequired -> {
                    val rolling = transition(journal, MigrationState.ROLLING_BACK, "RB", written.code)
                    journal = transition(rolling, MigrationState.RECOVERY_REQUIRED, "RB", written.code)
                    emit(
                        flow,
                        MigrationObservation.Completed(
                            result(MigrationResultKind.RECOVERY_REQUIRED, plan, null, written.code),
                        ),
                    )
                    return
                }
                is MigrationWriteOutcome.Written -> {
                    journal = transition(journal, MigrationState.M5_DEPLOYING, "M5")
                }
            }

            // ---- M5 部署（目标用户态/数据）
            emit(
                flow,
                MigrationObservation.Progress(stage = MigrationStage.DEPLOYING, stepKey = MigrationStageKeys.M5),
            )
            when (val deployed = writer.deploy(migrationId, target)) {
                is MigrationWriteOutcome.Written -> {
                    journal = transition(journal, MigrationState.M6_CLEANUP, "M6")
                    journal = transition(journal, MigrationState.M7_FINALIZED, "M7")
                    emit(
                        flow,
                        MigrationObservation.Progress(
                            stage = MigrationStage.PENDING_REBOOT,
                            stepKey = MigrationStageKeys.M7,
                        ),
                    )
                    emit(
                        flow,
                        MigrationObservation.Completed(
                            result(
                                kind = MigrationResultKind.VERIFIED,
                                plan = plan,
                                backup = null,
                                errorCode = "",
                            ),
                        ),
                    )
                }
                is MigrationWriteOutcome.RolledBack -> {
                    val rolling = transition(journal, MigrationState.ROLLING_BACK, "RB", deployed.code)
                    journal = transition(rolling, MigrationState.ROLLED_BACK, "RB")
                    emit(
                        flow,
                        MigrationObservation.Completed(
                            result(MigrationResultKind.ROLLED_BACK, plan, null, deployed.code),
                        ),
                    )
                }
                is MigrationWriteOutcome.RecoveryRequired -> {
                    val rolling = transition(journal, MigrationState.ROLLING_BACK, "RB", deployed.code)
                    journal = transition(rolling, MigrationState.RECOVERY_REQUIRED, "RB", deployed.code)
                    emit(
                        flow,
                        MigrationObservation.Completed(
                            result(MigrationResultKind.RECOVERY_REQUIRED, plan, null, deployed.code),
                        ),
                    )
                }
                is MigrationWriteOutcome.Failed -> {
                    emitFailureAfterWrite(
                        detection, target, plan, flow,
                        journal = transition(journal, MigrationState.ROLLING_BACK, "RB", deployed.code),
                        code = deployed.code,
                        rolledBack = true,
                    )
                }
            }
        } catch (aborted: CancellationException) {
            val current = runCatching { journalWriter.read(migrationId) }.getOrNull() ?: journal
            if (!isDestructiveStarted(current)) {
                val rolling = transition(current, MigrationState.ROLLING_BACK, "RB", ABORT_REQUESTED)
                transition(rolling, MigrationState.ROLLED_BACK, "RB")
                emit(
                    flow,
                    MigrationObservation.Completed(
                        MigrationResultUi(kind = MigrationResultKind.ROLLED_BACK, errorCode = ABORT_REQUESTED),
                    ),
                )
            }
        } catch (failure: Throwable) {
            val code = (failure as? MigrationFailure)?.code ?: MigrationCodes.PLAN_INVALID
            val current = runCatching { journalWriter.read(migrationId) }.getOrNull() ?: journal
            if (!current.isTerminal()) {
                val rolling = transition(current, MigrationState.ROLLING_BACK, "RB", code)
                transition(rolling, MigrationState.RECOVERY_REQUIRED, "RB", code)
            }
            emit(
                flow,
                MigrationObservation.Failed(
                    MigrationFailureUi(code = code, stage = MigrationStage.WRITING_TARGET, recoveryRequired = true),
                ),
            )
        } finally {
            jobs.remove(migrationId)
        }
    }

    private fun emitFailureAfterWrite(
        detection: PendingDetection,
        target: WorkMode,
        plan: MigrationPlan,
        flow: MutableStateFlow<MigrationObservation?>,
        journal: MigrationJournalFile,
        code: String,
        rolledBack: Boolean,
    ) {
        if (rolledBack) {
            transition(journal, MigrationState.ROLLED_BACK, "RB")
            emit(
                flow,
                MigrationObservation.Completed(
                    result(MigrationResultKind.ROLLED_BACK, plan, null, code),
                ),
            )
        } else {
            transition(journal, MigrationState.RECOVERY_REQUIRED, "RB", code)
            emit(
                flow,
                MigrationObservation.Completed(
                    result(MigrationResultKind.RECOVERY_REQUIRED, plan, null, code),
                ),
            )
        }
    }

    // ------------------------------------------------------------------ 辅助

    private fun planInput(detection: PendingDetection, target: WorkMode): PlanInput {
        val probe = detection.probe
        val present = probe.partitions.filterValues { it.present && it.sha256.matches(HEX64) }
        val pre = present.mapValues { (name, facts) ->
            ImageRef("partition:$name", facts.size, facts.sha256.lowercase(), ImageSource.PRE_PARTITION)
        }
        val clean = detection.info.stockBackups.values
            .filter { it.state == BackupState.USABLE && it.sha256 != null && it.sha256.matches(HEX64) }
            .mapNotNull { backupRef ->
                val hint = backupRef.partitionHint ?: return@mapNotNull null
                hint to ImageRef(
                    backupRef.path,
                    backupRef.size,
                    backupRef.sha256!!.lowercase(),
                    ImageSource.EXTERNAL_STOCK,
                )
            }
            .toMap()
        return PlanInput(
            migrationId = detection.migrationId,
            target = target,
            info = detection.info,
            pre = pre,
            clean = clean,
            partitionSize = present.mapValues { it.value.size },
        )
    }

    private fun result(
        kind: MigrationResultKind,
        plan: MigrationPlan,
        backup: MigrationBackupSummary?,
        errorCode: String,
    ): MigrationResultUi = MigrationResultUi(
        kind = kind,
        backup = backup,
        rebootRequired = kind == MigrationResultKind.VERIFIED,
        residual = plan.residual,
        residues = plan.entries
            .filter { it.residue != ResidueMode.NONE }
            .map { "${it.name}:${it.residue.name}" },
        errorCode = errorCode,
    )

    private fun PlannedPartition.toJournal(): JournalPartition = JournalPartition(
        name = name,
        action = action.name,
        pre = JournalImage(pre.path, pre.size, pre.sha256),
        final = final?.let { JournalImage(it.path, it.size, it.sha256) } ?: JournalImage(),
        producerInput = producerInput?.let { JournalImage(it.path, it.size, it.sha256, it.source.name) },
        residue = residue.name,
    )

    private fun emit(flow: MutableStateFlow<MigrationObservation?>, observation: MigrationObservation) {
        flow.value = observation
    }

    private fun transition(
        journal: MigrationJournalFile,
        to: MigrationState,
        phase: String,
        error: String = "",
    ): MigrationJournalFile = runCatching {
        journalWriter.transition(journal, to, phase, error)
    }.getOrElse { journal }

    private fun isDestructiveStarted(journal: MigrationJournalFile): Boolean {
        if (journal.partitions.any { it.writeIntent }) return true
        return journal.stateValue() in setOf(
            MigrationState.M4_WRITING,
            MigrationState.M5_DEPLOYING,
            MigrationState.M6_CLEANUP,
            MigrationState.M7_FINALIZED,
            MigrationState.M8_REBOOT_REQUESTED,
        )
    }

    private fun observationFromJournal(migrationId: String): MigrationObservation? {
        val journal = runCatching { journalWriter.read(migrationId) }.getOrNull() ?: return null
        return when (journal.stateValue()) {
            MigrationState.M1_FAILED -> MigrationObservation.Failed(
                MigrationFailureUi(code = MigrationCodes.BACKUP_VERIFY_FAILED, stage = MigrationStage.BACKING_UP),
            )
            MigrationState.M2_FAILED -> MigrationObservation.Failed(
                MigrationFailureUi(code = MigrationCodes.PLAN_INVALID, stage = MigrationStage.BACKING_UP),
            )
            MigrationState.FAILED -> MigrationObservation.Failed(
                MigrationFailureUi(code = journal.error, stage = MigrationStage.WRITING_TARGET),
            )
            MigrationState.ROLLED_BACK -> MigrationObservation.Completed(
                MigrationResultUi(kind = MigrationResultKind.ROLLED_BACK, errorCode = journal.error),
            )
            MigrationState.RECOVERY_REQUIRED -> MigrationObservation.Completed(
                MigrationResultUi(kind = MigrationResultKind.RECOVERY_REQUIRED, errorCode = journal.error),
            )
            MigrationState.M9_VERIFIED -> MigrationObservation.Completed(
                MigrationResultUi(kind = MigrationResultKind.VERIFIED),
            )
            MigrationState.M8_REBOOT_REQUESTED, MigrationState.M7_FINALIZED -> MigrationObservation.Progress(
                stage = MigrationStage.PENDING_REBOOT,
                stepKey = MigrationStageKeys.M7,
            )
            else -> MigrationObservation.Progress(
                stage = MigrationStage.DETECTED,
                stepKey = MigrationStageKeys.M0,
            )
        }
    }

    /** 写 detect.json；失败即视为检测失败（不进入可开始状态）。 */
    private fun writeDetectSummary(migrationId: String, info: ExternalRootInfo): Boolean {
        val directory = MigrationPaths.migrationDir(root, migrationId)
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) return false
        val patched = info.patchedPartitions.sorted().joinToString(",") { "\"${escape(it)}\"" }
        val managers = info.managerPackages.sorted().joinToString(",") { "\"${escape(it)}\"" }
        val json = buildString {
            append("{")
            append("\"schema\":1,")
            append("\"family\":\"").append(info.family.name).append("\",")
            append("\"version\":\"").append(escape(info.version.orEmpty())).append("\",")
            append("\"uapi\":").append(info.uapi ?: 0).append(',')
            append("\"restoreStrategy\":\"").append(info.restoreStrategy.name).append("\",")
            append("\"bestEffort\":").append(info.bestEffort).append(',')
            append("\"patchedPartitions\":[").append(patched).append("],")
            append("\"managerPackages\":[").append(managers).append("],")
            append("\"detectedAt\":").append(clock.nowMillis())
            append("}")
        }
        return runCatching {
            AtomicControlFile.writeJson(File(directory, "detect.json"), json)
        }.isSuccess
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    private data class PendingDetection(
        val migrationId: String,
        val info: ExternalRootInfo,
        val probe: ExternalRootProbe,
        val eligibility: MigrationEligibility,
        val journal: MigrationJournalFile,
    )

    companion object {
        const val PROBE_FAILED = "MIGRATION_PROBE_FAILED"
        const val JOURNAL_FAILED = "MIGRATION_JOURNAL_INIT_FAILED"
        const val NOT_DETECTED = "MIGRATION_NOT_DETECTED"
        const val ABORT_REQUESTED = "MIGRATION_ABORT_REQUESTED"
        private val HEX64 = Regex("[0-9a-fA-F]{64}")
    }
}
