package dev.maplesu.manager.workmode.service

import dev.maplesu.installer.SwitchFailure
import dev.maplesu.manager.installer.BaselineProvisioning
import dev.maplesu.manager.installer.BaselineAtomicFiles
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.control.ControlPaths
import dev.maplesu.manager.workmode.detect.BootIdSource
import dev.maplesu.manager.workmode.detect.Clock
import dev.maplesu.manager.workmode.detect.ProcBootIdSource
import dev.maplesu.manager.workmode.detect.WallClock
import dev.maplesu.manager.workmode.switch.BaselineErrors
import dev.maplesu.manager.workmode.switch.BaselineState
import dev.maplesu.manager.workmode.switch.BaselineVerification
import dev.maplesu.manager.workmode.switch.CandidateResult
import dev.maplesu.manager.workmode.switch.ExportedImage
import dev.maplesu.manager.workmode.switch.GatewayOutcome
import dev.maplesu.manager.workmode.switch.GatewayState
import dev.maplesu.manager.workmode.switch.GatewayStatus
import dev.maplesu.manager.workmode.switch.InstallerGateway
import dev.maplesu.manager.workmode.switch.JournalLine
import dev.maplesu.manager.workmode.switch.JournalReplay
import dev.maplesu.manager.workmode.switch.ModeSwitchCodes
import dev.maplesu.manager.workmode.switch.ModeSwitchHashes
import dev.maplesu.manager.workmode.switch.ModeSwitchJournalSink
import dev.maplesu.manager.workmode.switch.ModeSwitchKmi
import dev.maplesu.manager.workmode.switch.ModeSwitchPlanBuilder
import dev.maplesu.manager.workmode.switch.ModeSwitchPreflight
import dev.maplesu.manager.workmode.switch.ModeSwitchRequest
import dev.maplesu.manager.workmode.switch.ModeSwitchSnapshots
import dev.maplesu.manager.workmode.switch.ModeSwitchStatusProjector
import dev.maplesu.manager.workmode.switch.SwitchAuthority
import dev.maplesu.manager.workmode.switch.SwitchJournal
import dev.maplesu.manager.workmode.switch.SwitchJournalFactory
import dev.maplesu.manager.workmode.switch.SwitchOp
import dev.maplesu.manager.workmode.switch.SwitchPartitionStatus
import dev.maplesu.manager.workmode.switch.SwitchRecord
import dev.maplesu.manager.workmode.switch.SwitchRecordAttempts
import dev.maplesu.manager.workmode.switch.SwitchRecordDevice
import dev.maplesu.manager.workmode.switch.SwitchRecordError
import dev.maplesu.manager.workmode.switch.SwitchRecordMaterialize
import dev.maplesu.manager.workmode.switch.SwitchRecordMove
import dev.maplesu.manager.workmode.switch.SwitchRecordPartition
import dev.maplesu.manager.workmode.switch.SwitchSteps
import dev.maplesu.manager.workmode.switch.SwitchStateNames
import dev.maplesu.manager.workmode.switch.TakeoverRequest
import dev.maplesu.manager.workmode.switch.BaselineCaptureRequest
import dev.maplesu.manager.workmode.switch.BaselineImportRequest
import dev.maplesu.manager.workmode.switch.BaselineManifest
import dev.maplesu.manager.workmode.switch.BaselineCheck
import dev.maplesu.manager.workmode.switch.BaselineStore
import dev.maplesu.manager.workmode.switch.FixedPlanJournal
import dev.maplesu.manager.workmode.switch.MoveStagePlan
import dev.maplesu.manager.workmode.switch.MoveStageResult
import dev.maplesu.manager.workmode.switch.SwitchMoveStage
import java.io.File
import java.io.IOException

/** 服务端（UID 0）看到的模式事实。由 `ModesFile`/`ModeRuntime` 适配器提供。 */
data class DeviceModeFacts(
    val currentMode: WorkMode?,
    /** 六态 wire 名；null = 未判定。 */
    val modeState: String? = null,
)

fun interface DeviceModeFactsPort {
    fun read(): DeviceModeFacts
}

/** Live writer exclusion; a historical journal does not own this guard. */
fun interface DevicePendingGuard {
    @Throws(IOException::class)
    fun assertNoPendingInstall()
}

/** 跨进程设备锁（服务端实现 = InstallerDeviceLock.withDeviceLock）。 */
interface DeviceLockPort {
    fun <T> withLock(action: () -> T): T
}

/** 服务端状态（AIDL Bundle 由 `ModeSwitchAidlCodec` 编码）。 */
data class DeviceSwitchStatus(
    val switchId: String,
    val state: String,
    val stage: String,
    val step: String,
    val fromMode: String,
    val toMode: String,
    val error: String,
    val errorDetail: String,
    val planSha256: String,
    val rebootRequired: Boolean,
    val bootObserved: Boolean,
    val operationActive: Boolean,
    val canRollback: Boolean,
    val canResumeReboot: Boolean,
    val verifyFailures: Int,
    val partitions: List<SwitchPartitionStatus>,
    val device: Map<String, String>,
)

data class DeviceSwitchResult(
    val ok: Boolean,
    val code: String = "",
    val detail: String = "",
    val status: DeviceSwitchStatus? = null,
)

data class DevicePreflightResult(
    val ok: Boolean,
    val code: String = "",
    val detail: String = "",
    val status: DeviceSwitchStatus? = null,
    /**
     * P0-1 第 3 项：**未阻断**但值得记录的降级说明（例如"缺基线，App 侧会先自动获取"）。
     *
     * 只有 [ModeSwitchDeviceDelegate.baselineProvisionable] = true 且失败码属于
     * `BaselinePreflightRepair` 白名单时才会非空；它**不降低**任何写入门槛
     * （显式基线仍在后续阶段验证；已接 sourceRecovery 的空 baseline 直接使用 PRE）。
     */
    val warning: String = "",
    /**
     * **非阻断事实**（2026-11-01 用户指令）：目前只有 SELinux，格式
     * `INSTALLER_SELINUX_NOT_ENFORCING:selinux=<Permissive|Disabled|Unknown>`，`Enforcing` 时为空。
     *
     * 它**不是** blocker：`ok` 仍为 `true`、`code` 仍为空，begin/snapshot/commit 照常推进。
     * 只用于日志/诊断（服务端权威事实另见 `switch.json` 的 `device.selinux`）。
     */
    val advisory: String = "",
)

/** 契约错误：服务方法捕获后转 Bundle，**不得**让异常穿出 Binder。 */
class DeviceSwitchError(val code: String, val detail: String = "") : Exception("$code: $detail")

/**
 * W08 服务端增量状态机（S1–S6；AIDL 多调用拆分）。
 *
 * 与 App 侧 `ModeSwitchController` 的关系：二者消费同一份 `switch.json`/journal 合同与
 * 同一个 `InstallerGateway` 抽象；本类只做 root 服务进程内的**跨 Binder 调用**推进，
 * 分区字节仍只经 gateway → installer-core `InstallerMultiPartitionTransaction` →
 * `BlockDeviceTransaction` 写出（红线 4）。
 *
 * 执行规则：
 * - `begin` 使用本次请求和当前设备观测，不依赖此前的只读预检；
 * - 跨阶段状态不符 → `SWITCH_STATE_INVALID`；真实并发 writer → 明确错误码；
 * - `switch.json` 损坏 → `RECOVERY_JOURNAL_CORRUPT`（保留现场，不自动删除）。
 */
