package dev.maplesu.manager.workmode.migration

import dev.maplesu.manager.ModuleArchiveInspector
import dev.maplesu.manager.modules.LiteScriptModulePolicy
import dev.maplesu.manager.workmode.WorkMode
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 模块迁移（05 §5.2 / §5.3）。
 *
 * - 目标 KSU：KSU 模块直接恢复（同布局）；Magisk 模块恢复实体并打 disable（离线/禁用）;
 * - 目标 Lite：**逐模块预检**（[ModuleArchiveInspector] + [LiteScriptModulePolicy]）→
 *   通过即自动导入；不支持/失败**逐项报告且不阻断迁移完成**（主文档:1244 / D19）。
 *
 * 本类只做主机可测的预检与决策；真正 install 由 [ModuleInstallPort]（设备侧接线）执行，
 * 主机测试注入 fake 验证三态。
 */
enum class ModulePreflightState { READY, UNSUPPORTED, FAILED }

data class ModuleInstallOutcome(val code: Int, val needsReboot: Boolean = false, val message: String = "")

/** 设备侧安装端口（LiteScriptModuleBackend.install 的薄适配）。 */
fun interface ModuleInstallPort {
    fun install(archive: File, report: ModuleArchiveInspector.Report): ModuleInstallOutcome
}

data class ModuleMigrationResult(
    val items: List<ModulePlanItem>,
    val target: WorkMode,
) {
    val importedCount: Int get() = items.count { it.imported }
    val skippedCount: Int get() = items.count { !it.imported }
    val unsupportedCount: Int get() = items.count { it.decision == ModuleDecision.SKIP_UNSUPPORTED }
    val failedCount: Int get() = items.count { it.decision == ModuleDecision.SKIP_FAILED }

    /** 逐项报告结构（M9 report.json / UI 展开）。 */
    fun toReport(): List<Map<String, String>> = items.map { item ->
        linkedMapOf(
            "id" to item.moduleId,
            "origin" to item.origin.name,
            "decision" to item.decision.name,
            "reason" to item.reason,
            "installCode" to (item.installCode?.toString() ?: ""),
            "sourceDisabled" to item.sourceDisabled.toString(),
        )
    }
}

class ModuleMigrator(
    /** 工作目录：<migration>/backup/modules 或临时目录。 */
    private val workRoot: File,
    private val installPort: ModuleInstallPort? = null,
) {

    private val archiveDir: File = File(workRoot, "archives")

    fun migrate(candidates: List<ModuleCandidate>, target: WorkMode): ModuleMigrationResult {
        if (archiveDir.exists()) archiveDir.deleteRecursively()
        archiveDir.mkdirs()
        val items = candidates.map { candidate -> migrateOne(candidate, target) }
        return ModuleMigrationResult(items, target)
    }

    private fun migrateOne(candidate: ModuleCandidate, target: WorkMode): ModulePlanItem {
        val base = ModulePlanItem(
            moduleId = candidate.moduleId,
            origin = candidate.origin,
            sourceDir = candidate.sourceDir.path,
            decision = ModuleDecision.SKIP_UNSUPPORTED,
            sourceDisabled = candidate.disabled,
        )
        return when (target) {

            WorkMode.LITE -> importIntoLite(candidate, base)
        }
    }

    private fun importIntoLite(candidate: ModuleCandidate, base: ModulePlanItem): ModulePlanItem {
        // 1) 目录 → 确定性 zip（预检只接受仓库，不接受任意解包目录）
        val archive = File(archiveDir, "${safeFileName(candidate.moduleId)}.zip")
        try {
            ModuleRepacker.repack(candidate.sourceDir, archive)
        } catch (failure: MigrationFailure) {
            return base.copy(decision = ModuleDecision.SKIP_UNSUPPORTED, reason = failure.code)
        }

        // 2) 只读预检（soft/reject 由 inspector 决定）
        val report = try {
            ModuleArchiveInspector.inspect(archive)
        } catch (failure: ModuleArchiveInspector.Failure) {
            return base.copy(
                decision = ModuleDecision.SKIP_UNSUPPORTED,
                reason = mapInspectorCode(failure.code),
            )
        } catch (failure: Exception) {
            return base.copy(decision = ModuleDecision.SKIP_UNSUPPORTED, reason = MigrationCodes.MODULE_ARCHIVE_INVALID)
        }

        if (report.type != ModuleArchiveInspector.ModuleType.KERNEL_SU) {
            return base.copy(
                decision = ModuleDecision.SKIP_UNSUPPORTED,
                reason = MigrationCodes.MODULE_TYPE_UNSUPPORTED,
            )
        }

        // 3) 策略/脚本预检。抛错即"不支持"，**不执行**任何模块脚本。
        val scripts = ModuleRepacker.scriptsOf(candidate.sourceDir)
        try {
            LiteScriptModulePolicy.check(report, scripts)
        } catch (failure: IllegalStateException) {
            return base.copy(
                decision = ModuleDecision.SKIP_UNSUPPORTED,
                reason = failure.message ?: "MODULE_POLICY_REJECTED",
            )
        } catch (failure: IllegalArgumentException) {
            return base.copy(
                decision = ModuleDecision.SKIP_UNSUPPORTED,
                reason = failure.message ?: "MODULE_POLICY_REJECTED",
            )
        }

        // 4) 逐模块串行导入（设备侧 port；主机测试注入 fake）
        val archiveSha = MigrationHashes.sha256(archive)
        val port = installPort
            ?: return base.copy(
                decision = ModuleDecision.IMPORT_LITE,
                archiveSha256 = archiveSha,
            )
        return try {
            val outcome = port.install(archive, report)
            if (outcome.code == 0) {
                base.copy(
                    decision = ModuleDecision.IMPORT_LITE,
                    archiveSha256 = archiveSha,
                    installCode = outcome.code,
                    needsReboot = outcome.needsReboot,
                )
            } else {
                base.copy(
                    decision = ModuleDecision.SKIP_FAILED,
                    archiveSha256 = archiveSha,
                    installCode = outcome.code,
                    reason = outcome.message.ifBlank { "MODULE_INSTALL_FAILED" },
                )
            }
        } catch (failure: Exception) {
            base.copy(
                decision = ModuleDecision.SKIP_FAILED,
                archiveSha256 = archiveSha,
                reason = failure.message ?: "MODULE_INSTALL_FAILED",
            )
        }
    }

    private fun mapInspectorCode(code: String): String = when (code) {
        "UNSUPPORTED_ARCHIVE_ENTRY" -> MigrationCodes.MODULE_CONTAINS_SYMLINK
        "ARCHIVE_PATH_CONFLICT" -> MigrationCodes.MODULE_PATH_INVALID
        "INVALID_MODULE_ID" -> MigrationCodes.MODULE_ID_INVALID
        "INVALID_MODULE_METADATA", "MISSING_MODULE_METADATA" -> MigrationCodes.MODULE_METADATA_MISSING
        "DUPLICATE_ARCHIVE_PATH" -> MigrationCodes.MODULE_PATH_INVALID
        else -> code
    }

    private fun safeFileName(moduleId: String): String {
        val safe = moduleId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (safe.isEmpty()) "module" else safe
    }
}

