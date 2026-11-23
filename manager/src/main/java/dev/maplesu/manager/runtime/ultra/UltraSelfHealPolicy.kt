// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.workmode.WorkMode

/** 自愈动作：只用两种，避免「顺手多修一点」变成不可审计的写设备行为。 */
enum class SelfHealAction {
    /** 从 root 备份恢复 key（必要时补装环境），然后重新配对/白名单/刷新备份。 */
    RECOVER_FROM_BACKUP,

    /** key 已在本地：只补齐 su_auth 自白名单与备份（升级/迁移路径）。 */
    ENSURE_WHITELIST_AND_BACKUP,
}

/** 不动作的机器码原因（可直接进日志与文档；不含路径以外的敏感信息）。 */
enum class SelfHealSkip {
    ALREADY_ATTEMPTED,
    KSU_MODE,
    MODE_UNRESOLVED,
    QUIESCING,
    SUSPENDED,
    RATE_LIMITED,
    NO_ROOT,
    NO_KEY,
    BACKUP_UNREADABLE,
    BACKUP_INVALID,
}

/** 本地 key 的格式状态（与 [UltraAutoPair.KeyState] 同义，但更窄：只关系能否用于恢复）。 */
enum class StoredKeyState { OK, ABSENT, MALFORMED }

/** root 备份文件的状态（由 [UltraRecoveryKeyStore.restore] 的机器码映射而来）。 */
enum class BackupState { OK, ABSENT, UNREADABLE, INVALID }

/** key 的来源。[STORE] = 本地已有；[BACKUP] = 从 root 备份恢复。 */
enum class KeySource { STORE, BACKUP }

/** 判 LITE 的依据：运行时激活，还是「运行时未判定时的磁盘/通道证据」。 */
enum class ModeSource { LITE_RUNTIME, LITE_DISK_EVIDENCE, NONE }

/** 决策结果。 */
sealed interface SelfHealDecision {
    data class Skip(val reason: SelfHealSkip, val detail: String = "") : SelfHealDecision

    data class Proceed(
        val action: SelfHealAction,
        val keySource: KeySource,
        val modeSource: ModeSource,
        /** 恢复路径下若环境是 NotInstalled，需要先补装；[KeySource.STORE] 路径不做。 */
        val installEnvironmentIfMissing: Boolean,
        /** 本地 key 存在但格式非法：用备份覆盖它。 */
        val replaceMalformedKey: Boolean = false,
    ) : SelfHealDecision
}

/**
 * 自愈**触发条件矩阵**（纯函数，无 Android/IO/日志）。
 *
 * <p>顺序敏感，逐条对应产品约束：
 *
 *  1. 每个进程只尝试一次（`ALREADY_ATTEMPTED`）——防同一冷启动内的循环；
 *  2. KSU 模式**永不**执行（`KSU_MODE`）——不碰另一种工作模式；
 *  3. 模式未判定时，只接受「磁盘 + su 通道」的强证据（`MODE_UNRESOLVED` 否则）——
 *     因为重装后 `ModeRuntime` 往往判不出来（`UltraEnvironmentAdapter` 需要 key，
 *     `LiteCredentialProbe` 需要凭据），而这正是要自愈的场景；
 *  4. 切换写者静默期 / 用户「暂时卸载」一律不动；
 *  5. 失败退避窗口内不动（[UltraSelfHealBackoff]）；
 *  6. 本地已有合法 key → 只补 `su_auth` 白名单与备份（**不要求 root**：写 `su_auth`
 *     只需要 SDK + key；备份这一步在无根时由调用方 best-effort 处理）。这一条是设备上
 *     实测补出来的：已有 key、环境 Running、但从未进过 `su_auth` 的设备如果在这里被
 *     `NO_ROOT` 拦掉，就永远没有机会获得免 key 根通道（死锁）。
 *  7. 本地没有 key / 格式非法 → **必须**有免 key 的根通道才能读备份
 *     （`NO_ROOT`）：key 只能从 root 备份里恢复，猜不出来；
 *  8. 没有本地 key 且没有可用备份 → `NO_KEY`；备份存在但读不出/解不开 → 对应机器码。
 */
object UltraSelfHealPolicy {

    data class Facts(
        val mode: WorkMode?,
        val liteEvidence: UltraLiteEvidence.Result,
        val quiescing: Boolean = false,
        val suspended: Boolean = false,
        val attemptedThisProcess: Boolean = false,
        val nowMillis: Long = 0L,
        val limitedUntilMillis: Long = 0L,
        val rootUid: Int? = null,
        val storedKeyState: StoredKeyState = StoredKeyState.ABSENT,
        val backup: BackupState = BackupState.ABSENT,
    )

    /**
     * 只做「要不要动手」的前置判断（模式 / 静默 / 暂停 / 限频 / 根通道）。
     *
     * <p>单独拆出来是因为读 root 备份本身要起一次 su 进程：先过前置，再决定要不要去读
     * 备份 —— 否则每次冷启动都会为一个必然跳过的情况跑一遍 root。
     */
    data class Preflight(val modeSource: ModeSource, val skip: SelfHealDecision.Skip? = null)

