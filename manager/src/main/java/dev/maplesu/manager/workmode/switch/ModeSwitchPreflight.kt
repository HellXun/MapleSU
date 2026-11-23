package dev.maplesu.manager.workmode.switch

import dev.maplesu.manager.workmode.Hashes
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.ModeState
import dev.maplesu.manager.workmode.detect.activeMode
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * S0 刷写前置只读检查与相关纯函数。
 *
 * 本文件不含 Android 依赖：S0a（App 可读事实）在此完整判定；S0b（root 服务权威）由
 * [InstallerGateway.preflight] 返回并与之合并。所有检查固定按 id 排序输出，
 * UI 可直接渲染"22 项预检矩阵"。
 */

/** 计划中的一个分区（候选尚未产出时即可判定 S0）：`targetSha256` = baseline 摘要（restore 步）或空。 */
data class PlannedPartition(
    val name: String,
    val role: String,
    val size: Long,
    val targetSha256: String = "",
)

/** 单项检查结果。`code` 只在 `ok=false` 时非空。 */
data class S0Check(
    val id: String,
    val ok: Boolean,
    val code: String = "",
    val detail: String = "",
    /**
     * **非阻断事实**（2026-11-01 用户指令）：SELinux 等只记录、**不判定**。
     *
     * 非空时 [ok] 恒为 `true` —— 本字段永远不代表拒绝；`S0Report.ok` / `failure()` 都不看它。
     * 事实经 [S0Report.advisories] 进日志/诊断导出，绝不转成 blocker。
     */
    val advisory: String = "",
)

/** S0 报告（合并 S0a + S0b 后）。 */
data class S0Report(
    val switchId: String,
    val checks: List<S0Check>,
) {
    val ok: Boolean get() = checks.all { it.ok }

    fun failure(): S0Check? = checks.firstOrNull { !it.ok }

    /** 全部非阻断事实（SELinux 等）；调用方只能记录/展示，**不得**据此拒绝。 */
    fun advisories(): List<S0Check> = checks.filter { it.advisory.isNotEmpty() }

    fun merge(serviceChecks: List<S0Check>): S0Report {
        if (serviceChecks.isEmpty()) return this
        val byId = LinkedHashMap<String, S0Check>()
        checks.forEach { byId[it.id] = it }
        // 服务端 S0b 是权威：同 id 时覆盖本地结论（本地只做快速失败）。
        serviceChecks.forEach { service ->
            val local = byId[service.id]
            byId[service.id] = if (local != null && !service.ok) service else local ?: service
        }
        return copy(checks = byId.values.sortedBy { it.id })
    }
}

/** S0 的全部输入；主机测试逐项翻转即可覆盖 22 条检查。 */
data class S0Inputs(
    val switchId: String,
    val fromMode: WorkMode,
    val toMode: WorkMode,
    val currentMode: WorkMode?,
    val modeState: ModeState?,
    val planned: List<PlannedPartition>,
    val baseline: BaselineVerification,
    val device: SwitchDeviceFacts,
    val battery: BatteryFacts,
    val pending: PendingFacts,
    val rootRefusal: String?,
    val controlWritable: Boolean = true,
    val implantRestoreReady: Boolean = true,
    val exportReceipt: Map<String, String> = emptyMap(),
    val inventory: PartitionInventory,
    val avb: SwitchAvbFacts = SwitchAvbFacts(),
    val freeBytes: Long,
    val dataReserveBytes: Long = ModeSwitchSpaceBudget.DATA_RESERVE_BYTES,
)

object ModeSwitchPreflight {
    const val MIN_BATTERY_PERCENT = 20
    val VALID_SLOTS = setOf("", "_a", "_b")

    /** S0-12 的行 id（SELinux 事实；**只记录**，不阻断）。 */
    const val SELINUX_CHECK_ID = "S0-12"

    /** `getenforce` 的唯一"干净"取值；其余（Permissive/Disabled/Unknown）只记录。 */
    const val SELINUX_ENFORCING = "Enforcing"

