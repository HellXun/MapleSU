package dev.maplesu.manager.modules

import dev.maplesu.manager.ModuleArchiveInspector
import dev.maplesu.manager.workmode.WorkMode
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

enum class ModuleType { MAPLESU, KERNELSU, AMBIGUOUS, UNKNOWN }

data class ModuleTypeEvidence(
    val type: ModuleType,
    val reasons: List<String> = emptyList(),
) {
    val label: String
        get() = when (type) {
            ModuleType.MAPLESU -> "MapleSU_Ultra 模块"
            ModuleType.KERNELSU -> "KernelSU 脚本模块"
            ModuleType.AMBIGUOUS -> "混合特征（需确认）"
            ModuleType.UNKNOWN -> "无法识别"
        }
}

object ModuleTypeDetector {
    private val ELF_MAGIC = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
    private val KSU_ROOT_SCRIPTS = setOf("post-fs-data.sh", "service.sh", "customize.sh")
    private val ULTRA_COMPANION_HINTS = listOf("webroot/", "service.sh", "system/", "payload/")

    fun detect(file: File): ModuleTypeEvidence =
        runCatching { detect(ModuleArchiveInspector.inspect(file)) }.getOrElse {
            val reason = (it as? ModuleArchiveInspector.Failure)?.code ?: it.javaClass.simpleName
            ModuleTypeEvidence(ModuleType.UNKNOWN, listOf("archive-check-failed:$reason"))
        }

    fun detect(report: ModuleArchiveInspector.Report): ModuleTypeEvidence = ModuleTypeEvidence(
        type = when (report.type) {
            ModuleArchiveInspector.ModuleType.MAPLESU -> ModuleType.MAPLESU
            ModuleArchiveInspector.ModuleType.KERNEL_SU -> ModuleType.KERNELSU
        },
        reasons = listOf(report.nativeEntry?.let { "ultra-descriptor-elf:$it" } ?: "module.prop") +
            report.features.map { "feature:${it.name}" },
    )

    fun detect(zipBytes: ByteArray): ModuleTypeEvidence {
        if (zipBytes.isEmpty()) return ModuleTypeEvidence(ModuleType.UNKNOWN, listOf("empty-archive"))
        val names = mutableListOf<String>()
        val ultraDescriptors = mutableListOf<String>()
        val headBytes = mutableMapOf<String, ByteArray>()
        val read = runCatching {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    if (name.isBlank()) continue
                    names += name
                    if (!entry.isDirectory && name.substringAfterLast('/').matches(Regex("libmodule_.*\\.so"))) {
                        ultraDescriptors += name
                        val head = ByteArray(4)
                        var offset = 0
                        while (offset < head.size) {
                            val count = zip.read(head, offset, head.size - offset)
                            if (count <= 0) break
                            offset += count
                        }
                        headBytes[name] = head.copyOf(offset)
                    }
                }
            }
        }
        if (read.isFailure) {
            return ModuleTypeEvidence(ModuleType.UNKNOWN, listOf("invalid-zip:${read.exceptionOrNull()?.javaClass?.simpleName}"))
        }
        if (names.isEmpty()) return ModuleTypeEvidence(ModuleType.UNKNOWN, listOf("empty-archive"))

        val reasons = mutableListOf<String>()
        val validPro = ultraDescriptors.filter { path ->
            val head = headBytes[path] ?: ByteArray(0)
            head.size >= 4 && head.copyOf(4).contentEquals(ELF_MAGIC)
        }
        // Ultra 描述符要求根级（zip 内无目录前缀）；非根级仅记录、不作为 Ultra 证据。
        val rootPro = validPro.filter { !it.contains('/') }
        if (rootPro.isNotEmpty()) reasons += "ultra-descriptor-elf:${rootPro.first()}"
        if (ultraDescriptors.isNotEmpty() && validPro.isEmpty()) reasons += "libmodule-present-but-not-elf"
        if (ultraDescriptors.isNotEmpty() && validPro.isNotEmpty() && rootPro.isEmpty()) {
            reasons += "ultra-descriptor-not-at-root"
        }
        val hasModuleProp = names.any { it == "module.prop" }
        val rootScripts = names.filter { it in KSU_ROOT_SCRIPTS }
        if (hasModuleProp) reasons += "module.prop"
        if (rootScripts.isNotEmpty()) reasons += "ksu-script:${rootScripts.first()}"
        val ultraHints = ULTRA_COMPANION_HINTS.count { hint -> names.any { it == hint || it.startsWith(hint) } }
        if (ultraHints > 0) reasons += "ultra-companions:$ultraHints"

        val isPro = rootPro.isNotEmpty()
        // Ultra 模块可合法携带 service.sh/webroot（加分项）；module.prop 与 Ultra 描述符同时出现 →
        // AMBIGUOUS（安装前必须让用户确认）。无合法 Ultra ELF 时脚本/module.prop 才是 KSU。
        val isKsu = hasModuleProp || (rootScripts.isNotEmpty() && !isPro)
        return when {
            isPro && isKsu -> ModuleTypeEvidence(ModuleType.AMBIGUOUS, reasons)
            isPro -> ModuleTypeEvidence(
                ModuleType.MAPLESU,
                reasons + listOf("companionHints=$ultraHints"),
            )
            isKsu -> ModuleTypeEvidence(ModuleType.KERNELSU, reasons)
            else -> ModuleTypeEvidence(
                ModuleType.UNKNOWN,
                reasons + listOf("no-module.prop-no-scripts-no-ultra-elf"),
            )
        }
    }
}

