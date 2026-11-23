package dev.maplesu.manager.installer

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.Hashes
import dev.maplesu.manager.workmode.storage.MoveEntry
import dev.maplesu.manager.workmode.storage.MoveKind
import dev.maplesu.manager.workmode.storage.MoveMount
import dev.maplesu.manager.workmode.storage.MovePlan
import dev.maplesu.manager.workmode.storage.MoveSet
import java.io.File

/**
 * 一条 root 脚本的执行结果（避免本包依赖 Android 运行时）。
 * 通道错误独立于脚本输出，不能因脚本已打印成功标记而忽略执行状态不确定。
 */
data class RootScriptResult(
    val ok: Boolean,
    val code: Int = 0,
    val output: String = "",
    val error: String = "",
)

/**
 * 4b/4e/4f 的 root 脚本实现（Ultra 根通道必须有它，见 [InstallCommitCoordinator] 的说明）。
 *
 * 为什么必须是 root 脚本而不是复用 `FileSwitchMoveStage`：`FileMoveExecutor` 用 `java.nio`
 * 直接 rename，只在**root 进程**（服务端 AIDL 委托）里可用；App 进程连 `/data/adb` 都读不了。
 * 于是这里把"计划"留在 Kotlin（复用 [MoveSet] 的常量/顺序/校验），只把"动作"下推到 root 脚本。
 *
 * 语义对齐 [dev.maplesu.manager.workmode.storage.FileMoveExecutor]：
 * - 执行顺序 = [MoveSet.outboundOrder] / [MoveSet.inboundOrder]（`modules` 先于 `metamodule`）；
 * - 同挂载只做 `mv`（rename），**失败绝不降级为 copy**；
 * - `CROSS_MOUNT_COPY`（`/metadata/...`）走 `cp -a` → `sync` → 删源；
 * - 每步动作后复核"目标在 + 源消失 + 类型不变"，失败即停并保留现场；
 * - 回滚按**已执行阶段**记账（[InstallMovePhase]），4b 失败只做 `store → live`（S3）；
 * - 含 [MoveSet.NEVER_MOVE] 后代的条目（`/data/adb/maplesu` 里的 `installer` 锁目录）降级为
 *   "逐子项搬移 + 保留受保护子树"的合并式搬移（M5），绝不整树 rename；
 * - 4f 的判据与 `ModeSwitchMoveStage.verifyInvariants` 对齐（S2）：`store` 必须消失；
 *   `live` **合法缺失**（该条目本次没有入仓）不是失败，存在则形态必须符合 `expectedShape`。
 *
 * 验证边界：主机覆盖解析、顺序与受保护目录的真实 Shell 搬移（本文件不含 `dd`），
 * 真机 toybox/`cp -a`/跨挂载行为仍需上机验证。
 *
 * @param runner root 脚本执行器（生产 = Ultra `run_root_cmd`；测试 = 假执行器）。
 * @param manifestFile 证据文件（App 私有目录，`<switchId>/move-manifest.json`）。
 */
