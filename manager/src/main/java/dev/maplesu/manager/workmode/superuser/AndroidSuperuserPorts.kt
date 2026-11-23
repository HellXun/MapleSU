package dev.maplesu.manager.workmode.superuser

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import dev.maplesu.manager.runtime.authorization.AndroidApplicationIdentities
import dev.maplesu.manager.runtime.authorization.ApplicationIdentity
import dev.maplesu.manager.runtime.authorization.DirectPolicy
import dev.maplesu.manager.runtime.authorization.SuperuserControl
import dev.maplesu.manager.runtime.ultra.UltraAuthorization

/**
 * 生产端口：只做“Android 事实 → 纯接口”的适配，授权裁决仍由各后端/策略完成。
 * 这些类不可主机单测（只编译）；纯逻辑在 `DirectSuperuserBackend` / `UltraSuperuserBackend` /
 * `KsuSuperuserBackend`，主机用假端口覆盖。
 */
private fun ApplicationIdentity.toIdentitySnapshot(): IdentitySnapshot = IdentityRules.snapshotOf(
    uid = uid,
    packageName = packageName,
    signers = signers,
    firstInstallTime = firstInstallTime,
    lastUpdateTime = lastUpdateTime,
    apkPath = apkPath,
)

/** `A_DIRECT` 生产端口：一次性 root 工具（`DirectPolicy`）+ PackageManager 身份。 */
class AndroidDirectSuperuserPort(context: Context) : DirectSuperuserPort {
    private val app = context.applicationContext
    private val identities = AndroidApplicationIdentities(app)

    override fun enabled(): Boolean = DirectPolicy.isEnabled(app) ?: false

    /** 持久记录集合；是否仍然有效由 `hasCurrentGrant` 逐 uid 复核（G2）。 */
    override fun grantedUids(): Set<Int> = DirectPolicy.grantedUids(app) ?: emptySet()

    override fun hasCurrentGrant(uid: Int): Boolean? = DirectPolicy.hasGrantStrict(app, uid)

    override fun identity(uid: Int): IdentitySnapshot? = identities.read(uid)?.toIdentitySnapshot()

    override fun grant(uid: Int): Boolean = DirectPolicy.grant(app, uid)

    override fun revoke(uid: Int): Boolean = DirectPolicy.revoke(app, uid)
}

/** `A_ULTRA`: the sole durable authority writes; native full-UID state confirms delivery. */
class AndroidUltraSuperuserPort(context: Context) : UltraSuperuserPort {
    private val app = context.applicationContext
    private val manager: PackageManager = app.packageManager
    private var currentIdentities: Map<Int, ApplicationIdentity> = emptyMap()

    override fun isActive(): Boolean = UltraAuthorization.isActive(app)

    override val supportsUidProfiles: Boolean = true

    override fun uidProfiles(): Map<Int, dev.maplesu.manager.runtime.ultra.SuUidProfile>? {
        currentIdentities = emptyMap()
        val observed = SuperuserControl.currentIdentities(app) ?: return null
        if (!UltraAuthorization.refresh(app)) return null
        currentIdentities = observed.grants
        return UltraAuthorization.uidProfiles()
    }

    override fun setUidAllowed(uid: Int, packageName: String, allowed: Boolean): Boolean = runCatching {
        if (allowed && packageName !in packagesForUid(uid)) return@runCatching false
        val policy = dev.maplesu.manager.runtime.authorization.ManagerAuthorizations.policy(app)
        if (allowed) policy.grant(uid) else policy.revoke(uid)
        true
    }.getOrDefault(false)

    override fun grantedPackages(): Set<String> = UltraAuthorization.grantedPackages()

    override fun isGranted(packageName: String): Boolean =
        uidForPackage(packageName)?.let(UltraAuthorization::isGranted) == true

    override fun uidForPackage(packageName: String): Int? = runCatching {
        manager.getApplicationInfo(packageName, 0).uid
    }.getOrNull()

    override fun identity(packageName: String, uid: Int): IdentitySnapshot? =
        currentIdentities[uid]?.members?.get(packageName)?.let { member ->
            IdentityRules.snapshotOf(uid, packageName, member.signers, member.firstInstallTime,
                member.lastUpdateTime, member.apkPath)
        }

    override fun packagesForUid(uid: Int): List<String> =
        currentIdentities[uid]?.members?.keys?.toList()
            ?: manager.getPackagesForUid(uid)?.toList().orEmpty()

    override fun grant(packageName: String): Boolean = UltraAuthorization.grant(app, packageName)

    override fun revoke(packageName: String): Boolean = UltraAuthorization.revoke(app, packageName)
}

