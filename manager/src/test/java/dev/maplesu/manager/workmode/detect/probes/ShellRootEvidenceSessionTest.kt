package dev.maplesu.manager.workmode.detect.probes

import dev.maplesu.manager.workmode.control.ControlPaths
import dev.maplesu.manager.workmode.detect.RootChannelKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** root 取证会话的只读命令语义：UNKNOWN(nullptr) 与 false 必须区分。 */
class ShellRootEvidenceSessionTest {

    private class FakeShell(
        private val handler: (String) -> RootCommandResult,
    ) : RootShellSession {
        val commands = mutableListOf<String>()
        var closed = false
        override val runner: RootShellRunner = RootShellRunner { command ->
            commands += command
            handler(command)
        }
        override fun close() { closed = true }
    }

    private fun ok(vararg out: String) = RootCommandResult(true, out.toList())
    private val fail = RootCommandResult(false, emptyList())

    private fun session(handler: (String) -> RootCommandResult): Pair<ShellRootEvidenceSession, FakeShell> {
        val shell = FakeShell(handler)
        return ShellRootEvidenceSession(shell) to shell
    }

    @Test
    fun readControlFileMapsNameAndJoinsOutput() {
        val (session, shell) = session { command ->
            if (command.startsWith("cat ")) ok("{", "\"schema\":1", "}") else fail
        }
        assertEquals("{\n\"schema\":1\n}", session.readControlFile("modes.json"))
        assertTrue(shell.commands.single().contains("${ControlPaths.CONTROL}/modes.json"))
    }

    @Test
    fun readControlFileSupportsSharedSubpath() {
        val (session, shell) = session { command ->
            if (command.startsWith("cat ")) ok("{}") else fail
        }
        session.readControlFile("shared/superuser.json")
        assertTrue(shell.commands.single().contains("${ControlPaths.ROOT}/shared/superuser.json"))
    }

    @Test
    fun readControlFileFailureIsUnknown() {
        val (session, _) = session { fail }
        assertNull(session.readControlFile("modes.json"))
    }

    @Test
    fun isExecutableUsesTestX() {
        val (session, shell) = session { command -> if (command.startsWith("test -x ")) ok() else fail }
        assertEquals(true, session.isExecutable("/data/adb/ksud"))
        assertEquals("test -x '/data/adb/ksud'", shell.commands.single())
        val (missing, _) = session { fail }
        assertEquals(false, missing.isExecutable("/data/adb/ksud"))
    }

    @Test
    fun isRealDirectoryRejectsSymlink() {
        val (symlinkDir, _) = session { command ->
            when {
                command.startsWith("test -d ") -> ok()
                command.startsWith("test -L ") -> ok()
                else -> fail
            }
        }
        assertEquals(false, symlinkDir.isRealDirectory("/data/adb/ksu"))

        val (realDir, _) = session { command ->
            if (command.startsWith("test -d ")) ok() else fail
        }
        assertEquals(true, realDir.isRealDirectory("/data/adb/ksu"))
    }

    @Test
    fun hasAnyEntryDistinguishesEmptyAndNonEmpty() {
        val (empty, _) = session { command ->
            when {
                command.startsWith("test -d ") || command.startsWith("test -L ") && false -> ok()
                command.startsWith("ls -A ") -> RootCommandResult(true, emptyList())
                else -> fail
            }
        }
        // 先 test -d（ok）→ test -L（fail=false，表示不是软链）→ ls -A 空 → false。
        assertEquals(false, empty.hasAnyEntry("/data/adb/ksu"))

        val (nonEmpty, _) = session { command ->
            when {
                command.startsWith("test -d ") -> ok()
                command.startsWith("ls -A ") -> ok("bin", ".allowlist")
                else -> fail
            }
        }
        assertEquals(true, nonEmpty.hasAnyEntry("/data/adb/ksu"))
    }

    @Test
    fun runnerThrowIsUnknownNotFalse() {
        val (session, shell) = session { throw IllegalStateException("shell died") }
        assertNull(session.isExecutable("/data/adb/ksud"))
        assertNull(session.isRealDirectory("/data/adb/ksu"))
        assertNull(session.hasAnyEntry("/data/adb/ksu"))
        assertNull(session.exists("/data/adb/ksu"))
        assertNull(session.readControlFile("modes.json"))
        assertTrue(shell.commands.isNotEmpty())
    }

    @Test
    fun existsUsesTestEAndCloseClosesSession() {
        val (session, shell) = session { command -> if (command.startsWith("test -e ")) ok() else fail }
        assertEquals(true, session.exists("/data/adb/modules"))
        session.close()
        assertTrue(shell.closed)
    }

    @Test
    fun adapterWithoutOpenerIsUnknown() {
        var requested: RootChannelKind? = null
        val adapter = RootEvidenceReaderAdapter { channel ->
            requested = channel
            null
        }
        assertNull(adapter.open(RootChannelKind.KSU_NATIVE))
        assertEquals(RootChannelKind.KSU_NATIVE, requested)
    }

    @Test
    fun adapterWrapsOpenedSession() {
        val shell = FakeShell { command -> if (command.startsWith("cat ")) ok("{}") else fail }
        val adapter = RootEvidenceReaderAdapter {
            object : RootShellSession {
                override val runner: RootShellRunner = shell.runner
                override fun close() { shell.close() }
            }
        }
        val session = adapter.open(RootChannelKind.LITE_DIRECT)
        assertEquals("{}", session?.readControlFile("modes.json"))
        session?.close()
    }

    @Test
    fun quoteEscapesSingleQuotes() {
        assertEquals("'/data/a'", ShellRootEvidenceSession.quote("/data/a"))
        assertEquals("'/data/a'\\''b'", ShellRootEvidenceSession.quote("/data/a'b"))
    }

    @Test
    fun adapterOpenerThrowIsUnknown() {
        val adapter = RootEvidenceReaderAdapter { error("no channel") }
        assertNull(adapter.open(RootChannelKind.LITE_ULTRA))
    }
}
