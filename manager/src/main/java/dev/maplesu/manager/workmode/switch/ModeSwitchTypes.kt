package dev.maplesu.manager.workmode.switch

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.ModeState
import java.io.File

/**
 * W08 切换编排的类型与端口。
 *
 * 设计边界：
 * - 本包（`workmode/switch/ModeSwitch*.kt`）是**App 侧编排**：S0 只读预检、阶段推进、
 *   switch.json/journal 写入、失败决策、恢复判定与 UI 投影。
 * - 一切 root/分区动作经 [InstallerGateway] 端口（真实实现 = `installer/InstallerServiceClient`
 *   的 AIDL 封装，见 `workmode/service/ServiceClientInstallerGateway.kt`）。
 * - 主机单测用 Fake 实现全部端口；本文件与 Controller 不出现任何 `android.*` 类型，
 *   因此可在 `:manager:testDebugUnitTest` 直接跑。
 *
 * 命名冲突：本包已有 `SwitchJournal`/`BaselineStore`。
 * 本编排**只消费**它们，不复制其状态机；`switch.json` 权威态由 [SwitchAuthority] 承担。
 */

// ---------------------------------------------------------------------------
// 固定步骤/阶段（02 §3.1 写死步骤 id）
// ---------------------------------------------------------------------------

/** 主阶段。`wire` 与 02 §3.1 的 `S0..S6` 文本一致（写进 journal/switch.json）。 */
enum class SwitchPhase(val wire: String) {
    PREFLIGHT("S0"),
    BEGIN("S1"),
    SNAPSHOT("S2"),
    PRODUCE("S3"),
    COMMIT("S4"),
    REBOOT("S5"),
    VERIFY("S6"),
    DONE("DONE"),
}

/** 子步 id（02 §3.1 表格）；`4c`/`4d` 是角色，不是分区名。 */
object SwitchSteps {
    const val BEGIN = "begin"
    const val SNAPSHOT = "snapshot"
    const val PRODUCE = "produce"
    const val QUIESCE = "4a"
    const val MOVE_OUT = "4b"
    const val RESTORE_BASELINE = "4c"
    const val WRITE_CANDIDATE = "4d"
    const val MOVE_IN = "4e"
    const val MATERIALIZE = "4f"
    const val COMMIT_PENDING = "4g"
    const val REBOOT = "S5"
    const val VERIFY_PARTITIONS = "6.1"
    const val VERIFY_DEVICE = "6.2"
    const val VERIFY_CONSUMER = "6.3"
    const val VERIFY_SUPERUSER = "6.4"

    /** 4a→4g 全部子步，按执行顺序；用于恢复矩阵逐项判定。 */
    val COMMIT_ORDER = listOf(QUIESCE, MOVE_OUT, RESTORE_BASELINE, WRITE_CANDIDATE,
        MOVE_IN, MATERIALIZE, COMMIT_PENDING)
}

/** 错误码常量（02 §7.5；服务端只给码，UI 做映射）。 */
object ModeSwitchCodes {
    const val STATE_UNDETERMINED = "SWITCH_STATE_UNDETERMINED"
    const val NOT_INSTALLED = "SWITCH_NOT_INSTALLED"
    const val FOREIGN_SU = "SWITCH_FOREIGN_SU"
    const val SAME_MODE = "SWITCH_SAME_MODE"
    const val INSTALL_PENDING = "SWITCH_INSTALL_PENDING"
    const val PENDING = "SWITCH_PENDING"
    const val BUSY = "SWITCH_BUSY"
    const val SPACE_INSUFFICIENT = "SWITCH_SPACE_INSUFFICIENT"
    const val BATTERY_LOW = "SWITCH_BATTERY_LOW"
    const val ROOT_UNAVAILABLE = "SWITCH_ROOT_UNAVAILABLE"
    const val IMPLANT_RESTORE_MISSING = "SWITCH_IMPLANT_RESTORE_MISSING"
    const val EXPORT_REQUIRED = "SWITCH_EXPORT_REQUIRED"
    const val LAYOUT_UNSUPPORTED = "SWITCH_PARTITION_LAYOUT_UNSUPPORTED"
    const val SLOT_INVALID = "INSTALLER_SLOT_INVALID"
    const val KMI_UNSUPPORTED = "SWITCH_KMI_UNSUPPORTED"
    const val AVB_LAYOUT_UNKNOWN = "SWITCH_AVB_LAYOUT_UNKNOWN"
    const val PARTITION_READONLY = "SWITCH_PARTITION_READONLY"
    const val DEVICE_CHANGED = "INSTALLER_DEVICE_CHANGED"
    const val SELINUX_NOT_ENFORCING = "INSTALLER_SELINUX_NOT_ENFORCING"
    const val CONTROL_DIR_INVALID = "INSTALLER_STATE_DIRECTORY_INVALID"

