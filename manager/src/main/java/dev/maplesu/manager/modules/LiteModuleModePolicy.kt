// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.modules

import dev.maplesu.manager.workmode.WorkMode

/**
 * Lite（Ultra）脚本模块 late-boot 的**模式门**（纯函数，主机可测）。
 *
 * 背景（审计 §D0「Normal 下 Lite 模块 boot service 无模式门」）：
 * `LiteModuleBootService`/`LiteModuleBootReceiver` 以前只判断 `MapleSuRuntime` 与
 * `BOOT_COMPLETED`，于是 Normal（KSU）下也可能被拉起，和 ksud 的
 * `post-fs-data`/`service` 双跑两套模块系统。
 *
 * 规则只有一条：**只有 LITE 允许执行**。KSU 与未判定一律拒绝（fail-closed，
 * 绝不"默认 LITE"）。
 */
object LiteModuleModePolicy {

    fun allowsRun(mode: WorkMode?): Boolean = mode == WorkMode.LITE

    /** 拒绝原因（日志/诊断用；允许时返回 null）。 */
    fun rejectionReason(mode: WorkMode?): String? = when (mode) {
        WorkMode.LITE -> null
        null -> "MODE_UNRESOLVED: lite module worker blocked"
    }
}
