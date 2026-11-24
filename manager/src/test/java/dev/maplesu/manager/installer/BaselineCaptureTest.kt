package dev.maplesu.manager.installer

import dev.maplesu.manager.workmode.detect.Clock
import dev.maplesu.manager.workmode.switch.BaselineErrors
import dev.maplesu.manager.workmode.switch.BaselineManifest
import dev.maplesu.manager.workmode.switch.BaselineProvenance
import dev.maplesu.manager.workmode.switch.BaselineResult
import dev.maplesu.manager.workmode.switch.BaselineState
import dev.maplesu.manager.workmode.switch.DeviceBootFacts
import dev.maplesu.manager.workmode.switch.FileBaselineStore
import dev.maplesu.manager.workmode.switch.PatchMarkers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/** Host tests for the P0 baseline capture pipeline (real bytes + materializer + store wiring). */
class BaselineCaptureTest {
    private class TestClock(var now: Long = 1_000L) : Clock {
        override fun nowMillis(): Long = now
    }

    private inner class FakeSource(
        var snapshotValue: BaselineDeviceSnapshot,
        private val contents: Map<String, ByteArray>,
    ) : BaselinePartitionSourcePort {
        var readFailure: String? = null
        var appendBytes = 0
        var shaOverride: String? = null
        var secondSnapshot: BaselineDeviceSnapshot? = null
        private var snapshots = 0

        override fun snapshot(): BaselineDeviceSnapshot {
            snapshots++
            return if (snapshots > 1 && secondSnapshot != null) secondSnapshot!! else snapshotValue
        }

        override fun readPartition(name: String, destination: File): BaselinePartitionRead {
            readFailure?.let { throw IOException(it) }
            val bytes = contents[name] ?: throw IOException("missing partition $name")
            destination.writeBytes(if (appendBytes == 0) bytes else bytes + ByteArray(appendBytes) { 7 })
            return BaselinePartitionRead(
                name = name,
                size = destination.length(),
                sha256 = shaOverride ?: sha(destination.readBytes()),
            )
        }
    }

    private class FakeCleanliness(var report: BaselineCleanReport) : BaselineCleanlinessPort {
        var lastEvidence: BaselinePatchEvidence? = null
        override fun verify(
            images: Map<String, File>,
            expectedSizes: Map<String, Long>,
            evidence: BaselinePatchEvidence,
        ): BaselineCleanReport {
            lastEvidence = evidence
            return report
        }
    }

    private class FakeEvidence(var value: BaselinePatchEvidence) : BaselinePatchEvidencePort {
        override fun read(): BaselinePatchEvidence = value
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun cleanReport(vararg partitions: String): BaselineCleanReport = BaselineCleanReport(
        clean = true,
        patchMarkers = PatchMarkers(),
        avbTailPreserved = partitions.associateWith { true },
        evidence = partitions.map { "$it:form=STOCK" },
    )

    private inner class Harness(includeInitBoot: Boolean = true) {
        val root: File = Files.createTempDirectory("maplesu-baseline-capture-").toFile()
        val clock = TestClock()
        val bootBytes = ByteArray(512) { ((it * 13 + 1) and 0xFF).toByte() }
        val initBootBytes = ByteArray(1024) { ((it * 17 + 2) and 0xFF).toByte() }
        val partitions = LinkedHashMap<String, BaselinePartitionFacts>().apply {
            put("boot", BaselinePartitionFacts("boot", bootBytes.size.toLong(), sha(bootBytes)))
            if (includeInitBoot) {
                put("init_boot", BaselinePartitionFacts("init_boot", initBootBytes.size.toLong(), sha(initBootBytes)))
            }
        }
        var snapshot = BaselineDeviceSnapshot(
            slot = "_a",
            firmware = "fw-1",
            kernelRelease = "5.10.198-android13-8-test",
            selinux = "Enforcing",
            partitions = partitions,
            vbmeta = BaselineVbmetaFacts(present = true, sha256 = "v".repeat(64)),
        )
        val contents = LinkedHashMap<String, ByteArray>().apply {
            put("boot", bootBytes)
            if (includeInitBoot) put("init_boot", initBootBytes)
        }
        val source = FakeSource(snapshot, contents)
        val evidence = FakeEvidence(BaselinePatchEvidence(true, false, null, "installed.json"))
        val cleanliness = FakeCleanliness(cleanReport(*partitions.keys.toTypedArray()))
        /** 非阻断观察落点（材料器 + 门面各记一次，内容相同）。 */
        val notes = mutableListOf<String>()
        var device = DeviceBootFacts(snapshot.slot, snapshot.firmware, snapshot.kernelRelease)
        val store = FileBaselineStore(
            root = root,
            deviceFacts = { device },
            clock = clock,
            materializer = InstallerBaselineMaterializer(source, cleanliness, evidence, clock) {
                notes += it
            },
        )
        var id = "bl-test"
        val service = BaselineCaptureService(
            store = store,
            source = source,
            clock = clock,
            newBaselineId = { id },
            notes = { notes += it },
        )

        fun baselineDirectory(id: String = this.id) = File(root, "boot/baseline/$id")

        fun close() {
            store.closeOpenImages()
            root.deleteRecursively()
        }
    }

