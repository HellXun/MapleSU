package dev.maplesu.manager.workmode.migration

import dev.maplesu.manager.workmode.WorkMode
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 外部 Root 迁移后端纯数据模型。
 *
 * 本包只放**主机可测**的纯 Kotlin：不 import Android，不接触真实块设备。
 * Android 侧探针/写入接线由服务层实现（05 §1.2 的三段式架构）。
 */

/** 迁移失败：所有对外错误码统一 `MIGRATION_*`（05 附录 A）。 */
class MigrationFailure(
    val code: String,
    detail: String = "",
    cause: Throwable? = null,
) : IOException(if (detail.isEmpty()) code else "$code: $detail", cause) {
    companion object {
        fun require(condition: Boolean, code: String, detail: String = "") {
            if (!condition) throw MigrationFailure(code, detail)
        }
    }
}

/** 迁移错误码唯一来源（05 附录 A 的子集；不复制主文档错误码）。 */
object MigrationCodes {
    const val NO_ROOT = "MIGRATION_NO_ROOT"
    const val NO_SPACE = "MIGRATION_NO_SPACE"
    const val PENDING_TRANSACTION = "MIGRATION_PENDING_TRANSACTION"
    const val LAYOUT_UNSUPPORTED = "MIGRATION_LAYOUT_UNSUPPORTED"
    const val CANDIDATE_UNPRODUCIBLE = "MIGRATION_CANDIDATE_UNPRODUCIBLE"
    const val CLEAN_IMAGE_UNAVAILABLE = "MIGRATION_CLEAN_IMAGE_UNAVAILABLE"
    const val OWN_MANAGER_INSTALLED = "MIGRATION_OWN_MANAGER_INSTALLED"
    const val PLAN_INVALID = "MIGRATION_PLAN_INVALID"
    const val ORDER_VIOLATION = "MIGRATION_ORDER_VIOLATION"
    const val PRE_IMAGE_MISSING = "MIGRATION_PRE_IMAGE_MISSING"
    const val PARTITION_SIZE_MISMATCH = "MIGRATION_PARTITION_SIZE_MISMATCH"
    const val CANDIDATE_LENGTH_INVALID = "MIGRATION_CANDIDATE_LENGTH_INVALID"
    const val MANIFEST_INVALID = "MIGRATION_MANIFEST_INVALID"
    const val BACKUP_SOURCE_MISSING = "MIGRATION_BACKUP_SOURCE_MISSING"
    const val BACKUP_VERIFY_FAILED = "MIGRATION_BACKUP_VERIFY_FAILED"
    const val BACKUP_NOT_VERIFIED = "MIGRATION_BACKUP_NOT_VERIFIED"
    const val BACKUP_MISSING = "MIGRATION_BACKUP_MISSING"
    const val JOURNAL_INVALID = "MIGRATION_JOURNAL_INVALID"
    const val JOURNAL_STATE_INVALID = "MIGRATION_JOURNAL_STATE_INVALID"
    const val MODULE_CONTAINS_SYMLINK = "MODULE_CONTAINS_SYMLINK"
    const val MODULE_PATH_INVALID = "MODULE_PATH_INVALID"
    const val MODULE_ARCHIVE_INVALID = "MODULE_ARCHIVE_INVALID"
    const val MODULE_TYPE_UNSUPPORTED = "MODULE_TYPE_UNSUPPORTED"
    const val MODULE_METADATA_MISSING = "MODULE_METADATA_MISSING"
    const val MODULE_ID_INVALID = "MODULE_ID_INVALID"
    const val MODULE_ORIGIN_MAGISK_UNVERIFIED = "MODULE_ORIGIN_MAGISK_UNVERIFIED"
    const val MODULE_REPACK_FAILED = "MODULE_REPACK_FAILED"
    const val ALLOWLIST_UNSUPPORTED = "ALLOWLIST_VERSION_UNSUPPORTED"
    const val AUTH_STALE = "AUTH_IDENTITY_STALE"
    const val RESTORE_EXTERNAL_TOOL_FORBIDDEN = "MIGRATION_EXTERNAL_TOOL_FORBIDDEN"
    const val RESTORE_INPUT_NOT_COPY = "MIGRATION_RESTORE_INPUT_NOT_COPY"
    const val RESTORE_BACKUP_UNVERIFIED = "MIGRATION_RESTORE_BACKUP_UNVERIFIED"
    const val SELF_UNINSTALL_REFUSED = "MIGRATION_SELF_UNINSTALL_REFUSED"
    const val APK_UNINSTALL_BEFORE_VERIFIED = "MIGRATION_APK_UNINSTALL_BEFORE_VERIFIED"
}

