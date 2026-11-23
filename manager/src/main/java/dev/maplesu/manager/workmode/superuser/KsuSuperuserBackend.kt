package dev.maplesu.manager.workmode.superuser

import android.content.Context
import dev.maplesu.manager.ksu.KsuBackends
import dev.maplesu.manager.ksu.KsuSuperuserAdapter
import dev.maplesu.manager.runtime.ultra.UltraAuthorization
import dev.maplesu.manager.runtime.HookRootExecutor
import dev.maplesu.manager.runtime.ultra.UltraSdkSession
import dev.maplesu.manager.runtime.authorization.UltraAllowlistMirror
import dev.maplesu.manager.runtime.authorization.UltraAllowlistPort
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.control.schema.Tomb

/**
 * `B_KSU` 后端的可注入端口。
 *
 * 内核 allowlist **没有可枚举 API**（上游 JNI 只回数量）：`enumerate()` 只能
 * “PackageManager 全量 × getAppProfile → allowSu”，非应用 uid / 已卸载 uid 记为
 * `unrepresentableCount`，**不假装完整**（U2 保守语义）。写入只改 `allowSu`，其它 profile
 * 字段由适配器回读原样保留。
 */
interface KsuSuperuserPort {
    fun available(): Boolean
    fun count(): Int
    /** 当前 profile 的 allowSu；null = 不可读/不可裁决（不能当作“用户拒绝过”）。 */
    fun allow(packageName: String, uid: Int): Boolean?
    fun setAllow(packageName: String, uid: Int, allow: Boolean): Boolean
    fun installedApplications(): List<KsuInstalledApp>
    fun identity(packageName: String, uid: Int): IdentitySnapshot?
    fun packagesForUid(uid: Int): List<String>
}

data class KsuInstalledApp(val packageName: String, val uid: Int, val identity: IdentitySnapshot?)

class KsuSuperuserBackend(private val port: KsuSuperuserPort) : SuperuserBackend {
    override val id: BackendId = BackendId.B_KSU
    private var unrepresentable = 0

    override fun available(): Boolean = runCatching { port.available() }.getOrDefault(false)

    override fun enumerate(): List<GrantView> {
        if (!available()) return emptyList()
        val views = ArrayList<GrantView>()
        for (app in port.installedApplications()) {
            if (ProtectedIdentities.isProtected(app.uid, app.packageName)) continue
            val allow = try {
                port.allow(app.packageName, app.uid)
            } catch (_: Exception) {
                null
            }
            if (allow != true) continue
            val identity = app.identity ?: port.identity(app.packageName, app.uid) ?: continue
            views.add(GrantView(app.uid, app.packageName, true, Granularity.UID_PACKAGE_PROFILE, identity))
        }
        unrepresentable = runCatching { (port.count() - views.size).coerceAtLeast(0) }.getOrDefault(0)
        return views
    }

    override fun apply(desired: List<GrantView>, tombs: List<Tomb>, rev: Long): ApplyReport {
        if (!available()) {
            return ApplyReport.reject(id, desired, rev, "KSU_UNAVAILABLE", "ksu native unavailable")
        }
        val failures = ArrayList<ApplyFailure>()
        val revokes = LinkedHashSet<Pair<String, Int>>()
        for (tomb in tombs) {
            if (ProtectedIdentities.isProtected(tomb.uid, tomb.packageName)) continue
            revokes.add(tomb.packageName to tomb.uid)
        }
        for (view in desired) {
            if (!view.allow && !ProtectedIdentities.isProtected(view.uid, view.packageName)) {
                revokes.add(view.packageName to view.uid)
            }
        }
        // G1：先清墓碑/deny，再 apply allow。
        for ((packageName, uid) in revokes) {
            if (port.allow(packageName, uid) != true) continue
            if (!port.setAllow(packageName, uid, false)) {
                failures.add(
                    ApplyFailure(uid, packageName, ApplyReport.OP_REVOKE, "KSU_APPLY_REJECTED"),
                )
            }
        }
        val grants = LinkedHashSet<Pair<String, Int>>()
        for (view in desired) {
            if (view.allow && !ProtectedIdentities.isProtected(view.uid, view.packageName)) {
                grants.add(view.packageName to view.uid)
            }
        }
        for ((packageName, uid) in grants) {
            if (port.allow(packageName, uid) == true) continue
            if (!port.setAllow(packageName, uid, true)) {
                failures.add(ApplyFailure(uid, packageName, ApplyReport.OP_GRANT, "KSU_APPLY_REJECTED"))
            }
        }
        return if (failures.isEmpty()) ApplyReport.success(id, rev)
        else ApplyReport(emptyList(), failures, rev)
    }

