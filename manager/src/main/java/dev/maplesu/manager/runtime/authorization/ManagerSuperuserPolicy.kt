package dev.maplesu.manager.runtime.authorization

/**
 * The one superuser decision function (design `maplesu-ultra-thirdparty-su-design-2026-10-31.md`
 * §1.2 / §4.4).
 *
 * <p>Why this file exists. Before it, four stores answered "may this uid run su?" with
 * four different rules: `RootAuthorizationStore` (uid + full install identity, root 0600,
 * restricted Binder), the Pro SDK's `su_auth` list (package names) and the KSU kernel
 * allowlist (package names). The user's decision is that **the Manager's list is the
 * authority**; so there is exactly one decision function now, and every entry point -
 * the Ultra supervised endpoints and the legacy Binder (through `AuthorizationPolicy`)
 * - calls it.
 *
 * <p>Order is load-bearing and is the same order the design fixes:
 * corrupt store → deny; master switch off → `AUTH_DISABLED`; revoked (tombstone newer
 * than the last grant) → `AUTH_NOT_GRANTED`; no record → `AUTH_NOT_GRANTED` (default
 * deny); identity absent or changed → `AUTH_IDENTITY_CHANGED`; otherwise allow.
 *
 * <p>Pure Kotlin: no `Context`, no Android type, so the host tests drive every branch.
 */

/** One revocation tombstone. `rev` is the revision the revoke happened at. */
@kotlinx.serialization.Serializable
data class SuperuserTomb(
    val uid: Int,
    val packageName: String,
    val rev: Long,
    val at: Long,
)

/**
 * The watermark of one mirror backend (KSU allowlist, Pro `su_auth`).
 *
 * <p>`ok == false` means the decision is in force (the restricted entry already refuses)
 * but the kernel-side mirror has not caught up yet: the design's `PENDING_APPLY`.
 */
@kotlinx.serialization.Serializable
data class SuperuserApplied(
    val backend: String,
    val rev: Long,
    val at: Long,
    val ok: Boolean,
    val detail: String = "",
)

/**
 * A legacy grant that could not be mapped to a current installation.
 *
 * <p>It is **not** a grant: an unmapped legacy entry is recorded for the UI ("needs
 * confirmation") and the uid stays denied by default.
 */
@kotlinx.serialization.Serializable
data class SuperuserUnresolved(
    val source: String,
    val uid: Int?,
    val packageName: String?,
    val reason: String,
    val at: Long,
)

/**
 * Everything a decision needs, read once per decision.
 *
 * <p>`rev` is the journal's monotonic revision. `uidRev[uid]` is the revision of that
 * uid's last **grant**; a tombstone covers the uid when `tombRev(uid) > uidRev(uid)`.
 * A revoke therefore never updates `uidRev`, which is what makes "revoke does not come
 * back" true even when the binary store still carries the old record (a crash between
 * the journal write and the store write).
 */
data class AuthorizationSnapshot(
    val enabled: Boolean,
    val grants: Map<Int, ApplicationIdentity>,
    val rev: Long = 0,
    val uidRev: Map<Int, Long> = emptyMap(),
    val tombs: List<SuperuserTomb> = emptyList(),
    val applied: List<SuperuserApplied> = emptyList(),
    val migrated: Map<String, Long> = emptyMap(),
    val unresolved: List<SuperuserUnresolved> = emptyList(),
    /** True when the store could not be read; every decision is then a refusal. */
    val corrupt: Boolean = false,
    val switchPending: Boolean = false,
    val pendingApplyRev: Long? = null,
    val profiles: Map<Int, StoredSuProfile> = emptyMap(),
    val profileRev: Map<Int, Long> = emptyMap(),
) {
    fun tombRev(uid: Int): Long =
        tombs.asSequence().filter { it.uid == uid }.maxOfOrNull { it.rev } ?: 0L

    fun uidRevOf(uid: Int): Long = uidRev[uid] ?: 0L

    /** The design's single revocation predicate: a newer tombstone than the last grant. */
    fun revoked(uid: Int): Boolean = tombRev(uid) > uidRevOf(uid)

    fun appliedOf(backend: String): SuperuserApplied? = applied.asReversed()
        .filter { it.backend == backend }.maxByOrNull { it.rev }

    /** The latest selected mirror must cover this revision; historical backends are retired. */
    fun pendingApply(): Boolean = switchPending || pendingApplyRev != null ||
        (applied.asReversed().maxByOrNull { it.rev }
            ?.let { !it.ok || it.rev < rev } ?: false)

    fun grantOf(uid: Int): ApplicationIdentity? = grants[uid]

    fun profileOf(uid: Int): StoredSuProfile? = profiles[uid]

    companion object {
        /** The only "nothing" snapshot: switch off, no grants, not corrupt. */
        @JvmStatic
        fun empty(): AuthorizationSnapshot = AuthorizationSnapshot(false, emptyMap())

        /** A store that could not be read: switch off **and** marked corrupt (fail-closed). */
        @JvmStatic
        fun unreadable(): AuthorizationSnapshot =
            AuthorizationSnapshot(false, emptyMap(), corrupt = true)
    }
}