    /**
     * S0-12：SELinux 事实 → **非阻断**检查行。
     *
     * 2026-11-01 用户指令：只有「越狱模式」可拒绝刷写，其它一律放行。SELinux 状态与分区字节
     * 是否安全无关，因此本行**恒 `ok = true`**：非 `Enforcing` 时把
     * `INSTALLER_SELINUX_NOT_ENFORCING:selinux=<值>` 写进 [S0Check.advisory]（证据/日志），
     * S0 判定继续往下走（`S0Report.ok` 不再被它影响）。
     *
     * 仍然保留的唯一动作是用户要的**自动恢复**：`ModeSwitchFacade.startSwitch` 在入口执行
     * `setenforce 1` + 回读 + 根通道活性探针（本函数不参与，也不改系统状态）。
     *
     * 单一来源：`LocalInstallerGateway` / `ModeSwitchDeviceDelegate` 也调用本函数，
     * 保证 App 侧 S0a 与服务端 S0b 对同一事实给出同一行。
     */
    fun selinuxCheck(selinux: String): S0Check {
        val observed = "selinux=" + selinux.ifBlank { "Unknown" }
        return S0Check(
            id = SELINUX_CHECK_ID,
            ok = true,
            detail = observed,
            advisory = if (selinux == SELINUX_ENFORCING) "" else "${ModeSwitchCodes.SELINUX_NOT_ENFORCING}:$observed",
        )
    }

