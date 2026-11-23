// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目标分区策略（纯函数）契约。
 *
 * 用户口径：
 * - Ultra 使用双载体策略，自动模式无 bridge 能力时默认 `boot`，文件载体按内容识别；
 * - Normal 可选 `init_boot`（默认）/ `boot`（与 KernelSU 一致）；
 * - 白名单之外的分区一律拒绝，不做猜测；
 * - 分区 + 槽位拼成真实块设备节点（`init_boot_a` / `boot_a`）。
 */
class InstallPartitionTest {

    @Test
    fun automaticSelectionUsesActualNodesAndKmiWhileExplicitSelectionWins() {
        fun resolve(requested: String?, nodes: Set<String>, kmi: String) =
            (InstallPartitionPolicy.resolve(InstallTargetMode.ULTRA, requested, nodes, kmi)
                as InstallPartitionResolution.Ready).partition
        assertEquals(InstallPartition.BOOT, resolve(null, setOf("boot"), "android15-6.6"))
        assertEquals(InstallPartition.BOOT, resolve(null, setOf("boot", "init_boot"), "android12-5.10"))
        assertEquals(InstallPartition.INIT_BOOT, resolve(null, setOf("boot", "init_boot"), "android15-6.6"))
        assertEquals(InstallPartition.BOOT, resolve("boot", setOf("boot", "init_boot"), "android15-6.6"))
        assertEquals(InstallPartition.INIT_BOOT, resolve("init_boot", setOf("boot", "init_boot"), "android12-5.10"))
    }

    @Test
    fun imageCarrierComesFromItsHeader() {
        fun header(kernel: Int, ramdisk: Int, version: Int) = ByteArray(44).also {
            "ANDROID!".toByteArray().copyInto(it)
            java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(8, kernel).putInt(12, ramdisk).putInt(40, version)
        }
        assertEquals(InstallPartition.BOOT, InstallPartitionPolicy.imagePartition(header(123, 0, 4)))
        assertEquals(InstallPartition.INIT_BOOT, InstallPartitionPolicy.imagePartition(header(0, 123, 4)))
        assertNull(InstallPartitionPolicy.imagePartition(header(0, 123, 3)))
        assertNull(InstallPartitionPolicy.imagePartition("070701".toByteArray()))
    }

    // ── 枚举与白名单 ─────────────────────────────────────────────────────

    @Test
    fun whitelistIsExactlyInitBootAndBoot() {
        assertEquals(listOf("init_boot", "boot"), InstallPartition.ALLOWED.map { it.wire })
        assertEquals(setOf("init_boot", "boot"), InstallPartition.ALLOWED_WIRE)
        assertEquals(InstallPartition.INIT_BOOT, InstallPartition.fromWire("init_boot"))
        assertEquals(InstallPartition.BOOT, InstallPartition.fromWire("BOOT"))
        assertNull(InstallPartition.fromWire("vendor_boot"))
        assertNull(InstallPartition.fromWire("/dev/block/by-name/init_boot"))
        assertNull(InstallPartition.fromWire(""))
        assertNull(InstallPartition.fromWire(null))
    }

    // ── 模式 → 可选分区 ───────────────────────────────────────────────────

    @Test
    fun ultraHasExactlyOneFixedPartition() {
        assertEquals(listOf(InstallPartition.BOOT), InstallPartitionPolicy.optionsFor(InstallTargetMode.ULTRA))
        assertTrue(InstallPartitionPolicy.isFixed(InstallTargetMode.ULTRA))
        assertEquals(InstallPartition.BOOT, InstallPartitionPolicy.ULTRA_FIXED)
        // Ultra 下 init_boot **不可选**：尝试选它会被策略拒绝。
        assertFalse(InstallPartitionPolicy.isSelectable(InstallTargetMode.ULTRA, InstallPartition.INIT_BOOT))
    }