    const val PLAN_INVALID = "SWITCH_PLAN_INVALID"
    const val PLAN_CONFLICT = "SWITCH_PLAN_CONFLICT"
    const val PRE_IMAGE_INVALID = "SWITCH_PRE_IMAGE_INVALID"
    const val PRE_IMAGE_CHANGED = "SWITCH_PRE_IMAGE_CHANGED"

    const val SNAPSHOT_FAILED = "SWITCH_SNAPSHOT_FAILED"
    const val PRODUCE_FAILED = "PRODUCE_FAILED"
    const val PRODUCE_OUTPUT_SIZE_INVALID = "PRODUCE_OUTPUT_SIZE_INVALID"
    const val PRODUCE_CANDIDATE_CHANGED = "PRODUCE_CANDIDATE_CHANGED"
    const val PRODUCE_CANDIDATE_CONFLICT = "PRODUCE_CANDIDATE_CONFLICT"
    const val PREPARE_EXPORT_FAILED = "SWITCH_EXPORT_REQUIRED"

    const val QUIESCE_TIMEOUT = "SWITCH_QUIESCE_TIMEOUT"
    const val WRITE_FAILED = "WRITE_FAILED"
    const val WRITE_IO_FAILED = "WRITE_IO_FAILED"
    const val WRITE_READBACK_MISMATCH = "WRITE_READBACK_MISMATCH"
    const val WRITE_TAIL_MISMATCH = "WRITE_TAIL_MISMATCH"
    const val WRITE_PARTITION_UNEXPECTED = "WRITE_PARTITION_UNEXPECTED"
    const val WRITE_ROLLBACK_FAILED = "WRITE_ROLLBACK_FAILED"
    /** 离线仓 rename 阶段回滚失败（S1 MOVE_OUT/MOVE_IN 的补偿动作失败）。 */
    const val MOVE_ROLLBACK_FAILED = "SWITCH_MOVE_ROLLBACK_FAILED"
    const val BOOTSTRAP_FAILED = "SWITCH_BOOTSTRAP_FAILED"

    const val REBOOT_NOT_OBSERVED = "SWITCH_REBOOT_NOT_OBSERVED"
    const val VERIFY_PARTITION_MISMATCH = "VERIFY_PARTITION_MISMATCH"
    const val VERIFY_DEVICE_CHANGED = "VERIFY_DEVICE_CHANGED"
    const val VERIFY_BOOT_NOT_OBSERVED = "VERIFY_BOOT_NOT_OBSERVED"
    const val VERIFY_CONSUMER_FAILED = "VERIFY_CONSUMER_FAILED"
    const val VERIFY_SUPERUSER_FAILED = "VERIFY_SUPERUSER_FAILED"

    const val RECOVERY_REQUIRED = "RECOVERY_REQUIRED"
    const val RECOVERY_JOURNAL_CORRUPT = "RECOVERY_JOURNAL_CORRUPT"
    const val ABORTED = "SWITCH_ABORTED"
    const val ROLLBACK_FAILED = "WRITE_ROLLBACK_FAILED"
}

