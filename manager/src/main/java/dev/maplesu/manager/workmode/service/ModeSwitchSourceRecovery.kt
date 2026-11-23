package dev.maplesu.manager.workmode.service

import dev.maplesu.image.BootImageInspector
import dev.maplesu.image.UltraInitBootProducer
import dev.maplesu.manager.ultrakernel.UltraKernelAssetSource
import dev.maplesu.manager.ultrakernel.UltraKernelOriginalRecovery
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.switch.CandidateResult
import dev.maplesu.manager.workmode.switch.ModeSwitchCodes
import dev.maplesu.manager.workmode.switch.ModeSwitchHashes
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Builds only a recovered source copy; callers own partition snapshots and writes.
 *
 * 上游同时支持 Normal（KernelSU，经 ksud 重建 ramdisk）与 Ultra 两条恢复路径；本分支已删除
 * KSU 后端与 ksud，因此只剩 Ultra 路径。构造参数里随 Normal 一起移除的是守护进程路径与
 * root 命令通道——它们只服务于 ksud 那一条链。
 */
internal class ModeSwitchSourceRecovery(
    private val assets: UltraKernelAssetSource,
    private val receiptsDirectory: File,
    private val retainedOriginals: List<File>,
    private val logger: (String) -> Unit = {},
) {
    fun recover(from: WorkMode, partition: String, input: File, outputDir: File,
                sourceKnown: Boolean): CandidateResult = try {
        if (!Files.isRegularFile(input.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            failed("SOURCE_RECOVERY_INPUT_MISSING", input.path)
        } else if (partition !in setOf("boot", "init_boot")) {
            failed("SOURCE_RECOVERY_PARTITION_UNSUPPORTED", partition)
        } else {
            val before = BootImageInspector.inspect(input)
            when (from) {
                // 枚举只剩 Ultra；分支写全以满足穷尽性检查。
                WorkMode.LITE -> ultra(partition, input, outputDir, sourceKnown, before)
            }
        }
    } catch (error: BootImageInspector.InvalidImageException) {
        failed("SOURCE_RECOVERY_CONTAINER_INVALID", error.code)
    } catch (error: IOException) {
        failed(error.message.orEmpty().substringBefore(':').takeIf { it.startsWith("ULTRA_INIT_") }
            ?: "SOURCE_RECOVERY_IO", error.message.orEmpty())
    } catch (error: Exception) {
        val code = if (error.message == ModeSwitchCodes.PRODUCE_OUTPUT_SIZE_INVALID)
            ModeSwitchCodes.PRODUCE_OUTPUT_SIZE_INVALID else "SOURCE_RECOVERY_FAILED"
        failed(code, error.message.orEmpty())
    }

    private fun ultra(partition: String, input: File, outputDir: File, sourceKnown: Boolean,
                      before: BootImageInspector.Report): CandidateResult {
        if (partition != "boot" || UltraInitBootProducer.recognizes(input)) {
            if (!UltraInitBootProducer.recognizes(input)) {
                if (sourceKnown) return failed("SOURCE_RECOVERY_UNAVAILABLE", "ultra:$partition")
                return complete(input, input, before, "ultra:unchanged:non-source")
            }
            val work = workspace(outputDir)
            val recovered = UltraInitBootProducer.restore(input, File(work, "restored.img"))
            val full = fullSizeSwitchCandidate(recovered, File(work, "source-base.img"), before.fileSize)
            return complete(input, full, before, "ultra:$partition:owned-writes-restored")
        }
        val work = workspace(outputDir)
        val recovery = UltraKernelOriginalRecovery(assets)
        val recovered = recovery.recover(input, receiptsDirectory, retainedOriginals, work)
            ?: recovery.recover(input, receiptsDirectory, emptyList(), work, generatedOnly = true)
        if (recovered == null) {
            if (sourceKnown) return failed("SOURCE_RECOVERY_UNAVAILABLE", "ultra:$partition")
            return complete(input, input, before, "ultra:unchanged:non-source")
        }
        val full = fullSizeSwitchCandidate(recovered.image, File(work, "source-base.img"), before.fileSize)
        return complete(input, full, before, "ultra:${recovered.source.name}")
    }

    private fun complete(input: File, candidate: File, before: BootImageInspector.Report,
                         evidence: String): CandidateResult {
        if (ModeSwitchHashes.sha256(input) != before.sha256)
            return failed("SOURCE_RECOVERY_INPUT_CHANGED", input.path)
        val report = if (candidate == input) before else BootImageInspector.inspect(candidate)
        return CandidateResult(candidate, report.sha256, report.fileSize, structureEvidence = evidence)
    }

    private fun workspace(outputDir: File): File {
        if (!outputDir.isDirectory && !outputDir.mkdirs()) throw IOException("SOURCE_RECOVERY_OUTPUT_CREATE_FAILED")
        return Files.createTempDirectory(outputDir.toPath(), "source-recovery-").toFile()
    }

    private fun failed(code: String, detail: String) = CandidateResult(null, "", 0, code, redact(detail))

    private fun redact(text: String) = text.replace(KEY_LIKE, "<REDACTED-KEY>")

    private companion object {
        // Match the existing SDK logging rule without introducing an Android runtime dependency.
        val KEY_LIKE = Regex("(?<![A-Za-z0-9])[A-Za-z0-9]{48}(?![A-Za-z0-9])")
    }
}
