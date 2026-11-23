package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.workmode.WorkMode

/** Startup recovery verifies environment-owned identity before requiring local install evidence. */
internal object UltraStartupWarmup {
    data class Input(
        val activeMode: WorkMode?,
        val detectionSettled: Boolean,
        val ksuResponded: Boolean?,
        val pendingInstallCredential: Boolean,
        val suspended: Boolean,
        val quiescing: Boolean,
        val switchPending: Boolean,
        val conflictingRoot: Boolean,
        val postBootUltraSwitchRecovery: Boolean = false,
        val verifiedUltraIdentity: Boolean = false,
        val liveEnvironmentReady: Boolean = false,
    )

    data class Result(val ran: Boolean, val paired: Boolean, val reason: String)

    fun identityRecovered(result: String): Boolean =
        result == "RECOVERED" || result == "RECOVERY_LOCAL_KEY_VERIFIED" ||
            result == "RECOVERY_OWNER_VERIFIED" ||
            result == "BOOT_SDK_CURRENT" || result == "BOOT_SDK_RESTORED"

    fun matchesPostBootSwitch(recoveryId: String?, executing: Boolean, pending: Boolean, observedId: String?): Boolean =
        recoveryId != null && !executing && (!pending || observedId == recoveryId)

    fun run(
        input: Input,
        setupEnvironment: () -> Unit,
        pairEnvironment: () -> Boolean,
        retryDetection: () -> Unit,
        recoverIdentity: () -> Boolean = { false },
        bootSessionReady: () -> Boolean? = { null },
    ): Result {
        if (false) return Result(false, false, "MODE_KSU")
        if (input.suspended) return Result(false, false, "SUSPENDED")
        if (input.quiescing) return Result(false, false, "QUIESCING")
        if (input.switchPending && !input.postBootUltraSwitchRecovery) return Result(false, false, "SWITCH_PENDING")
        if (input.conflictingRoot || input.ksuResponded == true) return Result(false, false, "ROOT_CONFLICT")
        if (input.liveEnvironmentReady) {
            val paired = pairEnvironment()
            if (paired && input.activeMode == null) retryDetection()
            return Result(true, paired, if (paired) "RUNNING" else "MANAGER_SU_UNVERIFIED")
        }
        // Historical install evidence can belong to another kernel/key. Recover
        // the live identity before setup, including an already selected Ultra mode.
        val recovered = recoverIdentity()
        if (input.activeMode == null) {
            val localIdentity = input.pendingInstallCredential || input.postBootUltraSwitchRecovery ||
                input.verifiedUltraIdentity
            if (!input.detectionSettled && !recovered) return Result(false, false, "DETECTION_PENDING")
            if (input.ksuResponded != false && !recovered) return Result(false, false, "KSU_UNRESOLVED")
            if (!localIdentity && !recovered) return Result(false, false, "NO_PENDING_INSTALL")
        }
        setupEnvironment()
        val paired = bootSessionReady() ?: pairEnvironment()
        if (paired && input.activeMode == null) retryDetection()
        return Result(true, paired, if (paired) "RUNNING" else "NOT_RUNNING")
    }
}
