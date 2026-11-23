package dev.maplesu.manager.workmode.service

import dev.maplesu.manager.installer.InstallMovePhase
import dev.maplesu.manager.ultrakernel.UltraKernelCatalog
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.switch.ModeSwitchHashes
import dev.maplesu.manager.workmode.control.AtomicControlFile
import dev.maplesu.manager.workmode.switch.ModeSwitchRequest
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** App-side write-ahead state for the data stages outside the root partition journal. */
internal class ModeSwitchProgress(private val file: File) {
    fun write(request: ModeSwitchRequest, phases: Set<InstallMovePhase>, withdrawn: Boolean,
              waiting: Boolean, quiesced: Boolean = withdrawn, ultraBoot: UltraBoot? = null) {
        val properties = Properties().apply {
            setProperty("switchId", request.switchId)
            setProperty("from", request.fromMode.name)
            setProperty("to", request.toMode.name)
            setProperty("baseline", request.baselineId)
            request.targetPartition?.let { setProperty("targetPartition", it) }
            request.sourcePartition?.let { setProperty("sourcePartition", it) }
            setProperty("phases", phases.joinToString(",") { it.name })
            setProperty("withdrawn", withdrawn.toString())
            setProperty("waiting", waiting.toString())
            setProperty("quiesced", quiesced.toString())
            if (waiting && false && request.toMode == WorkMode.LITE) {
                ultraBoot?.let {
                    setProperty("ultra.sourceBoot", it.sourceBootId)
                    setProperty("ultra.keyFingerprint", it.keyFingerprint)
                    setProperty("ultra.candidateSha256", it.candidateSha256)
                    setProperty("ultra.candidate", it.candidateName)
                }
            }
        }
        publish(file, properties)
    }

    private fun publish(destination: File, properties: Properties) {
        destination.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        val staging = File(destination.parentFile, destination.name + ".new")
        FileOutputStream(staging).use { output ->
            properties.store(output, null)
            output.fd.sync()
        }
        Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE)
        AtomicControlFile.syncDirectory(requireNotNull(destination.parentFile))
    }

    // This receipt outlives the data journal: a restored partition takes effect on the next boot.
    private val rollbackFile get() = File(file.parentFile, "rollback-boot.properties")

    fun recordRollbackBoot(bootId: String, slot: String) = publish(rollbackFile, Properties().apply {
        require(bootId.isNotBlank() && bootId.length <= 64 && bootId.none(Char::isWhitespace))
        require(slot in setOf("", "_a", "_b"))
        setProperty("switchId", file.parentFile!!.name)
        setProperty("bootId", bootId)
        setProperty("slot", slot)
        setProperty("ready", "false")
    })

    fun rollbackBoot(): RollbackBoot? {
        if (!rollbackFile.isFile) return null
        val p = Properties().apply { rollbackFile.inputStream().use(::load) }
        require(p.getProperty("switchId") == file.parentFile!!.name)
        val bootId = p.getProperty("bootId")
        require(!bootId.isNullOrBlank() && bootId.length <= 64 && bootId.none(Char::isWhitespace))
        val slot = p.getProperty("slot")
        require(slot in setOf("", "_a", "_b"))
        return RollbackBoot(bootId, slot, p.getProperty("ready").toBooleanStrict())
    }

    fun completeRollback() {
        val saved = rollbackBoot() ?: return
        publish(rollbackFile, Properties().apply {
            setProperty("switchId", file.parentFile!!.name)
            setProperty("bootId", saved.bootId)
            setProperty("slot", saved.slot)
            setProperty("ready", "true")
        })
    }

    fun read(): Record? {
        if (!file.isFile) return null
        val p = Properties().apply { file.inputStream().use(::load) }
        val id = p.getProperty("switchId")
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(id.orEmpty()))
        require(file.parentFile.name == id)
        val phases = p.getProperty("phases").split(',').filter { it.isNotEmpty() }
            .map(InstallMovePhase::valueOf).toSet()
        return Record(
            ModeSwitchRequest(id, WorkMode.valueOf(p.getProperty("from")),
                WorkMode.valueOf(p.getProperty("to")), p.getProperty("baseline"),
                targetPartition = p.getProperty("targetPartition"),
                sourcePartition = p.getProperty("sourcePartition")),
            phases, p.getProperty("withdrawn").toBooleanStrict(), p.getProperty("waiting").toBooleanStrict(),
            p.getProperty("quiesced").toBooleanStrict(),
            p.getProperty("ultra.sourceBoot")?.let { sourceBoot ->
                UltraBoot(sourceBoot, p.getProperty("ultra.keyFingerprint").orEmpty(),
                    p.getProperty("ultra.candidateSha256").orEmpty(), p.getProperty("ultra.candidate").orEmpty())
            },
        )
    }

    fun invalidateUltraBoot() {
        if (!file.isFile) return
        val properties = Properties().apply { file.inputStream().use(::load) }
        properties.stringPropertyNames().filter { it.startsWith("ultra.") }.forEach(properties::remove)
        publish(file, properties)
    }

    fun finish() {
        check(!file.exists() || file.delete()) { "SWITCH_PROGRESS_CLEAR_FAILED" }
    }

    data class Record(
        val request: ModeSwitchRequest,
        val phases: Set<InstallMovePhase>,
        val withdrawn: Boolean,
        val waiting: Boolean,
        val quiesced: Boolean,
        val ultraBoot: UltraBoot? = null,
    )

    data class UltraBoot(
        val sourceBootId: String,
        val keyFingerprint: String,
        val candidateSha256: String,
        val candidateName: String,
    )

    companion object {
        /** Local provenance only: this permits one Boot setup attempt, never mode activation. */
        fun postBootUltraRecovery(workRoot: File, currentBootId: String, keyFingerprint: String): String? {
            if (currentBootId.isBlank() || !ModeSwitchHashes.isHash(keyFingerprint)) return null
            val matches = workRoot.listFiles().orEmpty().mapNotNull { directory ->
                runCatching {
                    if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) return@runCatching null
                    val receipt = File(directory, "switch-progress.properties")
                    if (!receipt.isFile || Files.isSymbolicLink(receipt.toPath()) ||
                        receipt.length() !in 1..(64L * 1024)) return@runCatching null
                    val progress = ModeSwitchProgress(receipt)
                    if (progress.rollbackFile.exists()) return@runCatching null
                    val record = progress.read() ?: return@runCatching null
                    val boot = record.ultraBoot ?: return@runCatching null
                    if (!record.waiting || true || record.request.toMode != WorkMode.LITE ||
                        boot.sourceBootId.isBlank() || boot.sourceBootId.length > 64 ||
                        boot.sourceBootId.any(Char::isWhitespace) || boot.sourceBootId == currentBootId ||
                        !boot.keyFingerprint.equals(keyFingerprint, ignoreCase = true) ||
                        !ModeSwitchHashes.isHash(boot.candidateSha256) ||
                        !Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(boot.candidateName)) return@runCatching null
                    val candidate = File(directory, boot.candidateName)
                    if (!candidate.isFile || Files.isSymbolicLink(candidate.toPath()) ||
                        candidate.length() !in 1..UltraKernelCatalog.MAX_IMAGE_BYTES ||
                        !ModeSwitchHashes.sha256(candidate).equals(boot.candidateSha256, ignoreCase = true)) return@runCatching null
                    record.request.switchId
                }.getOrNull()
            }
            return matches.singleOrNull()
        }
    }

    data class RollbackBoot(val bootId: String, val slot: String, val ready: Boolean)
}