// ---------------------------------------------------------------------------
// 请求与设备事实
// ---------------------------------------------------------------------------

/** 一次切换的语义参数；分区/镜像细节由 S2/S3 产生（02 §2.3 的 request 超集）。 */
data class ModeSwitchRequest(
    val switchId: String,
    val fromMode: WorkMode,
    val toMode: WorkMode,
    val baselineId: String,
    val fromBuild: String = "",
    val toBuild: String = "",
    val targetPartition: String? = null,
    /** null retains a legacy record's source; empty means the current source is unknown. */
    val sourcePartition: String? = null,
) {
    init {
        require(switchId.matches(Regex("[0-9a-fA-F-]{36}"))) { ModeSwitchCodes.PLAN_INVALID }
        require(fromMode != toMode) { ModeSwitchCodes.SAME_MODE }
        require(targetPartition == null || targetPartition in ModeSwitchPreflight.allowedTargets(toMode)) {
            ModeSwitchCodes.PLAN_INVALID
        }
        require(sourcePartition.isNullOrEmpty() || sourcePartition in ModeSwitchPreflight.allowedTargets(fromMode)) {
            ModeSwitchCodes.PLAN_INVALID
        }
    }

    val resolvedTarget: String get() = targetPartition ?: ModeSwitchPreflight.targetPartition(toMode)

    fun plannedPartitions(available: Set<String>? = null): List<String> =
        ModeSwitchPreflight.writeOrder(toMode, resolvedTarget, sourcePartition, available)

    fun resolve(available: Set<String>?, kernelRelease: String?): ModeSwitchRequest = copy(
        targetPartition = ModeSwitchPreflight.resolveTarget(toMode, targetPartition, available,
            ModeSwitchKmi.parse(kernelRelease)) ?: error(ModeSwitchCodes.PLAN_INVALID),
        sourcePartition = sourcePartition ?: "",
    )
}

/** 只读设备事实（02 §3.2 `device`）。 */
data class SwitchDeviceFacts(
    val slot: String,
    val firmware: String,
    val kernelRelease: String,
    val selinux: String = "Enforcing",
)

data class BatteryFacts(val levelPercent: Int, val charging: Boolean)

/** pending/锁的 App 侧视图（02 §2.2 S0-06/07/14）。 */
data class PendingFacts(
    val installPending: Boolean = false,
    val switchPending: Boolean = false,
    val deviceLockAvailable: Boolean = true,
)

/** 只读分区清单（S0-17/18/22）。`sizes` 缺失 = 未读到，由网关预检复判。 */
data class PartitionInventory(
    val present: Set<String> = emptySet(),
    val sizes: Map<String, Long> = emptyMap(),
    val readOnly: Map<String, Boolean> = emptyMap(),
)

/** S0-20 只读 AVB 结构结论；`code` 为空 = 已知且可继续。 */
data class SwitchAvbFacts(val code: String = "", val detail: String = "")

/** S1 需要的导出文件（superuser.json / settings 镜像）；由 W14/W05 产出，未就绪则 S1 前失败。 */
data class BeginInputs(
    val superuserJson: File?,
    val settingsMirrorJson: File?,
    val error: String = "",
)

/**
 * S0a/本地只读事实端口。真实实现放 `workmode/service/`（W04/W05 接线）；
 * 主机测试用 Fake 逐项注入。**不得**在本接口内做任何写入。
 */
interface SwitchEnvironment {
    fun modeState(): ModeState?

    /** `modes.json.current`；null = 未判定（02 S0-03 用）。 */
    fun currentMode(): WorkMode?

    fun deviceFacts(): SwitchDeviceFacts

    fun battery(): BatteryFacts

    fun pending(): PendingFacts

    fun rootRefusal(): String?

