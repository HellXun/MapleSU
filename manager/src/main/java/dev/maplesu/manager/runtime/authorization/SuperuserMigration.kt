package dev.maplesu.manager.runtime.authorization

/**
 * One-shot import of the legacy sources into the one authority (design §3.1).
 *
 * <p>The legacy stores are read **only** here and **only** while their watermark is
 * absent from the journal. After the import they stay on disk untouched (rollback and
 * reconciliation material) but no decision path reads them again - which is what stops a
 * fifth authority from reappearing.
 *
 * <p>Three sources, three shapes:
 *  - legacy lease grants (uid list, app-private): uid -> identity;
 *  - `ultra` (`UltraAuthorization` SDK `su_auth`, package names): package -> uid -> identity;
 *  - `ksu` (kernel allowlist, package names): package -> uid -> identity.
 *
 * <p>An entry that cannot be mapped to exactly one current installation is **not** a
 * grant. It is recorded in `unresolved` with a reason, it stays denied (default deny),
 * and the UI can ask the user to confirm it again. There is deliberately no
 * "best effort" mapping: a wrong uid would hand root to the wrong application.
 */
data class SuperuserMigrationPlan(
    /** Legacy source ids this plan imports; each becomes a `migrated` watermark. */
    val sources: List<String>,
    val grants: List<ApplicationIdentity>,
    val unresolved: List<SuperuserUnresolved>,
) {
    val empty: Boolean get() = sources.isEmpty() && grants.isEmpty() && unresolved.isEmpty()
}

/** What one legacy store currently says, already reduced to uids and package names. */
data class SuperuserLegacySource(
    val id: String,
    val uids: Set<Int> = emptySet(),
    val packages: Set<String> = emptySet(),
)

object SuperuserMigration {
    const val SOURCE_JAILBREAK = "jailbreak"
    const val SOURCE_ULTRA = "ultra"
    const val SOURCE_KSU = "ksu"

    const val REASON_UID_UNRESOLVED = "uid-unresolved"
    const val REASON_PACKAGE_UNRESOLVED = "package-unresolved"
    const val REASON_IDENTITY_UNAVAILABLE = "identity-unavailable"
    const val REASON_SHARED_UID_CONFIRMATION = "shared-uid-needs-confirmation"

    /**
     * Builds the import plan for every source that the journal has not marked yet.
     *
     * @param identityOfUid the service-side identity snapshot; null means "no package,
     *   several packages, or not installed" - all of which are unresolved, never grants.
     * @param uidOfPackage resolved through PackageManager; null when the package is gone.
     */
    @JvmStatic
    @JvmOverloads
    fun plan(
        journal: SuperuserJournal,
        sources: List<SuperuserLegacySource>,
        identityOfUid: (Int) -> ApplicationIdentity?,
        uidOfPackage: (String) -> Int?,
        now: Long = System.currentTimeMillis(),
    ): SuperuserMigrationPlan {
        val pending = sources.filter { !journal.migrated.containsKey(it.id) }
        if (pending.isEmpty()) {
            return SuperuserMigrationPlan(emptyList(), emptyList(), emptyList())
        }
        val grants = LinkedHashMap<Int, ApplicationIdentity>()
        val unresolved = ArrayList<SuperuserUnresolved>()
        for (source in pending) {
            for (uid in source.uids) {
                val identity = identityOfUid(uid)
                if (identity == null || identity.members.size != 1) {
                    unresolved += SuperuserUnresolved(
                        source = source.id, uid = uid, packageName = null,
                        reason = if (identity == null) REASON_IDENTITY_UNAVAILABLE else REASON_SHARED_UID_CONFIRMATION, at = now,
                    )
                } else {
                    // The last source wins by uid only when the identity is identical;
                    // a conflicting identity is unresolved instead of silently picked.
                    val existing = grants[identity.uid]
                    if (existing == null || existing == identity) {
                        grants[identity.uid] = identity
                    } else {
                        unresolved += SuperuserUnresolved(
                            source = source.id, uid = uid, packageName = identity.packageName,
                            reason = REASON_UID_UNRESOLVED, at = now,
                        )
                    }
                }
            }
            for (packageName in source.packages) {
                val uid = uidOfPackage(packageName)
                if (uid == null) {
                    unresolved += SuperuserUnresolved(
                        source = source.id, uid = null, packageName = packageName,
                        reason = REASON_PACKAGE_UNRESOLVED, at = now,
                    )
                    continue
                }
                val identity = identityOfUid(uid)
                if (identity == null || identity.members.size != 1 || identity.packageName != packageName) {
                    unresolved += SuperuserUnresolved(
                        source = source.id, uid = uid, packageName = packageName,
                        reason = if (identity != null && identity.members.size != 1) REASON_SHARED_UID_CONFIRMATION else REASON_IDENTITY_UNAVAILABLE, at = now,
                    )
                } else {
                    grants[identity.uid] = identity
                }
            }
        }
        return SuperuserMigrationPlan(
            sources = pending.map { it.id },
            grants = grants.values.toList(),
            unresolved = unresolved,
        )
    }
}
