package dev.maplesu.manager.installer

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.service.ClientResult
import dev.maplesu.manager.workmode.service.ClientStatus
import dev.maplesu.manager.workmode.service.ModeSwitchClient
import dev.maplesu.manager.workmode.switch.ModeSwitchPreflight
import dev.maplesu.manager.workmode.switch.SwitchStateNames
import java.io.File
import java.util.UUID

/**
 * 「Normal 安装真正落盘」的稳定错误码（P0-1 第 4 项）。
 *
 * 命名纪律：能表达为既有 `SWITCH_*`/`MOVE_*` 的一律复用（与 `ModeSwitchCodes` / `MoveSet.Errors`
 * 同前缀）；只有"安装提交"这条链特有的失败面才用 `KSU_INSTALL_*`（与
 * [KsuInstallCodes] 同前缀）。UI 只按码映射文案，不做字符串匹配。
 */
object InstallCommitCodes {
    /** 源模式不是 Ultra（LITE）：Normal 安装只允许从 Ultra 发起（否则会在已是 Normal 的设备上再写 init_boot）。 */
    const val SOURCE_MODE_INVALID = "KSU_INSTALL_SOURCE_MODE_INVALID"

    /** 事务 id 不是 canonical UUID（服务端 `requireSwitchId` 的硬契约）。 */
    const val SWITCH_ID_INVALID = "KSU_INSTALL_SWITCH_ID_INVALID"

    /** 候选分区不是目标模式拥有的分区（KSU = init_boot）。 */
    const val PARTITION_INVALID = "KSU_INSTALL_PARTITION_INVALID"

    /** 候选文件在产出之后被改写（字节/大小与提交前记录不一致）。 */
    const val CANDIDATE_CHANGED = "KSU_INSTALL_CANDIDATE_CHANGED"

    /** 阶段包装码：事务客户端抛异常（网络/Binder/IO）时使用；真正的失败码优先。 */
    const val PREFLIGHT_FAILED = "KSU_INSTALL_PREFLIGHT_FAILED"
    const val BEGIN_FAILED = "KSU_INSTALL_BEGIN_FAILED"
    const val SNAPSHOT_FAILED = "KSU_INSTALL_SNAPSHOT_FAILED"
    const val SUBMIT_FAILED = "KSU_INSTALL_SUBMIT_FAILED"
    const val COMMIT_FAILED = "KSU_INSTALL_COMMIT_FAILED"

    /**
     * **防谎报成功的核心码**：候选被事务"接收"了，但没有经过 `commit` + 逐分区回读确认
     * （或后端报的状态不是 `REBOOT_REQUIRED`）。带这个码的结果**永远不是成功**。
     */
    const val COMMIT_NOT_CONFIRMED = "KSU_INSTALL_COMMIT_NOT_CONFIRMED"

    /** 4b 配置/模块出仓失败（未写任何分区）。 */
    const val MOVE_OUT_FAILED = "SWITCH_MOVE_OUT_FAILED"

    /** 4e 配置/模块入仓失败（分区已写，已尝试逆序回滚）。 */
    const val MOVE_IN_FAILED = "SWITCH_MOVE_IN_FAILED"

    /** 4f 预物化/不变量校验失败。 */
    const val MATERIALIZE_FAILED = "SWITCH_MATERIALIZE_FAILED"

    /**
     * 该后端**不在** `commit` 内搬配置/模块（Ultra 根通道），而调用方没有可用的 root 搬移实现。
     * fail-closed：宁可在**写任何分区之前**显式失败，也不产出"切换成功但配置还是旧的"。
     */
    const val MOVE_STAGE_UNWIRED = "SWITCH_MOVE_STAGE_UNWIRED"

    /** 配置/模块回滚失败：分区可能已恢复，但配置不再可信。 */
    const val MOVE_ROLLBACK_FAILED = "SWITCH_MOVE_ROLLBACK_FAILED"

    /**
     * **分区回滚失败**（S1）：`commit` 结果不可信，而显式 `client.rollback` 也没有确认
     * `ROLLED_BACK`。带这个码时 `rolledBack` 必须为 `false`、`requiresRecovery` 必须为 `true`
     * —— 用户绝不能被告诉"已还原安装前的启动镜像"。
     */
    const val PARTITION_ROLLBACK_FAILED = "SWITCH_PARTITION_ROLLBACK_FAILED"

