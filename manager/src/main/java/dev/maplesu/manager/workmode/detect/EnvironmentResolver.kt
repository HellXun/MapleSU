// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.workmode.detect

import dev.maplesu.manager.workmode.WorkMode

/**
 * T14：**降级探测**的端口（用户给定顺序；每一步都是不启动 root shell 的低成本检查）。
 *
 * 与 [ModeDetector] 的三阶段探测不同，这里只回答"这台机器的环境是什么"，不写控制面、
 * 不建 root 会话、不跑 KSU 部署。任何一项读不到都必须如实返回 unknown（[ksuNormal] 返回
 * null），**禁止**把"读不到"折叠成 false。
 */
interface EnvironmentProbes {

    /**
     * a) su 能否**真的**给出 uid 0（§5 C6）。
     *
     * 三态：[SuReadiness.USABLE]（真子进程 exit 0 且 stdout uid=0）/ [SuReadiness.NOT_USABLE]
     * （真跑过但 exit!=0）/ [SuReadiness.UNKNOWN]（起不来/超时/读不到）。UNKNOWN **不得**
     * 被调用方折叠成 false，更不得落成"未安装"（用户要求：UNKNOWN 保留上次状态）。
     *
     * 默认实现只给旧测试 fake 用（它们只实现了 [suPresent]）；生产实现见
     * `probes/SuReadinessProbeAdapter.kt`，一律走真实子进程。
     */
    fun suReadiness(): SuReadiness = if (suPresent()) SuReadiness.USABLE else SuReadiness.NOT_USABLE

    /**
     * 旧布尔端口（仅为既有安装页 gate 保留）：`suReadiness() == USABLE`。
     *
     * 绝不能把"文件存在"当实现；生产实现已改为真实探针（默认预算 ≤1500ms）。
     */
    fun suPresent(): Boolean

    /** Current key/boot or Hook evidence only; no credential recovery or ordinary SU probe. */
    fun currentUltraFingerprint(): String? = null

    /** b) Ultra recovery fallback after the current channel did not confirm its identity. */
    fun ultraFingerprint(): String?

    /**
     * c) 是否 MapleSU_Normal（KSU）：JNI `KsuBackends.natives()` 或 `ksud debug info`。
     *
     * 允许在"模式尚未判定为 KSU"时调用（解除互锁的唯一例外）；null = 本次读不到（unknown）。
     * **不得**为了这个调用去放宽 `RootManagement` 的路由语义。
     */
    fun ksuNormal(): Boolean?

    /**
     * 保留端口：慢路径（起 root shell）才允许调用。
     *
     * 当前策略**恒不调用**它（用户要求：8 秒内拿不到结论就如实 UNKNOWN，不靠 root 硬猜）。
     * 保留在端口上的目的，是让"降级链绝不暗中起 root"可被计数探针证明（计数恒为 0）。
     */
    fun rootShellAvailable(): Boolean? = null
}

/** 判定终态（每一种都有具名 code；用户明确要求不得静默成功）。 */
enum class EnvironmentVerdict(val code: String) {
    /** 同 boot、未脏且自身模式身份仍有效，静默继承。 */
    INHERITED("ENV_INHERITED"),

    /** Ultra 与 Normal 自身身份均已排除 → 未安装。 */
    NOT_INSTALLED("ENV_NOT_INSTALLED"),

    /** 快速核对到 Ultra 环境 → MapleSU_Ultra（LITE）。 */
    ULTRA("ENV_ULTRA"),

    /** 拿不到结论（超时/探针异常/KSU 探针不可用）。**绝不**写成未安装，也绝不覆盖上次状态。 */
    UNKNOWN("ENV_UNKNOWN_TIMEOUT"),
}

/** 具名细节码（写进日志/证据；UI 不得硬编码字符串）。 */
object EnvironmentCheckDetail {
    const val INHERITED_SAME_BOOT = "INHERITED_SAME_BOOT"
    const val SU_ABSENT_NO_ULTRA = "SU_ABSENT_NO_ULTRA"
    const val ULTRA_FINGERPRINT = "ULTRA_FINGERPRINT"
    const val ULTRA_PROBE_NEGATIVE = "ULTRA_PROBE_NEGATIVE"
    const val ULTRA_PROBE_UNAVAILABLE = "ULTRA_PROBE_UNAVAILABLE"
    /** 真探针起不来/超时 → UNKNOWN；保留上次状态，不落盘。 */
    const val SU_PROBE_UNKNOWN = "SU_PROBE_UNKNOWN"
    const val DEADLINE_EXCEEDED = "PROBE_DEADLINE_EXCEEDED"
    const val PROBE_STILL_RUNNING = "PROBE_STILL_RUNNING"
    const val PROBE_FAILED = "PROBE_FAILED"
}

