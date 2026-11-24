// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.workmode.detect

import dev.maplesu.manager.workmode.WorkMode

/**
 * T14：**上一次**环境判定的持久化快照（用户要求：参考 KSU，有状态、懒检测）。
 *
 * 为什么需要它（实测根因）：首页每次冷启动/重试都重跑全量探测；探测链里的
 * `rootAvailable()` 在"模式未判定"时走 Ultra 凭据通道，而没装 Ultra 的设备没有凭据
 * （`MANAGEMENT_CREDENTIAL_UNAVAILABLE`）⇒ 判不出模式 ⇒ `KsuRootChannel` 不注册 ⇒ 拿不到
 * root ⇒ 更判不出模式（互锁），首页永远"环境检查未完成"。
 *
 * 语义：**已安装先展示、后台核对 SU**。SU 仍在且快照有效则继承；未安装每次重新快查。
 *
 * @param mode 上次结论的终态模式；`null` = 上次结论是"未安装"（有状态，但没有任何模式）。
 * @param suAvailable 上次 `/system/bin/su` 快速核对结果（诊断用；不参与继承裁决）。
 * @param ultraEnvFingerprint 上次 Ultra 环境指纹（凭据/Ultra/私有目录，具体取值见适配器）。
 * @param bootId 上次判定时的 `/proc/sys/kernel/random/boot_id`；与本 boot 不同 ⇒ 状态**失效**
 *   （用户可能绕过管理器在 recovery/fastboot 刷过内核，必须重判一次）。
 * @param dirty 显式失效位：模式切换 / 刷写 / 用户强制重试时置位。
 */
data class EnvironmentSnapshot(
    val mode: WorkMode?,
    val suAvailable: Boolean,
    val ultraEnvFingerprint: String?,
    val bootId: String,
    val checkedAtMillis: Long,
    val dirty: Boolean = false,
) {
    /** 仅用于日志/证据；不含路径、凭据或包名。 */
    val describe: String
        get() = "mode=${mode?.suffix ?: "NONE"} su=$suAvailable ultra=${ultraEnvFingerprint ?: "NONE"} " +
            "dirty=$dirty"
}

/**
 * 让"上次状态"失效的**显式动作**（只有这些；每一种都有具名 code，便于证据/日志引用）。
 *
 * 用户要求：管理器没有刷写动作或工作状态修改时优先继承。反过来说，这些动作发生后
 * 必须重新检测 —— 否则会把"刷完 Normal 但还显示 Ultra"这类错误状态无限继承下去。
 */
enum class EnvironmentInvalidation(val code: String) {
    /** 模式切换事务已提交（`modes.json.current` 变了）。 */
    MODE_SWITCHED("ENV_DIRTY_MODE_SWITCHED"),

    /** 分区/镜像刷写完成。 */
    FLASHED("ENV_DIRTY_FLASHED"),

    /** 安装器任务完成（可能改变 root 通道）。 */
    INSTALLER_COMPLETED("ENV_DIRTY_INSTALLER_COMPLETED"),

    /** 用户显式点"重新检测环境"。 */
    USER_RETRY("ENV_DIRTY_USER_RETRY"),
}

/** 持久化端口。生产 = SharedPreferences（[dev.maplesu.manager.workmode.detect.probes]）；测试 = 内存。 */
interface EnvironmentStatePersistence {
    fun read(): EnvironmentSnapshot?
    fun write(snapshot: EnvironmentSnapshot)
    fun clear()
}

/** 内存实现（单测 / 无 prefs 环境；也是"进程内缓存"的最小形态）。 */
class InMemoryEnvironmentStatePersistence(initial: EnvironmentSnapshot? = null) :
    EnvironmentStatePersistence {

    @Volatile
    var stored: EnvironmentSnapshot? = initial

    override fun read(): EnvironmentSnapshot? = stored

    override fun write(snapshot: EnvironmentSnapshot) {
        stored = snapshot
    }

    override fun clear() {
        stored = null
    }
}

/**
 * 快照 ⇄ 键值对（prefs 适配器只做搬运，**不**参与语义）。
 *
 * 解码纪律：schema 不认识 / 关键字段缺失 / 模式 wire 不合法 → 返回 null（"没有状态"），
 * 于是退化成一次完整探测。绝不把损坏的记录当成"未安装"。
 */
object EnvironmentSnapshotCodec {
    const val SCHEMA_KEY = "env.schema"
    const val SCHEMA_VERSION = "1"
    const val MODE_KEY = "env.mode"
    const val MODE_NONE = "NONE"
    const val SU_KEY = "env.su"
    const val ULTRA_KEY = "env.ultra"
    const val BOOT_KEY = "env.bootId"
    const val CHECKED_AT_KEY = "env.checkedAt"
    const val DIRTY_KEY = "env.dirty"

    fun encode(snapshot: EnvironmentSnapshot): Map<String, String> = buildMap {
        put(SCHEMA_KEY, SCHEMA_VERSION)
        put(MODE_KEY, snapshot.mode?.suffix ?: MODE_NONE)
        put(SU_KEY, if (snapshot.suAvailable) "1" else "0")
        snapshot.ultraEnvFingerprint?.takeIf { it.isNotBlank() }?.let { put(ULTRA_KEY, it) }
        put(BOOT_KEY, snapshot.bootId)
        put(CHECKED_AT_KEY, snapshot.checkedAtMillis.toString())
        put(DIRTY_KEY, if (snapshot.dirty) "1" else "0")
    }

