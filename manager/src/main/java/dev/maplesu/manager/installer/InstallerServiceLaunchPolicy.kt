package dev.maplesu.manager.installer

import dev.maplesu.manager.workmode.WorkMode

/** Ultra-only：越狱 Hook 安装路线已移除，只剩内核/环境 root 与 shell 两条。 */
internal enum class InstallerServiceLaunchRoute { ROOT_EXEC, SHELL }

internal fun installerServiceLaunchRoute(
    active: WorkMode?,
    target: InstallTargetMode?,
    verifiedUltraIdentity: Boolean = false,
): InstallerServiceLaunchRoute = when {
    // Preserve the persistent Ultra identity and its own root recovery path.
    verifiedUltraIdentity -> InstallerServiceLaunchRoute.ROOT_EXEC
    target == InstallTargetMode.ULTRA -> InstallerServiceLaunchRoute.ROOT_EXEC
    active == WorkMode.LITE -> InstallerServiceLaunchRoute.ROOT_EXEC
    else -> InstallerServiceLaunchRoute.SHELL
}