// ---------------------------------------------------------------------------------------------
// 证据 / 探针事实（05 §1.3）
// ---------------------------------------------------------------------------------------------

/** DEFINITIVE=只有该家族才会出现；STRONG=典型但不唯一；WEAK=仅提示。 */
enum class EvidenceStrength { DEFINITIVE, STRONG, WEAK }

enum class EvidenceKind {
    PACKAGE, SU_VERSION, TOOL_BINARY, DATA_DIR, IMAGE_MARKER, STOCK_BACKUP, MANAGER_APK, CONTROL_PLANE,
}

data class Evidence(
    val kind: EvidenceKind,
    /** 例如 "/data/adb/sevenk" / "su -v" / "init_boot ramdisk member kernelsu.ko"。 */
    val source: String,
    /** 观测到的原文/摘要；敏感值必须已脱敏（不写 allowlist 原始字节 / signer 明文）。 */
    val value: String,
    val strength: EvidenceStrength,
)

/**
 * 家族。主文档:1123 写成 `KSU_UPSTREAM / KSU_7K / MAGISK / UNSUPPORTED`，
 * 但同节规则 1（主文档:1138）要求未命中时为 UNKNOWN 且仍可迁移 —— 以规则为准（05 C10）。
 */
enum class Family {
    KSU_UPSTREAM, KSU_7K, MAGISK, APATCH, UNKNOWN;

    val isKsuFamily: Boolean get() = this == KSU_UPSTREAM || this == KSU_7K
    val isKnown: Boolean get() = this != UNKNOWN && this != APATCH
}

/** 单个路径事实：软链只记录 target 原文，不跟随（主文档:1232）。 */
data class FileFact(
    val path: String,
    val exists: Boolean,
    val isSymlink: Boolean = false,
    val linkTarget: String? = null,
    val isDirectory: Boolean = false,
    val size: Long = 0,
    val uid: Int = -1,
    val mode: Int = -1,
)

data class ToolFact(
    val path: String,
    val executable: Boolean,
    /** 原始版本文本，例如 "KernelSU 0.9.5 (uapi: 6)"。 */
    val version: String? = null,
    val sha256: String? = null,
)

/** 外部 stock 备份可用性（05 §1.7；USABLE 的判据在 [StockBackupRules]）。 */
enum class BackupState { USABLE, MISSING, UNREADABLE, SIZE_MISMATCH, CORRUPT }

data class StockBackupRef(
    /** "boot" / "init_boot"；推断不出来为 null。 */
    val partitionHint: String?,
    /** KSU: stock_image.sha1；Magisk: .backup/.magisk 的 SHA1=。 */
    val recordedId: String?,
    val path: String,
    val size: Long,
    /** 我们对备份文件的 SHA-256（可用性证明）。 */
    val sha256: String?,
    val state: BackupState,
)

/** 镜像 marker（05 §1.8）。 */
enum class ImageMarker {
    KSU_KO,
    SEVENK_KO,
    KSU_INIT_REAL,
    MAGISK_BACKUP,
    MAGISK_OVERLAY,
    MAGISK_INIT,
    OTHER_PATCHER,
}

data class PartitionFacts(
    val name: String,
    val present: Boolean,
    val size: Long = 0,
    val sha256: String = "",
    val resolvedPath: String = "",
    val markers: Set<ImageMarker> = emptySet(),
    /** 由 markers 推出的"谁改的"，未知则 null。 */
    val patchedBy: Family? = null,
)

data class PackageFacts(
    val uid: Int,
    val packages: List<String>,
    val signerSha256: Set<String> = emptySet(),
    val firstInstallTime: Long = 0,
    val lastUpdateTime: Long = 0,
    val apkPath: String = "",
)

data class PackageInventory(val entries: List<PackageFacts> = emptyList()) {
    fun byUid(uid: Int): PackageFacts? = entries.firstOrNull { it.uid == uid }
    fun allPackages(): Set<String> = entries.flatMap { it.packages }.toSet()
    fun entryForPackage(pkg: String): PackageFacts? = entries.firstOrNull { pkg in it.packages }
}

