package com.maplesu.core.ui.util

import dev.maplesu.manager.workmode.WorkMode

/**
 * ksud daemon 二进制的**模式感知选择**（纯 Kotlin，主机可测）。
 *
 * KSU 构建产物约定：
 * - A（Lite）的安装路径依赖 `nativeLibraryDir/libksud.so`（**不改** `getKsuDaemonPath()`）；
 * - B（KSU 分支）的 daemon 是 `nativeLibraryDir/libksud_ksu.so`（我们的构建产物）。
 *
 * B 下若该文件不存在，返回 null（由调用方显式失败/禁用 UI），**不回退 Lite daemon**。
 */
object KsuDaemonPolicy {
    const val LITE_DAEMON = "libksud.so"
    const val KSU_DAEMON = "libksud_ksu.so"

    /**
     * **部署后**的 ksud（`KsuBootstrap`/`ksud install --data-path /data/adb` 的产物）。
     *
     * 内核侧 rc 固定 `exec /data/adb/ksud`（`ksud_integration.c:40-49`、`sucompat.c:198`），
     * 所以 Normal 模式下所有 `ksud <subcommand>` 都必须指向它 —— APK 里**没有**
     * `libksud.so`（那是 Ultra 的运行时），指向它只会让每条命令都失败。
     */
    const val DEPLOYED_KSUD = "/data/adb/ksud"

    /** `ksud install` 的 work-dir 形态（部署产物之一；主路径不可用时才用）。 */
    const val WORKDIR_KSUD = "/data/adb/ksu/bin/ksud"

    fun daemonName(mode: WorkMode?): String = when (mode) {
        WorkMode.LITE, null -> LITE_DAEMON
    }

    /**
     * KSU 模式真正要执行的 ksud 路径（纯函数，主机可测）。
     *
     * 不在本进程 stat 部署路径：`/data/adb` 是 0700 root，app uid 连 `stat` 都不行，
     * 用本地 `File()` 判定只会把"已部署"误判成"不存在"。
     * [workDirFallback] = 已知主路径不可执行（由 root shell 判定）时的形态。
     */
    fun ksuDaemonPath(workDirFallback: Boolean = false): String =
        if (workDirFallback) WORKDIR_KSUD else DEPLOYED_KSUD

    /**
     * 是否指向"APK 内 launcher"（`libksud_ksu.so`）。用于诊断断言：
     * Normal 的**命令**不得走 launcher，只有起 root shell 时才用 launcher。
     */
    fun isLauncherPath(path: String): Boolean =
        // 主机测试/Windows 路径可能用反斜杠；部署路径永远是 '/'，这里统一后再比较。
        path.replace('\\', '/').endsWith("/$KSU_DAEMON")

    fun daemonPath(nativeLibraryDir: String, mode: WorkMode?): String {
        val dir = nativeLibraryDir.trimEnd('/')
        return "$dir/${daemonName(mode)}"
    }

    /**
     * 实际可执行的 daemon 路径：
     * - KSU：`libksud_ksu.so` 存在则用，否则 null（不存在的二进制不要盲跑）；
     * - LITE/未判定：沿用 [legacyLitePath]（即现有 `getKsuDaemonPath()` 的结果，行为不变）。
     */
    fun resolve(
        mode: WorkMode?,
        nativeLibraryDir: String,
        legacyLitePath: String,
        exists: (String) -> Boolean,
    ): String? = when (mode) {
        WorkMode.LITE, null -> legacyLitePath
    }
}