    private fun withHarness(includeInitBoot: Boolean = true, block: (Harness) -> Unit) {
        val harness = Harness(includeInitBoot)
        try {
            block(harness)
        } finally {
            harness.close()
        }
    }

    private fun assertFailed(result: BaselineResult, code: String): BaselineResult.Failed {
        assertTrue("expected Failed but was $result", result is BaselineResult.Failed)
        val failed = result as BaselineResult.Failed
        assertEquals("code", code, failed.code)
        return failed
    }

    private fun assertOk(result: BaselineResult): BaselineResult.Ok {
        assertTrue("expected Ok but was $result", result is BaselineResult.Ok)
        return result as BaselineResult.Ok
    }

    // ------------------------------------------------------------------ status / ensure

    @Test
    fun missingBaselineStatusAndS0Blocker() = withHarness { h ->
        val status = h.service.status()
        assertEquals(BaselineFacadeState.MISSING, status.state)
        assertTrue(status.canCapture)
        val ensure = h.service.ensureBaseline()
        assertEquals("CAPTURE", ensure.action)
        assertEquals(BaselineErrors.BASELINE_MISSING, ensure.blocker)
        assertEquals(BaselineErrors.BASELINE_MISSING, h.service.blockerForS0())
    }

    @Test
    fun captureMakesBaselinePresentAndClearsBlocker() = withHarness { h ->
        assertOk(h.service.capture())
        val status = h.service.status()
        assertEquals(BaselineFacadeState.PRESENT_OK, status.state)
        assertEquals("bl-test", status.baselineId)
        assertNull(h.service.ensureBaseline().blocker)
        assertNull(h.service.blockerForS0())
        assertEquals(sha(h.bootBytes), h.service.check().bootSha256)
        assertEquals(sha(h.initBootBytes), h.service.check().initBootSha256)
    }

    // ------------------------------------------------------------------ capture happy path

    @Test
    fun captureWritesRealImagesAndManifest() = withHarness { h ->
        val manifest = assertOk(h.service.capture()).manifest
        assertEquals(BaselineProvenance.PRE_FIRST_WRITE_CAPTURE, manifest.provenance)
        assertTrue(manifest.clean)
        assertEquals("_a", manifest.slot)
        assertEquals("fw-1", manifest.firmware)
        assertEquals(2, manifest.partitions.size)

        val image = File(h.baselineDirectory(), "boot.img")
        val initImage = File(h.baselineDirectory(), "init_boot.img")
        assertTrue(image.isFile)
        assertTrue(initImage.isFile)
        assertArrayEqualsBytes(h.bootBytes, image.readBytes())
        assertArrayEqualsBytes(h.initBootBytes, initImage.readBytes())
        assertTrue(File(h.baselineDirectory(), "manifest.json").isFile)
        assertEquals(BaselineState.PRESENT_OK, h.store.verify().state)
        assertEquals(sha(h.bootBytes), h.store.check().bootSha256)
    }

    @Test
    fun captureRecordsEvidenceAndAvbTail() = withHarness { h ->
        h.cleanliness.report = cleanReport("boot", "init_boot").copy(
            avbTailPreserved = mapOf("boot" to true, "init_boot" to false),
        )
        val manifest = assertOk(h.service.capture()).manifest
        assertTrue(manifest.partitions.first { it.name == "boot" }.avbTailPreserved)
        assertTrue(!manifest.partitions.first { it.name == "init_boot" }.avbTailPreserved)
        assertTrue(manifest.patchMarkers.allAbsent)
        assertEquals(h.evidence.value, h.cleanliness.lastEvidence)
    }

    @Test
    fun captureRejectsSizeMismatch() = withHarness { h ->
        h.source.appendBytes = 16
        assertFailed(h.service.capture(), BaselineCaptureCodes.CAPTURE_SIZE_MISMATCH)
        assertTrue(!File(h.baselineDirectory(), "manifest.json").exists())
        assertTrue(h.store.verify().state != BaselineState.PRESENT_OK)
    }

