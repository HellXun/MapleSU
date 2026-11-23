package dev.maplesu.manager.workmode.snapshot

import dev.maplesu.manager.workmode.TestFixtures
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.control.AtomicControlFile
import dev.maplesu.manager.workmode.control.ShaCheck
import dev.maplesu.manager.workmode.detect.BootIdSource
import dev.maplesu.manager.workmode.detect.Clock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** FileSnapshotService：真实文件编排 + 非 root fail-closed。 */
class FileSnapshotServiceTest {
    private lateinit var appDir: File
    private lateinit var storeRoot: File
    private val clock = Clock { 1_000L }
    private val boot = BootIdSource { "boot-1" }

    @Before
    fun setUp() {
        appDir = TestFixtures.tempDir("snapshot-service-app").toFile()
        storeRoot = TestFixtures.tempDir("snapshot-service-store").toFile()
    }

    private fun service(
        rootAvailable: Boolean = true,
        facts: () -> dev.maplesu.manager.workmode.control.schema.AppSnapshotFacts? = { TestFixtures.appSnapshotFacts() },
        localSettings: () -> Map<String, String> = { emptyMap() },
        mirrorSettings: () -> Map<String, String> = { emptyMap() },
        mirrorRevision: () -> Long = { 0L },
        localRevision: () -> Long = { 0L },
    ) = FileSnapshotService(
        appDir = appDir,
        storeRoot = storeRoot,
        rootAvailable = { rootAvailable },
        appFactsProvider = facts,
        settingsProvider = localSettings,
        mirrorSettingsProvider = mirrorSettings,
        mirrorRevisionProvider = mirrorRevision,
        localRevisionProvider = localRevision,
        clock = clock,
        bootIdSource = boot,
    )

    private fun writeAppFile() {
        File(appDir, "wallpaper").mkdirs()
        File(appDir, "wallpaper/a.png").writeBytes(byteArrayOf(1, 2, 3))
    }

    @Test
    fun createFailsClosedWithoutRoot() = runBlocking {
        writeAppFile()
        val result = service(rootAvailable = false).create(
            SnapshotRequest(WorkMode.LITE, switchId = "sw-1", reason = SnapshotReason.PRE_SWITCH, includeSecrets = false),
        )
        assertTrue(result is SnapshotResult.Skipped)
        assertEquals(SnapshotErrors.NO_ROOT, (result as SnapshotResult.Skipped).reason)
        assertFalse(File(storeRoot, "snapshots").exists())
    }

    @Test
    fun restoreFailsClosedWithoutRoot() = runBlocking {
        writeAppFile()
        val result = service(rootAvailable = false).restore(
            RestoreRequest(WorkMode.LITE, allowOverwrite = false, reason = RestoreReason.AFTER_CLEAR_DATA),
        )
        assertTrue(result.blockers.contains(SnapshotErrors.NO_ROOT))
        assertEquals(0, result.settingsRestored)
        assertTrue(result.moduleLinksRebuilt == 0)
        assertFalse(result.secretsRestored)
        assertFalse(result.superuserApplied)
    }

    @Test
    fun createWritesManifestAndIncrementsRevision() = runBlocking {
        writeAppFile()
        val first = service().create(
            SnapshotRequest(WorkMode.LITE, switchId = "sw-1", reason = SnapshotReason.PRE_SWITCH, includeSecrets = false),
        )
        assertTrue("result=$first", first is SnapshotResult.Ok)
        val firstOk = first as SnapshotResult.Ok
        assertEquals("rev-1-A", firstOk.manifest.id)
        val manifestFile = File(firstOk.storePath, "manifest.json")
        assertTrue(manifestFile.isFile)
        assertEquals(ShaCheck.OK, AtomicControlFile.verifySha(manifestFile))
        assertEquals("A", firstOk.manifest.mode)
        assertTrue(File(firstOk.storePath, "payload/app/wallpaper/a.png").isFile)

        val second = service().create(
            SnapshotRequest(WorkMode.LITE, switchId = "sw-2", reason = SnapshotReason.PERIODIC, includeSecrets = false),
        )
        assertEquals("rev-2-A", (second as SnapshotResult.Ok).manifest.id)
        assertEquals(3L, service().nextRevision(WorkMode.LITE))
        assertEquals(1L, service().nextRevision(WorkMode.LITE))
    }