/**
 * 模块目录 → 确定性 zip（05 §5.3 `ModuleRepacker`）。
 *
 * 约束逐条对应 `ModuleArchiveInspector` 的拒绝条件：软链拒绝、路径合法性、无重复路径、
 * 条目排序 + mtime 归零（可复现，便于测试与哈希对比）。
 */
object ModuleRepacker {
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    private const val MAX_FILE_BYTES = 128L * 1024 * 1024
    private const val MAX_ENTRIES = 20_000

    /** 与 ModuleArchiveInspector.SCRIPTS 对齐（预检输入只需读根目录脚本）。 */
    val SCRIPT_NAMES: Set<String> = setOf(
        "customize.sh", "install.sh", "uninstall.sh", "post-fs-data.sh", "post-mount.sh",
        "service.sh", "boot-completed.sh", "action.sh", "metainstall.sh",
    )

    fun repack(moduleDir: File, destination: File): File {
        MigrationFailure.require(
            moduleDir.isDirectory && !Files.isSymbolicLink(moduleDir.toPath()),
            MigrationCodes.MODULE_PATH_INVALID,
            moduleDir.path,
        )
        val files = mutableListOf<Pair<String, File>>()
        val directories = mutableListOf<String>()
        collect(moduleDir, "", files, directories)
        val total = files.sumOf { it.second.length() }
        MigrationFailure.require(total <= MAX_TOTAL_BYTES, MigrationCodes.MODULE_ARCHIVE_INVALID, "expanded")
        MigrationFailure.require(files.size + directories.size <= MAX_ENTRIES, MigrationCodes.MODULE_ARCHIVE_INVALID, "entries")
        files.sortedBy { it.first }.forEach { (_, file) ->
            MigrationFailure.require(
                file.length() <= MAX_FILE_BYTES,
                MigrationCodes.MODULE_ARCHIVE_INVALID,
                "entry",
            )
        }
        destination.parentFile?.mkdirs()
        ZipOutputStream(destination.outputStream()).use { zip ->
            directories.sorted().forEach { name ->
                zip.putNextEntry(ZipEntry("$name/").apply { time = 0L })
                zip.closeEntry()
            }
            files.sortedBy { it.first }.forEach { (name, file) ->
                val entry = ZipEntry(name).apply { time = 0L }
                zip.putNextEntry(entry)
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        MigrationFailure.require(
            destination.isFile && destination.length() > 0,
            MigrationCodes.MODULE_REPACK_FAILED,
            destination.name,
        )
        return destination
    }

    private fun collect(
        dir: File,
        prefix: String,
        files: MutableList<Pair<String, File>>,
        directories: MutableList<String>,
    ) {
        val children = dir.listFiles() ?: emptyArray()
        for (child in children.sortedBy { it.name }) {
            MigrationFailure.require(
                !Files.isSymbolicLink(child.toPath()) && !Files.isSymbolicLink(dir.toPath()),
                MigrationCodes.MODULE_CONTAINS_SYMLINK,
                "${prefix}${child.name}",
            )
            val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            MigrationFailure.require(
                isSafeEntryName(relative),
                MigrationCodes.MODULE_PATH_INVALID,
                relative,
            )
            if (child.isDirectory) {
                directories += relative
                collect(child, relative, files, directories)
            } else if (child.isFile) {
                MigrationFailure.require(
                    relative.none { it.code < 0x20 },
                    MigrationCodes.MODULE_PATH_INVALID,
                    relative,
                )
                files += relative to child
            } else {
                throw MigrationFailure(MigrationCodes.MODULE_CONTAINS_SYMLINK, relative)
            }
        }
    }

    fun isSafeEntryName(name: String): Boolean {
        if (name.isEmpty() || name.startsWith("/") || name.contains('\\') || name.contains("//")) return false
        if (name.any { it.code < 0x20 || it == ':' }) return false
        return name.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    /** 读根目录脚本原文（policy 只接受 map，不做路径发现）。 */
    fun scriptsOf(moduleDir: File): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (name in SCRIPT_NAMES) {
            val file = File(moduleDir, name)
            if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() > 1024L * 1024L) continue
            result[name] = runCatching { file.readText() }.getOrElse { continue }
        }
        return result
    }
}
