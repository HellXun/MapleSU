package dev.maplesu.manager.recovery

import dev.maplesu.manager.workmode.WorkMode

/** 隐藏/WebUI/卸载恢复的统一后端（A=Lite+Ultra 环境；B=KSU）。 */
enum class HiddenMode { A_LITE_ULTRA_ENV, B_KSU }

sealed interface BackendStepResult {
    data class Ok(val step: String, val detail: String = "") : BackendStepResult
    data class Failed(val step: String, val code: String, val detail: String = "") : BackendStepResult

    val ok: Boolean get() = this is Ok
}

/**
 * 两模式共享的能力抽象：实现各自 root/隐藏/模块/APK 操作；事务与 daemon/恢复步骤共用。
 * 模式隔离是硬约束：A 实现只写 `/data/adb/maplesu/` 下文件与 Ultra，B 只走 KSU 与 `/data/adb/modules`。
 */
interface HiddenModeBackend {
    val mode: HiddenMode

    /** 该后端唯一 root 通道（A：Ultra run_root_cmd；B：KSU su/ksud）。 */
    fun rootExec(script: String): RecoveryCommandResult

    /** 写/读隐藏状态（A：lite_hidden；B：KSU stealth + lite_hidden 兼容位）。 */
    fun writeHidden(enabled: Boolean): BackendStepResult

    fun currentHidden(): Boolean?

    /** 安装开机钩子模块（A：Ultra 模块；B：KSU 模块）。 */
    fun installBootHook(moduleZip: ByteArray, moduleId: String): BackendStepResult

    fun verifyBootHook(moduleId: String): BackendStepResult

    /** 卸载/重装管理器（A：Ultra 环境 pm；B：KSU 权限 pm/ksud）。 */
    fun uninstallManager(): BackendStepResult

    fun installManagerApk(apkPath: String): BackendStepResult

    /** 开机持久性证据（模块日志 + daemon 监听 + pid）。 */
    fun bootPersistence(): BootHookEvidence?
}

class UltraEnvBackend(
    private val root: (String) -> RecoveryCommandResult,
    private val moduleInstaller: BootHookInstaller? = null,
) : HiddenModeBackend {
    override val mode: HiddenMode = HiddenMode.A_LITE_ULTRA_ENV

    override fun rootExec(script: String): RecoveryCommandResult = root(script)

    override fun writeHidden(enabled: Boolean): BackendStepResult {
        val value = if (enabled) "1" else "0"
        val result = root(
            "printf '$value' > /data/adb/maplesu/lite_hidden && chmod 600 /data/adb/maplesu/lite_hidden && echo LITE_OK",
        )
        return if (result.code == 0 && result.output.contains("LITE_OK")) {
            BackendStepResult.Ok("writeHidden")
        } else {
            BackendStepResult.Failed("writeHidden", "HIDDEN_WRITE_FAILED", result.output.trim())
        }
    }

    override fun currentHidden(): Boolean? = readHidden(root)

    override fun installBootHook(moduleZip: ByteArray, moduleId: String): BackendStepResult {
        val installer = moduleInstaller
            ?: return BackendStepResult.Failed(
                "installBootHook",
                "ULTRA_MODULE_INSTALL_NOT_WIRED",
                "Ultra SDK install_module 适配未接线（脚本-only zip 无 libmodule 描述符）",
            )
        return when (val result = installer.install(moduleZip, moduleId)) {
            is BootHookInstallResult.Ok -> BackendStepResult.Ok("installBootHook", result.moduleId)
            is BootHookInstallResult.Failed -> BackendStepResult.Failed(
                "installBootHook", result.code, result.detail,
            )
        }
    }

    override fun verifyBootHook(moduleId: String): BackendStepResult {
        val installer = moduleInstaller
            ?: return BackendStepResult.Failed("verifyBootHook", "ULTRA_MODULE_VERIFY_NOT_WIRED")
        return when (val result = installer.verify(moduleId)) {
            is BootHookVerifyResult.Installed -> BackendStepResult.Ok("verifyBootHook", result.detail)
            is BootHookVerifyResult.Missing -> BackendStepResult.Failed(
                "verifyBootHook", result.code, result.detail,
            )
        }
    }

    override fun uninstallManager(): BackendStepResult = uninstallViaPm(root)

    override fun installManagerApk(apkPath: String): BackendStepResult = installViaPm(root, apkPath)

    override fun bootPersistence(): BootHookEvidence? = readBootEvidence(root)
}