class RootScriptInstallConfigMover(
    private val runner: (String) -> RootScriptResult,
    private val manifestFile: File,
    private val dynamicFrom: List<MoveEntry> = emptyList(),
    private val dynamicTo: List<MoveEntry> = emptyList(),
    private val log: (String) -> Unit = {},
) : InstallConfigMover {

    override fun moveOut(switchId: String, from: WorkMode, to: WorkMode): InstallMoveOutcome =
        runPhase("out", InstallCommitCodes.MOVE_OUT_FAILED, switchId, from, to) { plan ->
            plan.outbound.map { entry -> step(entry, storeToLive = false) }
        }

    override fun moveIn(switchId: String, from: WorkMode, to: WorkMode): InstallMoveOutcome =
        runPhase("in", InstallCommitCodes.MOVE_IN_FAILED, switchId, from, to) { plan ->
            plan.inbound.map { entry -> step(entry, storeToLive = true) }
        }

    /**
     * 4f 预物化校验（S2）。
     *
     * 判据**对齐** `ModeSwitchMoveStage.verifyInvariants`（4f 的服务端版本）：
     * - `store` 必须消失（`store-remnant` 是唯一强不变量）；
     * - `live` 缺失是**合法**的（该条目本次没有入仓 = 4e `SKIPPED`），绝不因此失败；
     * - `live` 存在时形态必须符合 `expectedShape`（`ABSENT` 表示"应当不存在"）。
     *
     * 原实现要求"live 必须存在"，比参照实现更严 ⇒ 首次 Ultra→Normal 在 4b 之后
     * `/data/adb/ksu` 必不存在（KSU 槽从未播种），于是写完分区后 4f 必然失败并回滚，
     * Ultra 主路径不可达。
     */
    override fun materialize(switchId: String, from: WorkMode, to: WorkMode): InstallMoveOutcome {
        val movePlan = runCatching { buildPlan(switchId, from, to) }
            .getOrElse { return InstallMoveOutcome.failed(InstallCommitCodes.MOVE_PLAN_INVALID, MoveSet.redactEvidence(it.message.orEmpty())) }
        val invalid = validate(movePlan)
        if (invalid.isNotEmpty()) {
            return InstallMoveOutcome.failed(InstallCommitCodes.MOVE_PLAN_INVALID, MoveSet.redactEvidence(invalid.joinToString(",")))
        }
        var verified = 0
        var absent = 0
        for (entry in movePlan.inbound) {
            val live = entry.livePath
            val store = MoveSet.storePathFor(entry.owner, live)
            val result = runScript(verifyScript(entry, live, store, switchId, to))
            val outcome = outcomeOf(result)
            if (!outcome.ok) {
                appendManifest(switchId, "materialize", false, "${outcome.code}:$live")
                return InstallMoveOutcome.failed(
                    if (outcome.code == ROOT_OPERATION_UNCERTAIN) outcome.code else InstallCommitCodes.MATERIALIZE_FAILED,
                    MoveSet.redactEvidence("${outcome.code}:$live ${outcome.detail}".trim()),
                )
            }
            if (outcome.detail == SKIPPED) absent++ else verified++
        }
        appendManifest(switchId, "materialize", true, "verified=$verified absent=$absent")
        log("install-move materialize ok switch=$switchId verified=$verified absent=$absent")
        return InstallMoveOutcome.OK
    }

    /**
     * 逆序撤销，且**只撤真正执行过的阶段**（S3）。
     *
     * 原实现无条件"先 inbound 反向（live→store 到**目标**槽）再 outbound 反向"，在 4b 中途失败时
     * 会把还没搬出 live 的目录搬进 `modes/<目标>/...`（错槽 + live 丢失 + 回报"已回滚"）。
     * 现在：
     * - `IN ∈ completed` → 先撤入仓（`live → store`）；
     * - `OUT ∈ completed` → 再撤出仓（`store → live`）；
     * - 空集合 → 什么都不做（`OK`）。
     */
    override fun rollback(
        switchId: String,
        from: WorkMode,
        to: WorkMode,
        completed: Set<InstallMovePhase>,
    ): InstallMoveOutcome {
        if (completed.isEmpty()) {
            log("install-move rollback skipped switch=$switchId (no completed phase)")
            return InstallMoveOutcome.OK
        }
        return runPhase("rollback", InstallCommitCodes.MOVE_ROLLBACK_FAILED, switchId, from, to) { plan ->
            val undoInbound = if (InstallMovePhase.IN in completed) {
                plan.inbound.reversed().map { entry -> step(entry, storeToLive = false) }
            } else {
                emptyList()
            }
            val undoOutbound = if (InstallMovePhase.OUT in completed) {
                plan.outbound.reversed().map { entry -> step(entry, storeToLive = true) }
            } else {
                emptyList()
            }
            undoInbound + undoOutbound
        }
    }

    // ------------------------------------------------------------------ internals

    private fun runPhase(
        phase: String,
        code: String,
        switchId: String,
        from: WorkMode,
        to: WorkMode,
        steps: (MovePlan) -> List<Step>,
    ): InstallMoveOutcome {
        val movePlan = runCatching { buildPlan(switchId, from, to) }
            .getOrElse { return InstallMoveOutcome.failed(InstallCommitCodes.MOVE_PLAN_INVALID, MoveSet.redactEvidence(it.message.orEmpty())) }
        val invalid = validate(movePlan)
        if (invalid.isNotEmpty()) {
            appendManifest(switchId, phase, false, invalid.joinToString(","))
            return InstallMoveOutcome.failed(InstallCommitCodes.MOVE_PLAN_INVALID, MoveSet.redactEvidence(invalid.joinToString(",")))
        }
        var moved = 0
        var skipped = 0
        for (step in steps(movePlan)) {
            val result = runScript(moveScript(switchId, phase, step))
            val outcome = outcomeOf(result)
            if (!outcome.ok) {
                appendManifest(switchId, phase, false, "${outcome.code}:${step.entry.livePath}")
                log(
                    MoveSet.redactEvidence("install-move $phase failed switch=$switchId path=${step.entry.livePath} " +
                        "code=${outcome.code} detail=${outcome.detail}"),
                )
                return InstallMoveOutcome.failed(
                    outcome.code.ifEmpty { code },
                    MoveSet.redactEvidence("${step.entry.livePath} ${outcome.detail}".trim()),
                )
            }
            if (outcome.detail == SKIPPED) skipped++ else moved++
        }
        appendManifest(switchId, phase, true, "moved=$moved skipped=$skipped")
        log("install-move $phase ok switch=$switchId moved=$moved skipped=$skipped")
        return InstallMoveOutcome.OK
    }

    private fun buildPlan(switchId: String, from: WorkMode, to: WorkMode): MovePlan =
        MoveSet.plan(
            switchId = switchId,
            from = from,
            to = to,
            dynamicFrom = dynamicFrom,
            dynamicTo = dynamicTo,
        )

    /** 出/入是两次独立的子计划，同一 livePath 在两个方向重复出现属正常语义（同 `FileSwitchMoveStage`）。 */
    private fun validate(plan: MovePlan): List<String> =
        (MoveSet.validate(plan.outbound, plan.owners) + MoveSet.validate(plan.inbound, plan.owners)).distinct()

    private data class Step(val entry: MoveEntry, val storeToLive: Boolean) {
        val source: String get() = if (storeToLive) storePath else entry.livePath

        val storePath: String get() = MoveSet.storePathFor(entry.owner, entry.livePath)

        val target: String get() = if (storeToLive) entry.livePath else storePath
    }

    private fun step(entry: MoveEntry, storeToLive: Boolean): Step = Step(entry, storeToLive)

    /** 脚本首行标记（测试与排障用；`<phase>` = out/in/materialize/rollback，`<dir>` = 搬移方向）。 */
    private fun markerOf(phase: String, step: Step): String = buildString {
        append("#maplesu:move:").append(phase).append(':')
            .append(if (step.storeToLive) DIR_STORE_TO_LIVE else DIR_LIVE_TO_STORE)
            .append(':').append(step.entry.livePath).append('\n')
    }

    private fun moveScript(switchId: String, phase: String, step: Step): String = buildString {
        append(markerOf(phase, step))
        append("umask 077\n")
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(switchId))
        val forwardPhase = if (phase == "rollback") {
            if (step.storeToLive) "out" else "in"
        } else phase
        val entryKey = "${step.entry.owner.suffix}-${Hashes.sha256Hex(step.entry.livePath)}"
        append("T=").append(q("${MoveSet.INSTALLER_ROOT}/move-forward/$switchId/$forwardPhase")).append('\n')
        append("F=\"\$T/$entryKey\"\n")
        if (phase == "rollback") {
            // Older transactions lack entry receipts; keep their existing recovery path.
            append("if [ -f \"\$T/tracked\" ] && [ ! -f \"\$F\" ]; then echo '").append(SKIPPED).append("'; exit 0; fi\n")
            append("R=").append(q(MoveSet.rollbackReceiptPath(switchId, step.entry, step.storeToLive))).append('\n')
            append("if [ -f \"\$R\" ]; then echo '").append(SKIPPED).append("'; exit 0; fi\n")
            append("mkdir -p \"\$(dirname \"\$R\")\" || { echo 'MKDIR_FAILED'; exit 42; }\n")
            append("finish_move() { touch \"\$R\" && sync || { echo 'RECEIPT_FAILED'; exit 53; }; }\n")
        } else {
            append("mkdir -p \"\$T\" && touch \"\$T/tracked\" || { echo 'RECEIPT_FAILED'; exit 53; }\n")
            append("finish_move() { :; }\n")
        }
        append("L=").append(q(step.source)).append('\n')
        append("S=").append(q(step.target)).append('\n')
        // 源不存在 = 已收敛（`FileMoveExecutor` 的 Step.Skipped 语义）。
        append("if [ ! -e \"\$L\" ] && [ ! -L \"\$L\" ]; then finish_move; echo '").append(SKIPPED)
        append("'; exit 0; fi\n")
        // 受保护后代在**两个方向**上都存在：out 时要把它们排除在搬移之外；in/回滚时目标 live
        // 目录里它们还在（store 里没有），所以不能整树 rename，必须逐子项合并回去。
        val preserved = MoveSet.preservedPathsUnder(step.entry.livePath)
        if (phase != "rollback") {
            append("touch \"\$F\" && sync || { echo 'RECEIPT_FAILED'; exit 53; }\n")
        }
        if (phase == "out") {
            if (preserved.isNotEmpty()) {
                append("if [ -L \"\$S\" ] || { [ -e \"\$S\" ] && [ ! -d \"\$S\" ]; }; then echo 'DEST_EXISTS'; exit 41; fi\n")
            }
            // A manually restored/reinstalled source can coexist with its old offline store.
            // Capture the current live tree and retain the superseded snapshot outside both modes.
            append("if [ -e \"\$S\" ] || [ -L \"\$S\" ]; then\n")
            append(" A=").append(q("${MoveSet.INSTALLER_ROOT}/move-conflicts/$switchId")).append('\n')
            append(" mkdir -p \"\$A\" || { echo 'MKDIR_FAILED'; exit 42; }\n")
            append(" P=\$(mktemp -d \"\$A/$entryKey.XXXXXXXX\") || { echo 'MKDIR_FAILED'; exit 42; }\n")
            append(" mv \"\$S\" \"\$P/data\" && sync || { echo 'RENAME_FAILED'; exit 45; }\nfi\n")
        }
        if (preserved.isEmpty()) {
            if (phase == "rollback" && step.storeToLive && step.entry.livePath == "/data/adb/ksud") {
                append("if [ -f \"\$L\" ] && [ ! -L \"\$L\" ] && [ -f \"\$S\" ] && [ ! -L \"\$S\" ] && cmp -s \"\$L\" \"\$S\"; then\n")
                append(" mv -f \"\$L\" \"\$S\" || { echo 'RENAME_FAILED'; exit 45; }\n")
                append(" finish_move\n echo '").append(OK).append("'\n exit 0\nfi\n")
            }
            append("if [ -e \"\$S\" ] || [ -L \"\$S\" ]; then echo 'DEST_EXISTS'; exit 41; fi\n")
            append("mkdir -p \"\$(dirname \"\$S\")\" || { echo 'MKDIR_FAILED'; exit 42; }\n")
            if (step.entry.mount == MoveMount.CROSS_MOUNT_COPY) {
                append("cp -a \"\$L\" \"\$S\" || { echo 'COPY_FAILED'; exit 43; }\n")
                append("sync\n")
                append("rm -rf \"\$L\" || { echo 'DELETE_FAILED'; exit 44; }\n")
            } else {
                // A1：同挂载 rename 失败不得静默降级为 copy。
                append("mv \"\$L\" \"\$S\" || { echo 'RENAME_FAILED'; exit 45; }\n")
            }
            // A3：目标在 + 源消失 + 类型不变。
            append("if [ -e \"\$L\" ] || [ -L \"\$L\" ]; then echo 'SOURCE_REMAINS'; exit 46; fi\n")
            append("if [ ! -e \"\$S\" ] && [ ! -L \"\$S\" ]; then echo 'TARGET_MISSING'; exit 47; fi\n")
            append(shapeCheck(step.entry.kind))
        } else {
            append(mergeBody(step, preserved))
        }
        append("finish_move\necho '").append(OK).append("'\n")
    }

    /**
     * 含 [MoveSet.NEVER_MOVE] 后代的条目：**逐子项**搬移并保留受保护子树（M5）。
     *
     * 为什么不能整树 rename：`/data/adb/maplesu` 里含 `installer`（设备锁 + pending 标记），
     * 整树搬走会破坏锁的互斥/释放语义（服务端 `withLockSafe` 正在持锁时尤其危险）。
     *
     * 语义：
     * - 目标目录允许已存在（部分失败/重试），但**同名子项存在即 `DEST_EXISTS`**（绝不覆盖）；
     * - 保留项事前记下"是否存在"，搬完复核仍在原处（`PRESERVED_MISSING`，fail-closed）；
     * - 源目录搬空后 `rmdir`（best-effort），让 store 侧"必须消失"的不变量仍然成立。
     */
    private fun mergeBody(step: Step, preserved: List<String>): String = buildString {
        val op = if (step.entry.mount == MoveMount.CROSS_MOUNT_COPY) {
            "cp -a \"\$c\" \"\$S/\" && sync && rm -rf \"\$c\" || { echo 'COPY_FAILED'; exit 43; }\n"
        } else {
            "mv \"\$c\" \"\$S/\" || { echo 'RENAME_FAILED'; exit 45; }\n"
        }
        append("if [ -L \"\$S\" ] || { [ -e \"\$S\" ] && [ ! -d \"\$S\" ]; }; then echo 'DEST_EXISTS'; exit 41; fi\n")
        append("if [ ! -d \"\$L\" ] || [ -L \"\$L\" ]; then echo 'SHAPE_MISMATCH'; exit 48; fi\n")
        append("mkdir -p \"\$S\" || { echo 'MKDIR_FAILED'; exit 42; }\n")
        // 保留项的存在性快照（下标变量名 H0..Hn）。
        preserved.forEachIndexed { index, path ->
            append("H").append(index).append("=0\n")
            append("if [ -e ").append(q(path)).append(" ] || [ -L ").append(q(path))
                .append(" ]; then H").append(index).append("=1; fi\n")
        }
        if (step.storeToLive) {
            append("for c in \"\$L\"/* \"\$L\"/.[!.]* \"\$L\"/..?*; do\n")
            append("  [ -e \"\$c\" ] || [ -L \"\$c\" ] || continue\n")
            append("  n=\$(basename \"\$c\")\n")
            if (MoveSet.RECOVERY_ROOT in preserved) {
                // Older stores contain shared recovery data. Keep the live identity and retain the old
                // encrypted copy outside the mode stores; different ciphertext is normal.
                append("  if { [ \"\$n\" = recovery ] || [ \"\$n\" = normal-webui-token ] || [ \"\$n\" = lite_hidden ]; } && { [ -e \"\$S/\$n\" ] || [ -L \"\$S/\$n\" ]; }; then\n")
                append("    mkdir -p ").append(q(MoveSet.LEGACY_RECOVERY_ROOT))
                    .append(" || { echo 'MKDIR_FAILED'; exit 42; }\n")
                append("    a=\$(mktemp -d ").append(q("${MoveSet.LEGACY_RECOVERY_ROOT}/${step.entry.owner.suffix}.XXXXXXXX"))
                    .append(") || { echo 'MKDIR_FAILED'; exit 42; }\n")
                append("    mv \"\$c\" \"\$a/\$n\" || { echo 'RENAME_FAILED'; exit 45; }\n")
                append("    continue\n  fi\n")
            }
            append("  if [ -e \"\$S/\$n\" ] || [ -L \"\$S/\$n\" ]; then echo 'DEST_EXISTS'; exit 41; fi\n")
            append("  ").append(op)
            append("done\n")
        } else {
            append("for c in \"\$L\"/* \"\$L\"/.[!.]* \"\$L\"/..?*; do\n")
            append("  [ -e \"\$c\" ] || [ -L \"\$c\" ] || continue\n")
            append("  n=\$(basename \"\$c\")\n")
            append("  case \"\$n\" in\n")
            preserved.forEach { path ->
                append("    ").append(q(path.substringAfterLast('/'))).append(") continue ;;\n")
            }
            append("  esac\n")
            append("  if [ -e \"\$S/\$n\" ] || [ -L \"\$S/\$n\" ]; then echo 'DEST_EXISTS'; exit 41; fi\n")
            append("  ").append(op)
            append("done\n")
        }
        // 源目录搬空后清掉空壳（store 侧"必须消失"依赖它）。
        append("rmdir \"\$L\" 2>/dev/null || true\n")
        append("for c in \"\$L\"/* \"\$L\"/.[!.]* \"\$L\"/..?*; do\n")
        append("  [ -e \"\$c\" ] || [ -L \"\$c\" ] || continue\n")
        if (!step.storeToLive) {
            // 出仓时受保护子树应当原地保留；其余残留仍视为搬移失败。
            append("  case \"\$(basename \"\$c\")\" in\n")
            preserved.forEach { path ->
                append("    ").append(q(path.substringAfterLast('/'))).append(") continue ;;\n")
            }
            append("  esac\n")
        }
        append("  echo 'SOURCE_REMAINS'; exit 46\n")
        append("done\n")
        append("if [ ! -d \"\$S\" ]; then echo 'TARGET_MISSING'; exit 47; fi\n")
        preserved.forEachIndexed { index, path ->
            append("if [ \"\$H").append(index).append("\" = 1 ] && [ ! -e ").append(q(path))
                .append(" ] && [ ! -L ").append(q(path))
                .append(" ]; then echo 'PRESERVED_MISSING'; exit 50; fi\n")
        }
    }

    /**
     * 4f 的单条目校验脚本（S2）。
     *
     * 唯一强不变量是"`store` 消失"；`live` 缺失 ⇒ `SKIPPED`（合法），`live` 存在 ⇒ 形态校验。
     * 标记第 4 段固定为 [DIR_VERIFY]，与移动脚本保持同构（`<phase>:<dir>:<livePath>`）。
     */
    private fun verifyScript(entry: MoveEntry, livePath: String, storePath: String, switchId: String, to: WorkMode): String = buildString {
        append("#maplesu:move:verify:").append(DIR_VERIFY).append(':').append(livePath).append('\n')
        append("L=").append(q(livePath)).append('\n')
        append("S=").append(q(storePath)).append('\n')
        append("if [ -e \"\$S\" ] || [ -L \"\$S\" ]; then echo 'STORE_REMNANT'; exit 52; fi\n")
        if (false && livePath in setOf("/data/adb/ksu", "/data/adb/ksud")) {
            // Userland deployment may create these paths after an empty inbound
            // slot. Existing rollback must park them before restoring the source.
            val receipts = "${MoveSet.INSTALLER_ROOT}/move-forward/$switchId/in"
            val key = "${entry.owner.suffix}-${Hashes.sha256Hex(livePath)}"
            append("T=").append(q(receipts)).append('\n')
            append("mkdir -p \"\$T\" && touch \"\$T/tracked\" \"\$T/$key\" && sync || { echo 'RECEIPT_FAILED'; exit 53; }\n")
        }
        append("if [ ! -e \"\$L\" ] && [ ! -L \"\$L\" ]; then echo '").append(SKIPPED)
        append("'; exit 0; fi\n")
        append(liveShapeCheck(entry))
        append("echo '").append(OK).append("'\n")
    }

    /** `live` 存在时的形态判据（只在这里用 `expectedShape`；`ABSENT` 表示"应当不存在"）。 */
    private fun liveShapeCheck(entry: MoveEntry): String = when (entry.expectedShape) {
        MoveEntry.ABSENT -> "echo 'SHAPE_MISMATCH'; exit 48\n"
        // Preserve the link itself; an already missing optional module is not a move failure.
        MoveEntry.SYMLINK -> "if [ ! -L \"\$L\" ]; then echo 'SHAPE_MISMATCH'; exit 48; fi\n"
        MoveEntry.SYMLINK_TREE -> "if [ ! -d \"\$L\" ]; then echo 'SHAPE_MISMATCH'; exit 48; fi\n"
        MoveEntry.FILE -> "if [ ! -f \"\$L\" ] || [ -L \"\$L\" ]; then echo 'SHAPE_MISMATCH'; exit 48; fi\n"
        // REAL_DIR / REAL_DIR_EMPTY：只要求"是目录且不是软链"（与 shapeCheck 同口径）。
        else -> "if [ ! -d \"\$L\" ] || [ -L \"\$L\" ]; then echo 'SHAPE_MISMATCH'; exit 48; fi\n"
    }

    private fun shapeCheck(kind: MoveKind): String = when (kind) {
        MoveKind.SYMLINK -> "if [ ! -L \"\$S\" ]; then echo 'SHAPE_MISMATCH'; exit 48; fi\n"
        MoveKind.FILE -> "if [ ! -f \"\$S\" ] || [ -L \"\$S\" ]; then echo 'SHAPE_MISMATCH'; exit 48; fi\n"
        // TREE：只要求"是目录"。软链树（`MoveEntry.SYMLINK_TREE`）本身的形态由 MoveSet 的
        // expectedShape 描述，rename 会原样搬走，这里不做与它冲突的强判。
        MoveKind.TREE -> "if [ ! -d \"\$S\" ]; then echo 'SHAPE_MISMATCH'; exit 48; fi\n"
    }

    private fun outcomeOf(result: RootScriptResult): InstallMoveOutcome {
        if (result.error.contains(ROOT_OPERATION_UNCERTAIN) || result.output.contains(ROOT_OPERATION_UNCERTAIN)) {
            return InstallMoveOutcome.failed(
                ROOT_OPERATION_UNCERTAIN,
                MoveSet.redactEvidence("${result.error} ${result.output}".trim()).takeLast(240),
            )
        }
        if (result.ok) {
            val output = result.output
            if (output.lineSequence().any { it.trim() == OK }) return InstallMoveOutcome.OK
            if (output.lineSequence().any { it.trim() == SKIPPED }) {
                return InstallMoveOutcome(true, detail = SKIPPED)
            }
            // 退出码 0 但没有标记：输出被截断/被包装 → 不做"看起来成功"的推断。
            return InstallMoveOutcome.failed(MoveSet.Errors.VERIFY_FAILED, "no marker")
        }
        val token = FAILURE_TOKENS.firstOrNull { result.output.contains(it) } ?: ""
        val code = when (token) {
            "DEST_EXISTS" -> MoveSet.Errors.DEST_EXISTS
            "RENAME_FAILED" -> MoveSet.Errors.RENAME_FAILED
            "COPY_FAILED", "DELETE_FAILED" -> MoveSet.Errors.COPY_VERIFY_FAILED
            "MKDIR_FAILED" -> MoveSet.Errors.RENAME_FAILED
            "SHAPE_MISMATCH", "SYMLINK_TARGET_LOST" -> MoveSet.Errors.SOURCE_SHAPE_MISMATCH
            else -> MoveSet.Errors.VERIFY_FAILED
        }
        val detail = if (token.isEmpty()) {
            "exit=${result.code} ${MoveSet.redactEvidence(result.output.trim()).takeLast(120)}"
        } else {
            token
        }
        return InstallMoveOutcome.failed(code, MoveSet.redactEvidence(detail))
    }

    private fun runScript(script: String): RootScriptResult = try {
        runner(script)
    } catch (error: Throwable) {
        if (error is Error) throw error
        RootScriptResult(false, -1, "${error.javaClass.simpleName}: ${error.message}")
    }

    private fun appendManifest(switchId: String, phase: String, ok: Boolean, detail: String) {
        runCatching {
            manifestFile.parentFile?.mkdirs()
            manifestFile.appendText(
                buildString {
                    append("{\"schema\":1,\"switchId\":\"").append(switchId).append('"')
                    append(",\"phase\":\"").append(phase).append('"')
                    append(",\"ok\":").append(ok)
                    append(",\"detail\":\"").append(MoveSet.redactEvidence(detail).replace('"', '\'')).append('"')
                    append(",\"atMillis\":").append(System.currentTimeMillis())
                    append("}\n")
                },
            )
        }.onFailure { log("install-move manifest append failed: ${it.javaClass.simpleName}") }
    }

    companion object {
        const val OK = "OK"
        const val SKIPPED = "SKIPPED"
        private const val ROOT_OPERATION_UNCERTAIN = "MODE_SWITCH_ROOT_OPERATION_UNCERTAIN"

        /** 搬移方向（脚本首行标记的第 4 段；测试靠它断言"回滚只撤做过的方向"）。 */
        const val DIR_LIVE_TO_STORE = "live2store"
        const val DIR_STORE_TO_LIVE = "store2live"

        /** 4f 校验脚本的方向段（不搬移，只校验；保持标记同构便于解析）。 */
        const val DIR_VERIFY = "expect"

        private val FAILURE_TOKENS = listOf(
            "DEST_EXISTS", "MKDIR_FAILED", "RENAME_FAILED", "COPY_FAILED", "DELETE_FAILED",
            "SOURCE_REMAINS", "TARGET_MISSING", "SHAPE_MISMATCH", "PRESERVED_MISSING",
            "SYMLINK_TARGET_LOST", "STORE_REMNANT",
            "RECEIPT_FAILED",
            // 兼容旧脚本/旧现场：`LIVE_MISSING` 不再是失败判据，但保留解析。
            "LIVE_MISSING",
        )

        private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
