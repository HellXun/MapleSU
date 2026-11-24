package dev.maplesu.manager.workmode.detect

import dev.maplesu.manager.workmode.control.schema.InstalledFile
import dev.maplesu.manager.workmode.control.schema.ModesFile
import dev.maplesu.manager.workmode.control.schema.SwitchHeadView

/**
 * 判定输入事实。只含数据、无 Android 依赖 → 主机可构造。
 *
 * 约定：所有字段的默认值表示"未知/未探测"，探针未接线的项一律保持默认值，
 * **禁止**把默认值当成负面证据（UNKNOWN ≠ false）。
 */

/** 同树 JNI `KsuNatives` 的非 root 探针结果。 */
data class KsuFacts(
    val responded: Boolean = false,
    val version: Int = -1,
    val uapi: Int = -1,
    val isManager: Boolean = false,
    val isLkm: Boolean = false,
)

/** Ultra 环境缓存状态（UltraEnvironmentProbe 的五态 + UNKNOWN）。 */
enum class UltraState { RUNNING, NOT_INSTALLED, NO_KEY, FAULT, UNKNOWN }

/** A 侧传输（凭据 / Ultra）事实。 */
data class LiteFacts(
    val credentialPresent: Boolean = false,
    /** null = 未尝试（**不是**失败；handshakeNullIsNotFalse）。 */
    val handshakeOk: Boolean? = null,
    val ultraState: UltraState = UltraState.UNKNOWN,
    val ultraKeyPresent: Boolean = false,
    /** Current-boot, current-key SDK uid-0 proof; independent of userspace boot receipts. */
    val ultraKernelRootVerified: Boolean = false,
    /** Signed Manager identity from this observation, without Root or helper readiness. */
    val managerOwnerBound: Boolean = false,
)

/** 一次 root 会话取到的磁盘证据；null 字段 = 该项未能读取（≠ 不存在）。 */
data class RootFacts(
    val channel: RootChannelKind,
    val modesJson: ModesFile? = null,
    val installedJson: InstalledFile? = null,
    val switchHead: SwitchHeadView? = null,
    val aAuthorizationTable: Boolean? = null,
    val aInstallerRecord: Boolean? = null,
    val aPublishedDirectory: Boolean? = null,
    val ksuDirHasContent: Boolean? = null,
    val ksudExecutable: Boolean? = null,
    val kmiWritablePreinit: Boolean? = null,
    val foreignManagerMarkers: Boolean? = null,
)

/** 阶段 2 使用的 root 通道（禁止盲试；由阶段 0/1 结论决定）。 */
enum class RootChannelKind { KSU_NATIVE, LITE_DIRECT, LITE_ULTRA }

/** 阶段 0 本地只读事实（<50ms，主线程可跑）。 */
data class LocalFacts(
    val bootId: String,
    val managerFirstInstallAgeMs: Long,
    val lastKnownMode: String? = null,
    val lastKnownBootId: String? = null,
    val installerLastTaskId: String? = null,
    val freshInstallWindowMs: Long = 5 * 60 * 1000L,
)

/** 一次判定的全部输入。 */
data class DetectionFacts(
    val local: LocalFacts,
    val ksu: KsuFacts = KsuFacts(),
    val lite: LiteFacts = LiteFacts(),
    /** null = 阶段 2 未执行。 */
    val root: RootFacts? = null,
    /** 主文档:148 KERNEL_SU_UAPI_VERSION=7。 */
    val ksuBranchUapi: Int = 7,
)
