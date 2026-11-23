package dev.maplesu.manager.workmode.superuser

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.control.ControlPlane
import dev.maplesu.manager.workmode.control.ControlRead
import dev.maplesu.manager.workmode.control.ControlWrite
import dev.maplesu.manager.workmode.control.CountFact
import dev.maplesu.manager.workmode.control.EvidenceLine
import dev.maplesu.manager.workmode.control.schema.AppliedMark
import dev.maplesu.manager.workmode.control.schema.GrantIntent
import dev.maplesu.manager.workmode.control.schema.PendingOp
import dev.maplesu.manager.workmode.control.schema.SharedPolicy
import dev.maplesu.manager.workmode.control.schema.SharedSuperuserFile
import dev.maplesu.manager.workmode.detect.Clock
import dev.maplesu.manager.workmode.detect.WallClock

/** `SuperuserSync` 状态机（01 §6.5）。BACKOFF 只读告警；BLOCKED 阻止切换。 */
enum class SyncState { IDLE, EXPORTING, EXPORTED, APPLYING, VERIFIED, PARTIAL_PENDING, BACKOFF, BLOCKED }

data class SyncStatus(
    val state: SyncState,
    val revision: Long,
    val entries: Int,
    val pending: Int,
    val tombs: Int,
    val lastError: String? = null,
)

sealed interface ExportResult {
    data class Ok(
        val revision: Long,
        val allowed: Int,
        val denied: Int,
        val tombs: Int,
    ) : ExportResult

    /** 必须阻止切换：绝不用空表覆盖（主文档:362）。 */
    data class Blocked(val code: String, val detail: String) : ExportResult
}

data class ReconcileResult(
    val applied: Int,
    val failed: Int,
    val skippedIdentityStale: Int,
    val dangerousRevokeFailures: List<ApplyFailure>,
    val state: SyncState,
)

interface SuperuserSync {
    /** SNAPSHOT 前导出：逐后端 enumerate → merge → 写 shared(revision+1, .prev)。 */
    suspend fun exportBeforeSwitch(from: WorkMode, rev: Long): ExportResult

    /** 目标 boot 后兑现：先清墓碑再 apply，verify 不过不得写 ok=true（发布红线 8）。 */
    suspend fun reconcileAfterBoot(mode: WorkMode): ReconcileResult

    fun status(): SyncStatus
}

/**
 * 默认实现：由 `ControlPlane`（设备侧 root 适配器 / 主机临时目录）持久化，
 * 后端由 W11/W08 注入。所有 Android 行为都在后端端口内，本类纯 JVM 可测。
 */
