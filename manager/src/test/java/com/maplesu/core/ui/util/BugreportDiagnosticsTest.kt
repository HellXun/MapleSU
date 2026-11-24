package com.maplesu.core.ui.util

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.GZIPInputStream

class BugreportDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun preservesExistingProviderEvidenceAndStatus() {
        val protected = temporary.newFolder("protected")
        val files = temporary.newFolder("files")
        val destination = temporary.newFolder("report")
        val names = listOf("native-stage.log", "maplesu-ultravider.log", "maplesu-hook.json",
            "maplesu-hook-attest.json", "maplesu-hook-unload.json", "maplesu-hook-daemon.json",
            "maplesu-handover.json", "maplesu-handover-env", "maplesu-93260-source.json")
        names.forEach { File(protected, it).writeText("evidence:$it") }
        BugreportDiagnostics.collect(protected, files, destination)

        assertEquals((names + listOf("diagnostics-collection.log")).toSet(),
            destination.list()!!.toSet())
        names.forEach { assertEquals("evidence:$it", File(destination, it).readText()) }
    }

    @Test fun recordsPersistedEvidenceProvenanceWithoutClaimingCurrentBoot() {
        val protected = temporary.newFolder("provenance-protected")
        val files = temporary.newFolder("provenance-files")
        val destination = temporary.newFolder("provenance-report")
        val original = "persisted diagnostic evidence\n".toByteArray(Charsets.UTF_8)
        val source = File(protected, "native-stage.log").apply { writeBytes(original) }
        val sourceBytes = source.length()
        val sourceModifiedAtMs = source.lastModified()
        assertTrue(source.isFile)

        BugreportDiagnostics.collect(protected, files, destination)

        val collection = File(destination, "diagnostics-collection.log").readLines()
        val header = Regex("APP_PRIVATE_PERSISTED_EVIDENCE copiedAtMs=([0-9]+) bootScope=UNVERIFIED")
            .matchEntire(collection.first())
        assertNotNull(header)
        assertTrue(header!!.groupValues[1].toLong() > 0L)
        assertEquals("COPIED native-stage.log bytes=$sourceBytes sourceBytesBefore=$sourceBytes " +
            "sourceModifiedAtMsBefore=$sourceModifiedAtMs sourceBytesAfter=$sourceBytes " +
            "sourceModifiedAtMsAfter=$sourceModifiedAtMs metadataChanged=false bootScope=UNVERIFIED",
            collection.single { it.startsWith("COPIED native-stage.log ") })
        assertEquals(original.size.toLong(), sourceBytes)
        assertEquals(sourceBytes, source.length())
        assertEquals(sourceModifiedAtMs, source.lastModified())
        assertArrayEquals(original, source.readBytes())
        assertArrayEquals(original, File(destination, "native-stage.log").readBytes())
    }

    @Test fun includesSessionStagesLauncherOutputAndBothAuditGenerationsAsText() {
        val protected = temporary.newFolder("session-protected")
        val files = temporary.newFolder("session-files")
        val destination = temporary.newFolder("session-report")
        val expected = linkedMapOf(
            "stage-marks" to "stage-marks.log",
            "launcher-output" to "launcher-output.log",
            "su-audit.log" to "su-audit.log",
            "su-audit.log.1" to "su-audit.1.log",
        )
        expected.keys.forEach { File(protected, it).writeText("diagnostic:$it\n") }

        BugreportDiagnostics.collect(protected, files, destination)

        assertEquals(expected.values.toSet() + "diagnostics-collection.log", destination.list()!!.toSet())
        expected.forEach { (source, name) ->
            assertEquals("diagnostic:$source\n", File(destination, name).readText())
            assertEquals("diagnostic:$source\n", File(protected, source).readText())
        }
    }

    @Test fun archivesAddedTracesWithRootKeysAndSuTokensRedactedAcrossFiles() {
        val protected = temporary.newFolder("private-protected")
        val files = temporary.newFolder("private-files")
        val destination = temporary.newFolder("private-report")
        val token = "12abcdef34abcdef"
        val key = "Ab9_".repeat(12)
        val original = linkedMapOf(
            "stage-marks" to "prepare:failed:HELPER_STAGE_FAILED $token\n",
            "launcher-output" to "launcher-err: argument $key $token\n",
            "su-audit.log" to "{\"event\":\"exit\",\"exitCode\":126,\"detail\":\"$key\"}\n",
            "su-audit.log.1" to "rootKey=$key suToken=$token\n",
        )
        original.forEach { (name, text) -> File(protected, name).writeText(text) }
        BugreportDiagnostics.collect(protected, files, destination)

        val report = BugreportArchive.pack(destination, File(temporary.root, "private.tar.gz"))
        val entries = unpack(report)

        assertEquals(setOf("stage-marks.log", "launcher-output.log", "su-audit.log",
            "su-audit.1.log", "diagnostics-collection.log"), entries.keys)
        entries.values.forEach { text ->
            assertFalse(text.contains(key))
            assertFalse(text.contains(token))
        }
        assertTrue(entries.getValue("stage-marks.log").contains("HELPER_STAGE_FAILED"))
        assertTrue(entries.getValue("su-audit.log").contains("\"exitCode\":126"))
        original.forEach { (name, text) -> assertEquals(text, File(protected, name).readText()) }
    }

    @Test fun missingEvidenceAndUnrelatedFilesDoNotEnterTheReport() {
        val protected = temporary.newFolder("empty-protected")
        val files = temporary.newFolder("empty-files")
        val destination = temporary.newFolder("empty-report")
        File(protected, "unrelated.txt").writeText("unrelated")
        File(files, "other.json").writeText("unrelated")

        BugreportDiagnostics.collect(protected, files, destination)

        assertEquals(setOf("diagnostics-collection.log"), destination.list()!!.toSet())
        assertTrue(File(destination, "diagnostics-collection.log").readText().contains("MISSING_OR_UNREADABLE"))
    }

    @Test fun legacyAttemptDirectoriesDoNotEnterTheReport() {
        val files = temporary.newFolder("legacy-files")
        val destination = temporary.newFolder("legacy-report")
        File(files, "attempts/dirtyfrag/request.json").apply { parentFile!!.mkdirs(); writeText("{}") }
        BugreportDiagnostics.collect(temporary.newFolder("legacy-protected"), files, destination)
        val entries = unpack(BugreportArchive.pack(destination, File(temporary.root, "legacy.tar.gz")))
        assertTrue(entries.keys.none { it.startsWith("attempts/") })
        assertEquals(setOf("diagnostics-collection.log"), entries.keys)
    }

    private fun unpack(report: File): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        GZIPInputStream(report.inputStream()).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    entries[entry.name] = tar.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
        return entries
    }
}