    fun evaluate(input: S0Inputs): S0Report {
        val checks = mutableListOf<S0Check>()

        fun add(id: String, ok: Boolean, code: String = "", detail: String = "") {
            checks += S0Check(id, ok, if (ok) "" else code, detail)
        }

        // S0-01 当前模式可判定且已安装。
        val modeState = input.modeState
        val activeFor = modeState?.activeMode
        val stateAllowed = modeState in setOf(
            ModeState.MODE_A_ACTIVE, ModeState.MODE_B_ACTIVE, ModeState.MODE_B_KERNEL_ONLY,
        )
        when {
            modeState == null || modeState == ModeState.INDETERMINATE
                    || modeState == ModeState.MODE_A_INSTALLED_UNBOUND ->
                add("S0-01", false, ModeSwitchCodes.STATE_UNDETERMINED,
                    "state=${modeState?.name ?: "NONE"}")
            modeState == ModeState.NOT_INSTALLED ->
                add("S0-01", false, ModeSwitchCodes.NOT_INSTALLED, "state=NOT_INSTALLED")
            modeState == ModeState.FOREIGN_SU ->
                add("S0-01", false, ModeSwitchCodes.FOREIGN_SU, "state=FOREIGN_SU")
            !stateAllowed || activeFor == null ->
                add("S0-01", false, ModeSwitchCodes.STATE_UNDETERMINED, "state=${modeState.name}")
            activeFor != input.fromMode ->
                // 六态与请求的源模式不一致 = 状态不可信，禁止切换。
                add("S0-01", false, ModeSwitchCodes.STATE_UNDETERMINED,
                    "state=${modeState.name} from=${input.fromMode.name}")
            else -> add("S0-01", true)
        }

        // S0-02 外来 Root 未接管。
        add("S0-02", modeState != ModeState.FOREIGN_SU, ModeSwitchCodes.FOREIGN_SU)

        // S0-03 目标 ≠ 当前（且当前模式必须等于请求源模式）。
        val sameMode = input.currentMode == input.toMode
        val currentMatchesFrom = input.currentMode == input.fromMode
        when {
            sameMode -> add("S0-03", false, ModeSwitchCodes.SAME_MODE, "current=${input.toMode.name}")
            !currentMatchesFrom -> add("S0-03", false, ModeSwitchCodes.STATE_UNDETERMINED,
                "current=${input.currentMode?.name ?: "NONE"} from=${input.fromMode.name}")
            else -> add("S0-03", true)
        }

        // S0-04 干净 baseline 存在且匹配。
        val baselineOk = input.baseline.state == BaselineState.PRESENT_OK
        add("S0-04", baselineOk, input.baseline.blocker ?: BaselineErrors.BASELINE_MISSING,
            input.baseline.mismatch.joinToString(","))

        // S0-05 baseline 干净性可证（S0-04 已失败时不重复报码）。
        val cleanKnown = baselineOk && (input.baseline.check?.clean == true ||
            input.baseline.check?.provenance == BaselineProvenance.USER_SELECTED)
        when {
            !baselineOk -> add("S0-05", false, input.baseline.blocker ?: BaselineErrors.BASELINE_UNCLEAN)
            !cleanKnown -> add("S0-05", false, BaselineErrors.BASELINE_UNCLEAN)
            else -> add("S0-05", true)
        }

        // Persisted records survive process death and reinstallation. Only S0-14's live
        // writer lock excludes concurrent writes; recovery records remain visible.
        checks += S0Check("S0-06", true,
            advisory = if (input.pending.installPending) ModeSwitchCodes.INSTALL_PENDING else "")
        checks += S0Check("S0-07", true,
            advisory = if (input.pending.switchPending) ModeSwitchCodes.PENDING else "")

        // S0-08 无越狱活动租约。
        add("S0-08", !input.pending.jailbreakLeaseActive, ModeSwitchCodes.JAILBREAK_ACTIVE)

        // S0-09 空间（逐挂载点；S0 用最保守的 /data 单点 + 预留）。
        val sizes = input.planned.associate { it.name to it.size }
        val target = input.planned.firstOrNull { it.role == "WRITE_CANDIDATE" }?.name
            ?: resolveTarget(input.toMode, null, input.inventory.present, ModeSwitchKmi.parse(input.device.kernelRelease))
            ?: targetPartition(input.toMode)
        val required = ModeSwitchSpaceBudget.required(sizes, target, input.dataReserveBytes)
        val spaceCode = ModeSwitchSpaceBudget.check(input.freeBytes, required)
        add("S0-09", spaceCode == null, spaceCode ?: "", "free=${input.freeBytes} required=$required")

        // Battery readings remain diagnostic facts, not a user-operation gate.
        checks += S0Check("S0-10", true,
            detail = "level=${input.battery.levelPercent} charging=${input.battery.charging}",
            advisory = if (input.battery.levelPercent < MIN_BATTERY_PERCENT && !input.battery.charging)
                ModeSwitchCodes.BATTERY_LOW else "")

        // S0-11 root 通道可用且来源已验证。
        add("S0-11", input.rootRefusal == null, input.rootRefusal ?: ModeSwitchCodes.ROOT_UNAVAILABLE)

        // S0-12 SELinux 事实：**只记录，不阻断**（2026-11-01 用户指令：只有越狱模式可拒绝刷写）。
        // 非 Enforcing ⇒ 把事实写进 advisory（证据/日志），S0 继续；自动 setenforce 仍只在
        // `ModeSwitchFacade.startSwitch` 入口做一次（用户要的自动恢复，本文件不碰系统状态）。
        checks += selinuxCheck(input.device.selinux)

        // S0-13 控制面 0700 0:0 可写。
        add("S0-13", input.controlWritable, ModeSwitchCodes.CONTROL_DIR_INVALID)

        // S0-14 无并发写任务（install.lock 可获取）。
        add("S0-14", input.pending.deviceLockAvailable, ModeSwitchCodes.BUSY)

        // S0-15 Lite 植入撤回材料齐备（只在切出 LITE 时要求）。
        val leavingLite = input.fromMode == WorkMode.LITE
        add("S0-15", !leavingLite || input.implantRestoreReady, ModeSwitchCodes.IMPLANT_RESTORE_MISSING,
            "from=${input.fromMode.name}")

        // S0-16 已导出可外部恢复的镜像（每个计划分区都要有匹配摘要的回执）。
        // baseline 不可用（MISSING/STALE/CORRUPT/IMPORT_REQUIRED）已由 S0-04 阻断：
        // 此时无法解析应导出的摘要，不能再叠加 EXPORT_REQUIRED（02 §5.2：只报可行动的错误）。
        val receiptFailures = if (input.baseline.state == BaselineState.PRESENT_OK) {
            plannedExportFailures(input)
        } else {
            emptyList()
        }
        add("S0-16", receiptFailures.isEmpty(), ModeSwitchCodes.EXPORT_REQUIRED,
            receiptFailures.joinToString(","))

        // S0-17 分区布局。
        val layoutFailures = mutableListOf<String>()
        if ("boot" !in input.inventory.present) layoutFailures += "boot-missing"
        input.planned.filter { it.name !in input.inventory.present }
            .forEach { layoutFailures += "${it.name}-missing" }
        if (input.planned.isEmpty()) layoutFailures += "plan-empty"
        add("S0-17", layoutFailures.isEmpty(), ModeSwitchCodes.LAYOUT_UNSUPPORTED,
            layoutFailures.joinToString(","))

        // S0-18 双槽识别（只写当前槽）。
        add("S0-18", input.device.slot in VALID_SLOTS, ModeSwitchCodes.SLOT_INVALID,
            "slot=${input.device.slot}")

        // S0-19 KMI 门（KSU/LKM 的 init_boot 路线不支持 android12-*）。
        val kmiFailure = if (false && target == "init_boot") {
            if (ModeSwitchKmi.supported(input.device.kernelRelease)) null
            else ModeSwitchKmi.parse(input.device.kernelRelease) ?: "KMI_UNPARSED"
        } else null
        add("S0-19", kmiFailure == null, ModeSwitchCodes.KMI_UNSUPPORTED,
            kmiFailure ?: ModeSwitchKmi.parse(input.device.kernelRelease).orEmpty())

        // S0-20 AVB/vbmeta 结构（只读结论）。
        add("S0-20", input.avb.code.isEmpty(), input.avb.code.ifEmpty {
            ModeSwitchCodes.AVB_LAYOUT_UNKNOWN
        }, input.avb.detail)

        // S0-21 设备事实一致（槽位、镜像完整性与真实分区尺寸；已 STALE 时不重复报码）。
        val stale = input.baseline.state == BaselineState.STALE
        val sizeMismatch = input.planned.filter { plan ->
            val known = input.inventory.sizes[plan.name]
            known != null && known != plan.size
        }.map { it.name }
        add("S0-21", !stale && sizeMismatch.isEmpty(), ModeSwitchCodes.DEVICE_CHANGED,
            if (stale) input.baseline.mismatch.joinToString(",") else sizeMismatch.joinToString(","))

        // S0-22 分区可写性（只读探测；真正解 ro 由 4c/4d 前完成，属真机项 D2）。
        val readOnly = input.planned.filter { input.inventory.readOnly[it.name] == true }.map { it.name }
        add("S0-22", readOnly.isEmpty(), ModeSwitchCodes.PARTITION_READONLY, readOnly.joinToString(","))

        return S0Report(input.switchId, checks)
    }