class ModeSwitchDeviceDelegate(
    private val controlRoot: File,
    private val workRoot: File,
    private val authority: SwitchAuthority,
    private val journalFactory: SwitchJournalFactory,
    private val baselines: BaselineStore,
    private val gateway: InstallerGateway,
    private val modeFacts: DeviceModeFactsPort,
    private val pendingGuard: DevicePendingGuard,
    private val lock: DeviceLockPort,
    private val deviceFacts: () -> dev.maplesu.manager.workmode.switch.SwitchDeviceFacts,
    /** W08b：S4 配置交换在 root 服务进程内执行（真实 roots）；null = 仅分区（兼容旧测试）。 */
    private val moveStage: SwitchMoveStage? = null,
    private val bootIds: BootIdSource = ProcBootIdSource,
    private val clock: Clock = WallClock,
    /**
     * P0-1 第 3 项（CTA 放宽，施工图 §11.4-C）：本构建的 App 侧是否已接线"基线自动获取"。
     *
     * 默认值 = [BaselineProvisioning.APP_WIRED]（同一 APK 的构建期不变量，理由见其 KDoc）。
     * 传 `false` 可退回旧行为（缺基线在 preflight 直接阻断）——既有的
     * `preflightRejectsUnknownPlanFieldsAndMissingBaseline` 用显式 `false` 钉住这条旧语义。
     *
     * 为 true 时只把 `BaselinePreflightRepair` 白名单里的**基线专属码**降级成
     * [DevicePreflightResult.warning]（不是放行写入）：
     * - 理由：`KSU→LITE`(Normal→Ultra) 必定走 SERVICE 客户端，缺基线时这里返回
     *   `BASELINE_MISSING` ⇒ facade 判 Blocked ⇒ UI 按钮变灰 ⇒ `startSwitch` 里的
     *   自动获取**永远跑不到**（与已修的 S-1 完全同型）；
     * - 实际 baseline 在 [snapshot] 读取前验证；[begin] 只使用本次请求与当前设备观测。
     *   镜像完整性和写入/回读仍由后续实际执行链处理。
     */
    private val baselineProvisionable: Boolean = BaselineProvisioning.APP_WIRED,
    /** The same file-only carrier recovery used by the direct root client. */
    private val sourceRecovery: ((WorkMode, String, File, File, Boolean) -> CandidateResult)? = null,
) {

    // ------------------------------------------------------------------ 12 方法

    fun preflight(switchId: String, planFields: Map<String, String>): DevicePreflightResult {
        return try {
            preflightInternal(switchId, planFields)
        } catch (error: DeviceSwitchError) {
            DevicePreflightResult(false, error.code, error.detail)
        } catch (error: IOException) {
            DevicePreflightResult(false, ModeSwitchCodes.ROOT_UNAVAILABLE, error.message.orEmpty())
        }
    }

    private fun preflightInternal(switchId: String, planFields: Map<String, String>): DevicePreflightResult {
        val parsed = requestOf(switchId, planFields)
        requireCurrentMode(parsed)
        try {
            pendingGuard.assertNoPendingInstall()
        } catch (error: IOException) {
            return DevicePreflightResult(false, error.message ?: "INSTALLER_TRANSACTION_BUSY", "active writer")
        }
        val service = runCatching { gateway.preflight(parsed) }.getOrElse { error ->
            return DevicePreflightResult(false, ModeSwitchCodes.ROOT_UNAVAILABLE, error.message.orEmpty())
        }
        if (!service.ok) return DevicePreflightResult(false, service.code, service.detail)
        val device = service.device ?: deviceFacts()
        val inventory = service.partitionInventory
        val request = parsed.resolve(inventory?.present, device.kernelRelease)
        val layoutFailure = runCatching {
            if (inventory != null && "boot" !in inventory.present) {
                throw DeviceSwitchError(ModeSwitchCodes.LAYOUT_UNSUPPORTED, "boot-missing")
            }
            if (inventory != null) {
                val missing = request.plannedPartitions(inventory.present).filter { it !in inventory.present }
                if (missing.isNotEmpty()) {
                    throw DeviceSwitchError(ModeSwitchCodes.LAYOUT_UNSUPPORTED, missing.joinToString(","))
                }
            }
        }.exceptionOrNull()
        if (layoutFailure != null) {
            return DevicePreflightResult(false, ModeSwitchCodes.LAYOUT_UNSUPPORTED, layoutFailure.message.orEmpty())
        }
        // SELinux 事实（2026-11-01 用户指令：**只记录，不阻断**）。
        // 只有「越狱模式」可拒绝刷写；SELinux 与分区字节是否安全无关 ⇒ 不再产出
        // `SELINUX_NOT_ENFORCING` 失败，事实进 [DevicePreflightResult.advisory]，
        // preflight 继续（`ok=true`、`code=""`）。自动恢复仍只在
        // `ModeSwitchFacade.startSwitch` 入口做一次（setenforce + 回读 + 活性）。
        val selinuxAdvisory = ModeSwitchPreflight.selinuxCheck(device.selinux).advisory
        if (device.slot !in ModeSwitchPreflight.VALID_SLOTS) {
            return DevicePreflightResult(false, ModeSwitchCodes.SLOT_INVALID, device.slot)
        }
        if (false && request.resolvedTarget == "init_boot" &&
            !ModeSwitchKmi.supported(device.kernelRelease)) {
            return DevicePreflightResult(false, ModeSwitchCodes.KMI_UNSUPPORTED, device.kernelRelease)
        }
        if (usesSourceRecovery(request)) {
            return DevicePreflightResult(true, advisory = selinuxAdvisory)
        }
        val baseline = baselines.verify(request.baselineId)
        if (baseline.state != BaselineState.PRESENT_OK) {
            val blocker = baseline.blocker ?: BaselineErrors.BASELINE_MISSING
            // P0-1 第 3 项：只在"App 侧已接线基线自动获取" + "这是基线专属码" + "**lite 探测**
            // 语义（baselineId 为空 = 页面/CTA 还没拿到 id）"时降级为非阻断 note。
            //
            // 为什么必须加 baselineId 为空这一条：`startSwitch` 自动获取基线之后会用**真实 id**
            // 重新 preflight，那一次必须是**严格模式**——id 非空就说明"基线应该存在"，
            // 不存在就是不匹配/被删，必须显式失败（绝不降级掩盖）。这样"严格模式"不需要
            // 改 AIDL/ModeSwitchClient，而是由请求内容本身表达。
            // 其它任何码（ROOT_UNAVAILABLE/PRE_IMAGE_*/PENDING/SLOT/KMI/SPACE…）一律不降级。
            // SELinux 已不再是码（2026-11-01：只记 advisory，见上），不参与这里的判定。
            if (baselineProvisionable &&
                request.baselineId.isEmpty() &&
                BaselinePreflightRepair.isRepairable(blocker)
            ) {
                return DevicePreflightResult(true, warning = blocker, advisory = selinuxAdvisory)
            }
            return DevicePreflightResult(
                false, blocker,
                baseline.mismatch.joinToString(","),
            )
        }
        return DevicePreflightResult(true, advisory = selinuxAdvisory)
    }

    private fun requireCurrentMode(request: ModeSwitchRequest) {
        val current = modeFacts.read().currentMode
        if (current == null) throw DeviceSwitchError(ModeSwitchCodes.STATE_UNDETERMINED, "mode undetermined")
        if (current == request.toMode)
            throw DeviceSwitchError(ModeSwitchCodes.SAME_MODE, "already ${request.toMode}")
        if (current != request.fromMode)
            throw DeviceSwitchError(ModeSwitchCodes.STATE_UNDETERMINED, "current=$current from=${request.fromMode}")
    }

    fun begin(switchId: String, planFields: Map<String, String>,
              superuserJson: File, settingsMirrorJson: File): DeviceSwitchResult {
        val parsed = try { requestOf(switchId, planFields) } catch (error: DeviceSwitchError) {
            return fail(error.code, error.detail)
        }
        return failClosed {
            if (!superuserJson.isFile || !settingsMirrorJson.isFile) {
                return@failClosed fail(ModeSwitchCodes.PREPARE_EXPORT_FAILED, "mirror files missing")
            }
            withLockSafe {
                pendingGuard.assertNoPendingInstall()
                requireCurrentMode(parsed)
                val device = deviceFacts()
                if (device.slot !in ModeSwitchPreflight.VALID_SLOTS)
                    throw DeviceSwitchError(ModeSwitchCodes.SLOT_INVALID, device.slot)
                val available = try {
                    gateway.observePartitions(parsed, listOf("boot", "init_boot")).map { it.name }.toSet()
                } catch (error: Exception) {
                    throw DeviceSwitchError(ModeSwitchCodes.LAYOUT_UNSUPPORTED, error.message.orEmpty())
                }
                if ("boot" !in available)
                    throw DeviceSwitchError(ModeSwitchCodes.LAYOUT_UNSUPPORTED, "boot-missing")
                val target = ModeSwitchPreflight.resolveTarget(parsed.toMode, parsed.targetPartition, available,
                    ModeSwitchKmi.parse(device.kernelRelease))
                    ?: throw DeviceSwitchError(ModeSwitchCodes.LAYOUT_UNSUPPORTED, "target ${parsed.targetPartition.orEmpty()}")
                val request = parsed.copy(targetPartition = target)
                val missing = request.plannedPartitions(available).filter { it !in available }
                if (missing.isNotEmpty())
                    throw DeviceSwitchError(ModeSwitchCodes.LAYOUT_UNSUPPORTED, missing.joinToString(","))
                val directory = File(workRoot, switchId)
                if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                    throw DeviceSwitchError(ModeSwitchCodes.CONTROL_DIR_INVALID, "work dir")
                }
                val superuserCopy = File(directory, "superuser-mirror.json")
                val settingsCopy = File(directory, "settings-mirror.json")
                superuserJson.copyTo(superuserCopy, overwrite = true)
                settingsMirrorJson.copyTo(settingsCopy, overwrite = true)
                val now = clock.nowMillis()
                val bootId = bootIds.read()
                val record = SwitchRecord(
                    switchId = switchId,
                    state = SwitchStateNames.BEGIN,
                    stage = "S1",
                    step = SwitchSteps.BEGIN,
                    fromMode = request.fromMode.name,
                    toMode = request.toMode.name,
                    baselineId = request.baselineId,
                    targetPartition = request.resolvedTarget,
                    sourcePartition = request.sourcePartition,
                    startBootId = bootId,
                    currentBootId = bootId,
                    createdAtUptimeMs = now,
                    updatedAtUptimeMs = now,
                    device = SwitchRecordDevice(
                        device.slot, device.firmware, device.kernelRelease,
                        ModeSwitchKmi.parse(device.kernelRelease).orEmpty(), device.selinux,
                    ),
                    artifacts = mapOf(
                        "superuserMirror" to superuserCopy.absolutePath,
                        "settingsMirror" to settingsCopy.absolutePath,
                        "journal" to File(directory, "journal.jsonl").absolutePath,
                    ),
                )
                authority.write(record)
                ok(statusOf(record))
            }
        }
    }

    fun snapshot(switchId: String): DeviceSwitchResult = failClosed {
        withLockSafe {
            val record = requireRecord(switchId)
            requireState(record, SwitchStateNames.BEGIN, SwitchStateNames.SNAPSHOT)
            val request = requestOf(record)
            val observed = runCatching { gateway.snapshot(request) }.getOrElse { error ->
                throw DeviceSwitchError(ModeSwitchCodes.SNAPSHOT_FAILED, error.message.orEmpty())
            }
            if (!observed.ok) throw DeviceSwitchError(
                observed.code.ifEmpty { ModeSwitchCodes.SNAPSHOT_FAILED }, observed.detail,
            )
            val status = observed.status ?: throw DeviceSwitchError(ModeSwitchCodes.SNAPSHOT_FAILED, "no status")
            if (status.partitions.isEmpty()) throw DeviceSwitchError(
                ModeSwitchCodes.SNAPSHOT_FAILED, "no partitions",
            )
            val baseline = if (usesSourceRecovery(request)) null else baselines.verify(request.baselineId)
            if (baseline != null && baseline.state != BaselineState.PRESENT_OK) {
                throw DeviceSwitchError(baseline.blocker ?: BaselineErrors.BASELINE_MISSING,
                    baseline.mismatch.joinToString(","))
            }
            val directory = File(workRoot, switchId)
            val artifacts = record.artifacts.toMutableMap().apply {
                remove("baseMode")
                for (name in listOf("boot", "init_boot")) {
                    remove("pre-$name")
                    remove("base-$name")
                    remove("baseSha256-$name")
                    remove("baseSource-$name")
                }
            }
            val rows = mutableListOf<SwitchRecordPartition>()
            val preImages = linkedMapOf<String, ExportedImage>()
            val planned = request.plannedPartitions(status.partitions.map { it.name }.toSet())
            val missing = planned.filter { name -> status.partitions.none { it.name == name } }
            if (missing.isNotEmpty()) {
                throw DeviceSwitchError(ModeSwitchCodes.LAYOUT_UNSUPPORTED, missing.joinToString(","))
            }
            for (partition in status.partitions.filter { it.name in planned }) {
                val preFile = export(request, "PRE", partition.name, File(directory, "pre-${partition.name}.img"))
                verifyImage(preFile, partition.size, partition.preSha256,
                    ModeSwitchCodes.PRE_IMAGE_INVALID, ModeSwitchCodes.PRE_IMAGE_CHANGED, partition.name)
                artifacts["pre-${partition.name}"] = preFile.file.absolutePath
                preImages[partition.name] = preFile
            }
            if (baseline == null) artifacts["baseMode"] = "SOURCE_RECOVERY"
            for (partition in status.partitions.filter { it.name in planned }) {
                val baselineSha = ModeSwitchPlanBuilder.baselineSha(baseline, partition.name)
                if (baselineSha != null) {
                    val baseFile = export(request, "BASE", partition.name,
                        File(directory, "base-${partition.name}.img"))
                    verifyImage(baseFile, partition.size, baselineSha,
                        BaselineErrors.BASELINE_CORRUPT, BaselineErrors.BASELINE_CORRUPT, partition.name)
                    artifacts["base-${partition.name}"] = baseFile.file.absolutePath
                } else if (baseline == null) {
                    val pre = preImages.getValue(partition.name)
                    val knownSource = !request.sourcePartition.isNullOrEmpty()
                    val isSource = if (knownSource) partition.name == request.sourcePartition
                        else partition.name in ModeSwitchPreflight.allowedTargets(request.fromMode)
                    val destination = File(directory, "base-${partition.name}.img")
                    val base = if (isSource) {
                        recoveredBase(request, partition.name, pre, destination, knownSource, artifacts)
                    } else {
                        if (!BaselineAtomicFiles.copyVerified(pre.file, destination, pre.sha256))
                            throw DeviceSwitchError(ModeSwitchCodes.PRE_IMAGE_INVALID,
                                "base copy failed: ${partition.name}")
                        ExportedImage(destination, destination.length(), ModeSwitchHashes.sha256(destination))
                            .also {
                                verifyImage(it, partition.size, pre.sha256,
                                    ModeSwitchCodes.PRE_IMAGE_INVALID, ModeSwitchCodes.PRE_IMAGE_CHANGED, partition.name)
                                artifacts["baseSource-${partition.name}"] = "PRE"
                            }
                    }
                    artifacts["base-${partition.name}"] = base.file.absolutePath
                    artifacts["baseSha256-${partition.name}"] = base.sha256
                }
                rows += SwitchRecordPartition(
                    name = partition.name,
                    role = if (partition.name == request.resolvedTarget) {
                        "WRITE_CANDIDATE"
                    } else {
                        "RESTORE_BASELINE"
                    },
                    size = partition.size,
                    preSha256 = partition.preSha256,
                    targetSha256 = partition.targetSha256,
                    tailSha256 = partition.tailSha256,
                    state = "PENDING",
                    observedSha256 = partition.observedSha256,
                )
            }
            val updated = record.copy(
                state = SwitchStateNames.SNAPSHOT,
                stage = "S2",
                step = SwitchSteps.SNAPSHOT,
                updatedAtUptimeMs = clock.nowMillis(),
                partitions = rows,
                artifacts = artifacts,
            )
            authority.write(updated)
            ok(statusOf(updated))
        }
    }

    /** 服务端把 PRE/BASE/CANDIDATE 只读副本作为 PFD 返回前的文件解析（防目录穿越）。 */
    fun imageFile(switchId: String, kind: String, partition: String): File {
        if (partition !in setOf("boot", "init_boot")) {
            throw DeviceSwitchError("SWITCH_PARTITION_FORBIDDEN", partition)
        }
        val record = requireRecord(switchId)
        val key = when (kind) {
            "PRE" -> "pre-$partition"
            "BASE" -> "base-$partition"
            "CANDIDATE" -> "candidate-$partition"
            else -> throw DeviceSwitchError(ModeSwitchCodes.PLAN_INVALID, "kind $kind")
        }
        if (record.partitions.none { it.name == partition }) {
            throw DeviceSwitchError(ModeSwitchCodes.PRE_IMAGE_INVALID, partition)
        }
        val path = record.artifacts[key] ?: throw DeviceSwitchError(ModeSwitchCodes.PRE_IMAGE_INVALID, key)
        val file = File(path).canonicalFile
        val root = workRoot.canonicalFile
        if (!file.path.startsWith(root.path + File.separator)) {
            throw DeviceSwitchError(ModeSwitchCodes.PRE_IMAGE_INVALID, "outside work dir")
        }
        if (!file.isFile) throw DeviceSwitchError(ModeSwitchCodes.PRE_IMAGE_INVALID, key)
        if (kind == "BASE" && record.artifacts["baseMode"] == "SOURCE_RECOVERY") {
            val expected = record.artifacts["baseSha256-$partition"]
            if (!ModeSwitchHashes.isHash(expected.orEmpty()) ||
                !ModeSwitchHashes.sha256(file).equals(expected, ignoreCase = true))
                throw DeviceSwitchError("SOURCE_RECOVERY_OUTPUT_CHANGED", partition)
        }
        return file
    }

    fun submitCandidate(switchId: String, partition: String, candidateFile: File,
                        candidateSha256: String): DeviceSwitchResult = failClosed {
        if (partition !in setOf("boot", "init_boot")) {
            return@failClosed fail("SWITCH_PARTITION_FORBIDDEN", partition)
        }
        withLockSafe {
            val record = requireRecord(switchId)
            requireState(record, SwitchStateNames.SNAPSHOT, SwitchStateNames.PRODUCE, SwitchStateNames.COMMIT)
            if (partition != requestOf(record).resolvedTarget) {
                throw DeviceSwitchError(ModeSwitchCodes.PLAN_INVALID, "candidate partition")
            }
            if (record.state == SwitchStateNames.COMMIT) {
                // 幂等重提：同 sha 视为成功。
                val step = record.partitions.firstOrNull { it.name == partition }
                if (step?.targetSha256.equals(candidateSha256, ignoreCase = true)) {
                    return@withLockSafe ok(statusOf(record))
                }
                throw DeviceSwitchError("PRODUCE_CANDIDATE_CONFLICT", partition)
            }
            val row = record.partitions.firstOrNull { it.name == partition }
                ?: throw DeviceSwitchError(ModeSwitchCodes.PLAN_INVALID, "unknown partition $partition")
            if (!candidateFile.isFile || candidateFile.length() != row.size) {
                throw DeviceSwitchError(ModeSwitchCodes.PRODUCE_OUTPUT_SIZE_INVALID, partition)
            }
            if (!ModeSwitchHashes.isHash(candidateSha256)) {
                throw DeviceSwitchError(ModeSwitchCodes.PRODUCE_CANDIDATE_CHANGED, "hash")
            }
            val actual = ModeSwitchHashes.sha256(candidateFile)
            if (!actual.equals(candidateSha256, ignoreCase = true)) {
                throw DeviceSwitchError(ModeSwitchCodes.PRODUCE_CANDIDATE_CHANGED, partition)
            }
            val directory = File(workRoot, switchId)
            val stored = File(directory, "candidate-$partition.img")
            if (stored.canonicalFile != candidateFile.canonicalFile) {
                if (!BaselineAtomicFiles.copyVerified(candidateFile, stored, actual))
                    throw DeviceSwitchError(ModeSwitchCodes.PRODUCE_FAILED, "candidate copy failed: $partition")
            }
            val artifacts = record.artifacts + mapOf("candidate-$partition" to stored.absolutePath)
            val updatedRows = record.partitions.map {
                if (it.name == partition) {
                    it.copy(targetSha256 = actual, tailSha256 = ModeSwitchHashes.tailSha256(stored))
                } else {
                    it
                }
            }
            val producing = record.copy(
                state = SwitchStateNames.PRODUCE,
                stage = "S3",
                step = SwitchSteps.PRODUCE,
                updatedAtUptimeMs = clock.nowMillis(),
                partitions = updatedRows,
                artifacts = artifacts,
            )
            val request = requestOf(producing)
            val recoveredSources = producing.artifacts["baseMode"] == "SOURCE_RECOVERY"
            val baseline = if (recoveredSources) null else baselines.verify(request.baselineId)
            val plan = try {
                ModeSwitchPlanBuilder.build(
                    request = request,
                    startBootId = producing.startBootId,
                    device = deviceFacts(),
                    baseline = baseline,
                    partitions = updatedRows.map {
                        SwitchPartitionStatus(it.name, it.role, it.size, it.preSha256, it.targetSha256,
                            it.tailSha256, it.observedSha256, it.state)
                    },
                    preImages = imagesOf(producing, "pre-"),
                    baseImages = imagesOf(producing, "base-"),
                    candidate = ModeSwitchPlanBuilder.CandidateImage(partition, stored, actual),
                    recoveredBaseShas = if (recoveredSources) updatedRows.associate {
                        it.name to producing.artifacts["baseSha256-${it.name}"].orEmpty()
                    } else emptyMap(),
                )
            } catch (failure: SwitchFailure) {
                throw DeviceSwitchError(failure.code, failure.detail)
            }
            // The shared builder decides whether any partition has an effective write.
            authority.write(producing)
            val prepared = runCatching { gateway.preparePlan(request, plan.flatFields()) }.getOrElse { error ->
                throw DeviceSwitchError(ModeSwitchCodes.PLAN_INVALID, error.message.orEmpty())
            }
            if (!prepared.ok) throw DeviceSwitchError(
                prepared.code.ifEmpty { ModeSwitchCodes.PLAN_INVALID }, prepared.detail,
            )
            val committed = producing.copy(
                state = SwitchStateNames.COMMIT,
                stage = "S4",
                step = SwitchSteps.QUIESCE,
                planSha256 = plan.planSha256,
                artifacts = producing.artifacts.filterKeys { !it.startsWith(SwitchRecord.PREPARED_PLAN_PREFIX) } +
                    plan.flatFields().mapKeys { (key, _) ->
                        SwitchRecord.PREPARED_PLAN_PREFIX + key
                    },
                updatedAtUptimeMs = clock.nowMillis(),
            )
            authority.write(committed)
            journal(committed).append(
                switchId, plan.planSha256, "S3", SwitchSteps.PRODUCE, SwitchOp.PREPARED,
            )
            ok(statusOf(committed))
        }
    }

    fun commit(switchId: String): DeviceSwitchResult = failClosed {
        withLockSafe {
            val record = requireRecord(switchId)
            requireState(record, SwitchStateNames.COMMIT)
            pendingGuard.assertNoPendingInstall()
            val request = requestOf(record)

            // 4b：服务进程（UID 0）执行出仓；App 侧不再需要写 /data/adb。
            val movePlan = planMoves(record)
            if (movePlan != null) {
                if (!movePlan.ok) {
                    return@withLockSafe fail("MOVE_PLAN_INVALID",
                        movePlan.validationErrors.joinToString(","), record)
                }
                journal(record).append(
                    switchId, record.planSha256, "S4", SwitchSteps.MOVE_OUT, SwitchOp.MOVE_INTENT,
                )
                val movedOut = runCatching { moveStage!!.moveOut(movePlan, moveJournal(record)) }
                    .getOrElse { error -> MoveStageResult(false, "MOVE_OUT_FAILED", error.message.orEmpty()) }
                journal(record).append(
                    switchId, record.planSha256, "S4", SwitchSteps.MOVE_OUT,
                    if (movedOut.ok) SwitchOp.MOVE_DONE else SwitchOp.FAIL, code = movedOut.code,
                )
                if (!movedOut.ok) {
                    return@withLockSafe fail(movedOut.code, movedOut.detail, record)
                }
            }

            val outcome = runCatching { gateway.commit(request) }.getOrElse { error ->
                GatewayOutcome(false, "", ModeSwitchCodes.WRITE_FAILED, error.message.orEmpty())
            }
            if (!outcome.ok) {
                // 分区已由服务端回滚（或不可回滚）：配置按逆序撤销；失败升级 RECOVERY_REQUIRED。
                return@withLockSafe rollbackOutcome(
                    record, outcome.code, outcome.detail,
                    rolledBack = outcome.state == GatewayState.ROLLED_BACK,
                    movePlan = movePlan,
                    successOnRollback = false,
                )
            }
            // 4e 入仓 + 4f 物化；任一失败 → 先回滚分区，再回滚配置，绝不静默成功。
            if (movePlan != null) {
                journal(record).append(
                    switchId, record.planSha256, "S4", SwitchSteps.MOVE_IN, SwitchOp.MOVE_INTENT,
                )
                val movedIn = runCatching { moveStage!!.moveIn(movePlan, moveJournal(record)) }
                    .getOrElse { error -> MoveStageResult(false, "MOVE_IN_FAILED", error.message.orEmpty()) }
                journal(record).append(
                    switchId, record.planSha256, "S4", SwitchSteps.MOVE_IN,
                    if (movedIn.ok) SwitchOp.MOVE_DONE else SwitchOp.FAIL, code = movedIn.code,
                )
                if (!movedIn.ok) {
                    val partitionRollback = runCatching { gateway.rollback(request) }.getOrNull()
                    return@withLockSafe rollbackOutcome(record, movedIn.code, movedIn.detail,
                        rolledBack = partitionRollback?.state == GatewayState.ROLLED_BACK,
                        movePlan = movePlan, successOnRollback = false)
                }
                journal(record).append(
                    switchId, record.planSha256, "S4", SwitchSteps.MATERIALIZE,
                    SwitchOp.MATERIALIZE_INTENT,
                )
                val materialized = runCatching { moveStage!!.materialize(movePlan, moveJournal(record)) }
                    .getOrElse { error -> MoveStageResult(false, "MOVE_VERIFY_FAILED", error.message.orEmpty()) }
                journal(record).append(
                    switchId, record.planSha256, "S4", SwitchSteps.MATERIALIZE,
                    if (materialized.ok) SwitchOp.MATERIALIZE_DONE else SwitchOp.FAIL,
                    code = materialized.code,
                )
                if (!materialized.ok) {
                    val partitionRollback = runCatching { gateway.rollback(request) }.getOrNull()
                    return@withLockSafe rollbackOutcome(record, materialized.code, materialized.detail,
                        rolledBack = partitionRollback?.state == GatewayState.ROLLED_BACK,
                        movePlan = movePlan, successOnRollback = false)
                }
            }
            val next = record.copy(
                state = SwitchStateNames.REBOOT_REQUIRED,
                stage = "S4",
                step = SwitchSteps.COMMIT_PENDING,
                updatedAtUptimeMs = clock.nowMillis(),
                // Preserve the gateway's actual commit readback (including idempotent skips).
                // A gateway without digests still reports state only; never substitute targetSha.
                partitions = outcome.status?.partitions?.takeIf { it.isNotEmpty() }?.map {
                    SwitchRecordPartition(it.name, it.role, it.size, it.preSha256, it.targetSha256,
                        it.tailSha256, it.state, it.observedSha256)
                } ?: record.partitions.map { it.copy(state = "DONE") },
                moves = if (movePlan != null) {
                    movePlan.plan.outbound.map {
                        SwitchRecordMove(it.livePath, it.kind.name, it.expectedShape, it.owner.suffix, "DONE")
                    }
                } else {
                    record.moves
                },
                materialize = SwitchRecordMaterialize(if (movePlan != null) "DONE" else "SKIPPED", ""),
                error = SwitchRecordError(),
            )
            authority.write(next)
            journal(next).append(
                switchId, next.planSha256, "S4", SwitchSteps.COMMIT_PENDING, SwitchOp.REBOOT_PENDING,
            )
            ok(statusOf(next))
        }
    }

    fun rollback(switchId: String): DeviceSwitchResult = failClosed {
        withLockSafe {
            val record = requireRecord(switchId)
            requireState(
                record, SwitchStateNames.COMMIT, SwitchStateNames.REBOOT_REQUIRED,
                SwitchStateNames.VERIFYING, SwitchStateNames.RECOVERY_REQUIRED,
                SwitchStateNames.ROLLING_BACK,
            )
            val request = requestOf(record)
            val outcome = runCatching { gateway.rollback(request) }.getOrElse { error ->
                GatewayOutcome(false, GatewayState.RECOVERY_REQUIRED,
                    ModeSwitchCodes.ROLLBACK_FAILED, error.message.orEmpty())
            }
            rollbackOutcome(record, outcome.code, outcome.detail,
                rolledBack = outcome.state == GatewayState.ROLLED_BACK,
                movePlan = planMoves(record))
        }
    }

    fun abort(switchId: String): DeviceSwitchResult = failClosed {
        withLockSafe {
            val record = requireRecord(switchId)
            requireState(record, SwitchStateNames.BEGIN, SwitchStateNames.SNAPSHOT, SwitchStateNames.PRODUCE)
            val request = requestOf(record)
            runCatching { gateway.abort(request) }
            val cancelled = record.copy(
                state = SwitchStateNames.CANCELLED,
                updatedAtUptimeMs = clock.nowMillis(),
                error = SwitchRecordError(ModeSwitchCodes.ABORTED, record.stage, record.step, "", 0),
            )
            authority.write(cancelled)
            ok(statusOf(cancelled))
        }
    }

    fun status(switchId: String): DeviceSwitchStatus? {
        val record = when (val read = authority.read()) {
            is dev.maplesu.manager.workmode.switch.AuthorityRead.Corrupt -> throw DeviceSwitchError(
                ModeSwitchCodes.RECOVERY_JOURNAL_CORRUPT, "${read.code}:${read.detail}",
            )
            // 02 §3.3：switch.json 丢失时用 journal 重建"最近已知状态"（只读呈现，不据此写分区）。
            is dev.maplesu.manager.workmode.switch.AuthorityRead.Missing -> return journalHead(switchId)
            is dev.maplesu.manager.workmode.switch.AuthorityRead.Ok -> read.record
        }
        if (switchId.isNotEmpty() && record.switchId != switchId) return null
        return statusOf(record)
    }

    /** 读出 `boot/work/<id>/journal.jsonl` 的 pending 头部；无 journal 返回 null。 */
    private fun journalHead(switchId: String): DeviceSwitchStatus? {
        val directories = if (switchId.isNotEmpty()) {
            listOf(File(workRoot, switchId))
        } else {
            workRoot.listFiles { file -> file.isDirectory }?.toList().orEmpty()
        }
        for (directory in directories) {
            val journalFile = File(directory, "journal.jsonl")
            if (!journalFile.isFile) continue
            val replay = runCatching {
                dev.maplesu.manager.workmode.switch.FileSwitchJournal(
                    journalFile, clock, bootIds,
                ).replay(directory.name)
            }.getOrNull() as? JournalReplay.Known ?: continue
            if (!SwitchStateNames.isPending(replay.state)) continue
            return DeviceSwitchStatus(
                switchId = directory.name,
                state = replay.state,
                stage = replay.stage,
                step = replay.step,
                fromMode = "",
                toMode = "",
                error = if (replay.recoveryRequired) ModeSwitchCodes.RECOVERY_REQUIRED else "",
                errorDetail = "journal-head",
                planSha256 = "",
                rebootRequired = replay.rebootRequested,
                // 无 switch.json 时没有 startBootId，不得假装观测到重启。
                bootObserved = false,
                operationActive = false,
                canRollback = replay.state in ModeSwitchStatusProjector.ROLLBACK_ALLOWED,
                canResumeReboot = replay.rebootRequested,
                verifyFailures = 0,
                partitions = emptyList(),
                device = emptyMap(),
            )
        }
        return null
    }

    fun verify(switchId: String): DeviceSwitchResult = failClosed {
        val record = requireRecord(switchId)
        requireState(record, SwitchStateNames.REBOOT_REQUIRED, SwitchStateNames.VERIFYING)
        val currentBoot = bootIds.read()
        val bootChanged = currentBoot.isNotEmpty() && currentBoot != record.startBootId
        if (!bootChanged) {
            throw DeviceSwitchError(ModeSwitchCodes.VERIFY_BOOT_NOT_OBSERVED, "boot id unchanged")
        }
        val request = requestOf(record)
        val verifying = record.copy(
            state = SwitchStateNames.VERIFYING,
            stage = "S6",
            step = SwitchSteps.VERIFY_PARTITIONS,
            currentBootId = currentBoot,
            rebootObservedBootId = currentBoot,
            updatedAtUptimeMs = clock.nowMillis(),
        )
        authority.write(verifying)
        val outcome = runCatching { gateway.verify(request) }.getOrElse { error ->
            GatewayOutcome(false, "", ModeSwitchCodes.WRITE_IO_FAILED, error.message.orEmpty())
        }
        if (!outcome.ok) {
            val code = outcome.code.ifEmpty { ModeSwitchCodes.VERIFY_PARTITION_MISMATCH }
            val failed = verifying.copy(
                state = SwitchStateNames.RECOVERY_REQUIRED,
                error = SwitchRecordError(code, "S6", SwitchSteps.VERIFY_PARTITIONS,
                    outcome.detail, clock.nowMillis()),
            )
            authority.write(failed)
            journal(failed).append(
                switchId, failed.planSha256, "S6", SwitchSteps.VERIFY_PARTITIONS,
                SwitchOp.RECOVERY_REQUIRED, code = code,
            )
            return@failClosed fail(code, outcome.detail, failed)
        }
        val verified = verifying.copy(
            state = SwitchStateNames.VERIFIED,
            stage = "S6",
            step = SwitchSteps.VERIFY_SUPERUSER,
            updatedAtUptimeMs = clock.nowMillis(),
            error = SwitchRecordError(),
        )
        authority.write(verified)
        journal(verified).append(
            switchId, verified.planSha256, "S6", SwitchSteps.VERIFY_PARTITIONS, SwitchOp.VERIFIED,
        )
        ok(statusOf(verified))
    }

    /** 仅落 pending 标记；真正重启由 UI 决定（Lead 要求，服务不自行 reboot）。 */
    fun markRebootRequested(switchId: String): Boolean {
        return try {
            val record = requireRecord(switchId)
            requireState(record, SwitchStateNames.REBOOT_REQUIRED)
            val next = record.copy(
                rebootRequestedAtUptimeMs = clock.nowMillis(),
                attempts = record.attempts.copy(rebootRequests = record.attempts.rebootRequests + 1),
                updatedAtUptimeMs = clock.nowMillis(),
            )
            authority.write(next)
            true
        } catch (_: DeviceSwitchError) {
            false
        } catch (_: IOException) {
            false
        }
    }

    fun recoveryLog(switchId: String, destination: File): File {
        val status = status(switchId) ?: throw DeviceSwitchError(ModeSwitchCodes.PLAN_INVALID, "no record")
        val record = requireRecord(switchId)
        destination.parentFile?.mkdirs()
        val journalReplay = runCatching { journal(record).read() }.getOrElse { null }
        val lines = buildList {
            add("switchId=${status.switchId}")
            add("state=${status.state} stage=${status.stage} step=${status.step}")
            add("from=${status.fromMode} to=${status.toMode} planSha=${status.planSha256}")
            add("error=${status.error} detail=${status.errorDetail}")
            status.partitions.forEach { row ->
                add("partition ${row.name} size=${row.size} role=${row.role} " +
                    "pre=${row.preSha256} target=${row.targetSha256} observed=${row.observedSha256}")
            }
            record.artifacts.forEach { (key, value) -> add("image $key=$value") }
            add("device=${status.device}")
            if (journalReplay is dev.maplesu.manager.workmode.switch.JournalRead.Ok) {
                journalReplay.lines.takeLast(200).forEach { line ->
                    add("journal seq=${line.seq} op=${line.op} step=${line.step} code=${line.code}")
                }
            }
            add("本产品在该状态下不自动重启、不自动重试；请按 pre 镜像与摘要人工恢复。")
        }
        destination.writeText(lines.joinToString("\n"))
        return destination
    }

    // ------------------------------------------------------------------ 实现工具

    private fun usesSourceRecovery(request: ModeSwitchRequest): Boolean =
        request.baselineId.isEmpty() && sourceRecovery != null

    private fun recoveredBase(request: ModeSwitchRequest, partition: String, pre: ExportedImage,
                              destination: File, sourceKnown: Boolean,
                              artifacts: MutableMap<String, String>): ExportedImage {
        val result = sourceRecovery!!.invoke(request.fromMode, partition, pre.file,
            File(destination.parentFile, "source-recovery-$partition"), sourceKnown)
        if (!result.ok) throw DeviceSwitchError(result.code.ifEmpty { "SOURCE_RECOVERY_FAILED" }, result.detail)
        val image = requireNotNull(result.image)
        if (result.size != pre.size || !image.isFile || image.length() != pre.size)
            throw DeviceSwitchError(ModeSwitchCodes.PRODUCE_OUTPUT_SIZE_INVALID, partition)
        if (!ModeSwitchHashes.isHash(result.sha256) ||
            !ModeSwitchHashes.sha256(image).equals(result.sha256, ignoreCase = true))
            throw DeviceSwitchError("SOURCE_RECOVERY_OUTPUT_CHANGED", partition)
        if (!ModeSwitchHashes.sha256(pre.file).equals(pre.sha256, ignoreCase = true))
            throw DeviceSwitchError(ModeSwitchCodes.PRE_IMAGE_CHANGED, partition)
        if (image.canonicalFile != destination.canonicalFile &&
            !BaselineAtomicFiles.copyVerified(image, destination, result.sha256))
            throw DeviceSwitchError("SOURCE_RECOVERY_FAILED", "base copy failed: $partition")
        val copied = ExportedImage(destination, destination.length(), ModeSwitchHashes.sha256(destination))
        verifyImage(copied, pre.size, result.sha256,
            ModeSwitchCodes.PRE_IMAGE_INVALID, "SOURCE_RECOVERY_OUTPUT_CHANGED", partition)
        artifacts["baseSource-$partition"] = result.structureEvidence
        return copied
    }

    /** 12 个结果型方法统一 fail-closed：异常转带错误码的结果，绝不穿出 Binder。 */
    private inline fun failClosed(block: () -> DeviceSwitchResult): DeviceSwitchResult = try {
        block()
    } catch (error: DeviceSwitchError) {
        DeviceSwitchResult(false, error.code, error.detail, runCatching { status("") }.getOrNull())
    } catch (error: IOException) {
        DeviceSwitchResult(false, error.message ?: ModeSwitchCodes.ROOT_UNAVAILABLE, "", null)
    }

    private fun withLockSafe(action: () -> DeviceSwitchResult): DeviceSwitchResult = try {
        lock.withLock(action)
    } catch (error: DeviceSwitchError) {
        DeviceSwitchResult(false, error.code, error.detail)
    } catch (error: IOException) {
        DeviceSwitchResult(false, error.message ?: ModeSwitchCodes.ROOT_UNAVAILABLE, "lock")
    }

    private fun requireRecord(switchId: String): SwitchRecord {
        val read = authority.read()
        if (read is dev.maplesu.manager.workmode.switch.AuthorityRead.Corrupt) {
            throw DeviceSwitchError(ModeSwitchCodes.RECOVERY_JOURNAL_CORRUPT, "${read.code}:${read.detail}")
        }
        val record = (read as? dev.maplesu.manager.workmode.switch.AuthorityRead.Ok)?.record
            ?: throw DeviceSwitchError(ModeSwitchCodes.PLAN_INVALID, "no switch record")
        if (record.switchId != switchId) {
            throw DeviceSwitchError(ModeSwitchCodes.PLAN_CONFLICT, "switchId mismatch")
        }
        return record
    }

    private fun readRecord(): SwitchRecord? =
        (authority.read() as? dev.maplesu.manager.workmode.switch.AuthorityRead.Ok)?.record

    private fun requireState(record: SwitchRecord, vararg allowed: String) {
        if (record.state !in allowed) {
            throw DeviceSwitchError(ModeSwitchCodes.PLAN_INVALID, "state ${record.state} not in ${allowed.toList()}")
        }
    }

    private fun requestOf(record: SwitchRecord): ModeSwitchRequest {
        val from = WorkMode.fromWire(record.fromMode)
            ?: throw DeviceSwitchError(ModeSwitchCodes.PLAN_INVALID, "fromMode")
        val to = WorkMode.fromWire(record.toMode)
            ?: throw DeviceSwitchError(ModeSwitchCodes.PLAN_INVALID, "toMode")
        return runCatching { ModeSwitchRequest(record.switchId, from, to, record.baselineId,
            targetPartition = record.targetPartition ?: ModeSwitchPreflight.targetPartition(to),
            sourcePartition = record.sourcePartition) }.getOrElse {
            throw DeviceSwitchError(ModeSwitchCodes.PLAN_INVALID, "persisted source/target partition")
        }
    }

    private fun requestOf(switchId: String, planFields: Map<String, String>): ModeSwitchRequest {
        if (!ModeSwitchHashes.isCanonicalUuid(switchId)) {
            throw DeviceSwitchError(ModeSwitchCodes.PLAN_INVALID, "switchId")
        }
        val from = WorkMode.fromWire(planFields["fromMode"])
            ?: throw DeviceSwitchError(ModeSwitchCodes.PLAN_INVALID, "fromMode")
        val to = WorkMode.fromWire(planFields["toMode"])
            ?: throw DeviceSwitchError(ModeSwitchCodes.PLAN_INVALID, "toMode")
        if (from == to) throw DeviceSwitchError(ModeSwitchCodes.SAME_MODE, "same mode")
        val baselineId = planFields["baselineId"]
            ?: throw DeviceSwitchError(ModeSwitchCodes.PLAN_INVALID, "baselineId")
        val target = planFields["targetPartition"]?.trim()?.takeIf(String::isNotEmpty)
        val source = planFields["sourcePartition"]?.trim()
        if (target != null && target !in ModeSwitchPreflight.allowedTargets(to) ||
            !source.isNullOrEmpty() && source !in ModeSwitchPreflight.allowedTargets(from)) {
            throw DeviceSwitchError(ModeSwitchCodes.PLAN_INVALID, "source/target partition")
        }
        return ModeSwitchRequest(switchId, from, to, baselineId,
            targetPartition = target,
            sourcePartition = source ?: "")
    }

    private fun export(request: ModeSwitchRequest, kind: String, partition: String,
                       destination: File): ExportedImage = try {
        gateway.exportImage(request, kind, partition, destination)
    } catch (error: DeviceSwitchError) {
        throw error
    } catch (error: Throwable) {
        throw DeviceSwitchError(
            if (kind == "BASE") BaselineErrors.BASELINE_CORRUPT else ModeSwitchCodes.PRE_IMAGE_INVALID,
            error.message.orEmpty(),
        )
    }

    private fun verifyImage(image: ExportedImage, size: Long, expectedSha: String,
                            sizeCode: String, shaCode: String, partition: String) {
        if (image.size != size) throw DeviceSwitchError(sizeCode, partition)
        if (!image.sha256.equals(expectedSha, ignoreCase = true)) throw DeviceSwitchError(shaCode, partition)
    }

    private fun imagesOf(record: SwitchRecord, prefix: String): Map<String, ExportedImage> = buildMap {
        record.partitions.forEach { row ->
            val path = record.artifacts["$prefix${row.name}"] ?: return@forEach
            val file = File(path)
            val sha = runCatching { ModeSwitchHashes.sha256(file) }.getOrNull() ?: return@forEach
            put(row.name, ExportedImage(file, file.length(), sha))
        }
    }

    private fun journal(record: SwitchRecord): SwitchJournal =
        journalFactory.open(record.switchId)

    /** WAL 行 planSha 固定为 switch 计划摘要（02 §3.3；move 子计划摘要不得混入）。 */
    private fun moveJournal(record: SwitchRecord): SwitchJournal {
        val target = journal(record)
        return if (record.planSha256.isNotEmpty()) FixedPlanJournal(target, record.planSha256) else target
    }

    /** 4b/4e 计划（无 moveStage 返回 null；plan 校验失败由调用方 fail-closed）。 */
    private fun planMoves(record: SwitchRecord): MoveStagePlan? {
        val stage = moveStage ?: return null
        val request = requestOf(record)
        return stage.plan(request.switchId, request.fromMode, request.toMode)
    }

    private fun rollbackOutcome(record: SwitchRecord, code: String, detail: String,
                                rolledBack: Boolean,
                                movePlan: MoveStagePlan? = null,
                                successOnRollback: Boolean = true): DeviceSwitchResult {
        // 先撤分区（调用方保证），再逆序撤配置；配置撤销失败必须升级 RECOVERY_REQUIRED。
        var moveFailure = ""
        val stage = moveStage
        if (movePlan != null && stage != null) {
            val moves = runCatching { stage.rollback(movePlan, moveJournal(record)) }
                .getOrElse { error -> MoveStageResult(false, "MOVE_ROLLBACK_FAILED", error.message.orEmpty()) }
            if (!moves.ok) moveFailure = moves.code.ifEmpty { "MOVE_ROLLBACK_FAILED" }
        }
        val effectivelyRolledBack = rolledBack && moveFailure.isEmpty()
        val effectiveCode = if (moveFailure.isNotEmpty()) moveFailure else code
        val next = if (effectivelyRolledBack) {
            record.copy(
                state = SwitchStateNames.ROLLED_BACK,
                stage = "RB",
                updatedAtUptimeMs = clock.nowMillis(),
                error = SwitchRecordError(effectiveCode, record.stage, record.step, detail, clock.nowMillis()),
            )
        } else {
            record.copy(
                state = SwitchStateNames.RECOVERY_REQUIRED,
                stage = "RB",
                updatedAtUptimeMs = clock.nowMillis(),
                error = SwitchRecordError(
                    effectiveCode.ifEmpty { ModeSwitchCodes.ROLLBACK_FAILED }, record.stage, record.step,
                    if (moveFailure.isEmpty()) detail else "move rollback failed",
                    clock.nowMillis(),
                ),
            )
        }
        authority.write(next)
        runCatching {
            journal(next).append(record.switchId, next.planSha256, "RB", record.step,
                if (effectivelyRolledBack) SwitchOp.ROLLBACK_DONE else SwitchOp.RECOVERY_REQUIRED,
                code = next.error.code)
        }
        return if (effectivelyRolledBack) {
            // commit 路径的回滚成功仍是"本次提交失败"（ok=false），只有显式 rollback API 才 ok=true。
            if (successOnRollback) ok(statusOf(next)) else fail(effectiveCode, detail, next)
        } else {
            fail(next.error.code, detail, next)
        }
    }

    private fun ok(status: DeviceSwitchStatus) = DeviceSwitchResult(true, status = status)

    private fun fail(code: String, detail: String, record: SwitchRecord? = null): DeviceSwitchResult =
        DeviceSwitchResult(false, code, detail, record?.let { statusOf(it) })

    private fun statusOf(record: SwitchRecord): DeviceSwitchStatus {
        val currentBoot = bootIds.read()
        val bootObserved = currentBoot.isNotEmpty() && record.startBootId.isNotEmpty() &&
            currentBoot != record.startBootId
        val status = ModeSwitchStatusProjector.project(record, bootObserved)
        return DeviceSwitchStatus(
            switchId = record.switchId,
            state = record.state,
            stage = record.stage,
            step = record.step,
            fromMode = record.fromMode,
            toMode = record.toMode,
            error = record.error.code,
            errorDetail = record.error.detail,
            planSha256 = record.planSha256,
            rebootRequired = record.state == SwitchStateNames.REBOOT_REQUIRED,
            bootObserved = bootObserved,
            operationActive = false,
            canRollback = status?.canRollback ?: false,
            canResumeReboot = status?.canResumeReboot ?: false,
            verifyFailures = record.attempts.bootVerifyFailures,
            partitions = record.partitions.map {
                SwitchPartitionStatus(it.name, it.role, it.size, it.preSha256, it.targetSha256,
                    it.tailSha256, it.observedSha256, it.state)
            },
            device = mapOf(
                "slotSuffix" to record.device.slotSuffix,
                "targetPartition" to record.targetPartition.orEmpty(),
                "firmware" to record.device.firmware,
                "kernelRelease" to record.device.kernelRelease,
                "kmi" to record.device.kmi,
                "selinux" to record.device.selinux,
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// P0-1 第 3 项：哪些 preflight 失败是"App 侧可自动修复的基线类"
// ---------------------------------------------------------------------------

/**
 * CTA 放宽的**唯一**白名单（与 `ModeSwitchFacade.SelinuxPreflightRepair` 同一套模式）。
 *
 * 只放行**基线专属码**（`BaselineRules.evaluate` 的全部取值域），其余一律不降级：
 * `SWITCH_ROOT_UNAVAILABLE` / `SWITCH_PRE_IMAGE_*` / `SWITCH_PENDING` /
 * `SWITCH_SPACE_INSUFFICIENT` / `SWITCH_BATTERY_LOW` / `SWITCH_PARTITION_LAYOUT_UNSUPPORTED` /
 * `INSTALLER_SLOT_INVALID` / `SWITCH_KMI_UNSUPPORTED` /
 * `INSTALLER_DEVICE_CHANGED`。
 * （`INSTALLER_SELINUX_NOT_ENFORCING` 自 2026-11-01 起不再由 preflight 产出，见
 * `ModeSwitchPreflight.selinuxCheck`：它只进 advisory，不是码。）
 *
 * 为什么白名单而不是黑名单：错误码是开放字符串集合，黑名单漏一个就等于放行一次真阻断。
 * 白名单漏一个的后果只是"按钮仍灰"（保守失败），不会假成功。
 */
internal object BaselinePreflightRepair {

    val REPAIRABLE: Set<String> = setOf(
        BaselineErrors.BASELINE_MISSING,
        BaselineErrors.BASELINE_STALE,
        BaselineErrors.BASELINE_CORRUPT,
        BaselineErrors.BASELINE_UNCLEAN,
        BaselineErrors.BASELINE_PROVENANCE_UNKNOWN,
        BaselineErrors.BASELINE_PROMOTION_FORBIDDEN,
        BaselineErrors.BASELINE_CAPTURE_UNSUPPORTED,
    )

    fun isRepairable(code: String): Boolean = code.isNotEmpty() && code in REPAIRABLE
}