/** One decision. [reason] is a code from [SuCodes], never a sentence. */
data class Decision(
    val allowed: Boolean,
    val reason: String,
    val rev: Long = 0,
    val grant: ApplicationIdentity? = null,
) {
    companion object {
        @JvmStatic
        fun allow(rev: Long, grant: ApplicationIdentity? = null): Decision =
            Decision(true, SuCodes.ALLOW, rev, grant)

        @JvmStatic
        fun deny(reason: String, rev: Long = 0): Decision = Decision(false, reason, rev)
    }
}

/**
 * The shared failure-code table (design §4.4: "Ultra 与 Normal 共用一张表").
 *
 * <p>Normal expresses the Ultra-only gate codes as `AUTH_NOT_GRANTED` plus a
 * `backendReason`; the `AUTH_*` codes below are produced by [ManagerSuperuserPolicy] on
 * both routes, which is what makes the two modes' refusal semantics identical.
 */
object SuCodes {
    const val ALLOW = "ALLOW"

    // Authorization (both modes; produced by ManagerSuperuserPolicy).
    const val AUTH_DENIED = "AUTH_DENIED"
    const val AUTH_NOT_GRANTED = "AUTH_NOT_GRANTED"
    const val AUTH_DISABLED = "AUTH_DISABLED"
    const val AUTH_IDENTITY_CHANGED = "AUTH_IDENTITY_CHANGED"
    const val AUTH_IDENTITY_UNAVAILABLE = "AUTH_IDENTITY_UNAVAILABLE"
    const val AUTH_REVOKED = "AUTH_REVOKED"

    // Caller resolution (Ultra; the same facts Normal's provider returns null for).
    const val SU_CALLER_IDENTITY_UNAVAILABLE = "SU_CALLER_IDENTITY_UNAVAILABLE"
    const val SU_CALLER_PACKAGE_UNAVAILABLE = "SU_CALLER_PACKAGE_UNAVAILABLE"
    const val SU_CALLER_PACKAGE_AMBIGUOUS = "SU_CALLER_PACKAGE_AMBIGUOUS"

    // Ultra gate / transport.
    const val SU_GATE_NOT_ARMED = "SU_GATE_NOT_ARMED"
    const val SU_GATE_DENIED = "SU_GATE_DENIED"
    const val SU_EXECUTOR_NOT_READY = "SU_EXECUTOR_NOT_READY"
    const val SU_TRANSPORT_UNAVAILABLE = "SU_TRANSPORT_UNAVAILABLE"
    const val SU_EXEC_FAILED = "SU_EXEC_FAILED"
    const val SU_TIMEOUT = "SU_TIMEOUT"

    /**
     * The one-shot privileged child died on the App's inherited seccomp filter
     * (`SIGSYS`, exit 128+31). It is named separately from
     * [SU_TRANSPORT_UNAVAILABLE] because the two require different remediations:
     * a transport is missing, versus a transport that ran and was killed by the
     * kernel. Never retried into a success (design
     * `maplesu-seccomp-safe-privileged-path-2026-10-31.md` §4).
     */
    const val SU_PRIVILEGED_PATH_SECCOMP_BLOCKED = "SU_PRIVILEGED_PATH_SECCOMP_BLOCKED"

    // Request shape (identical on both restricted entries).
    const val SU_REQUEST_INVALID = "SU_REQUEST_INVALID"
    const val SU_REQUEST_TRAILING_DATA = "SU_REQUEST_TRAILING_DATA"
    const val SU_TERMINAL_UNAVAILABLE = "SU_TERMINAL_UNAVAILABLE"
    const val SU_TARGET_UID_UNAVAILABLE = "SU_TARGET_UID_UNAVAILABLE"
    const val SU_GLOBAL_MOUNT_UNAVAILABLE = "SU_GLOBAL_MOUNT_UNAVAILABLE"
    const val SU_SESSION_LIMIT = "SU_SESSION_LIMIT"
    const val SU_RESIZE_UNAVAILABLE = "SU_RESIZE_UNAVAILABLE"

    // Audit is best-effort and never changes a decision.
    const val SU_AUDIT_WRITE_FAILED = "SU_AUDIT_WRITE_FAILED"
    const val SU_AUTH_APPLY_PENDING = "SU_AUTH_APPLY_PENDING"

    // Normal/KSU mirror semantics.
    const val KSU_SUCOMPAT_DENIED = "KSU_SUCOMPAT_DENIED"