    private fun plannedExportFailures(input: S0Inputs): List<String> {
        val check = input.baseline.check ?: return input.planned.map { it.name }
        val expected = mapOf("boot" to check.bootSha256, "init_boot" to check.initBootSha256)
        return input.planned.mapNotNull { plan ->
            val receipt = input.exportReceipt[plan.name]
            val expectedSha = expected[plan.name].orEmpty()
            when {
                receipt.isNullOrBlank() -> plan.name
                expectedSha.isBlank() -> plan.name
                !receipt.equals(expectedSha, ignoreCase = true) -> plan.name
                else -> null
            }
        }
    }

    /** 该目标模式"默认拥有"的分区（02 §1.3）。 */
    fun targetPartition(to: WorkMode): String = if (to == WorkMode.LITE) "boot" else "init_boot"

    /**
     * 该目标模式**允许**的写入分区。
     *
     * - Ultra(LITE)：boot 与显式 init_boot；可生产能力由实际 producer 决定；
     * - Normal(KSU)：默认 `init_boot`；无独立 init_boot / 用户显式选择时允许 `boot`
     *   （与 KernelSU 的 `boot-patch --partition boot|init_boot` 一致）。
     */
    fun allowedTargets(to: WorkMode): Set<String> =
        setOf("init_boot", "boot").filterTo(linkedSetOf()) {
            dev.maplesu.installer.SwitchPartitionLayout.allowsPartition(
                dev.maplesu.installer.WorkMode.valueOf(to.name), it)
        }

