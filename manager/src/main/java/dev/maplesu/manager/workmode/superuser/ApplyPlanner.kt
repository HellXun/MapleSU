package dev.maplesu.manager.workmode.superuser

import dev.maplesu.manager.workmode.control.schema.GrantIntent
import dev.maplesu.manager.workmode.control.schema.SharedSuperuserFile
import dev.maplesu.manager.workmode.control.schema.coversWatermark

/**
 * apply 计划（01 §6.5 / §6.6，纯函数）：
 * - **先清墓碑/deny，再 apply allow**（G1）；revoke 与 grant 冲突时 revoke 胜；
 * - `applied[backend].rev` 低于墓碑 → 强制重放 revoke（G3，断电丢 revoke 的兜底）；
 * - `identityStale`（STALE_HARD）与 A 侧 `identity:rotated-soft` 不自动兑现，只计数
 *   （禁止为了“看起来没丢”在 A 侧自动 re-grant，01 §6.4 裁决）；
 * - 受保护身份（管理器/root/系统/shell）永不进入计划。
 */
data class PlannedApply(
    val revokes: List<GrantView>,
    val grants: List<GrantView>,
    val skippedIdentityStale: Int,
    val degraded: List<String>,
) {
    /** 后端 apply 输入顺序：先 revoke 后 grant。 */
    val desired: List<GrantView> get() = revokes + grants

    val total: Int get() = revokes.size + grants.size

    companion object {
        fun granularityOf(target: BackendId): Granularity = when (target) {
            BackendId.A_DIRECT -> Granularity.UID
            BackendId.A_ULTRA -> Granularity.PACKAGE
        }
    }
}

object ApplyPlanner {
    fun plan(
        shared: SharedSuperuserFile,
        target: BackendId,
        packagesForUid: (Int) -> List<String>,
    ): PlannedApply {
        val revokes = LinkedHashMap<String, GrantView>()
        val grants = LinkedHashMap<String, GrantView>()
        val degraded = LinkedHashSet<String>()
        var skipped = 0
        for (intent in shared.entries) {
            if (ProtectedIdentities.isProtected(intent.uid, intent.packageName)) continue
            if (intent.decision == GrantIntent.DENY) {
                revokes[key(intent.uid, intent.packageName)] = view(target, intent.uid, intent.packageName, false)
                continue
            }
            val rotated = intent.degraded.contains(GrantMerge.ROTATED_MARKER)
            if (intent.identityStale || rotated) {
                // A_DIRECT/A_ULTRA：身份待确认不自动放行；B_KSU 可兑现（内核按当前包/uid 裁决）。
                skipped++
                continue
            }
            val expanded = IdentityRules.expandAll(
                listOf(intent), target, mapOf(intent.uid to packagesForUid(intent.uid)),
            )
            if (expanded.isEmpty()) {
                skipped++
                continue
            }
            for (grant in expanded) {
                grant.degraded?.let(degraded::add)
                val view = view(target, grant.uid, grant.packageName, grant.allow)
                if (grant.allow) grants[key(grant.uid, grant.packageName)] = view
                else revokes[key(grant.uid, grant.packageName)] = view
            }
        }
        // G3：墓碑强制 revoke；唯一例外是 shared 里存在晚于墓碑的 allow 决策（G1 冲突规则的“新胜旧”）。
        for (tomb in shared.tombs) {
            if (ProtectedIdentities.isProtected(tomb.uid, tomb.packageName)) continue
            val entry = shared.entryFor(tomb.userId, tomb.uid, tomb.packageName)
            val newerAllow = entry != null && entry.decision == GrantIntent.ALLOW &&
                entry.decidedAt > tomb.revokedAt
            if (newerAllow) continue
            val mustReplayRevoke = tomb.coversWatermark(target.wire, entry?.applied ?: emptyList())
            if (mustReplayRevoke || entry == null || entry.decision == GrantIntent.DENY) {
                revokes[key(tomb.uid, tomb.packageName)] =
                    view(target, tomb.uid, tomb.packageName, false)
            }
        }
        // 冲突：revoke 胜（先清后放）。
        val grantList = grants.values.filterNot { revokes.containsKey(key(it.uid, it.packageName)) }
        return PlannedApply(
            revokes = revokes.values.toList(),
            grants = grantList,
            skippedIdentityStale = skipped,
            degraded = degraded.toList(),
        )
    }

    private fun view(target: BackendId, uid: Int, packageName: String, allow: Boolean) =
        GrantView(uid, packageName, allow, PlannedApply.granularityOf(target), null)

    private fun key(uid: Int, packageName: String) = "$uid/$packageName"
}
