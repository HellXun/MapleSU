// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.installer

/**
 * 一次修补/写入的**目标分区**（纯 Kotlin，无 Android / Compose 依赖，主机可测）。
 *
 * 为什么要有这个枚举（而不是继续传字符串）：
 * - 分区名是**白名单**语义（只有 [INIT_BOOT] / [BOOT]），白名单外的值一律拒绝，不做猜测；
 * - 分区要和**槽位**拼成真实块设备节点（`init_boot_a` / `boot_a`），拼接规则只允许有一份实现；
 * - 目标模式的分区选择受实际后端能力和拓扑约束，约束集中在纯函数中。
 *
 * 与 KernelSU 的关系（用户口径「与 KernelSU 一致」）：
 * - Normal（LKM）默认 `init_boot`（独立 init_boot 的 LKM 路线）；
 * - Normal 也允许 `boot`（没有独立 init_boot 的机型 / 用户显式选择）；
 * - Ultra 支持 `boot` / `init_boot` 双载体；无实际 bridge 能力时默认 `boot`，镜像按内容识别。
 */
enum class InstallPartition(
    /** 传给 `ksud boot-patch --partition` 与刷写事务的规范值（一经发布不得修改）。 */
    val wire: String,
) {
    /** 独立 `init_boot` 分区（Android 13+ GKI 机型的 ramdisk 载体）。 */
    INIT_BOOT("init_boot"),

    /** `boot` 分区（无独立 `init_boot` 的机型；ramdisk 与 kernel 同一个容器）。 */
    BOOT("boot");

    companion object {
        /** 白名单（顺序即 Normal 选择器的展示顺序：`init_boot` 在前）。 */
        val ALLOWED: List<InstallPartition> = listOf(INIT_BOOT, BOOT)

        /** 白名单的 wire 值集合（供字符串级校验复用）。 */
        val ALLOWED_WIRE: Set<String> = ALLOWED.map { it.wire }.toSet()

        /**
         * wire → 枚举；`null` / 空 / 白名单外一律返回 `null`。
         *
         * **不在这里兜底**：兜底（"没选就用默认"）只允许发生在
         * [InstallPartitionPolicy.resolve]。
         */
        fun fromWire(value: String?): InstallPartition? {
            val normalized = value?.trim()?.lowercase().orEmpty()
            if (normalized.isEmpty()) return null
            return entries.firstOrNull { it.wire == normalized }
        }
    }
}

/**
 * 分区名 + 槽位 → 真实块设备节点（**唯一**拼接实现）。
 *
 * 槽位来源：`getprop ro.boot.slot_suffix`（如 `_a` / `_b`）。读不到时按规范 fallback
 * [FALLBACK_SLOT]（`_a`）；另外还会尝试**无后缀**节点，因为 A-only 机型的 by-name
 * 节点本来就不带后缀，`_a` 只是"不知道槽位时先试哪个"的顺序，不是猜测机型。
 */
object InstallPartitionNodes {
    /** 槽位属性（与 `InstallerRootSession.discoverBootTarget` 同一个键）。 */
    const val SLOT_PROPERTY: String = "ro.boot.slot_suffix"

    /** 无槽位时的 fallback 槽位（用户口径：无槽位 fallback `_a`）。 */
    const val FALLBACK_SLOT: String = "_a"

    /** 块设备 by-name 目录。 */
    const val BY_NAME_DIR: String = "/dev/block/by-name"

    /** 合法槽位：`_a` / `_b` / `_c` … （下划线 + 单个小写字母/数字）。 */
    private val SLOT_PATTERN = Regex("^_[a-z0-9]$")

    /** 设备上报的槽位 → 规范化槽位（非法/空一律 fallback，不抛）。 */
    fun slotSuffix(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        return if (SLOT_PATTERN.matches(trimmed)) trimmed else FALLBACK_SLOT
    }

    /** 设备是否**上报**了合法槽位（false = 走了 fallback）。 */
    fun hasReportedSlot(raw: String?): Boolean = SLOT_PATTERN.matches(raw?.trim().orEmpty())