    @Test
    fun captureRejectsShaMismatch() = withHarness { h ->
        h.source.shaOverride = "0".repeat(64)
        assertFailed(h.service.capture(), BaselineCaptureCodes.CAPTURE_SHA_MISMATCH)
        assertTrue(!File(h.baselineDirectory(), "manifest.json").exists())
    }

    @Test
    fun captureRejectsReadFailure() = withHarness { h ->
        h.source.readFailure = "io error"
        assertFailed(h.service.capture(), BaselineCaptureCodes.CAPTURE_READ_FAILED)
        assertTrue(!File(h.baselineDirectory(), "manifest.json").exists())
    }

    @Test
    fun captureRejectsUncleanImages() = withHarness { h ->
        h.cleanliness.report = BaselineCleanReport(
            clean = false,
            patchMarkers = PatchMarkers(ksuKo = true),
            avbTailPreserved = mapOf("boot" to true, "init_boot" to true),
            evidence = listOf("init_boot:ksu residue"),
            code = BaselineErrors.BASELINE_UNCLEAN,
            detail = "kernelsu.ko",
        )
        assertFailed(h.service.capture(), BaselineErrors.BASELINE_UNCLEAN)
        assertTrue(!File(h.baselineDirectory(), "manifest.json").exists())
    }

    @Test
    fun captureRejectsUnknownPatchEvidence() = withHarness { h ->
        h.cleanliness.report = h.cleanliness.report.copy(
            clean = false,
            code = BaselineErrors.BASELINE_PROVENANCE_UNKNOWN,
            detail = "control plane unreadable",
        )
        assertFailed(h.service.capture(), BaselineErrors.BASELINE_PROVENANCE_UNKNOWN)
    }

    @Test
    fun captureReusesExistingBaselineInsteadOfOverwriting() = withHarness { h ->
        // W4a：抓到过就不再抓（"已存在即复用"）—— 但**绝不覆盖**（immutable 红线仍在）。
        val first = assertOk(h.service.capture())
        val imageBefore = File(h.baselineDirectory(), "boot.img").readBytes()
        val second = assertOk(h.service.capture())
        assertEquals("复用必须交回同一份 baseline", first.manifest.id, second.manifest.id)
        assertArrayEqualsBytes(imageBefore, File(h.baselineDirectory(), "boot.img").readBytes())
        assertEquals(first.manifest.partitions, second.manifest.partitions)
        assertTrue(second.manifest.clean)
        assertEquals(first.manifest.id, h.store.verify().id)
    }

    @Test
    fun captureRejectsLiveFactsChangedAfterSnapshot() = withHarness { h ->
        val changed = h.snapshot.copy(
            partitions = h.partitions.mapValues { (name, facts) ->
                if (name == "boot") facts.copy(sha256 = "1".repeat(64)) else facts
            },
        )
        h.source.secondSnapshot = changed
        assertFailed(h.service.capture(), BaselineErrors.BASELINE_STALE)
        assertTrue(!File(h.baselineDirectory(), "manifest.json").exists())
    }

    @Test
    fun captureSucceedsOnPermissiveSelinuxAndRecordsTheFact() = withHarness { h ->
        // 2026-11-01 用户指令：只有「不可信来源」可拒绝刷写 ⇒ SELinux 不再是 baseline 判据。
        // 断言具体返回值：捕获成功 + manifest 干净 + 事实进 note（材料器与门面各一次）。
        h.source.snapshotValue = h.snapshot.copy(selinux = "Permissive")
        val manifest = assertOk(h.service.capture()).manifest
        assertTrue("SELinux 非 Enforcing 不得把 baseline 判成不干净", manifest.clean)
        assertEquals("_a", manifest.slot)
        assertEquals(2, manifest.partitions.size)
        assertEquals(setOf("selinux=Permissive"), h.notes.toSet())
        // 门面与 store 的结论一致：baseline 真的落地了，S0 不再被 BASELINE_UNCLEAN 挡住。
        assertEquals(BaselineFacadeState.PRESENT_OK, h.service.status().state)
        assertNull(h.service.blockerForS0())
    }

    @Test
    fun captureDoesNotNoteAnythingWhileEnforcing() = withHarness { h ->
        assertOk(h.service.capture())
        assertTrue("Enforcing 不产生噪音: ${h.notes}", h.notes.isEmpty())
    }

    @Test
    fun deviceChangeAfterCaptureSurfacesStale() = withHarness { h ->
        assertOk(h.service.capture())
        assertEquals(BaselineFacadeState.PRESENT_OK, h.service.status().state)
        h.device = h.device.copy(slot = "_b")
        val status = h.service.status()
        assertEquals(BaselineFacadeState.STALE, status.state)
        assertEquals(BaselineErrors.BASELINE_STALE, status.code)
        assertEquals(BaselineErrors.BASELINE_STALE, h.service.blockerForS0())
    }

