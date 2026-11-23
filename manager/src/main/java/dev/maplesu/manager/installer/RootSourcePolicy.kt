package dev.maplesu.manager.installer

import android.content.Context
import com.topjohnwu.superuser.Shell
import dev.maplesu.installer.WorkMode

/** Which root transport a session was opened over (02-switch-contract §5.3). */
internal enum class RootSource {
    /** `RootManagement.openShell` - the MapleSU Lite runtime. */
    LITE_RUNTIME,
    /** Mode B native channel (ksud/sucompat), only after in-service kernel proof. */
    KSU_NATIVE,
    /** Pre-existing explicit `su` grant; never accepted for MODE_SWITCH. */
    EXPLICIT_SU,
}

/** Pluggable Mode B native channel; W11 (KSU backend) installs the real implementation. */
internal fun interface NativeRootChannel {
    fun openShell(context: Context): Shell

    /**
     * Same channel with the global mount-namespace requirement made explicit
     * (`ksud debug su -g` for the KSU channel; [RootManagement] validates the result).
     *
     * Default ignores the flag so existing callers/install paths are unchanged; only the
     * KSU implementation distinguishes it.
     */
    fun openShell(context: Context, globalMnt: Boolean): Shell = openShell(context)
}

internal object InstallerRootSourceRegistry {
    @Volatile private var channel: NativeRootChannel? = null

    /** Registers the Mode B channel; `null` unregisters it (tests/backends). */
    fun install(channel: NativeRootChannel?) {
        this.channel = channel
    }

    fun require(): NativeRootChannel =
        channel ?: throw IllegalStateException("SWITCH_ROOT_UNAVAILABLE:KSU_CHANNEL_UNAVAILABLE")

    /** Peek at the registered channel without throwing; used to degrade instead of refusing. */
    fun channelOrNull(): NativeRootChannel? = channel
}

/**
 * Pure source-selection policy (02-switch-contract §5.3 table). `null` mode is the undetermined
 * state: no shell is opened at all.
 *
 * <p>2026-11-01 (user directive "user tapped it, so flash it"): `ksuUsable` — the in-service kernel
 * proof verdict — is **evidence only**. It no longer refuses a KSU-mode session: an unavailable
 * proof just means the KSU channel is unproven, and [InstallerRootSession] degrades to another
 * root transport instead of refusing the write.
 */
internal object RootSourcePolicy {
    @Suppress("UNUSED_PARAMETER")
    fun sourceFor(mode: WorkMode?, ksuUsable: Boolean): RootSource =
        if (mode == null) throw IllegalStateException("SWITCH_ROOT_UNAVAILABLE:STATE_UNDETERMINED")
        else RootSource.LITE_RUNTIME

    /** The existing boot-install path keeps its explicit-su fallback; mode switch must not. */
    fun allowExplicitSuFallback(scope: InstallerModeGate.WriteScope): Boolean =
        scope == InstallerModeGate.WriteScope.LITE_INSTALL
}