class KsuBackend(
    private val root: (String) -> RecoveryCommandResult,
    private val moduleInstaller: BootHookInstaller? = null,
) : HiddenModeBackend {
    override val mode: HiddenMode = HiddenMode.B_KSU

    override fun rootExec(script: String): RecoveryCommandResult = root(script)

    override fun writeHidden(enabled: Boolean): BackendStepResult {
        // B：KSU stealth ioctl 由上层 `Stealth.setEnabled` 执行；此层只维护兼容状态位。
        val value = if (enabled) "1" else "0"
        val result = root(
            "printf '$value' > /data/adb/maplesu/lite_hidden && chmod 600 /data/adb/maplesu/lite_hidden && echo LITE_OK",
        )
        return if (result.code == 0 && result.output.contains("LITE_OK")) {
            BackendStepResult.Ok("writeHidden")
        } else {
            BackendStepResult.Failed("writeHidden", "HIDDEN_WRITE_FAILED", result.output.trim())
        }
    }

    override fun currentHidden(): Boolean? = readHidden(root)

    override fun installBootHook(moduleZip: ByteArray, moduleId: String): BackendStepResult {
        val installer = moduleInstaller
            ?: return BackendStepResult.Failed("installBootHook", "KSU_MODULE_INSTALL_NOT_WIRED")
        return when (val result = installer.install(moduleZip, moduleId)) {
            is BootHookInstallResult.Ok -> BackendStepResult.Ok("installBootHook", result.moduleId)
            is BootHookInstallResult.Failed -> BackendStepResult.Failed(
                "installBootHook", result.code, result.detail,
            )
        }
    }

    override fun verifyBootHook(moduleId: String): BackendStepResult {
        val installer = moduleInstaller
            ?: return BackendStepResult.Failed("verifyBootHook", "KSU_MODULE_VERIFY_NOT_WIRED")
        return when (val result = installer.verify(moduleId)) {
            is BootHookVerifyResult.Installed -> BackendStepResult.Ok("verifyBootHook", result.detail)
            is BootHookVerifyResult.Missing -> BackendStepResult.Failed(
                "verifyBootHook", result.code, result.detail,
            )
        }
    }

    override fun uninstallManager(): BackendStepResult = uninstallViaPm(root)

    override fun installManagerApk(apkPath: String): BackendStepResult = installViaPm(root, apkPath)

    override fun bootPersistence(): BootHookEvidence? = readBootEvidence(root)
}

private fun readHidden(root: (String) -> RecoveryCommandResult): Boolean? {
    val result = root("cat /data/adb/maplesu/lite_hidden 2>/dev/null")
    if (result.code != 0) return null
    return when (result.output.trim()) {
        "1" -> true
        "0" -> false
        else -> null
    }
}

private fun uninstallViaPm(root: (String) -> RecoveryCommandResult): BackendStepResult {
    val result = root("pm uninstall -k dev.maplesu.manager")
    return if (result.code == 0 && result.output.contains("Success")) {
        BackendStepResult.Ok("uninstallManager")
    } else {
        BackendStepResult.Failed("uninstallManager", "UNINSTALL_FAILED", result.output.trim())
    }
}

private fun installViaPm(root: (String) -> RecoveryCommandResult, apkPath: String): BackendStepResult {
    // pm install 读不到 /data/adb（adb_data_file）→ 先搬 /data/local/tmp，装完删。
    val staged = stageApkForInstall(root, apkPath)
        ?: return BackendStepResult.Failed("installManagerApk", RestoreCodes.APK_STAGE_FAILED, apkPath)
    val result = installStagedApk(root, staged)
    return if (result.code == 0 && result.output.contains("Success")) {
        BackendStepResult.Ok("installManagerApk")
    } else {
        BackendStepResult.Failed("installManagerApk", "INSTALL_FAILED", result.output.trim())
    }
}

private fun readBootEvidence(root: (String) -> RecoveryCommandResult): BootHookEvidence? {
    val result = root("cat /data/adb/maplesu/boot-evidence.json 2>/dev/null")
    if (result.code != 0 || result.output.isBlank()) return null
    val text = result.output
    fun bool(key: String): Boolean = Regex("\"$key\"\\s*:\\s*(true|false)").find(text)
        ?.groupValues?.get(1) == "true"
    fun string(key: String): String = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(text)
        ?.groupValues?.get(1).orEmpty()
    fun int(key: String): Int? = Regex("\"$key\"\\s*:\\s*(\\d+)").find(text)
        ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    return BootHookEvidence(
        bootId = string("bootId"),
        recordedAt = int("recordedAt")?.toLong() ?: 0L,
        daemonPid = int("daemonPid"),
        port = int("port"),
        moduleLog = string("moduleLog"),
        serviceScriptRan = bool("serviceScriptRan"),
        daemonListening = bool("daemonListening"),
    )
}

object HiddenModeBackends {
    fun select(
        mode: WorkMode?,
        ultraRoot: (String) -> RecoveryCommandResult,
        ksuRoot: (String) -> RecoveryCommandResult,
        ultraModuleInstaller: BootHookInstaller? = null,
        ksuModuleInstaller: BootHookInstaller? = null,
    ): HiddenModeBackend? = when (mode) {
        WorkMode.LITE -> UltraEnvBackend(ultraRoot, ultraModuleInstaller)
        null -> null
    }
}
