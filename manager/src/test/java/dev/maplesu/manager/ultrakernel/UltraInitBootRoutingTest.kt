package dev.maplesu.manager.ultrakernel

import dev.maplesu.manager.installer.InstallPartition
import dev.maplesu.manager.installer.InstallPartitionPolicy
import dev.maplesu.manager.installer.InstallPartitionResolution
import dev.maplesu.manager.installer.InstallTargetMode
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.switch.ModeSwitchPreflight
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic container inputs prove routing/failure boundaries, never bridge bootability. */
class UltraInitBootRoutingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun automaticUltraKeepsTheImplementedBootBackendOnBothTopologies() {
        for (partitions in listOf(setOf("boot"), setOf("boot", "init_boot"))) {
            assertEquals(InstallPartitionResolution.Ready(InstallPartition.BOOT),
                InstallPartitionPolicy.resolve(InstallTargetMode.ULTRA, null, partitions))
        }
        assertTrue(InstallPartitionPolicy.resolve(InstallTargetMode.ULTRA, "init_boot", setOf("boot"))
            is InstallPartitionResolution.Rejected)
    }

    @Test fun missingArtifactFailsBeforeAnyCandidateIsProducedAndKeepsInput() {
        val input = initBoot()
        val before = input.readBytes()
        val workspace = temporary.newFolder("candidate-work")
        val flow = UltraKernelFlow(workspace, UltraKernelMemoryAssets(emptyMap()))
        val error = runCatching {
            flow.prepareTarget(input, UltraKernelCredential.of("A".repeat(48)))
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().startsWith("ULTRA_KERNEL_INIT_BOOT_ARTIFACT_REQUIRED:"))
        assertArrayEquals(before, input.readBytes())
        assertTrue(workspace.listFiles().orEmpty().isEmpty())
        assertTrue(runCatching { flow.releaseOf(input) }.exceptionOrNull()
            ?.message.orEmpty().contains("ULTRA_KERNEL_BASE_NO_KERNEL"))
    }

    @Test fun sameCarrierPlansStayMinimalAndUnknownCarrierCannotInventInitBoot() {
        assertEquals(listOf("init_boot"), ModeSwitchPreflight.writeOrder(
            WorkMode.LITE, "init_boot", "init_boot", setOf("boot", "init_boot")))
        assertEquals(listOf("boot"), ModeSwitchPreflight.writeOrder(
            WorkMode.LITE, "boot", "", setOf("boot")))
        assertEquals(listOf("init_boot", "boot"), ModeSwitchPreflight.writeOrder(
            WorkMode.LITE, "init_boot", "boot", setOf("boot", "init_boot")))
        assertTrue(runCatching { ModeSwitchPreflight.writeOrder(
            WorkMode.LITE, "init_boot", "", setOf("boot")) }.isFailure)
    }

    private fun initBoot(): File {
        val bytes = ByteArray(8192)
        "ANDROID!".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(12, 4); putInt(20, 1584); putInt(40, 4)
        }
        return File(temporary.root, "init_boot.img").also { it.writeBytes(bytes) }
    }
}