    @Test
    fun normalSelectsBetweenInitBootAndBootWithInitBootAsDefault() {
        assertEquals(
            listOf(InstallPartition.INIT_BOOT, InstallPartition.BOOT),
            InstallPartitionPolicy.optionsFor(InstallTargetMode.ULTRA),
        )
        assertFalse(InstallPartitionPolicy.isFixed(InstallTargetMode.ULTRA))
        assertEquals(InstallPartition.INIT_BOOT, InstallPartitionPolicy.ULTRA_FIXED)
        assertTrue(InstallPartitionPolicy.isSelectable(InstallTargetMode.ULTRA, InstallPartition.INIT_BOOT))
        assertTrue(InstallPartitionPolicy.isSelectable(InstallTargetMode.ULTRA, InstallPartition.BOOT))
    }

    // ── resolve：页面/入口传进来的字符串 → 生效分区 ─────────────────────────

    @Test
    fun normalResolvesNullToInitBootAndHonoursAnExplicitBoot() {
        assertEquals(
            InstallPartitionResolution.Ready(InstallPartition.INIT_BOOT),
            InstallPartitionPolicy.resolve(InstallTargetMode.ULTRA, null),
        )
        assertEquals(
            InstallPartitionResolution.Ready(InstallPartition.INIT_BOOT),
            InstallPartitionPolicy.resolve(InstallTargetMode.ULTRA, "  "),
        )
        assertEquals(
            InstallPartitionResolution.Ready(InstallPartition.BOOT),
            InstallPartitionPolicy.resolve(InstallTargetMode.ULTRA, "boot"),
        )
    }

    @Test
    fun normalRejectsPartitionsOutsideTheWhitelist() {
        listOf("vendor_boot", "super", "/dev/block/by-name/init_boot").forEach { name ->
            val rejected = InstallPartitionPolicy.resolve(InstallTargetMode.ULTRA, name)
            assertTrue("$name must be rejected, got $rejected", rejected is InstallPartitionResolution.Rejected)
            assertEquals(
                InstallPartitionPolicy.CODE_UNSUPPORTED,
                (rejected as InstallPartitionResolution.Rejected).code,
            )
        }
    }

    @Test
    fun ultraRoutesExplicitInitBootToTheProducer() {
        // 空白/缺省 → 固定值。
        assertEquals(
            InstallPartitionResolution.Ready(InstallPartition.BOOT),
            InstallPartitionPolicy.resolve(InstallTargetMode.ULTRA, null),
        )
        assertEquals(
            InstallPartitionResolution.Ready(InstallPartition.BOOT),
            InstallPartitionPolicy.resolve(InstallTargetMode.ULTRA, "boot"),
        )
        // Selecting a carrier does not assert that a loadable bridge exists.
        assertEquals(InstallPartitionResolution.Ready(InstallPartition.INIT_BOOT),
            InstallPartitionPolicy.resolve(InstallTargetMode.ULTRA, "init_boot"))
        // 白名单外的值优先报"不支持"。
        val unsupported = InstallPartitionPolicy.resolve(InstallTargetMode.ULTRA, "vendor_boot")
        assertEquals(
            InstallPartitionPolicy.CODE_UNSUPPORTED,
            (unsupported as InstallPartitionResolution.Rejected).code,
        )
    }

    // ── 分区 + 槽位 → 块设备节点 ─────────────────────────────────────────

    @Test
    fun nodeNameJoinsPartitionAndSlot() {
        assertEquals("init_boot_a", InstallPartitionNodes.nodeName(InstallPartition.INIT_BOOT, "_a"))
        assertEquals("boot_a", InstallPartitionNodes.nodeName(InstallPartition.BOOT, "_a"))
        assertEquals("init_boot_b", InstallPartitionNodes.nodeName(InstallPartition.INIT_BOOT, "_b"))
        assertEquals(
            "/dev/block/by-name/init_boot_a",
            InstallPartitionNodes.nodePath(InstallPartition.INIT_BOOT, "_a"),
        )
        assertEquals(
            "/dev/block/by-name/boot_a",
            InstallPartitionNodes.nodePath(InstallPartition.BOOT, "_a"),
        )
    }

