package dev.maplesu.manager.workmode.control.schema

import dev.maplesu.manager.workmode.WorkMode
import kotlinx.serialization.Serializable

/**
 * `control/modes.json`：跨模式事实权威。
 *
 * 编码约定（01 §4.2）：集合一律 List 且写入前排序，保证 canonical sha256 稳定。
 */
@Serializable
data class ModesFile(
    val schema: Int = SCHEMA,
    val revision: Long,
    val updatedAt: Long,
    /** 写入时的 bootId（不是"当前"）。 */
    val bootId: String,
    val manager: ManagerFacts,
    /** "A" | "B" | "NONE"（NONE = 未判定，禁止当 A）。 */
    val current: String,
    val last: String? = null,
    val pending: PendingSwitch? = null,
    /** 与 shared/superuser.json.revision 对齐。 */
    val sharedRevision: Long,
    /** 同路径单 owner。 */
    val owners: List<PathOwner>,
    /** 固定 2 项，按 suffix 排序。 */
    val modes: List<ModeEntry>,
    val boot: BootFacts,
    val migration: MigrationMark? = null,
) {
    companion object {
        const val SCHEMA = 1
        const val CURRENT_NONE = "NONE"
        val CURRENT_VALUES: Set<String> = setOf("A", "B", CURRENT_NONE)
    }
}

@Serializable
data class ManagerFacts(
    val uid: Int,
    val packageName: String,
    val apkSha256: String,
    /** 排序后（源 ApplicationIdentity.signers 是无序 Set）。 */
    val signerSha256: List<String>,
    val versionCode: Long,
)

@Serializable
data class PendingSwitch(
    val to: String,
    val switchId: String,
    val since: Long,
    /** SwitchState 名称（此处保留 wire 值，不复制枚举）。 */
    val state: String,
)

@Serializable
data class PathOwner(val path: String, val owner: String, val kind: String, val shape: String)

@Serializable
data class ModeEntry(
    val suffix: String,
    val implementation: String,
    val settingsRevision: Long,
    /** "ok" | "stale" | "pending-attention" | "blocked"。 */
    val superuserSyncState: String,
    val lastActiveBootId: String? = null,
    val lastActiveAt: Long? = null,
    val snapshot: SnapshotRef? = null,
    /** A:/data/adb/maplesu  B:/data/adb/ksu。 */
    val dataRoot: String,
)

@Serializable
data class SnapshotRef(val id: String, val sha256: String, val bytes: Long, val at: Long)

@Serializable
data class BootFacts(
    /** "" | "_a" | "_b"。 */
    val slot: String,
    val bootSha256: String,
    val initBootSha256: String,
    /** 必须与 current 一致才算已提交（主文档:522）。 */
    val activeMode: String,
    val baselineId: String? = null,
    val verifiedBootId: String? = null,
    val kernelRelease: String,
    val firmware: String,
)

@Serializable
data class MigrationMark(val from: String, val at: Long, val current: String)

/** 解析侧访问器（01 §4.3）。 */
fun ModesFile.entry(suffix: String): ModeEntry? = modes.firstOrNull { it.suffix == suffix }

fun ModesFile.currentMode(): WorkMode? = WorkMode.fromSuffix(current)

/** 语义校验：固定 2 项、suffix 合法、current 合法（损坏恢复矩阵用）。 */
fun ModesFile.validateSemantics(): List<String> = buildList {
    if (schema != ModesFile.SCHEMA) add("schema")
    if (current !in ModesFile.CURRENT_VALUES) add("current")
    // 历史文件可能仍带 B（Normal）条目：只要含 Ultra 的 "A" 就算合法，不强制裁剪。
    if (modes.isEmpty()) add("modes.size")
    if (modes.none { it.suffix == WorkMode.LITE.suffix }) add("modes.suffix")
    if (revision < 0) add("revision")
}

/** 唯一 canonical 形态：排序所有集合，写盘前必须调用。 */
fun ModesFile.canonicalized(): ModesFile = copy(
    manager = manager.copy(signerSha256 = manager.signerSha256.sorted()),
    owners = owners.sortedWith(compareBy({ it.path }, { it.owner })),
    modes = modes.sortedBy { it.suffix },
)
