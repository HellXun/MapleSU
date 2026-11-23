// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.workmode.detect

import dev.maplesu.manager.workmode.WorkMode

/**
 * T14：环境检查结论 → **启动判定的前置覆盖**（解除互锁的唯一出口，纯映射、主机可测）。
 *
 * 为什么需要覆盖：实测设备上"判不出模式 ⇒ 不装 KSU 通道 ⇒ 拿不到 root ⇒ 判不出模式"。
 * 三阶段探测器需要 root 取证才能给出 `MODE_B_ACTIVE`，而 `MODE_B_KERNEL_ONLY`
 * （= KSU 内核在、用户态未验证）只需要"这是 MapleSU_Normal"这一条事实，
 * `activeMode` 就是 [WorkMode.KSU] —— 先激活、接上 KSU 通道，root 才拿得到。
 *
 * 覆盖只发生在两个**终态**上，其余一律交回既有两阶段判定：
 * - [EnvironmentVerdict.KSU_NORMAL] / 继承的 KSU → `MODE_B_KERNEL_ONLY`（激活 KSU）。
 * - [EnvironmentVerdict.NOT_INSTALLED] / 继承的"未安装" → `NOT_INSTALLED`（无 activeMode，
 *   立即终态，不再跑任何 root 探测）。
 * - **LITE 不覆盖**：Ultra 的激活必须带本 boot 的凭据握手证据（既有 LITE 探测语义零改动）。
 * - [EnvironmentVerdict.ULTRA] / [EnvironmentVerdict.UNKNOWN] → 不覆盖（照常全量判定；
 *   UNKNOWN 尤其不得被写成任何结论）。
 */
object EnvironmentDetectionProjection {

    fun override(
        outcome: EnvironmentCheckOutcome,
        observedAtMillis: Long = System.currentTimeMillis(),
    ): DetectionResult? {
        val state = when (outcome.verdict) {
            EnvironmentVerdict.NOT_INSTALLED -> ModeState.NOT_INSTALLED
            EnvironmentVerdict.INHERITED -> when (outcome.mode) {
                null -> ModeState.NOT_INSTALLED
                WorkMode.LITE -> return null
            }
            EnvironmentVerdict.ULTRA, EnvironmentVerdict.UNKNOWN -> return null
        }
        return DetectionResult(
            state = state,
            group = state.group,
            rule = when (state) {
                ModeState.MODE_B_KERNEL_ONLY -> DetectionRules.Rule.R2_KSU_KERNEL_ONLY
                ModeState.NOT_INSTALLED -> DetectionRules.Rule.R11_FRESH_INSTALL
                else -> DetectionRules.Rule.R12_INDETERMINATE
            },
            bootId = outcome.bootId,
            observedAtMillis = observedAtMillis,
            trace = listOf(
                ProbeTrace(
                    id = TRACE_ID,
                    strength = Strength.S,
                    ok = state != ModeState.NOT_INSTALLED,
                    detail = buildString {
                        append(outcome.code).append(':').append(outcome.detail)
                        append(" inherited=").append(outcome.inherited)
                        append(" probes=").append(outcome.probes.joinToString(","))
                        append(" trigger=").append(outcome.trigger?.code ?: "NONE")
                    },
                    elapsedMillis = outcome.elapsedMillis,
                ),
            ),
            recovery = RecoveryReadiness.NONE,
            rootChannel = null,
        )
    }

    /** 前置环境证据的 trace id（与三阶段探测器的 S1、L2、M1 区分开）。 */
    const val TRACE_ID = "E1"
}
