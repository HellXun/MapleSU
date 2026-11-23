package dev.maplesu.manager.workmode.storage

import dev.maplesu.manager.workmode.TestFixtures
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.BootIdSource
import dev.maplesu.manager.workmode.detect.Clock
import dev.maplesu.manager.workmode.switch.FileSwitchJournal
import dev.maplesu.manager.workmode.switch.JournalLine
import dev.maplesu.manager.workmode.switch.SwitchJournal
import dev.maplesu.manager.workmode.switch.SwitchOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** FileMoveExecutor（W04b）：出/入/回滚/跨挂载 copy/四态收敛/journal 顺序。 */
class FileMoveExecutorTest {
    private lateinit var liveRoot: File
    private lateinit var storeRoot: File
    private lateinit var workDir: File
    private val clock = Clock { 1_000L }
    private val boot = BootIdSource { "boot-1" }

    @Before
    fun setUp() {
        workDir = TestFixtures.tempDir("move").toFile()
        liveRoot = File(workDir, "live").apply { mkdirs() }
        storeRoot = File(workDir, "store").apply { mkdirs() }
    }

    private fun executor() = FileMoveExecutor(MovePathRoots(liveRoot, storeRoot))

    private fun journal(file: File = File(workDir, "journal/journal.jsonl")): SwitchJournal =
        FileSwitchJournal(file, clock, boot)

    private fun live(path: String): File = File(liveRoot, path.trimStart('/'))

    private fun store(owner: WorkMode = WorkMode.LITE, path: String): File =
        File(storeRoot, "${owner.suffix}/data/${path.trimStart('/')}")

    private fun entry(
        path: String,
        kind: MoveKind = MoveKind.TREE,
        mount: MoveMount = MoveMount.SAME_AS_STORE,
        owner: WorkMode = WorkMode.LITE,
        shape: String = MoveEntry.REAL_DIR,
    ) = MoveEntry(path, kind, mount, owner, shape)

    private fun plan(
        outbound: List<MoveEntry> = emptyList(),
        inbound: List<MoveEntry> = emptyList(),
        switchId: String = "sw-1",
    ) = MovePlan(switchId, WorkMode.LITE, WorkMode.LITE, outbound, inbound, emptyMap())

    private fun writeLiveTree(path: String) {
        val root = live(path)
        File(root, "bin").mkdirs()
        File(root, "bin/su").writeText("su-binary")
        File(root, "module.prop").writeText("id=demo")
    }

