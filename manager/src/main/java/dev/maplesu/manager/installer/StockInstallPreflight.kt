package dev.maplesu.manager.installer

import android.content.Context
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.service.ModeSwitchRootSession
import dev.maplesu.manager.runtime.RootExec

/** Called only after choosing an installation source, on an IO thread. */
internal object StockInstallPreflight {
    fun discoverTarget(context: Context, rootTarget: InstallTargetMode? = null): InstallerBootTarget {
        if (ModeRuntime.active == null && !hasUltraIdentity(context)) {
            return InstallerRootSession.open(context).use { it.discoverBootTarget() }
        }
        val session = ModeSwitchRootSession(context)
        val ready = session.ensureReady()
        check(ready.ok) { ready.code }
        val result = session.execute("/system/bin/getprop ro.boot.slot_suffix")
        check(result.ok) { result.detail.ifBlank { "INSTALLER_ROOT_UNAVAILABLE" } }
        val slot = InstallerSnapshotParser.slot(result.output.trim())
        val nodes = session.execute(InstallerBootTarget.discoveryCommand(slot))
        check(nodes.ok) { "INSTALLER_BLOCK_DEVICE_REQUIRED" }
        return InstallerBootTarget.fromOutput(slot, nodes.output)
    }

    private fun hasUltraIdentity(context: Context): Boolean =
        dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe.usesUltraBackend(context)
}
