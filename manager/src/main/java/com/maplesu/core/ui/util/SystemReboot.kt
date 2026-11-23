package com.maplesu.core.ui.util

import android.content.Context
import android.util.Log
import dev.maplesu.manager.runtime.RootExec
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.ultra.UltraSdkCredentials

/** Explicit user action. Call off the UI thread; success means the backend accepted the request. */
internal fun requestSystemReboot(context: Context, reason: String): Boolean {
    if (reason !in RebootDispatcher.REASONS) return false
    return try {
        val app = context.applicationContext
        // Ultra-only：重启只走 Ultra 通道（SDK zygote 重启 / Ultra 根命令）。
        // 越狱 Hook 与 Normal(KSU) 两条旧通道已从本构建移除。
        val backend = RebootDispatcher.backend(
            ultraRunning = UltraEnvironmentProbe.isRunning(),
            verifiedUltraIdentity = UltraEnvironmentProbe.usesUltraBackend(app),
        )
        val executed = RebootDispatcher(
            backend = backend,
            restartUltra = {
                UltraSdkCredentials.client(app).restartZygote64().succeeded
            },
            runRoot = { runRebootRootCommand(app, it) },
        ).execute(reason)
        if (!executed) {
            // Never fail silently: this line is what was missing when a soft
            // reboot "did nothing" and logcat had no trace.
            Log.w("MapleSUReboot", "rejected backend=" + backend + " reason=" + reason)
        }
        executed
    } catch (error: Exception) {
        Log.w("MapleSUReboot", "Reboot request failed", error)
        false
    }
}

/** Shared with the read-only device regression; this function never retries the submitted script. */
internal fun runRebootRootCommand(context: Context, command: String): Boolean {
    return RootExec.execute(context, command).ok
}
