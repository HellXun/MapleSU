package com.maplesu.core.ui.util

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

class BugreportArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun helperAndSystemDirectoryPrefixesAreRedactedWithoutHidingBootReceipts() {
        val text = "/data/Abcd0123456789ef/init.log /data/Abcd0123456789efA/modules\n" +
            "boot=12345678-1234-1234-1234-123456789abc uid=0 /data/system/packages.list"
        val safe = BugreportArchive.redact(text)
        assertFalse(safe.contains("Abcd0123456789ef"))
        assertTrue(safe.contains("boot=12345678-1234-1234-1234-123456789abc"))
        assertTrue(safe.contains("/data/system/packages.list"))
    }

    @Test fun reportWithoutRootIsNonEmptyAndContainsReadableDiagnostics() {
        val directory = temporary.newFolder("report")
        File(directory, "basic.txt").writeText("MapleSU 5.0.0\nRoot: unavailable\n")
        File(directory, "清理日志.txt").writeText("stage=ATTESTING\n")
        val report = BugreportArchive.pack(directory, File(temporary.root, "report.tar.gz"))
        assertTrue(report.length() > 0)
        val entries = linkedMapOf<String, String>()
        GZIPInputStream(report.inputStream()).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    entries[entry.name] = tar.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
        assertEquals("stage=ATTESTING\n", entries["清理日志.txt"])
        assertTrue(entries.getValue("basic.txt").contains("Root: unavailable"))
    }

    @Test fun missingArchiveDoesNotOpenOrTruncateDestination() {
        var opened = false
        assertThrows(IOException::class.java) {
            BugreportArchive.copy(File(temporary.root, "missing.tar.gz")) {
                opened = true
                ByteArrayOutputStream()
            }
        }
        assertFalse(opened)
    }

    @Test fun emptyArchiveDoesNotOpenDestination() {
        val report = temporary.newFile("empty.tar.gz")
        assertThrows(IOException::class.java) {
            BugreportArchive.copy(report) { fail("Destination opened before archive exists"); null }
        }
    }

    @Test fun unavailableDestinationIsAnExportFailure() {
        val report = temporary.newFile("archive.tar.gz").apply { writeText("archive") }
        assertThrows(IOException::class.java) { BugreportArchive.copy(report) { null } }
    }

    @Test fun successfulExportCopiesAllBytesAndClosesDestination() {
        val report = temporary.newFile("archive.tar.gz").apply { writeBytes(ByteArray(8193) { it.toByte() }) }
        var closed = false
        val destination = object : ByteArrayOutputStream() {
            override fun close() { closed = true; super.close() }
        }
        assertEquals(report.length(), BugreportArchive.copy(report) { destination })
        assertTrue(closed)
        assertArrayEquals(report.readBytes(), destination.toByteArray())
    }

    @Test fun exportedTextsRedactHookTokensAndUltraKeysIncludingUnlabelledProcessArguments() {
        val directory = temporary.newFolder("sensitive-report")
        val token = "12abcdef34abcdef"
        val key = "Ab9_".repeat(12)
        val process = "app_process --root-key $key --su-token $token\n"
        val status = "{\"suToken\":\"$token\",\"rootKey\":\"$key\",\"token\":\"MAPLESU_HOOK_RESTORE_UNVERIFIED\"}"
        File(directory, "a-process.txt").writeText(process)
        File(directory, "z-status.json").writeText(status)
        val binary = byteArrayOf(0, 1, 127, -1)
        File(directory, "allowlist.bin").writeBytes(binary)

        val archive = BugreportArchive.pack(directory, File(temporary.root, "sensitive.tar.gz"), listOf(key))
        val entries = unpack(archive)
        for (name in listOf("a-process.txt", "z-status.json")) {
            val text = entries.getValue(name).toString(Charsets.UTF_8)
            assertFalse(text.contains(token))
            assertFalse(text.contains(key))
        }
        assertTrue(entries.getValue("z-status.json").toString(Charsets.UTF_8)
            .contains("MAPLESU_HOOK_RESTORE_UNVERIFIED"))
        assertArrayEquals(binary, entries["allowlist.bin"])
        assertEquals(process, File(directory, "a-process.txt").readText())
        assertEquals(status, File(directory, "z-status.json").readText())
        assertFalse(temporary.root.listFiles()!!.any { it.name.startsWith(".redacted-") })
    }

    @Test fun hashesAndDiagnosticTokensRemainIntactWhileCredentialShapedWordsAreRedacted() {
        val token = "0123456789abcdef"
        val hash = token.repeat(4)
        val plain = "word=" + "a".repeat(48) + "\nsha256=$hash\n" +
            "token=MAPLESU_HOOK_RESTORE_UNVERIFIED\nordinary=$token\n"
        assertEquals(plain.replace("a".repeat(48), "<REDACTED-KEY>"), BugreportArchive.redact(plain))
        assertEquals("sha256=$hash", BugreportArchive.redact("sha256=$hash", listOf(token)))
        assertEquals("{\"suToken\":\"<REDACTED-TOKEN>\"}",
            BugreportArchive.redact("{\"suToken\":1234567890123456}", listOf("1234567890123456")))
    }

    @Test fun redactionPreservesUnicodeLineEndingsAndValuesAcrossReadBuffers() {
        val directory = temporary.newFolder("buffer-report")
        val key = "Abc9".repeat(12)
        val text = "字".repeat(8184) + " rootKey=\"$key\"\r\nlast line without newline"
        File(directory, "native-stage.log").writeText(text)
        val report = BugreportArchive.pack(directory, File(temporary.root, "buffer.tar.gz"))
        assertEquals(BugreportArchive.redact(text), unpack(report).getValue("native-stage.log").toString(Charsets.UTF_8))
    }

    @Test fun collectionFilesExistBeforeCollectionAndWritesPreserveTheirOwnerAndIdentity() {
        val directory = temporary.newFolder("app-owned-staging")
        val file = File(directory, "root-log.txt")
        BugreportArchive.prepareCollectionFiles(listOf(file))
        assertTrue(file.canRead() && file.canWrite())
        val owner = Files.getOwner(file.toPath())
        val identity = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()
        file.outputStream().use { it.write("root diagnostic".toByteArray()) }
        assertEquals(owner, Files.getOwner(file.toPath()))
        assertEquals(identity, Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey())
        assertThrows(IllegalStateException::class.java) { BugreportArchive.prepareCollectionFiles(listOf(file)) }
        assertEquals("root diagnostic", file.readText())
    }

    @Test fun stagingIsCleanedOnBothSuccessAndAnEarlyCollectionFailure() {
        lateinit var success: File
        assertEquals("collected", BugreportArchive.withStagingDirectory(temporary.root) { directory ->
            success = directory
            File(directory, "evidence.txt").writeText("evidence")
            "collected"
        })
        assertFalse(success.exists())
        lateinit var failed: File
        assertThrows(IOException::class.java) {
            BugreportArchive.withStagingDirectory(temporary.root) { directory ->
                failed = directory
                File(directory, "partial.txt").writeText("partial")
                throw IOException("collection failed before packing")
            }
        }
        assertFalse(failed.exists())
    }

    @Test fun aPublicationFailureLeavesNoTemporaryArchiveOrRedactedCopies() {
        val directory = temporary.newFolder("publication-source")
        File(directory, "basic.txt").writeText("plain diagnostics")
        val blocked = temporary.newFolder("publication-blocked")
        assertThrows(IOException::class.java) { BugreportArchive.pack(directory, blocked) }
        assertFalse(temporary.root.listFiles()!!.any {
            it.name.startsWith(".bugreport-") || it.name.startsWith(".redacted-")
        })
        assertEquals("plain diagnostics", File(directory, "basic.txt").readText())
    }

    @Test fun destinationWriteFailuresAreReportedAndCloseTheOutput() {
        val report = temporary.newFile("failed-copy.tar.gz").apply { writeText("archive") }
        var closed = false
        val output = object : java.io.OutputStream() {
            override fun write(value: Int) { throw IOException("destination disconnected") }
            override fun close() { closed = true }
        }
        assertThrows(IOException::class.java) { BugreportArchive.copy(report) { output } }
        assertTrue(closed)
    }

    @Test fun unlabelledHistoricalAndTemporaryCredentialsAreRedactedWithoutTheCurrentKey() {
        val historical = "A1".repeat(24)
        val temporaryKey = "Z9".repeat(24)
        val text = "历史[$historical] 临时=$temporaryKey\nargv --credential $historical"
        assertEquals("历史[<REDACTED-KEY>] 临时=<REDACTED-KEY>\nargv --credential <REDACTED-KEY>",
            BugreportArchive.redact(text))
    }

    @Test fun keyLikeRedactionKeepsLongerAlphanumericTokensAndSha256Whole() {
        val key = "Abc9".repeat(12)
        val sha256 = "0123456789abcdef".repeat(4)
        val longer = listOf("a".repeat(47), "b".repeat(49), key + "A", "B" + key,
            key + key, "c".repeat(128), sha256)
        val unchanged = longer.joinToString(" ")
        assertEquals(unchanged, BugreportArchive.redact(unchanged))
        assertEquals("_<REDACTED-KEY>_ -<REDACTED-KEY>- 中文<REDACTED-KEY>🙂",
            BugreportArchive.redact("_${key}_ -$key- 中文${key}🙂"))
    }

    @Test fun archivesRedactUnlabelledKeysAcrossReadBuffersAndKeepUnicodeAndOriginalBytes() {
        val directory = temporary.newFolder("unlabelled-buffer-report")
        val historical = "A1".repeat(24)
        val temporaryKey = "Z9".repeat(24)
        val sha256 = "0123456789abcdef".repeat(4)
        val expected = linkedMapOf<String, String>()
        val originals = linkedMapOf<File, ByteArray>()
        for (offset in listOf(8150, 8170, 8180, 8190, 8191, 8192, 8193, 16370)) {
            val prefix = "字".repeat(offset) + "🙂 |"
            val text = prefix + historical + "|\r\n尾行 $temporaryKey sha256=$sha256"
            val file = File(directory, "诊断-$offset.log")
            file.writeText(text)
            originals[file] = file.readBytes()
            expected[file.name] = prefix + "<REDACTED-KEY>|\r\n尾行 <REDACTED-KEY> sha256=$sha256"
        }
        val archive = BugreportArchive.pack(directory, File(temporary.root, "unlabelled-buffer.tar.gz"))
        val entries = unpack(archive)
        assertEquals(expected.keys, entries.keys)
        expected.forEach { (name, text) ->
            assertEquals(name, text, entries.getValue(name).toString(Charsets.UTF_8))
        }
        originals.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
        assertFalse(temporary.root.listFiles()!!.any { it.name.startsWith(".redacted-") })
    }

    @Test fun archivedHashesAndLongTokensAcrossReadBuffersRemainByteForByteIntact() {
        val directory = temporary.newFolder("long-token-buffer-report")
        val tokens = listOf("0123456789abcdef".repeat(4), "Abc9".repeat(24), "Z".repeat(128))
        val originals = linkedMapOf<File, ByteArray>()
        tokens.forEachIndexed { index, token ->
            val file = File(directory, "哈希-$index.txt")
            file.writeText("字".repeat(8185) + "🙂 " + token + "\r\n末行🙂")
            originals[file] = file.readBytes()
        }
        val archive = BugreportArchive.pack(directory, File(temporary.root, "long-token-buffer.tar.gz"))
        val entries = unpack(archive)
        originals.forEach { (file, bytes) ->
            assertArrayEquals(file.name, bytes, entries.getValue(file.name))
            assertArrayEquals(file.name, bytes, file.readBytes())
        }
    }


    private fun unpack(report: File): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        GZIPInputStream(report.inputStream()).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    entries[entry.name] = tar.readBytes()
                }
            }
        }
        return entries
    }
}
