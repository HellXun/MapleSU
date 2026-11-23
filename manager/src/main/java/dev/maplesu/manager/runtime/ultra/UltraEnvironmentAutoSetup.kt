package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.compat.ultraPreferences

import android.content.Context
import android.os.Looper
import android.util.Log
import dev.maplesu.manager.modules.UltraRootKeyStore
import dev.maplesu.manager.ultrakernel.UltraKernelCredential
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.Hashes
import dev.maplesu.manager.workmode.service.ModeSwitchProgress
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.ModeState
import dev.maplesu.manager.workmode.superuser.LiteImplantCodes
import dev.maplesu.manager.workmode.superuser.LiteImplantQuiesceGate
import dev.maplesu.manager.workmode.ui.WorkModeSwitchSession
import java.io.File

/**
 * The startup recovery path restores a live kernel key before entering this setup.
 * A fresh SDK NotInstalled observation permits one helper installation per request;
 * later foreground requests can retry a failed submission in the same boot.
 * Running is reused. Unknown requires separate evidence of a never-started helper;
 * Unknown/Fault never substitute for evidence of absence.
 */
object UltraEnvironmentAutoSetup {

    private const val TAG = "MapleSUUltraAutoSetup"
    private const val PREFS = "ultra-auto-setup"
    private const val KEY_LAST_BOOT = "lastBootId"

    @Volatile
    private var processBootId: String? = null

    data class Result(val attempted: Boolean, val installed: Boolean, val reason: String)

    /** Startup and foreground requests reconcile the current environment; idempotent. */
    fun ensure(context: Context): Result = ensure(context) { true }

    internal fun ensure(context: Context, current: () -> Boolean): Result {
        if (!current()) return Result(false, false, "SDK_CREDENTIAL_SCOPE_CHANGED")
        val bootId = currentBootId()
        val result = ensureForBoot(context, bootId, current)
        // App-owned evidence survives loss of both the SDK and the no-key su channel.
        if (current()) runCatching {
            context.ultraPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("lastObservedBoot", bootId)
                .putLong("lastObservedAt", System.currentTimeMillis())
                .putString("lastResult", "attempted=${result.attempted} installed=${result.installed} reason=${result.reason}")
                .apply()
        }
        return if (current()) result else result.copy(
            installed = false,
            reason = if (result.reason == "AUTHORIZATION_SYNC_PENDING") result.reason
                else "SDK_CREDENTIAL_SCOPE_CHANGED",
        )
    }

    internal fun diagnostics(context: Context): String {
        val prefs = context.ultraPreferences(PREFS, Context.MODE_PRIVATE)
        return "attemptedBoot=${prefs.getString(KEY_LAST_BOOT, null)}\n" +
            "observedBoot=${prefs.getString("lastObservedBoot", null)}\n" +
            "observedAtMs=${prefs.getLong("lastObservedAt", 0L)}\n" +
            "result=${prefs.getString("lastResult", "NOT_OBSERVED")}\n"
    }

    internal fun ensureForBoot(context: Context, bootId: String?): Result =
        ensureForBoot(context, bootId) { true }