    // ------------------------------------------------------------------ import

    private fun externalDirectory(h: Harness, bytes: Map<String, ByteArray>): File {
        val directory = File(h.root, "external-oem").apply { mkdirs() }
        for ((name, content) in bytes) File(directory, "$name.img").writeBytes(content)
        return directory
    }

    @Test
    fun importFromOemDirectoryWritesBaseline() = withHarness { h ->
        val oemBoot = ByteArray(512) { ((it * 3) and 0xFF).toByte() }
        val oemInit = ByteArray(1024) { ((it * 5) and 0xFF).toByte() }
        val directory = externalDirectory(h, mapOf("boot" to oemBoot, "init_boot" to oemInit))
        val result = h.service.import("bl-oem", directory)
        val manifest = assertOk(result).manifest
        assertEquals(BaselineProvenance.OEM_PACKAGE, manifest.provenance)
        assertEquals(sha(oemBoot), manifest.partitions.first { it.name == "boot" }.sha256)
        assertTrue(File(h.root, "boot/baseline/bl-oem/boot.img").isFile)
        assertEquals(BaselineState.PRESENT_OK, h.store.verify("bl-oem").state)
        // 导入镜像的 clean 证明只看字节：控制面证据不参与。
        assertEquals("import:image-only", h.cleanliness.lastEvidence?.detail)
    }

    @Test
    fun importSingleFileForBootOnlyBaseline() = withHarness(includeInitBoot = false) { h ->
        val oemBoot = ByteArray(512) { ((it * 7) and 0xFF).toByte() }
        val file = File(h.root, "oem-boot.img").apply { writeBytes(oemBoot) }
        val manifest = assertOk(h.service.import("bl-single", file)).manifest
        assertEquals(1, manifest.partitions.size)
        assertEquals(sha(oemBoot), manifest.partitions.single().sha256)
    }

    @Test
    fun importRejectsStudyImagePath() = withHarness { h ->
        val directory = File(h.root, "Ace6/oem").apply { mkdirs() }
        File(directory, "boot.img").writeBytes(h.bootBytes)
        File(directory, "init_boot.img").writeBytes(h.initBootBytes)
        assertFailed(h.service.import("bl-study", directory), BaselineErrors.BASELINE_PROVENANCE_UNKNOWN)
    }

    @Test
    fun importRejectsWrongSize() = withHarness { h ->
        val directory = externalDirectory(
            h, mapOf("boot" to ByteArray(100), "init_boot" to h.initBootBytes),
        )
        assertFailed(h.service.import("bl-bad-size", directory), BaselineCaptureCodes.CAPTURE_SIZE_MISMATCH)
    }

    @Test
    fun importRejectsUncleanImages() = withHarness { h ->
        h.cleanliness.report = BaselineCleanReport(
            clean = false,
            patchMarkers = PatchMarkers(ksuInitReal = true),
            avbTailPreserved = mapOf("boot" to true, "init_boot" to true),
            evidence = emptyList(),
            code = BaselineErrors.BASELINE_UNCLEAN,
            detail = "init.real",
        )
        val directory = externalDirectory(
            h, mapOf("boot" to h.bootBytes, "init_boot" to h.initBootBytes),
        )
        assertFailed(h.service.import("bl-unclean", directory), BaselineErrors.BASELINE_UNCLEAN)
    }

    // ------------------------------------------------------------------ open() / 回读

    @Test
    fun openReturnsRealImageDescriptor() = withHarness { h ->
        assertOk(h.service.capture())
        assertNull(h.store.open("missing", "boot"))
        val descriptor = h.store.open(h.id, "boot")
        assertNotNull("P0: open() must expose the real baseline image", descriptor)
        val bytes = FileInputStream(descriptor!!).use { it.readBytes() }
        assertArrayEqualsBytes(h.bootBytes, bytes)
        h.store.closeOpenImages()
    }

    @Test
    fun manifestIsImmutableAndSchemaOne() = withHarness { h ->
        val manifest = assertOk(h.service.capture()).manifest
        assertTrue(manifest.immutable)
        assertEquals(1, manifest.schema)
        assertEquals(h.clock.now, manifest.createdAt)
        assertEquals(BaselineManifest.SCHEMA, manifest.schema)
    }

    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals("length", expected.size, actual.size)
        for (index in expected.indices) {
            if (expected[index] != actual[index]) {
                throw AssertionError("byte mismatch at $index")
            }
        }
    }
}
