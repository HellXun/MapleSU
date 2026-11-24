package dev.maplesu.manager.runtime.authorization

import android.content.Context
import dev.maplesu.manager.runtime.DirectRoot

import dev.maplesu.manager.runtime.SuAuditLog
import dev.maplesu.manager.runtime.ultra.UltraAuthorization
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import dev.maplesu.manager.runtime.ultra.UltraSdkSession
import dev.maplesu.manager.runtime.ultra.UltraBootSdkSession
import dev.maplesu.manager.runtime.UltraRootCommandReceipt
import dev.maplesu.manager.workmode.superuser.SuperuserMirrors
import java.io.File
import java.util.Base64

/**
 * The Manager's side of the authorization transaction (design §3.2).
 *
 * <p>One place decides *how* the root-side work is reached, and nothing about *what* it
 * does - the semantics are the transaction's and the policy's. The transport is chosen
 * by mode, exactly as the design divides it:
 *
 *  - **Ultra (hook)**: a one-shot `app_process` child behind the L3 gate. There is no
 *    resident root transport on this route, and inventing one is out of scope; if the
 *    gate is not armed the operation answers `SU_TRANSPORT_UNAVAILABLE` and the UI shows
 *    a failure. It never reports a success it did not get.
 *  - **Normal / Lite**: `DirectRoot`'s one-shot privileged process (the same mechanism
 *    `DirectPolicy` already uses to read and write the store).
 *  - **Current boot SDK**: SDK `runRootCommand` executes this same authority tool when
 *    the original Hook gate is absent, including after a same-boot manager restore.
 *  - **Permanent Ultra**: authorization stays on its existing SDK-only path.
 *
 * <p>Mirroring is orchestrated here - on the app side - because the kernel backends
 * (`KsuSuperuserAdapter`) are app-process JNI, not something the one-shot root tool may
 * assume. The order is still the design's: decision transaction first, mirror second,
 * `applied` watermark third. A failing mirror never rolls the decision back; it turns
 * into `PENDING_APPLY` and is retried (also once per boot, from the boot receiver).
 */
object SuperuserControl {
    const val TOOL = "dev.maplesu.manager.runtime.authorization.SuperuserTool"

    data class Result(
        val ok: Boolean,
        val rev: Long = 0,
        val code: String? = null,
        val pendingApply: Boolean = false,
        val detail: String = "",
    ) {
        val succeeded: Boolean get() = ok && !pendingApply

        fun requireApplied() {
            if (!succeeded) throw IllegalStateException(listOfNotNull(
                code ?: if (pendingApply) SuCodes.SU_AUTH_APPLY_PENDING else SuCodes.SU_STORE_WRITE_FAILED,
                "rev=$rev", detail.takeIf { it.isNotEmpty() },
            ).joinToString("; "))
        }

        companion object {
            @JvmStatic
            fun failure(code: String, detail: String = ""): Result =
                Result(false, 0, code, detail = detail)
        }
    }

    private fun RootToolOutcome.Failed.result(): Result =
        Result(false, rev, code, pendingApply, detail)

    /** The authority as the current process may see it; null means "no root transport". */
    @JvmStatic
    fun snapshot(context: Context): AuthorizationSnapshot? = snapshot(context, null)

    private fun snapshot(context: Context, scope: UltraAuthorization.Scope?): AuthorizationSnapshot? {
        if (!scopeCurrent(scope)) return null
        val snapshot = when (val reply = runTool(context, scope, "snapshot")) {
            is RootToolOutcome.Ok -> snapshotOf(reply)
            // Fail closed. The named code is visible on the write paths; a read has no
            // result type that could carry it, and a fabricated empty snapshot would be
            // worse than "unknown".
            is RootToolOutcome.Failed -> null
        }
        if (!scopeCurrent(scope)) return null
        return snapshot.takeIf { scopeCurrent(scope) }
    }

