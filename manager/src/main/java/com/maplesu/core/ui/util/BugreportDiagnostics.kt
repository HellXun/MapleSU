package com.maplesu.core.ui.util

import java.io.File

/** App-owned evidence is collected even when the privileged channel is unavailable. */
internal object BugreportDiagnostics {
    private val protectedFiles = listOf(
        "native-stage.log" to "native-stage.log",
        "maplesu-ultravider.log" to "maplesu-ultravider.log",
        "maplesu-hook.json" to "maplesu-hook.json",
        "maplesu-hook-attest.json" to "maplesu-hook-attest.json",
        "maplesu-hook-unload.json" to "maplesu-hook-unload.json",
        "maplesu-hook-daemon.json" to "maplesu-hook-daemon.json",
        // T13: the unified handover evidence and the App-written env document.
        "maplesu-handover.json" to "maplesu-handover.json",
        "maplesu-93260-source.json" to "maplesu-93260-source.json",
        "maplesu-handover-env" to "maplesu-handover-env",
        "maplesu-hook-env" to "maplesu-hook-env.log",
        "maplesu-teardown" to "maplesu-teardown.log",
        // Give extensionless and rotated text files a .log suffix so packing redacts them.
        "stage-marks" to "stage-marks.log",
        "launcher-output" to "launcher-output.log",
        "su-audit.log" to "su-audit.log",
        "su-audit.log.1" to "su-audit.1.log",
    )

    fun collect(protectedDirectory: File, filesDirectory: File, destination: File) {
        // App-private evidence can survive several boots; copying does not date its contents.
        val collection = StringBuilder().append("APP_PRIVATE_PERSISTED_EVIDENCE copiedAtMs=")
            .append(System.currentTimeMillis()).append(" bootScope=UNVERIFIED\n")
        fun copy(source: File, name: String) {
            if (!source.isFile) {
                collection.append("MISSING_OR_UNREADABLE ").append(name).append('\n')
                return
            }
            runCatching {
                val target = File(destination, name)
                target.parentFile?.mkdirs()
                val beforeBytes = source.length()
                val beforeModifiedAtMs = source.lastModified()
                source.copyTo(target)
                val afterBytes = source.length()
                val afterModifiedAtMs = source.lastModified()
                collection.append("COPIED ").append(name).append(" bytes=").append(target.length())
                    .append(" sourceBytesBefore=").append(beforeBytes)
                    .append(" sourceModifiedAtMsBefore=").append(beforeModifiedAtMs)
                    .append(" sourceBytesAfter=").append(afterBytes)
                    .append(" sourceModifiedAtMsAfter=").append(afterModifiedAtMs)
                    .append(" metadataChanged=").append(beforeBytes != afterBytes || beforeModifiedAtMs != afterModifiedAtMs)
                    .append(" bootScope=UNVERIFIED\n")
            }.onFailure {
                collection.append("COPY_FAILED ").append(name).append(' ')
                    .append(it.javaClass.simpleName).append('\n')
            }
        }
        val sources = protectedFiles.map { (source, name) -> File(protectedDirectory, source) to name } +
            listOf(
                File(filesDirectory, "installer-io-failures.log") to "installer-io-failures.log",
            )
        sources.forEach { (source, name) -> copy(source, name) }
        File(destination, "diagnostics-collection.log").writeText(collection.toString())
    }
}
