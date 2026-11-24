package dev.maplesu.manager.workmode.detect

/**
 * 探针端口与证据类型。
 *
 * 每项一个纯接口，便于主机 fake；实现见 `probes/`（Android 适配器）。
 * 未被接线的探针一律返回"未知"默认值，**禁止**猜测（UNKNOWN ≠ 负面证据）。
 */

fun interface KsuKernelProbe { fun probe(): KsuFacts }

/** (credentialPresent, handshakeOk)；handshakeOk == null 表示未尝试。 */
fun interface LiteCredentialProbe { fun probe(): Pair<Boolean, Boolean?> }

data class UltraEnvironmentObservation(
    val state: UltraState,
    val kernelRootVerified: Boolean = false,
    val managerOwnerBound: Boolean = false,
)

fun interface UltraEnvironmentPort {
    fun state(): UltraState
    /** Refresh is allowed only after the non-root KSU probe and the startup consent gate. */
    fun observe(allowRefresh: Boolean): UltraEnvironmentObservation = UltraEnvironmentObservation(state())
    /** Owner discovery is non-root and separately permitted after KSU conflict exclusion. */
    fun observe(allowRefresh: Boolean, allowOwnerQuery: Boolean): UltraEnvironmentObservation = observe(allowRefresh)
}

/**
 * Lite 凭据→UID0 握手探针（独立于"凭据存在"：
 * 存在性可在启动期只读（Keystore 解密不拿 root），握手会起 Lite executor，**只允许在
 * `allowLiteExecutor=true` 的阶段二尝试**）。null = 未尝试/未知；false = 已尝试但失败。
 */
fun interface LiteHandshakeProbe { fun handshake(): Boolean? }

fun interface BootIdSource { fun read(): String }

fun interface Clock { fun nowMillis(): Long }

/** 挂钟实现。 */
object WallClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** 默认 bootId 来源：读 /proc；读不到返回空串（= 未知，不得当成某个 boot）。 */
object ProcBootIdSource : BootIdSource {
    override fun read(): String = runCatching {
        java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("")
}

/** root 取证：一次会话内批量读，避免 N 次 su。实现由 installer 服务或各通道适配。 */
interface RootEvidenceReader {
    fun open(channel: RootChannelKind): RootEvidenceSession?
}

interface RootEvidenceSession : AutoCloseable {
    fun readControlFile(name: String): String?
    fun isExecutable(path: String): Boolean?
    fun isRealDirectory(path: String): Boolean?
    fun hasAnyEntry(path: String): Boolean?
    fun exists(path: String): Boolean?
}

/** 不可用的 root 取证端口：所有 open 返回 null（= 无通道，绝不伪造证据）。 */
object UnavailableRootEvidenceReader : RootEvidenceReader {
    override fun open(channel: RootChannelKind): RootEvidenceSession? = null
}

/** 证据强度（S = 内核/系统接口直接证明）。 */
enum class Strength { S, A, B, C }

/** 每项证据：id 与 §2.3 表一致，detail 不得含凭据/rootKey/stealth_code。 */
data class ProbeTrace(
    val id: String,
    val strength: Strength,
    /** null = UNKNOWN（非负面）。 */
    val ok: Boolean?,
    val detail: String,
    val elapsedMillis: Long,
)

enum class DetectionOrigin { STARTUP, BOOT_COMPLETED, WORKMODE_SCREEN, INSTALL_PAGE, RESTORE, AFTER_ROOT_GRANTED }

data class DetectionRequest(
    val origin: DetectionOrigin,
    /** 见 §2.5 同意规则：启动期不拿 root。 */
    val allowRootProbe: Boolean,
    /** 未排除 KSU 冲突前必须 false。 */
    val allowLiteExecutor: Boolean,
    val timeoutMillis: Long = 8_000,
)

data class DetectionResult(
    val state: ModeState,
    val group: DetectionGroup,
    val rule: DetectionRules.Rule,
    val bootId: String,
    val observedAtMillis: Long,
    /** 每项证据：id/strength/ok/detail/耗时。 */
    val trace: List<ProbeTrace>,
    val recovery: RecoveryReadiness,
    val rootChannel: RootChannelKind?,
)

/** 探测器接口（实现见 DefaultModeDetector）。 */
interface ModeDetector {
    suspend fun detect(request: DetectionRequest): DetectionResult
}