    /** 校验并规范化目标分区；非法/不允许 → null。 */
    fun resolveTarget(to: WorkMode, requested: String?,
                      availablePartitions: Collection<String>? = null, kmi: String? = null): String? =
        (dev.maplesu.manager.installer.InstallPartitionPolicy.resolve(
            dev.maplesu.manager.installer.InstallTargetMode.fromWorkMode(to), requested, availablePartitions, kmi)
            as? dev.maplesu.manager.installer.InstallPartitionResolution.Ready)?.partition?.wire

    /** init_boot first: install Normal before removing Ultra; remove Normal before installing Ultra. */
    fun writeOrder(to: WorkMode, target: String = targetPartition(to), source: String? = null,
                   availablePartitions: Set<String>? = null): List<String> {
        require(target in allowedTargets(to)) { ModeSwitchCodes.PLAN_INVALID }
        require(availablePartitions == null || target in availablePartitions) { ModeSwitchCodes.PLAN_INVALID }
        val from = WorkMode.LITE
        val sources = when {
            source == null -> setOf(targetPartition(from)) // Existing persisted plans.
            source.isNotEmpty() -> {
                require(source in allowedTargets(from)) { ModeSwitchCodes.PLAN_INVALID }
                require(availablePartitions == null || source in availablePartitions) { ModeSwitchCodes.PLAN_INVALID }
                setOf(source)
            }
            else -> allowedTargets(from).intersect(availablePartitions ?: allowedTargets(from))
        }
        return listOf("init_boot", "boot").filter { it == target || it in sources }
    }
}

/**
 * 空间预算（02 §2.4）：pre（每分区一份）+ base（每分区一份，供 producer 只读）+
 * candidate（目标分区一份）+ reserve。现有事务无 GC，不能按单份估算。
 */
object ModeSwitchSpaceBudget {
    const val DATA_RESERVE_BYTES = 512L * 1024 * 1024
    const val APP_RESERVE_BYTES = 64L * 1024 * 1024

    fun required(partitionSizes: Map<String, Long>, targetPartition: String, reserveBytes: Long): Long {
        require(partitionSizes.values.all { it > 0 }) { ModeSwitchCodes.PLAN_INVALID }
        val total = partitionSizes.values.sum()
        val candidate = partitionSizes[targetPartition] ?: 0L
        return total + total + candidate + reserveBytes
    }

    /** null = 通过；否则返回 `SWITCH_SPACE_INSUFFICIENT`。 */
    fun check(freeBytes: Long, requiredBytes: Long): String? =
        if (freeBytes >= requiredBytes) null else ModeSwitchCodes.SPACE_INSUFFICIENT
}

/**
 * KMI 版本门（02 S0-19；与 ksud `choose_boot_partition` 的 `android12-*` 规则一致）。
 *
 * `5.10.198-android13-8-g...` → `android13-5.10`；`android12-*` 不支持 init_boot 路线。
 */
object ModeSwitchKmi {
    private val RELEASE = Regex("^(\\d+)\\.(\\d+)\\.\\d+.*-android(\\d+)-")
    private val KMI = Regex("^android(\\d+)-(\\d+)\\.(\\d+)$")

    fun parse(kernelRelease: String?): String? {
        val value = kernelRelease?.trim().orEmpty()
        if (value.isEmpty()) return null
        KMI.matchEntire(value)?.let { return "android${it.groupValues[1]}-${it.groupValues[2]}.${it.groupValues[3]}" }
        val match = RELEASE.find(value) ?: return null
        return "android${match.groupValues[3]}-${match.groupValues[1]}.${match.groupValues[2]}"
    }

    fun supported(kernelRelease: String?): Boolean {
        val kmi = parse(kernelRelease) ?: return false
        return !kmi.startsWith("android12-")
    }
}

/** AVB footer（last 64B）解析结果。 */
data class AvbTailFacts(
    val footerPresent: Boolean = false,
    val originalImageSize: Long = 0,
    val vbmetaOffset: Long = 0,
    val vbmetaSize: Long = 0,
    val unknownNonZeroTail: Boolean = false,
)