    fun preflight(facts: Facts): Preflight {
        if (facts.attemptedThisProcess) {
            return Preflight(ModeSource.NONE, SelfHealDecision.Skip(SelfHealSkip.ALREADY_ATTEMPTED))
        }
        val modeSource = when (facts.mode) {
            WorkMode.LITE -> ModeSource.LITE_RUNTIME
            null -> if (facts.liteEvidence.strong) {
                ModeSource.LITE_DISK_EVIDENCE
            } else {
                return Preflight(
                    ModeSource.NONE,
                    SelfHealDecision.Skip(
                        SelfHealSkip.MODE_UNRESOLVED,
                        facts.liteEvidence.reasons.joinToString(","),
                    ),
                )
            }
        }
        if (facts.quiescing) {
            return Preflight(modeSource, SelfHealDecision.Skip(SelfHealSkip.QUIESCING))
        }
        if (facts.suspended) {
            return Preflight(modeSource, SelfHealDecision.Skip(SelfHealSkip.SUSPENDED))
        }
        if (facts.nowMillis < facts.limitedUntilMillis) {
            return Preflight(
                modeSource,
                SelfHealDecision.Skip(SelfHealSkip.RATE_LIMITED, "until=${facts.limitedUntilMillis}"),
            )
        }
        // 注意：root 不在这里判。ENSURE_WHITELIST_AND_BACKUP 只需要 key（SDK 调用），
        // 恰恰是「已有 key、环境 Running、但还没进 su_auth」的设备必须先补白名单，
        // 之后才有免 key 的根通道；在这里用 NO_ROOT 拦掉会形成死锁。
        // 只有 RECOVER_FROM_BACKUP（必须 root 读备份）才要求 uid 0，见 [decide]。
        return Preflight(modeSource)
    }

    fun decide(facts: Facts): SelfHealDecision {
        val pre = preflight(facts)
        pre.skip?.let { return it }
        val modeSource = pre.modeSource
        return when (facts.storedKeyState) {
            StoredKeyState.OK -> SelfHealDecision.Proceed(
                action = SelfHealAction.ENSURE_WHITELIST_AND_BACKUP,
                keySource = KeySource.STORE,
                modeSource = modeSource,
                installEnvironmentIfMissing = false,
            )

            StoredKeyState.ABSENT, StoredKeyState.MALFORMED -> {
                if (facts.rootUid != 0) {
                    return SelfHealDecision.Skip(
                        SelfHealSkip.NO_ROOT,
                        "uid=${facts.rootUid ?: "none"}",
                    )
                }
                when (facts.backup) {
                    BackupState.OK -> SelfHealDecision.Proceed(
                        action = SelfHealAction.RECOVER_FROM_BACKUP,
                        keySource = KeySource.BACKUP,
                        modeSource = modeSource,
                        installEnvironmentIfMissing = true,
                        replaceMalformedKey = facts.storedKeyState == StoredKeyState.MALFORMED,
                    )

                    BackupState.ABSENT -> SelfHealDecision.Skip(SelfHealSkip.NO_KEY)
                    BackupState.UNREADABLE -> SelfHealDecision.Skip(SelfHealSkip.BACKUP_UNREADABLE)
                    BackupState.INVALID -> SelfHealDecision.Skip(SelfHealSkip.BACKUP_INVALID)
                }
            }
        }
    }
}

/**
 * 失败退避阶梯：第一次失败后 15 分钟，其后 1h / 6h / 24h（封顶）。
 *
 * <p>「每次冷启动最多一次」由进程内标记保证；跨冷启动的重复尝试由本阶梯限频，
 * 成功一次就把计数清零（[UltraSelfHealBackoff] 只算数，持久化在调用方）。
 */
object UltraSelfHealBackoff {

    private const val MINUTE = 60_000L
    private val LADDER = longArrayOf(15 * MINUTE, 60 * MINUTE, 6 * 60 * MINUTE, 24 * 60 * MINUTE)

    fun delayMillis(failureCount: Int): Long {
        if (failureCount <= 0) return 0L
        val index = (failureCount - 1).coerceAtMost(LADDER.size - 1)
        return LADDER[index]
    }

    /** 限频截止时刻；从未失败（或计数为 0）返回 0（不禁）。 */
    fun limitedUntilMillis(lastFailureAtMillis: Long, failureCount: Int): Long {
        if (lastFailureAtMillis <= 0L || failureCount <= 0) return 0L
        return lastFailureAtMillis + delayMillis(failureCount)
    }

    fun isAllowed(nowMillis: Long, lastFailureAtMillis: Long, failureCount: Int): Boolean =
        nowMillis >= limitedUntilMillis(lastFailureAtMillis, failureCount)
}