    @Test
    fun createSkipsWhenAppFactsUnknown() = runBlocking {
        writeAppFile()
        val result = service(facts = { null }).create(
            SnapshotRequest(WorkMode.LITE, switchId = null, reason = SnapshotReason.MANUAL, includeSecrets = false),
        )
        assertTrue(result is SnapshotResult.Skipped)
        assertEquals(FileSnapshotService.APP_FACTS_UNKNOWN, (result as SnapshotResult.Skipped).reason)
    }

    @Test
    fun createRefusesSecretsWithoutDedicatedChannel() = runBlocking {
        writeAppFile()
        val result = service().create(
            SnapshotRequest(WorkMode.LITE, switchId = null, reason = SnapshotReason.MANUAL, includeSecrets = true),
        )
        assertTrue(result is SnapshotResult.Skipped)
        assertEquals(
            FileSnapshotService.SECRETS_REQUIRES_ROOT_CHANNEL,
            (result as SnapshotResult.Skipped).reason,
        )
    }

    @Test
    fun restoreFillsMissingFileAfterCreate() = runBlocking {
        writeAppFile()
        assertTrue(
            service().create(
                SnapshotRequest(WorkMode.LITE, switchId = null, reason = SnapshotReason.PRE_SWITCH, includeSecrets = false),
            ) is SnapshotResult.Ok,
        )
        File(appDir, "wallpaper/a.png").delete()
        val result = service().restore(
            RestoreRequest(WorkMode.LITE, allowOverwrite = false, reason = RestoreReason.AFTER_CLEAR_DATA),
        )
        assertTrue("blockers=${result.blockers}", result.blockers.isEmpty())
        assertTrue(File(appDir, "wallpaper/a.png").isFile)
        assertEquals(3, File(appDir, "wallpaper/a.png").readBytes().size)
    }

    @Test
    fun restoreMergesSettingsAndReportsCounts() = runBlocking {
        writeAppFile()
        assertTrue(
            service().create(
                SnapshotRequest(WorkMode.LITE, switchId = null, reason = SnapshotReason.PRE_SWITCH, includeSecrets = false),
            ) is SnapshotResult.Ok,
        )
        val service = service(
            localSettings = { linkedMapOf("ui_mode" to "light") },
            mirrorSettings = { linkedMapOf("ui_mode" to "dark", "check_update" to "true") },
            mirrorRevision = { 2L },
            localRevision = { 2L },
        )
        val result = service.restore(
            RestoreRequest(WorkMode.LITE, allowOverwrite = false, reason = RestoreReason.AFTER_CLEAR_DATA),
        )
        assertEquals(1, result.settingsRestored)
        assertEquals(1, result.settingsSkipped)
        assertTrue(result.blockers.isEmpty())
    }

    @Test
    fun restoreCorruptManifestIsBlockedWithoutWrites() = runBlocking {
        writeAppFile()
        val created = service().create(
            SnapshotRequest(WorkMode.LITE, switchId = null, reason = SnapshotReason.PRE_SWITCH, includeSecrets = false),
        ) as SnapshotResult.Ok
        File(created.storePath, "manifest.json").writeText("{ corrupt")
        File(appDir, "wallpaper/a.png").delete()
        val result = service().restore(
            RestoreRequest(WorkMode.LITE, allowOverwrite = false, reason = RestoreReason.AFTER_CLEAR_DATA),
        )
        assertTrue(result.blockers.isNotEmpty())
        assertFalse(File(appDir, "wallpaper/a.png").exists())
    }
}
