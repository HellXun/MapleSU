package dev.maplesu.manager.workmode.superuser

import dev.maplesu.manager.workmode.Hashes
import dev.maplesu.manager.workmode.control.schema.GrantIntent

/**
 * 身份裁决与粒度展开（纯函数，主机可测）。
 *
 * 三条粒度规则：
 * 1. `A_ULTRA` 是包名后端：同 uid 多包必须 all-or-none，任一包 deny / 缺省 → 该 uid 整体不 allow；
 * 2. 多用户按 userId 分开，禁止把 user 0 的决策套到 user 10；
 * 3. `A_DIRECT` 是 uid 后端且 `ApplicationIdentity` 要求“该 uid 恰好一个包”，多包 uid 无法无损表达 → null，
 *    **不得**降级成任选一个包。
 */
enum class IdentityVerdict {
    MATCH,
    ROTATED_SOFT,
    STALE_HARD,
    UNKNOWN,
}

data class ExpandedGrant(
    val target: BackendId,
    val uid: Int,
    val packageName: String,
    val allow: Boolean,
    val degraded: String? = null,
)

/** 管理器自身 / root / 系统 / shell uid 永不写入共享层，也永不被 apply 撤销（避免自锁）。 */
object ProtectedIdentities {
    const val MANAGER_PACKAGE = "dev.maplesu.manager"
    private val PROTECTED_PACKAGES = setOf("android", MANAGER_PACKAGE, "com.android.shell")

    fun isProtected(uid: Int, packageName: String): Boolean =
        uid < 10_000 || packageName in PROTECTED_PACKAGES

    fun isProtected(uid: Int): Boolean = uid < 10_000
}

object IdentityRules {
    const val MULTI_PACKAGE_UID_MARKER = "A_ULTRA:multi-package-uid"
    const val ALL_OR_NONE_MARKER = "A_ULTRA:all-or-none"

    fun userIdOf(uid: Int): Int = uid / 100_000

    /** sha256(apkPath) 前 32 hex；共享层不落原始路径。 */
    fun apkPathHash(apkPath: String): String = Hashes.shortSha256(apkPath, 32)

    fun snapshotOf(
        uid: Int,
        packageName: String,
        signers: Collection<String>,
        firstInstallTime: Long,
        lastUpdateTime: Long,
        apkPath: String,
    ): IdentitySnapshot = IdentitySnapshot(
        uid = uid,
        packageName = packageName,
        signerSha256 = signers.sorted(),
        firstInstallTime = firstInstallTime,
        lastUpdateTime = lastUpdateTime,
        apkPathHash = apkPathHash(apkPath),
    )

    /**
     * `MATCH` 要求 uid+包名+签名集合+安装时间+APK 路径全一致；同签名正常升级（仅有
     * lastUpdateTime / apkPathHash 变化）是 `ROTATED_SOFT`：保留意图，但 A 后端不自动放行。
     */
    fun verdict(intent: GrantIntent, current: IdentitySnapshot?): IdentityVerdict {
        if (current == null) return IdentityVerdict.UNKNOWN
        if (current.uid != intent.uid || current.userId != intent.userId ||
            current.packageName != intent.packageName) {
            return IdentityVerdict.STALE_HARD
        }
        if (current.signerSha256.sorted() != intent.signerSha256.sorted()) {
            return IdentityVerdict.STALE_HARD
        }
        if (current.firstInstallTime != intent.firstInstallTime) {
            return IdentityVerdict.STALE_HARD
        }
        return if (current.lastUpdateTime == intent.lastUpdateTime &&
            current.apkPathHash == intent.apkPathHash
        ) IdentityVerdict.MATCH else IdentityVerdict.ROTATED_SOFT
    }

    /** 单条意图朝目标后端展开；null = 无法无损表达（多包 uid / 已卸载 / 受保护身份）。 */
    fun expand(intent: GrantIntent, target: BackendId, packagesForUid: List<String>): ExpandedGrant? {
        if (ProtectedIdentities.isProtected(intent.uid, intent.packageName)) return null
        val allow = intent.decision == GrantIntent.ALLOW
        return when (target) {
            BackendId.A_DIRECT ->
                if (packagesForUid.size != 1 || packagesForUid[0] != intent.packageName) null
                else ExpandedGrant(target, intent.uid, intent.packageName, allow)
            BackendId.A_ULTRA -> ExpandedGrant(
                target, intent.uid, intent.packageName, allow,
                if (packagesForUid.size > 1) MULTI_PACKAGE_UID_MARKER else null,
            )
        }
    }

    /**
     * 批量展开（apply 计划用）。`packagesForUid` 来自后端枚举（PackageManager），未知时
     * A_ULTRA 只撤回显式 deny，不凭空 allow——all-or-none 不能靠猜。
     */
    fun expandAll(
        entries: List<GrantIntent>,
        target: BackendId,
        packagesForUid: Map<Int, List<String>>,
    ): List<ExpandedGrant> {
        val result = ArrayList<ExpandedGrant>()
        val byUid = entries
            .filterNot { ProtectedIdentities.isProtected(it.uid, it.packageName) }
            .groupBy { it.uid }
            .toSortedMap()
        for ((uid, intents) in byUid) {
            val packages = packagesForUid[uid].orEmpty().distinct().sorted()
            when (target) {
                BackendId.A_DIRECT -> {
                    val intent = intents.singleOrNull() ?: continue
                    expand(intent, target, packages)?.let(result::add)
                }
                BackendId.A_ULTRA -> {
                    if (packages.isEmpty()) {
                        for (intent in intents) {
                            if (intent.decision == GrantIntent.DENY) {
                                result.add(ExpandedGrant(target, uid, intent.packageName, false))
                            }
                        }
                        continue
                    }
                    val allowed = intents.filter { it.decision == GrantIntent.ALLOW }
                        .map { it.packageName }.toSet()
                    if (packages.size > 1) {
                        val allAllowed = allowed.containsAll(packages)
                        val allowWholeUid = allAllowed
                        for (name in packages) {
                            result.add(
                                ExpandedGrant(
                                    target, uid, name, allowWholeUid,
                                    if (allowWholeUid) MULTI_PACKAGE_UID_MARKER else ALL_OR_NONE_MARKER,
                                ),
                            )
                        }
                    } else {
                        val name = packages.single()
                        val intent = intents.firstOrNull { it.packageName == name } ?: continue
                        result.add(ExpandedGrant(target, uid, name, intent.decision == GrantIntent.ALLOW))
                    }
                }
            }
        }
        return result
    }
}