    /** `init_boot` + `_a` → `init_boot_a`（供日志/测试直接断言）。 */
    fun nodeName(partition: InstallPartition, slotSuffix: String): String =
        partition.wire + slotSuffix

    /** `/dev/block/by-name/init_boot_a`。 */
    fun nodePath(partition: InstallPartition, slotSuffix: String): String =
        "$BY_NAME_DIR/${nodeName(partition, slotSuffix)}"

    /**
     * 探测顺序：设备上报了槽位 → 只认带槽位的节点；
     * 未上报 → 先 [FALLBACK_SLOT]（`_a`），再试无后缀（A-only 机型）。
     */
    fun nodeCandidates(partition: InstallPartition, rawSlotSuffix: String?): List<String> =
        if (hasReportedSlot(rawSlotSuffix)) {
            listOf(nodePath(partition, slotSuffix(rawSlotSuffix)))
        } else {
            listOf(
                nodePath(partition, FALLBACK_SLOT),
                "$BY_NAME_DIR/${partition.wire}",
            )
        }
}

/** [InstallPartitionPolicy.resolve] 的结论（纯值，不抛异常）。 */
sealed interface InstallPartitionResolution {
    /** 本次实际要修补/写入的分区。 */
    data class Ready(val partition: InstallPartition) : InstallPartitionResolution

    /** 稳定机器码 + 人读细节（UI 只做本地化映射，禁止字符串匹配）。 */
    data class Rejected(val code: String, val detail: String) : InstallPartitionResolution
}

/**
 * 「目标模式 → 允许哪些分区」的**唯一**策略点（纯函数）。
 *
 * UI 继续保留既有默认项；执行时接受 boot/init_boot 的实际载体。
 * 缺少独立 init_boot 的设备使用 boot；桥输入/加载合同由实际 producer 校验。
 * 白名单外一律拒绝，不静默替换用户镜像。

 */
object InstallPartitionPolicy {
    /** Ultra 下请求了别的分区（分区由管线固定，用户不能改）。 */
    const val CODE_ULTRA_FIXED: String = "INSTALL_PARTITION_FIXED_ULTRA"

    /** 请求的分区不在白名单（`init_boot` / `boot`）内。 */
    const val CODE_UNSUPPORTED: String = "INSTALL_PARTITION_UNSUPPORTED"

    /** Legacy UI default only. This is not the execution-time carrier restriction. */
    val ULTRA_FIXED: InstallPartition = InstallPartition.BOOT

    /** Normal 可选分区（顺序 = 选择器展示顺序，`init_boot` 默认在前）。 */
    val NORMAL_SELECTABLE: List<InstallPartition> =
        listOf(InstallPartition.INIT_BOOT, InstallPartition.BOOT)

    /** Normal 默认分区（没选过 / 旧路由没带值）。 */
    val NORMAL_DEFAULT: InstallPartition = InstallPartition.INIT_BOOT

    /** 该模式下的全部可选值（Ultra = 单元素列表，选择器据此决定"能不能点"）。 */
    fun optionsFor(mode: InstallTargetMode): List<InstallPartition> = listOf(ULTRA_FIXED)

    /** 该模式下这个分区是否可选（Ultra 下 `boot` 为 true、`init_boot` 为 false）。 */
    fun isSelectable(mode: InstallTargetMode, partition: InstallPartition): Boolean =
        partition in optionsFor(mode)

    /** 该模式的选择器是否**固定**（= 只有一项，不可改）。 */
    fun isFixed(mode: InstallTargetMode): Boolean = optionsFor(mode).size <= 1

    /** 该模式的默认分区（新建选择状态 / 切模式时的落点）。 */
    fun defaultFor(mode: InstallTargetMode): InstallPartition = optionsFor(mode).first()