    override fun verify(): List<GrantView> = enumerate()

    override fun packageNamesForUid(uid: Int): List<String> = port.packagesForUid(uid)

    override fun unrepresentableCount(): Int = unrepresentable
}

/** 身份来源：PackageManager 侧的全量应用 + 身份快照（Android 实现见 AndroidSuperuserPorts.kt）。 */
interface KsuIdentitySource {
    fun installedApplications(): List<KsuInstalledApp>
    fun identity(packageName: String, uid: Int): IdentitySnapshot?
}

/**
 * 现有 `KsuSuperuserAdapter` 到 [KsuSuperuserPort] 的唯一桥（本文件在
 * `KsuSourceIsolationTest` 的 import 白名单内）。`available()==false` 时全部写返回 false，
 * 由 [KsuSuperuserBackend] 安全降级为逐条失败，不伪造成功。
 */
class AdapterKsuSuperuserPort(
    private val adapter: KsuSuperuserAdapter,
    private val identities: KsuIdentitySource,
) : KsuSuperuserPort {
    override fun available(): Boolean = adapter.available()

    override fun count(): Int = adapter.count()

    override fun allow(packageName: String, uid: Int): Boolean? =
        adapter.getProfile(packageName, uid)?.allowSu

    override fun setAllow(packageName: String, uid: Int, allow: Boolean): Boolean =
        adapter.setAllow(packageName, uid, allow)

    override fun installedApplications(): List<KsuInstalledApp> = identities.installedApplications()

    override fun identity(packageName: String, uid: Int): IdentitySnapshot? =
        identities.identity(packageName, uid)

    override fun packagesForUid(uid: Int): List<String> =
        identities.installedApplications().filter { it.uid == uid }.map { it.packageName }
}

/** native/认主不可用时的安全替身：不抛、不写、不伪造。 */
class UnavailableKsuSuperuserPort(private val reason: String) : KsuSuperuserPort {
    override fun available(): Boolean = false
    override fun count(): Int = 0
    override fun allow(packageName: String, uid: Int): Boolean? = null
    override fun setAllow(packageName: String, uid: Int, allow: Boolean): Boolean = false
    override fun installedApplications(): List<KsuInstalledApp> = emptyList()
    override fun identity(packageName: String, uid: Int): IdentitySnapshot? = null
    override fun packagesForUid(uid: Int): List<String> = emptyList()
}

fun ksuSuperuserBackend(adapter: KsuSuperuserAdapter, identities: KsuIdentitySource): SuperuserBackend =
    KsuSuperuserBackend(AdapterKsuSuperuserPort(adapter, identities))

/**
 * 生产装配（供 W04/W08 集成调用）：Ultra 环境只装 Ultra 后端（Direct 路径在 Ultra 内核会重启系统）；
 * 否则装 Direct；KSU 适配器由 W11 注入。
 */
fun productionSuperuserBackends(
    context: Context,
    mode: WorkMode,
    ksuAdapter: KsuSuperuserAdapter?,
    ksuIdentities: KsuIdentitySource?,
): List<SuperuserBackend> {
    return modeSuperuserBackends(mode, lite = {
        if (UltraAuthorization.isActive(context)) {
            UltraSuperuserBackend(AndroidUltraSuperuserPort(context))
        } else {
            DirectSuperuserBackend(AndroidDirectSuperuserPort(context))
        }
    }, ksu = {
        if (ksuAdapter != null && ksuIdentities != null) {
            ksuSuperuserBackend(ksuAdapter, ksuIdentities)
        } else {
            KsuSuperuserBackend(UnavailableKsuSuperuserPort("no ksu adapter wired"))
        }
    })
}