    /** S0-13：控制面目录存在且 0700 0:0 可写。 */
    fun controlWritable(): Boolean

    /** S0-15：W19 植入撤回材料是否齐备（只在切出 LITE 时要求）。 */
    fun implantRestoreReady(): Boolean

    /** S0-16：外部恢复导出的回执 partition→sha256（与 baseline 摘要比对）。 */
    fun exportReceipt(): Map<String, String>

    fun partitionInventory(): PartitionInventory

    fun avb(): SwitchAvbFacts

    /** S0-09 空间预算：控制面/data 所在挂载点可用字节。 */
    fun freeBytes(): Long

    /** S1 的导出文件；未就绪返回 [BeginInputs.error]。 */
    fun beginInputs(): BeginInputs

    /** S6 失败时是否还有目标 root 可执行回滚（02 §6.1）。 */
    fun targetRootAvailable(to: WorkMode): Boolean
}

// ---------------------------------------------------------------------------
// 安装服务端口（root 侧；真实实现 = InstallerServiceClient 封装）
// ---------------------------------------------------------------------------

/** 单个分区的服务端观测（S6.1 / 恢复判定读回）。 */
data class PartitionObservation(
    val name: String,
    val size: Long,
    val sha256: String,
    val readOnly: Boolean = false,
)

/** switch.json 分区行（02 §3.2 `partitions[i]`）。 */
data class SwitchPartitionStatus(
    val name: String,
    val role: String,
    val size: Long,
    val preSha256: String,
    val targetSha256: String,
    val tailSha256: String,
    val observedSha256: String,
    val state: String,
)

/** `getModeSwitch` 投影。空 switchId 由实现处理，本类型只承载已知值。 */
data class GatewayStatus(
    val switchId: String,
    val state: String,
    val stage: String = "",
    val step: String = "",
    val error: String = "",
    val errorDetail: String = "",
    val fromMode: String = "",
    val toMode: String = "",
    val planSha256: String = "",
    val rebootRequired: Boolean = false,
    val bootObserved: Boolean = false,
    val operationActive: Boolean = false,
    val canRollback: Boolean = false,
    val canResumeReboot: Boolean = false,
    val verifyFailures: Int = 0,
    val partitions: List<SwitchPartitionStatus> = emptyList(),
    val device: Map<String, String> = emptyMap(),
)

/**
 * 所有 root 侧动作的统一结果。`state` 取 [GatewayState] 的 wire 或空串（调用失败）。
 * `code` 是契约错误码；`detail` 是原始 `INSTALLER_*`/诊断信息。
 */
data class GatewayOutcome(
    val ok: Boolean,
    val state: String,
    val code: String = "",
    val detail: String = "",
    val status: GatewayStatus? = null,
)

object GatewayState {
    const val WRITTEN = "WRITTEN"
    const val ROLLED_BACK = "ROLLED_BACK"
    const val RECOVERY_REQUIRED = "RECOVERY_REQUIRED"
    const val VERIFIED = "VERIFIED"
    const val REBOOT_REQUIRED = "REBOOT_REQUIRED"
    const val CANCELLED = "CANCELLED"
}

/** S0b 预检结果（root 服务权威）。`checks` 供合并进 UI 报告。 */
data class GatewayPreflight(
    val ok: Boolean,
    val code: String = "",
    val detail: String = "",
    val preflightSha256: String = "",
    val checks: List<S0Check> = emptyList(),
    val device: SwitchDeviceFacts? = null,
    val partitionInventory: PartitionInventory? = null,
    val avb: SwitchAvbFacts? = null,
)

/** 从服务端导出的工作镜像（PRE/BASE/CANDIDATE 只读副本落地成本地文件）。 */
data class ExportedImage(val file: File, val size: Long, val sha256: String)

/**
 * 安装服务端口：唯一允许触达分区/root 的抽象。
 *
 * 红线 4：实现方（`installer` 侧）必须复用 `BlockDeviceTransaction`；本编排只调用，
 * 不持有任何字节写入原语。
 */
