package com.maplesu.core.ui.util

internal enum class RebootBackend { ULTRA, DIRECT }

/** One selected reboot route; a failed mutation is never retried through another backend. */
internal class RebootDispatcher(
    private val backend: RebootBackend,
    private val restartUltra: () -> Boolean,
    private val runRoot: (String) -> Boolean,
) {
    fun execute(reason: String): Boolean {
        if (reason !in REASONS) return false
        if (reason == "soft_reboot") return when (backend) {
            RebootBackend.ULTRA -> restartUltra()
            RebootBackend.DIRECT ->
                runRoot("/system/bin/setprop ctl.restart zygote64 || " +
                    "/system/bin/setprop ctl.restart zygote")
        }
        val command = buildString {
            if (reason == "recovery") append("/system/bin/input keyevent 26 >/dev/null 2>&1; ")
            append("/system/bin/svc power reboot")
            if (reason.isNotEmpty()) append(' ').append(reason)
            append(" || ")
            append("/system/bin/reboot")
            if (reason.isNotEmpty()) append(' ').append(reason)
        }
        return runRoot(command)
    }

    companion object {
        val REASONS = setOf("", "userspace", "soft_reboot", "recovery", "bootloader", "download", "edl")
        const val RESTART_ZYGOTE = "/system/bin/setprop ctl.restart zygote"
        /** §6.5: closes the kernel window without a full boot. The gate child's domain
         *  cannot write `powerctl`, so each step degrades; killing zygote makes init
         *  restart the framework (a userspace reboot) even when property writes are
         *  denied. The last step guarantees a visible reboot if signalling is denied too. */
        /** §6.5 + SK reference: a soft reboot restarts the framework only; the kernel and
         *  the L3 gate stay alive and `boot_id` does not change. On arm64 the init service
         *  is `zygote64`, not `zygote` (the latter answers HANDLE_CONTROL_MESSAGE). */
        const val SOFT_REBOOT =
            "/system/bin/setprop ctl.restart zygote64 || " +
                "/system/bin/setprop ctl.restart zygote || " +
                "/system/bin/setprop sys.powerctl reboot,soft_reboot || " +
                "/system/bin/kill -9 $(/system/bin/pidof zygote64) " +
                "$(/system/bin/pidof zygote) 2>/dev/null || " +
                "/system/bin/reboot soft_reboot || /system/bin/reboot"

        // A verified Ultra identity outranks a cached Running observation: only the
        // identity check proves which backend actually answers.
        fun backend(
            ultraRunning: Boolean,
            verifiedUltraIdentity: Boolean,
        ): RebootBackend = when {
            verifiedUltraIdentity -> RebootBackend.ULTRA
            ultraRunning -> RebootBackend.ULTRA
            else -> RebootBackend.DIRECT
        }

        fun showMenu(installed: Boolean, confirming: Boolean): Boolean = installed && !confirming
    }
}
