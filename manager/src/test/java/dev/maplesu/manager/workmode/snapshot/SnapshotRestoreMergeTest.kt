package dev.maplesu.manager.workmode.snapshot

import dev.maplesu.manager.workmode.TestFixtures
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.control.AtomicControlFile
import dev.maplesu.manager.workmode.detect.BootIdSource
import dev.maplesu.manager.workmode.detect.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 01 §8.1 `SnapshotRestoreMergeTest`：merge 语义 + 恢复顺序 + 损坏不覆盖。 */
class SnapshotRestoreMergeTest {
    private val clock = Clock { 1_000L }
    private val boot = BootIdSource { "boot-1" }

    private fun writer(appDir: File, storeRoot: File) = FileSnapshotWriter(appDir, storeRoot, clock, boot)

    private fun restorer(appDir: File, storeRoot: File) = FileSnapshotRestorer(appDir, storeRoot, clock)

    // ------------------------------------------------------------- SettingsMirror

    @Test
    fun emptyLocalRestoresAll() {
        val mirror = linkedMapOf("ui_mode" to "dark", "check_update" to "true")
        val merged = SettingsMirror.merge(emptyMap(), mirror, mirrorRevision = 3, localRevision = 0, allowOverwrite = false)
        assertEquals(mirror, merged.restored)
        assertEquals(emptyList<String>(), merged.requiresConfirmation)
        assertEquals(null, merged.blocker)
    }

    @Test
    fun nonEmptyLocalFillsMissingOnly() {
        val local = linkedMapOf("ui_mode" to "light")
        val mirror = linkedMapOf("ui_mode" to "dark", "check_update" to "true")
        val merged = SettingsMirror.merge(local, mirror, mirrorRevision = 3, localRevision = 3, allowOverwrite = false)
        assertEquals(mapOf("check_update" to "true"), merged.restored)
        assertEquals(mapOf("ui_mode" to "light"), merged.skipped)
    }

    @Test
    fun higherRevisionRequiresConfirmation() {
        val local = linkedMapOf("ui_mode" to "light")
        val mirror = linkedMapOf("ui_mode" to "dark")
        val merged = SettingsMirror.merge(local, mirror, mirrorRevision = 5, localRevision = 3, allowOverwrite = false)
        assertEquals(listOf("ui_mode"), merged.requiresConfirmation)
        assertEquals(SnapshotErrors.CONFIRM_REQUIRED, merged.blocker)
        assertTrue(merged.restored.isEmpty())
    }

    @Test
    fun allowOverwriteAppliesMirror() {
        val local = linkedMapOf("ui_mode" to "light")
        val mirror = linkedMapOf("ui_mode" to "dark")
        val merged = SettingsMirror.merge(local, mirror, mirrorRevision = 5, localRevision = 3, allowOverwrite = true)
        assertEquals(mapOf("ui_mode" to "dark"), merged.restored)
        assertTrue(merged.requiresConfirmation.isEmpty())
    }

    @Test
    fun sensitiveKeysNeverMirrored() {
        val mirror = linkedMapOf(
            "ui_mode" to "dark",
            "maplesu-ultra/rootKey" to "secret",
            "stealth_code" to "123",
            "some_token" to "t",
        )
        val merged = SettingsMirror.merge(emptyMap(), mirror, mirrorRevision = 1, localRevision = 0, allowOverwrite = false)
        assertEquals(mapOf("ui_mode" to "dark"), merged.restored)
        assertFalse(merged.restored.keys.any { it.contains("rootKey") || it.contains("token") || it.contains("stealth") })
    }

    @Test
    fun restoreOrderAppThenModulesThenSettings() {
        assertEquals(listOf("APP", "MODULES", "SETTINGS"), FileSnapshotRestorer.RESTORE_ORDER)
    }

    // ------------------------------------------------------------- 文件恢复

    @Test
    fun restoreFillsMissingAppFilesOnly() {
        val appDir = TestFixtures.tempDir("restore-app").toFile()
        val storeRoot = TestFixtures.tempDir("restore-store").toFile()
        File(appDir, "wallpaper").mkdirs()
        File(appDir, "wallpaper/a.png").writeBytes(byteArrayOf(1, 2, 3))
        File(appDir, "module-catalog/rev-1").mkdirs()
        File(appDir, "module-catalog/rev-1/module.prop").writeText("id=demo\n")
        val created = writer(appDir, storeRoot).create(WorkMode.LITE, 5, TestFixtures.appSnapshotFacts())
        assertTrue(created is SnapshotResult.Ok)

        // 本地丢失一个文件 + 本地修改另一个文件（不得被无 allowOverwrite 覆盖）。
        File(appDir, "wallpaper/a.png").delete()
        File(appDir, "module-catalog/rev-1/module.prop").writeText("local-edit\n")

        val outcome = restorer(appDir, storeRoot).restore(
            RestoreRequest(WorkMode.LITE, allowOverwrite = false, reason = RestoreReason.AFTER_CLEAR_DATA),
        )
        assertTrue(File(appDir, "wallpaper/a.png").isFile)
        assertEquals("local-edit\n", File(appDir, "module-catalog/rev-1/module.prop").readText())
        assertTrue(outcome.result.blockers.isEmpty())
        assertNotNull(outcome.manifest)
    }

