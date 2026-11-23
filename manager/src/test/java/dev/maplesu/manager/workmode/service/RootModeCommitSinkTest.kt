package dev.maplesu.manager.workmode.service

import dev.maplesu.manager.runtime.RootBackend
import dev.maplesu.manager.runtime.RootExecResult
import dev.maplesu.manager.workmode.Hashes
import dev.maplesu.manager.workmode.TestFixtures
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.control.ControlJsonCodec
import dev.maplesu.manager.workmode.control.schema.ModesFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RootModeCommitSinkTest {
    @get:Rule val temporary = TemporaryFolder()
    private val control by lazy { temporary.newFolder("control with spaces") }
    private val staging by lazy { temporary.newFolder("app staging") }
    private val modes get() = File(control, "modes.json")
    private val sha get() = File(control, "modes.json.sha256")
    private val bootId = "0f8fad5b-d9cb-469f-a165-70867728950e"

    private fun seed(current: String = "A", last: String? = null) {
        modes.writeText(ControlJsonCodec.encode(TestFixtures.modesFile(current = current, last = last), ModesFile.serializer()))
        sha.writeText(Hashes.sha256(modes.readBytes()))
    }

    private fun shell(script: String): RootExecResult {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val executable = if (windows) "C:/Program Files/Git/bin/bash.exe" else "/bin/sh"
        val portable = script.replace("/system/bin/", "")
            .replace(staging.absolutePath, staging.absolutePath.replace('\\', '/'))
        // stdin avoids Windows command-line quoting changing the actual shell script.
        val process = ProcessBuilder(executable).redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write("chown() { :; }\n$portable") }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return RootExecResult(RootBackend.KSU, process.waitFor(), output)
    }

    private fun sink(execute: (String) -> RootExecResult = ::shell) = RootModeCommitSink(
        execute, clock = { 2000L }, root = control.absolutePath.replace('\\', '/'))

    private fun read() = requireNotNull(ControlJsonCodec.decodeOrNull(modes.readText(), ModesFile.serializer()))

    @Test fun verifiedSwitchPublishesModeAndPreservesBaseline() {
        seed()
        sink().commit(WorkMode.LITE, bootId, TestFixtures.hash('b'), TestFixtures.hash('c'))
        val result = read()
        assertEquals("B", result.current)
        assertEquals("A", result.last)
        assertEquals(2L, result.revision)
        assertEquals("bl-1", result.boot.baselineId)
        assertEquals(bootId, result.boot.verifiedBootId)
        assertEquals(TestFixtures.hash('b'), result.boot.bootSha256)
        assertEquals(TestFixtures.hash('c'), result.boot.initBootSha256)
        assertEquals(Hashes.sha256(modes.readBytes()), sha.readText())
        assertTrue(staging.listFiles().orEmpty().isEmpty())
        assertEquals(setOf("modes.json", "modes.json.sha256"), control.list().orEmpty().toSet())
    }

    @Test fun firstSwitchInitializesMissingModesFromObservedFacts() {
        control.mkdirs()
        RootModeCommitSink(::shell, clock = { 2000L },
            root = control.absolutePath.replace('\\', '/'),
            initial = { TestFixtures.modesFile(current = "NONE", revision = 0) })
            .commit(WorkMode.LITE, bootId, TestFixtures.hash('b'), TestFixtures.hash('c'))
        assertEquals("B", read().current)
        assertNull(read().last)
        assertEquals(1L, read().revision)
        assertEquals(Hashes.sha256(modes.readBytes()), sha.readText())
    }

    @Test fun publicationDoesNotAskRootToOpenAppPrivateFiles() {
        seed()
        sink { script ->
            if (script.contains(staging.absolutePath)) RootExecResult(RootBackend.KSU, 1, "EACCES")
            else shell(script)
        }.commit(WorkMode.LITE, bootId, "", "")
        assertEquals("B", read().current)
    }

    @Test fun repeatedVerificationPreservesLastModeAndUnchangedHashes() {
        seed(current = "B", last = "A")
        sink().commit(WorkMode.LITE, bootId, "", "")
        assertEquals("A", read().last)
        assertEquals(TestFixtures.HASH_64, read().boot.bootSha256)
    }

    @Test fun legacyFileWithoutSidecarCanBeUpdated() {
        seed()
        assertTrue(sha.delete())
        sink().commit(WorkMode.LITE, bootId, "", "")
        assertEquals(Hashes.sha256(modes.readBytes()), sha.readText())
    }

    @Test fun corruptSidecarDoesNotPublishAnotherMode() {
        seed()
        val original = modes.readText()
        sha.writeText("0".repeat(64))
        assertThrows(IllegalStateException::class.java) { sink().commit(WorkMode.LITE, bootId, "", "") }
        assertEquals(original, modes.readText())
    }

    @Test fun concurrentControlUpdateIsNotOverwritten() {
        seed()
        var calls = 0
        val sink = sink { script ->
            if (++calls == 2) {
                modes.writeText(ControlJsonCodec.encode(TestFixtures.modesFile(revision = 99), ModesFile.serializer()))
                sha.writeText(Hashes.sha256(modes.readBytes()))
            }
            shell(script)
        }
        assertThrows(IllegalStateException::class.java) { sink.commit(WorkMode.LITE, bootId, "", "") }
        assertEquals(99L, read().revision)
        assertEquals("A", read().current)
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test fun failedPayloadKeepsControlAndCleansOnlyItsTemporaryFiles() {
        seed()
        val original = modes.readText()
        val sink = sink { script ->
            shell(if (script.contains("/system/bin/printf '%s'")) "base64() { return 1; }\n$script" else script)
        }
        assertThrows(IllegalStateException::class.java) { sink.commit(WorkMode.LITE, bootId, "", "") }
        assertEquals(original, modes.readText())
        assertTrue(staging.listFiles().orEmpty().isEmpty())
        assertEquals(setOf("modes.json", "modes.json.sha256"), control.list().orEmpty().toSet())
    }
}