class DefaultSuperuserSync(
    private val control: ControlPlane,
    private val backends: List<SuperuserBackend>,
    private val clock: Clock = WallClock,
    private val maxAttempts: Int = 3,
) : SuperuserSync {
    @Volatile
    private var status = SyncStatus(SyncState.IDLE, 0, 0, 0, 0)

    override fun status(): SyncStatus = status

    override suspend fun exportBeforeSwitch(from: WorkMode, rev: Long): ExportResult {
        status = status.copy(state = SyncState.EXPORTING, lastError = null)
        val observed = LinkedHashMap<BackendId, List<GrantView>>()
        for (backend in backends.sortedBy { it.id.ordinal }) {
            if (!backend.available()) {
                return blocked("SUPERUSER_BACKEND_UNAVAILABLE", backend.id.wire)
            }
            val views = try {
                backend.enumerate()
            } catch (failure: Exception) {
                return blocked("SUPERUSER_ENUMERATE_FAILED",
                    backend.id.wire + ":" + failure.message.orEmpty())
            }
            observed[backend.id] = views
        }
        val current = when (val read = control.readSharedSuperuser()) {
            is ControlRead.Ok -> read.value
            is ControlRead.Missing -> emptyShared(clock.nowMillis())
            is ControlRead.Corrupt -> return blocked(read.code, read.detail)
            is ControlRead.Unreadable -> return blocked(read.code, read.path)
        }
        val now = clock.nowMillis()
        var merged = GrantMerge.merge(current, observed, now, from)
        if (rev > merged.revision) merged = merged.copy(revision = rev)
        merged = merged.copy(policy = merged.policy.copy(lastExportRev = merged.revision))
        val write = control.writeSharedSuperuser { merged }
        return when (write) {
            is ControlWrite.Ok -> {
                val value = write.value
                val allowed = value.entries.count { it.decision == GrantIntent.ALLOW }
                val denied = value.entries.count { it.decision == GrantIntent.DENY }
                status = SyncStatus(SyncState.EXPORTED, value.revision, value.entries.size,
                    pendingCount(value), value.tombs.size)
                evidence("export", EvidenceLine.OK, value, from)
                ExportResult.Ok(value.revision, allowed, denied, value.tombs.size)
            }
            is ControlWrite.Rejected -> blocked(write.code, write.detail)
        }
    }

    override suspend fun reconcileAfterBoot(mode: WorkMode): ReconcileResult {
        status = status.copy(state = SyncState.APPLYING, lastError = null)
        val current = when (val read = control.readSharedSuperuser()) {
            is ControlRead.Ok -> read.value
            is ControlRead.Missing -> {
                status = SyncStatus(SyncState.IDLE, 0, 0, 0, 0)
                return ReconcileResult(0, 0, 0, emptyList(), SyncState.IDLE)
            }
            is ControlRead.Corrupt -> {
                status = status.copy(state = SyncState.BLOCKED, lastError = read.code)
                return ReconcileResult(0, 0, 0, emptyList(), SyncState.BLOCKED)
            }
            is ControlRead.Unreadable -> {
                status = status.copy(state = SyncState.BLOCKED, lastError = read.code)
                return ReconcileResult(0, 0, 0, emptyList(), SyncState.BLOCKED)
            }
        }
        val selected = selectBackends(mode)
        val now = clock.nowMillis()
        var applied = 0
        var failed = 0
        var skipped = 0
        val okKeys = LinkedHashMap<BackendId, MutableSet<GrantKey>>()
        val failuresByBackend = LinkedHashMap<BackendId, MutableList<ApplyFailure>>()
        for (backend in selected) {
            val planned = ApplyPlanner.plan(current, backend.id) { uid -> backend.packageNamesForUid(uid) }
            skipped += planned.skippedIdentityStale
            val failures = failuresByBackend.getOrPut(backend.id) { mutableListOf() }
            if (!backend.available()) {
                // 未兑现的 grant/revoke 都保留 pending；revoke 仍按危险失败告警。
                for (view in planned.desired) {
                    failed++
                    failures.add(ApplyFailure(view.uid, view.packageName, ApplyReport.opOf(view),
                        "SUPERUSER_BACKEND_UNAVAILABLE", backend.id.wire))
                }
                continue
            }
            val report = try {
                backend.apply(planned.desired, current.tombs, current.revision)
            } catch (failure: Exception) {
                ApplyReport.reject(backend.id, planned.desired, current.revision,
                    "SUPERUSER_APPLY_FAILED", failure.message.orEmpty())
            }
            val observed = try {
                backend.verify()
            } catch (_: Exception) {
                emptyList()
            }
            for (view in planned.desired) {
                val reported = report.failed.firstOrNull {
                    it.uid == view.uid && it.packageName == view.packageName &&
                        it.op == ApplyReport.opOf(view)
                }
                if (reported != null) {
                    failed++
                    failures.add(reported)
                    continue
                }
                val verified = if (view.allow) {
                    observed.any { it.uid == view.uid && it.packageName == view.packageName && it.allow }
                } else {
                    observed.none { it.uid == view.uid && it.packageName == view.packageName && it.allow }
                }
                if (!verified) {
                    // 发布红线 8：verify 没通过绝不算 ok。
                    failed++
                    failures.add(ApplyFailure(view.uid, view.packageName, ApplyReport.opOf(view),
                        "VERIFY_MISMATCH", backend.id.wire))
                } else {
                    applied++
                    okKeys.getOrPut(backend.id) { mutableSetOf() }
                        .add(GrantKey(view.userId, view.uid, view.packageName))
                }
            }
        }
        val nextRevision = current.revision + 1
        val nextEntries = current.entries.map { intent ->
            updateIntent(intent, selected, okKeys, failuresByBackend, current.revision, now)
        }
        val next = current.copy(
            revision = nextRevision,
            updatedAt = now,
            lastWriter = mode.suffix,
            entries = nextEntries,
        )
        val write = control.writeSharedSuperuser { next }
        val writeError = (write as? ControlWrite.Rejected)?.code
        val dangerous = failuresByBackend.values.flatten().filter { it.dangerous }
        val maxPending = next.entries.mapNotNull { it.pending?.attempts }.maxOrNull() ?: 0
        val state = when {
            writeError != null -> SyncState.PARTIAL_PENDING
            failed == 0 -> SyncState.VERIFIED
            maxPending >= maxAttempts -> SyncState.BACKOFF
            else -> SyncState.PARTIAL_PENDING
        }
        status = SyncStatus(state, nextRevision, next.entries.size, pendingCount(next),
            next.tombs.size, writeError)
        evidence("apply", if (state == SyncState.VERIFIED) EvidenceLine.OK else EvidenceLine.PARTIAL, next, mode)
        return ReconcileResult(applied, failed, skipped, dangerous, state)
    }

    private fun updateIntent(
        intent: GrantIntent,
        selected: List<SuperuserBackend>,
        okKeys: Map<BackendId, MutableSet<GrantKey>>,
        failures: Map<BackendId, MutableList<ApplyFailure>>,
        revision: Long,
        now: Long,
    ): GrantIntent {
        var result = intent
        val key = GrantKey.of(intent)
        for (backend in selected) {
            val ok = okKeys[backend.id]?.contains(key) == true
            val failure = failures[backend.id]?.firstOrNull {
                it.uid == intent.uid && it.packageName == intent.packageName
            }
            if (!ok && failure == null) continue
            result = if (ok) {
                result.copy(
                    applied = result.applied.filterNot { it.backend == backend.id.wire } +
                        AppliedMark(backend.id.wire, revision, now, true),
                    pending = result.pending?.takeIf { it.backend != backend.id.wire },
                )
            } else {
                val attempts = (result.pending?.takeIf { it.backend == backend.id.wire }?.attempts ?: 0) + 1
                result.copy(
                    // 水位回退：失败后清掉 ok 标记，保证墓碑在下次 apply 被强制重放（G3）。
                    applied = result.applied.filterNot { it.backend == backend.id.wire },
                    pending = PendingOp(
                        backend = backend.id.wire,
                        op = requireNotNull(failure).op,
                        attempts = attempts,
                        lastError = requireNotNull(failure).code,
                        since = result.pending?.takeIf { it.backend == backend.id.wire }?.since ?: now,
                    ),
                )
            }
        }
        return result
    }

    /** 某模式下真正生效的 A 后端（Ultra 优先）或 KSU 后端；不可用后端仍需处理危险 revoke。 */
    private fun selectBackends(mode: WorkMode): List<SuperuserBackend> = when (mode) {
        WorkMode.LITE -> {
            val ultra = backends.firstOrNull { it.id == BackendId.A_ULTRA && it.available() }
            if (ultra != null) listOf(ultra) else backends.filter { it.id == BackendId.A_DIRECT }
        }
    }

    private fun blocked(code: String, detail: String): ExportResult.Blocked {
        status = status.copy(state = SyncState.BLOCKED, lastError = code)
        return ExportResult.Blocked(code, detail)
    }

    private fun emptyShared(now: Long) = SharedSuperuserFile(
        revision = 0,
        updatedAt = now,
        lastWriter = "",
        entries = emptyList(),
        tombs = emptyList(),
        policy = SharedPolicy(),
    )

    private fun pendingCount(file: SharedSuperuserFile) = file.entries.count { it.pending != null }

    private fun evidence(stage: String, result: String, file: SharedSuperuserFile, mode: WorkMode) {
        runCatching {
            control.appendEvidence(
                EvidenceLine(
                    at = clock.nowMillis(),
                    bootId = "",
                    kind = "SUPERUSER_SYNC",
                    stage = stage,
                    result = result,
                    state = status.state.name,
                    rule = mode.suffix,
                    counts = listOf(
                        CountFact("entries", file.entries.size.toLong()),
                        CountFact("tombs", file.tombs.size.toLong()),
                        CountFact("pending", pendingCount(file).toLong()),
                    ),
                ),
            )
        }
    }
}
