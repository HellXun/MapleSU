package dev.maplesu.manager.litehidden

/**
 * 痕迹收敛（L2）项的**白名单与安全边界**（纯函数，主机可测）。
 *
 * 原则：只清理**本 App 可再生的敏感痕迹**；**禁止**删除 root 凭证 / 内核证据 / 备份 /
 * 授权表 / 模块实体（删了不可恢复）。每项独立开关，默认关闭。
 */
data class LiteHiddenTraceSpec(
    val item: LiteHiddenTraceItem,
    /** 该痕迹是否确实存在持久化载体（false = 如实显示"无持久化痕迹"，不假装清理）。 */
    val hasPersistentArtifact: Boolean = true,
    val requiresRoot: Boolean = false,
)

object LiteHiddenTracePolicy {

    /** 允许删除的 App 私有子目录（相对 filesDir）。 */
    val ALLOWED_RELATIVE_DIRS: Set<String> = setOf(
        "diagnostics",
        "debug-exports",
        "logs",
    )

    /** logcat 中本 App 的关键 tag（清 buffer 仍为尽力而为）。 */
    val LOGCAT_TAGS: List<String> = listOf("MapleSUMode", "maplesu", "KernelSU")

    val ITEMS: List<LiteHiddenTraceSpec> = listOf(
        // 首页"最近安装"来自 PackageManager 实时计算，无持久化 JSON/日志。
        LiteHiddenTraceSpec(LiteHiddenTraceItem.RECENT_INSTALL_LOG, hasPersistentArtifact = false),
        LiteHiddenTraceSpec(LiteHiddenTraceItem.LOGCAT_TAGS, requiresRoot = true),
        LiteHiddenTraceSpec(LiteHiddenTraceItem.DEBUG_EXPORTS),
        // 当前 Manifest 只有一个 LauncherAlias（桌面图标）；"不做图标禁用" ⇒ 无可操作别名。
        LiteHiddenTraceSpec(LiteHiddenTraceItem.INTENT_ALIASES, hasPersistentArtifact = false),
        LiteHiddenTraceSpec(LiteHiddenTraceItem.BACKGROUND_WRITERS),
    )

    /** 默认全关（用户逐项 opt-in）。 */
    fun defaults(): Map<LiteHiddenTraceItem, Boolean> =
        LiteHiddenTraceItem.entries.associateWith { false }

    /** prefs 里保存的 wire 集合 → 选择表（未知项忽略）。 */
    fun selectionFromWire(values: Set<String>?): Map<LiteHiddenTraceItem, Boolean> {
        val selected = values.orEmpty().mapNotNull { LiteHiddenTraceItem.fromWire(it) }.toSet()
        return LiteHiddenTraceItem.entries.associateWith { it in selected }
    }

    fun toWire(selection: Map<LiteHiddenTraceItem, Boolean>): Set<String> =
        selection.filterValues { it }.keys.map { it.wire }.toSet()

    /** 某痕迹项是否有可清理载体（false → UI 显示"无持久化痕迹"而非假装成功）。 */
    fun hasPersistentArtifact(item: LiteHiddenTraceItem): Boolean =
        ITEMS.first { it.item == item }.hasPersistentArtifact

    fun requiresRoot(item: LiteHiddenTraceItem): Boolean =
        ITEMS.first { it.item == item }.requiresRoot

    /**
     * 是否允许删除该绝对路径：必须位于 `filesDir` 内、第一层子目录在
     * [ALLOWED_RELATIVE_DIRS] 且命中 [LiteHiddenForbidden] 任一模式即拒绝。
     */
    fun canDelete(filesRoot: String, path: String): Boolean {
        val root = filesRoot.trimEnd('/')
        val normalized = path.replace('\\', '/')
        if (!normalized.startsWith("$root/")) return false
        val relative = normalized.removePrefix("$root/")
        val first = relative.substringBefore('/', missingDelimiterValue = relative)
        if (first !in ALLOWED_RELATIVE_DIRS) return false
        val lower = normalized.lowercase()
        if (LiteHiddenForbidden.PATH_PATTERNS.any { lower.contains(it.lowercase()) }) return false
        if (LiteHiddenForbidden.NAME_PATTERNS.any { lower.contains(it.lowercase()) }) return false
        // 目录穿越防御
        if (relative.split('/').any { it == ".." }) return false
        return true
    }

    /** 明确禁止删除的路径（供测试与审计断言）。 */
    fun isForbidden(path: String): Boolean {
        val lower = path.replace('\\', '/').lowercase()
        return LiteHiddenForbidden.PATH_PATTERNS.any { lower.contains(it.lowercase()) } ||
            LiteHiddenForbidden.NAME_PATTERNS.any { lower.contains(it.lowercase()) }
    }
}
