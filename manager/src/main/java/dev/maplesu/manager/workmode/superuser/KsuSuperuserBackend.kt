package dev.maplesu.manager.workmode.superuser

import android.content.Context
import dev.maplesu.manager.runtime.ultra.UltraAuthorization
import dev.maplesu.manager.runtime.ultra.UltraSdkSession
import dev.maplesu.manager.runtime.authorization.UltraAllowlistMirror
import dev.maplesu.manager.runtime.authorization.UltraAllowlistPort
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.control.schema.Tomb

/**
 * 生产装配（供 W04/W08 集成调用）。
 *
 * Ultra-only：Ultra 环境只装 Ultra 后端（Direct 路径在 Ultra 内核会重启系统），
 * 否则装 Direct。上游在这里按模式追加 KSU 后端与内核 allowlist 镜像；
 * KSU 后端删除后两者一并消失。
 */
fun productionSuperuserBackends(
    context: Context,
    mode: WorkMode,
): List<SuperuserBackend> = listOf(
    if (UltraAuthorization.isActive(context)) {
        UltraSuperuserBackend(AndroidUltraSuperuserPort(context))
    } else {
        DirectSuperuserBackend(AndroidDirectSuperuserPort(context))
    },
)


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
            (UltraSdkSession.current(context) != null ||
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
