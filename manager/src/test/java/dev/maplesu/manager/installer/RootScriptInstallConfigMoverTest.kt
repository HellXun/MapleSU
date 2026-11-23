package dev.maplesu.manager.installer

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.storage.MoveSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 4b/4e/4f 的 root 脚本实现（Pro 根通道唯一可用的搬移方式）。
 *
 * 主机上**不能执行** toybox/bash，所以这里钉三件事：
 * 1. 计划来自 `MoveSet`（顺序/路径/挂载类型都不是本类自己发明的）；
 * 2. 每个动作的脚本形状正确（同挂载 `mv`、跨挂载 `cp -a` + `sync` + 删源、动作后复核）；
 * 3. 解析是 fail-closed 的（没有 `OK`/`SKIPPED` 标记就不算成功）。
 */
class RootScriptInstallConfigMoverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val log = mutableListOf<String>()

    private fun mover(
        runner: (String) -> RootScriptResult,
        dynamicFrom: List<dev.maplesu.manager.workmode.storage.MoveEntry> = emptyList(),
        dynamicTo: List<dev.maplesu.manager.workmode.storage.MoveEntry> = emptyList(),
    ): Pair<RootScriptInstallConfigMover, MutableList<String>> {
        val scripts = mutableListOf<String>()
        val instance = RootScriptInstallConfigMover(
            runner = { script ->
                scripts += script
                runner(script)
            },
            manifestFile = File(tmp.root, "tx/$switchId/move-manifest.json"),
            dynamicFrom = dynamicFrom,
            dynamicTo = dynamicTo,
            log = { log += it },
        )
        return instance to scripts
    }

    /** 成功脚本的输出必须**含独立一行 `OK`**（解析器按行匹配，不做子串推断）。 */
    private fun ok(script: String): RootScriptResult {
        require(script.isNotBlank())
        return RootScriptResult(true, 0, "OK")
    }

    private fun marker(script: String): String = script.lineSequence().first().removeSuffix("\n")

    /**
     * 标记格式：`#maplesu:move:<phase>:<direction>:<livePath>`（第 4 段是**搬移方向**，
     * `live2store` / `store2live`）。方向进标记是为了让 S3 能被机器断言：
     * "4b 失败后回滚不得出现 live2store"。
     */
    private fun phaseOf(script: String): String = marker(script).split(':').getOrElse(2) { "" }

    private fun directionOf(script: String): String = marker(script).split(':').getOrElse(3) { "" }

    private fun pathOf(script: String): String = marker(script).split(':').drop(4).joinToString(":")

    private val switchId = "0f8fad5b-d9cb-469f-a165-70867728950e"

    @Test fun newlyDeployedNormalUserlandIsParkedBeforeUltraRollback() {
        for (partial in listOf(true, false)) {
            val root = tmp.newFolder("deployment-$partial")
            fun shellPath(file: File): String {
                val value = file.absolutePath.replace('\\', '/')
                return if (System.getProperty("os.name")?.startsWith("Windows") == true)
                    "/" + value[0].lowercaseChar() + value.substring(2) else value
            }
            val bash = if (System.getProperty("os.name")?.startsWith("Windows") == true)
                File(System.getenv("ProgramFiles") ?: "C:/Program Files", "Git/bin/bash.exe").path else "/bin/bash"
            fun fixture(path: String) = File(root, path.trimStart('/'))
            fixture("/data/adb/ksu").mkdirs()
            fixture("/data/adb/maplesu/source-state").apply { parentFile!!.mkdirs(); writeText("ultra") }
            val instance = RootScriptInstallConfigMover(
                runner = { script ->
                    val mapped = script.replace("'/data/", "'" + shellPath(File(root, "data")) + "/")
                        .replace("'/metadata/", "'" + shellPath(File(root, "metadata")) + "/")
                    val file = File(root, "action.sh").apply { writeText("export PATH=/usr/bin:/bin\n" + mapped) }
                    val builder = ProcessBuilder(bash, shellPath(file)).directory(root).redirectErrorStream(true)
                    builder.environment()["MSYS_NO_PATHCONV"] = "1"
                    builder.environment()["PATH"] = "/usr/bin:/bin"
                    val process = builder.start()
                    val output = process.inputStream.bufferedReader().readText()
                    val code = process.waitFor()
                    if (code != 0) throw AssertionError("exit=$code\n$output\n$mapped")
                    RootScriptResult(code == 0, code, output)
                }, manifestFile = File(root, "manifest.json"),
            )
            val outbound = instance.moveOut(switchId, WorkMode.LITE, WorkMode.LITE)
            assertTrue(outbound.toString(), outbound.ok)
            val inbound = instance.moveIn(switchId, WorkMode.LITE, WorkMode.LITE)
            assertTrue(inbound.toString(), inbound.ok)
            val materialized = instance.materialize(switchId, WorkMode.LITE, WorkMode.LITE)
            assertTrue(materialized.toString(), materialized.ok)
            fixture("/data/adb/ksu/bin/busybox").apply { parentFile!!.mkdirs(); writeText("normal") }
            if (!partial) fixture("/data/adb/ksud").writeText("normal-daemon")
            val outcome = instance.rollback(switchId, WorkMode.LITE, WorkMode.LITE,
                setOf(InstallMovePhase.OUT, InstallMovePhase.IN))
            assertTrue(outcome.detail, outcome.ok)
            assertTrue(fixture("/data/adb/ksu").isDirectory)
            assertEquals(emptyList<String>(), fixture("/data/adb/ksu").listFiles().orEmpty().map { it.name })
            assertEquals("ultra", fixture("/data/adb/maplesu/source-state").readText())
            assertFalse(fixture("/data/adb/ksud").exists())
            assertEquals("normal", fixture(MoveSet.storePathFor(WorkMode.LITE,
                "/data/adb/ksu") + "/bin/busybox").readText())
        }
    }

    @Test
    fun moveOutFollowsMoveSetOutboundOrderWithRename() {
        val (instance, scripts) = mover({ ok(it) })
        val outcome = instance.moveOut(switchId, WorkMode.LITE, WorkMode.LITE)
        assertTrue(outcome.ok)
        val expected = MoveSet.outboundOrder(MoveSet.LITE_MOVE_SET)
        assertEquals(expected.size, scripts.size)
        assertEquals(expected.map { it.livePath }, scripts.map { pathOf(it) })
        scripts.forEach { script ->
            assertTrue(marker(script).startsWith("#maplesu:move:out:"))
            assertFalse(script.contains("cp -a"))
            assertTrue(script.contains("SOURCE_REMAINS"))
            assertTrue(script.contains("TARGET_MISSING"))
            if (pathOf(script) == "/data/adb/maplesu") {
                // M5：含 installer 锁的条目走合并搬移，绝不整树 rename（见 installerLockSubtree…）。
                assertFalse(script.contains("mv \"\$L\" \"\$S\""))
            } else {
                // 同挂载只允许 rename（A1：失败不许静默降级成 copy）。
                assertTrue(script.contains("mv \"\$L\" \"\$S\""))
            }
        }
        assertTrue(File(tmp.root, "tx/$switchId/move-manifest.json").isFile)
    }

    @Test
    fun crossMountEntriesUseCopySyncThenDeleteSource() {
        val (instance, scripts) = mover({ ok(it) })
        assertTrue(instance.moveIn(switchId, WorkMode.LITE, WorkMode.LITE).ok)
        val metadata = scripts.filter { pathOf(it).startsWith("/metadata/") }
        assertTrue("/metadata entries must exist in the KSU move set", metadata.isNotEmpty())
        metadata.forEach { script ->
            assertTrue(script.contains("cp -a \"\$L\" \"\$S\""))
            assertTrue(script.contains("sync"))
            assertTrue(script.contains("rm -rf \"\$L\""))
            assertFalse(script.contains("mv \"\$L\" \"\$S\""))
        }
    }

    @Test
    fun absentSourceIsSkippedNotFailed() {
        val (instance, scripts) = mover({ RootScriptResult(true, 0, "SKIPPED") })
        assertTrue(instance.moveOut(switchId, WorkMode.LITE, WorkMode.LITE).ok)
        assertEquals(MoveSet.outboundOrder(MoveSet.LITE_MOVE_SET).size, scripts.size)
        assertTrue(log.any { it.contains("skipped=") })
    }

    @Test
    fun firstFailureStopsAndMapsToStableMoveCode() {
        val (instance, scripts) = mover({ RootScriptResult(false, 41, "DEST_EXISTS") })
        val outcome = instance.moveOut(switchId, WorkMode.LITE, WorkMode.LITE)
        assertFalse(outcome.ok)
        assertEquals(MoveSet.Errors.DEST_EXISTS, outcome.code)
        assertEquals(1, scripts.size)
    }

    @Test
    fun exitZeroWithoutMarkerIsNotSuccess() {
        val (instance, _) = mover({ RootScriptResult(true, 0, "") })
        val outcome = instance.moveOut(switchId, WorkMode.LITE, WorkMode.LITE)
        assertFalse("no marker must never be treated as success", outcome.ok)
        assertEquals(MoveSet.Errors.VERIFY_FAILED, outcome.code)
    }

    @Test
    fun transportUncertaintySurvivesMoveAndRollbackPhases() {
        val uncertain = "MODE_SWITCH_ROOT_OPERATION_UNCERTAIN"
        val operations: List<(RootScriptInstallConfigMover) -> InstallMoveOutcome> = listOf(
            { it.moveOut(switchId, WorkMode.LITE, WorkMode.LITE) },
            { it.moveIn(switchId, WorkMode.LITE, WorkMode.LITE) },
            { it.rollback(switchId, WorkMode.LITE, WorkMode.LITE, setOf(InstallMovePhase.OUT)) },
        )
        operations.forEach { operation ->
            val (instance, scripts) = mover({ RootScriptResult(false, -1, "RENAME_FAILED", uncertain) })
            val outcome = operation(instance)
            assertFalse(outcome.ok)
            assertEquals(uncertain, outcome.code)
            assertEquals(1, scripts.size)
        }
    }

    @Test
    fun materializePreservesUncertaintyBeforeInterpretingSuccessMarker() {
        val uncertain = "MODE_SWITCH_ROOT_OPERATION_UNCERTAIN"
        val (instance, scripts) = mover({ RootScriptResult(true, 0, "OK", uncertain) })
        val outcome = instance.materialize(switchId, WorkMode.LITE, WorkMode.LITE)
        assertFalse(outcome.ok)
        assertEquals(uncertain, outcome.code)
        assertEquals(1, scripts.size)
    }

    @Test
    fun uncertaintyInLegacyOutputIsPreservedAndEvidenceIsRedacted() {
        val uncertain = "MODE_SWITCH_ROOT_OPERATION_UNCERTAIN"
        val (instance, _) = mover({ RootScriptResult(false, -1, "$uncertain /data/0123456789abcdef/state") })
        val outcome = instance.moveOut(switchId, WorkMode.LITE, WorkMode.LITE)
        assertEquals(uncertain, outcome.code)
        assertFalse(outcome.detail.contains("0123456789abcdef"))
        assertFalse(File(tmp.root, "tx/$switchId/move-manifest.json").readText().contains("0123456789abcdef"))
    }

    @Test
    fun rollbackOfOutOnlyNeverTouchesInboundDirection() {
        val (instance, scripts) = mover({ ok(it) })
        assertTrue(instance.rollback(switchId, WorkMode.LITE, WorkMode.LITE, setOf(InstallMovePhase.OUT)).ok)
        // 只有 outbound 的 store→live；一条 live→store 都不许有。
        val outbound = MoveSet.outboundOrder(MoveSet.LITE_MOVE_SET).map { it.livePath }.reversed()
        assertEquals(outbound, scripts.map { pathOf(it) })
        assertTrue(
            "4b 失败后不得把 live 搬进目标槽: ${scripts.map { directionOf(it) }}",
            scripts.all { directionOf(it) == RootScriptInstallConfigMover.DIR_STORE_TO_LIVE },
        )
        // KSU 槽（目标模式）绝不能被写入。
        scripts.forEach { script ->
            assertFalse(
                "rollback 不得引用目标模式的 store: $script",
                script.contains(MoveSet.storePathFor(WorkMode.LITE, "/data/adb/modules")),
            )
        }
    }

    @Test
    fun rollbackWithNoCompletedPhaseDoesNothing() {
        val (instance, scripts) = mover({ ok(it) })
        assertTrue(instance.rollback(switchId, WorkMode.LITE, WorkMode.LITE, emptySet()).ok)
        assertTrue("没有任何阶段执行过就不许动文件: $scripts", scripts.isEmpty())
    }

    // ------------------------------------------------------------------ S2：4f 判据对齐参照实现

    @Test
    fun materializeFailureIsExplicit() {
        val (instance, _) = mover({ RootScriptResult(false, 52, "STORE_REMNANT") })
        val outcome = instance.materialize(switchId, WorkMode.LITE, WorkMode.LITE)
        assertFalse(outcome.ok)
        assertEquals(InstallCommitCodes.MATERIALIZE_FAILED, outcome.code)
    }

    // ------------------------------------------------------------------ M5：installer 子树不搬

    @Test
    fun installerLockSubtreeIsNeverMovedWithItsParent() {
        val (instance, scripts) = mover({ ok(it) })
        assertTrue(instance.moveOut(switchId, WorkMode.LITE, WorkMode.LITE).ok)
        val maplesu = scripts.first { pathOf(it) == "/data/adb/maplesu" }
        // 绝不整树 rename（那会把 installer 锁目录一起搬走）。
        assertFalse("不得整树搬 /data/adb/maplesu: $maplesu", maplesu.contains("mv \"\$L\" \"\$S\""))
        // 逐子项搬移 + 显式跳过 installer + 事后复核它仍在原处。
        assertTrue(maplesu.contains("for c in"))
        assertTrue(maplesu.contains("'installer') continue ;;"))
        assertTrue(maplesu.contains("'lite_hidden') continue ;;"))
        assertTrue(maplesu.contains("'normal-webui-token') continue ;;"))
        assertTrue(maplesu.contains("PRESERVED_MISSING"))
        // 其它条目仍然整树 rename（改动面最小）。
        val policy = scripts.first { pathOf(it) == "/data/adb/maplesu-su-policy" }
        assertTrue(policy.contains("mv \"\$L\" \"\$S\""))
        assertTrue(scripts.none { pathOf(it) == "/data/adb/modules" })
    }

    @Test
    fun rollbackOfPreservedEntryMergesBackIntoLive() {
        val (instance, scripts) = mover({ ok(it) })
        assertTrue(instance.rollback(switchId, WorkMode.LITE, WorkMode.LITE, setOf(InstallMovePhase.OUT)).ok)
        val maplesu = scripts.first { pathOf(it) == "/data/adb/maplesu" }
        // 反向也是逐子项合并（目标是**已存在**的 live 目录，不能 rm/mv 整树）。
        assertTrue(maplesu.contains("for c in"))
        assertTrue(maplesu.contains("DEST_EXISTS"))
        assertFalse(maplesu.contains("mv \"\$L\" \"\$S\""))
    }

    @Test
    fun moveOutCarriesDynamicEntries() {
        val external = dev.maplesu.manager.workmode.storage.MoveSet.dynamicEntries(
            registeredExternal = listOf("/data/adb/tricky_store"),
            owner = WorkMode.LITE,
        )
        val (instance, scripts) = mover({ ok(it) }, dynamicFrom = external)
        assertTrue(instance.moveOut(switchId, WorkMode.LITE, WorkMode.LITE).ok)
        assertTrue(scripts.any { pathOf(it) == "/data/adb/tricky_store" })
    }
}
