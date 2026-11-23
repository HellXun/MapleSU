package dev.maplesu.manager.workmode.storage

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.Hashes

/**
 * 切换移动集：rename 进/出离线仓。
 *
 * 纯数据 + 纯校验，主机可测。执行顺序约束：入按 [inboundOrder]（modules 先于 metamodule），
 * 出按 [outboundOrder]（= 入的逆序：先移指针，再移实体，否则中途崩溃会留下指向已移走实体的软链）。
 */
enum class MoveKind { TREE, SYMLINK, FILE }

/** 跨挂载必须 copy+fsync+读回+删源。 */
enum class MoveMount { SAME_AS_STORE, CROSS_MOUNT_COPY }

data class MoveEntry(
    val livePath: String,
    val kind: MoveKind,
    val mount: MoveMount,
    val owner: WorkMode,
    /** owner 模式活动时该路径的形态；源不存在允许跳过，存在时搬出/恢复均按此校验。 */
    val expectedShape: String,
) {
    companion object {
        const val REAL_DIR = "REAL_DIR"
        const val REAL_DIR_EMPTY = "REAL_DIR_EMPTY"
        const val SYMLINK = "SYMLINK"
        const val SYMLINK_TREE = "SYMLINK_TREE"
        const val FILE = "FILE"
        const val ABSENT = "ABSENT"
        val SHAPES: Set<String> = setOf(REAL_DIR, REAL_DIR_EMPTY, SYMLINK, SYMLINK_TREE, FILE, ABSENT)
    }
}

object MoveSet {
    const val STORE_ROOT = "/data/adb/maplesu-workmode/modes"
    const val CONTROL_ROOT = "/data/adb/maplesu-workmode"
    const val ADB_ROOT = "/data/adb"

    /** 主文档:300-304；动态项（/data/<credentialHex>、/data/<UltraKey16>）由调用方追加。 */
    val LITE_MOVE_SET: List<MoveEntry> = listOf(
        MoveEntry("/data/adb/maplesu", MoveKind.TREE, MoveMount.SAME_AS_STORE, WorkMode.LITE, MoveEntry.REAL_DIR),
        MoveEntry("/data/adb/maplesu-su-policy", MoveKind.TREE, MoveMount.SAME_AS_STORE, WorkMode.LITE, MoveEntry.REAL_DIR),
        MoveEntry("/data/adb/maplesu-policy.lock", MoveKind.FILE, MoveMount.SAME_AS_STORE, WorkMode.LITE, MoveEntry.FILE),
        MoveEntry("/data/adb/ksu", MoveKind.TREE, MoveMount.SAME_AS_STORE, WorkMode.LITE, MoveEntry.REAL_DIR_EMPTY),
        MoveEntry("/data/adb/sevenk", MoveKind.TREE, MoveMount.SAME_AS_STORE, WorkMode.LITE, MoveEntry.REAL_DIR),
    )

    /**
     * installer 锁 + 待处理标记目录（与 `ServiceModeSwitchWiring.INSTALLER_ROOT` 同值）。
     *
     * 它位于 `/data/adb/maplesu`（LITE 的搬移项）**之内**：整树搬移会把设备锁一起搬到
     * `modes/LITE/...`，锁的"释放/后续互斥"语义随之被破坏（M5）。因此它是
     * [NEVER_MOVE] 的后代，[preservedPathsUnder] 会把包含它的条目降级为"保留子树"的合并搬移。
     */
    const val INSTALLER_ROOT = "/data/adb/maplesu/installer"

    /** Manager identity recovery is shared by both modes, not Ultra configuration. */
    const val RECOVERY_ROOT = "/data/adb/maplesu/recovery"
    const val ULTRA_IDENTITY_ROOT = "/data/adb/maplesu/ultra-identity"
    const val HIDDEN_STATE = "/data/adb/maplesu/lite_hidden"
    const val NORMAL_RECOVERY_TOKEN = "/data/adb/maplesu/normal-webui-token"
    const val LEGACY_RECOVERY_ROOT = "$INSTALLER_ROOT/legacy-recovery"

    /** 主文档:310：永远不移动（含控制面自身 + installer 锁目录）。 */
    // KSU payloads, pending updates and their mount provider are shared by both modes.
    // Moving them to a mode slot hides existing modules and can strand their configuration.
    val NEVER_MOVE: Set<String> = setOf(ADB_ROOT, CONTROL_ROOT, INSTALLER_ROOT, RECOVERY_ROOT, ULTRA_IDENTITY_ROOT, NORMAL_RECOVERY_TOKEN, HIDDEN_STATE,
        ORDER_MODULES, ORDER_MODULES_UPDATE, ORDER_METAMODULE)