    /**
     * 页面/入口传进来的 `partition` 字符串 → 本次真正的目标分区。
     *
     * 判定顺序有意如此：
     * 1. **非空但不在白名单** → [CODE_UNSUPPORTED]（不猜、不静默换成默认值）；
     * 2. Ultra 显式分区必须在已观测拓扑内；
     * 3. 空/`null` → Ultra 默认采用现有 boot 后端；Normal 按实际节点和 KMI 选择。
     */
    fun resolve(
        mode: InstallTargetMode,
        requested: String?,
        availablePartitions: Collection<String>? = null,
        kmi: String? = null,
    ): InstallPartitionResolution {
        val requestedPartition = InstallPartition.fromWire(requested)
        if (!requested.isNullOrBlank() && requestedPartition == null) {
            return InstallPartitionResolution.Rejected(
                CODE_UNSUPPORTED,
                "partition '$requested' is not one of ${InstallPartition.ALLOWED.map { it.wire }}",
            )
        }
        return when (mode) {
            InstallTargetMode.ULTRA -> {
                // With no concrete bridge input, keep the implemented boot producer. The execution
                // entry supplies an installed carrier or a parsed artifact-backed target explicitly.
                val selected = requestedPartition ?: InstallPartition.BOOT
                if (availablePartitions != null && selected.wire !in availablePartitions) {
                    InstallPartitionResolution.Rejected(CODE_UNSUPPORTED,
                        "partition '${selected.wire}' absent in current-slot topology")
                } else InstallPartitionResolution.Ready(selected)
            }

        }
    }

    /** KernelSU selection order: explicit target, existing init_boot except android12, then boot. */
    fun automaticNormal(availablePartitions: Collection<String>?, kmi: String?): InstallPartition =
        if (kmi?.startsWith("android12-") == true ||
            availablePartitions != null && "init_boot" !in availablePartitions) InstallPartition.BOOT
        else InstallPartition.INIT_BOOT

    /** Read only the AOSP header; full container validation remains with the image producer. */
    fun imagePartition(header: ByteArray): InstallPartition? {
        if (header.size < 44 || !header.copyOfRange(0, 8).contentEquals("ANDROID!".toByteArray())) return null
        fun u32(at: Int): Long = (0..3).fold(0L) { value, i ->
            value or ((header[at + i].toLong() and 255L) shl (8 * i))
        }
        val version = u32(40)
        if (version !in 0L..4L) return null
        if (u32(8) > 0) return InstallPartition.BOOT
        return InstallPartition.INIT_BOOT.takeIf { version == 4L && u32(12) > 0 }
    }

    fun imagePartition(input: java.io.InputStream): InstallPartition? {
        val header = ByteArray(44)
        var read = 0
        while (read < header.size) {
            val count = input.read(header, read, header.size - read)
            if (count <= 0) return null
            read += count
        }
        return imagePartition(header)
    }
}

/**
 * 分区选择的状态迁移 + 生效值（纯函数，页面只调这两个）。
 *
 * 为什么不做成一个带字段的状态类：页面本身用 Compose 的 `rememberSaveable` 持有"用户上次选的
 * Normal 分区"，Ultra 的生效值是**推导**出来的（[effective]），两者一旦各存一份就会不同步。
 * 两个纯函数是唯一入口，于是"固定不可改 / 锁定不可改 / 非法值不生效"都能在 JVM 上测死。
 */
object InstallPartitionSelection {

    /**
     * 该模式下**真正生效**的分区。
     *
     * Ultra → [InstallPartitionPolicy.ULTRA_FIXED]（与用户存储值无关：不可改）；
     * Normal → [stored]（用户最后选中的合法值，默认 `init_boot`）。
     */
    fun effective(mode: InstallTargetMode, stored: InstallPartition): InstallPartition =
        if (InstallPartitionPolicy.isFixed(mode)) {
            InstallPartitionPolicy.defaultFor(mode)
        } else {
            stored
        }

    /**
     * 用户点了某个分区之后应该存下来的值。
     *
     * **不生效**的三种情况都返回 [stored]（= 什么也没发生）：
     * 1. [locked]（安装/切换进行中）；
     * 2. 该模式的分区被固定（Ultra）；
     * 3. 请求值不在该模式的可选集合内。
     */
    fun select(
        mode: InstallTargetMode,
        stored: InstallPartition,
        requested: InstallPartition,
        locked: Boolean = false,
    ): InstallPartition = when {
        locked -> stored
        InstallPartitionPolicy.isFixed(mode) -> stored
        !InstallPartitionPolicy.isSelectable(mode, requested) -> stored
        else -> requested
    }
}
