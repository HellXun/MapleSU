package dev.maplesu.manager.installer

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import dev.maplesu.manager.workmode.Hashes
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.service.ClientPartition
import dev.maplesu.manager.workmode.service.ClientResult
import dev.maplesu.manager.workmode.service.ClientStatus
import dev.maplesu.manager.workmode.service.SignedSwitchClient
import java.io.File
import java.util.UUID

/** Recovery routing persists across Manager death/reboot. The root-owned native record is
 * authoritative for all image writes; the ordinary switch record has never committed. */
internal class NativeSignedSwitchClient(private val context: Context) : SignedSwitchClient {
    private val prefs = context.getSharedPreferences("maplesu-signed-switches", Context.MODE_PRIVATE)
    override fun owns(switchId: String) = switchId.isNotEmpty() && prefs.contains("$switchId.mode")
    override fun latestId(): String? = prefs.getString("latest", null)
    private fun partition(id: String) = requireNotNull(prefs.getString("$id.partition", null))
    private fun target(id: String) = InstallTargetMode.fromWorkMode(WorkMode.valueOf(requireNotNull(prefs.getString("$id.to", null))))
    private fun <T> service(id: String, action: (IInstallerService) -> T) =
        InstallerServiceClient.withService(context, persistent = true, rootTarget = target(id), action = action)
    private fun guarded(id: String, expected: Set<String>, action: () -> Bundle): ClientResult = try {
        val state = decode(id, action())
        ClientResult(state.state in expected, state.error, state.errorDetail, state)
    } catch (error: Exception) { ClientResult(false, error.message.orEmpty()) }