    /** Minted only from the root tool's fresh same-revision identity observation. */
    internal class CurrentIdentityRead private constructor(
        val rev: Long,
        val grants: Map<Int, ApplicationIdentity>,
        private val owner: UltraAuthorization.Scope,
    ) {
        internal fun matches(scope: UltraAuthorization.Scope, revision: Long, uid: Int,
                             packageName: String, allow: Boolean): Boolean =
            currentIdentityReadMatches(owner, scope, rev, revision, grants, uid, packageName, allow, owner.current)

        companion object {
            internal fun read(context: Context, scope: UltraAuthorization.Scope): CurrentIdentityRead? {
                if (!scope.current()) return null
                val reply = SuperuserControl.runTool(context, scope, "current-identities") as? RootToolOutcome.Ok ?: return null
                val encoded = reply.payload.substringAfter("identities=", "").substringBefore(' ')
                if (encoded.isEmpty() || !scope.current()) return null
                val observed = SuperuserWire.decode(encoded)
                    ?.takeIf { !it.corrupt && !it.enabled && scope.current() } ?: return null
                return CurrentIdentityRead(observed.rev, observed.grants, scope)
            }
        }
    }

    internal fun currentIdentities(context: Context, scope: UltraAuthorization.Scope? = null): CurrentIdentityRead? {
        val owner = scope ?: UltraAuthorization.captureScope(context) ?: return null
        return CurrentIdentityRead.read(context, owner)
    }

    @JvmStatic
    fun grant(context: Context, uid: Int): Result = commit(context, "grant", uid.toString())

    internal fun grant(context: Context, uid: Int, scope: UltraAuthorization.Scope): Result =
        commit(context, scope, "grant", uid.toString())

    /** Only an explicit install may initialize a truly absent authority. */
    fun initializeForInstallationIfAbsent(context: Context): Result = commit(context, "initialize-installation")

    internal fun initializeForInstallationIfAbsent(context: Context, scope: UltraAuthorization.Scope): Result =
        commit(context, scope, "initialize-installation")

    /** Manager startup may create its first entry, but never undo a disable or revocation. */
    internal fun ensureManagerGrant(context: Context, scope: UltraAuthorization.Scope): Result {
        val uid = context.applicationInfo.uid
        if (!scope.current()) return Result.failure("SU_AUTH_SCOPE_CHANGED")
        val observed = snapshot(context, scope) ?: return Result.failure(SuCodes.SU_STORE_READ_FAILED)
        if (!observed.enabled || observed.switchPending) return Result.failure(SuCodes.AUTH_DISABLED)
        if (observed.revoked(uid) || observed.profileOf(uid)?.profile?.allowSu == false)
            return Result.failure(SuCodes.AUTH_NOT_GRANTED)
        val identity = AndroidApplicationIdentities(context).read(uid)
            ?: return Result.failure(SuCodes.AUTH_IDENTITY_CHANGED)
        if (!scope.current()) return Result.failure("SU_AUTH_SCOPE_CHANGED")
        if (ManagerSuperuserPolicy.decide(observed, uid, identity).allowed)
            return Result(true, observed.rev, detail = "manager-grant-current")
        if (observed.grantOf(uid) != null) return Result.failure(SuCodes.AUTH_IDENTITY_CHANGED)
        return grant(context, uid, scope)
    }

    @JvmStatic
    fun saveProfile(context: Context, profile: SuProfile): Result {
        val encoded = try { SuperuserProfileWire.encode(profile) }
        catch (error: IllegalArgumentException) { return Result.failure("PROFILE_INVALID", error.message.orEmpty()) }
        val result = commit(context, "save-profile", encoded)
        return if (profile.allowSu) result else completeSessionRevocation(context, profile.currentUid, result)
    }

