package dev.maplesu.manager.installer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maplesu.core.ui.util.FlashResult
import com.maplesu.core.ui.util.InstallStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Flash progress for one durable installer task.
 *
 * Resumes the exact persisted task. Only the legacy PREPARED flow already owns a candidate;
 * an interrupted native preparation is cancelled so a new attempt can rebuild its candidate.
 */
internal class InstallerFlashViewModel : ViewModel() {
    data class State(
        val text: String = "",
        val result: FlashResult? = null,
        /** S5：修补中 / 写入中 / 待重启（结构化，页面不解析日志）。 */
        val stage: InstallStage? = null,
        val canRestore: Boolean = false,
    )
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var attachedTask: String? = null
    private var restoring = false

    /** S5：由执行体（`installBoot`）上报阶段；线程安全（只在 VM 的 IO 协程里调用）。 */
    fun reportStage(stage: InstallStage) {
        mutable.value = mutable.value.copy(stage = stage)
    }

    fun restore(context: Context) {
        val taskId = attachedTask ?: return
        if (!mutable.value.canRestore || mutable.value.result == null) return
        restoring = true
        mutable.value = mutable.value.copy(result = null, canRestore = false, stage = InstallStage.WRITING)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val status = InstallerServiceClient.restoreInstallation(context.applicationContext, taskId)
                publishResult(context, taskId, installerTaskResult(status.getString("state").orEmpty(),
                    status.getBoolean("rebootRequired"), status.getString("error").orEmpty(), restoring = true))
            } catch (error: Exception) {
                publishResult(context, taskId, FlashResult(1, error.message.orEmpty(), false,
                    com.maplesu.core.ui.util.FlashOutcome.RECOVERY_REQUIRED))
            }
        }
    }

    private fun publishResult(context: Context, taskId: String, result: FlashResult) {
        val canRestore = if (result.outcome in setOf(com.maplesu.core.ui.util.FlashOutcome.WRITTEN,
                com.maplesu.core.ui.util.FlashOutcome.RECOVERY_REQUIRED)) {
            runCatching { InstallerServiceClient.installationStatus(context.applicationContext, taskId)
                .getBoolean("canRestore") }.getOrDefault(false)
        } else false
        mutable.value = mutable.value.copy(result = result, canRestore = canRestore)
    }

    fun attach(context: Context, taskId: String, resume: Boolean, restore: Boolean, transactional: Boolean,
               execute: suspend ((String) -> Unit) -> FlashResult) {
        if (attachedTask == taskId) return
        check(attachedTask == null) { "INSTALLER_TASK_CONFLICT" }
        attachedTask = taskId
        restoring = restore
        viewModelScope.launch(Dispatchers.IO) {
            val environmentResult = if (resume) UltraDirectEnvironmentUpgrade.resume(context, taskId) else null
            if (environmentResult != null) {
                reportStage(InstallStage.UPGRADING_ENVIRONMENT)
                publishResult(context, taskId, environmentResult)
            } else if (resume && transactional) observe(context.applicationContext, taskId)
            else if (resume) {
                mutable.value = mutable.value.copy(result = FlashResult(1,
                    context.getString(com.maplesu.core.R.string.installer_patch_interrupted), false))
            } else {
                try {
                    // The selected native or mode-switch flow owns its pending check and root transport.
                    val result = execute { line -> mutable.value = mutable.value.copy(text = mutable.value.text + line + "\n") }
                    publishResult(context, taskId, result)
                } catch (error: Exception) {
                    mutable.value = mutable.value.copy(result = FlashResult(1, error.message.orEmpty(), false))
                }
            }
        }
    }

    fun continueModeSwitch(context: Context, boot: android.net.Uri?, initBoot: android.net.Uri?) {
        val request = mutable.value.result?.imagesRequired ?: return
        val taskId = attachedTask ?: return
        if (request.requiresBoot && boot == null ||
            request.restoreInitBoot && initBoot == null) return
        mutable.value = mutable.value.copy(result = null, stage = InstallStage.PATCHING)
        viewModelScope.launch(Dispatchers.IO) {
            val directory = java.io.File(context.cacheDir, "switch-images-${java.util.UUID.randomUUID()}")
            try {
                check(directory.mkdirs())
                fun copy(uri: android.net.Uri?, name: String): java.io.File? = uri?.let {
                    java.io.File(directory, name).also { file ->
                        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                            file.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var size = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    size += count
                                    check(size <= dev.maplesu.manager.ultrakernel.UltraKernelCatalog.MAX_IMAGE_BYTES)
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                }
                val images = dev.maplesu.manager.workmode.service.ModeSwitchImages(
                    copy(boot, "boot.img"), copy(initBoot, "init_boot.img"))
                val facade = dev.maplesu.manager.workmode.service.ModeSwitchRuntime.facadeOrNull()
                    ?: dev.maplesu.manager.workmode.service.ModeSwitchRuntime.initialize(context)
                val result = installerModeSwitchResult(facade.startSwitch(request.target,
                    confirmHidden = true, requestedSwitchId = taskId, images = images,
                    onProgress = { line -> mutable.value = mutable.value.copy(text = mutable.value.text + line + "\n") }))
                if (result.outcome == com.maplesu.core.ui.util.FlashOutcome.WRITTEN) {
                    InstallerServiceClient.rememberInstallTarget(context, taskId,
                        InstallTargetMode.ULTRA)
                    if (request.target == dev.maplesu.manager.workmode.WorkMode.LITE) {
                        runCatching { dev.maplesu.manager.runtime.ultra.UltraInstallCredentialRecovery.recover(context) }
                            .onFailure { android.util.Log.w("InstallerFlash", "Credential handoff deferred to startup", it) }
                    }
                }
                publishResult(context, taskId, result)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                publishResult(context, taskId, FlashResult(1, error.message.orEmpty(), false))
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private suspend fun observe(context: Context, taskId: String) {
        var autoContinued = false
        try {
            while (true) {
                val status = InstallerServiceClient.installationStatus(context, taskId)
                val observed = status.getString("state", "NONE")
                val current = if (observed == "REBOOT_REQUESTED" && status.getString("rebootSourceState") == "RESTORED")
                    "RESTORED" else observed
                // S5：恢复观察时也要有阶段（进程被杀后重进刷写页）。
                val stage = when (current) {
                    "BEGIN", "SNAPSHOT", "PRODUCE", "PREPARING", "PREPARED" -> InstallStage.PATCHING
                    "COMMIT", "ROLLING_BACK", "WRITING", "RESTORING" -> InstallStage.WRITING
                    "REBOOT_REQUIRED", "REBOOT_REQUESTED", "BOOT_IMAGE_VERIFIED", "BOOT_VERIFIED", "VERIFIED" ->
                        InstallStage.AWAITING_REBOOT
                    else -> mutable.value.stage
                }
                mutable.value = mutable.value.copy(
                    stage = stage,
                )
                val active = status.getBoolean("operationActive")
                if ((current in setOf("PREPARING", "PREPARED") ||
                        current == "FAILED_BEFORE_WRITE" && status.getBoolean("canCancel")) &&
                    status.getString("kind") == "NATIVE" && !active) {
                    // Candidate production did not finish; there is no candidate to replay as a write.
                    InstallerServiceClient.cancelInstallation(context, taskId)
                    mutable.value = mutable.value.copy(result = FlashResult(1,
                        context.getString(com.maplesu.core.R.string.installer_patch_interrupted), false))
                    return
                }
                if (current == "PREPARED" && status.getString("kind") != "MODE_SWITCH" && !restoring && !active) {
                    if (autoContinued) {
                        // The commit was already requested once. Seeing PREPARED again is a real
                        // failure (nothing was written), never a reason to ask for a second tap.
                        val error = status.getString("error").orEmpty()
                            .ifBlank { "INSTALLER_PREPARED_NOT_COMMITTED" }
                        mutable.value = mutable.value.copy(result = FlashResult(1, error, false))
                        return
                    }
                    autoContinued = true
                    val continued = runCatching {
                        InstallerServiceClient.continueInstallation(context, taskId)
                    }
                    continued.exceptionOrNull()?.let { error ->
                        mutable.value = mutable.value.copy(result = FlashResult(1, error.message.orEmpty(), false))
                        return
                    }
                    delay(200)
                    continue
                }
                if (current in setOf("NONE", "BEGIN", "SNAPSHOT", "PRODUCE", "COMMIT", "VERIFYING", "ROLLING_BACK",
                        "PREPARING", "PREPARED", "WRITING", "RESTORING") && active) {
                    delay(500)
                    continue
                }
                val stockRestore = status.getString("operation") == "RESTORE_STOCK"
                val result = installerTaskResult(current, status.getBoolean("rebootRequired"),
                    status.getString("error").orEmpty(), restoring)
                publishResult(context, taskId, if (stockRestore && result.code == 0 &&
                    ImageRestorationKind.fromWire(status.getString("restorationKind")) == ImageRestorationKind.EXACT_STOCK)
                    result.copy(outcome = com.maplesu.core.ui.util.FlashOutcome.RESTORED) else result)
                return
            }
        } catch (error: Exception) {
            mutable.value = mutable.value.copy(result = FlashResult(1, error.message.orEmpty(), false))
        }
    }
}