    /** The helper may still be mutating state; recovery must wait for a known outcome. */
    const val ROOT_OPERATION_UNCERTAIN = "MODE_SWITCH_ROOT_OPERATION_UNCERTAIN"

    /**
     * 基线 id 为空（M3）：后端会把它解析成"最新 manifest"且**不校验固件/slot**，
     * 等于拿一份来路不明的镜像去还原 `boot`。fail-closed：零 tx、零 dd。
     * 与 `BaselineErrors.BASELINE_MISSING` 同机器值。
     */
    const val BASELINE_MISSING = "BASELINE_MISSING"

    /** 搬移计划本身不合法（`MoveSet.validate` 的既有码）。 */
    const val MOVE_PLAN_INVALID = "MOVE_PLAN_INVALID"

    /** 提交必须在后台线程执行（同步端口 ↔ suspend 客户端的桥）。 */
    const val MAIN_THREAD = "KSU_INSTALL_MAIN_THREAD"

    /** 事务客户端整体失败（未预期的异常）。 */
    const val TX_FAILED = "KSU_INSTALL_TX_FAILED"
}

/**
 * 安装成功的**证据强度**（M2）。
 *
 * 事实（21 报告 §4-M2）：服务端 AIDL 通道的 `LocalInstallerGateway.statusFor` 把
 * `observedSha256` 填成空串，于是 `InstallCommitCoordinator.confirmationFailure` 的
 * "写后回读摘要必须等于候选摘要"这条在 SERVICE 路径上是**空条件**；真实回读确实发生在
 * `InstallerMultiPartitionTransaction`（写后 `partition.sha256()` + tail 校验），只是没有
 * 上抛到 App。Ultra 根通道会把 `sha256sum` 的回读值写进 `observedSha256`。
 *
 * 因此成功判据分两级，且必须**照实记**（日志/manifest/报告），不得把 state-only 说成回读：
 * - [STATE_AND_READBACK]：后端上报了目标分区写后回读摘要，且与候选摘要一致；
 * - [STATE_ONLY]：只能靠 `state ∈ {REBOOT_REQUIRED, VERIFIED}`（这两个状态只在后端回读
 *   通过之后才写，但 App 侧拿不到独立证据）。
 */
object InstallConfirmationEvidence {
    const val STATE_AND_READBACK = "state+readback"
    const val STATE_ONLY = "state-only"
}

/**
 * 安装页选中的候选镜像。
 *
 * **这是"用户选了某个镜像"这件事的唯一载体**：producer（`KsuInstallProducer`）用用户选的
 * stock 镜像跑 ksud 得到它；这里只负责把它原样提交进共用事务，绝不重新产候选、绝不换文件。
 */
data class InstallCandidateImage(
    val image: File,
    val sha256: String,
    val size: Long,
    /** 目标分区；KSU 只允许 `init_boot`（[ModeSwitchPreflight.targetPartition]）。 */
    val partition: String,
    val compatibilityImages: Map<String, InstallCompanionImage> = emptyMap(),
)

data class InstallCompanionImage(val image: File, val sha256: String, val sourceSha256: String)

/** 一次安装提交的结果：只有 [AwaitingReboot] 表示"分区已写、回读已过、等重启"。 */
sealed interface InstallCommitOutcome {
    data class AwaitingReboot(
        val switchId: String,
        val status: ClientStatus?,
        /**
         * 成功判据的**证据强度**（M2）：见 [InstallConfirmationEvidence]。
         *
         * SERVICE 后端目前只上报 `state`（`observedSha256` 为空），所以真实值是
         * [InstallConfirmationEvidence.STATE_ONLY]；Ultra 根通道会给出写后回读摘要 ⇒
         * [InstallConfirmationEvidence.STATE_AND_READBACK]。UI/日志必须照实标注，不得夸大。
         */
        val evidence: String = InstallConfirmationEvidence.STATE_ONLY,
    ) : InstallCommitOutcome

    /**
     * 失败（**绝不**与成功混用）。
     *
     * @param rolledBack 分区是否已确认回滚到 PRE（或压根没写过）
     * @param requiresRecovery 需要人工恢复（分区/配置回滚失败）
     */
    data class Rejected(
        val code: String,
        val detail: String = "",
        val switchId: String = "",
        val rolledBack: Boolean = false,
        val requiresRecovery: Boolean = false,
    ) : InstallCommitOutcome
}

