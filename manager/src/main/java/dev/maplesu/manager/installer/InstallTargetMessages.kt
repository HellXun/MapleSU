// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.installer

import android.content.res.Resources
import android.util.Log
import com.maplesu.core.R

/**
 * 分派结论的用户可读文案（安装页 + 修补入口**共用**，避免两处各写一套字符串映射）。
 *
 * 放在本包而不是 UI 包：`com.maplesu.core.ui.util.installBoot`（修补入口）也要用同一份文案。
 * 纯逻辑仍在 `InstallTargetDispatch`；本文件只做 `blocker → string res` 的 1:1 翻译。
 */

/** 仅做 UI 文案投影：判据不变，机器码和原始 detail 只写日志。 */
fun installTargetBlockerMessage(
    resources: Resources,
    blocked: InstallTargetDecision.Blocked,
): String {
    val text = when (blocked.blocker) {
        InstallTargetBlocker.PARTITION_FIXED ->
            resources.getString(
                R.string.install_target_blocker_partition_fixed,
                blocked.target.productName,
            )

        InstallTargetBlocker.PARTITION_UNSUPPORTED ->
            resources.getString(
                R.string.install_target_blocker_partition_unsupported,
                InstallPartition.ALLOWED.joinToString(", ") { it.wire },
            )

        InstallTargetBlocker.KMI_UNPARSEABLE ->
            resources.getString(R.string.install_target_blocker_kmi_unparseable)

        InstallTargetBlocker.KMI_NOT_IN_MATRIX ->
            resources.getString(R.string.install_target_blocker_kmi_not_in_matrix)

        InstallTargetBlocker.KMI_ARTIFACT_MISSING ->
            resources.getString(R.string.install_target_blocker_kmi_artifact_missing)

        InstallTargetBlocker.KMI_KERNEL_PLATFORM_MISMATCH ->
            resources.getString(R.string.install_target_blocker_kmi_mismatch)
    }
    Log.w("InstallTarget", "${blocked.code}: ${blocked.kmi.orEmpty()} ${blocked.detail}")
    return text
}

/**
 * 安装确认 / 事务记录里的「目标：…」行（用户点「下一步」后在刷写日志第一条就能看到）。
 */
fun installTargetBanner(
    resources: Resources,
    ready: InstallTargetDecision.Ready,
): String {
    val current = resources.getString(R.string.install_target_mode_current, ready.target.productName)
    return "$current · ${resources.getString(R.string.install_target_mode_ultra_summary)}"
}
