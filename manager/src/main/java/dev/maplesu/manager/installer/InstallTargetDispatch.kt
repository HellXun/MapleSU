// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.installer

import dev.maplesu.manager.workmode.WorkMode

/**
 * 本次修补的**方式**（目标模式的产物，不是页面各自 if 出来的）。
 *
 * - [ULTRA_ULTRA_KERNEL]：Ultra 内核 release 资产/门禁链路（`UltraKernelFlow`）；
 * - [NORMAL_KSU_LKM]：KSU LKM 链路（`KsuInstallGlue` → `KsuInstallProducer` → ksud 产候选）。
 */
enum class InstallPatchPath {
    ULTRA_ULTRA_KERNEL,
    NORMAL_KSU_LKM,
}

/** 修补对象来源：用户选的镜像文件 / 下载得到的镜像 / 当前分区直装。 */
enum class InstallSourceKind {
    /** 用户选择的镜像文件（`InstallMethod.SelectFile`）。 */
    IMAGE,

    /**
     * 下载并按目标分区提取的镜像。枚举名沿用旧调用契约，可承载 boot 或 init_boot。
     */
    DOWNLOADED_BOOT,

    /**
     * 当前分区直装（`InstallMethod.DirectInstall`）：由 **root 读取当前槽的目标分区**得到源镜像
     * （`RootPartitionExtractor`），再走与"选文件"完全相同的修补/提交链。
     *
     * 注意：这里不再等同于"由模式切换事务负责"。Normal 的直装是**安装事务**（产候选 → 共用
     * 事务写盘），与 A↔B 模式切换是两条不同的入口。
     */
    CURRENT_PARTITION,
}

/** Normal 路径的稳定失败码（UI 只做本地化映射，禁止字符串匹配）。 */
object InstallTargetCodes {
    /**
     * 旧装配的"必须先选一份镜像"码（**保留常量**：已有日志/排障口径不改）。
     *
     * 直装（`CURRENT_PARTITION`）现在由 root 提取当前槽分区，不再产生这个码；
     * 分区名非法/被模式固定走 [InstallPartitionPolicy.CODE_UNSUPPORTED] /
     * [InstallPartitionPolicy.CODE_ULTRA_FIXED]。
     */
    const val KSU_INSTALL_REQUIRES_IMAGE = "KSU_INSTALL_REQUIRES_IMAGE"

    /** 下载方式只提供 `boot` 分区条目，不能用于 Normal 的镜像修补。 */
    const val KSU_DOWNLOAD_PARTITION_UNSUPPORTED = "KSU_DOWNLOAD_PARTITION_UNSUPPORTED"

    /** 设备内核 release 读不到 / 解析不出 KMI。 */
    const val KMI_UNPARSEABLE = "KMI_UNPARSEABLE"

    /** 解析出 KMI，但不在本分支实际构建的 Normal 矩阵内。 */
    const val KMI_NOT_IN_MATRIX = "KMI_NOT_IN_MATRIX"

    /** 矩阵内有，但本次 APK 没打进该 KMI 的 `kernelsu.ko`。 */
    const val KMI_ARTIFACT_MISSING = "KMI_ARTIFACT_MISSING"

    /** 内核代际与 `ro.board.platform` 矛盾。 */
    const val KMI_KERNEL_PLATFORM_MISMATCH = "KMI_KERNEL_PLATFORM_MISMATCH"
}

/**
 * Normal 不可用时的 blocker（纯层不引用 `R`：UI 用 `when` 映射到字符串资源）。
 */
enum class InstallTargetBlocker {
    /** 需要选一个镜像（**保留**：旧装配/旧日志口径；直装现在走 root 提取）。 */
    NORMAL_REQUIRES_IMAGE,

    /** 下载方式只提供 `boot` 分区，不能作为镜像修补输入。 */
    NORMAL_DOWNLOAD_UNSUPPORTED,

    /** 该模式下的目标分区被固定（Ultra 只能是管线自己的那一个），用户选择被拒绝。 */
    PARTITION_FIXED,

    /** 请求的分区不在白名单（`init_boot` / `boot`）内。 */
    PARTITION_UNSUPPORTED,

    /** 设备内核 release 读不到 / 无法解析成 KMI。 */
    KMI_UNPARSEABLE,

    /** 设备 KMI 不在本分支构建矩阵内（没有该 KMI 的 `kernelsu.ko`）。 */
    KMI_NOT_IN_MATRIX,

    /** 设备 KMI 在矩阵内，但本次 APK 未打包该 KMI 的 `kernelsu.ko`。 */
    KMI_ARTIFACT_MISSING,

    /** 内核代际与平台交叉校验矛盾。 */
    KMI_KERNEL_PLATFORM_MISMATCH,
}