/** 4b/4e/4f 一步的结果（`ok=false` 时 [code] 必须非空）。 */
data class InstallMoveOutcome(val ok: Boolean, val code: String = "", val detail: String = "") {
    companion object {
        val OK = InstallMoveOutcome(true)
        fun failed(code: String, detail: String = "") = InstallMoveOutcome(false, code, detail)
    }
}

/**
 * 4b/4e/4f 里**已经执行过**的阶段（S3 的回滚记账单位）。
 *
 * 为什么必须显式传递：`outbound`（4b，live→store）与 `inbound`（4e，store→live）是**两个方向**。
 * 回滚时把没执行过的方向也"逆序撤一遍"会把 live 上的真实数据搬进错误的模式槽
 * （4b 第 k 项失败后，第 k+1..n 项的 live 数据还在原地，反向撤 inbound 会把它们搬进 `modes/<目标>`）。
 */
enum class InstallMovePhase {
    /** 4b：`live → store`（源模式出仓）。 */
    OUT,

    /** 4e：`store → live`（目标模式入仓）。 */
    IN,
}

/**
 * 配置/模块搬移端口（S4 的 4b/4e/4f）。
 *
 * 为什么需要它：`UltraRootModeSwitchClient.commit` **不做** 4b/4e/4f（Ultra 通道里没有任何 move
 * 代码），而服务端 AIDL 委托会在 `commit` 内部搬。于是"谁搬"取决于后端，见
 * [InstallCommitCoordinator] 的 `backendMovesConfig`。
 *
 * 实现必须复用 `MoveSet.plan/validate/outboundOrder/inboundOrder`（顺序与路径来自常量），
 * 生产实现见 [RootScriptInstallConfigMover]（root 脚本，App 进程不能直接 rename `/data/adb`）。
 */
interface InstallConfigMover {
    fun moveOut(switchId: String, from: WorkMode, to: WorkMode): InstallMoveOutcome

    fun moveIn(switchId: String, from: WorkMode, to: WorkMode): InstallMoveOutcome

    fun materialize(switchId: String, from: WorkMode, to: WorkMode): InstallMoveOutcome

    /**
     * 逆序撤销**只针对 [completed] 里真正执行过的阶段**（S3）。
     *
     * - `completed = {OUT}`（4b 失败 / 分区写失败）：只做 `store → live`，**绝不动** inbound 方向；
     * - `completed = {OUT, IN}`（4e/4f 失败）：先撤 inbound（`live → store`），再撤 outbound；
     * - `completed = {}`：什么都不做（返回 OK）。
     *
     * @param completed 可能已经产生过搬移的阶段集合（不是"成功"集合：失败点之前的条目已搬走）。
     */
    fun rollback(
        switchId: String,
        from: WorkMode,
        to: WorkMode,
        completed: Set<InstallMovePhase>,
    ): InstallMoveOutcome
}

/**
 * 把「安装页已经产出的候选」提交进**共用切换事务**（P0-1 第 4 项）。
 *
 * 顺序（每一步失败即返回，不留半成品；只有最后一步才可能返回成功）：
 * `begin` → `snapshot（第一次 dd，只读）` →
 * `submitCandidate（用户选中的那个镜像）` → `4b 配置出仓` → `commit（逐分区回读 + 逆序回滚）` →
 * `回读确认（必须 REBOOT_REQUIRED）` → `4e 入仓` → `4f 物化` → `markRebootRequested`。
 * （注：4b 在 `begin/snapshot` **之后**、写分区**之前**；早期 KDoc 把它写在 begin 之前是笔误。）
 *
 * 为什么 4b 必须在 `commit` 之前：契约要求"4b 出仓 → 4c 还原源分区 → 4d 写目标分区"；
 * 分区写完再搬配置，会让"配置搬移失败"变成"分区已改、配置未改"的不可判定状态。
 *
 * 红线：
 * - 不新造写盘路径：分区字节只由注入的 [ModeSwitchClient]（生产 = 12 方法事务）写；
 * - 不产候选、不换镜像：`candidate.image` 就是安装页选中的那一份，先复核 sha/size；
 * - **没有 `commit` + 回读确认就绝不返回成功**（[InstallCommitCodes.COMMIT_NOT_CONFIRMED]）。
 *
 * 线程：全部方法都是 `suspend`（客户端是 suspend），调用方负责切到 IO。
 *
 * @param backendMovesConfig 该后端是否在 `commit` 内部完成 4b/4e/4f（服务端 AIDL = true）。
 *   必须在 **begin 成功之后**求值：`SelectingModeSwitchClient` 是延迟钉扎的。
 * @param moverProvider 需要调用方搬移时才构造（`null` = 没有可用实现 → fail-closed）。
 * @param log 证据/诊断行（不含凭据；只含 switchId/分区/码/摘要）。
 */