    // Store / transaction.
    const val SU_JOURNAL_WRITE_FAILED = "SU_JOURNAL_WRITE_FAILED"
    const val SU_STORE_WRITE_FAILED = "SU_STORE_WRITE_FAILED"
    const val SU_STORE_READ_FAILED = "SU_STORE_READ_FAILED"
}

object ManagerSuperuserPolicy {
    /** The AUTH code a normalized numeric app uid must live in (same domain as identities). */
    @JvmStatic
    fun validUserDomain(uid: Int): Boolean = uid >= 0 && uid % 100_000 in 10_000..19_999

    /**
     * The one decision (design §1.2).
     *
     * @param identity the PackageManager snapshot of [callerUid], or null when it could
     *   not be resolved. Null is a refusal: the design's rule is that an absent identity
     *   and a changed identity are the same answer (`AUTH_IDENTITY_CHANGED`).
     */
    @JvmStatic
    fun decide(
        snapshot: AuthorizationSnapshot,
        callerUid: Int,
        identity: ApplicationIdentity?,
    ): Decision {
        // 0. An unreadable store denies everything; it never silently becomes "disabled".
        if (snapshot.corrupt) return Decision.deny(SuCodes.AUTH_NOT_GRANTED, snapshot.rev)
        // 1. Master switch first: "off" is a refusal with its own code on both modes.
        if (!snapshot.enabled || snapshot.switchPending) return Decision.deny(SuCodes.AUTH_DISABLED, snapshot.rev)
        // 2. The caller must be an application uid of this user domain.
        if (!validUserDomain(callerUid)) {
            return Decision.deny(SuCodes.SU_CALLER_IDENTITY_UNAVAILABLE, snapshot.rev)
        }
        // 3. Revocation never resurrects: a tombstone newer than the last grant denies.
        if (snapshot.revoked(callerUid)) {
            return Decision.deny(SuCodes.AUTH_NOT_GRANTED, snapshot.rev)
        }
        // 4. No record = default deny. The answer does not reveal whether a package exists.
        val record = snapshot.grantOf(callerUid)
            ?: return Decision.deny(SuCodes.AUTH_NOT_GRANTED, snapshot.rev)
        // 5. Same UID, member set, signers and install epochs; legitimate updates may change APK facts.
        if (identity == null) return Decision.deny(SuCodes.AUTH_IDENTITY_CHANGED, snapshot.rev)
        if (identity.uid != callerUid) {
            return Decision.deny(SuCodes.AUTH_IDENTITY_CHANGED, snapshot.rev)
        }
        if (!record.matches(identity)) return Decision.deny(SuCodes.AUTH_IDENTITY_CHANGED, snapshot.rev)
        return Decision.allow(snapshot.rev, record)
    }

    /** The same decision, with the caller's complete installed package set cross-checked. */
    @JvmStatic
    fun decide(
        snapshot: AuthorizationSnapshot,
        callerUid: Int,
        identity: ApplicationIdentity?,
        packagesForUid: List<String>?,
    ): Decision {
        val decision = decide(snapshot, callerUid, identity)
        if (!decision.allowed) return decision
        if (packagesForUid != null) {
            if (packagesForUid.isEmpty()) {
                return Decision.deny(SuCodes.SU_CALLER_PACKAGE_UNAVAILABLE, snapshot.rev)
            }
            val names = packagesForUid.toSet()
            if (names.size != packagesForUid.size || names.any { it.isBlank() }) {
                return Decision.deny(SuCodes.SU_CALLER_PACKAGE_AMBIGUOUS, snapshot.rev)
            }
            if (names != identity?.members?.keys) {
                return Decision.deny(SuCodes.AUTH_IDENTITY_CHANGED, snapshot.rev)
            }
        }
        return decision
    }

    /**
     * The Manager's own uid: no grant record is needed, but the master switch still
     * applies (design §2.3). An unreadable store denies here too.
     */
    @JvmStatic
    fun decideSelf(snapshot: AuthorizationSnapshot): Decision = when {
        snapshot.corrupt -> Decision.deny(SuCodes.AUTH_NOT_GRANTED, snapshot.rev)
        !snapshot.enabled -> Decision.deny(SuCodes.AUTH_DISABLED, snapshot.rev)
        else -> Decision.allow(snapshot.rev)
    }

    /** [decide] as the `SecurityException` shape `AuthorizationPolicy` has always thrown. */
    @JvmStatic
    fun require(
        snapshot: AuthorizationSnapshot,
        callerUid: Int,
        identity: ApplicationIdentity?,
    ): ApplicationIdentity {
        val decision = decide(snapshot, callerUid, identity)
        if (!decision.allowed) throw SecurityException(decision.reason)
        return requireNotNull(identity) { SuCodes.AUTH_IDENTITY_CHANGED }
    }
}
