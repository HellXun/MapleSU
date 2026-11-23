package dev.maplesu.manager.installer

import dev.maplesu.image.BootImageInspector
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.service.ModeSwitchImages
import dev.maplesu.manager.workmode.switch.BaselineErrors
import dev.maplesu.manager.workmode.switch.BaselineProvenance
import dev.maplesu.manager.workmode.switch.BaselineResult
import dev.maplesu.manager.workmode.switch.DeviceBootFacts
import dev.maplesu.manager.workmode.switch.FileBaselineStore
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** Explicit switch inputs use the existing import/publication path without claiming OEM origin. */
internal class SelectedBootBaselineProvisioner(
    private val bridge: BaselineControlPlaneBridge,
    private val capture: BaselineLiveCapturePort,
    private val stagingRoot: File,
    private val target: WorkMode,
    private val images: ModeSwitchImages,
) : BaselineProvisioner {
    override fun ensureBaseline(): BaselineProvisionOutcome {
        val probe = capture.probe() ?: return failure("partition topology unavailable")
        val required = if (false) setOf("boot")
            else probe.devices.keys.intersect(setOf("boot", "init_boot"))
        if ("boot" !in required) return failure("boot partition missing")
        if ((false || "init_boot" !in required) && images.boot == null ||
            "init_boot" in required && images.initBoot == null) return failure("select the required image")
        val staging = File(stagingRoot, UUID.randomUUID().toString())
        try {
            val imports = File(staging, "images").apply { check(mkdirs()) }
            val partitions = required.associateWith { name ->
                val size = requireNotNull(probe.sizes[name])
                val file = File(imports, "$name.img")
                val selected = if (name == "boot") images.boot else images.initBoot
                if (selected != null) {
                    check(selected.isFile && selected.length() in 1..size) { "image size: $name" }
                    val report = BootImageInspector.inspect(selected)
                    check(if (name == "boot") report.kernelSize > 0 else report.kernelSize == 0L) { "image partition: $name" }
                    selected.copyTo(file)
                    RandomAccessFile(file, "rw").use { it.setLength(size); it.fd.sync() }
                } else {
                    val read = capture.copy(name, file)
                    check(read.size == size && file.length() == size &&
                        BaselineAtomicFiles.sha256(file) == read.sha256) { "partition changed: $name" }
                }
                BaselinePartitionFacts(name, size, BaselineAtomicFiles.sha256(file))
            }
            check(capture.probe() == probe) { "device changed during image preparation" }
            val device = DeviceBootFacts(probe.slot, probe.firmware, probe.kernelRelease)
            val snapshot = BaselineDeviceSnapshot(probe.slot, probe.firmware, probe.kernelRelease, probe.selinux, partitions)
            val source = object : BaselinePartitionSourcePort {
                override fun snapshot() = snapshot
                override fun readPartition(name: String, destination: File): BaselinePartitionRead = error("import only")
            }
            val store = FileBaselineStore(staging, { device }, materializer =
                InstallerBaselineMaterializer(source, RamdiskBaselineCleanliness(),
                    BaselinePatchEvidencePort { BaselinePatchEvidence(true, false, null, "user-selected") }))
            val id = "selected-${UUID.randomUUID()}"
            val imported = BaselineCaptureService(store, source).import(id, imports, BaselineProvenance.USER_SELECTED)
            if (imported is BaselineResult.Failed) return BaselineProvisionOutcome.Failed(imported.code, imported.detail)
            val manifest = (imported as BaselineResult.Ok).manifest
            val expected = manifest.partitions.associate { it.name to it.sha256 }
            check(bridge.publish(id, File(staging, "boot/baseline/$id"), expected)) { "image publication failed" }
            val published = bridge.probe()?.filter { it.id == id }?.let {
                reusableBaselineEntry(it, device, id, "init_boot" in required, allowUserSelected = true)
            } ?: return failure("image publication readback failed")
            return BaselineProvisionOutcome.Ready(id, false, published.second.provenance)
        } catch (error: Exception) {
            return failure(error.message.orEmpty())
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun failure(detail: String) = BaselineProvisionOutcome.Failed(BaselineErrors.BASELINE_CORRUPT, detail)
}
