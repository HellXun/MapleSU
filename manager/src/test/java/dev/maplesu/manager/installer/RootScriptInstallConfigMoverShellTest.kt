package dev.maplesu.manager.installer

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.storage.ModeSwitchDataPaths
import dev.maplesu.manager.workmode.storage.MoveSet
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Execute production-generated move scripts against an isolated host filesystem. */
class RootScriptInstallConfigMoverShellTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val switchId = "0f8fad5b-d9cb-469f-a165-70867728950e"

    @Test
    fun danglingMetamoduleIsPreservedAcrossBothModes() {
        val fixture = Fixture()
        fixture.write("/data/adb/modules/other/config", "module-config")
        val link = fixture.file("/data/adb/metamodule").toPath()
        val target = File("modules/mountify").toPath()
        Files.createSymbolicLink(link, target)
        for ((from, to) in listOf(WorkMode.LITE to WorkMode.LITE, WorkMode.LITE to WorkMode.LITE)) {
            val mover = fixture.mover(ModeSwitchDataPaths.fromLocal(), from, to)
            assertTrue(mover.moveOut(switchId, from, to).ok)
            assertTrue(mover.moveIn(switchId, from, to).ok)
            val ready = mover.materialize(switchId, from, to)
            assertTrue(ready.toString(), ready.ok)
        }
        assertTrue(Files.isSymbolicLink(link))
        assertEquals(target, Files.readSymbolicLink(link))
        assertFalse(link.toFile().exists())
        assertEquals("module-config", fixture.file("/data/adb/modules/other/config").readText())
    }

    @Test
    fun sharedMetamoduleIsNotReplacedOrRepairedByAModeSwitch() {
        val fixture = Fixture()
        fixture.write("/data/adb/metamodule", "not-a-link")
        for ((from, to) in listOf(WorkMode.LITE to WorkMode.LITE, WorkMode.LITE to WorkMode.LITE)) {
            val result = fixture.mover(ModeSwitchDataPaths.fromLocal(), from, to)
                .materialize(switchId, from, to)
            assertTrue(result.toString(), result.ok)
        }
        assertEquals("not-a-link", fixture.file("/data/adb/metamodule").readText())
    }

    @Test
    fun aRestoredLiveEnvironmentSupersedesItsOldStoreWithoutLosingTheOldSnapshot() {
        val fixture = Fixture()
        fixture.write("/data/adb/maplesu/installer/install.lock", "held-lock")
        fixture.write("/data/adb/maplesu/bin/su", "current-su")
        fixture.write("/data/adb/modules/current/config", "current-module")
        val config = MoveSet.storePathFor(WorkMode.LITE, "/data/adb/maplesu")
        val modules = MoveSet.storePathFor(WorkMode.LITE, "/data/adb/modules")
        fixture.write("$config/bin/su", "old-su")
        fixture.write("$modules/old/config", "old-module")

        val moved = fixture.mover.moveOut(switchId, WorkMode.LITE, WorkMode.LITE)
        assertTrue(moved.toString(), moved.ok)
        assertEquals("current-su", fixture.file("$config/bin/su").readText())
        assertEquals("current-module", fixture.file("/data/adb/modules/current/config").readText())
        assertEquals("old-module", fixture.file("$modules/old/config").readText())
        val archived = fixture.file("${MoveSet.INSTALLER_ROOT}/move-conflicts/$switchId")
            .walkTopDown().filter { it.isFile }.map { it.readText() }.toList()
        assertTrue(archived.toString(), archived.contains("old-su"))
        assertTrue(fixture.mover.moveIn(switchId, WorkMode.LITE, WorkMode.LITE).ok)
        assertEquals("current-su", fixture.file("/data/adb/maplesu/bin/su").readText())
        assertEquals("current-module", fixture.file("/data/adb/modules/current/config").readText())
    }

    @Test
    fun partialOutboundRollbackDoesNotTouchAnUnvisitedOldModuleStore() {
        val fixture = Fixture(
            "mv() { case \"\$1\" in */maplesu/config) return 1 ;; esac; command mv \"\$@\"; }\n",
        )
        fixture.write("/data/adb/maplesu/installer/install.lock", "held-lock")
        fixture.write("/data/adb/maplesu/config", "current-config")
        fixture.write("/data/adb/modules/current/config", "current-module")
        val modules = MoveSet.storePathFor(WorkMode.LITE, "/data/adb/modules")
        fixture.write("$modules/old/config", "old-module")
        assertFalse(fixture.mover.moveOut(switchId, WorkMode.LITE, WorkMode.LITE).ok)

        val rollback = fixture.mover.rollback(switchId, WorkMode.LITE, WorkMode.LITE, setOf(InstallMovePhase.OUT))
        assertTrue(rollback.toString(), rollback.ok)
        assertEquals("current-config", fixture.file("/data/adb/maplesu/config").readText())
        assertEquals("current-module", fixture.file("/data/adb/modules/current/config").readText())
        assertEquals("old-module", fixture.file("$modules/old/config").readText())
    }

    @Test
    fun recoveryCredentialStaysSharedAcrossBothDirections() {
        val fixture = Fixture()
        fixture.write("/data/adb/maplesu/installer/install.lock", "held-lock")
        fixture.write("/data/adb/maplesu/recovery/rootkey", "shared-ciphertext")
        fixture.write("/data/adb/maplesu/config", "ultra-config")
        assertTrue(fixture.mover.moveOut(switchId, WorkMode.LITE, WorkMode.LITE).ok)
        assertEquals("shared-ciphertext", fixture.file("/data/adb/maplesu/recovery/rootkey").readText())
        val store = MoveSet.storePathFor(WorkMode.LITE, "/data/adb/maplesu")
        assertFalse(fixture.file("$store/recovery").exists())
        assertTrue(fixture.mover.moveIn(switchId, WorkMode.LITE, WorkMode.LITE).ok)
        assertEquals("shared-ciphertext", fixture.file("/data/adb/maplesu/recovery/rootkey").readText())
        assertFalse(fixture.file(store).exists())
    }

    @Test
    fun legacyRecoveryCollisionKeepsBothCiphertextsAndDoesNotBlockModeSwitch() {
        val fixture = Fixture()
        fixture.write("/data/adb/maplesu/installer/install.lock", "held-lock")
        fixture.write("/data/adb/maplesu/recovery/rootkey", "new-ciphertext")
        val store = MoveSet.storePathFor(WorkMode.LITE, "/data/adb/maplesu")
        fixture.write("$store/recovery/rootkey", "old-ciphertext")
        fixture.write("$store/config", "ultra-config")
        val result = fixture.mover.moveIn(switchId, WorkMode.LITE, WorkMode.LITE)
        assertTrue(result.toString(), result.ok)
        assertEquals("new-ciphertext", fixture.file("/data/adb/maplesu/recovery/rootkey").readText())
        val archived = fixture.file("/data/adb/maplesu/installer/legacy-recovery").walkTopDown()
            .filter { it.isFile && it.name == "rootkey" }.map { it.readText() }.toList()
        assertEquals(listOf("old-ciphertext"), archived)
        assertEquals("held-lock", fixture.file("/data/adb/maplesu/installer/install.lock").readText())
        assertEquals("ultra-config", fixture.file("/data/adb/maplesu/config").readText())
        assertFalse(fixture.file(store).exists())
        assertTrue(fixture.mover.materialize(switchId, WorkMode.LITE, WorkMode.LITE).ok)
    }

    @Test
    fun normalRecoveryTokenStaysSharedAndLegacyCollisionDoesNotBlockSwitch() {
        val fixture = Fixture()
        val token = "/data/adb/maplesu/normal-webui-token"
        fixture.write("/data/adb/maplesu/installer/install.lock", "held-lock")
        fixture.write(token, "current-normal-token")
        fixture.write("/data/adb/maplesu/config", "ultra-config")
        assertTrue(fixture.mover.moveOut(switchId, WorkMode.LITE, WorkMode.LITE).ok)
        assertEquals("current-normal-token", fixture.file(token).readText())
        val store = MoveSet.storePathFor(WorkMode.LITE, "/data/adb/maplesu")
        assertFalse(fixture.file("$store/normal-webui-token").exists())
        fixture.write("$store/normal-webui-token", "legacy-token")
        val result = fixture.mover.moveIn(switchId, WorkMode.LITE, WorkMode.LITE)
        assertTrue(result.toString(), result.ok)
        assertEquals("current-normal-token", fixture.file(token).readText())
        val archived = fixture.file(MoveSet.LEGACY_RECOVERY_ROOT).walkTopDown()
            .filter { it.isFile && it.name == "normal-webui-token" }.map { it.readText() }.toList()
        assertEquals(listOf("legacy-token"), archived)
        assertFalse(fixture.file(store).exists())
        assertTrue(fixture.mover.materialize(switchId, WorkMode.LITE, WorkMode.LITE).ok)
    }

    @Test
    fun legacyRecoveryIsRestoredWhenNoSharedCredentialExists() {
        val fixture = Fixture()
        fixture.write("/data/adb/maplesu/installer/install.lock", "held-lock")
        val store = MoveSet.storePathFor(WorkMode.LITE, "/data/adb/maplesu")
        fixture.write("$store/recovery/rootkey", "only-ciphertext")
        assertTrue(fixture.mover.moveIn(switchId, WorkMode.LITE, WorkMode.LITE).ok)
        assertEquals("only-ciphertext", fixture.file("/data/adb/maplesu/recovery/rootkey").readText())
        assertFalse(fixture.file(store).exists())
    }

    @Test
    fun rollbackConvergesAnIdenticalRecreatedDaemonButKeepsDifferentBytes() {
        for (same in listOf(true, false)) {
            val fixture = Fixture()
            fixture.write("/data/adb/ksud", "original-daemon")
            assertTrue(fixture.mover.moveOut(switchId, WorkMode.LITE, WorkMode.LITE).ok)
            fixture.write("/data/adb/ksud", if (same) "original-daemon" else "different-daemon")
            val result = fixture.mover.rollback(switchId, WorkMode.LITE, WorkMode.LITE, setOf(InstallMovePhase.OUT))
            assertEquals(result.toString(), same, result.ok)
            assertEquals(if (same) "original-daemon" else "different-daemon", fixture.file("/data/adb/ksud").readText())
            assertEquals(!same, fixture.file(MoveSet.storePathFor(WorkMode.LITE, "/data/adb/ksud")).exists())
        }
    }

    @Test
    fun rollbackRetryDoesNotUndoChildrenWhichWereAlreadyRestored() {
        val fixture = Fixture()
        fixture.write("/data/adb/maplesu/installer/install.lock", "held-lock")
        fixture.write("/data/adb/maplesu/config", "ultra-config")
        fixture.write("/data/adb/modules/ultra/config", "ultra-module")
        val normal = MoveSet.storePathFor(WorkMode.LITE, "/data/adb/modules")
        fixture.write("$normal/normal/config", "normal-module")
        assertTrue(fixture.mover.moveOut(switchId, WorkMode.LITE, WorkMode.LITE).ok)
        assertTrue(fixture.mover.moveIn(switchId, WorkMode.LITE, WorkMode.LITE).ok)
        val conflict = MoveSet.storePathFor(WorkMode.LITE, "/data/adb/maplesu")
        fixture.write("/data/adb/maplesu/config", "unexpected-config")
        val phases = setOf(InstallMovePhase.OUT, InstallMovePhase.IN)
        assertFalse(fixture.mover.rollback(switchId, WorkMode.LITE, WorkMode.LITE, phases).ok)
        fixture.file("/data/adb/maplesu/config").delete()
        val retried = fixture.mover.rollback(switchId, WorkMode.LITE, WorkMode.LITE, phases)
        assertTrue(retried.toString(), retried.ok)
        assertEquals("ultra-module", fixture.file("/data/adb/modules/ultra/config").readText())
        assertEquals("normal-module", fixture.file("$normal/normal/config").readText())
        assertEquals("ultra-config", fixture.file("/data/adb/maplesu/config").readText())
        assertFalse(fixture.file(conflict).exists())
    }

    @Test
    fun moveOutKeepsInstallerAndPreservesAllOtherChildrenInTheUltraStore() {
        val fixture = Fixture()
        fixture.write("/data/adb/maplesu/installer/install.lock", "held-lock")
        fixture.write("/data/adb/maplesu/installer/pending", "pending-task")
        fixture.write("/data/adb/maplesu/settings/config.json", "ultra-config")
        fixture.write("/data/adb/maplesu/.hidden-config", "hidden-config")

        val result = fixture.mover.moveOut(switchId, WorkMode.LITE, WorkMode.LITE)

        assertTrue(result.toString(), result.ok)
        assertEquals("held-lock", fixture.file("/data/adb/maplesu/installer/install.lock").readText())
        assertEquals("pending-task", fixture.file("/data/adb/maplesu/installer/pending").readText())
        assertEquals(listOf("installer"), fixture.file("/data/adb/maplesu").list()!!.toList())
        val store = MoveSet.storePathFor(WorkMode.LITE, "/data/adb/maplesu")
        assertEquals("ultra-config", fixture.file("$store/settings/config.json").readText())
        assertEquals("hidden-config", fixture.file("$store/.hidden-config").readText())
        assertFalse(fixture.file("$store/installer").exists())
    }

    @Test
    fun ultraRoundTripMergesTheOriginalConfigBackWithoutReplacingInstaller() {
        val fixture = Fixture()
        fixture.write("/data/adb/maplesu/installer/install.lock", "held-lock")
        fixture.write("/data/adb/maplesu/settings/config.json", "ultra-config")
        assertTrue(fixture.mover.moveOut(switchId, WorkMode.LITE, WorkMode.LITE).ok)

        val result = fixture.mover.moveIn(switchId, WorkMode.LITE, WorkMode.LITE)

        assertTrue(result.toString(), result.ok)
        assertEquals("held-lock", fixture.file("/data/adb/maplesu/installer/install.lock").readText())
        assertEquals("ultra-config", fixture.file("/data/adb/maplesu/settings/config.json").readText())
        assertFalse(fixture.file(MoveSet.storePathFor(WorkMode.LITE, "/data/adb/maplesu")).exists())
    }

    @Test
    fun rollbackRestoresTheMovedChildrenAndLeavesTheHeldLockInPlace() {
        val fixture = Fixture()
        fixture.write("/data/adb/maplesu/installer/install.lock", "held-lock")
        fixture.write("/data/adb/maplesu/config.json", "ultra-config")
        assertTrue(fixture.mover.moveOut(switchId, WorkMode.LITE, WorkMode.LITE).ok)

        val result = fixture.mover.rollback(switchId, WorkMode.LITE, WorkMode.LITE, setOf(InstallMovePhase.OUT))

        assertTrue(result.toString(), result.ok)
        assertEquals("held-lock", fixture.file("/data/adb/maplesu/installer/install.lock").readText())
        assertEquals("ultra-config", fixture.file("/data/adb/maplesu/config.json").readText())
        assertFalse(fixture.file(MoveSet.storePathFor(WorkMode.LITE, "/data/adb/maplesu")).exists())
    }

    @Test
    fun anUnmovedUnprotectedChildStillFailsVerification() {
        val fixture = Fixture(
            "mv() { case \"\$1\" in */stranded.conf) return 0 ;; esac; command mv \"\$@\"; }\n",
        )
        fixture.write("/data/adb/maplesu/installer/install.lock", "held-lock")
        fixture.write("/data/adb/maplesu/stranded.conf", "must-not-be-lost")

        val result = fixture.mover.moveOut(switchId, WorkMode.LITE, WorkMode.LITE)

        assertFalse(result.toString(), result.ok)
        assertEquals(MoveSet.Errors.VERIFY_FAILED, result.code)
        assertTrue(result.detail, result.detail.contains("SOURCE_REMAINS"))
        assertEquals("must-not-be-lost", fixture.file("/data/adb/maplesu/stranded.conf").readText())
        assertEquals("held-lock", fixture.file("/data/adb/maplesu/installer/install.lock").readText())
    }

    @Test
    fun populatedModuleConfigMetadataAndPrivateTreesRoundTripThroughProductionScripts() {
        val fixture = Fixture()
        val paths = ModeSwitchDataPaths.fromLocal(
            rootKey = "a1b2c3d4e5f60718" + "9".repeat(32), credential = "7".repeat(48).toByteArray(),
        )
        fixture.write("/data/adb/maplesu/installer/install.lock", "held-lock")
        fixture.write("/data/adb/maplesu/settings.json", "ultra-config")
        fixture.write("${MoveSet.ULTRA_IDENTITY_ROOT}/identity.json", "environment-identity")
        fixture.write("${MoveSet.ULTRA_IDENTITY_ROOT}/pairing.apk", "independent-bootstrap")
        fixture.write("/data/adb/maplesu-su-policy/grants.json", "ultra-grants")
        fixture.write("/data/adb/maplesu-policy.lock", "policy-lock")
        fixture.write("/data/adb/modules/ultra/config", "ultra-module")
        fixture.write("/data/adb/modules_update/ultra/module.prop", "ultra-update")
        Files.createSymbolicLink(fixture.file("/data/adb/modules/ultra/current").toPath(), File("config").toPath())
        val normalStore = MoveSet.storePathFor(WorkMode.LITE, "/data/adb/modules")
        fixture.write("$normalStore/normal/config", "normal-module")
        val metadataStore = MoveSet.storePathFor(WorkMode.LITE, "/metadata/ksu")
        fixture.write("$metadataStore/state", "normal-metadata")
        paths.entries(WorkMode.LITE).forEach { fixture.write("${it.livePath}/state", it.livePath) }
        val outward = fixture.mover(paths, WorkMode.LITE, WorkMode.LITE)
        assertTrue(outward.moveOut(switchId, WorkMode.LITE, WorkMode.LITE).ok)
        assertEquals("environment-identity", fixture.file("${MoveSet.ULTRA_IDENTITY_ROOT}/identity.json").readText())
        assertEquals("independent-bootstrap", fixture.file("${MoveSet.ULTRA_IDENTITY_ROOT}/pairing.apk").readText())
        assertFalse(fixture.file(MoveSet.storePathFor(WorkMode.LITE, MoveSet.ULTRA_IDENTITY_ROOT)).exists())
        assertTrue(outward.moveIn(switchId, WorkMode.LITE, WorkMode.LITE).ok)
        val normalReady = outward.materialize(switchId, WorkMode.LITE, WorkMode.LITE)
        assertTrue(normalReady.toString(), normalReady.ok)
        assertEquals("ultra-module", fixture.file("/data/adb/modules/ultra/config").readText())
        assertEquals("normal-module", fixture.file("$normalStore/normal/config").readText())
        assertEquals("normal-metadata", fixture.file("/metadata/ksu/state").readText())
        val returning = fixture.mover(paths, WorkMode.LITE, WorkMode.LITE)
        assertTrue(returning.moveOut(switchId, WorkMode.LITE, WorkMode.LITE).ok)
        assertTrue(returning.moveIn(switchId, WorkMode.LITE, WorkMode.LITE).ok)
        val ultraReady = returning.materialize(switchId, WorkMode.LITE, WorkMode.LITE)
        assertTrue(ultraReady.toString(), ultraReady.ok)
        assertEquals("held-lock", fixture.file("/data/adb/maplesu/installer/install.lock").readText())
        assertEquals("ultra-config", fixture.file("/data/adb/maplesu/settings.json").readText())
        assertEquals("environment-identity", fixture.file("${MoveSet.ULTRA_IDENTITY_ROOT}/identity.json").readText())
        assertEquals("independent-bootstrap", fixture.file("${MoveSet.ULTRA_IDENTITY_ROOT}/pairing.apk").readText())
        assertEquals("ultra-grants", fixture.file("/data/adb/maplesu-su-policy/grants.json").readText())
        assertEquals("policy-lock", fixture.file("/data/adb/maplesu-policy.lock").readText())
        assertEquals("ultra-module", fixture.file("/data/adb/modules/ultra/config").readText())
        assertEquals("ultra-update", fixture.file("/data/adb/modules_update/ultra/module.prop").readText())
        assertTrue(Files.isSymbolicLink(fixture.file("/data/adb/modules/ultra/current").toPath()))
        assertEquals("normal-module", fixture.file("$normalStore/normal/config").readText())
        paths.entries(WorkMode.LITE).forEach { assertEquals(it.livePath, fixture.file("${it.livePath}/state").readText()) }
    }

    @Test
    fun mergeRejectsASymbolicDestinationInsteadOfWritingIntoItsTarget() {
        val fixture = Fixture()
        fixture.write("/data/adb/maplesu/installer/install.lock", "held-lock")
        fixture.write("/data/adb/maplesu/config", "ultra-config")
        val unrelated = fixture.file("/data/other").apply { mkdirs() }
        val destination = fixture.file(MoveSet.storePathFor(WorkMode.LITE, "/data/adb/maplesu"))
        destination.parentFile!!.mkdirs()
        Files.createSymbolicLink(destination.toPath(), unrelated.toPath())
        val result = fixture.mover.moveOut(switchId, WorkMode.LITE, WorkMode.LITE)
        assertFalse(result.ok)
        assertEquals(MoveSet.Errors.DEST_EXISTS, result.code)
        assertEquals("ultra-config", fixture.file("/data/adb/maplesu/config").readText())
        assertTrue(unrelated.list()!!.isEmpty())
    }

    private inner class Fixture(private val prefix: String = "") {
        private val root = tmp.newFolder()
        private val hostRoot = root.absolutePath.replace('\\', '/')
        val mover get() = mover(ModeSwitchDataPaths.fromLocal(), WorkMode.LITE, WorkMode.LITE)

        fun mover(paths: ModeSwitchDataPaths, from: WorkMode, to: WorkMode) = RootScriptInstallConfigMover(
            runner = { script ->
                execute(script.replace("'/data/", "'$hostRoot/data/")
                    .replace("'/metadata/", "'$hostRoot/metadata/"))
            },
            manifestFile = File(root, "move-manifest.json"),
            dynamicFrom = paths.entries(from),
            dynamicTo = paths.entries(to),
        )

        fun file(path: String): File = File(root, path.trimStart('/'))

        fun write(path: String, body: String) {
            file(path).also { it.parentFile!!.mkdirs(); it.writeText(body) }
        }

        private fun execute(script: String): RootScriptResult {
            val shell = if (System.getProperty("os.name", "").startsWith("Windows")) {
                listOf("C:/Program Files/Git/bin/bash.exe", "--noprofile", "--norc", "-s")
            } else {
                listOf("/bin/sh", "-s")
            }
            // A shell may exit before consuming the entire script. File stdin preserves its
            // exit marker instead of turning that expected early exit into a broken pipe.
            val input = File(root, "move-script.sh").apply { writeText(prefix + script + "\n") }
            val process = ProcessBuilder(shell).redirectErrorStream(true).redirectInput(input).start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val code = process.waitFor()
            return RootScriptResult(code == 0, code, output)
        }
    }
}
