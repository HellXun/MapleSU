package dev.maplesu.manager.runtime

import android.content.Context

/**
 * One trusted runtime state for the Ultra-only build.
 *
 * <p>Readiness means "this device has a live Ultra kernel runtime credential".
 * The former legacy credentials are
 * both gone from this build; there is no second transport to fall back to, so an
 * absent credential is reported as a clear not-ready state rather than a
 * permanent "installed" flag.
 */
object TrustedRuntime {
    enum class Origin { NONE, ULTRA }

    data class State(
        val origin: Origin,
        val ready: Boolean,
        val detail: String? = null,
    )

    fun state(context: Context): State {
        val application = context.applicationContext
        return if (runCatching { MapleSuRuntime.isReady(application) }.getOrDefault(false)) {
            State(Origin.ULTRA, true)
        } else {
            State(Origin.NONE, false, detail = "ULTRA_NOT_READY")
        }
    }

    fun ready(context: Context): Boolean = state(context).ready

    fun origin(context: Context): Origin = state(context).origin
}