    @Test
    fun slotPreferenceIsTheDevicePropertyThenFallbackA() {
        assertEquals("_b", InstallPartitionNodes.slotSuffix("_b"))
        assertEquals("_b", InstallPartitionNodes.slotSuffix(" _b "))
        // 无槽位 / 非法槽位 → fallback `_a`。
        assertEquals("_a", InstallPartitionNodes.slotSuffix(null))
        assertEquals("_a", InstallPartitionNodes.slotSuffix(""))
        assertEquals("_a", InstallPartitionNodes.slotSuffix("  "))
        assertEquals("_a", InstallPartitionNodes.slotSuffix("_"))
        assertEquals("_a", InstallPartitionNodes.slotSuffix("b"))
        assertFalse(InstallPartitionNodes.hasReportedSlot(null))
        assertFalse(InstallPartitionNodes.hasReportedSlot(""))
        assertTrue(InstallPartitionNodes.hasReportedSlot("_a"))
    }

    @Test
    fun nodeCandidatesPreferTheReportedSlotThenAThenNoSuffix() {
        assertEquals(
            listOf("/dev/block/by-name/init_boot_b"),
            InstallPartitionNodes.nodeCandidates(InstallPartition.INIT_BOOT, "_b"),
        )
        // 没上报槽位：先 `_a`（规范 fallback），再无后缀（A-only 机型）。
        assertEquals(
            listOf("/dev/block/by-name/init_boot_a", "/dev/block/by-name/init_boot"),
            InstallPartitionNodes.nodeCandidates(InstallPartition.INIT_BOOT, null),
        )
        assertEquals(
            listOf("/dev/block/by-name/boot_a", "/dev/block/by-name/boot"),
            InstallPartitionNodes.nodeCandidates(InstallPartition.BOOT, ""),
        )
    }

    // ── 选择状态迁移（页面用这两个纯函数）────────────────────────────────

    @Test
    fun ultraSelectionNeverChangesThePartition() {
        // Ultra 的生效值恒为固定值，与"存了多少"无关。
        assertEquals(
            InstallPartition.BOOT,
            InstallPartitionSelection.effective(InstallTargetMode.ULTRA, InstallPartition.INIT_BOOT),
        )
        // 尝试把 Ultra 改到 init_boot：存下来的值原样不动。
        assertEquals(
            InstallPartition.INIT_BOOT,
            InstallPartitionSelection.select(
                InstallTargetMode.ULTRA,
                stored = InstallPartition.INIT_BOOT,
                requested = InstallPartition.INIT_BOOT,
            ),
        )
    }

    @Test
    fun normalSelectionStoresTheChosenPartitionButIgnoresInvalidOrLockedOnes() {
        assertEquals(
            InstallPartition.BOOT,
            InstallPartitionSelection.select(
                InstallTargetMode.ULTRA,
                stored = InstallPartition.INIT_BOOT,
                requested = InstallPartition.BOOT,
            ),
        )
        // 锁定（安装/切换进行中）：不生效。
        assertEquals(
            InstallPartition.INIT_BOOT,
            InstallPartitionSelection.select(
                InstallTargetMode.ULTRA,
                stored = InstallPartition.INIT_BOOT,
                requested = InstallPartition.BOOT,
                locked = true,
            ),
        )
        // Normal 的生效值就是存下来的值。
        assertEquals(
            InstallPartition.BOOT,
            InstallPartitionSelection.effective(InstallTargetMode.ULTRA, InstallPartition.BOOT),
        )
        assertEquals(
            InstallPartition.INIT_BOOT,
            InstallPartitionSelection.effective(InstallTargetMode.ULTRA, InstallPartition.INIT_BOOT),
        )
    }
}
