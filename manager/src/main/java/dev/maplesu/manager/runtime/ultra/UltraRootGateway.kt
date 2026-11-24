// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.runtime.ultra

import android.content.Context
import dev.maplesu.manager.runtime.LiteKernelRootExecutor
import dev.maplesu.manager.runtime.RootExecResult

/**
 * Ultra-only 的根通道门面。
 *
 * 本构建已移除旧 provider（Hook/L3 gate），此前用于引导 Ultra Boot SDK 的
 * `HookRootExecutor` 由内核凭据通道取代：Ultra 内核安装后，`DirectRoot` 以
 * `libmaplesu_runtime.so` + 凭据执行一次性 root 命令，语义与调用点一致
 * （“现在有没有 root 可以启动/查询 gateway”）。
 *
 * 保留这一层的原因：Boot SDK 的引导、校验与恢复都需要同一个“根可用”判据，
 * 集中在这里可以避免调用点各自猜测 root 来源。
 */
internal object UltraRootGateway {
    fun available(context: Context): Boolean =
        runCatching { LiteKernelRootExecutor(context).available() }.getOrDefault(false)

    fun run(context: Context, script: String): RootExecResult =
        LiteKernelRootExecutor(context).run(script)
}