    @Synchronized
    internal fun ensureForBoot(context: Context, bootId: String?, current: () -> Boolean): Result {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ULTRA_AUTO_SETUP_BACKGROUND_THREAD_REQUIRED"
        }
        val appContext = context.applicationContext
        if (!current()) return Result(false, false, "SDK_CREDENTIAL_SCOPE_CHANGED")
        if (!current()) return Result(false, false, "SDK_CREDENTIAL_SCOPE_CHANGED")
        blockedReason(appContext, bootSession = UltraSdkSession.current(appContext) != null)
            ?.let { return Result(false, false, it) }
        if (!current()) return Result(false, false, "SDK_CREDENTIAL_SCOPE_CHANGED")
        val session = UltraSdkSession.current(appContext)
        if (session != null) {
            val result = UltraSdkSession.ensure(appContext, session, current)
            fun changed() = Result(result.submitted, false, "SDK_CREDENTIAL_SCOPE_CHANGED")
            if (!current()) return changed()
            if (result.ready) {
                val scope = UltraAuthorization.captureScope(appContext, session, current) ?: return changed()
                val imported = dev.maplesu.manager.runtime.authorization.SuperuserControl.migrate(appContext, scope)
                if (!scope.current()) return if (imported.ok && imported.pendingApply)
                    Result(result.submitted, false, "AUTHORIZATION_SYNC_PENDING") else changed()
                if (!imported.ok) return Result(result.submitted, false, "AUTHORIZATION_IMPORT_PENDING")
                val reconciled = dev.maplesu.manager.runtime.authorization.SuperuserControl
                    .reconcilePending(appContext, scope, force = true)
                if (!scope.current()) return changed()
                if (!reconciled) return Result(result.submitted, false, "AUTHORIZATION_SYNC_PENDING")
            }
            return Result(result.submitted, result.ready, result.reason)
        }
        if (!current()) return Result(false, false, "SDK_CREDENTIAL_SCOPE_CHANGED")
        val key = UltraRootKeyStore.read(appContext)
        if (!current()) return Result(false, false, "SDK_CREDENTIAL_SCOPE_CHANGED")
        val observation = UltraEnvironmentProbe.currentSnapshot(appContext)
        val owner = observation.managerOwnerIdentity?.takeIf { key == null &&
            UltraEnvironmentProbe.hasCurrentRootProof(appContext) }
        if (key == null && owner == null) return Result(false, false, "NO_KEY")
        if (key != null && (!UltraKernelCredential.isValid(key) || !UltraKernelCredential.of(key).sdkMetadataValid)) {
            return Result(false, false, "KEY_MALFORMED")
        }
        if (bootId.isNullOrBlank()) return Result(false, false, "BOOT_ID_UNAVAILABLE")
        val prefs = appContext.ultraPreferences(PREFS, Context.MODE_PRIVATE)
        val activation = ModeRuntime.activation
        val rootCurrent = UltraRootKeyStore.captureOwnerMarker(appContext)
        val sessionCurrent = UltraSdkSession.captureOwnerMarker(appContext)
        val ownerProbe = UltraRuntimeClient()
        fun scopeCurrent() = current() && currentBootId() == bootId && ModeRuntime.activation === activation &&
            rootCurrent() && sessionCurrent() && UltraSdkCredentials.read(appContext) == key &&
            (owner == null || ownerProbe.queryManagerOwnerIdentity().identity == owner)
        val client = UltraRuntimeClient { key }.scoped(key, ::scopeCurrent)
        var installSkipped = false
        val result = UltraAutoSetupAttempt.run(
            object : UltraAutoSetupAttempt.Port {
                override fun blockedReason(): String? = when {
                    !scopeCurrent() -> "SDK_CREDENTIAL_SCOPE_CHANGED"
                    UltraRootKeyStore.read(appContext) != key -> "KEY_CHANGED"
                    currentBootId() != bootId -> "BOOT_CHANGED"
                    else -> UltraEnvironmentAutoSetup.blockedReason(appContext)
                }
                override fun state() = UltraEnvironmentProbe.refresh(appContext)
                override fun recordAttempt() {
                    if (current()) prefs.edit().putString(KEY_LAST_BOOT, bootId).apply()
                }
                override fun install(): UltraEnvironmentInstaller.Outcome {
                    if (!scopeCurrent()) {
                        installSkipped = true
                        return UltraEnvironmentInstaller.Outcome(false, "SDK_CREDENTIAL_SCOPE_CHANGED")
                    }
                    return UltraEnvironmentInstaller.install(appContext, client,
                        if (owner != null) UltraEnvironmentInstaller.Intent.InstallToBundledBuild
                        else UltraEnvironmentInstaller.Intent.ReuseRunning)
                        .also { Log.i(TAG, "环境自动安装 ${it.summary}") }
                }
                override fun refresh() { if (scopeCurrent()) UltraEnvironmentProbe.refresh(appContext) }
            }, recoverIdle = { key?.let { UltraIdleEnvironmentRecovery.ensure(appContext, it, bootId, ::scopeCurrent) } })
        return if (scopeCurrent()) result else result.copy(
            attempted = result.attempted && !installSkipped,
            reason = "SDK_CREDENTIAL_SCOPE_CHANGED",
        )
    }

    /** Recovery eligibility only: historical verification never substitutes for live Root. */
    internal fun hasVerifiedUltraIdentity(context: Context): Boolean {
        val key = UltraRootKeyStore.read(context) ?: return false
        return UltraKernelCredential.isValid(key) && UltraKernelCredential.of(key).sdkMetadataValid &&
            UltraKeyPairedStore.matchesVerifiedKey(context, key)
    }

    internal fun blockedReason(
        context: Context,
        bootSession: Boolean = UltraSdkSession.current(context) != null,
    ): String? = when {
        LiteImplantQuiesceGate.isBlocked() -> LiteImplantCodes.QUIESCING
        !bootSession && UltraRootKeyStore.isSuspended(context) -> "SUSPENDED"
        WorkModeSwitchSession.executing -> "SWITCH_PENDING"
        ModeRuntime.active == WorkMode.KSU -> "MODE_KSU"
        ModeRuntime.latestDetection?.state == ModeState.FOREIGN_SU ||
            ModeRuntime.latestDetection?.trace?.firstOrNull { it.id == "S1" }?.ok == true -> "ROOT_CONFLICT"
        else -> null
    }

    /** A matching committed local receipt breaks the first-Ultra-boot setup/Root dependency. */
    internal fun hasPostBootUltraSwitchRecovery(context: Context): Boolean = runCatching {
        if (WorkModeSwitchSession.executing) return@runCatching false
        val key = UltraRootKeyStore.read(context) ?: return@runCatching false
        if (key.length != UltraAutoPair.KEY_LENGTH || !key.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }) {
            return@runCatching false
        }
        val currentBoot = currentBootId() ?: return@runCatching false
        val bytes = key.toByteArray(Charsets.US_ASCII)
        val fingerprint = try { Hashes.sha256(bytes) } finally { bytes.fill(0) }
        val id = ModeSwitchProgress.postBootUltraRecovery(File(context.filesDir, "workmode/switch-work"),
            currentBoot, fingerprint) ?: return@runCatching false
        UltraStartupWarmup.matchesPostBootSwitch(id, WorkModeSwitchSession.executing,
            WorkModeSwitchSession.pending, WorkModeSwitchSession.switchId)
    }.getOrDefault(false)

    internal fun currentBootId(): String? = processBootId ?: runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()?.also { processBootId = it }
}