/**
 * The kernel-side mirror of the Manager's decision (design §3.4, option A).
 *
 * <p>It is a *mirror*, never an authority: the decision is already written to the
 * Manager's store and enforced by the restricted entries before this runs. The mirror's
 * job is that an application which only ever `exec`s `su` keeps working in Normal mode -
 * the KSU kernel's `sucompat` consults the allowlist, so the allowlist has to follow the
 * Manager's list.
 *
 * <p>Failure is not rolled back into the decision. It becomes `applied.ok=false`
 * (`PENDING_APPLY`): the UI says "已授权·待同步", [dev.maplesu.manager.runtime.authorization.
 * SuperuserControl.reconcilePending] retries, and the boot receiver retries once per boot.
 */
class KsuAllowlistMirror(
    private val adapter: KsuSuperuserAdapter,
    private val identities: KsuIdentitySource,
) : dev.maplesu.manager.runtime.authorization.SuperuserMirror {
    override val backend: String = BACKEND

    private val delegate by lazy { KsuSuperuserBackend(AdapterKsuSuperuserPort(adapter, identities)) }

    override fun apply(
        snapshot: dev.maplesu.manager.runtime.authorization.AuthorizationSnapshot,
    ): dev.maplesu.manager.runtime.authorization.MirrorOutcome {
        if (!runCatching { delegate.available() }.getOrDefault(false)) {
            return dev.maplesu.manager.runtime.authorization.MirrorOutcome.failure("KSU_UNAVAILABLE")
        }
        val desired = snapshot.grants.values.map { identity ->
            GrantView(
                uid = identity.uid,
                packageName = identity.packageName,
                allow = true,
                granularity = Granularity.UID_PACKAGE_PROFILE,
                identity = IdentityRules.snapshotOf(
                    identity.uid, identity.packageName, identity.signers,
                    identity.firstInstallTime, identity.lastUpdateTime, identity.apkPath,
                ),
            )
        }
        val tombs = snapshot.tombs.map { tomb ->
            Tomb(
                uid = tomb.uid,
                userId = tomb.uid / 100_000,
                packageName = tomb.packageName,
                signerSha256 = emptyList(),
                revokedAt = tomb.at,
                rev = tomb.rev,
            )
        }
        val report = runCatching { delegate.apply(desired, tombs, snapshot.rev) }
            .getOrElse { error ->
                return dev.maplesu.manager.runtime.authorization.MirrorOutcome.failure(
                    error.javaClass.simpleName + ":" + error.message,
                )
            }
        return if (report.succeeded) {
            dev.maplesu.manager.runtime.authorization.MirrorOutcome.success()
        } else {
            dev.maplesu.manager.runtime.authorization.MirrorOutcome.failure(
                report.failed.joinToString(";") { it.code + ":" + it.packageName }.take(240),
            )
        }
    }

    companion object {
        const val BACKEND = "KSU_ALLOWLIST"
    }
}

/**
 * 生产镜像装配。
 *
 * <p>No adapter (native unavailable / not the manager / Lite mode) means
 * [dev.maplesu.manager.runtime.authorization.NoSuperuserMirror]: nothing to mirror and
 * nothing to retry, rather than a permanent `PENDING_APPLY` on a mode that has no kernel
 * allowlist at all.
 */
object SuperuserMirrors {
    @JvmStatic
    fun current(context: Context): dev.maplesu.manager.runtime.authorization.SuperuserMirror {
        // Ultra-only：Normal（KSU）允许列表镜像已移除，只保留 Ultra 授权镜像。
        val ultraActive = UltraAuthorization.isActive(context) &&
            (HookRootExecutor.production(context).available() || UltraSdkSession.current(context) != null ||
                dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe.currentSnapshot(context).let {
                    it.managerOwnerIdentity != null && it.kernelRootVerified
                })
        return if (ultraActive) ultraMirror(context, null)
        else dev.maplesu.manager.runtime.authorization.NoSuperuserMirror
    }