data class DeviceFacts(
    val kernelRelease: String = "",
    val firmware: String = "",
    val slotSuffix: String = "",
    val selinux: String = "Enforcing",
    val bootId: String = "",
    val freeBytesOnData: Long = Long.MAX_VALUE,
    /** `id -u` == 0（经外部 su）。 */
    val externalRootAvailable: Boolean = true,
    /** init_boot 存在或 boot 可写（05 §1.9）。 */
    val layoutSupported: Boolean = true,
    /** 任一 install/switch/migration journal 非终态。 */
    val pendingTransaction: Boolean = false,
    /** ≥20% 或充电中（主文档:614）。 */
    val batteryOk: Boolean = true,
)

/** `.allowlist` 探测（KSU/kernel/policy/allowlist.c:29-30,72）。 */
data class AllowlistProbe(
    val path: String? = null,
    val readable: Boolean = false,
    val magic: Int? = null,
    val version: Int? = null,
    val bytes: Long = 0,
)

/** magisk.db 探测（Magisk/native/src/include/consts.hpp:7）。 */
data class MagiskDbProbe(
    val path: String? = null,
    val readable: Boolean = false,
    val userVersion: Int? = null,
    val policyRows: Int? = null,
)

/** 服务侧只读探测结果（App 侧只补 [PackageInventory]）。 */
data class ExternalRootProbe(
    val suUid: Int = -1,
    val suVersion: String? = null,
    val suPath: String? = null,
    /** MigrationPaths 白名单路径 → 事实。 */
    val files: Map<String, FileFact> = emptyMap(),
    /** "ksud" / "magisk" / "magiskboot" → 事实。 */
    val tools: Map<String, ToolFact> = emptyMap(),
    val allowlist: AllowlistProbe = AllowlistProbe(),
    val magiskDb: MagiskDbProbe = MagiskDbProbe(),
    /** "boot"/"init_boot"/"vbmeta" → 只读镜像事实。 */
    val partitions: Map<String, PartitionFacts> = emptyMap(),
    /** 分区名或 "unknown#i" → stock 备份。 */
    val stockBackups: Map<String, StockBackupRef> = emptyMap(),
    val device: DeviceFacts = DeviceFacts(),
    /** 本产品控制面已存在（`/data/adb/maplesu-workmode/control/modes.json`）。 */
    val ownControlPlanePresent: Boolean = false,
)

/** 还原策略阶梯（05 §3.3 P0→P3）。 */
enum class RestoreStrategy {
    KSU_STOCK_COPY,
    MAGISK_STOCK_GZ,
    KSU_EXTERNAL_TOOL_ON_COPY,
    MAGISK_INTERNAL_REBUILD,
    GENERIC_STOCK_SCAN,
    NONE,
}

sealed interface Blocker {
    val code: String

    data object NoRoot : Blocker {
        override val code: String get() = MigrationCodes.NO_ROOT
    }

    data class NoSpace(val requiredBytes: Long, val freeBytes: Long) : Blocker {
        override val code: String get() = MigrationCodes.NO_SPACE
    }

    data object PendingTransaction : Blocker {
        override val code: String get() = MigrationCodes.PENDING_TRANSACTION
    }

    data object LayoutUnsupported : Blocker {
        override val code: String get() = MigrationCodes.LAYOUT_UNSUPPORTED
    }

    data class CandidateUnproducible(val partition: String) : Blocker {
        override val code: String get() = MigrationCodes.CANDIDATE_UNPRODUCIBLE
    }

    data class CleanImageUnavailable(val partitions: List<String>) : Blocker {
        override val code: String get() = MigrationCodes.CLEAN_IMAGE_UNAVAILABLE
    }

    data object OwnManagerInstalled : Blocker {
        override val code: String get() = MigrationCodes.OWN_MANAGER_INSTALLED
    }
}

/**
 * 检测输出。保留主文档:1122-1133 的全部字段，另补 M1/M5 需要的字段
 * （`cleanImagePartitions` 是本册新增：M1 前即可判定"能否取得干净镜像"）。
 */