    /** 写单个 live 文件（自动建父目录；Windows 临时根下逻辑路径必须逐级建）。 */
    private fun writeLiveFile(path: String, content: String) {
        val file = live(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    @Test
    fun outboundMovesLiveTreeIntoStore() {
        writeLiveTree("/data/adb/maplesu")
        val report = executor().execute(
            plan(outbound = listOf(entry("/data/adb/maplesu"))), journal(),
        )
        assertTrue("failures=${report.failures}", report.ok)
        assertEquals(listOf("/data/adb/maplesu"), report.movedOut)
        assertFalse(live("/data/adb/maplesu").exists())
        assertEquals("su-binary", File(store(WorkMode.LITE, "/data/adb/maplesu"), "bin/su").readText())
    }

    @Test
    fun legacyRecoveryCollisionIsArchivedWithoutReplacingTheSharedCredential() {
        writeLiveFile("/data/adb/maplesu/installer/install.lock", "held-lock")
        writeLiveFile("/data/adb/maplesu/recovery/rootkey", "new-ciphertext")
        val old = File(store(path = "/data/adb/maplesu"), "recovery/rootkey")
        old.parentFile!!.mkdirs()
        old.writeText("old-ciphertext")
        val report = executor().execute(plan(inbound = listOf(entry("/data/adb/maplesu"))), journal())
        assertTrue(report.failures.toString(), report.ok)
        assertEquals("new-ciphertext", live("/data/adb/maplesu/recovery/rootkey").readText())
        val archived = live("/data/adb/maplesu/installer/legacy-recovery").walkTopDown()
            .filter { it.isFile && it.name == "rootkey" }.map { it.readText() }.toList()
        assertEquals(listOf("old-ciphertext"), archived)
        assertFalse(store(path = "/data/adb/maplesu").exists())
        assertTrue(executor().execute(plan(outbound = listOf(entry("/data/adb/maplesu"))), journal()).ok)
        assertEquals("new-ciphertext", live("/data/adb/maplesu/recovery/rootkey").readText())
        assertFalse(File(store(path = "/data/adb/maplesu"), "recovery").exists())
    }

    @Test
    fun sharedHiddenStateAndNormalTokenSurviveAStoredUltraEnvironment() {
        writeLiveFile("/data/adb/maplesu/installer/install.lock", "held-lock")
        writeLiveFile("/data/adb/maplesu/lite_hidden", "0")
        writeLiveFile("/data/adb/maplesu/normal-webui-token", "current-token")
        val stored = store(path = "/data/adb/maplesu").apply { mkdirs() }
        File(stored, "lite_hidden").writeText("1")
        File(stored, "normal-webui-token").writeText("old-token")
        File(stored, "ultra.conf").writeText("ultra-data")
        val report = executor().execute(plan(inbound = listOf(entry("/data/adb/maplesu"))), journal())
        assertTrue(report.failures.toString(), report.ok)
        assertEquals("0", live("/data/adb/maplesu/lite_hidden").readText())
        assertEquals("current-token", live("/data/adb/maplesu/normal-webui-token").readText())
        assertEquals("ultra-data", live("/data/adb/maplesu/ultra.conf").readText())
        assertFalse(stored.exists())
        assertTrue(executor().execute(plan(outbound = listOf(entry("/data/adb/maplesu"))), journal()).ok)
        assertEquals("0", live("/data/adb/maplesu/lite_hidden").readText())
        assertEquals("current-token", live("/data/adb/maplesu/normal-webui-token").readText())
        assertFalse(File(stored, "lite_hidden").exists())
        assertFalse(File(stored, "normal-webui-token").exists())
    }

    @Test
    fun ultraIdentityFilesStayLiveDuringMoveAndRollback() {
        val identity = MoveSet.ULTRA_IDENTITY_ROOT
        writeLiveFile("$identity/identity.json", "environment-config")
        writeLiveFile("$identity/pairing.apk", "independent-helper")
        writeLiveFile("$identity/start.sh", "environment-startup")
        writeLiveFile("/data/adb/maplesu/ultra.conf", "mode-config")
        val switching = plan(outbound = listOf(entry("/data/adb/maplesu")))
        val executor = executor()
        val journal = journal()
        val moved = executor.execute(switching, journal)
        assertTrue(moved.failures.toString(), moved.ok)
        assertEquals("environment-config", live("$identity/identity.json").readText())
        assertEquals("independent-helper", live("$identity/pairing.apk").readText())
        assertEquals("environment-startup", live("$identity/start.sh").readText())
        assertFalse(File(store(path = "/data/adb/maplesu"), "ultra-identity").exists())
        assertFalse(live("/data/adb/maplesu/ultra.conf").exists())
        val restored = executor.rollback(switching, journal)
        assertTrue(restored.failures.toString(), restored.ok)
        assertEquals("environment-config", live("$identity/identity.json").readText())
        assertEquals("independent-helper", live("$identity/pairing.apk").readText())
        assertEquals("mode-config", live("/data/adb/maplesu/ultra.conf").readText())
    }

    @Test
    fun inboundMovesStoreTreeToLive() {
        val storeRootEntry = store(WorkMode.LITE, "/data/adb/modules")
        File(storeRootEntry, "demo").mkdirs()
        File(storeRootEntry, "demo/module.prop").writeText("id=demo")
        val inbound = entry("/data/adb/modules", owner = WorkMode.LITE)
        val report = executor().execute(plan(inbound = listOf(inbound)), journal())
        assertTrue(report.ok)
        assertEquals(listOf("/data/adb/modules"), report.movedIn)
        assertFalse(storeRootEntry.exists())
        assertTrue(File(live("/data/adb/modules"), "demo/module.prop").isFile)
    }

    @Test
    fun executionIsOutboundThenInbound() {
        writeLiveTree("/data/adb/maplesu")
        val inboundStore = store(WorkMode.LITE, "/data/adb/modules")
        File(inboundStore, "x").mkdirs()
        val report = executor().execute(
            plan(
                outbound = listOf(entry("/data/adb/maplesu")),
                inbound = listOf(entry("/data/adb/modules", owner = WorkMode.LITE)),
            ),
            journal(),
        )
        assertTrue(report.ok)
        assertEquals(1, report.movedOut.size)
        assertEquals(1, report.movedIn.size)
        assertTrue(File(live("/data/adb/modules"), "x").isDirectory)
    }

    @Test
    fun missingSourceIsSkipped() {
        val report = executor().execute(
            plan(outbound = listOf(entry("/data/adb/maplesu"))), journal(),
        )
        assertTrue(report.ok)
        assertEquals(listOf("/data/adb/maplesu"), report.skipped)
        assertTrue(report.movedOut.isEmpty())
    }

    @Test
    fun expectedShapeMismatchBlocksAndKeepsSource() {
        writeLiveFile("/data/adb/maplesu", "not-a-dir")
        val report = executor().execute(
            plan(outbound = listOf(entry("/data/adb/maplesu", shape = MoveEntry.REAL_DIR))), journal(),
        )
        assertEquals(1, report.failures.size)
        assertEquals(MoveSet.Errors.SOURCE_SHAPE_MISMATCH, report.failures.single().code)
        assertTrue(live("/data/adb/maplesu").isFile)
        assertFalse(store(WorkMode.LITE, "/data/adb/maplesu").exists())
    }

    @Test
    fun fileShapeRejectsDirectory() {
        live("/data/adb/ksud").mkdirs()
        val report = executor().execute(
            plan(outbound = listOf(entry("/data/adb/ksud", kind = MoveKind.FILE, shape = MoveEntry.FILE))), journal(),
        )
        assertEquals(MoveSet.Errors.SOURCE_SHAPE_MISMATCH, report.failures.single().code)
    }

    @Test
    fun destinationExistsFailsWithoutTouchingSource() {
        writeLiveTree("/data/adb/maplesu")
        store(WorkMode.LITE, "/data/adb/maplesu").mkdirs()
        val report = executor().execute(
            plan(outbound = listOf(entry("/data/adb/maplesu"))), journal(),
        )
        assertEquals(MoveSet.Errors.DEST_EXISTS, report.failures.single().code)
        assertTrue(live("/data/adb/maplesu").isDirectory)
    }

    @Test
    fun fileEntryMovesSingleFile() {
        writeLiveFile("/data/adb/ksud", "ksud-binary")
        val report = executor().execute(
            plan(outbound = listOf(entry("/data/adb/ksud", kind = MoveKind.FILE, shape = MoveEntry.FILE))),
            journal(),
        )
        assertTrue(report.ok)
        assertEquals("ksud-binary", store(WorkMode.LITE, "/data/adb/ksud").readText())
        assertFalse(live("/data/adb/ksud").exists())
    }

    @Test
    fun symlinkMovesAsLinkPreservingTarget() {
        assumeTrue("symlinks unavailable on this host", symlinkSupported())
        val target = File(workDir, "module-catalog").apply { mkdirs() }
        live("/data/adb").mkdirs()
        Files.createSymbolicLink(live("/data/adb/metamodule").toPath(), target.toPath())
        val report = executor().execute(
            plan(
                outbound = listOf(
                    entry(
                        "/data/adb/metamodule", kind = MoveKind.SYMLINK,
                        shape = MoveEntry.SYMLINK,
                    ),
                ),
            ),
            journal(),
        )
        assertTrue("failures=${report.failures}", report.ok)
        val moved = store(WorkMode.LITE, "/data/adb/metamodule")
        assertTrue(Files.isSymbolicLink(moved.toPath()))
        assertEquals(target.toPath().toString(), Files.readSymbolicLink(moved.toPath()).toString())
    }

    @Test
    fun crossMountCopiesVerifiesAndDeletesSource() {
        val source = live("/metadata/ksu")
        File(source, "sub").mkdirs()
        File(source, "root.txt").writeText("root")
        File(source, "sub/child.txt").writeText("child")
        val report = executor().execute(
            plan(
                outbound = listOf(
                    entry(
                        "/metadata/ksu", mount = MoveMount.CROSS_MOUNT_COPY,
                        owner = WorkMode.LITE, shape = MoveEntry.REAL_DIR,
                    ),
                ),
            ),
            journal(),
        )
        assertTrue("failures=${report.failures}", report.ok)
        val copied = store(WorkMode.LITE, "/metadata/ksu")
        assertEquals("root", File(copied, "root.txt").readText())
        assertEquals("child", File(copied, "sub/child.txt").readText())
        assertFalse(source.exists())
        assertEquals(1, report.movedOut.size)
    }

    @Test
    fun rollbackOutboundRestoresLive() {
        writeLiveTree("/data/adb/maplesu")
        val journal = journal()
        val executor = executor()
        val executePlan = plan(outbound = listOf(entry("/data/adb/maplesu")))
        assertTrue(executor.execute(executePlan, journal).ok)
        assertFalse(live("/data/adb/maplesu").exists())

        val rollback = executor.rollback(executePlan, journal)
        assertTrue("failures=${rollback.failures}", rollback.ok)
        assertEquals(listOf("/data/adb/maplesu"), rollback.movedIn)
        assertEquals("su-binary", File(live("/data/adb/maplesu"), "bin/su").readText())
        assertFalse(store(WorkMode.LITE, "/data/adb/maplesu").exists())
    }

    @Test
    fun rollbackInboundRestoresStore() {
        val storeEntry = store(WorkMode.LITE, "/data/adb/modules")
        File(storeEntry, "demo").mkdirs()
        File(storeEntry, "demo/module.prop").writeText("id=demo")
        val journal = journal()
        val executor = executor()
        val executePlan = plan(inbound = listOf(entry("/data/adb/modules", owner = WorkMode.LITE)))
        assertTrue(executor.execute(executePlan, journal).ok)
        assertTrue(live("/data/adb/modules").isDirectory)

        val rollback = executor.rollback(executePlan, journal)
        assertTrue("failures=${rollback.failures}", rollback.ok)
        assertTrue(File(storeEntry, "demo/module.prop").isFile)
        assertFalse(live("/data/adb/modules").exists())
    }

    @Test
    fun convergeCompletesPendingMove() {
        writeLiveTree("/data/adb/maplesu")
        val report = executor().converge(plan(outbound = listOf(entry("/data/adb/maplesu"))), journal())
        assertTrue("failures=${report.failures}", report.ok)
        assertEquals(listOf("/data/adb/maplesu"), report.movedOut)
        assertTrue(store(WorkMode.LITE, "/data/adb/maplesu").isDirectory)
        assertFalse(live("/data/adb/maplesu").exists())
    }

    @Test
    fun convergeBothSidesExistIsAmbiguousAndTouchesNothing() {
        writeLiveTree("/data/adb/maplesu")
        store(WorkMode.LITE, "/data/adb/maplesu").mkdirs()
        val report = executor().converge(plan(outbound = listOf(entry("/data/adb/maplesu"))), journal())
        assertEquals(MoveSet.Errors.AMBIGUOUS, report.failures.single().code)
        assertTrue(live("/data/adb/maplesu").isDirectory)
        assertTrue(store(WorkMode.LITE, "/data/adb/maplesu").isDirectory)
    }

    @Test
    fun convergeNeitherSideExistsIsSkipped() {
        val report = executor().converge(plan(outbound = listOf(entry("/data/adb/maplesu"))), journal())
        assertTrue(report.failures.isEmpty())
        assertEquals(listOf("/data/adb/maplesu"), report.skipped)
    }

    @Test
    fun convergeSeqGapJournalNeedsRecovery() {
        val journalFile = File(workDir, "journal/journal.jsonl")
        journalFile.parentFile.mkdirs()
        val first = JournalLine(
            seq = 1, switchId = "sw-1", planSha256 = "a".repeat(64), atUptimeMs = 1,
            bootId = "boot-1", stage = "S4", step = "4b", op = SwitchOp.MOVE_INTENT.wire,
            prev = JournalLine.ZERO_PREV,
        )
        val gap = first.copy(seq = 3)
        journalFile.writeText(
            dev.maplesu.manager.workmode.control.ControlJsonCodec.encode(first) + "\n" +
                dev.maplesu.manager.workmode.control.ControlJsonCodec.encode(gap) + "\n",
        )
        writeLiveTree("/data/adb/maplesu")
        val report = executor().converge(plan(outbound = listOf(entry("/data/adb/maplesu"))), journal(journalFile))
        assertEquals("RECOVERY_REQUIRED", report.failures.single().code)
        // 损坏的 journal 不允许据此移动任何路径。
        assertTrue(live("/data/adb/maplesu").isDirectory)
    }

    @Test
    fun journalRecordsIntentBeforeDone() {
        writeLiveTree("/data/adb/maplesu")
        val journal = journal()
        executor().execute(plan(outbound = listOf(entry("/data/adb/maplesu"))), journal)
        val ops = (journal.read() as dev.maplesu.manager.workmode.switch.JournalRead.Ok)
            .lines.map { it.op }
        assertTrue(ops.contains(SwitchOp.MOVE_INTENT.wire))
        assertTrue(ops.contains(SwitchOp.MOVE_DONE.wire))
        assertTrue(ops.indexOf(SwitchOp.MOVE_INTENT.wire) < ops.indexOf(SwitchOp.MOVE_DONE.wire))
    }

    @Test
    fun planSha256IsStableAndSensitive() {
        val first = plan(outbound = listOf(entry("/data/adb/maplesu")))
        val same = plan(outbound = listOf(entry("/data/adb/maplesu")))
        val different = plan(outbound = listOf(entry("/data/adb/sevenk")))
        assertEquals(first.planSha256(), same.planSha256())
        assertFalse(first.planSha256() == different.planSha256())
        assertEquals(64, first.planSha256().length)
    }

    @Test
    fun pathRootsRejectForeignStorePath() {
        try {
            MovePathRoots(liveRoot, storeRoot).store("/tmp/other")
            throw AssertionError("foreign store path accepted")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(
            File(liveRoot, "data/adb/maplesu"),
            MovePathRoots(liveRoot, storeRoot).live("/data/adb/maplesu"),
        )
    }

    @Test
    fun presenceReportsBothSides() {
        writeLiveTree("/data/adb/maplesu")
        val entry = entry("/data/adb/maplesu")
        assertEquals(true to false, executor().presence(entry))
    }

    private fun symlinkSupported(): Boolean = runCatching {
        val probe = File(workDir, "symlink-probe")
        Files.createSymbolicLink(probe.toPath(), workDir.toPath())
        Files.deleteIfExists(probe.toPath())
        true
    }.getOrDefault(false)
}