    internal fun forSdkScope(context: Context, scope: UltraAuthorization.Scope):
        dev.maplesu.manager.runtime.authorization.SuperuserMirror = ultraMirror(context, scope)

    private fun ultraMirror(context: Context, scope: UltraAuthorization.Scope?):
        dev.maplesu.manager.runtime.authorization.SuperuserMirror {
        val activeScope = scope ?: UltraAuthorization.captureScope(context)
        fun current() = activeScope?.current?.invoke() == true
        val catalog by lazy {
            if (!current()) null else {
                val result = dev.maplesu.manager.litehidden.UltraEnvRootRunner(
                    activeScope?.client ?: dev.maplesu.manager.runtime.ultra.UltraSdkCredentials.client(context),
                ).run("pm list packages -U --user all")
                if (current() && result.code == 0)
                    dev.maplesu.manager.liteweb.daemon.DaemonSuPackageCatalog.parse(result.output)
                else null
            }
        }
        return UltraAllowlistMirror(object : UltraAllowlistPort {
            override val supportsUidProfiles: Boolean = true
            private var identityRead: dev.maplesu.manager.runtime.authorization.SuperuserControl.CurrentIdentityRead? = null
            private var authorityRevision: Long? = null
            override fun beginIdentityRead(revision: Long): Boolean {
                if (!current()) return false
                val observed = dev.maplesu.manager.runtime.authorization.SuperuserControl.currentIdentities(context, activeScope)
                    ?: return false
                if (observed.rev != revision || !current()) return false
                identityRead = observed
                authorityRevision = revision
                return true
            }
            override fun reconcileUidMapping(): Boolean {
                if (!current()) return false
                val client = activeScope?.client ?: dev.maplesu.manager.runtime.ultra.UltraSdkCredentials.client(context)
                val observed = client.suUidProfiles()
                if (!current() || observed.receipt.sdkErrorCode != -21001) return false
                val receipt = client.refreshSuAuthList()
                val result = receipt.authorizationResult
                return receipt.succeeded && result?.operation == 4L && result.deliveryConfirmed && current()
            }
            override fun uidProfiles(): Map<Int, dev.maplesu.manager.runtime.ultra.SuUidProfile>? {
                if (!current()) return null
                val refreshed = activeScope?.let(UltraAuthorization::refresh) == true
                return if (refreshed && current()) UltraAuthorization.uidProfiles() else null
            }
            override fun setAllowed(uid: Int, packageName: String, allowed: Boolean) =
                // apply(snapshot) runs after the authority decision has committed.
                UltraAuthorization.setUidGranted(context, uid, packageName, allowed, activeScope,
                    durableDecisionCommitted = true, identityRead = identityRead, authorityRevision = authorityRevision)
            override fun packages(): Set<String>? {
                if (!current()) return null
                val refreshed = activeScope?.let(UltraAuthorization::refresh) == true
                if (!refreshed) return null
                return UltraAuthorization.grantedPackages().takeIf { current() }
            }
            override fun identity(uid: Int) = if (current()) identityRead?.grants?.get(uid).takeIf { current() } else null
            override fun uniquelyMapped(packageName: String, uid: Int): Boolean {
                if (!current()) return false
                val mapped = catalog?.filter { it.packageName == packageName }?.singleOrNull()?.uid == uid
                return current() && mapped
            }
            override fun setAllowed(packageName: String, allowed: Boolean) =
                if (activeScope == null) dev.maplesu.manager.runtime.ultra.UltraAuthorizationAttempt.local("SU_AUTH_SCOPE_CHANGED")
                else UltraAuthorization.setGranted(context, packageName, allowed, activeScope)
        }, setOf(context.packageName, "android", "com.android.shell"))
    }
}