    @Test
    fun restoreMissingSnapshotIsBlocked() {
        val appDir = TestFixtures.tempDir("restore-empty-app").toFile()
        val storeRoot = TestFixtures.tempDir("restore-empty-store").toFile()
        val outcome = restorer(appDir, storeRoot).restore(
            RestoreRequest(WorkMode.LITE, allowOverwrite = false, reason = RestoreReason.USER_REQUEST),
        )
        assertTrue(outcome.result.blockers.contains(SnapshotErrors.SNAPSHOT_NOT_FOUND))
        assertTrue(outcome.settingsToApply.isEmpty())
    }

    @Test
    fun corruptPayloadDoesNotOverwrite() {
        val appDir = TestFixtures.tempDir("restore-corrupt-app").toFile()
        val storeRoot = TestFixtures.tempDir("restore-corrupt-store").toFile()
        File(appDir, "wallpaper").mkdirs()
        File(appDir, "wallpaper/a.png").writeBytes(byteArrayOf(1, 2, 3))
        val created = writer(appDir, storeRoot).create(WorkMode.LITE, 5, TestFixtures.appSnapshotFacts()) as SnapshotResult.Ok
        // 篡改 payload 字节 → 恢复时必须拒绝，且本地不被覆盖。
        File(created.storePath, "payload/app/wallpaper/a.png").writeBytes(byteArrayOf(9, 9, 9))
        File(appDir, "wallpaper/a.png").writeBytes(byteArrayOf(7, 7))

        val outcome = restorer(appDir, storeRoot).restore(
            RestoreRequest(WorkMode.LITE, allowOverwrite = true, reason = RestoreReason.AFTER_CLEAR_DATA),
        )
        assertTrue(outcome.result.blockers.contains(SnapshotErrors.SNAPSHOT_CORRUPT))
        org.junit.Assert.assertArrayEquals(
            byteArrayOf(7, 7),
            File(appDir, "wallpaper/a.png").readBytes(),
        )
    }

    @Test
    fun rootEntriesProduceNoRootBlocker() {
        val appDir = TestFixtures.tempDir("restore-root-app").toFile()
        val storeRoot = TestFixtures.tempDir("restore-root-store").toFile()
        // 手工构造一个只含 root 软链条目的快照（模拟 A 模块软链）。
        val snapshotDir = File(storeRoot, "snapshots/A/rev-9-A")
        snapshotDir.mkdirs()
        val manifest = SnapshotManifests.withIntegrity(
            dev.maplesu.manager.workmode.control.schema.SnapshotManifest(
                id = "rev-9-A",
                revision = 9,
                mode = "A",
                createdAt = 1,
                bootId = "boot-1",
                app = TestFixtures.appSnapshotFacts(),
                entries = listOf(
                    dev.maplesu.manager.workmode.control.schema.SnapshotEntry(
                        kind = SnapshotScope.KIND_ROOT_SYMLINK,
                        livePath = "/data/adb/modules/demo",
                        storePath = "data/data/adb/modules/demo",
                        type = SnapshotScope.TYPE_SYMLINK,
                        size = 0,
                        sha256 = SnapshotManifests.symlinkSha("/data/user/0/files/module-catalog/rev-1"),
                        linkTarget = "/data/user/0/files/module-catalog/rev-1",
                        mode = 0,
                        uid = 0,
                        gid = 0,
                        origin = SnapshotScope.ORIGIN_A,
                    ),
                ),
                excluded = emptyList(),
                totals = SnapshotManifests.totals(emptyList()),
                integrity = dev.maplesu.manager.workmode.control.schema.Integrity(""),
            ),
        )
        AtomicControlFile.writeJson(File(snapshotDir, "manifest.json"), SnapshotManifests.encode(manifest))

        val outcome = restorer(appDir, storeRoot).restore(
            RestoreRequest(WorkMode.LITE, allowOverwrite = false, reason = RestoreReason.AFTER_CLEAR_DATA),
        )
        assertTrue(outcome.result.blockers.contains(SnapshotErrors.NO_ROOT))
        assertEquals(0, outcome.result.moduleLinksRebuilt)
        assertFalse(outcome.result.secretsRestored)
    }

    @Test
    fun restoreMergeCountsAndSettingsApplyMap() {
        val appDir = TestFixtures.tempDir("restore-merge-app").toFile()
        val storeRoot = TestFixtures.tempDir("restore-merge-store").toFile()
        File(appDir, "wallpaper").mkdirs()
        File(appDir, "wallpaper/a.png").writeBytes(byteArrayOf(1))
        writer(appDir, storeRoot).create(WorkMode.LITE, 2, TestFixtures.appSnapshotFacts())
        val outcome = restorer(appDir, storeRoot).restore(
            request = RestoreRequest(WorkMode.LITE, allowOverwrite = false, reason = RestoreReason.USER_REQUEST),
            localSettings = linkedMapOf("ui_mode" to "light"),
            mirrorSettings = linkedMapOf("ui_mode" to "dark", "check_update" to "true"),
            mirrorRevision = 2,
            localRevision = 2,
        )
        assertEquals(1, outcome.result.settingsRestored)
        assertEquals(1, outcome.result.settingsSkipped)
        assertEquals(mapOf("check_update" to "true"), outcome.settingsToApply)
        assertTrue(outcome.requiresConfirmation.isEmpty())
    }
}
