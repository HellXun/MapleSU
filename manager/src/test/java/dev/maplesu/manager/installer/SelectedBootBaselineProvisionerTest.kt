package dev.maplesu.manager.installer

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.service.ModeSwitchImages
import dev.maplesu.manager.workmode.switch.BaselineProvenance
import dev.maplesu.manager.workmode.switch.DeviceBootFacts
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SelectedBootBaselineProvisionerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun normalUsesOnlyTheSelectedBootAndNeverCapturesTheUltraBoot() {
        val h = Harness()
        val selected = h.file("selected.img", boot().apply { this[4096] = 42 })
        val ready = h.provision(WorkMode.LITE, ModeSwitchImages(boot = selected))
        assertTrue("$ready", ready is BaselineProvisionOutcome.Ready)
        assertEquals(emptyList<String>(), h.captures)
        assertEquals(setOf("boot"), h.bridge.entries.single().imageSha256.keys)
        assertEquals(BaselineAtomicFiles.sha256(selected), h.bridge.entries.single().imageSha256["boot"])
        assertTrue(selected.isFile)
    }

    @Test fun ultraWithEmptyBootCapturesItAndUsesTheSelectedInitBootForRestore() {
        val h = Harness()
        val init = h.file("init.img", boot(cpio("init")))
        val ready = h.provision(WorkMode.LITE, ModeSwitchImages(initBoot = init))
        assertTrue("$ready", ready is BaselineProvisionOutcome.Ready)
        assertEquals(listOf("boot"), h.captures)
        assertEquals(BaselineAtomicFiles.sha256(init), h.bridge.entries.single().imageSha256["init_boot"])
        assertEquals(BaselineAtomicFiles.sha256(h.currentBoot), h.bridge.entries.single().imageSha256["boot"])
        val manifest = decodeBaselineManifest(h.bridge.entries.single().manifestJson)!!
        assertEquals(BaselineProvenance.USER_SELECTED, manifest.provenance)
        assertNull(reusableBaselineEntry(h.bridge.entries, DeviceBootFacts("_a", "fixture", "6.6.89-android15"),
            manifest.id, requireInitBoot = true))
    }

    @Test fun compactSelectedBootIsPaddedToThePartitionSizeWithoutChangingTheDocument() {
        val h = Harness()
        val bytes = boot().copyOf(4097).apply {
            ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 1)
        }
        val selected = h.file("compact.img", bytes)
        val ready = h.provision(WorkMode.LITE, ModeSwitchImages(boot = selected))
        assertTrue("$ready", ready is BaselineProvisionOutcome.Ready)
        val entry = h.bridge.entries.single()
        assertEquals(8192L, entry.imageSize["boot"])
        assertEquals(dev.maplesu.manager.workmode.Hashes.sha256(bytes.copyOf(8192)), entry.imageSha256["boot"])
        assertArrayEquals(bytes, selected.readBytes())
        assertTrue(h.captures.isEmpty())
    }

    @Test fun eachSelectionHasItsOwnIdentityEvenWhenBootIsTheSame() {
        val h = Harness()
        val boot = h.file("selected.img", boot())
        val init = h.file("init.img", boot(cpio("init")))
        val first = h.provision(WorkMode.LITE, ModeSwitchImages(boot, init)) as BaselineProvisionOutcome.Ready
        val second = h.provision(WorkMode.LITE, ModeSwitchImages(boot, init)) as BaselineProvisionOutcome.Ready
        assertNotEquals(first.id, second.id)
    }

    @Test fun ultraSelectedBootIsUsedInsteadOfTheCurrentPartition() {
        val h = Harness()
        val selected = h.file("target.img", boot().apply { this[4096] = 99 })
        val init = h.file("init.img", boot(cpio("init")))
        assertTrue(h.provision(WorkMode.LITE, ModeSwitchImages(selected, init)) is BaselineProvisionOutcome.Ready)
        assertTrue(h.captures.isEmpty())
        assertEquals(BaselineAtomicFiles.sha256(selected), h.bridge.entries.single().imageSha256["boot"])
    }

    @Test fun ultraCanCaptureAPatchedNormalBootWithoutDemandingFactoryProvenance() {
        val h = Harness(patchedBoot = true)
        val init = h.file("init.img", boot(cpio("init")))
        val ready = h.provision(WorkMode.LITE, ModeSwitchImages(initBoot = init))
        assertTrue("$ready", ready is BaselineProvisionOutcome.Ready)
        assertEquals(listOf("boot"), h.captures)
        assertEquals(BaselineAtomicFiles.sha256(h.currentBoot), h.bridge.entries.single().imageSha256["boot"])
        val manifest = decodeBaselineManifest(h.bridge.entries.single().manifestJson)!!
        assertFalse(manifest.clean)
        assertTrue(manifest.patchMarkers.ksuKo)
    }

    @Test fun missingRequiredFileDoesNotPublishOrReadPartitions() {
        val h = Harness()
        assertTrue(h.provision(WorkMode.LITE, ModeSwitchImages()) is BaselineProvisionOutcome.Failed)
        assertTrue(h.provision(WorkMode.LITE, ModeSwitchImages()) is BaselineProvisionOutcome.Failed)
        assertTrue(h.captures.isEmpty())
        assertTrue(h.bridge.entries.isEmpty())
    }

    @Test fun bootOnlyUltraNeedsNoInitBootFile() {
        val h = Harness(hasInitBoot = false)
        val selected = h.file("selected.img", boot())
        assertTrue(h.provision(WorkMode.LITE, ModeSwitchImages(boot = selected)) is BaselineProvisionOutcome.Ready)
        assertTrue(h.captures.isEmpty())
    }

    @Test fun bootOnlyUltraRequiresASelectedBootBeforeReadingTheNormalPartition() {
        val h = Harness(hasInitBoot = false, patchedBoot = true)
        assertTrue(h.provision(WorkMode.LITE, ModeSwitchImages()) is BaselineProvisionOutcome.Failed)
        assertTrue(h.captures.isEmpty())
        assertTrue(h.bridge.entries.isEmpty())
    }

    private inner class Harness(hasInitBoot: Boolean = true, patchedBoot: Boolean = false) {
        val root = temporary.newFolder()
        fun file(name: String, bytes: ByteArray) = File(root, name).apply { writeBytes(bytes) }
        val currentBoot = file("current.img", if (patchedBoot) {
            val disk = cpio("kernelsu.ko")
            ByteArray(12288).apply {
                boot().copyInto(this)
                ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(12, disk.size)
                disk.copyInto(this, 8192)
            }
        } else boot())
        val captures = mutableListOf<String>()
        val bridge = LocalBridge()
        private val probe = BaselineCaptureProbe("_a", "fixture", "6.6.89-android15", "Enforcing",
            (if (hasInitBoot) listOf("boot", "init_boot") else listOf("boot")).associateWith { "/dev/block/by-name/"+it+"_a" },
            (if (hasInitBoot) listOf("boot", "init_boot") else listOf("boot")).associateWith { if (it == "boot") currentBoot.length() else 8192L })
        val capture = object : BaselineLiveCapturePort {
            override fun probe() = probe
            override fun writeMarkers(): BaselineWriteMarkers? = error("user selection does not need provenance evidence")
            override fun copy(name: String, destination: File): BaselineCapturedImage {
                captures += name
                currentBoot.copyTo(destination)
                return BaselineCapturedImage(name, destination, destination.length(), BaselineAtomicFiles.sha256(destination))
            }
        }
        fun provision(target: WorkMode, images: ModeSwitchImages) = SelectedBootBaselineProvisioner(
            bridge, capture, File(root, "stage"), target, images).ensureBaseline()
    }

    private class LocalBridge : BaselineControlPlaneBridge {
        var entries = emptyList<BaselineControlPlaneEntry>()
        override fun probe() = entries
        override fun publish(id: String, localDirectory: File, expectedSha256: Map<String, String>): Boolean {
            val manifest = File(localDirectory, "manifest.json")
            entries = listOf(BaselineControlPlaneEntry(id, manifest.readText(), BaselineAtomicFiles.sha256(manifest),
                File(localDirectory, "manifest.json.sha256").readText().trim(), expectedSha256,
                expectedSha256.keys.associateWith { File(localDirectory, "$it.img").length() }))
            return true
        }
    }

    private fun boot(ramdisk: ByteArray = ByteArray(0)): ByteArray {
        val kernelSize = if (ramdisk.isEmpty()) 4096 else 0
        val bytes = ByteArray(if (ramdisk.isEmpty()) 8192 else 4096 + ((ramdisk.size + 4095) / 4096) * 4096)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("ANDROID!".toByteArray()); putInt(8, kernelSize); putInt(12, ramdisk.size)
            putInt(20, 1584); putInt(40, 4)
        }
        ramdisk.copyInto(bytes, 4096 + kernelSize)
        return bytes
    }

    private fun cpio(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (entry in listOf(name, "TRAILER!!!")) {
            val header = ByteArray(110) { '0'.code.toByte() }
            "070701".toByteArray().copyInto(header)
            "%08x".format(0x81a4).toByteArray().copyInto(header, 14)
            "%08x".format(entry.length + 1).toByteArray().copyInto(header, 94)
            out.write(header); out.write((entry + "\u0000").toByteArray())
            while (out.size() % 4 != 0) out.write(0)
        }
        return out.toByteArray()
    }
}
