package dev.maplesu.manager.installer

import android.content.Context
import android.os.Build
import dev.maplesu.image.VendorBootInspector
import dev.maplesu.manager.runtime.ultra.UltraSdkLogging
import dev.maplesu.manager.workmode.Hashes
import dev.maplesu.manager.workmode.WorkMode
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** Shared producer for Normal, Ultra and mode switches. Never opens a block device. */
object VivoCompatibility {
    fun deviceSupported(manufacturer: String = Build.MANUFACTURER, brand: String = Build.BRAND): Boolean =
        listOf(manufacturer, brand).any { it.equals("vivo", true) || it.equals("iqoo", true) }

    fun prepare(context: Context, mode: WorkMode, candidate: InstallCandidateImage,
                sources: Map<String, File>, log: (String) -> Unit = {},
                @Suppress("UNUSED_PARAMETER") operationId: String = dev.maplesu.manager.runtime.ultra.UltraInstallCredentials.IMAGE_EXPORT_OPERATION): InstallCandidateImage {
        val companions = LinkedHashMap(candidate.compatibilityImages)
        sources["vendor_boot"]?.let { vendor ->
            val directory = File(context.cacheDir, "vivo-${UUID.randomUUID()}").apply { check(mkdirs()) }
            cleanVendor(context, vendor, directory, log)?.let { companions["vendor_boot"] = it }
        }
        if (mode == WorkMode.LITE && candidate.partition == "boot") {
            check(dev.maplesu.manager.ultrakernel.UltraKernelVivoFilter.present(
                dev.maplesu.manager.ultrakernel.UltraKernelPlan.readKernel(candidate.image))) { "INSTALLER_VR_FILTER_INACTIVE" }
            log("vivo/iQOO：Ultra 内核早期 vr 过滤器已核验；仅刷入 boot 即生效")
        } else if (mode == WorkMode.LITE && candidate.partition == "init_boot") {
            log("vivo/iQOO：init_boot 不含内核；未核验 bridge 的 vr 过滤能力，vendor_boot 仅提交实际清理差异")
        }
        return candidate.copy(compatibilityImages = companions)
    }

    fun prepareFiles(context: Context, mode: WorkMode, candidate: InstallCandidateImage,
                     inputs: VivoCompatibilityInputs, log: (String) -> Unit): InstallCandidateImage {
        val required = linkedMapOf<String, android.net.Uri>()
        inputs.vendorBoot?.let { required["vendor_boot"] = it }
        val directory = File(context.cacheDir, "vivo-input-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val sources = required.mapValues { (name, uri) ->
                File(directory, "$name.img").also { file ->
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(65536); var size = 0L
                            while (true) {
                                check(!Thread.currentThread().isInterrupted) { "INSTALLER_CANCELLED" }
                                val count = input.read(buffer)
                                if (count < 0) break
                                check(count > 0 && count <= 512L * 1024 * 1024 - size) { "IMAGE_SIZE_LIMIT" }
                                output.write(buffer, 0, count); size += count
                            }
                            check(size > 0) { "DOCUMENT_READ_FAILED" }; output.fd.sync()
                        }
                    }
                }
            }
            return prepare(context, mode, candidate, sources, log)
        } finally { directory.deleteRecursively() }
    }

    fun cleanVendor(context: Context, source: File, directory: File,
                    log: (String) -> Unit = {}): InstallCompanionImage? {
        val original = VendorBootInspector.inspect(source)
        val output = File(directory, "vendor_boot-rmvr.img")
        val receipt = File(directory, "vendor-boot-report.json")
        command(context, listOf("boot-patch-rmvr", "-b", source.path, "-o", directory.path,
            "--out-name", output.name, "--report", receipt.path))
        val info = JSONObject(receipt.readText())
        val after = VendorBootInspector.inspect(output)
        check(info.getInt("schema") == 1 && info.getString("partition") == "vendor_boot" &&
            info.getString("sourceSha256") == original.sha256 && info.getString("candidateSha256") == after.sha256 &&
            Hashes.sha256(source) == original.sha256 && output.length() == source.length()) { "INSTALLER_VENDOR_BOOT_CHANGED" }
        if (!info.getBoolean("changed")) {
            check(after.sha256 == original.sha256) { "INSTALLER_VENDOR_BOOT_CHANGED" }
            log("vivo/iQOO：vendor_boot 无 vr/vklp 引用，保持原镜像")
            return null
        }
        log("vivo/iQOO：已清理 ${info.getInt("removedModules")} 个模块、${info.getInt("updatedIndexes")} 个索引")
        return InstallCompanionImage(output, after.sha256, original.sha256)
    }

    fun verifyRuntime(mode: String, daemon: File, kernelFilterVerified: Boolean = false) {
        val status = runCatching {
            if (mode == "LITE" && kernelFilterVerified) "KERNEL_VERIFIED"
            else if (mode == "LITE") File("/sys/module/maplesu_vr/parameters/vr_filter_active").readText()
            else command(daemon, listOf("feature", "get", "vr_filter"))
        }.getOrElse {
            if (it is InterruptedException) throw it
            throw IllegalStateException("INSTALLER_VR_FILTER_INACTIVE: ${it.message.orEmpty()}", it)
        }
        VivoCompatibilityStatus.requireActive(mode, status, File("/proc/modules").readText())
    }

    /**
     * Normal（KernelSU）的 vivo 生产者：上游经 `libksud_ksu.so feature get vr_filter` 读状态。
     * 本分支已删除 ksud，Normal 也不再是可选目标，因此这里显式失败，不伪造成功。
     */
    private fun command(context: Context, arguments: List<String>): String =
        throw IllegalStateException("INSTALLER_VIVO_PRODUCER_MISSING: ksud removed (Ultra-only)")

    private fun command(daemon: File, arguments: List<String>): String {
        check(daemon.isFile) { "INSTALLER_VIVO_PRODUCER_MISSING" }
        val argv = listOf(daemon.path) + arguments
        val stage = arguments.firstOrNull().orEmpty()
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        val finished = process.waitFor(BOOT_PATCH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            if (Thread.currentThread().isInterrupted) throw InterruptedException("INSTALLER_CANCELLED:stage=$stage")
            throw IllegalStateException("INSTALLER_VIVO_PRODUCER_FAILED:stage=$stage timeout")
        }
        val out = process.inputStream.bufferedReader().use { it.readText() }
        android.util.Log.i("VivoCompatibility", UltraSdkLogging.redact(
            "stage=$stage argv=$argv code=${process.exitValue()} out=$out"))
        if (process.exitValue() != 0) {
            throw IllegalStateException(
                "INSTALLER_VIVO_PRODUCER_FAILED:stage=$stage code=${process.exitValue()}")
        }
        return out
    }

    private const val BOOT_PATCH_TIMEOUT_MS = 300_000L
}