    fun decode(values: Map<String, String?>): EnvironmentSnapshot? {
        if (values[SCHEMA_KEY] != SCHEMA_VERSION) return null
        val modeWire = values[MODE_KEY] ?: return null
        // Retired unresolved-SU snapshots are not installation records. Recheck from scratch.
        if (modeWire == MODE_NONE && values["env.suTemp"] == "1") return null
        val mode = when (modeWire) {
            MODE_NONE -> null
            else -> WorkMode.fromSuffix(modeWire) ?: return null
        }
        val bootId = values[BOOT_KEY]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val checkedAt = values[CHECKED_AT_KEY]?.toLongOrNull() ?: return null
        return EnvironmentSnapshot(
            mode = mode,
            suAvailable = values[SU_KEY] == "1",
            ultraEnvFingerprint = values[ULTRA_KEY]?.takeIf { it.isNotBlank() },
            bootId = bootId,
            checkedAtMillis = checkedAt,
            dirty = values[DIRTY_KEY] == "1",
        )
    }
}

/**
 * 上次状态的读写门面（纯 Kotlin，无 Android/IO 依赖）。
 *
 * 每次调用只做一次 [EnvironmentStatePersistence] 访问；生产实现内部就是一次 prefs 读，
 * 调用点都在后台线程。读失败一律当"没有状态"（退化成一次探测），绝不伪造继承。
 */
class EnvironmentStateStore(private val persistence: EnvironmentStatePersistence) {

    fun snapshot(): EnvironmentSnapshot? = runCatching { persistence.read() }.getOrNull()

    fun isDirty(): Boolean = snapshot()?.dirty == true

    /**
     * 置脏：**保留**上次状态（`mode`/`su`/`ultra` 字段都不动），只打开 `dirty`。
     *
     * @return 更新后的快照；null = 本来就没有状态（条件① 已经满足，不需要置脏）。
     */
    fun markDirty(reason: EnvironmentInvalidation): EnvironmentSnapshot? {
        val previous = snapshot() ?: return null
        if (previous.dirty) return previous
        val updated = previous.copy(dirty = true)
        runCatching { persistence.write(updated) }
        return updated
    }

    fun save(snapshot: EnvironmentSnapshot) {
        runCatching { persistence.write(snapshot) }
    }

    fun clear() {
        runCatching { persistence.clear() }
    }
}

/**
 * 本次要不要真的跑探测（用户要求：**只有两个条件**）。
 *
 * 条件①"没有状态"在这里精确化成"没有**本 boot 有效**的状态"：bootId 不同说明设备可能
 * 在管理器之外换过内核（recovery/fastboot），继承旧结论会把错误状态无限传下去。
 * 条件②是显式脏位。除此之外（同 boot 且未脏）一律继承。
 */
enum class EnvironmentCheckTrigger(val code: String) {
    NO_STATE("ENV_CHECK_NO_STATE"),
    NOT_INSTALLED("ENV_CHECK_NOT_INSTALLED"),
    SU_MISSING("ENV_CHECK_SU_MISSING"),
    SU_RECHECK("ENV_CHECK_SU_RECHECK"),
    BOOT_CHANGED("ENV_CHECK_BOOT_CHANGED"),
    DIRTY("ENV_CHECK_DIRTY"),
}

object EnvironmentInheritancePolicy {

    /**
     * @return null = SU 快核对后可以继承；非空 = 必须重新识别，值即具名原因。
     *
     * 用户口径（2026-11-02 两次澄清后的最终版）：
     * - **继承要保留**：冷启动优先沿用上次**已确定**的状态，检查在后台**静默**做；
     * - **不继承"未完成"**：`EnvironmentSnapshot` 只由终态写入（`EnvironmentResolver`
     *   对 `ENV_UNKNOWN_TIMEOUT` 明确不落盘）；未安装也不继承，每次快速检查 SU 与 Key；
     * - 状态被显式标脏（刷写 / 换模式 / 用户强制重试）或 bootId 变化 → 必须重判。
     */
    fun triggerFor(snapshot: EnvironmentSnapshot?, currentBootId: String): EnvironmentCheckTrigger? =
        when {
            snapshot == null -> EnvironmentCheckTrigger.NO_STATE
            snapshot.mode == null -> EnvironmentCheckTrigger.NOT_INSTALLED
            snapshot.dirty -> EnvironmentCheckTrigger.DIRTY
            // bootId 读不到（空串）时无法证明"同一个 boot" → 必须探测（fail-safe）。
            currentBootId.isBlank() -> EnvironmentCheckTrigger.BOOT_CHANGED
            snapshot.bootId != currentBootId -> EnvironmentCheckTrigger.BOOT_CHANGED
            // 旧版本曾把持有凭据当成 Ultra；重查一次，不继承这个误判。
            snapshot.mode == WorkMode.LITE && snapshot.ultraEnvFingerprint in
                setOf("ultra:paired", "lite:credential") -> EnvironmentCheckTrigger.DIRTY
            else -> null
        }
}