data class ExternalRootInfo(
    val family: Family,
    val version: String? = null,
    val managerPackages: List<String> = emptyList(),
    val uapi: Int? = null,
    val patchedPartitions: Set<String> = emptySet(),
    val stockBackups: Map<String, StockBackupRef> = emptyMap(),
    val restoreStrategy: RestoreStrategy = RestoreStrategy.NONE,
    /** 已知家族 = true；UNKNOWN = false 但仍可迁移。 */
    val supported: Boolean = false,
    /** family == UNKNOWN 或策略为 GENERIC/NONE。 */
    val bestEffort: Boolean = false,
    val blockers: List<Blocker> = emptyList(),
    val evidence: List<Evidence> = emptyList(),
    /** 判定为外部 owner 且存在的 /data/adb 子树。 */
    val dataDirs: Map<String, FileFact> = emptyMap(),
    val tools: Map<String, ToolFact> = emptyMap(),
    val allowlist: AllowlistProbe = AllowlistProbe(),
    val magiskDb: MagiskDbProbe = MagiskDbProbe(),
    /** 可能得到"干净镜像"的分区集合（P0 或 P2 证据）。 */
    val cleanImagePartitions: Set<String> = emptySet(),
    val detectSchema: Int = 1,
) {
    /** 歧义/未知来源按 best-effort。 */
    val isAmbiguous: Boolean get() = family == Family.UNKNOWN
}

data class SpaceEstimate(
    val backupEstimateBytes: Long,
    val partitionBytes: Long,
    val requiredBytes: Long,
    val freeBytesOnData: Long,
    val adequate: Boolean,
)

data class MigrationEligibility(
    val enabled: Boolean,
    val blockers: List<Blocker>,
    val recommendedTarget: WorkMode?,
    /** i18n key，UI 直接映射，不在此拼英文长句。 */
    val recommendedReason: String?,
    val space: SpaceEstimate,
    /** 每个目标模式是否可执行（目标候选可产出）。 */
    val targetAllowed: Map<WorkMode, Boolean> = emptyMap(),
)

/** 目标模式分区常量：只来自模式，不接受客户端传值（主文档:641）。 */
val WorkMode.targetPartitions: Set<String>
    get() = when (this) {
        WorkMode.LITE -> setOf("boot")
    }

// ---------------------------------------------------------------------------------------------
// 最终镜像计划（05 §3.1）
// ---------------------------------------------------------------------------------------------

enum class ImageSource { PRE_PARTITION, EXTERNAL_STOCK, BASELINE, CURRENT_PARTITION_AS_INPUT, CANDIDATE }

data class ImageRef(
    /** 备份/工作目录内相对路径；绝不指向 /dev。 */
    val path: String,
    val size: Long,
    val sha256: String,
    val source: ImageSource,
) {
    init {
        MigrationFailure.require(path.isNotEmpty(), MigrationCodes.PLAN_INVALID, "image path")
        MigrationFailure.require(size > 0, MigrationCodes.PLAN_INVALID, "image size")
        MigrationFailure.require(sha256.matches(Regex("[0-9a-f]{64}")), MigrationCodes.PLAN_INVALID, "image sha")
    }
}

enum class PartitionAction { RESTORE_EXTERNAL, WRITE_CANDIDATE, LEAVE_UNCHANGED }

/** 残留语义：决定 M9 是否把本分区计入"无残留"（红线 14）。 */
enum class ResidueMode { NONE, UNRESTORED_EXTERNAL, UNKNOWN_MANAGER }

data class PlannedPartition(
    val name: String,
    val action: PartitionAction,
    /** 写前整分区镜像（回滚源）。 */
    val pre: ImageRef,
    /** 写后目标镜像；WRITE_CANDIDATE 在 M3 产出前为 null。 */
    val final: ImageRef?,
    /** WRITE_CANDIDATE 的 producer 输入（干净镜像/基线；UNKNOWN 相交时可为当前镜像）。 */
    val producerInput: ImageRef?,
    val residue: ResidueMode,
    val evidence: List<Evidence> = emptyList(),
)

data class MigrationPlan(
    val migrationId: String,
    val target: WorkMode,
    val targetSet: Set<String>,
    val restoreSet: Set<String>,
    /** 每分区恰好一条。 */
    val entries: List<PlannedPartition>,
    /** 需要写入的顺序（不含 LEAVE_UNCHANGED）。 */
    val writeOrder: List<String>,
    /** writeOrder 的逆序。 */
    val rollbackOrder: List<String>,
    /** 任一 entry.residue != NONE。 */
    val residual: Boolean,
) {
    fun partition(name: String): PlannedPartition =
        entries.singleOrNull { it.name == name }
            ?: throw MigrationFailure(MigrationCodes.PLAN_INVALID, name)

    fun writableEntries(): List<PlannedPartition> =
        writeOrder.map { partition(it) }

    fun restoreOnlyPartitions(): Set<String> = restoreSet - targetSet
}