/**
 * 「Lite 正在运行」的**免 key 判据**（纯函数）。
 *
 * <p>重装后 `ModeRuntime` 大概率停在未判定（[dev.maplesu.manager.workmode.detect.DetectionRules]
 * 的 R6 需要 Ultra key、R5 需要 Lite 凭据、R8 需要 root 证据）。这里用三件事组合出可解释的
 * 强证据，且**必须**先有一条免 key 的根通道：
 *
 *  1. `su` 通道可用（uid 0）——Ultra 的 `module_su_redirect` 只可能来自 Ultra 内核模块，
 *     这是「Ultra 环境在跑」的直接证据；
 *  2. 控制面 `modes.json` 的 `current == "A"`（root 可读，卸载 App 不会清掉）；
 *  3. `modes/A` 目录存在，或 Ultra 环境文件标记存在（环境装过）。
 *
 * <p>明确不足以判 LITE 的信号：bridge 能加载（所有构建都有）、`UltraEnvironmentProbe`
 * 缓存（无 key 时恒为 NoKey/Unknown）。`current == "B"` 时**永不**判 LITE。
 */
object UltraLiteEvidence {

    data class Signals(
        val suRootUid: Int? = null,
        /** `/data/adb/maplesu-workmode/control/modes.json` 里的 current（"A"/"B"/其它/null）。 */
        val modeFileCurrent: String? = null,
        /** `/data/adb/maplesu-workmode/modes/A` 是否存在。 */
        val modeADirectoryPresent: Boolean? = null,
        /** Ultra 环境文件标记（su allowlist / 环境版本文件）是否存在。 */
        val ultraEnvironmentMarker: Boolean? = null,
    )

    data class Result(val strong: Boolean, val reasons: List<String>) {
        companion object {
            val NONE = Result(false, listOf("NO_SIGNALS"))
        }
    }

    fun evaluate(signals: Signals): Result {
        val reasons = mutableListOf<String>()
        if (signals.suRootUid != 0) {
            reasons += "NO_KEYLESS_ROOT"
            return Result(false, reasons)
        }
        reasons += "SU_ROOT"
        val current = signals.modeFileCurrent?.trim()?.uppercase()
        when (current) {
            "B" -> {
                reasons += "MODE_FILE_B"
                return Result(false, reasons)
            }

            "A" -> {
                reasons += "MODE_FILE_A"
                return Result(true, reasons)
            }

            null -> Unit
            else -> reasons += "MODE_FILE_OTHER:$current"
        }
        if (signals.modeADirectoryPresent == true) {
            reasons += "MODE_A_DIR"
            return Result(true, reasons)
        }
        if (signals.ultraEnvironmentMarker == true) {
            reasons += "ULTRA_ENV_FILES"
            return Result(true, reasons)
        }
        return Result(false, reasons)
    }

    /**
     * 免 key 判据的只读取证脚本（单次 su 调用，全部只读）。标记行用于解析，避免被
     * `cat` 内容里的文字干扰。
     */
    val SIGNAL_SCRIPT: String = """
        echo M_MODE_FILE
        cat /data/adb/maplesu-workmode/control/modes.json 2>/dev/null | head -c 4096
        echo
        echo M_MODE_A_DIR
        if [ -d /data/adb/maplesu-workmode/modes/A ]; then echo YES; else echo NO; fi
        echo M_ULTRA_ENV
        ${dev.maplesu.manager.compat.StoredInputs.ENVIRONMENT_INPUT_TEST}
        echo M_END
    """.trimIndent()

    /** 解析 [SIGNAL_SCRIPT] 的输出（纯函数；marker 之外的任何文本都不参与判定）。 */
    fun parseSignals(output: String?, suRootUid: Int?): Signals {
        if (output.isNullOrBlank()) return Signals(suRootUid = suRootUid)
        val sections = mutableMapOf<String, String>()
        var current: String? = null
        val buffer = StringBuilder()
        for (line in output.lineSequence()) {
            val marker = line.trim()
            if (marker == "M_MODE_FILE" || marker == "M_MODE_A_DIR" || marker == "M_ULTRA_ENV" || marker == "M_END") {
                current?.let { sections[it] = buffer.toString().trim() }
                buffer.setLength(0)
                current = marker
                continue
            }
            if (current != null) buffer.append(line).append('\n')
        }
        val modeFile = sections["M_MODE_FILE"].orEmpty()
        val currentMode = CURRENT_FIELD.find(modeFile)?.groupValues?.getOrNull(1)
        val modeADir = when (sections["M_MODE_A_DIR"].orEmpty().trim().uppercase()) {
            "YES" -> true
            "NO" -> false
            else -> null
        }
        val ultraEnv = when (sections["M_ULTRA_ENV"].orEmpty().trim().uppercase()) {
            "YES" -> true
            "NO" -> false
            else -> null
        }
        return Signals(
            suRootUid = suRootUid,
            modeFileCurrent = currentMode,
            modeADirectoryPresent = modeADir,
            ultraEnvironmentMarker = ultraEnv,
        )
    }

    /** 只取第一个 `"current":"A|B|NONE"`（`migration.current` 在它后面，不会先命中）。 */
    private val CURRENT_FIELD = Regex(""""current"\s*:\s*"([^"]{1,16})"""")
}