class InstallCommitCoordinator(
    private val client: ModeSwitchClient,
    private val moverProvider: () -> InstallConfigMover? = { null },
    private val backendMovesConfig: () -> Boolean = { true },
    private val newSwitchId: () -> String = { UUID.randomUUID().toString() },
    private val log: (String) -> Unit = {},
    private val afterMaterialize: (WorkMode) -> InstallMoveOutcome = { InstallMoveOutcome.OK },
) {

    suspend fun commit(
        candidate: InstallCandidateImage,
        from: WorkMode,
        to: WorkMode,
        baselineId: String,
        superuserJson: File,
        settingsMirrorJson: File,
        requestedSwitchId: String? = null,
        onProgress: (String) -> Unit = {},
    ): InstallCommitOutcome {
        val switchId = requestedSwitchId?.takeIf { it.isNotBlank() } ?: newSwitchId()
        if (!canonicalUuid(switchId)) {
            return rejected(
                InstallCommitCodes.SWITCH_ID_INVALID,
                "switchId is not a canonical UUID: $switchId",
            )
        }
        // An empty id requests current carrier snapshots. The backend resolves and verifies
        // the actual source images; an explicit imported baseline id is passed unchanged.
        // 安装允许 KSU 显式选择 init_boot 或 boot（与 KernelSU 的 --partition 一致）；
        // Ultra 固定 boot。真正的介质写入仍由后端按 plan.targetPartition + 写序复核。
        val allowed = ModeSwitchPreflight.allowedTargets(to)
        if (candidate.partition !in allowed) {
            return rejected(
                InstallCommitCodes.PARTITION_INVALID,
                "candidate targets '${candidate.partition}', but ${to.name} allows ${allowed.joinToString("/")}",
                switchId,
            )
        }
        // 「用户选的那个镜像」不变式：提交前复核字节身份（producer 产出后可能被改写/删）。
        if (!candidate.image.isFile || candidate.image.length() != candidate.size) {
            return rejected(
                InstallCommitCodes.CANDIDATE_CHANGED,
                "candidate missing or resized: ${candidate.image.absolutePath}",
                switchId,
            )
        }

        val fields = mapOf(
            "fromMode" to from.name,
            "toMode" to to.name,
            "baselineId" to baselineId,
            // 安装选择的分区必须进入 plan；否则 KSU 安装到 boot 会被后端按默认 init_boot 拒绝。
            "targetPartition" to candidate.partition,
        )

        // ---------------- S1 begin（首次真实调用选择后端） ----------------
        onProgress("S1")
        val begin = call(InstallCommitCodes.BEGIN_FAILED) {
            client.begin(switchId, fields, superuserJson, settingsMirrorJson)
        }
        if (!begin.ok) {
            if (begin.uncertain()) return rejected(begin.code, begin.detail, switchId, requiresRecovery = true)
            val aborted = abortQuietly(switchId)
            return rejected(begin.code.ifEmpty { InstallCommitCodes.BEGIN_FAILED }, begin.detail,
                switchId, rolledBack = aborted)
        }

        // begin 已选择实际后端，才判断配置搬移归属；失败时终止已有任务，尚未写分区。
        val backendMoves = runCatching { backendMovesConfig() }.getOrNull()
        if (backendMoves == null) {
            val aborted = abortQuietly(switchId)
            return rejected(
                InstallCommitCodes.MOVE_STAGE_UNWIRED,
                "cannot determine whether the backend moves config/modules inside commit; " +
                    "no partition was written; aborted=$aborted",
                switchId,
                rolledBack = aborted,
                requiresRecovery = !aborted,
            )
        }
        val movesByCaller = !backendMoves
        val mover = if (movesByCaller) runCatching { moverProvider() }.getOrNull() else null
        if (movesByCaller && mover == null) {
            val aborted = abortQuietly(switchId)
            return rejected(
                InstallCommitCodes.MOVE_STAGE_UNWIRED,
                "backend does not move config/modules inside commit and no root mover is wired; " +
                    "no partition was written; aborted=$aborted",
                switchId,
                rolledBack = aborted,
                requiresRecovery = !aborted,
            )
        }

        // ---------------- S2 snapshot（第一次 dd；只读，但会落 PRE 镜像） ----------------
        onProgress("S2")
        val snapshot = call(InstallCommitCodes.SNAPSHOT_FAILED) { client.snapshot(switchId) }
        if (!snapshot.ok) {
            if (snapshot.uncertain()) return rejected(snapshot.code, snapshot.detail, switchId, requiresRecovery = true)
            val aborted = abortQuietly(switchId)
            return rejected(snapshot.code.ifEmpty { InstallCommitCodes.SNAPSHOT_FAILED },
                snapshot.detail, switchId, rolledBack = aborted)
        }

        // ---------------- S3 submit（源环境仍完整；候选被接收后才撤源） ----------------
        onProgress("S3")
        val submit = call(InstallCommitCodes.SUBMIT_FAILED) {
            client.submitCandidate(switchId, candidate.partition, candidate.image, candidate.sha256)
        }
        if (!submit.ok) {
            if (submit.uncertain()) return rejected(submit.code, submit.detail, switchId, requiresRecovery = true)
            val aborted = abortQuietly(switchId)
            return rejected(submit.code.ifEmpty { InstallCommitCodes.SUBMIT_FAILED }, submit.detail,
                switchId, rolledBack = aborted)
        }

        // ---------------- 4b 配置/模块出仓（分区未动） ----------------
        if (mover != null) {
            onProgress("4b")
            val out = move("out", InstallCommitCodes.MOVE_OUT_FAILED, switchId, from, to) {
                mover.moveOut(switchId, from, to)
            }
            if (!out.ok) {
                if (out.uncertain()) return rejected(out.code, out.detail, switchId, requiresRecovery = true)
                // 出仓失败：分区未写，只需撤**真正做过的出仓步骤**（S3：绝不反向搬 inbound）。
                val config = rollbackConfig(
                    mover, switchId, from, to, setOf(InstallMovePhase.OUT),
                )
                if (config.uncertain()) return rejected(config.code, config.detail, switchId, requiresRecovery = true)
                val aborted = abortQuietly(switchId)
                return rejected(
                    out.code.ifEmpty { InstallCommitCodes.MOVE_OUT_FAILED }, out.detail, switchId,
                    rolledBack = config.ok && aborted,
                    requiresRecovery = !config.ok,
                )
            }
        }

        // ---------------- M1：写分区之前重求值"后端是否自己搬配置" ----------------
        // begin 后选定的搬移归属必须保持到 commit；否则可能无人搬移或重复搬移配置。
        val backendMovesNow = runCatching { backendMovesConfig() }.getOrNull()
        if (backendMovesNow == null || backendMovesNow != backendMoves) {
            val config = rollbackConfig(mover, switchId, from, to, setOf(InstallMovePhase.OUT))
            if (config.uncertain()) return rejected(config.code, config.detail, switchId, requiresRecovery = true)
            val aborted = abortQuietly(switchId)
            return rejected(
                InstallCommitCodes.MOVE_STAGE_UNWIRED,
                "backend move capability changed/unknown before commit " +
                    "(before=$backendMoves, now=$backendMovesNow); no partition was written; " +
                    "configRolledBack=${config.ok}; aborted=$aborted",
                switchId,
                // `commit` 是唯一写分区的方法，它还没被调用 ⇒ 分区"压根没写过"。
                rolledBack = config.ok && aborted,
                requiresRecovery = !config.ok || !aborted,
            )
        }

        // ---------------- S4 commit（唯一写分区；内部逐分区回读 + 失败逆序回滚） ----------------
        onProgress("S4")
        val commit = call(InstallCommitCodes.COMMIT_FAILED) { client.commit(switchId) }
        if (!commit.ok) {
            if (commit.uncertain()) return rejected(commit.code, commit.detail, switchId, requiresRecovery = true)
            val status = commit.status ?: statusQuietly(switchId)
            val partitions = when (status?.state) {
                SwitchStateNames.ROLLED_BACK, SwitchStateNames.FAILED_PRE_WRITE -> PartitionRestore(true, status.state)
                SwitchStateNames.RECOVERY_REQUIRED -> PartitionRestore(false, status.errorDetail)
                else -> rollbackPartitions(switchId)
            }
            if (!partitions.ok) return rejected(
                InstallCommitCodes.PARTITION_ROLLBACK_FAILED, joinDetail(commit.detail, partitions.detail),
                switchId, requiresRecovery = true,
            )
            val config = rollbackConfig(mover, switchId, from, to, setOf(InstallMovePhase.OUT))
            return rejected(
                if (config.uncertain()) config.code else commit.code.ifEmpty { InstallCommitCodes.COMMIT_FAILED },
                joinDetail(commit.detail, config.detail), switchId,
                rolledBack = config.ok, requiresRecovery = !config.ok,
            )
        }

        // ---------------- 回读确认：没有确认绝不返回成功 ----------------
        val status = commit.status ?: statusQuietly(switchId)
        val unconfirmed = confirmationFailure(status, candidate)
        if (unconfirmed != null) {
            // S1：分区回滚结果**必须**参与判定。原实现 `runCatching { client.rollback() }` 把
            // 结果整个丢掉、`rolledBack` 只看配置回滚（mover 为 null 时恒 true）⇒ 分区没回滚
            // 也会告诉用户"已还原安装前的启动映像"。
            val partitions = rollbackPartitions(switchId)
            if (!partitions.ok) return rejected(
                InstallCommitCodes.PARTITION_ROLLBACK_FAILED, joinDetail(unconfirmed, partitions.detail),
                switchId, requiresRecovery = true,
            )
            val config = rollbackConfig(mover, switchId, from, to, setOf(InstallMovePhase.OUT))
            // 分别记账（S1）：分区回滚与配置回滚是两件独立的事，任何一项失败都必须
            // requiresRecovery=true，且 detail 里逐项写清（UI 文案只看 code，但排障要看明细）。
            val accounting = buildString {
                append("partitions=")
                append(if (partitions.ok) "restored" else "NOT-restored[${partitions.detail}]")
                append("; config=")
                append(if (config.ok) "rolled-back" else "NOT-rolled-back[${config.detail}]")
            }
            log(
                "install-commit not confirmed switch=$switchId reason=$unconfirmed " +
                    "partitionsRestored=${partitions.ok} configRolledBack=${config.ok} " +
                    "switchEvidence=$accounting",
            )
            val code = when {
                config.uncertain() -> config.code
                !config.ok -> InstallCommitCodes.MOVE_ROLLBACK_FAILED
                else -> InstallCommitCodes.COMMIT_NOT_CONFIRMED
            }
            return rejected(
                code,
                joinDetail(unconfirmed, accounting),
                switchId,
                rolledBack = partitions.ok && config.ok,
                requiresRecovery = !(partitions.ok && config.ok),
            )
        }

        // ---------------- 4e 入仓 + 4f 物化 ----------------
        if (mover != null) {
            onProgress("4e")
            val inbound = move("in", InstallCommitCodes.MOVE_IN_FAILED, switchId, from, to) {
                mover.moveIn(switchId, from, to)
            }
            if (!inbound.ok) {
                if (inbound.uncertain()) return rejected(inbound.code, inbound.detail, switchId, requiresRecovery = true)
                // 契约：先回滚分区、再回滚配置，终态需要人工恢复。
                val partitions = rollbackPartitions(switchId)
                if (!partitions.ok) return rejected(
                    InstallCommitCodes.PARTITION_ROLLBACK_FAILED, joinDetail(inbound.detail, partitions.detail),
                    switchId, requiresRecovery = true,
                )
                val config = rollbackConfig(
                    mover, switchId, from, to,
                    setOf(InstallMovePhase.OUT, InstallMovePhase.IN),
                )
                return rejected(
                    if (config.uncertain()) config.code else inbound.code.ifEmpty { InstallCommitCodes.MOVE_IN_FAILED },
                    joinDetail(inbound.detail, config.detail), switchId,
                    rolledBack = config.ok, requiresRecovery = !config.ok,
                )
            }
        }
        run {
            if (mover != null) onProgress("4f")
            val materialized = move("materialize", InstallCommitCodes.MATERIALIZE_FAILED, switchId, from, to) {
                val moved = mover?.materialize(switchId, from, to) ?: InstallMoveOutcome.OK
                if (moved.ok) afterMaterialize(to) else moved
            }
            if (!materialized.ok) {
                if (materialized.uncertain()) return rejected(materialized.code, materialized.detail, switchId, requiresRecovery = true)
                val partitions = rollbackPartitions(switchId)
                if (!partitions.ok) return rejected(
                    InstallCommitCodes.PARTITION_ROLLBACK_FAILED, joinDetail(materialized.detail, partitions.detail),
                    switchId, requiresRecovery = true,
                )
                val config = rollbackConfig(
                    mover, switchId, from, to,
                    setOf(InstallMovePhase.OUT, InstallMovePhase.IN),
                )
                return rejected(
                    if (config.uncertain()) config.code else materialized.code.ifEmpty { InstallCommitCodes.MATERIALIZE_FAILED },
                    joinDetail(materialized.detail, config.detail), switchId,
                    rolledBack = config.ok, requiresRecovery = !config.ok,
                )
            }
        }

        // ---------------- 4g 重启标记（best-effort：标记失败不改变"分区已写"这个事实） ----------------
        runCatching { client.markRebootRequested(switchId) }
        onProgress(SwitchStateNames.REBOOT_REQUIRED)
        val evidence = confirmationEvidence(status, candidate)
        log(
            "install-commit ok switch=$switchId partition=${candidate.partition} " +
                "sha=${candidate.sha256} state=${status?.state.orEmpty()} " +
                "evidence=$evidence movesByCaller=$movesByCaller",
        )
        return InstallCommitOutcome.AwaitingReboot(switchId, status, evidence)
    }

    // ------------------------------------------------------------------ internals

    /**
     * 成功判据：后端报 `REBOOT_REQUIRED`/`VERIFIED`（这两个状态**只在逐分区回读通过之后**才写），
     * 且若后端上报了目标分区的 `observedSha256`，必须等于候选摘要。
     *
     * L3：不再接受"`rebootRequired=true` 但状态不是 REBOOT_REQUIRED"——两个后端都只在
     * `REBOOT_REQUIRED` 时置真，留这条或条件会被下一个后端当成"建议重启"而放宽成功判据。
     */
    private fun confirmationFailure(status: ClientStatus?, candidate: InstallCandidateImage): String? {
        if (status == null) return "no persisted status after commit"
        val confirmed = status.state == SwitchStateNames.REBOOT_REQUIRED ||
            status.state == SwitchStateNames.VERIFIED
        if (!confirmed) return "unexpected state after commit: '${status.state}'"
        val row = status.partitions.firstOrNull { it.name == candidate.partition }
        val observed = row?.observedSha256.orEmpty()
        if (observed.isNotBlank() && !observed.equals(candidate.sha256, ignoreCase = true)) {
            return "readback mismatch on ${candidate.partition}: observed=$observed " +
                "expected=${candidate.sha256}"
        }
        return null
    }

    /**
     * 成功判据的证据强度（M2）：后端给了写后回读摘要且一致 ⇒ `state+readback`；否则 `state-only`。
     *
     * 这里**不**放宽/收紧成功条件，只把"我们到底掌握了什么证据"写实，
     * 供日志、manifest 与交付报告引用（SERVICE 通道目前恒为 `state-only`）。
     */
    private fun confirmationEvidence(status: ClientStatus?, candidate: InstallCandidateImage): String {
        val observed = status?.partitions
            ?.firstOrNull { it.name == candidate.partition }
            ?.observedSha256.orEmpty()
        return if (observed.isNotBlank() && observed.equals(candidate.sha256, ignoreCase = true)) {
            InstallConfirmationEvidence.STATE_AND_READBACK
        } else {
            InstallConfirmationEvidence.STATE_ONLY
        }
    }

    private suspend fun call(code: String, block: suspend () -> ClientResult): ClientResult = try {
        block()
    } catch (error: Throwable) {
        if (error is Error) throw error
        ClientResult(false, code, "${error.javaClass.simpleName}: ${error.message}")
    }

    private suspend fun abortQuietly(switchId: String): Boolean = try {
        val status = client.abort(switchId)
        status.ok || status.cancelled()
    } catch (error: Throwable) {
        if (error is Error) throw error
        false
    }

    /** 分区回滚的显式结论（S1）：`ok=true` 才允许对外说"已还原"，`detail` 进失败明细。 */
    private data class PartitionRestore(val ok: Boolean, val detail: String)

    /**
     * **显式**回滚分区并读取结论（S1）。
     *
     * 与原实现的区别：结果不再被 `runCatching` 吞掉 —— `ok` 来自 `ClientResult.ok` 或后端
     * 明确的 `ROLLED_BACK` 状态；后端明确报 `RECOVERY_REQUIRED` 时即使 `ok=true` 也判失败
     * （fail-closed）。任何异常/无结论都按"未回滚"处理。
     */
    private suspend fun rollbackPartitions(switchId: String): PartitionRestore {
        val result = try {
            client.rollback(switchId)
        } catch (error: Throwable) {
            if (error is Error) throw error
            return PartitionRestore(false, "rollback threw ${error.javaClass.simpleName}: ${error.message}")
        }
        val state = result.status?.state.orEmpty()
        val restored = (result.ok || state == SwitchStateNames.ROLLED_BACK) &&
            state != SwitchStateNames.RECOVERY_REQUIRED
        return if (restored) {
            PartitionRestore(true, "ok=${result.ok} state=$state")
        } else {
            PartitionRestore(
                false,
                "ok=${result.ok} state=${state.ifEmpty { "-" }} " +
                    "code=${result.code.ifEmpty { "-" }}",
            )
        }
    }

    private suspend fun statusQuietly(switchId: String): ClientStatus? = try {
        client.status(switchId)
    } catch (error: Throwable) {
        if (error is Error) throw error
        null
    }

    private fun rollbackConfig(
        mover: InstallConfigMover?,
        switchId: String,
        from: WorkMode,
        to: WorkMode,
        completed: Set<InstallMovePhase>,
    ): InstallMoveOutcome {
        val implementation = mover ?: return InstallMoveOutcome.OK
        if (completed.isEmpty()) return InstallMoveOutcome.OK
        return move("rollback", InstallCommitCodes.MOVE_ROLLBACK_FAILED, switchId, from, to) {
            implementation.rollback(switchId, from, to, completed)
        }
    }

    private fun move(
        phase: String,
        code: String,
        switchId: String,
        from: WorkMode,
        to: WorkMode,
        action: () -> InstallMoveOutcome,
    ): InstallMoveOutcome {
        val outcome = try {
            action()
        } catch (error: Throwable) {
            if (error is Error) throw error
            InstallMoveOutcome.failed(code, "${error.javaClass.simpleName}: ${error.message}")
        }
        log(
            "install-move $phase switch=$switchId ${from.suffix}->${to.suffix} ok=${outcome.ok} " +
                "code=${outcome.code} detail=${outcome.detail}",
        )
        return if (outcome.ok || outcome.uncertain()) {
            outcome
        } else {
            // 对外只暴露**阶段码**（`SWITCH_MOVE_*`）：实现返回的 `MoveSet.Errors.MOVE_*` 细码
            // 归入 detail（UI 按码映射文案，细码只用于诊断/报告，不进用户可见码表）。
            InstallMoveOutcome(false, code, joinDetail(outcome.code, outcome.detail))
        }
    }

    private fun joinDetail(vararg parts: String): String =
        parts.filter { it.isNotBlank() }.joinToString(": ")

    private fun rejected(
        code: String,
        detail: String,
        switchId: String = "",
        rolledBack: Boolean = false,
        requiresRecovery: Boolean = false,
    ): InstallCommitOutcome.Rejected {
        log("install-commit rejected code=$code switch=$switchId detail=$detail")
        return InstallCommitOutcome.Rejected(code, detail, switchId, rolledBack, requiresRecovery)
    }

    private fun canonicalUuid(value: String): Boolean = try {
        UUID.fromString(value).toString() == value
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun ClientResult.cancelled(): Boolean =
        status?.state == SwitchStateNames.CANCELLED

    private fun ClientResult.uncertain(): Boolean = code == InstallCommitCodes.ROOT_OPERATION_UNCERTAIN

    private fun InstallMoveOutcome.uncertain(): Boolean = code == InstallCommitCodes.ROOT_OPERATION_UNCERTAIN
}
