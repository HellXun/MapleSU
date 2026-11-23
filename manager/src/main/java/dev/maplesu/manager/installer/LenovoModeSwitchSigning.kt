package dev.maplesu.manager.installer

import android.content.Context
import android.os.Build
import dev.maplesu.manager.workmode.service.ModeSwitchSigningContext
import dev.maplesu.manager.workmode.switch.ModeSwitchRequest
import java.io.File

/** Called by the open progress page's execution, after carrier resolution and before begin. */
internal object LenovoModeSwitchSigning {
    fun open(context: Context, request: ModeSwitchRequest, log: (String) -> Unit): ModeSwitchSigningContext? {
        val partition = requireNotNull(request.targetPartition)
        val target = InstallTargetMode.fromWorkMode(request.toMode)
        val options = InstallerServiceClient.withService(context, persistent = true, rootTarget = target) { service ->
            val record = service.getBootInstall("")
            check(record.getString("reconciliationError").isNullOrEmpty()) { "LENOVO_AVB_INSTALLED_STATE_INVALID" }
            check(!record.getBoolean("operationActive")) { "LENOVO_AVB_INSTALLED_STATE_BUSY" }
            val installed = if (record.getString("kind") == "NATIVE" && record.getString("operation") == "INSTALL" &&
                record.getString("targetMode") == request.fromMode.name && record.getString("state") in
                setOf("REBOOT_REQUIRED", "REBOOT_REQUESTED", "BOOT_IMAGE_VERIFIED")) {
                // partitionSha256 is a historical expected value. Match it to a fresh read of
                // the active slot instead of exporting/importing an image or trusting the receipt.
                matchingSwitchSigningMode(record.getString("partition").orEmpty(),
                    record.getString("slotSuffix").orEmpty(), record.getString("partitionSha256").orEmpty(),
                    record.getString("signingMode").orEmpty(), InstallerServiceClient.decode(InstallerServiceClient.observe(service)))
            } else null
            switchSigningOptions(Build.FINGERPRINT, installed)
        }
        if (!options.enabled) return null
        val signingMode = options.mode(partition).name
        log("模式切换 AVB 签名：$signingMode，目标分区：$partition，使用公开开发密钥")
        val session = LenovoSigningSession(context, options, target, request.switchId, false, log)
        return object : ModeSwitchSigningContext {
            override val mode = signingMode
            override fun finish(candidate: InstallCandidateImage, source: File) = session.finish(candidate, source)
            override fun close() = session.close()
        }
    }
}

internal fun matchingSwitchSigningMode(partition: String, slot: String, expectedSha256: String,
                                      mode: String, current: InstallerSnapshot): String? {
    if (partition !in setOf("boot", "init_boot") || slot != current.slotSuffix ||
        !expectedSha256.matches(Regex("[0-9a-f]{64}")) || current.partitions[partition]?.sha256 != expectedSha256) return null
    check(mode.isEmpty() || when (partition) {
        "boot" -> mode in setOf("CHAINED", "AOSP")
        "init_boot" -> mode == "NON_CHAINED"
        else -> false
    }) { "LENOVO_AVB_INSTALLED_MODE_INVALID" }
    return mode
}

/** An init_boot receipt preserves signing enablement; the resolved target selects its AVB mode. */
internal fun switchSigningOptions(fingerprint: String?, installedMode: String?): LenovoSigningOptions =
    if (installedMode == "NON_CHAINED") LenovoSigningOptions(enabled = true)
    else kernelUpdateSigningOptions(fingerprint, installedMode)
