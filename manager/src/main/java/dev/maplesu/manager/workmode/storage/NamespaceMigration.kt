package dev.maplesu.manager.workmode.storage

import android.content.Context
import dev.maplesu.manager.workmode.WorkMode
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * 一次性命名空间迁移：首次启用双模式时把"无模式维度"的旧文件归位。
 *
 * 规则（与 01 §3.2 的精确路径表对齐，**A 保持原名**）：
 * - `settings`：B 需要 `settings_B`；A 保持 `settings`；
 * - `installer`：仅 B 需要 `installer_B`；
 * - A-only prefs（maplesu-ultra/ultra-auto-setup/su_entry_mode/runtime_deployment）**不迁移**、不改名；
 * - 复制而不是移动：源保留，另存 `<name>.legacy` 供回退（05:641）；
 * - `filesDir` 下 A 实体**不移动**；current==KSU 且 `filesDir/ksu/` 不存在时只建空目录；
 * - 任一步失败：保留原文件、报告 failed、**不翻转** work_mode；
 * - 幂等：目标已存在 → 跳过并记 skipped（SKIPPED_EXISTS）。
 *
 * 偏差说明：01 §3.3 步骤 3 提到把 A-only prefs 迁到 `*_A`，但 §3.2 的"实现常量唯一来源"
 * 表明确 A 用原名（无 `_A` 后缀）。本实现以 §3.2 为准（否则 A 会读不到自己的旧数据）。
 */
interface NamespaceMigration {
    fun needsMigration(context: Context): Boolean
    fun run(context: Context, current: WorkMode): MigrationReport
}

data class MigrationReport(
    /** 已成功写入的目标文件（prefs 文件名，不含 .xml）。 */
    val copied: List<String>,
    /** 目标已存在（SKIPPED_EXISTS）。 */
    val skipped: List<String>,
    /** 保留原文件，不进入半状态。 */
    val failed: List<String>,
    /** "*.legacy"。 */
    val legacyKept: List<String>,
) {
    val ok: Boolean get() = failed.isEmpty()
}

/**
 * 文件系统实现（主机可测）：[copier] 可注入用于故障注入；
 * Android 侧由 [AndroidNamespaceMigration] 组装 prefsDir/filesDir。
 */
class FileNamespaceMigration(
    private val prefsDir: File,
    private val filesDir: File,
    copier: ((File, File) -> Unit)? = null,
) {
    private val copier: (File, File) -> Unit = copier ?: { source, target -> copyFile(source, target) }

    private fun prefsFile(name: String) = File(prefsDir, "$name.xml")

    private fun legacyFile(name: String) = File(prefsDir, "$name.legacy")

    /** 是否仍处于"单命名空间旧布局"。 */
    fun needsMigration(): Boolean {
        if (!prefsFile("settings").isFile) return false
        if (prefsFile("settings_B").exists()) return false
        return !legacyFile("settings").exists()
    }

    fun run(current: WorkMode): MigrationReport {
        val copied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val legacyKept = mutableListOf<String>()

        val settings = prefsFile("settings")
        if (!settings.isFile) {
            return MigrationReport(copied, skipped, failed, legacyKept)
        }

        // 1) 回退副本：始终保留 legacy（幂等）。
        val legacy = legacyFile("settings")
        if (legacy.exists()) {
            skipped += legacy.name
            legacyKept += legacy.name
        } else {
            val result = copySafely(settings, legacy, copier)
            if (result) {
                copied += legacy.name
                legacyKept += legacy.name
            } else {
                failed += legacy.name
            }
        }

        // 2) B 命名空间：settings → settings_B（仅当前模式为 B；A 保持原名）。
        if (false) {
            val settingsB = prefsFile("settings_B")
            when {
                settingsB.exists() -> skipped += settingsB.name
                copySafely(settings, settingsB, copier) -> copied += settingsB.name
                else -> failed += settingsB.name
            }
            val installer = prefsFile("installer")
            if (installer.isFile) {
                val installerB = prefsFile("installer_B")
                when {
                    installerB.exists() -> skipped += installerB.name
                    copySafely(installer, installerB, copier) -> copied += installerB.name
                    else -> failed += installerB.name
                }
            }
            // 只建空目录，不复制 A 的图片/模块内容（D9）。
            val ksuDir = File(filesDir, ModePaths.KSU_FILES_ROOT)
            if (!ksuDir.exists() && !ksuDir.mkdirs()) failed += "${ModePaths.KSU_FILES_ROOT}/"
        }

        return MigrationReport(
            copied = copied.distinct().sorted(),
            skipped = skipped.distinct().sorted(),
            failed = failed.distinct().sorted(),
            legacyKept = legacyKept.distinct().sorted(),
        )
    }

    private fun copySafely(source: File, target: File, copier: (File, File) -> Unit): Boolean =
        runCatching { copier(source, target) }.isSuccess

    private fun copyFile(source: File, target: File) {
        if (!source.isFile) throw IOException("MIGRATION_SOURCE_INVALID")
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
        if (!target.isFile || target.length() != source.length()) {
            target.delete()
            throw IOException("MIGRATION_COPY_FAILED")
        }
    }

    companion object {
        /** `context` 侧的标准组装（prefsDir = /data/user/0/<pkg>/shared_prefs）。 */
        fun forLegacyLayout(prefsDir: File, filesDir: File): FileNamespaceMigration =
            FileNamespaceMigration(prefsDir, filesDir)
    }
}

/** Android 门面：只负责解析目录，不做业务判断。 */
class AndroidNamespaceMigration(private val context: Context) : NamespaceMigration {
    private val delegate: FileNamespaceMigration by lazy {
        val dataDir = File(context.applicationInfo.dataDir)
        FileNamespaceMigration(File(dataDir, "shared_prefs"), context.filesDir)
    }

    override fun needsMigration(context: Context): Boolean = delegate.needsMigration()

    override fun run(context: Context, current: WorkMode): MigrationReport = delegate.run(current)
}
