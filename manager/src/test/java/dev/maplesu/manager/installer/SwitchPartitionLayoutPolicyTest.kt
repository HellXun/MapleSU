package dev.maplesu.manager.installer

import dev.maplesu.installer.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Pure partition layout / provider policy around the W07 multi-partition transaction. */
class SwitchPartitionLayoutPolicyTest {
    private fun identity() = BootPartitionIdentity(deviceNumber = 1, inode = 2, filesystem = 3)

    private fun snapshot(vararg names: String): InstallerSnapshot {
        val partitions = names.associateWith { name ->
            BootPartitionSnapshot(
                name = name,
                path = InstallerSnapshotParser.partitionPath(name, "_a"),
                resolvedPath = "/dev/block/sde$name",
                size = 1024,
                sha256 = "a".repeat(64),
                identity = identity(),
            )
        }
        return InstallerSnapshot("5.10.198-android13-8-test", "fw-1", "_a", "Enforcing", partitions)
    }

    private fun rejects(message: String, block: () -> Unit) {
        try {
            block()
            fail("expected $message")
        } catch (error: Exception) {
            val text = error.message.orEmpty()
            assertTrue("expected $message but got $text", text.contains(message))
        }
    }

    @Test
    fun writableNamesExcludeVbmeta() {
        val names = SwitchPartitionLayoutPolicy.writableNames(snapshot("boot", "init_boot", "vbmeta"))
        assertEquals(listOf("boot", "init_boot"), names.toList())
        assertEquals(listOf("boot"), SwitchPartitionLayoutPolicy.writableNames(snapshot("boot")).toList())
    }

    @Test
    fun bootIsMandatory() {
        rejects("INSTALLER_BLOCK_DEVICE_REQUIRED") {
            SwitchPartitionLayoutPolicy.writableNames(snapshot("init_boot", "vbmeta"))
        }
        rejects("INSTALLER_BLOCK_DEVICE_REQUIRED") {
            SwitchPartitionLayoutPolicy.requireTargetLayout(snapshot("init_boot"), WorkMode.LITE)
        }
    }

    @Test
    fun ksuTargetUsesBootWhenNoIndependentInitBootExists() {
        SwitchPartitionLayoutPolicy.requireTargetLayout(snapshot("boot", "init_boot"), WorkMode.LITE)
        SwitchPartitionLayoutPolicy.requireTargetLayout(snapshot("boot"), WorkMode.LITE)
        SwitchPartitionLayoutPolicy.requireTargetLayout(snapshot("boot"), WorkMode.LITE)
    }

    @Test
    fun partitionPathsAreValidatedPerSlot() {
        assertEquals("/dev/block/by-name/boot_a",
            SwitchPartitionLayoutPolicy.partitionPath("_a", "boot"))
        assertEquals("/dev/block/by-name/init_boot_b",
            SwitchPartitionLayoutPolicy.partitionPath("_b", "init_boot"))
        rejects("INSTALLER_PARTITION_INVALID") {
            SwitchPartitionLayoutPolicy.partitionPath("_a", "system")
        }
        rejects("INSTALLER_SLOT_INVALID") {
            SwitchPartitionLayoutPolicy.partitionPath("_c", "boot")
        }
    }

    @Test
    fun providerOnlyExposesAllowlist() {
        val provider = AndroidSwitchPartitionProvider(snapshot("boot", "init_boot", "vbmeta"))
        assertEquals(listOf("boot", "init_boot"), provider.available().toList())
        assertEquals("boot", provider.get("boot").name())
        assertEquals(1024L, provider.get("boot").size())
        assertFalse("vbmeta must never be writable", "vbmeta" in provider.available())
        rejects("SWITCH_PARTITION_FORBIDDEN") { provider.get("vbmeta") }
        rejects("SWITCH_PARTITION_FORBIDDEN") { provider.get("system") }
        try {
            AndroidSwitchPartitionProvider(snapshot("boot", "init_boot")).get("init_boot")
        } catch (_: IOException) {
            fail("init_boot should resolve when present")
        }
    }

    /** B: a Permissive/Unknown SELinux value is evidence only; the provider must still resolve. */
    @Test
    fun providerAcceptsNonEnforcingSnapshot() {
        val partitions = mapOf("boot" to BootPartitionSnapshot("boot",
            InstallerSnapshotParser.partitionPath("boot", "_a"), "/dev/block/sdeboot", 1024,
            "a".repeat(64), identity()))
        for (status in listOf("Permissive", "Unknown", "Disabled", "Enforcing")) {
            val provider = AndroidSwitchPartitionProvider(
                InstallerSnapshot("kernel", "fw", "_a", status, partitions))
            assertEquals("available($status)", listOf("boot"), provider.available().toList())
            assertEquals("boot", provider.get("boot").name())
        }
    }

    @Test
    fun providerRequiresDeviceIdentity() {
        val partitions = mapOf("boot" to BootPartitionSnapshot("boot",
            InstallerSnapshotParser.partitionPath("boot", "_a"), "/dev/block/sdeboot", 1024,
            "a".repeat(64), null))
        val snapshot = InstallerSnapshot("kernel", "fw", "_a", "Enforcing", partitions)
        rejects("INSTALLER_DEVICE_IDENTITY_REQUIRED") { AndroidSwitchPartitionProvider(snapshot) }
    }

    @Test
    fun missingPartitionIsUnauthorizedByLayout() {
        val provider = AndroidSwitchPartitionProvider(snapshot("boot"))
        try {
            provider.get("init_boot")
            fail("missing init_boot accepted")
        } catch (error: IOException) {
            assertEquals("SWITCH_PARTITION_LAYOUT_UNSUPPORTED", error.message)
        }
    }
}