    @JvmStatic
    fun revoke(context: Context, uid: Int): Result =
        completeSessionRevocation(context, uid, commit(context, "revoke", uid.toString()))

    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean): Result {
        val result = commit(context, "set-enabled:$enabled")
        if (enabled) return result
        val current = snapshot(context)
        if (current == null || current.corrupt) return withSessionReceipt(result,
            SessionRevocation(false, 0, null, "global-session-uids-unknown"))
        val uids = current.grants.keys + current.profiles.keys + current.tombs.map { it.uid }
        return uids.sorted().fold(result) { receipt, uid -> completeSessionRevocation(context, uid, receipt) }
    }

    /**
     * One-shot import of the legacy stores (design §3.1). Sources that are not present
     * in this mode are **not** marked as migrated, so they are imported when they do
     * appear instead of being silently skipped forever.
     */
    @JvmStatic
    fun migrate(context: Context): Result = migrateInternal(context, null)

    internal fun migrate(context: Context, scope: UltraAuthorization.Scope): Result = migrateInternal(context, scope)

    private fun migrateInternal(context: Context, scope: UltraAuthorization.Scope?): Result = synchronized(this) {
        if (!scopeCurrent(scope)) return@synchronized Result.failure("SU_AUTH_SCOPE_CHANGED")
        val current = when (val reply = runTool(context, scope, "snapshot")) {
            is RootToolOutcome.Ok -> snapshotOf(reply)
                ?: return@synchronized Result.failure(SuCodes.SU_TRANSPORT_UNAVAILABLE)
            is RootToolOutcome.Failed -> return@synchronized reply.result()
        }
        // The status read is the transport probe the old code performed inside
        // legacySources(); it must not be collapsed into "no legacy sources", or a
        // seccomp-blocked tool would look like a clean no-op.
        when (val status = runTool(context, scope, "status")) {
            is RootToolOutcome.Failed -> return@synchronized status.result()
            is RootToolOutcome.Ok -> Unit
        }
        val sources = legacySources(context, current, scope)
            ?: return@synchronized Result.failure(
                if (scopeCurrent(scope)) SuCodes.SU_TRANSPORT_UNAVAILABLE else "SU_AUTH_SCOPE_CHANGED")
        if (!scopeCurrent(scope)) return@synchronized Result.failure("SU_AUTH_SCOPE_CHANGED")
        if (sources.isEmpty()) return@synchronized Result(true, current.rev, detail = "no-legacy-sources")
        val identities = AndroidApplicationIdentities(context)
        val packageManager = context.packageManager
        val plan = SuperuserMigration.plan(
            journal = journalOf(current),
            sources = sources,
            identityOfUid = { uid -> runCatching { identities.read(uid) }.getOrNull() },
            uidOfPackage = { name ->
                runCatching { packageManager.getApplicationInfo(name, 0).uid }.getOrNull()
            },
        )
        if (!scopeCurrent(scope)) return@synchronized Result.failure("SU_AUTH_SCOPE_CHANGED")
        if (plan.empty) return@synchronized Result(true, current.rev, detail = "already-migrated")
        val payload = SuperuserWire.encodePlan(plan)
            ?: return@synchronized Result.failure(SuCodes.SU_TRANSPORT_UNAVAILABLE)
        val reply = when (val outcome = runTool(context, scope, "migrate", payload)) {
            is RootToolOutcome.Ok -> outcome.payload
            is RootToolOutcome.Failed -> return@synchronized outcome.result()
        }
        val outcome = parseOutcome(reply, defaultRev = current.rev)
        if (!outcome.ok) return@synchronized outcome
        if (!scopeCurrent(scope)) return@synchronized outcome.copy(pendingApply = true)
        snapshot(context)
        if (scopeCurrent(scope)) outcome else outcome.copy(pendingApply = true)
    }

    /**
     * Revocation's third leg: terminate the caller's live sessions. The registry lives in
     * the `:root_directory` process, so this is an in-app `ContentProvider.call` (same
     * application, manager-uid check on the other side) - not a new exported component.
     */
    data class SessionRevocation(
        val receiptKnown: Boolean,
        val killed: Int,
        val pendingCount: Int?,
        val detail: String,
    ) {
        val pending: Boolean get() = !receiptKnown || pendingCount != 0
    }

    @JvmStatic
    fun killSessionsDetailed(context: Context, uid: Int): SessionRevocation = try {
        require(uid >= 0) { "SU_SESSION_UID_INVALID" }
        val reply = com.maplesu.core.ui.util.ProviderCall.call(context.contentResolver,
            android.net.Uri.parse("content://dev.maplesu.manager.root_directory"),
            "revokeSessions", null, android.os.Bundle().apply { putInt("uid", uid) },
        ) ?: throw IllegalStateException("SU_SESSION_RECEIPT_MISSING")
        val killed = reply.getInt("killed", -1)
        val pending = reply.getInt("pendingCount", -1)
        if (!reply.containsKey("pending") || killed < 0 || pending < 0 ||
            reply.getBoolean("pending", true) != (pending > 0)) {
            SessionRevocation(false, killed.coerceAtLeast(0), null, "uid=$uid receipt=invalid")
        } else SessionRevocation(true, killed, pending, "uid=$uid killed=$killed pendingCount=$pending")
    } catch (error: Exception) {
        SessionRevocation(false, 0, null, "uid=$uid receipt=unknown:" + error.javaClass.simpleName + ":" +
            error.message.orEmpty().take(512))
    }

    /** Legacy count API never represents an unobserved/pending drain as completed. */
    @JvmStatic
    fun killSessions(context: Context, uid: Int): Int {
        val result = killSessionsDetailed(context, uid)
        check(!result.pending) { "SU_SESSION_DRAIN_PENDING; " + result.detail }
        return result.killed
    }

    internal fun completeSessionRevocation(context: Context, uid: Int, decision: Result): Result =
        withSessionReceipt(decision, killSessionsDetailed(context, uid))

    private fun withSessionReceipt(decision: Result, sessions: SessionRevocation): Result = decision.copy(
        pendingApply = decision.pendingApply || sessions.pending,
        code = decision.code ?: if (sessions.pending) "SU_SESSION_DRAIN_PENDING" else null,
        // Mirror watermark and session reap/drain are separate facts; do not rewrite the journal here.
        detail = listOf(decision.detail, "sessionDrain=" + if (sessions.pending) "pending; " else "complete; ",
            sessions.detail).filter { it.isNotEmpty() }.joinToString("; "),
    )

    /**
     * Re-runs a failed mirror (P2 `pendingApply`). Returns true when nothing is pending
     * afterwards. Called from the boot receiver and after a failed commit.
     */
    @JvmStatic
    @JvmOverloads
    fun reconcilePending(context: Context, force: Boolean = false): Boolean =
        reconcilePendingInternal(context, null, force)

    internal fun reconcilePending(context: Context, scope: UltraAuthorization.Scope, force: Boolean = false): Boolean =
        reconcilePendingInternal(context, scope, force)

    private fun reconcilePendingInternal(
        context: Context,
        scope: UltraAuthorization.Scope?,
        force: Boolean,
    ): Boolean = synchronized(this) {
        if (!scopeCurrent(scope)) return@synchronized false
        val current = snapshot(context, scope) ?: return@synchronized false
        if (!force && !hasPendingMarker(context) && !current.pendingApply()) {
            if (!scopeCurrent(scope)) return@synchronized false
            clearPending(context)
            return@synchronized scopeCurrent(scope)
        }
        val mirror = if (scope == null) SuperuserMirrors.current(context)
            else SuperuserMirrors.forSdkScope(context, scope)
        if (!scopeCurrent(scope)) return@synchronized false
        val outcome = runCatching { mirror.apply(current) }
            .getOrElse { MirrorOutcome.failure(it.javaClass.simpleName) }
        if (!scopeCurrent(scope)) return@synchronized false
        val recorded = markApplied(context, mirror.backend, current.rev, outcome, scope)
        val applied = recorded.succeeded && outcome.ok
        if (!scopeCurrent(scope)) return@synchronized false
        if (applied) clearPending(context) else markPending(context)
        applied && scopeCurrent(scope)
    }

    /**
     * A small app-private marker so the boot receiver can answer "is there anything to
     * retry?" without starting a root process on every boot. It is written only when a
     * mirror actually failed and cleared when one succeeds; it is never a decision input.
     */
    @JvmStatic
    fun hasPendingMarker(context: Context): Boolean = pendingMarker(context).isFile

    @JvmStatic
    fun markPending(context: Context) {
        runCatching {
            val file = pendingMarker(context)
            file.parentFile?.mkdirs()
            file.writeText(System.currentTimeMillis().toString())
        }
    }

    @JvmStatic
    fun clearPending(context: Context) {
        runCatching { pendingMarker(context).delete() }
    }

    private fun pendingMarker(context: Context): File {
        val protected = context.createDeviceProtectedStorageContext().dataDir
            ?: context.filesDir.parentFile ?: context.filesDir
        return File(protected, "superuser-pending-apply")
    }

    // --- internals ---------------------------------------------------------

    /** Decision transaction (root-side), then mirror (app-side), then the watermark. */
    private fun commit(context: Context, vararg arguments: String): Result = commit(context, null, *arguments)

    private fun commit(context: Context, scope: UltraAuthorization.Scope?, vararg arguments: String): Result = synchronized(this) {
        val reply = when (val outcome = runTool(context, scope, *arguments)) {
            is RootToolOutcome.Ok -> outcome.payload
            is RootToolOutcome.Failed -> return@synchronized outcome.result()
        }
        val kind = if (arguments.firstOrNull()?.startsWith("set-enabled:") == true) {
            OutcomeKind.SET_ENABLED
        } else OutcomeKind.TRANSACTION
        val outcome = parseOutcome(reply, kind = kind)
        if (!outcome.ok) return@synchronized outcome
        DirectPolicy.invalidate()
        val snapshot = snapshot(context, scope)
        val mirror = if (scope == null) SuperuserMirrors.current(context)
            else SuperuserMirrors.forSdkScope(context, scope)
        if (snapshot == null) {
            val recorded = markApplied(context, mirror.backend, outcome.rev, MirrorOutcome.failure("snapshot-unavailable"),
                scope = scope, decision = outcome, mirrorDetail = "snapshot-unavailable")
            markPending(context)
            if (!recorded.ok) return@synchronized recorded
            return@synchronized outcome.copy(pendingApply = true,
                detail = listOf(outcome.detail, "snapshot-unavailable",
                    if (recorded.ok) "" else "mark-applied code=${recorded.code} ${recorded.detail}")
                    .filter { it.isNotEmpty() }.joinToString("; "))
        }
        val mirrored = runCatching { mirror.apply(snapshot) }
            .getOrElse { MirrorOutcome.failure(it.javaClass.simpleName + ":" + it.message) }
        val recorded = markApplied(context, mirror.backend, snapshot.rev, mirrored,
            scope = scope, decision = outcome, mirrorDetail = mirrored.detail)
        val applied = recorded.succeeded && mirrored.ok
        if (applied) clearPending(context) else markPending(context)
        if (!recorded.ok) return@synchronized recorded
        outcome.copy(
            pendingApply = !applied,
            detail = listOf(outcome.detail, mirrored.detail,
                if (recorded.ok) "" else "mark-applied code=${recorded.code} ${recorded.detail}")
                .filter { it.isNotEmpty() }.joinToString("; "),
        )
    }

    private fun markApplied(
        context: Context,
        backend: String,
        rev: Long,
        outcome: MirrorOutcome,
        scope: UltraAuthorization.Scope? = null,
        decision: Result? = null,
        mirrorDetail: String = "",
    ): Result {
        fun failedMark(failed: Result, known: Boolean): Result {
            if (decision == null) return failed
            val detail = listOf(failed.detail,
                "decisionRev=${decision.rev} markApplied=${if (known) "failed" else "unknown"}",
                decision.detail, mirrorDetail).filter { it.isNotEmpty() }.joinToString("; ")
            // Missing or malformed mark receipts cannot erase the observed decision.
            return if (known) failed.copy(detail = detail)
            else failed.copy(rev = decision.rev, pendingApply = true, detail = detail)
        }
        val detail = Base64.getEncoder().encodeToString(outcome.detail.toByteArray(Charsets.UTF_8))
        val reply = when (val reply = runTool(
            context, scope, "mark-applied", backend, rev.toString(),
            if (outcome.ok) "1" else "0", detail,
        )) {
            is RootToolOutcome.Ok -> reply
            is RootToolOutcome.Failed -> return failedMark(reply.result(), reply.receiptKnown)
        }
        val recorded = parseOutcome(reply.payload)
        if (!recorded.ok) return failedMark(recorded, known = false)
        val checked = if (recorded.ok && !recorded.pendingApply && recorded.rev == rev) recorded
        else recorded.copy(ok = false, code = recorded.code ?: SuCodes.SU_TRANSPORT_UNAVAILABLE,
            detail = "expectedRev=$rev observedRev=${recorded.rev} pending=${recorded.pendingApply} ${recorded.detail}")
        if (!checked.ok) return failedMark(checked, known = true)
        return if (checked.ok && !scopeCurrent(scope)) checked.copy(pendingApply = true) else checked
    }

    /**
     * Collects the legacy stores that currently exist. `null` means "the question could
     * not be answered" (no transport); an empty list means "nothing to import now".
     */
    private fun legacySources(
        context: Context,
        snapshot: AuthorizationSnapshot,
        scope: UltraAuthorization.Scope?,
    ): List<SuperuserLegacySource>? {
        if (!scopeCurrent(scope)) return null
        val sources = ArrayList<SuperuserLegacySource>()
        val migrated = snapshot.migrated
        if (!scopeCurrent(scope)) return null
        if (!migrated.containsKey(SuperuserMigration.SOURCE_ULTRA) &&
            (scope != null || UltraAuthorization.isActive(context))) {
            val packages = runCatching {
                val refreshed = if (scope == null) UltraAuthorization.refresh(context) else UltraAuthorization.refresh(scope)
                if (!refreshed) return null
                UltraAuthorization.grantedPackages().also { if (!scopeCurrent(scope)) return null }
            }.getOrElse { if (scope != null) return null else emptySet() }
            if (packages.isNotEmpty()) {
                sources += SuperuserLegacySource(SuperuserMigration.SOURCE_ULTRA, packages = packages)
            }
        }
        if (!scopeCurrent(scope)) return null
        // Ultra-only：Normal（KSU）授权表迁移源已移除。
        return sources.takeIf { scopeCurrent(scope) }
    }

    private fun journalOf(snapshot: AuthorizationSnapshot): SuperuserJournal = SuperuserJournal(
        rev = snapshot.rev,
        uidRev = snapshot.uidRev.mapKeys { it.key.toString() },
        tombs = snapshot.tombs,
        applied = snapshot.applied,
        migrated = snapshot.migrated,
        unresolved = snapshot.unresolved,
        pendingApplyRev = snapshot.pendingApplyRev,
        profileRev = snapshot.profileRev.mapKeys { it.key.toString() },
    )

    /**
     * Runs one tool operation and classifies its answer once.
     *
     * <p>Every failure is fail-closed: no gate / no root / an `ERR` line maps to
     * [SuCodes.SU_TRANSPORT_UNAVAILABLE], and a child killed by the App's inherited
     * seccomp filter (`SIGSYS`, exit 159) maps to
     * [SuCodes.SU_PRIVILEGED_PATH_SECCOMP_BLOCKED]. There is no path here that invents a
     * successful revision.
     */
    internal fun runTool(context: Context, vararg arguments: String): RootToolOutcome =
        runTool(context, null, *arguments)

    private fun runTool(context: Context, scope: UltraAuthorization.Scope?, vararg arguments: String): RootToolOutcome {
        if (!scopeCurrent(scope)) return RootToolOutcome.Failed("SU_AUTH_SCOPE_CHANGED")
        val outcome = if (scope != null) {
            val command = command(context, *arguments)
            if (!scope.current()) return RootToolOutcome.Failed("SU_AUTH_SCOPE_CHANGED")
            runSdkTool(scope.client, command)
        } else {
            val resolution = resolve(context)
            val command = command(context, *arguments)
            when (resolution) {
                is Resolution.BootSdk -> runSdkTool(UltraSdkSession.client(context, resolution.session), command)
                is Resolution.OwnerSdk -> {
                    if (!resolution.scope.current()) RootToolOutcome.Failed("SU_AUTH_SCOPE_CHANGED")
                    else runSdkTool(resolution.scope.client, command).let {
                        if (resolution.scope.current()) it else RootToolOutcome.Failed("SU_AUTH_SCOPE_CHANGED")
                    }
                }
                Resolution.Direct -> {
                    val result = DirectRoot.run(context, command)
                    RootToolOutcome.parse(result.code, result.output)
                }
                Resolution.Unavailable -> RootToolOutcome.Failed(SuCodes.SU_TRANSPORT_UNAVAILABLE)
            }
        }
        if (outcome is RootToolOutcome.Failed &&
            outcome.code == SuCodes.SU_PRIVILEGED_PATH_SECCOMP_BLOCKED
        ) {
            // Name the failure once. The operation name is safe; arguments (a migration
            // plan) are never logged.
            android.util.Log.w(
                "MapleSU",
                "SU_PRIVILEGED_PATH_SECCOMP_BLOCKED op=" + (arguments.firstOrNull() ?: "-"),
            )
        }
        return outcome
    }

    private fun runSdkTool(client: UltraRuntimeClient, command: String): RootToolOutcome {
        val wrapped = UltraRootCommandReceipt(command)
        val receipt = client.runRootCommand(wrapped.command)
        val result = wrapped.decodeSdk(receipt, client::decodeText)
        if (!receipt.succeeded) return RootToolOutcome.Failed(
            SuCodes.SU_TRANSPORT_UNAVAILABLE, detail = result.detail,
        )
        return RootToolOutcome.parse(result.code, result.output)
    }

    private fun scopeCurrent(scope: UltraAuthorization.Scope?): Boolean = scope?.current?.invoke() != false

    /**
     * The exact `app_process` invocation, the same shape `DirectPolicy` already uses.
     *
     * <p>The audit path is an argument because the root tool no longer creates a
     * `Context`; it is the same device-protected file `SuAuditLog.file(context)` names,
     * so the root tool and the app still append to one log.
     */
    internal fun command(context: Context, vararg arguments: String): String {
        val application = context.applicationContext
        val legacy = File(application.noBackupFilesDir, "authorizations.bin").absolutePath
        val audit = SuAuditLog.file(application).absolutePath
        return buildString {
            append("CLASSPATH=").append(quote(application.applicationInfo.sourceDir))
            // ServiceManager evaluates the caller's SELinux domain in its own process.
            // The Hook kernel domain cannot find package_service there. Scope the shell
            // domain to this UID-0 tool so it can verify package identities through Binder.
            append(" exec ")
            append("/system/bin/app_process /system/bin ")
            append(TOOL).append(' ')
            append(application.applicationInfo.uid).append(' ')
            append(quote(legacy)).append(' ')
            append(quote(audit)).append(' ')
            arguments.joinTo(this, " ") { quote(it) }
        }
    }

    private fun snapshotOf(reply: RootToolOutcome.Ok): AuthorizationSnapshot? {
        val wire = reply.payload.substringAfter("wire=", "").substringBefore(' ')
        if (wire.isEmpty()) return null
        return SuperuserWire.decode(wire)
    }

    internal enum class OutcomeKind { TRANSACTION, SET_ENABLED }

    // Keep the existing entry point; a prior revision is never proof of a new receipt.
    @Suppress("UNUSED_PARAMETER")
    internal fun parseOutcome(payload: String, defaultRev: Long = 0): Result =
        parseOutcome(payload, OutcomeKind.TRANSACTION)

    internal fun parseOutcome(payload: String, kind: OutcomeKind): Result {
        val fields = LinkedHashMap<String, String>()
        fun invalid(reason: String): Result = Result.failure(
            SuCodes.SU_STORE_WRITE_FAILED,
            listOf("invalid-result:$reason", fields["detail"].orEmpty(),
                fields["code"]?.takeUnless { it == "OK" }?.let { "code=$it" }.orEmpty())
                .filter { it.isNotEmpty() }.joinToString("; "),
        )
        var fieldError: String? = null
        for (token in payload.split(' ').filter { it.isNotEmpty() }) {
            val separator = token.indexOf('=')
            if (separator <= 0) {
                fieldError = fieldError ?: "field"
                continue
            }
            val key = token.substring(0, separator)
            if (key in fields) fieldError = fieldError ?: "duplicate-field"
            else fields[key] = token.substring(separator + 1)
        }
        fieldError?.let { return invalid(it) }
        val allowed = when (kind) {
            OutcomeKind.TRANSACTION -> setOf("rev", "pending", "detail", "code")
            OutcomeKind.SET_ENABLED -> setOf("enabled", "rev", "pending", "detail", "code")
        }
        if (fields.keys.any { it !in allowed }) return invalid("unexpected-field")
        val revision = fields["rev"] ?: return invalid("missing-rev")
        val rev = revision.toLongOrNull()?.takeIf { it >= 0 } ?: return invalid("invalid-rev")
        val pending = when (kind) {
            OutcomeKind.TRANSACTION -> when (fields["pending"]) {
                "0" -> false
                "1" -> true
                null -> return invalid("missing-pending")
                else -> return invalid("invalid-pending")
            }
            OutcomeKind.SET_ENABLED -> {
                if (fields["enabled"] !in setOf("true", "false")) return invalid("invalid-enabled")
                when (fields["pending"]) {
                    null -> if (fields.keys == setOf("enabled", "rev")) false
                        else return invalid("missing-pending")
                    "0" -> false
                    "1" -> true
                    else -> return invalid("invalid-pending")
                }
            }
        }
        if (fields["code"] == "") return invalid("invalid-code")
        return Result(
            ok = true,
            rev = rev,
            code = fields["code"]?.takeUnless { it == "OK" },
            pendingApply = pending,
            detail = fields["detail"].orEmpty(),
        )
    }

    /** Which transport this build offers; pure, so the host test can assert the table. */
    internal enum class Transport { BOOT_SDK, DIRECT, NONE }

    internal fun transportOf(
        ultraActive: Boolean,
        bootSdk: Boolean = false,
    ): Transport = when {
        bootSdk -> Transport.BOOT_SDK
        // The Ultra kernel restarts if the one-shot root runtime is started; refusing by
        // name is the honest answer and keeps the "no faked success" rule.
        ultraActive -> Transport.NONE
        else -> Transport.DIRECT
    }

    /**
     * One resolved transport: the gate travels with the decision, so "which transport"
     * and "which token" cannot be answered from two different reads.
     */
    internal sealed interface Resolution {
        data class BootSdk(val session: UltraBootSdkSession) : Resolution
        data class OwnerSdk(val scope: UltraAuthorization.Scope) : Resolution
        object Direct : Resolution
        object Unavailable : Resolution
    }

    /**
     * Resolves the transport for one command.
     *
     * <p>Ultra-only: the legacy hook route is gone, so the decision is simply
     * "is there an SDK boot session, an owner scope, or a direct credential". Every
     * missing part answers [Resolution.Unavailable] rather than inventing success.
     */
    internal fun resolve(context: Context): Resolution {
        val bootSession = UltraSdkSession.current(context)
        val ownerScope = dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe.currentSnapshot(context)
            ?.managerOwnerIdentity?.let { UltraAuthorization.captureOwnerScope(context, it) { true } }
        if (bootSession != null) return Resolution.BootSdk(bootSession)
        if (ownerScope != null) return Resolution.OwnerSdk(ownerScope)
        val ultraActive = runCatching { UltraAuthorization.isActive(context) }.getOrDefault(false)
        return if (transportOf(ultraActive) == Transport.DIRECT) {
            Resolution.Direct
        } else {
            Resolution.Unavailable
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

/** The token's tested acceptance predicate; transport creation remains root-read-only. */
internal fun currentIdentityReadMatches(owner: Any, scope: Any, observedRevision: Long, revision: Long,
                                       identities: Map<Int, ApplicationIdentity>, uid: Int, packageName: String,
                                       allow: Boolean, current: () -> Boolean): Boolean =
    scope === owner && observedRevision == revision && current() &&
        (!allow || identities[uid]?.members?.containsKey(packageName) == true)