    /** 错误码（01 §3.5 原子性约束 A1–A8）。 */
    object Errors {
        const val LIVE_PATH_INVALID = "MOVE_LIVE_PATH_INVALID"
        const val FORBIDDEN_PATH = "MOVE_FORBIDDEN_PATH"
        const val DUPLICATE_PATH = "MOVE_DUPLICATE_LIVE_PATH"
        const val STORE_OVERLAP = "MOVE_STORE_OVERLAP"
        const val EXDEV_UNSUPPORTED = "MOVE_EXDEV_UNSUPPORTED"
        const val OWNER_CONFLICT = "MOVE_OWNER_CONFLICT"
        const val SHAPE_MISMATCH = "MOVE_SHAPE_MISMATCH"
        const val STORE_PATH_INVALID = "MOVE_STORE_PATH_INVALID"

        // 执行阶段（W04b 落地 FileMoveExecutor）
        const val SOURCE_SHAPE_MISMATCH = "MOVE_SOURCE_SHAPE_MISMATCH"
        const val VERIFY_FAILED = "MOVE_VERIFY_FAILED"
        const val COPY_VERIFY_FAILED = "MOVE_COPY_VERIFY_FAILED"
        const val RENAME_FAILED = "MOVE_RENAME_FAILED"
        const val DEST_EXISTS = "MOVE_DEST_EXISTS"
        const val AMBIGUOUS = "MOVE_AMBIGUOUS"
    }

    fun moveSetFor(mode: WorkMode): List<MoveEntry> = when (mode) {
        WorkMode.LITE -> LITE_MOVE_SET
    }

    /** live → store：`modes/<M>/data` + livePath（去掉前导 '/'），保证可逆且自描述。 */
    fun storePathFor(mode: WorkMode, livePath: String): String =
        "$STORE_ROOT/${mode.suffix}/data/${livePath.trimStart('/')}"

