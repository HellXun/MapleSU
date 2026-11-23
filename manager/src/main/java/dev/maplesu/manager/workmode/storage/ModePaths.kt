package dev.maplesu.manager.workmode.storage

import dev.maplesu.manager.workmode.WorkMode

/**
 * A/B 两套命名空间的纯函数映射：无 Android import，主机表驱动测试。
 *
 * 不变量（D9）：A 保留现有 prefs/files 路径（**不改名、不搬家**）；B 用独立 prefs 后缀 +
 * `filesDir/ksu/`。兼容模块实体使用标准 KSU 路径，归档及回执保持独立。
 */
object ModePaths {
    const val NEUTRAL_PREFS = "workmode_state"
    const val KEY_ACTIVE_MODE = "active_mode"
    const val KEY_ACTIVE_BOOT_ID = "active_boot_id"
    const val KEY_SCHEMA = "schema"

    /** B 的 filesDir 子根。 */
    const val KSU_FILES_ROOT = "ksu"

    /** KSU-compatible executable trees use the standard root in either mode.
     * Ultra archives, receipts and preferences remain app-private and mode-scoped.
     * Ultra also discovers existing KSU trees through root metadata and markers.
     */
    const val KSU_MODULE_ROOT = "/data/adb/modules"

    /** Ultra private runtime metadata and legacy revision storage. */
    const val LITE_MODULE_ROOT_SUBDIR = "module-runtime"

    /** Lite 的模块目录（catalog）：`filesDir/<此子目录>`。 */
    const val LITE_MODULE_CATALOG_SUBDIR = "module-catalog"

    /** 模块树归属：谁拥有、在哪、是否与另一模式共享。 */
    enum class ModuleOwner {
        /** Ultra/Lite owns catalog entries, not the entire standard module root. */
        LITE,

        /** KernelSU/ksud 独占（`/data/adb/modules`，经 root shell 由 ksud 读）。 */
        KSU,
    }

    data class ModuleRoot(
        val owner: ModuleOwner,
        val path: String,
        /**
         * Executable KSU paths are shared; archives, receipts and settings stay isolated.
         */
        val sharedWithOtherMode: Boolean = false,
    )

    /**
     * 当前模式的模块根。`mode == null`（未判定）→ `null`：**调用方必须显式失败**，
     * 不得回落到任一模式的目录（01 §1.2、04 §6.1）。
     */
    fun moduleRoot(mode: WorkMode?, filesDir: String): ModuleRoot? = when (mode) {
        null -> null
        WorkMode.LITE -> ModuleRoot(
            owner = ModuleOwner.LITE,
            path = KSU_MODULE_ROOT,
            sharedWithOtherMode = true,
        )
    }

    /**
     * 该模式是否允许读 [absolutePath] 下的模块树。
     *
     * Ultra reads the standard executable tree through root, plus its private metadata.
     * Normal reads the standard tree through ksud, without reading Ultra metadata.
     * 未判定模式 → false（两个方向都拒绝）。
     */
    fun allowsModulePath(mode: WorkMode?, absolutePath: String, filesDir: String): Boolean {
        val path = absolutePath.trimEnd('/')
        return when (mode) {
            null -> false
            WorkMode.LITE -> {
                val root = "$filesDir/$LITE_MODULE_ROOT_SUBDIR".trimEnd('/')
                val catalog = "$filesDir/$LITE_MODULE_CATALOG_SUBDIR".trimEnd('/')
                path == KSU_MODULE_ROOT || path.startsWith("$KSU_MODULE_ROOT/") ||
                    path == root || path.startsWith("$root/") ||
                    path == catalog || path.startsWith("$catalog/")
            }
        }
    }

    /** prefs 文件名：A 保持原名，B 加 `_B`（D9）。 */
    fun prefsName(legacyName: String, mode: WorkMode): String =
        if (mode == WorkMode.LITE) legacyName else "${legacyName}_${mode.suffix}"

    /** filesDir 子目录：A 保持原路径，B 挂到 filesDir/ksu/ 下（D9、主文档:326）。 */
    fun filesSubdir(sub: String, mode: WorkMode): String =
        if (mode == WorkMode.LITE) sub else "$KSU_FILES_ROOT/$sub"

    fun prefsFile(prefsDir: String, legacyName: String, mode: WorkMode): String =
        "$prefsDir/${prefsName(legacyName, mode)}.xml"

    fun filesDir(filesDir: String, sub: String, mode: WorkMode): String =
        "$filesDir/${filesSubdir(sub, mode)}"

    /** 一个 prefs 文件在两种模式下的名字；[bOnly] 表示 B 不使用（A-only 文件）。 */
    data class PrefsNamespace(
        val legacyName: String,
        val liteName: String?,
        val ksuName: String?,
        val neutral: Boolean = false,
    ) {
        fun nameFor(mode: WorkMode): String? = when (mode) {
            WorkMode.LITE -> liteName
        }
    }

    /** 01 §3.2 的完整 prefs 表（实现常量唯一来源）。 */
    val PREFS_TABLE: List<PrefsNamespace> = listOf(
        PrefsNamespace("settings", "settings", "settings_B"),
        PrefsNamespace("maplesu-ultra", "maplesu-ultra", null),
        PrefsNamespace("ultra-auto-setup", "ultra-auto-setup", null),
        PrefsNamespace("su_entry_mode", "su_entry_mode", null),
        PrefsNamespace("runtime_deployment", "runtime_deployment", null),
        PrefsNamespace("installer", "installer", "installer_B"),
        PrefsNamespace("migration_notice", "migration_notice", "migration_notice", neutral = true),
        PrefsNamespace(NEUTRAL_PREFS, NEUTRAL_PREFS, NEUTRAL_PREFS, neutral = true),
    )

    /**
     * 01 §3.2 的 prefs 表查询：表内条目以表为准（B 不使用 → null，**禁止**回落成 `<name>_B`）；
     * 表外名字才按 [prefsName] 规则派生（向后兼容未来新增 prefs）。
     */
    fun prefsNameFor(legacyName: String, mode: WorkMode): String? {
        val entry = PREFS_TABLE.firstOrNull { it.legacyName == legacyName }
        return if (entry != null) entry.nameFor(mode) else prefsName(legacyName, mode)
    }

    /** A-only prefs（B 不使用，切换时保持原名不动）。 */
    val A_ONLY_PREFS: List<String> = PREFS_TABLE.filter { it.liteName != null && it.ksuName == null }
        .map { it.legacyName }

    /** 中性 prefs（与模式无关）。 */
    val NEUTRAL_PREFS_NAMES: List<String> = PREFS_TABLE.filter { it.neutral }.map { it.legacyName }

    /** 01 §3.2 filesDir 表：每个 A 实体在 B 下挂到 `filesDir/ksu/<同名>`。 */
    val FILES_SUBDIRS: List<String> = listOf(
        "images", "module-catalog", "module-runtime", "module_icons", "module_cards",
        "nav_icons", "wallpaper", "wallpaper_land", "published-directory", "jailbreak",
    )

    /** 中性文件/目录（诊断/缓存与模式无关，05:637）。 */
    val NEUTRAL_FILES: List<String> = listOf(
        "module_registry", "last_crash.txt", ".session_running",
    )

    /** A-only 的 noBackup 文件（不复制给 B，05:635）。 */
    val A_ONLY_NO_BACKUP: List<String> = listOf(
        "maplesu-credential.bin", dev.maplesu.manager.compat.StoredInputs.CREDENTIAL_FILE, "authorizations.bin",
        "su-daemon-policy.rule",
    )
}