/**
 * 一次环境检查的结果。
 *
 * [snapshot] 是"当前有效状态"：继承时就是上次那份（未改写磁盘）；UNKNOWN 时同样是上次那份
 * （保留，不覆盖）；判定成功时是刚落盘的新快照。
 */
data class EnvironmentCheckOutcome(
    val verdict: EnvironmentVerdict,
    val mode: WorkMode?,
    val snapshot: EnvironmentSnapshot?,
    /** null = SU 核对后继承；非空 = 本次重新识别模式的具名触发原因。 */
    val trigger: EnvironmentCheckTrigger?,
    val detail: String,
    val bootId: String,
    val elapsedMillis: Long,
    /** 实际调用过的探针名（顺序敏感，用于断言短路）：ultraCurrent / su / ultra / ksu。 */
    val probes: List<String> = emptyList(),
) {
    val code: String get() = verdict.code

    /** true = 本次没有任何触发探测的疑点，直接沿用了上次状态。 */
    val inherited: Boolean get() = verdict == EnvironmentVerdict.INHERITED

    /** 是否给出了**终态**（未安装 / Ultra / KSU）；UNKNOWN 不是终态。 */
    val terminal: Boolean get() = verdict != EnvironmentVerdict.UNKNOWN
}

/**
 * 降级探测的唯一实现（纯函数式：只有 [EnvironmentProbes] + [EnvironmentStateStore] 两个端口）。
 *
 * 同 boot、未脏的已安装记录先核对自身身份，再决定是否继承。
 * Normal 需要内核认主；Ultra 需要当前自身通道。普通 SU 仅报告能力，既不能
 * 单独延续安装身份，也不能因失败就跳过模式探针。命中当前身份即短路，
 * 两种身份均明确阴性才写未安装；读取不确定时保留上次状态且不授予新模式。
 * 只使用既有快速端口，不调用 rootShellAvailable。
 */