    override fun standalone(switchId: String) = prefs.getBoolean("$switchId.vivo", false)
    override suspend fun preflight(switchId: String, planFields: Map<String, String>) = ClientResult(true)
    override suspend fun begin(switchId: String, planFields: Map<String, String>, superuserJson: File, settingsMirrorJson: File): ClientResult {
        require(UUID.fromString(switchId).toString() == switchId)
        val mode = planFields["lenovoSigning"].orEmpty()
        val partition = planFields["targetPartition"]
        require(mode.isEmpty() && planFields["vivoCompatibility"] == "1" && partition in setOf("boot", "init_boot") ||
            partition == "boot" && mode in setOf("CHAINED", "AOSP") ||
            partition == "init_boot" && mode == "NON_CHAINED")
        check(prefs.edit().putBoolean("$switchId.vivo", planFields["vivoCompatibility"] == "1")
            .putString("$switchId.mode", mode).putString("$switchId.from", planFields["fromMode"])
            .putString("$switchId.partition", partition)
            .putString("$switchId.to", planFields["toMode"]).putString("latest", switchId).commit()) { "LENOVO_AVB_SWITCH_STORE_FAILED" }
        return ClientResult(true)
    }
    override suspend fun snapshot(switchId: String) = guarded(switchId, setOf("SNAPSHOT")) {
        service(switchId) {
            val mode = prefs.getString("$switchId.mode", "").orEmpty()
            if (mode.isEmpty()) it.prepareNativeInstall(switchId, target(switchId).workMode.name, partition(switchId), true, "", true)
            else it.prepareNativeSignedInstall(switchId, target(switchId).workMode.name, partition(switchId), true, "", true, mode)
        }
            .also { check(prefs.edit().putBoolean("$switchId.prepared", true).commit()) }
    }
    override suspend fun exportImage(switchId: String, kind: String, partition: String, destination: File): Boolean = try {
        val capturedPre = kind == "PRE" && partition in setOf("boot", "init_boot")
        check(capturedPre || kind == "BASE" && partition in setOf(partition(switchId), "init_boot") ||
            standalone(switchId) && kind == "PRE" && partition == "vendor_boot")
        val meta = Bundle()
        val expected = if (capturedPre) requireNotNull(statusExact(switchId)).let { status ->
            check(status.state == "SNAPSHOT") { "INSTALLER_TRANSACTION_NOT_PREPARED" }
            status to status.partitions.single { it.name == partition }
        } else null
        val descriptor = service(switchId) {
            if (capturedPre) it.openPartitionImage(partition, meta) else it.openNativeInstallImage(switchId, partition, meta)
        }
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            if (expected != null) {
                val observed = InstallerServiceClient.decodeSnapshot(meta)
                val row = observed.partitions.getValue(partition)
                check(observed.slotSuffix == expected.first.slotSuffix && row.size == expected.second.size &&
                    row.sha256 == expected.second.preSha256) { "INSTALLER_IMAGE_CHANGED" }
                meta.clear()
                meta.putLong("size", row.size); meta.putString("sha256", row.sha256)
            }
            destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var count = 0L
                while (true) {
                    if (capturedPre && Thread.currentThread().isInterrupted)
                        throw java.io.InterruptedIOException("SOURCE_RECOVERY_CANCELLED")
                    val bytes = input.read(buffer)
                    if (bytes < 0) break
                    check(bytes > 0 && bytes <= meta.getLong("size") - count) { "INSTALLER_IMAGE_CHANGED" }
                    output.write(buffer, 0, bytes); count += bytes
                }
                output.fd.sync()
            }
        }
        check(destination.length() == meta.getLong("size") && Hashes.sha256(destination) == meta.getString("sha256"))
        if (!capturedPre) check(prefs.edit().putString("$switchId.source.$partition", meta.getString("sha256")).commit())
        true
    } catch (error: Exception) {
        if (kind == "PRE" && partition in setOf("boot", "init_boot")) {
            try { java.nio.file.Files.deleteIfExists(destination.toPath()) } catch (cleanup: Exception) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
        destination.delete(); false
    }

    override suspend fun submitCandidate(switchId: String, partition: String, candidate: File, candidateSha256: String): ClientResult = try {
        check((partition == partition(switchId) || standalone(switchId) && partition in setOf("vendor_boot", "init_boot")) &&
            candidate.isFile && Hashes.sha256(candidate) == candidateSha256)
        if (partition == partition(switchId)) {
            check(prefs.edit().putString("$switchId.candidate", candidate.canonicalPath).putString("$switchId.sha256", candidateSha256).commit())
        } else {
            check(!prefs.getString("$switchId.source.$partition", null).isNullOrEmpty())
            val names = prefs.getStringSet("$switchId.compatibility", emptySet()).orEmpty() + partition
            check(prefs.edit().putStringSet("$switchId.compatibility", names)
                .putString("$switchId.compatibility.$partition", candidate.canonicalPath)
                .putString("$switchId.compatibility.$partition.sha256", candidateSha256).commit())
        }
        ClientResult(true, status = statusExact(switchId))
    } catch (error: Exception) { ClientResult(false, error.message.orEmpty()) }

    override suspend fun commit(switchId: String) = guarded(switchId, setOf("REBOOT_REQUIRED", "VERIFIED")) {
        val candidate = File(requireNotNull(prefs.getString("$switchId.candidate", null)))
        val hash = requireNotNull(prefs.getString("$switchId.sha256", null))
        check(candidate.isFile && Hashes.sha256(candidate) == hash) { "INSTALLER_CANDIDATE_CHANGED" }
        val signingMode = requireNotNull(prefs.getString("$switchId.mode", null))
        val names = when (signingMode) { "AOSP" -> listOf("recovery", "vbmeta_system", "vbmeta"); "" -> emptyList(); else -> listOf("vbmeta") }
        val signedDirectory = File(context.cacheDir, "lenovo-avb-$switchId/signed")
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        try {
            val companions = Bundle()
            names.forEach { name ->
                val file = File(signedDirectory, "$name.img")
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                descriptors += descriptor; companions.putParcelable(name, descriptor); companions.putString("$name.sha256", Hashes.sha256(file))
            }
            prefs.getStringSet("$switchId.compatibility", emptySet()).orEmpty().forEach { name ->
                check(name in setOf("vendor_boot", "init_boot"))
                val file = File(requireNotNull(prefs.getString("$switchId.compatibility.$name", null)))
                val hash = requireNotNull(prefs.getString("$switchId.compatibility.$name.sha256", null))
                check(Hashes.sha256(file) == hash) { "INSTALLER_IMAGE_CHANGED" }
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                descriptors += descriptor; companions.putParcelable(name, descriptor)
                companions.putString("$name.sha256", hash)
                companions.putString("$name.sourceSha256", prefs.getString("$switchId.source.$name", null))
            }
            ParcelFileDescriptor.open(candidate, ParcelFileDescriptor.MODE_READ_ONLY).use { boot ->
                service(switchId) { it.commitNativeSignedInstall(switchId, boot, hash, companions) }
            }
        } finally { descriptors.forEach { it.close() } }
    }
    override suspend fun rollback(switchId: String) = guarded(switchId, setOf("ROLLED_BACK")) { service(switchId) { it.restoreBootInstall(switchId) } }
    override suspend fun abort(switchId: String) = guarded(switchId, setOf("CANCELLED")) {
        service(switchId) { it.cancelNativeInstall(switchId) }.also {
            check(prefs.edit().putBoolean("$switchId.cancelled", true).commit())
        }
    }
    override fun status(switchId: String) = statusExact(switchId)
    override fun statusExact(switchId: String): ClientStatus? {
        if (!owns(switchId)) return null
        val record = service(switchId) { it.getBootInstall(switchId) }
        if (record.getString("state") == "NONE" && record.getString("error").isNullOrEmpty()) {
            val cancelled = prefs.getBoolean("$switchId.cancelled", false)
            check(cancelled || !prefs.getBoolean("$switchId.prepared", false)) { "INSTALLER_TRANSACTION_MISSING" }
            return ClientStatus(switchId, if (cancelled) "CANCELLED" else "BEGIN", "", "", requireNotNull(prefs.getString("$switchId.from", null)),
                target(switchId).workMode.name, "", "", "", false, false, false, false, 0, emptyList())
        }
        return decode(switchId, record)
    }
    override suspend fun verify(switchId: String) = guarded(switchId, setOf("VERIFIED")) { service(switchId) { it.verifyBootInstall(switchId) } }
    override suspend fun markRebootRequested(switchId: String) = statusExact(switchId)?.state == "REBOOT_REQUIRED"
    override suspend fun exportLog(switchId: String, destination: File): File? {
        destination.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "INSTALLER_LOG_EXPORT_FAILED" } }
        ParcelFileDescriptor.AutoCloseInputStream(service(switchId) { it.openSwitchLog(switchId) }).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        return destination
    }

    @Suppress("DEPRECATION")
    private fun decode(id: String, record: Bundle): ClientStatus {
        check(record.getString("taskId") == id && record.getString("kind") == "NATIVE") { "INSTALLER_SERVICE_RESPONSE_INVALID" }
        val state = when (record.getString("state")) {
            "PREPARING", "PREPARED" -> "SNAPSHOT"
            "WRITING" -> "COMMIT"
            "RESTORING" -> "ROLLING_BACK"
            "RESTORED" -> "ROLLED_BACK"
            "FAILED_BEFORE_WRITE" -> "FAILED_PRE_WRITE"
            "BOOT_IMAGE_VERIFIED" -> "VERIFIED"
            "REBOOT_REQUESTED" -> "REBOOT_REQUIRED"
            else -> record.getString("state").orEmpty()
        }
        val rows = record.getParcelableArrayList<Bundle>("partitionRows").orEmpty().map { row ->
            val name = requireNotNull(row.getString("name"))
            val writes = name == partition(id) || name in prefs.getStringSet("$id.compatibility", emptySet()).orEmpty()
            ClientPartition(name, if (writes) "WRITE_CANDIDATE" else "RESTORE_SOURCE", row.getLong("size"),
                row.getString("preSha256").orEmpty(), row.getString("targetSha256").orEmpty(), "", state)
        }
        return ClientStatus(id, state, "", "", requireNotNull(prefs.getString("$id.from", null)),
            target(id).workMode.name, record.getString("error").orEmpty(), record.getString("errorDetail").orEmpty(), "",
            record.getBoolean("rebootRequired"), record.getBoolean("rebootObserved"), record.getBoolean("canRestore"),
            record.getBoolean("rebootRequired"), 0, rows, slotSuffix = record.getString("slotSuffix"),
            targetPartition = partition(id), signingMode = record.getString("signingMode"))
    }
}
