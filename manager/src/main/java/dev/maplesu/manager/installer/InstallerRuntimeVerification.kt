package dev.maplesu.manager.installer

import dev.maplesu.manager.runtime.RootExecResult
import dev.maplesu.manager.runtime.RootBackend

/** A partition digest proves an image write; a fresh UID/boot receipt proves its running backend. */
internal fun installerRuntimeIdentityVerified(
    target: InstallTargetMode?,
    currentBootId: String,
    result: RootExecResult,
): Boolean {
    if (!currentBootId.matches(Regex("[0-9a-fA-F-]{36}"))) return false
    val expectedBackend = when (target) {
        InstallTargetMode.ULTRA -> result.backend in setOf(RootBackend.ULTRA_ENV, RootBackend.LITE_KERNEL)
        null -> false
    }
    val lines = result.output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    return expectedBackend && result.ok && lines == listOf("0", currentBootId)
}

/** Task-scoped receipts survive the source/target preference namespace changing after reboot. */
internal fun installTargetFromRecords(taskId: String, records: List<Pair<String?, String?>>): InstallTargetMode? {
    val matching = records.filter { it.first == taskId }
    if (matching.isEmpty()) return null
    val modes = matching.map { InstallTargetMode.fromWire(it.second) ?: return null }.distinct()
    return modes.singleOrNull()
}