/** 分派输入（页面/门面把设备事实填进来；纯函数不自己读 Android）。 */
data class InstallTargetRequest(
    val target: InstallTargetMode,
    val source: InstallSourceKind,
    /**
     * 用户/页面声明的目标分区（wire 值）。
     *
     * - `null` / 空 → 该模式的默认值（Normal = `init_boot`，Ultra = `boot`）；
     * - 白名单外 → 拒绝（[InstallPartitionPolicy.CODE_UNSUPPORTED]，不静默兜底）；
     * - Ultra 显式 init_boot 进入桥 producer；缺产物由执行链返回。
     */
    val partition: String? = null,
    /** 设备内核 release（`uname -r` / `/proc/sys/kernel/osrelease`）；null/空 = 未知。 */
    val kernelRelease: String? = null,
    /** 本次 APK 内嵌的 KMI 目录（`ksud boot-info supported-kmis`）；null = 未上报。 */
    val availableKmis: Collection<String>? = null,
    /** `ro.board.platform`（只做交叉校验，不做选择依据）。 */
    val boardPlatform: String = "",
    /** `ro.build.version.sdk`（0 = 未知；仅记录证据，不参与 KMI 选择）。 */
    val sdkInt: Int = 0,
    /** Current-slot nodes, when observed; null keeps compatibility with file-only callers. */
    val availablePartitions: Collection<String>? = null,
) {
    companion object {
        /** Normal 的默认分区（B v1 = 独立 `init_boot` + LKM，与 `KsuInstallPolicy` 一致）。 */
        const val NORMAL_PARTITION = "init_boot"

        /** 两个模式共同的分区白名单（`init_boot` / `boot`）。 */
        val ALLOWED_PARTITIONS: Set<String> = InstallPartition.ALLOWED_WIRE
    }
}

/** 分派结论：要么可执行（带方式/分区/KMI），要么 fail-closed（带稳定码 + 明确 blocker）。 */
sealed interface InstallTargetDecision {
    val target: InstallTargetMode

    data class Ready(
        override val target: InstallTargetMode,
        val path: InstallPatchPath,
        val workMode: WorkMode,
        /**
         * 实际修补/写入的分区（wire 值）。
         *
         * Ultra = 已解析的真实载体；
         * Normal = 用户选的那个（`init_boot` 默认 / `boot` 可选）。
         * 修补（`ksud boot-patch --partition`）与刷写（候选的 `partition`）**都**用它，
         * 因此不存在"补 init_boot、写 boot"这种错位。
         */
        val partition: String?,
        /** 当前分区 Normal 的设备 KMI；文件目标由生产者解析，Ultra 恒为 null。 */
        val kmi: String? = null,
        /** Normal 期望的内嵌产物名（`<kmi>_kernelsu.ko`）；Ultra 恒为 null。 */
        val kmiArtifact: String? = null,
        /**
         * 内嵌 KMI 目录是否已知。`false` = 未上报：产候选阶段由 ksud 按 `--kmi` 查内嵌资产
         * 并**硬失败**兜底（绝不静默换用别的 KMI）。
         */
        val kmiCatalogKnown: Boolean = false,
    ) : InstallTargetDecision

    data class Blocked(
        override val target: InstallTargetMode,
        /** 稳定机器码（写日志/事务记录；UI 用 [blocker] 本地化，不做字符串匹配）。 */
        val code: String,
        val blocker: InstallTargetBlocker,
        /** 人读细节（含缺的资产名/矩阵/已打包列表），写日志用。 */
        val detail: String = "",
        val kmi: String? = null,
    ) : InstallTargetDecision
}

/**
 * 目标模式 → 修补路径的**唯一**分派点（纯函数，主机可测）。
 *
 * 页面（`InstallScreen`）与修补入口（`com.maplesu.core.ui.util.installBoot`）都只消费本函数结果，
 * 不允许各自 `if (mode == ...)`：
 *
 * - **Ultra** → [InstallPatchPath.ULTRA_ULTRA_KERNEL]，继续既有 Ultra 链路（release 资产/门禁）。
 *   release 没有对应资产时由 `UltraKernelFlow` 自己报 `ULTRA_KERNEL_RELEASE_UNSUPPORTED:<release>`
 *   或回退到兼容 manifest —— 这里是**既有行为**，本分派不额外拦截，以免改变 Ultra 语义。
 *   按输入内容/实际后端与拓扑解析载体；init_boot 的桥输入由 producer 核实。
 * - **Normal** → [InstallPatchPath.NORMAL_KSU_LKM]，按实际目标选择 `init_boot` 或 `boot`。
 *   当前分区使用设备 KMI；文件交给生产者从目标内核解析后选择 `kernelsu.ko`。
 *   对应执行阶段准确返回 KMI 或资产错误，不回退 Ultra。
 */
object InstallTargetDispatch {

    fun decide(request: InstallTargetRequest): InstallTargetDecision = decideUltra(request)

    /** Ultra routes an explicit carrier without claiming that its bridge artifact exists. */
    private fun decideUltra(request: InstallTargetRequest): InstallTargetDecision =
        when (val resolution = InstallPartitionPolicy.resolve(InstallTargetMode.ULTRA, request.partition, request.availablePartitions)) {
            is InstallPartitionResolution.Ready -> InstallTargetDecision.Ready(
                target = InstallTargetMode.ULTRA,
                path = InstallPatchPath.ULTRA_ULTRA_KERNEL,
                workMode = WorkMode.LITE,
                partition = resolution.partition.wire,
            )
            is InstallPartitionResolution.Rejected -> InstallTargetDecision.Blocked(
                target = InstallTargetMode.ULTRA,
                code = resolution.code,
                blocker = resolution.blocker(),
                detail = resolution.detail,
            )
        }

    /** File targets resolve their KMI from the actual source during candidate generation. */
    /** 分区被拒 → UI blocker（一码一文案；纯层不引用 `R`）。 */
    private fun InstallPartitionResolution.Rejected.blocker(): InstallTargetBlocker = when (code) {
        InstallPartitionPolicy.CODE_ULTRA_FIXED -> InstallTargetBlocker.PARTITION_FIXED
        else -> InstallTargetBlocker.PARTITION_UNSUPPORTED
    }
}