data class PlanInput(
    val migrationId: String,
    val target: WorkMode,
    val info: ExternalRootInfo,
    /** 各分区写前镜像（M1 已落盘）。 */
    val pre: Map<String, ImageRef>,
    /** 已验证干净镜像（05 §3.3 P0/P2 产物）。 */
    val clean: Map<String, ImageRef> = emptyMap(),
    val partitionSize: Map<String, Long> = emptyMap(),
    /** 01 的 BaselineStore 提供的分区（可作为 producer 输入）。 */
    val baseline: Map<String, ImageRef> = emptyMap(),
    /** M3 已产出的目标候选（M2 计划时为 null）。 */
    val candidates: Map<String, ImageRef> = emptyMap(),
)

// ---------------------------------------------------------------------------------------------
// 数据迁移（05 §5.1）
// ---------------------------------------------------------------------------------------------

enum class ModuleOrigin { KSU, MAGISK }

enum class ModuleDecision {
    /** 目标 KSU：同名布局直接恢复。 */
    RESTORE_DIRECT,

    /** 目标 Lite：预检通过并自动导入。 */
    IMPORT_LITE,

    /** 目标 KSU + Magisk 模块：恢复实体但打 disable 标志（离线/禁用）。 */
    IMPORT_DISABLED,

    /** 预检拒绝（策略/格式不允许）。 */
    SKIP_UNSUPPORTED,

    /** 预检通过但安装失败。 */
    SKIP_FAILED,
}

data class ModuleCandidate(
    val moduleId: String,
    val origin: ModuleOrigin,
    val sourceDir: File,
    val disabled: Boolean = false,
    val remove: Boolean = false,
    val update: Boolean = false,
    val skipMount: Boolean = false,
)

data class ModulePlanItem(
    val moduleId: String,
    val origin: ModuleOrigin,
    val sourceDir: String,
    val decision: ModuleDecision,
    val reason: String = "",
    val archiveSha256: String? = null,
    val installCode: Int? = null,
    val needsReboot: Boolean? = null,
    /** 源模块带 disable 标志：导入后需保持禁用（设备侧执行）。 */
    val sourceDisabled: Boolean = false,
) {
    val imported: Boolean get() = decision == ModuleDecision.IMPORT_LITE ||
        decision == ModuleDecision.RESTORE_DIRECT || decision == ModuleDecision.IMPORT_DISABLED
}

enum class AuthSource { KSU_ALLOWLIST, MAGISK_DB, NONE }

enum class AllowDecision { ALLOW, DENY }

/** 源侧授权意图（05 §5.1）。uid 必须经身份核验后才允许生成。 */
data class AuthIntent(
    val uid: Int,
    val packageName: String,
    val signerSha256: Set<String>,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val decision: AllowDecision,
)

data class AuthStale(val uid: Int, val packageName: String?, val reason: String)

data class MigrationDataPlan(
    val mode: WorkMode,
    val modules: List<ModulePlanItem> = emptyList(),
    val authSource: AuthSource = AuthSource.NONE,
    val authIntents: List<AuthIntent> = emptyList(),
    val authStale: List<AuthStale> = emptyList(),
    /** 外部管理器包名；空 = 不卸载。 */
    val apkUninstall: List<String> = emptyList(),
)

@kotlinx.serialization.Serializable
data class ResidueRecord(
    val kind: String,
    val path: String,
    val reason: String,
    val recorded: Boolean = true,
)

// ---------------------------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------------------------

/** 流式 SHA-256（镜像/大文件用；`Hashes` 只接受 ByteArray）。 */
object MigrationHashes {
    fun sha256(file: File): String {
        MigrationFailure.require(file.isFile, MigrationCodes.MANIFEST_INVALID, "not a file: ${file.name}")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun tailSha256(file: File, tailBytes: Int = 64 * 1024): String {
        val size = file.length()
        val count = minOf(size, tailBytes.toLong()).toInt()
        val buffer = ByteArray(count)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(size - count)
            raf.readFully(buffer)
        }
        return MessageDigest.getInstance("SHA-256").digest(buffer)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun isHash(value: String): Boolean = value.matches(Regex("[0-9a-f]{64}"))
}
