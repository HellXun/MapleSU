package com.maplesu.core.ui.security

import dev.maplesu.manager.workmode.WorkMode

/**
 * 隐身密令文件的**模式感知路径策略**（纯 Kotlin，主机可测）。
 *
 * 事实（主文档 §3.3 / B 分支 ksud 源码 `userspace/ksud/src/webadmin_ksud.rs:114`）：
 * - A（Lite）：权威路径仍是 `/data/adb/sevenk/stealth_code`（本次不回归）；
 * - B（KSU）：ksud 读的是 `/data/adb/ksu/stealth_code`；
 * - B 下兼容读 sevenk 旧值仅作**迁移兜底（只读，不写）**；
 * - A 下兼容读 `/data/adb/ksu/stealth_code`（历史软链兜底），读到后自愈补写到 sevenk（原行为）。
 *
 * 写操作：B 写 ksu 路径；A/未判定保持 sevenk（未判定按 A 现有行为，Lite 不回归）。
 */
data class StealthReadPath(
    val path: String,
    /** 读到该文件后是否补写到当前模式的主路径（仅 A 的 legacy 软链兜底为 true）。 */
    val selfHealWriteToPrimary: Boolean = false,
)

object StealthPathPolicy {
    const val A_DIR = "/data/adb/sevenk"
    const val A_PATH = "$A_DIR/stealth_code"
    const val B_DIR = "/data/adb/ksu"
    const val B_PATH = "$B_DIR/stealth_code"

    /** 内核持久化隐身 flag（内核 `stealth.c::KSU_STEALTH_PATH` 按模式不同）。 */
    const val A_FLAG_PATH = "$A_DIR/stealth"
    const val B_FLAG_PATH = "$B_DIR/stealth"

    /** 当前模式的主路径（读/写/UI 展示优先）。 */
    fun primaryPath(mode: WorkMode?): String = when (mode) {
        WorkMode.LITE, null -> A_PATH
    }

    /** 写路径：B→ksu；A/未判定→sevenk（不回归 Lite）。 */
    fun writePath(mode: WorkMode?): String = primaryPath(mode)

    fun writeDir(mode: WorkMode?): String = when (mode) {
        WorkMode.LITE, null -> A_DIR
    }

    /**
     * 读取顺序（按优先级）：
     * - B：[ksu, sevenk]（sevenk 只读兼容，不写回）；
     * - A/未判定：[sevenk, ksu-legacy(self-heal)]（与现有行为一致）。
     */
    fun readPaths(mode: WorkMode?): List<StealthReadPath> = when (mode) {

        WorkMode.LITE, null -> listOf(
            StealthReadPath(A_PATH),
            StealthReadPath(B_PATH, selfHealWriteToPrimary = true),
        )
    }

    /** 接收器 acceptedCodes 并集用的路径（顺序即优先级）。 */
    fun acceptedPaths(mode: WorkMode?): List<String> = readPaths(mode).map { it.path }

    /**
     * 内核持久化 flag 的主路径：B=`/data/adb/ksu/stealth`（B 内核
     * `applied/kernel/manager/stealth.c` 的 `KSU_STEALTH_PATH`）；
     * A/未判定=`/data/adb/sevenk/stealth`（现状不变）。
     */
    fun flagPrimaryPath(mode: WorkMode?): String = when (mode) {
        WorkMode.LITE, null -> A_FLAG_PATH
    }

    /**
     * flag 兜底读取顺序（仅在 JNI `stealth_get` 读不到时使用）：
     * - B：[ksu, sevenk]（sevenk 旧值只读兼容）；
     * - A/未判定：[sevenk, ksu]（现状 + 只读兼容）。
     */
    fun flagReadPaths(mode: WorkMode?): List<StealthReadPath> = when (mode) {
        WorkMode.LITE, null -> listOf(StealthReadPath(A_FLAG_PATH), StealthReadPath(B_FLAG_PATH))
    }

    fun flagPaths(mode: WorkMode?): List<String> = flagReadPaths(mode).map { it.path }
}