    /** Completed inverse legs must not be replayed after a later leg fails. */
    fun rollbackReceiptPath(switchId: String, entry: MoveEntry, storeToLive: Boolean): String {
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(switchId))
        val direction = if (storeToLive) "in" else "out"
        return "$INSTALLER_ROOT/move-rollback/$switchId/${entry.owner.suffix}-$direction-${Hashes.sha256Hex(entry.livePath)}"
    }

    /** store → live：只接受落在 `modes/<M>/data/` 下的路径，防目录穿越。 */
    fun livePathFor(storePath: String): String {
        val prefix = "$STORE_ROOT/"
        require(storePath.startsWith(prefix)) { Errors.STORE_PATH_INVALID }
        val components = storePath.removePrefix(prefix).split('/')
        require(components.size >= 3) { Errors.STORE_PATH_INVALID }
        require(WorkMode.fromSuffix(components[0]) != null) { Errors.STORE_PATH_INVALID }
        require(components[1] == "data") { Errors.STORE_PATH_INVALID }
        val live = "/" + components.drop(2).joinToString("/")
        require(live.startsWith("/data/") || live.startsWith("/metadata/")) { Errors.STORE_PATH_INVALID }
        require(components.none { it == ".." || it.isEmpty() }) { Errors.STORE_PATH_INVALID }
        return live
    }

    /**
     * 计划校验（在写任何字节之前执行，失败 = 不切换）：01 §3.4 五条 +
     * 路径/形态合法性。返回错误码列表（空 = 通过）。
     */
    fun validate(plan: List<MoveEntry>, owners: Map<String, WorkMode>): List<String> {
        val errors = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (entry in plan) {
            val live = entry.livePath
            if (!live.startsWith("/")) {
                errors += "${Errors.LIVE_PATH_INVALID}:$live"
                continue
            }
            if (!seen.add(live)) errors += "${Errors.DUPLICATE_PATH}:$live"
            if (isNeverMove(live)) errors += "${Errors.FORBIDDEN_PATH}:$live"
            if (entry.expectedShape !in MoveEntry.SHAPES) {
                errors += "${Errors.SHAPE_MISMATCH}:$live"
            }
            if (live.startsWith("/metadata/") && entry.mount != MoveMount.CROSS_MOUNT_COPY) {
                errors += "${Errors.EXDEV_UNSUPPORTED}:$live"
            }
            val store = storePathFor(entry.owner, live)
            if (store == live || store.startsWith("$live/") || live.startsWith("$store/")) {
                errors += "${Errors.STORE_OVERLAP}:$live"
            }
            val declaredOwner = owners[live]
            if (declaredOwner != null && declaredOwner != entry.owner) {
                errors += "${Errors.OWNER_CONFLICT}:$live"
            }
        }
        plan.groupBy { it.livePath }.forEach { (path, entries) ->
            if (entries.map { it.owner }.distinct().size > 1) {
                errors += "${Errors.OWNER_CONFLICT}:$path"
            }
        }
        return errors.distinct()
    }

    /**
     * 该路径是否**永不移动**。
     *
     * `ADB_ROOT` 只做**精确**匹配（它是所有搬移项的父目录，前缀匹配会把整张表判成非法）；
     * 其余条目（`CONTROL_ROOT`、[INSTALLER_ROOT]）按前缀匹配，即"该子树整体受保护"。
     */
    fun isNeverMove(livePath: String): Boolean =
        livePath == ADB_ROOT ||
            NEVER_MOVE.any { it != ADB_ROOT && (livePath == it || livePath.startsWith("$it/")) }

    /**
     * `livePath` 之下**永不搬移**的后代（例如 `/data/adb/maplesu` 之下的 `installer` 锁目录）。
     *
     * 非空 ⇒ 该条目**不能**整树 rename（否则会把受保护子树一起搬走），执行体必须改成
     * "逐子项搬移 + 保留这些子树"的合并式搬移（见 `RootScriptInstallConfigMover`）。
     * 空列表 ⇒ 常规整树搬移（既有语义）。
     */
    fun preservedPathsUnder(livePath: String): List<String> {
        val prefix = livePath.trimEnd('/') + "/"
        return NEVER_MOVE.filter { it != ADB_ROOT && it.startsWith(prefix) }.sorted()
    }

    /**
     * 入（store → live）执行顺序：modules 实体 → modules_update → metamodule 指针 → 其它。
     * 主文档:675 只规定"modules 先于 metamodule"；此处把它固化为可测排序。
     */
    fun inboundOrder(entries: List<MoveEntry>): List<MoveEntry> =
        entries.sortedWith(compareBy({ inboundRank(it.livePath) }, { it.livePath }))

    /** 出（live → store）执行顺序 = 入的逆序（先移指针再移实体）。 */
    fun outboundOrder(entries: List<MoveEntry>): List<MoveEntry> =
        inboundOrder(entries).reversed()

    private fun inboundRank(path: String): Int = when (path) {
        ORDER_MODULES -> 0
        ORDER_MODULES_UPDATE -> 1
        ORDER_METAMODULE -> 2
        else -> 3
    }

    /**
     * 动态项（不写进常量表，运行时展开）：A 的凭据发布目录、Ultra SDK 目录、已登记外置配置。
     */
    fun dynamicEntries(
        credentialDirectories: List<String> = emptyList(),
        ultraDirectories: List<String> = emptyList(),
        registeredExternal: List<String> = emptyList(),
        owner: WorkMode = WorkMode.LITE,
    ): List<MoveEntry> = buildList {
        credentialDirectories.forEach { path ->
            add(MoveEntry(path, MoveKind.TREE, MoveMount.SAME_AS_STORE, owner, MoveEntry.REAL_DIR))
        }
        ultraDirectories.forEach { path ->
            add(MoveEntry(path, MoveKind.TREE, MoveMount.SAME_AS_STORE, owner, MoveEntry.REAL_DIR))
        }
        registeredExternal.forEach { path ->
            val mount = if (path.startsWith("/metadata/")) MoveMount.CROSS_MOUNT_COPY else MoveMount.SAME_AS_STORE
            add(MoveEntry(path, MoveKind.TREE, mount, owner, MoveEntry.REAL_DIR))
        }
    }

    /**
     * 组装一次切换的移动计划（出 = from 的 live → store；入 = store → to 的 live）。
     * [dynamicFrom]/[dynamicTo] 由调用方按实际存在的凭据/Ultra 目录展开。
     */
    fun plan(
        switchId: String,
        from: WorkMode,
        to: WorkMode,
        owners: Map<String, WorkMode> = emptyMap(),
        dynamicFrom: List<MoveEntry> = emptyList(),
        dynamicTo: List<MoveEntry> = emptyList(),
    ): MovePlan = MovePlan(
        switchId = switchId,
        from = from,
        to = to,
        outbound = outboundOrder(moveSetFor(from) + dynamicFrom),
        inbound = inboundOrder(moveSetFor(to) + dynamicTo),
        owners = owners,
    )

    const val ORDER_MODULES = "/data/adb/modules"
    const val ORDER_MODULES_UPDATE = "/data/adb/modules_update"
    const val ORDER_METAMODULE = "/data/adb/metamodule"

    /** Root-key and credential-derived directory names belong in the plan, never in diagnostics. */
    fun redactEvidence(value: String): String = PRIVATE_DATA_DIRECTORY.replace(value, "/data/<private>")

    private val PRIVATE_DATA_DIRECTORY = Regex("""[/\\]data[/\\][A-Za-z0-9_-]{16}(?=$|[^A-Za-z0-9_-])""")
}
