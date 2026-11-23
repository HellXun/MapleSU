package dev.maplesu.manager.workmode.superuser

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.control.schema.Tomb

/**
 * 共享超级用户授权层的后端契约。
 *
 * 共享层只存**意图**；执行真相永远在各后端。`enumerate()` 必须按**当前身份裁决**后的集合返回，
 * 不得导出“已失效但仍存记录”的项（E5 / G2）。
 */
enum class BackendId(val wire: String, val mode: WorkMode) {
    A_DIRECT("A_DIRECT", WorkMode.LITE),
    A_ULTRA("A_ULTRA", WorkMode.LITE);

    companion object {
        /** 某模式下允许执行的后端（A：Direct/Ultra；B：KSU）。 */
        fun relevant(mode: WorkMode): List<BackendId> = when (mode) {
            WorkMode.LITE -> listOf(A_DIRECT, A_ULTRA)
        }

        fun fromWire(value: String?): BackendId? = entries.firstOrNull { it.wire == value?.let { name -> dev.maplesu.manager.compat.StoredInputs.backend(name) } }
    }
}

enum class Granularity { UID, PACKAGE, UID_PACKAGE_PROFILE }

/** 后端枚举时读到的当前身份（绝不来自客户端）。 */
data class IdentitySnapshot(
    val uid: Int,
    val packageName: String,
    /** 已排序；多签名 = 全部当前签名。 */
    val signerSha256: List<String>,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    /** sha256(apkPath) 前 32 hex；不落原路径。 */
    val apkPathHash: String,
) {
    /** Android 多用户：full uid 低位为 appId，高位为 userId。 */
    val userId: Int get() = uid / 100_000
}

data class GrantView(
    val uid: Int,
    val packageName: String,
    val allow: Boolean,
    val granularity: Granularity,
    val identity: IdentitySnapshot?,
) {
    val userId: Int get() = uid / 100_000
}

data class ApplyFailure(
    val uid: Int,
    val packageName: String,
    /** "grant" | "revoke"。 */
    val op: String,
    val code: String,
    val detail: String = "",
) {
    val dangerous: Boolean get() = op == ApplyReport.OP_REVOKE
}

data class ApplyReport(
    val ok: List<BackendId>,
    val failed: List<ApplyFailure>,
    val appliedRev: Long,
) {
    val succeeded: Boolean get() = failed.isEmpty()

    companion object {
        fun success(id: BackendId, rev: Long): ApplyReport =
            ApplyReport(ok = listOf(id), failed = emptyList(), appliedRev = rev)

        /** 后端不可用/拒绝：不抛、不静默成功，逐条记失败，供 pending/告警使用。 */
        fun reject(id: BackendId, desired: List<GrantView>, rev: Long, code: String, detail: String): ApplyReport =
            ApplyReport(
                ok = emptyList(),
                failed = desired.map { ApplyFailure(it.uid, it.packageName, opOf(it), code, detail) },
                appliedRev = rev,
            )

        fun opOf(view: GrantView): String = if (view.allow) OP_GRANT else OP_REVOKE

        const val OP_GRANT = "grant"
        const val OP_REVOKE = "revoke"
    }
}

interface SuperuserBackend {
    val id: BackendId

    fun available(): Boolean

    /** 当前后端的 allow 集合；必须按当前身份裁决。 */
    fun enumerate(): List<GrantView>

    /** 幂等 diff：先清 tombstone/deny，再 apply allow；逐条成败。 */
    fun apply(desired: List<GrantView>, tombs: List<Tomb>, rev: Long): ApplyReport

    /** apply 后回读，用于判定 ok（发布红线 8：未通过 verify 的条目不得写 ok=true）。 */
    fun verify(): List<GrantView>

    /** 目标后端粒度展开所需的“同 uid 全部包名”（A_ULTRA all-or-none）。 */
    fun packageNamesForUid(uid: Int): List<String> = emptyList()

    /** 上次 enumerate 中无法表达的内核条目数（B_KSU：非应用 uid/已卸载 uid）。 */
    fun unrepresentableCount(): Int = 0
}

/** 多用户分离：确定性 key（禁止把 user 0 的决策套到 user 10）。 */
data class GrantKey(val userId: Int, val uid: Int, val packageName: String) {
    companion object {
        fun of(intent: dev.maplesu.manager.workmode.control.schema.GrantIntent) =
            GrantKey(intent.userId, intent.uid, intent.packageName)

        fun of(view: GrantView) = GrantKey(view.userId, view.uid, view.packageName)

        fun of(tomb: Tomb) = GrantKey(tomb.userId, tomb.uid, tomb.packageName)
    }
}