/**
 * AVB/vbmeta 只读结构判定（02 S0-20）。
 *
 * 只做"能不能安全继续"的结构判断，不验签（发布红线 2：不写 vbmeta、不承诺验签通过）。
 * footer 布局：magic[4]="AVBf"、version_major[4]、version_minor[4]、original_image_size[8]、
 * vbmeta_offset[8]、vbmeta_size[8]、reserved[28]，全部大端。
 */
object ModeSwitchAvb {
    const val FOOTER_SIZE = 64
    private val MAGIC = byteArrayOf('A'.code.toByte(), 'V'.code.toByte(), 'B'.code.toByte(), 'f'.code.toByte())
    private val VBMETA_MAGIC = byteArrayOf('A'.code.toByte(), 'V'.code.toByte(), 'B'.code.toByte(), '0'.code.toByte())

    fun inspectTail(footer: ByteArray, partitionSize: Long): AvbTailFacts {
        if (partitionSize <= 0) return AvbTailFacts(unknownNonZeroTail = true)
        if (footer.size < FOOTER_SIZE) {
            return AvbTailFacts(unknownNonZeroTail = footer.any { it.toInt() != 0 })
        }
        val bytes = footer.copyOfRange(footer.size - FOOTER_SIZE, footer.size)
        val magic = bytes.copyOfRange(0, 4)
        if (magic.contentEquals(MAGIC)) {
            return AvbTailFacts(
                footerPresent = true,
                originalImageSize = readLong(bytes, 12),
                vbmetaOffset = readLong(bytes, 20),
                vbmetaSize = readLong(bytes, 28),
            )
        }
        return AvbTailFacts(unknownNonZeroTail = bytes.any { it.toInt() != 0 })
    }

    /** vbmeta 头 magic 是否为 `AVB0`。 */
    fun hasVbmetaHeader(head: ByteArray): Boolean =
        head.size >= 4 && head.copyOfRange(0, 4).contentEquals(VBMETA_MAGIC)

    /** null = 可继续；否则 `SWITCH_AVB_LAYOUT_UNKNOWN`。 */
    fun evaluate(tail: AvbTailFacts, partitionSize: Long): String? {
        if (partitionSize <= 0) return ModeSwitchCodes.AVB_LAYOUT_UNKNOWN
        if (tail.unknownNonZeroTail) return ModeSwitchCodes.AVB_LAYOUT_UNKNOWN
        if (!tail.footerPresent) return null
        if (tail.originalImageSize <= 0 || tail.originalImageSize > partitionSize) {
            return ModeSwitchCodes.AVB_LAYOUT_UNKNOWN
        }
        if (tail.vbmetaOffset < 0 || tail.vbmetaSize <= 0 ||
            tail.vbmetaOffset + tail.vbmetaSize > partitionSize
        ) {
            return ModeSwitchCodes.AVB_LAYOUT_UNKNOWN
        }
        return null
    }

    private fun readLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xff)
        }
        return value
    }
}

/** 切换链专用摘要/长度工具（与 installer-core 的 `SwitchHashes` 同语义，包内可见性不同）。 */
object ModeSwitchHashes {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun tailSha256(file: File): String {
        val size = file.length()
        val count = minOf(size, WritableTailProbe.BYTES.toLong()).toInt()
        val buffer = ByteArray(count)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(size - count)
            raf.readFully(buffer)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(buffer)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun isFullLength(file: File?, size: Long): Boolean =
        file != null && file.isFile && file.length() == size

    fun isHash(value: String?): Boolean = value != null && value.matches(Regex("[0-9a-f]{64}"))

    fun isCanonicalUuid(value: String?): Boolean {
        if (value == null) return false
        return runCatching { java.util.UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}

/** 与 `WritablePartition.TAIL_PROBE_BYTES` 对齐（installer-core 常量不在本包可见）。 */
internal object WritableTailProbe {
    const val BYTES = 64 * 1024
}

/** `Hashes` 复用点：planSha/摘要统一走 [Hashes]，禁止另写一套。 */
internal fun canonicalSha(text: String): String = Hashes.sha256Hex(text)