class EnvironmentResolver(
    private val probes: EnvironmentProbes,
    private val store: EnvironmentStateStore,
    private val clock: Clock = WallClock,
) {

    /**
     * @param mayPersist 落盘许可。外层执行器在**超时窗口过后**传入 false：被放弃的探测
     *   即使之后自己跑完，也**不得**覆盖上次状态（用户要求：超时保留上次状态）。
     */
    fun resolve(currentBootId: String, mayPersist: () -> Boolean = { true }): EnvironmentCheckOutcome {
        val startedAt = clock.nowMillis()
        val previous = store.snapshot()
        val inheritanceTrigger = EnvironmentInheritancePolicy.triggerFor(previous, currentBootId)
        var trigger = inheritanceTrigger ?: EnvironmentCheckTrigger.SU_RECHECK
        val calls = mutableListOf<String>()
        // Identify the current channel before ordinary SU or credential recovery can
        // occupy the SDK. Mode evidence alone does not grant an untested SU capability.
        calls += PROBE_CURRENT_ULTRA
        val currentUltra = runCatching { probes.currentUltraFingerprint() }.getOrNull()
        if (currentUltra != null) {
            return persist(
                verdict = EnvironmentVerdict.ULTRA,
                mode = WorkMode.LITE,
                su = false,
                ultra = currentUltra,
                trigger = trigger,
                detail = EnvironmentCheckDetail.ULTRA_FINGERPRINT,
                mayPersist = mayPersist,
                bootId = currentBootId,
                startedAt = startedAt,
                calls = calls,
            )
        }
        // 快速核对读不**到**（异常/被中断）≠ 读到了"没有"：一律如实 UNKNOWN，绝不折叠成 false。
        calls += PROBE_SU
        val readiness: SuReadiness
        var ultra: String? = null
        var ultraChecked = false
        try {
            // Ordinary SU failure does not invalidate independent kernel/key evidence.
            readiness = runCatching { probes.suReadiness() }.getOrDefault(SuReadiness.UNKNOWN)
            if (inheritanceTrigger == null && previous?.mode == WorkMode.LITE) {
                calls += PROBE_ULTRA
                ultra = probes.ultraFingerprint()
                ultraChecked = true
            }
            if (inheritanceTrigger == null && readiness != SuReadiness.NOT_USABLE &&
                (previous?.mode == WorkMode.LITE && ultra != null)) {
                return EnvironmentCheckOutcome(
                    verdict = EnvironmentVerdict.INHERITED,
                    mode = previous.mode,
                    snapshot = previous,
                    trigger = null,
                    detail = EnvironmentCheckDetail.INHERITED_SAME_BOOT,
                    bootId = currentBootId,
                    elapsedMillis = clock.nowMillis() - startedAt,
                    probes = calls,
                )
            }
            if (inheritanceTrigger == null) trigger = EnvironmentCheckTrigger.SU_MISSING
            if (!ultraChecked) {
                calls += PROBE_ULTRA
                ultra = probes.ultraFingerprint()
                // 读到了"没有"就是明确阴性：null 返回值即终态证据，异常才走 UNKNOWN。
                ultraChecked = true
            }
        } catch (error: Throwable) {
            return unknown(
                previous = previous,
                trigger = trigger,
                bootId = currentBootId,
                startedAt = startedAt,
                calls = calls,
                detail = EnvironmentCheckDetail.PROBE_FAILED + ":" +
                    (error.message ?: error.javaClass.simpleName),
            )
        }
        val su = readiness == SuReadiness.USABLE
        // b) 快速读有 Ultra 环境 → Ultra（LITE）：短路，不再探测 KSU。
        if (ultra != null) {
            return persist(
                verdict = EnvironmentVerdict.ULTRA,
                mode = WorkMode.LITE,
                su = su,
                ultra = ultra,
                trigger = trigger,
                detail = EnvironmentCheckDetail.ULTRA_FINGERPRINT,
                mayPersist = mayPersist,
                bootId = currentBootId,
                startedAt = startedAt,
                calls = calls,
            )
        }

        // c) 没有 Ultra 身份：本构建只发布 Ultra，普通 su 的成功/超时都不是模式证据。
        //    探针读不到仍走 UNKNOWN，只有明确的阴性才覆盖旧 Ultra 缓存。
        if (ultraChecked) {
            return persist(
                verdict = EnvironmentVerdict.NOT_INSTALLED,
                mode = null,
                su = su,
                ultra = null,
                trigger = trigger,
                detail = (if (readiness == SuReadiness.NOT_USABLE) EnvironmentCheckDetail.SU_ABSENT_NO_ULTRA + ":" else "") +
                    EnvironmentCheckDetail.ULTRA_PROBE_NEGATIVE,
                mayPersist = mayPersist,
                bootId = currentBootId,
                startedAt = startedAt,
                calls = calls,
            )
        }
        // 读不到 / 未确认：**如实 UNKNOWN**，且不写磁盘（保留上次状态）。
        return unknown(
            previous = previous,
            trigger = trigger,
            bootId = currentBootId,
            startedAt = startedAt,
            calls = calls,
            detail = (if (readiness == SuReadiness.UNKNOWN) {
                EnvironmentCheckDetail.SU_PROBE_UNKNOWN + ":"
            } else "") + EnvironmentCheckDetail.ULTRA_PROBE_UNAVAILABLE,
        )
    }

    private fun unknown(
        previous: EnvironmentSnapshot?,
        trigger: EnvironmentCheckTrigger,
        bootId: String,
        startedAt: Long,
        calls: List<String>,
        detail: String,
    ): EnvironmentCheckOutcome = EnvironmentCheckOutcome(
        verdict = EnvironmentVerdict.UNKNOWN,
        mode = previous?.mode,
        snapshot = previous,
        trigger = trigger,
        detail = detail,
        bootId = bootId,
        elapsedMillis = clock.nowMillis() - startedAt,
        probes = calls,
    )

    private fun persist(
        verdict: EnvironmentVerdict,
        mode: WorkMode?,
        su: Boolean,
        ultra: String?,
        trigger: EnvironmentCheckTrigger,
        detail: String,
        bootId: String,
        startedAt: Long,
        calls: List<String>,
        mayPersist: () -> Boolean,
    ): EnvironmentCheckOutcome {
        val snapshot = EnvironmentSnapshot(
            mode = mode,
            suAvailable = su,
            ultraEnvFingerprint = ultra,
            bootId = bootId,
            checkedAtMillis = clock.nowMillis(),
            dirty = false,
        )
        // 只有给出**终态**才落盘；UNKNOWN 路径不经过这里（保留上次状态）。
        // 预算用尽后才跑完的探测同样不落盘 —— 否则"超时保留上次状态"会被迟到的结果覆盖。
        if (mayPersist()) store.save(snapshot)
        return EnvironmentCheckOutcome(
            verdict = verdict,
            mode = mode,
            snapshot = snapshot,
            trigger = trigger,
            detail = detail,
            bootId = bootId,
            elapsedMillis = clock.nowMillis() - startedAt,
            probes = calls,
        )
    }

    private companion object {
        const val PROBE_CURRENT_ULTRA = "ultraCurrent"
        const val PROBE_SU = "su"
        const val PROBE_ULTRA = "ultra"
        const val PROBE_KSU = "ksu"
    }
}