enum class ModuleInstallBackend { MAPLESU_ULTRA, LITE_SCRIPT, KSU_DAEMON }

data class ModuleRouting(
    val ok: Boolean,
    val backend: ModuleInstallBackend? = null,
    val code: String = "",
    val reason: String = "",
)

/** 安装路由：A 模式按类型走 Ultra/Lite 脚本；B 模式统一 KSU，Ultra 类型明确不支持。 */
object ModuleInstallRouter {
    const val CONFIRM_REQUIRED = "MODULE_TYPE_AMBIGUOUS_CONFIRM_REQUIRED"
    const val UNKNOWN_TYPE = "MODULE_TYPE_UNKNOWN_REJECTED"
    const val UNSUPPORTED_IN_MODE = "MODULE_TYPE_UNSUPPORTED_IN_MODE"
    const val MODE_UNRESOLVED = "MODE_UNRESOLVED_NO_MODULE_ROUTE"

    fun route(mode: WorkMode?, type: ModuleType, ambiguousConfirmed: Boolean = false): ModuleRouting = when {
        mode == null -> ModuleRouting(false, code = MODE_UNRESOLVED)
        type == ModuleType.UNKNOWN -> ModuleRouting(false, code = UNKNOWN_TYPE)
        type == ModuleType.AMBIGUOUS && !ambiguousConfirmed ->
            ModuleRouting(false, code = CONFIRM_REQUIRED, reason = "mixed ultra/ksu features; ask user")
        type == ModuleType.AMBIGUOUS -> ModuleRouting(false, code = CONFIRM_REQUIRED, reason = "resolve type explicitly")
        mode == WorkMode.LITE && type == ModuleType.MAPLESU ->
            ModuleRouting(true, ModuleInstallBackend.MAPLESU_ULTRA)
        mode == WorkMode.LITE && type == ModuleType.KERNELSU ->
            ModuleRouting(true, ModuleInstallBackend.LITE_SCRIPT)
        false && type == ModuleType.KERNELSU ->
            ModuleRouting(true, ModuleInstallBackend.KSU_DAEMON)
        false && type == ModuleType.MAPLESU ->
            ModuleRouting(false, code = UNSUPPORTED_IN_MODE, reason = "MapleSU_Ultra module is not supported in KSU mode")
        else -> ModuleRouting(false, code = UNKNOWN_TYPE)
    }
}

/** UI 展示用（类型 + 依据 + 目标后端）；安装前确认弹窗直接消费。 */
data class ModuleInstallPresentation(
    val type: ModuleType,
    val typeLabel: String,
    val evidence: List<String>,
    val routing: ModuleRouting,
    val requiresUserConfirmation: Boolean,
) {
    companion object {
        fun of(evidence: ModuleTypeEvidence, mode: WorkMode?): ModuleInstallPresentation {
            val routing = ModuleInstallRouter.route(mode, evidence.type)
            return ModuleInstallPresentation(
                type = evidence.type,
                typeLabel = evidence.label,
                evidence = evidence.reasons,
                routing = routing,
                requiresUserConfirmation = evidence.type == ModuleType.AMBIGUOUS,
            )
        }
    }
}