interface InstallerGateway {
    /** S0b 只读预检（`preflightModeSwitch`）。 */
    fun preflight(request: ModeSwitchRequest): GatewayPreflight

    /** S1：建立持久 pending、写 switch.json、写 superuser/settings 镜像（`beginModeSwitch`）。 */
    fun begin(request: ModeSwitchRequest, inputs: BeginInputs): GatewayOutcome

    /** S2：捕获 pre 镜像并暴露 base 镜像（`snapshotModeSwitch`）。 */
    fun snapshot(request: ModeSwitchRequest): GatewayOutcome

    /** 把 PRE/BASE/CANDIDATE 只读副本导出到本地文件（`openSwitchImage`）。 */
    fun exportImage(request: ModeSwitchRequest, kind: String, partition: String, destination: File): ExportedImage

    /** S3：提交候选（满长 + 摘要），服务端复核后才算 PRODUCE_DONE（`submitSwitchCandidate`）。 */
    fun submitCandidate(request: ModeSwitchRequest, partition: String, image: ExportedImage): GatewayOutcome

    /** S3b：向服务端注册计划（prepare 的传输面；不触碰分区）。 */
    fun preparePlan(request: ModeSwitchRequest, planFlatFields: Map<String, String>): GatewayOutcome

    /** S4：一次调用完成 4a–4g 的分区与数据动作（`commitModeSwitch`）。 */
    fun commit(request: ModeSwitchRequest): GatewayOutcome

    /** 逆序回滚到 pre（`rollbackModeSwitch`）。 */
    fun rollback(request: ModeSwitchRequest): GatewayOutcome

    /** 预写阶段中止（仅 BEGIN/SNAPSHOT/PRODUCE）。 */
    fun abort(request: ModeSwitchRequest): GatewayOutcome

    /** 读回观测（恢复判定；只读）。 */
    fun observePartitions(request: ModeSwitchRequest, names: List<String>): List<PartitionObservation>

    /** S6.1/6.2 服务端校验（`verifyModeSwitch`）。 */
    fun verify(request: ModeSwitchRequest): GatewayOutcome

    /** S5 固定重启命令（`rebootAfterModeSwitch`）。 */
    fun reboot(request: ModeSwitchRequest): Boolean

    /** 重新读取持久状态（`getModeSwitch`）；无记录返回 null。 */
    fun status(switchId: String): GatewayStatus?

    /** 导出 journal + recovery 文本（`openSwitchLog`）。 */
    fun exportDiagnostics(request: ModeSwitchRequest, destination: File): File
}

// ---------------------------------------------------------------------------
// 其余端口：快照 / 候选生产 / W19 钩子 / S6 消费确认
// ---------------------------------------------------------------------------

/** S1 快照端口（`SnapshotService.create(reason=PRE_SWITCH)`）。 */
interface ModeSwitchSnapshots {
    suspend fun preSwitchSnapshot(request: ModeSwitchRequest): GatewayOutcome

    /** 回滚成功后恢复 App 侧快照（尽力而为；失败只告警，不改变分区结论）。 */
    suspend fun restoreAfterRollback(request: ModeSwitchRequest): GatewayOutcome
}

/** 候选生产输入（02 §9.1 / §9.5）。producer 只产文件，不得打开任何块设备。 */
data class CandidateInput(
    val switchId: String,
    val toMode: WorkMode,
    val partition: String,
    val baselineImage: File,
    val baselineSha256: String,
    val workDir: File,
    val kmi: String,
    val kernelRelease: String,
    /** Memory-only producer inputs; never serialized into a switch journal or wire. */
    val initBootArtifact: dev.maplesu.image.UltraInitBootProducer.Artifact? = null,
    val targetBootSha256: String? = null,
) {
    /** Validated PRE lineage, memory-only; never part of a journal, credential or wire. */
    internal var deploymentNamespace: dev.maplesu.manager.ultrakernel.UltraKernelDeploymentNamespace? = null
        private set

    internal fun withDeploymentNamespace(value: dev.maplesu.manager.ultrakernel.UltraKernelDeploymentNamespace?): CandidateInput =
        copy().also { it.deploymentNamespace = value }
}

data class CandidateResult(
    val image: File?,
    val sha256: String,
    val size: Long,
    val code: String = "",
    val detail: String = "",
    val structureEvidence: String = "",
) {
    val ok: Boolean get() = image != null && code.isEmpty()
}

/** S3 producer 端口（W09 Lite / W12 KSU 各自实现；本册只定义接缝）。 */
fun interface SwitchCandidateProducer {
    fun produce(input: CandidateInput): CandidateResult
}

/** W19 钩子结果：`ok=false` 时 `code` 必须非空。 */
data class HookResult(val ok: Boolean, val code: String = "", val detail: String = "") {
    companion object {
        val OK = HookResult(true)
        fun failed(code: String, detail: String = "") = HookResult(false, code, detail)
    }
}

/**
 * W19 静默/植入撤回钩子（02 §5.4 4a；主文档 W19）。
 *
 * 现状：`SuDelivery`/`SuPathWiring` 没有任何备份/还原逻辑（01 §6.7），所以本接口的真实
 * 实现是 W19 的新交付物。**no-op 不得伪装成功**：默认实现 [NoOpSwitchQuiesceHooks]
 * 对"无 writer 可静默"的 App 层动作返回成功，但 `implantRestoreReady()` 恒 false（fail closed），
 * 因而切出 LITE 会被 S0-15 阻断，直到 W19 落地。
 */
interface SwitchQuiesceHooks {
    /** 4a：停止源模式写者并 flush 终态；失败 → ROLLING_BACK（数据级）。 */
    fun quiesce(fromMode: WorkMode): HookResult

    /** 失败回滚时恢复源模式写者/服务（尽力而为）。 */
    fun restoreAfterFailure(fromMode: WorkMode): HookResult

    /** 切出 LITE 前撤回产品植入入口（`SuDelivery.withdraw` / `SuPathWiring` 还原）。 */
    fun withdrawLiteImplants(): HookResult

    /** S0-15：原件 + 清单 + 逐文件摘要是否齐备。 */
    fun implantRestoreReady(): Boolean

    /** producer 失败/完成后清理其副作用（`/data/adb/ksu` 差分还原；02 §9.4）。 */
    fun restoreProducerSideEffects(): HookResult
}

/** 安全 no-op：不撒谎的默认实现（见 KDoc）。 */
object NoOpSwitchQuiesceHooks : SwitchQuiesceHooks {
    override fun quiesce(fromMode: WorkMode): HookResult = HookResult.OK
    override fun restoreAfterFailure(fromMode: WorkMode): HookResult = HookResult.OK
    override fun withdrawLiteImplants(): HookResult = HookResult.OK
    override fun implantRestoreReady(): Boolean = false
    override fun restoreProducerSideEffects(): HookResult = HookResult.OK
}

/** S6.3 mode consumer 确认（App 进程；`MapleSuRuntime`/`KsuNatives` 由 W11/W01 接线）。 */
fun interface ModeConsumerVerifier {
    fun verify(mode: WorkMode, bootId: String): HookResult
}

/** S6.4 授权 reconcile（01 §6.5 `SuperuserSync.reconcileAfterBoot`）。 */
fun interface SuperuserReconciler {
    suspend fun reconcileAfterBoot(mode: WorkMode): HookResult
}

/** S6 通过后提交 `modes.json.current=to`（01 §4.3；实现 = `ControlPlane.writeModes`）。 */
fun interface ModeCommitSink {
    fun commit(mode: WorkMode, bootId: String, bootSha256: String, initBootSha256: String)
}
