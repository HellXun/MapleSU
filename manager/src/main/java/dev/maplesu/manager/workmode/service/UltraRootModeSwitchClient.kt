package dev.maplesu.manager.workmode.service

import dev.maplesu.manager.runtime.RootBackend
import dev.maplesu.manager.runtime.RootExec
import dev.maplesu.manager.runtime.RootExecResult
import dev.maplesu.manager.workmode.Hashes
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.control.ControlJsonCodec
import dev.maplesu.manager.workmode.detect.ProcBootIdSource
import dev.maplesu.manager.workmode.switch.BaselineManifest
import dev.maplesu.manager.workmode.switch.CandidateResult
import dev.maplesu.manager.workmode.switch.ModeSwitchCodes
import dev.maplesu.manager.workmode.switch.ModeSwitchHashes
import dev.maplesu.manager.workmode.switch.ModeSwitchPreflight
import dev.maplesu.manager.workmode.switch.SwitchStateNames
import dev.maplesu.manager.workmode.switch.SwitchSteps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

class UltraRootModeSwitchClient(
    private val runner: (String) -> RootExecResult = { script -> RootExec.execute(script) },
    private val workRoot: File,
    private val logger: (String) -> Unit = {},
    private val destinationRoots: List<File> = emptyList(),
    private val baselineRoot: File = File(workRoot, "boot/baseline"),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val bootIdSource: () -> String = { ProcBootIdSource.read() },
    /** Legacy clients without sourceRecovery may still require their stock-baseline contract. */
    private val requireBaselineForCommit: Boolean = false,
    /** Recover a copy of the installed carrier; PRE remains the rollback image. */
    private val sourceRecovery: ((WorkMode, String, File, File, Boolean) -> CandidateResult)? = null,
) : ModeSwitchClient {

    // ------------------------------------------------------------------ 公开状态

    private val txRoot: File get() = File(workRoot, "tx")

    private val planCache = LinkedHashMap<String, UltraSwitchPlanFile>()
    private val stateCache = LinkedHashMap<String, UltraSwitchStateFile>()

    // ------------------------------------------------------------------ 12 方法

    override suspend fun preflight(switchId: String, planFields: Map<String, String>): ClientResult = io {
        guarded { preflightInternal(switchId, planFields) }
    }

    override suspend fun begin(
        switchId: String,
        planFields: Map<String, String>,
        superuserJson: File,
        settingsMirrorJson: File,
    ): ClientResult = io {
        guarded { beginInternal(switchId, planFields, superuserJson, settingsMirrorJson) }
    }

    override suspend fun snapshot(switchId: String): ClientResult = io {
        guarded { snapshotInternal(switchId) }
    }

    override suspend fun exportImage(
        switchId: String,
        kind: String,
        partition: String,
        destination: File,
    ): Boolean = io {
        runCatching { exportImageInternal(switchId, kind, partition, destination) }.getOrElse { error ->
            logger("ultra-switch exportImage failed: ${error.javaClass.simpleName}")
            false
        }
    }

    override suspend fun submitCandidate(
        switchId: String,
        partition: String,
        candidate: File,
        candidateSha256: String,
    ): ClientResult = io {
        guarded { submitCandidateInternal(switchId, partition, candidate, candidateSha256) }
    }

    override suspend fun commit(switchId: String): ClientResult = io {
        guarded { commitInternal(switchId) }
    }

    override suspend fun rollback(switchId: String): ClientResult = io {
        guarded { rollbackInternal(switchId) }
    }

    override suspend fun abort(switchId: String): ClientResult = io {
        guarded { abortInternal(switchId) }
    }

    override fun status(switchId: String): ClientStatus? {
        return runCatching {
            if (switchId.isNotEmpty() && !SWITCH_ID.matches(switchId)) return@runCatching null
            val id = if (switchId.isEmpty()) latestSwitchId() ?: return@runCatching null else switchId
            val state = readStateFile(id)
            if (state != null) {
                statusOf(state)
            } else {
                // state.json 丢失时按 journal 末行重建"最近已知状态"（只读呈现，不据此写分区）。
                journalHead(id)
            }
        }.getOrNull()
    }

    override fun statusExact(switchId: String): ClientStatus? {
        if (switchId.isEmpty()) return latestStatusExact()
        require(SWITCH_ID.matches(switchId)) { "SWITCH_STATUS_TASK_INVALID" }
        val raw = readTextExact(stateFile(switchId), Mark.STATE_READ)
        if (raw != null) {
            val state = ControlJsonCodec.decodeOrNull<UltraSwitchStateFile>(raw)
                ?: error("SWITCH_STATUS_RECORD_INVALID")
            check(state.switchId == switchId) { "SWITCH_STATUS_TASK_MISMATCH" }
            return statusOf(state)
        }
        val journal = readTextExact(journalFile(switchId), Mark.JOURNAL_READ) ?: return null
        return journalHead(switchId, journal) ?: error("SWITCH_STATUS_RECORD_INVALID")
    }

    override suspend fun verify(switchId: String): ClientResult = io {
        guarded { verifyInternal(switchId) }
    }

    override suspend fun markRebootRequested(switchId: String): Boolean = io {
        runCatching {
            if (!SWITCH_ID.matches(switchId)) return@runCatching false
            val state = loadState(switchId) ?: return@runCatching false
            if (state.state != SwitchStateNames.REBOOT_REQUIRED) return@runCatching false
            persist(state.copy(rebootRequested = true, updatedAtMillis = clock()))
        }.getOrDefault(false)
    }

    override suspend fun exportLog(switchId: String, destination: File): File? = io {
        runCatching {
            if (!SWITCH_ID.matches(switchId)) return@runCatching null
            if (!writeDestinationAllowed(destination)) return@runCatching null
            val state = readStateFile(switchId) ?: loadState(switchId) ?: return@runCatching null
            val status = statusOf(state)
            val journal = readTextQuietly(journalFile(switchId), Mark.JOURNAL_READ).orEmpty()
            val text = buildString {
                appendLine("switchId=${status.switchId}")
                appendLine("state=${status.state} stage=${status.stage} step=${status.step}")
                appendLine("from=${status.fromMode} to=${status.toMode} planSha=${status.planSha256}")
                appendLine("error=${status.error} detail=${status.errorDetail}")
                appendLine("rebootRequired=${status.rebootRequired} bootObserved=${status.bootObserved}")
                appendLine("backend=ULTRA_ROOT workRoot=${workRoot.absolutePath}")
                status.partitions.forEach { row ->
                    appendLine(
                        "partition ${row.name} role=${row.role} size=${row.size} " +
                            "pre=${row.preSha256} target=${row.targetSha256} observed=${row.observedSha256}",
                    )
                }
                appendLine("journal:")
                journal.lineSequence().filter { it.isNotBlank() }.toList().takeLast(200)
                    .forEach { appendLine(it) }
                appendLine("本产品不自动重启、不自动重试；分区回读失败时请按 pre 镜像人工恢复。")
            }
            destination.parentFile?.mkdirs()
            destination.writeText(text, Charsets.UTF_8)
            destination.takeIf { it.isFile }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ preflight（只读）

    private fun preflightInternal(switchId: String, planFields: Map<String, String>): ClientResult {
        val probe = resolveRequest(switchId, planFields)
        val request = probe.request
        logger(
            "ultra-switch preflight ok switch=${request.switchId} ${request.from}->${request.to} " +
                "source=${request.sourcePartition.orEmpty().ifEmpty { "unknown" }} " +
                "target=${request.targetPartition} partitions=${request.plannedPartitions}",
        )
        return ClientResult(true)
    }

    /** Resolve the actual input for this call; a previous preflight is never an execution ticket. */
    private fun resolveRequest(switchId: String, planFields: Map<String, String>): UltraProbe {
        val parsed = parseRequest(switchId, planFields)
        requireAbsoluteWorkRoot()
        // 1) root 可用（Ultra 通道下唯一可信判据是"跑一条命令并以 0 退出"）。
        val root = runScript(script(Mark.ROOT, "id -u"))
        if (!ok(root)) {
            throw Reject(ModeSwitchCodes.ROOT_UNAVAILABLE, root.detail.ifBlank { "id -u failed" })
        }
        if (root.output.trim().lineSequence().lastOrNull()?.trim() != "0") {
            throw Reject(ModeSwitchCodes.ROOT_UNAVAILABLE, "uid=${root.output.trim()}")
        }
        // 2) 槽位（只读；非 _a/_b 一律拒绝，避免拼出越界节点名）。
        val slot = readSlotSuffix()
        // 3) 每个计划分区：存在 + 大小合理（只读）。
        val devices = LinkedHashMap<String, String>()
        val sizes = LinkedHashMap<String, Long>()
        for (name in ModeSwitchPreflight.allowedTargets(parsed.from) + ModeSwitchPreflight.allowedTargets(parsed.to)) {
            val probed = probePartition(name, slot) ?: continue
            devices[name] = probed.first
            sizes[name] = probed.second
        }
        val kmi = if (false && planFields["targetPartition"].isNullOrBlank()) {
            val kernel = runScript(script(Mark.KERNEL, "uname -r"))
            dev.maplesu.manager.workmode.switch.ModeSwitchKmi.parse(kernel.output.trim())
        } else null
        val target = ModeSwitchPreflight.resolveTarget(parsed.to, planFields["targetPartition"], devices.keys, kmi)
            ?: throw Reject(ModeSwitchCodes.PLAN_INVALID, "target partition")
        val source = parsed.sourcePartition ?: knownSourcePartition(parsed.from, slot).orEmpty()
        val request = parsed.copy(targetPartition = target, sourcePartition = source,
            availablePartitions = devices.keys)
        val planned = request.plannedPartitions
        if (planned.any { it !in devices }) throw Reject(ModeSwitchCodes.LAYOUT_UNSUPPORTED,
            "partition ${planned.first { it !in devices }} missing")
        val plannedSizes = sizes.filterKeys { it in planned }
        plannedSizes.entries.firstOrNull { it.value <= 0 || it.value > MAX_PARTITION_BYTES }?.let {
            throw Reject(ModeSwitchCodes.LAYOUT_UNSUPPORTED, "partition ${it.key} size=${it.value}")
        }
        return UltraProbe(slot, devices, sizes, request)
    }

    private fun readSlotSuffix(): String {
        val result = runScript(
            script(
                Mark.SLOT,
                "getprop ro.boot.slot_suffix 2>/dev/null; getprop ro.boot.slot 2>/dev/null",
            ),
        )
        if (!ok(result)) throw Reject(ModeSwitchCodes.SLOT_INVALID, "slot unreadable")
        val raw = result.output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        if (raw.isEmpty()) return ""
        return when (raw) {
            "_a", "a" -> "_a"
            "_b", "b" -> "_b"
            else -> throw Reject(ModeSwitchCodes.SLOT_INVALID, raw)
        }
    }

    private fun requireCurrentSlot(plan: UltraSwitchPlanFile) {
        val current = readSlotSuffix()
        if (current != plan.slotSuffix) {
            throw Reject(ModeSwitchCodes.SLOT_INVALID, "slot=$current expected=${plan.slotSuffix}")
        }
    }

    /**
     * 只读探测一个分区：`/dev/block/by-name/<name><slot>` 优先，退回不带的节点。
     * 返回 (devicePath, sizeBytes)；节点不存在返回 null。
     */
    private fun probePartition(name: String, slot: String): Pair<String, Long>? {
        if (name !in PARTITIONS) throw Reject(UltraRootSwitchCodes.PARTITION_FORBIDDEN, name)
        val candidates = buildList {
            if (slot.isNotEmpty()) add(devicePath(name + slot))
            add(devicePath(name))
        }
        val body = buildString {
            appendLine("d=''")
            candidates.forEach { candidate ->
                appendLine("if [ -z \"\$d\" ] && [ -e ${q(candidate)} ]; then d=${q(candidate)}; fi")
            }
            appendLine("if [ -z \"\$d\" ]; then echo 'dev='; echo 'size=0'; exit 0; fi")
            appendLine("echo \"dev=\$d\"")
            appendLine("s=\$(blockdev --getsize64 \"\$d\" 2>/dev/null)")
            appendLine("if [ -z \"\$s\" ] || [ \"\$s\" = \"0\" ]; then")
            appendLine("  b=\$(basename \"\$(readlink -f \"\$d\" 2>/dev/null)\" 2>/dev/null)")
            appendLine("  s=\$(cat \"/sys/class/block/\$b/size\" 2>/dev/null)")
            appendLine("  [ -n \"\$s\" ] && s=\$((s * 512))")
            appendLine("fi")
            appendLine("echo \"size=\${s:-0}\"")
        }
        val result = runScript(script(Mark.DEVICE + name, body))
        if (!ok(result)) return null
        val device = result.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("dev=") }
            ?.removePrefix("dev=")
            .orEmpty()
        if (device.isEmpty()) return null
        if (!isAllowedDevicePath(device, name, slot)) {
            throw Reject(UltraRootSwitchCodes.PARTITION_FORBIDDEN, device)
        }
        val size = result.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("size=") }
            ?.removePrefix("size=")
            ?.toLongOrNull()
            ?: 0L
        return device to size
    }

    // ------------------------------------------------------------------ begin

    private fun beginInternal(
        switchId: String,
        planFields: Map<String, String>,
        superuserJson: File,
        settingsMirrorJson: File,
    ): ClientResult {
        val probe = resolveRequest(switchId, planFields)
        val request = probe.request
        if (!superuserJson.isFile || !settingsMirrorJson.isFile) {
            throw Reject(ModeSwitchCodes.PREPARE_EXPORT_FAILED, "mirror files missing")
        }
        if (superuserJson.length() <= 0 || settingsMirrorJson.length() <= 0) {
            throw Reject(ModeSwitchCodes.PREPARE_EXPORT_FAILED, "mirror files empty")
        }
        val partitions = request.plannedPartitions.map { name ->
            UltraSwitchPlanPartition(
                name = name,
                role = if (name == request.targetPartition) ROLE_WRITE else ROLE_RESTORE,
                device = probe.devices[name] ?: throw Reject(ModeSwitchCodes.LAYOUT_UNSUPPORTED, name),
                size = probe.sizes[name] ?: throw Reject(ModeSwitchCodes.LAYOUT_UNSUPPORTED, name),
            )
        }
        val now = clock()
        val plan = UltraSwitchPlanFile(
            switchId = request.switchId,
            fromMode = request.from.name,
            toMode = request.to.name,
            baselineId = request.baselineId,
            slotSuffix = probe.slot,
            targetPartition = request.targetPartition,
            sourcePartition = request.sourcePartition,
            createdAtMillis = now,
            partitions = partitions,
        )
        val planJson = ControlJsonCodec.encode(plan)
        if (!writeFileAtomically(planFile(request.switchId), planJson, Mark.PLAN_WRITE)) {
            throw Reject(UltraRootSwitchCodes.STATE_WRITE_FAILED, "plan.json")
        }
        if (!copyMirrors(request.switchId, superuserJson, settingsMirrorJson)) {
            throw Reject(ModeSwitchCodes.PREPARE_EXPORT_FAILED, "mirror copy")
        }
        val state = UltraSwitchStateFile(
            switchId = request.switchId,
            state = SwitchStateNames.BEGIN,
            stage = "S1",
            step = SwitchSteps.BEGIN,
            fromMode = request.from.name,
            toMode = request.to.name,
            baselineId = request.baselineId,
            planSha256 = Hashes.sha256Hex(planJson),
            startBootId = runCatching { bootIdSource() }.getOrDefault(""),
            createdAtMillis = now,
            updatedAtMillis = now,
            targetPartition = request.targetPartition,
            sourcePartition = request.sourcePartition,
            partitions = partitions.map { partition ->
                UltraSwitchPartitionState(
                    name = partition.name,
                    role = partition.role,
                    device = partition.device,
                    size = partition.size,
                )
            },
        )
        if (!persist(state)) {
            throw Reject(UltraRootSwitchCodes.STATE_WRITE_FAILED, "state.json")
        }
        appendJournal(state, "PREPARED", SwitchSteps.BEGIN)
        synchronized(this) {
            planCache[request.switchId] = plan
        }
        logger("ultra-switch begin ok switch=${request.switchId} planSha=${state.planSha256}")
        return ClientResult(true, status = statusOf(state))
    }

    private fun copyMirrors(switchId: String, superuserJson: File, settingsMirrorJson: File): Boolean {
        val dir = txDir(switchId)
        val superuserTarget = File(dir, SUPERUSER_MIRROR)
        val settingsTarget = File(dir, SETTINGS_MIRROR)
        val body = buildString {
            appendLine("umask 077")
            append("mkdir -p ${q(dir.absolutePath)}")
            append(" && cp -f ${q(superuserJson.absolutePath)} ${q(superuserTarget.absolutePath)}")
            append(" && cp -f ${q(settingsMirrorJson.absolutePath)} ${q(settingsTarget.absolutePath)}")
            append(" && chmod 0600 ${q(superuserTarget.absolutePath)} ${q(settingsTarget.absolutePath)}")
        }
        return ok(runScript(script(Mark.MIRRORS, body)))
    }

    // ------------------------------------------------------------------ snapshot

    /**
     * 全量快照：
     *
     * 1. **每个**计划分区（源 + 目标）都 `dd` 出 `pre-<name>.img` 并校验 sha/`wc -c`；
     *    为什么源分区也要镜像：回滚必须把源分区逐字节写回"切换前"，不能靠基线反推
     *    （基线是 stock，切换前源分区通常带本模式补丁，两者不同）。
     * 2. BASE is the producer/restore input: an explicit original, recovered carrier or unchanged PRE.
     *    Its actual baseSha256 is separate from the optional stock baselineSha256.
     * 3. `restoreRequired` 只对非目标分区分有意义（目标分区本来就要被候选覆盖）；
     *    它同时决定 commit 是否必须带 `BASE:<name>`。
     */
    private fun snapshotInternal(switchId: String): ClientResult {
        val state = loadStateOrThrow(switchId)
        if (state.state !in setOf(SwitchStateNames.BEGIN, SwitchStateNames.SNAPSHOT)) {
            throw Reject(UltraRootSwitchCodes.STATE_INVALID, state.state)
        }
        val plan = loadPlanOrThrow(switchId)
        validatePlan(plan)
        val target = plan.targetPartition

        // 1) 全量 PRE。
        val preShas = LinkedHashMap<String, String>()
        val preSizes = LinkedHashMap<String, Long>()
        for (planRow in plan.partitions) {
            val image = preImage(switchId, planRow.name)
            val snap = runScript(snapshotScript(planRow.name, planRow.device, image))
            if (!ok(snap)) throw Reject(ModeSwitchCodes.SNAPSHOT_FAILED, "partition ${planRow.name}")
            val sha = shaOf(snap.output)
                ?: throw Reject(ModeSwitchCodes.SNAPSHOT_FAILED, "no image sha ${planRow.name}")
            val size = firstInteger(snap.output)
                ?: throw Reject(ModeSwitchCodes.SNAPSHOT_FAILED, "no image size ${planRow.name}")
            if (size != planRow.size) {
                throw Reject(
                    UltraRootSwitchCodes.SNAPSHOT_VERIFY_FAILED,
                    "${planRow.name} image=$size partition=${planRow.size}",
                )
            }
            preShas[planRow.name] = sha
            preSizes[planRow.name] = size
        }

        // 2) 基线镜像（可选清单；没有 manifest 时所有分区的 baselineSha256 为空）。
        val manifest = if (sourceRecovery == null || plan.baselineId.isNotEmpty())
            resolveBaselineManifest(plan.baselineId) else null
        val baselineShas = manifest?.partitions
            ?.associate { it.name to it.sha256.trim().lowercase() }
            .orEmpty()
        val images = LinkedHashMap<String, String>()
        for (planRow in plan.partitions) {
            images["PRE:${planRow.name}"] = preImage(switchId, planRow.name).absolutePath
        }
        val baseShas = LinkedHashMap<String, String>()
        for (planRow in plan.partitions) {
            val baseline = baselineShas[planRow.name].orEmpty()
            if (baseline.isEmpty()) continue
            if (!ModeSwitchHashes.isHash(baseline)) {
                logger("ultra-switch snapshot: baseline sha invalid for ${planRow.name}")
                continue
            }
            val current = preShas.getValue(planRow.name)
            if (baseline == current) {
                // 当前内容与被记录的基线逐字节一致 → BASE 直接复用 PRE 镜像（不复制整份镜像）。
                images["BASE:${planRow.name}"] = images.getValue("PRE:${planRow.name}")
                baseShas[planRow.name] = baseline
                continue
            }
            val manifestId = manifest?.id.orEmpty()
            if (!BASELINE_ID.matches(manifestId)) {
                logger("ultra-switch snapshot: baseline id invalid for ${planRow.name}")
                continue
            }
            val copied = copyBaselineImage(switchId, manifestId, planRow.name, baseline)
            if (copied == null) {
                // 目标分区：只是少一份可导出的 BASE（facade 会另行以 PRE 失败码中止）。
                // 非目标分区：commit 会在任何 dd 之前以 SWITCH_BASELINE_REQUIRED 拒绝（fail closed）。
                logger("ultra-switch snapshot: baseline image unavailable for ${planRow.name} ($manifestId)")
                continue
            }
            images["BASE:${planRow.name}"] = copied.absolutePath
            baseShas[planRow.name] = baseline
        }
        val from = WorkMode.fromWire(plan.fromMode)
            ?: throw Reject(ModeSwitchCodes.PLAN_INVALID, "fromMode")
        for (planRow in plan.partitions) {
            if (baseShas.containsKey(planRow.name)) continue
            val knownSource = !plan.sourcePartition.isNullOrEmpty()
            val isSource = if (knownSource) planRow.name == plan.sourcePartition
                else planRow.name in ModeSwitchPreflight.allowedTargets(from)
            if (isSource && sourceRecovery != null) {
                val recovered = recoverSourceImage(switchId, from, planRow,
                    preShas.getValue(planRow.name), knownSource)
                images["BASE:${planRow.name}"] = recovered.first.absolutePath
                baseShas[planRow.name] = recovered.second
            } else if (!isSource || planRow.name == target) {
                images["BASE:${planRow.name}"] = images.getValue("PRE:${planRow.name}")
                baseShas[planRow.name] = preShas.getValue(planRow.name)
            }
        }

        // 3) 行状态。
        val rows = state.partitions.map { row ->
            val sha = preShas[row.name]
                ?: throw Reject(ModeSwitchCodes.PLAN_INVALID, "no planned image for ${row.name}")
            val size = preSizes.getValue(row.name)
            val baseline = baselineShas[row.name].orEmpty()
            row.copy(
                preSha256 = sha,
                preSize = size,
                baseSha256 = baseShas[row.name].orEmpty(),
                baselineSha256 = baseline,
                restoreRequired = row.name != target &&
                    (baseShas[row.name] ?: baseline.takeIf(ModeSwitchHashes::isHash))?.let { it != sha } == true,
                state = "SNAPSHOTTED",
            )
        }
        val next = state.copy(
            state = SwitchStateNames.SNAPSHOT,
            stage = "S2",
            step = SwitchSteps.SNAPSHOT,
            updatedAtMillis = clock(),
            partitions = rows,
            images = images,
            sourcePartition = state.sourcePartition?.takeIf(String::isNotEmpty)
                ?: rows.filter { it.baselineSha256.isNotEmpty() && it.preSha256 != it.baselineSha256 }
                    .singleOrNull()?.name.orEmpty(),
        )
        if (!persist(next)) throw Reject(UltraRootSwitchCodes.STATE_WRITE_FAILED, "state.json")
        for (row in rows) {
            appendJournal(
                next,
                "PRE_CAPTURED",
                SwitchSteps.SNAPSHOT,
                partition = row.name,
                expected = row.preSha256,
                observed = row.preSha256,
            )
        }
        logger(
            "ultra-switch snapshot ok switch=$switchId pre=" +
                rows.joinToString(",") { "${it.name}:${it.preSha256.take(12)}" } +
                " base=" + rows.joinToString(",") { "${it.name}:${it.baseSha256.take(12)}" } +
                " restore=" + rows.filter { it.restoreRequired }.joinToString(",") { it.name },
        )
        return ClientResult(true, status = statusOf(next))
    }

    private fun recoverSourceImage(
        switchId: String,
        from: WorkMode,
        row: UltraSwitchPlanPartition,
        preSha: String,
        sourceKnown: Boolean,
    ): Pair<File, String> {
        val appRoot = destinationRoots.firstOrNull()
            ?: throw Reject("SOURCE_RECOVERY_WORKSPACE_FAILED", "app workspace missing")
        val directory = File(appRoot, "$switchId/source-${row.name}")
        // The app owns the recovery output directory; root must not create it with uid 0.
        if ((!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) || !directory.canWrite()) {
            throw Reject("SOURCE_RECOVERY_WORKSPACE_FAILED", row.name)
        }
        val input = File(directory, "pre.img")
        if (!copyExportImage(preImage(switchId, row.name), input, preSha) ||
            !input.isFile || input.length() != row.size ||
            !preSha.equals(ModeSwitchHashes.sha256(input), true)) {
            throw Reject("SOURCE_RECOVERY_EXPORT_FAILED", row.name)
        }
        val recovered = sourceRecovery!!.invoke(from, row.name, input, File(directory, "recovered"), sourceKnown)
        if (!recovered.ok) throw Reject(recovered.code.ifEmpty { "SOURCE_RECOVERY_FAILED" }, recovered.detail)
        val image = recovered.image!!
        if (!input.isFile || input.length() != row.size ||
            !preSha.equals(ModeSwitchHashes.sha256(input), true)) {
            throw Reject("SOURCE_RECOVERY_INPUT_CHANGED", row.name)
        }
        if (!destinationAllowed(image) ||
            !java.nio.file.Files.isRegularFile(image.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
            image.length() != row.size || recovered.size != row.size ||
            !ModeSwitchHashes.isHash(recovered.sha256) ||
            !recovered.sha256.equals(ModeSwitchHashes.sha256(image), true)) {
            throw Reject("SOURCE_RECOVERY_OUTPUT_INVALID", row.name)
        }
        if (preSha.equals(recovered.sha256, true)) return preImage(switchId, row.name) to preSha
        val destination = baseImage(switchId, row.name)
        val body = buildString {
            appendLine("umask 077")
            append("cp -f ${q(image.absolutePath)} ${q(destination.absolutePath + ".tmp")}")
            append(" && chmod 0600 ${q(destination.absolutePath + ".tmp")}")
            append(" && mv -f ${q(destination.absolutePath + ".tmp")} ${q(destination.absolutePath)}")
            append(" && sha256sum ${q(destination.absolutePath)}")
            append(" && wc -c < ${q(destination.absolutePath)}")
        }
        val imported = runScript(script(Mark.SOURCE_IMPORT, body))
        if (!ok(imported) || !recovered.sha256.equals(shaOf(imported.output), true) ||
            firstInteger(imported.output) != row.size) {
            throw Reject("SOURCE_RECOVERY_IMPORT_FAILED", row.name)
        }
        logger("ultra-switch source recovered partition=${row.name} sha=${recovered.sha256} " +
            "evidence=${recovered.structureEvidence}")
        return destination to recovered.sha256.lowercase()
    }

    private fun copyBaselineImage(switchId: String, baselineId: String, partition: String, expectedSha: String): File? {
        val source = File(File(baselineRoot, baselineId), "$partition.img")
        val destination = baseImage(switchId, partition)
        val body = buildString {
            appendLine("umask 077")
            append("cp -f ${q(source.absolutePath)} ${q(destination.absolutePath)}")
            append(" && chmod 0600 ${q(destination.absolutePath)}")
            append(" && sha256sum ${q(destination.absolutePath)}")
        }
        val result = runScript(script(Mark.BASELINE_COPY, body))
        if (!ok(result)) return null
        if (!expectedSha.equals(shaOf(result.output), ignoreCase = true)) {
            logger("ultra-switch snapshot: baseline image sha mismatch for $partition")
            runScript(script(Mark.BASELINE_COPY, "rm -f ${q(destination.absolutePath)}"))
            return null
        }
        return destination
    }

    private fun resolveBaselineManifest(baselineId: String): BaselineManifest? {
        if (baselineId.isNotEmpty()) {
            if (!BASELINE_ID.matches(baselineId)) return null
            return readTextQuietly(File(File(baselineRoot, baselineId), "manifest.json"), Mark.BASELINE_CAT)
                ?.let { ControlJsonCodec.decodeOrNull<BaselineManifest>(it) }
        }
        val ids = listDirectory(baselineRoot).filter { BASELINE_ID.matches(it) }.take(MAX_BASELINE_CANDIDATES)
        if (ids.isEmpty()) return null
        return ids.mapNotNull { id ->
            readTextQuietly(File(File(baselineRoot, id), "manifest.json"), Mark.BASELINE_CAT)
                ?.let { ControlJsonCodec.decodeOrNull<BaselineManifest>(it) }
        }.filter { it.provenance != dev.maplesu.manager.workmode.switch.BaselineProvenance.USER_SELECTED }
            .maxWithOrNull(compareBy({ it.createdAt }, { it.id }))
    }

    // ------------------------------------------------------------------ exportImage

    private fun exportImageInternal(switchId: String, kind: String, partition: String, destination: File): Boolean {
        if (!SWITCH_ID.matches(switchId)) return false
        val normalizedKind = kind.trim().uppercase()
        if (normalizedKind !in IMAGE_KINDS) return false
        if (partition !in PARTITIONS) return false
        if (!destinationAllowed(destination)) {
            logger("ultra-switch exportImage rejected destination: ${destination.name}")
            return false
        }
        val state = readStateFile(switchId) ?: loadState(switchId) ?: return false
        val row = state.partitions.firstOrNull { it.name == partition } ?: return false
        val sourcePath = state.images["$normalizedKind:$partition"] ?: return false
        val source = File(sourcePath)
        if (!isInside(source, txRoot)) return false
        val expected = when (normalizedKind) {
            "PRE" -> row.preSha256
            "BASE" -> row.baseSha256
            else -> row.targetSha256
        }
        if (!ModeSwitchHashes.isHash(expected)) return false
        return copyExportImage(source, destination, expected)
    }

    private fun copyExportImage(source: File, destination: File, expected: String): Boolean {
        if (!isInside(source, txRoot) || !destinationAllowed(destination)) return false
        val body = buildString {
            appendLine("umask 022")
            append("mkdir -p ${q(destination.parentFile?.absolutePath.orEmpty())}")
            append(" && cp -f ${q(source.absolutePath)} ${q(destination.absolutePath + ".tmp")}")
            append(" && chmod 0644 ${q(destination.absolutePath + ".tmp")}")
            append(" && mv -f ${q(destination.absolutePath + ".tmp")} ${q(destination.absolutePath)}")
            append(" && sha256sum ${q(destination.absolutePath)}")
        }
        val result = runScript(script(Mark.EXPORT, body))
        val actual = if (ok(result)) shaOf(result.output) else null
        if (!expected.equals(actual, ignoreCase = true)) {
            runScript(
                script(
                    Mark.EXPORT,
                    "rm -f ${q(destination.absolutePath + ".tmp")} ${q(destination.absolutePath)}",
                ),
            )
            logger("ultra-switch exportImage verify failed file=${destination.name}")
            return false
        }
        logger("ultra-switch exportImage ok file=${destination.name} sha=$expected")
        return true
    }

    // ------------------------------------------------------------------ submitCandidate

    private fun submitCandidateInternal(
        switchId: String,
        partition: String,
        candidate: File,
        candidateSha256: String,
    ): ClientResult {
        val state = loadStateOrThrow(switchId)
        val plan = loadPlanOrThrow(switchId)
        validatePlan(plan)
        if (partition !in PARTITIONS) throw Reject(UltraRootSwitchCodes.PARTITION_FORBIDDEN, partition)
        if (partition != plan.targetPartition) {
            throw Reject(UltraRootSwitchCodes.PARTITION_FORBIDDEN, "not write target")
        }
        if (state.state !in setOf(SwitchStateNames.SNAPSHOT, SwitchStateNames.PRODUCE, SwitchStateNames.COMMIT)) {
            throw Reject(UltraRootSwitchCodes.STATE_INVALID, state.state)
        }
        if (!ModeSwitchHashes.isHash(candidateSha256)) {
            throw Reject(ModeSwitchCodes.PRODUCE_CANDIDATE_CHANGED, "hash format")
        }
        val row = state.partitions.firstOrNull { it.name == partition }
            ?: throw Reject(ModeSwitchCodes.PLAN_INVALID, "unknown partition")
        if (state.state == SwitchStateNames.COMMIT &&
            row.targetSha256.equals(candidateSha256, ignoreCase = true)
        ) {
            return ClientResult(true, status = statusOf(state))
        }
        if (!candidate.isFile) throw Reject(ModeSwitchCodes.PRODUCE_OUTPUT_SIZE_INVALID, "missing candidate")
        if (candidate.length() != row.size) {
            throw Reject(ModeSwitchCodes.PRODUCE_OUTPUT_SIZE_INVALID, "size=${candidate.length()} expected=${row.size}")
        }
        val actual = ModeSwitchHashes.sha256(candidate)
        if (!actual.equals(candidateSha256, ignoreCase = true)) {
            throw Reject(ModeSwitchCodes.PRODUCE_CANDIDATE_CHANGED, "candidate sha")
        }
        if (row.preSha256.equals(actual, ignoreCase = true) &&
            state.partitions.none { it.name != partition && it.restoreRequired }) {
            throw Reject("SWITCH_STEP_NO_OP", partition)
        }
        val stored = candidateImage(switchId, partition)
        val body = buildString {
            appendLine("umask 077")
            append("cp -f ${q(candidate.absolutePath)} ${q(stored.absolutePath + ".tmp")}")
            append(" && chmod 0600 ${q(stored.absolutePath + ".tmp")}")
            append(" && mv -f ${q(stored.absolutePath + ".tmp")} ${q(stored.absolutePath)}")
            append(" && sha256sum ${q(stored.absolutePath)}")
        }
        val copy = runScript(script(Mark.SUBMIT_COPY, body))
        val storedSha = if (ok(copy)) shaOf(copy.output) else null
        if (!actual.equals(storedSha, ignoreCase = true)) {
            runScript(script(Mark.SUBMIT_COPY, "rm -f ${q(stored.absolutePath + ".tmp")} ${q(stored.absolutePath)}"))
            throw Reject(ModeSwitchCodes.PRODUCE_CANDIDATE_CHANGED, "stored sha")
        }
        val next = state.copy(
            state = SwitchStateNames.PRODUCE,
            stage = "S3",
            step = SwitchSteps.PRODUCE,
            updatedAtMillis = clock(),
            partitions = state.partitions.map { existing ->
                if (existing.name == partition) {
                    existing.copy(targetSha256 = actual, state = "CANDIDATE")
                } else {
                    existing
                }
            },
            images = state.images + ("CANDIDATE:$partition" to stored.absolutePath),
        )
        if (!persist(next)) throw Reject(UltraRootSwitchCodes.STATE_WRITE_FAILED, "state.json")
        appendJournal(next, "DONE", SwitchSteps.PRODUCE, partition = partition, expected = actual, observed = actual)
        logger("ultra-switch submitCandidate ok switch=$switchId $partition sha=$actual")
        return ClientResult(true, status = statusOf(next))
    }

    // ------------------------------------------------------------------ commit（唯一写分区）

    /**
     * 有序写 + 全量回滚。
     *
     * 1. 前置（**零 dd**）：基线闸门 → 需还原分区的 BASE 镜像存在 → 目标候选 sha →
     *    每个待写镜像与"回滚用" PRE 镜像的 sha/size 本地复核。
     * 2. 写序 = plan 分区顺序 = [ModeSwitchPreflight.writeOrder]：源分区 `restoreRequired`
     *    时用 `BASE:<name>` 还原；非目标且无需还原 → **跳过不写**；目标分区写候选。
     * 3. 任一步 dd 或回读失败 → 逆序用 `PRE:<name>` 回滚本次**已尝试**的所有分区（含已还原
     *    的源分区），逐个回读；全部回滚成功 → 返回原始失败码（写失败/回读失败可区分）；
     *    任一回滚失败 → `RECOVERY_REQUIRED`，detail 列出失败分区。
     */
    private fun commitInternal(switchId: String): ClientResult {
        val state = loadStateOrThrow(switchId)
        if (state.state !in setOf(SwitchStateNames.PRODUCE, SwitchStateNames.COMMIT)) {
            throw Reject(UltraRootSwitchCodes.STATE_INVALID, state.state)
        }
        val plan = loadPlanOrThrow(switchId)
        validatePlan(plan)
        val target = plan.targetPartition
        val targetStateRow = state.partitions.firstOrNull { it.name == target }
            ?: throw Reject(ModeSwitchCodes.PLAN_INVALID, "no target row")

        // ---------------- 前置：全部只读，任何 dd 之前 ----------------
        if (requireBaselineForCommit && sourceRecovery == null) {
            // 不知道源分区该还原成什么 → 不允许写目标分区。
            val missingBaseline = baselineMissingPartitions(state, target)
            if (missingBaseline.isNotEmpty()) {
                throw Reject(
                    UltraRootSwitchCodes.BASELINE_REQUIRED,
                    missingBaseline.joinToString(",") + " have no baseline sha256",
                )
            }
        }
        if (sourceRecovery != null && state.partitions.any {
                !ModeSwitchHashes.isHash(it.baseSha256) || state.images["BASE:${it.name}"].isNullOrBlank()
            }) {
            throw Reject("SOURCE_RECOVERY_INPUT_MISSING", "snapshot source inputs missing")
        }
        // 需要还原却没有基线镜像 → "先还原源分区再写目标分区"无法执行 → 硬拒绝。
        val missingBase = state.partitions
            .filter { row -> row.name != target && row.restoreRequired && state.images["BASE:${row.name}"].isNullOrBlank() }
            .map { row -> row.name }
        if (missingBase.isNotEmpty()) {
            throw Reject(
                UltraRootSwitchCodes.BASELINE_REQUIRED,
                missingBase.joinToString(",") + " need baseline image for restore",
            )
        }
        if (!ModeSwitchHashes.isHash(targetStateRow.targetSha256)) {
            throw Reject(ModeSwitchCodes.PLAN_INVALID, "no candidate")
        }

        // 有序写计划：目标（候选）+ 需还原的源分区（基线镜像）；其它分区**不写**。
        // plan.partitions 的顺序在 validatePlan 里已与 writeOrder 对齐。
        val ops = ArrayList<WriteOp>(plan.partitions.size)
        for (planRow in plan.partitions) {
            val stateRow = state.partitions.firstOrNull { it.name == planRow.name }
                ?: throw Reject(ModeSwitchCodes.PLAN_INVALID, "no row ${planRow.name}")
            when {
                planRow.name == target -> ops += WriteOp(
                    name = planRow.name,
                    restore = false,
                    device = planRow.device,
                    image = txImage(state, "CANDIDATE", planRow.name, ModeSwitchCodes.PRODUCE_CANDIDATE_CHANGED),
                    expectedSha = targetStateRow.targetSha256,
                    size = planRow.size,
                )
                stateRow.restoreRequired -> ops += WriteOp(
                    name = planRow.name,
                    restore = true,
                    device = planRow.device,
                    image = txImage(state, "BASE", planRow.name, UltraRootSwitchCodes.BASELINE_REQUIRED),
                    expectedSha = stateRow.baseSha256.ifEmpty { stateRow.baselineSha256 },
                    size = planRow.size,
                )
                else -> Unit // 已是基线：无需还原，绝不写。
            }
        }
        // Both directions explicitly write init_boot before boot; validatePlan
        // 已保证 plan 分区顺序与它一致，这里再按写序取一次，防止未来 plan 顺序漂移。
        val toMode = WorkMode.fromWire(plan.toMode)
            ?: throw Reject(ModeSwitchCodes.PLAN_INVALID, "toMode")
        val orderedOps = ModeSwitchPreflight.writeOrder(toMode, target, plan.sourcePartition,
            plan.partitions.map { it.name }.toSet())
            .mapNotNull { name -> ops.firstOrNull { it.name == name } }
        if (orderedOps.size != ops.size || orderedOps.none { it.name == target }) {
            throw Reject(ModeSwitchCodes.PLAN_INVALID, "write order")
        }
        // 每个待写分区都必须有"写完之后回读应等于什么"，且都能回滚：
        // PRE 在 tx 内 + sha 记录有效 + 回读预期存在。
        for (op in ops) {
            if (!ModeSwitchHashes.isHash(op.expectedSha)) {
                throw Reject(
                    if (op.restore) UltraRootSwitchCodes.BASELINE_REQUIRED else ModeSwitchCodes.PLAN_INVALID,
                    "no expected sha for ${op.name}",
                )
            }
            val stateRow = state.partitions.first { row -> row.name == op.name }
            if (!ModeSwitchHashes.isHash(stateRow.preSha256)) {
                throw Reject(ModeSwitchCodes.PRE_IMAGE_INVALID, "no pre sha ${op.name}")
            }
            val preFile = txImage(state, "PRE", op.name, ModeSwitchCodes.PRE_IMAGE_INVALID)
            val preCheck = runScript(checkScript(Mark.COMMIT_CHECK + "pre:" + op.name, preFile))
            if (!ok(preCheck)) throw Reject(ModeSwitchCodes.PRE_IMAGE_INVALID, "snapshot unreadable ${op.name}")
            if (firstInteger(preCheck.output) != op.size) {
                throw Reject(ModeSwitchCodes.PRE_IMAGE_INVALID, "snapshot size ${op.name}")
            }
            if (!stateRow.preSha256.equals(shaOf(preCheck.output), ignoreCase = true)) {
                throw Reject(ModeSwitchCodes.PRE_IMAGE_INVALID, "snapshot changed ${op.name}")
            }
        }
        // 写前本地复核"要写进去的镜像"sha/size（只读）。
        for (op in ops) verifyWriteImage(op)
        requireCurrentSlot(plan)

        // ---------------- 写意图（崩溃时可判定"可能已写"） ----------------
        val committing = state.copy(
            state = SwitchStateNames.COMMIT,
            stage = "S4",
            step = SwitchSteps.RESTORE_BASELINE,
            updatedAtMillis = clock(),
            error = "",
            errorDetail = "",
        )
        if (!persist(committing)) {
            throw Reject(UltraRootSwitchCodes.STATE_WRITE_FAILED, "commit intent")
        }
        appendJournal(committing, "COMMIT_INTENT", SwitchSteps.RESTORE_BASELINE)

        // ---------------- 有序写（init_boot → boot） ----------------
        val attempted = ArrayList<String>(ops.size)
        val verified = LinkedHashMap<String, String>()
        var primaryCode: String? = null
        var failureDetail = ""
        for (op in orderedOps) {
            attempted += op.name
            val marker = if (op.restore) Mark.RESTORE_WRITE else Mark.COMMIT_WRITE
            val step = if (op.restore) SwitchSteps.RESTORE_BASELINE else SwitchSteps.WRITE_CANDIDATE
            val write = runScript(writeScript(marker, op.image, op.device))
            val observed = if (ok(write)) shaOf(write.output) else null
            if (observed == null || !observed.equals(op.expectedSha, ignoreCase = true)) {
                val failedCode = if (observed == null) {
                    UltraRootSwitchCodes.COMMIT_WRITE_FAILED
                } else {
                    UltraRootSwitchCodes.COMMIT_VERIFY_FAILED
                }
                primaryCode = failedCode
                failureDetail = "partition=${op.name} readback=${observed ?: "write-failed"} " +
                    "expected=${op.expectedSha}"
                appendJournal(
                    committing,
                    if (observed == null) "WRITE_FAILED" else "WRITE_VERIFY_FAILED",
                    step,
                    partition = op.name,
                    code = failedCode,
                    expected = op.expectedSha,
                    observed = observed.orEmpty(),
                )
                break
            }
            verified[op.name] = observed
            appendJournal(
                committing,
                if (op.restore) "RESTORE_BASELINE" else "WRITE_CANDIDATE",
                step,
                partition = op.name,
                expected = op.expectedSha,
                observed = observed,
            )
            appendJournal(
                committing,
                "PARTITION_VERIFIED",
                SwitchSteps.COMMIT_PENDING,
                partition = op.name,
                expected = op.expectedSha,
                observed = observed,
            )
            // 进度落盘（best-effort）：崩溃后 rollback 能按"已写分区"精确收敛。
            val progress = committing.copy(
                step = step,
                updatedAtMillis = clock(),
                partitions = committing.partitions.map { row ->
                    if (row.name == op.name) {
                        row.copy(observedSha256 = observed, state = if (op.restore) "RESTORED" else "WRITTEN")
                    } else {
                        row
                    }
                },
            )
            if (!persist(progress)) logger("ultra-switch commit: progress persist failed ${op.name}")
        }

        val failure = primaryCode
        if (failure == null) {
            val next = committing.copy(
                state = SwitchStateNames.REBOOT_REQUIRED,
                stage = "S4",
                step = SwitchSteps.COMMIT_PENDING,
                updatedAtMillis = clock(),
                rebootRequested = false,
                error = "",
                errorDetail = "",
                partitions = committing.partitions.map { existing ->
                    val observed = verified[existing.name]
                    when {
                        observed == null -> existing
                        existing.name == target -> existing.copy(observedSha256 = observed, state = "WRITTEN")
                        else -> existing.copy(observedSha256 = observed, state = "RESTORED")
                    }
                },
            )
            if (!persist(next)) throw Reject(UltraRootSwitchCodes.STATE_WRITE_FAILED, "state.json")
            appendJournal(next, "REBOOT_PENDING", SwitchSteps.COMMIT_PENDING)
            logger(
                "ultra-switch commit ok switch=$switchId partitions=" +
                    verified.entries.joinToString(",") { "${it.key}:${it.value.take(12)}" },
            )
            return ClientResult(true, status = statusOf(next))
        }
        logger("ultra-switch commit failed switch=$switchId code=$failure detail=$failureDetail; rolling back")
        return compensate(state, plan, failure, failureDetail, attempted)
    }

    /**
     * commit 失败补偿：逆序把本次**已尝试**的分区写回 `PRE:<name>` 并逐个回读。
     *
     * - 全部回滚成功：状态 `ROLLED_BACK`，返回**原始失败码**（COMMIT_WRITE_FAILED /
     *   COMMIT_VERIFY_FAILED），detail 保留失败分区与回读值；
     * - 任一回滚失败：状态 `RECOVERY_REQUIRED`，detail 列出失败分区（产品不自动重试）。
     */
    private fun compensate(
        state: UltraSwitchStateFile,
        plan: UltraSwitchPlanFile,
        primaryCode: String,
        detail: String,
        attempted: List<String>,
    ): ClientResult {
        val rolling = state.copy(
            state = SwitchStateNames.ROLLING_BACK,
            stage = "RB",
            updatedAtMillis = clock(),
        )
        val outcome = rollbackPartitions(rolling, plan, attempted.reversed(), primaryCode)
        val updated = markRollbackOutcome(rolling, attempted, outcome.recovered)
        if (outcome.failures.isEmpty()) {
            val next = updated.copy(
                state = SwitchStateNames.ROLLED_BACK,
                stage = "RB",
                updatedAtMillis = clock(),
                error = primaryCode,
                errorDetail = detail,
            )
            if (!persist(next)) throw Reject(UltraRootSwitchCodes.STATE_WRITE_FAILED, "state.json")
            appendJournal(next, "ROLLBACK_DONE", SwitchSteps.WRITE_CANDIDATE, code = primaryCode)
            logger(
                "ultra-switch commit rolled back switch=$state.switchId partitions=" +
                    attempted.joinToString(",") + " primary=$primaryCode",
            )
            return ClientResult(false, primaryCode, detail, statusOf(next))
        }
        val code = if (outcome.sawWriteFailure) {
            UltraRootSwitchCodes.ROLLBACK_FAILED
        } else {
            UltraRootSwitchCodes.ROLLBACK_VERIFY_FAILED
        }
        val failDetail = "$detail; rollback failed partitions=" + outcome.failures.joinToString("; ")
        logger("ultra-switch commit rollback failed switch=$state.switchId code=$code")
        val next = recoveryRequired(updated, code, failDetail)
        return ClientResult(false, code, failDetail, statusOf(next))
    }

    /**
     * 逆序回滚一组分区：每个分区 `dd if=PRE of=<dev> && sync && sha256sum`，回读必须等于
     * 该分区记录的快照摘要。返回结果供调用方决定 `ROLLED_BACK` / `RECOVERY_REQUIRED`。
     */
    private fun rollbackPartitions(
        state: UltraSwitchStateFile,
        plan: UltraSwitchPlanFile,
        names: List<String>,
        code: String,
    ): RollbackOutcome {
        requireCurrentSlot(plan)
        val recovered = LinkedHashMap<String, String>()
        val failures = mutableListOf<String>()
        var sawWriteFailure = false
        for (name in names) {
            val planRow = plan.partitions.firstOrNull { it.name == name }
            val stateRow = state.partitions.firstOrNull { it.name == name }
            if (planRow == null || stateRow == null) {
                failures += "$name:no-row"
                sawWriteFailure = true
                continue
            }
            val prePath = state.images["PRE:$name"]
            val preFile = prePath?.let { File(it) }
            if (preFile == null || !isInside(preFile, txRoot)) {
                failures += "$name:pre-image-missing"
                sawWriteFailure = true
                appendJournal(
                    state, "ROLLBACK_PARTITION_FAILED", SwitchSteps.WRITE_CANDIDATE,
                    partition = name, code = code,
                )
                continue
            }
            val write = runScript(writeScript(Mark.ROLLBACK_WRITE, preFile, planRow.device))
            val observed = if (ok(write)) shaOf(write.output) else null
            if (observed != null && observed.equals(stateRow.preSha256, ignoreCase = true)) {
                recovered[name] = observed
                appendJournal(
                    state, "ROLLBACK_PARTITION", SwitchSteps.WRITE_CANDIDATE,
                    partition = name, code = code,
                    expected = stateRow.preSha256, observed = observed,
                )
                continue
            }
            if (observed == null) sawWriteFailure = true
            failures += "$name:readback=${observed ?: "write-failed"} expected=${stateRow.preSha256}"
            appendJournal(
                state, "ROLLBACK_PARTITION_FAILED", SwitchSteps.WRITE_CANDIDATE,
                partition = name, code = code,
                expected = stateRow.preSha256, observed = observed.orEmpty(),
            )
        }
        return RollbackOutcome(recovered, failures, sawWriteFailure)
    }

    private fun recoveryRequired(state: UltraSwitchStateFile, code: String, detail: String): UltraSwitchStateFile {
        val next = state.copy(
            state = SwitchStateNames.RECOVERY_REQUIRED,
            stage = "RB",
            updatedAtMillis = clock(),
            error = code,
            errorDetail = detail,
        )
        persist(next)
        appendJournal(next, "RECOVERY_REQUIRED", SwitchSteps.WRITE_CANDIDATE, code = code)
        return next
    }

    /**
     * 把一次回滚尝试写进分区行：成功 → `ROLLED_BACK` + 回读摘要；失败/未确认 →
     * `WRITE_UNRECOVERED`（后续 `rollback` 会继续重试它，不会误判"已恢复"）。
     */
    private fun markRollbackOutcome(
        state: UltraSwitchStateFile,
        attempted: List<String>,
        recovered: Map<String, String>,
    ): UltraSwitchStateFile = state.copy(
        partitions = state.partitions.map { row ->
            when {
                row.name !in attempted -> row
                recovered.containsKey(row.name) ->
                    row.copy(observedSha256 = recovered.getValue(row.name), state = "ROLLED_BACK")
                else -> row.copy(state = "WRITE_UNRECOVERED")
            }
        },
    )

    // ------------------------------------------------------------------ rollback

    /**
     * 显式全量回滚（用户/编排在 commit 失败后调用）：把 commit 可能写过的分区逆序写回
     * `PRE:<name>` 并逐个回读。
     *
     * 回滚范围：`state=COMMIT`（可能写到一半，进度未知）→ **全量**；否则取有"已写"证据
     * （`WRITTEN`/`RESTORED`/`WRITE_UNRECOVERED` 或 `observedSha256` 非空且不是
     * `ROLLED_BACK`）的分区；证据全缺 → 保守全量。任一待回滚分区缺 PRE 镜像/摘要记录 →
     * 在**任何 dd 之前** `SWITCH_PRE_IMAGE_INVALID`。
     */
    private fun rollbackInternal(switchId: String): ClientResult {
        val state = loadStateOrThrow(switchId)
        if (state.state == SwitchStateNames.ROLLED_BACK) {
            return ClientResult(true, status = statusOf(state))
        }
        if (state.state !in ROLLBACK_ALLOWED) {
            throw Reject(UltraRootSwitchCodes.STATE_INVALID, state.state)
        }
        val plan = loadPlanOrThrow(switchId)
        validatePlan(plan)
        requireCurrentSlot(plan)
        val targets = rollbackTargets(state, plan)
        // 零 dd：先把每个待回滚分区的记录与 PRE 镜像校验齐。
        for (name in targets) {
            val stateRow = state.partitions.firstOrNull { it.name == name }
                ?: throw Reject(ModeSwitchCodes.PLAN_INVALID, "no row $name")
            if (!ModeSwitchHashes.isHash(stateRow.preSha256)) {
                throw Reject(ModeSwitchCodes.PRE_IMAGE_INVALID, "no pre sha $name")
            }
            txImage(state, "PRE", name, ModeSwitchCodes.PRE_IMAGE_INVALID)
        }
        val rolling = state.copy(
            state = SwitchStateNames.ROLLING_BACK,
            stage = "RB",
            updatedAtMillis = clock(),
        )
        if (!persist(rolling)) throw Reject(UltraRootSwitchCodes.STATE_WRITE_FAILED, "state.json")
        appendJournal(rolling, "ROLLBACK_INTENT", SwitchSteps.WRITE_CANDIDATE)
        val outcome = rollbackPartitions(rolling, plan, targets.reversed(), UltraRootSwitchCodes.ROLLBACK_FAILED)
        val updated = markRollbackOutcome(rolling, targets, outcome.recovered)
        if (outcome.failures.isEmpty()) {
            val next = updated.copy(
                state = SwitchStateNames.ROLLED_BACK,
                updatedAtMillis = clock(),
                error = "",
                errorDetail = "",
            )
            if (!persist(next)) throw Reject(UltraRootSwitchCodes.STATE_WRITE_FAILED, "state.json")
            appendJournal(next, "ROLLBACK_DONE", SwitchSteps.WRITE_CANDIDATE)
            logger("ultra-switch rollback ok switch=$switchId partitions=${targets.joinToString(",")}")
            return ClientResult(true, status = statusOf(next))
        }
        val code = if (outcome.sawWriteFailure) {
            UltraRootSwitchCodes.ROLLBACK_FAILED
        } else {
            UltraRootSwitchCodes.ROLLBACK_VERIFY_FAILED
        }
        val detail = "rollback failed partitions=" + outcome.failures.joinToString("; ")
        logger("ultra-switch rollback failed switch=$switchId code=$code detail=$detail")
        val next = recoveryRequired(updated, code, detail)
        return ClientResult(false, code, detail, statusOf(next))
    }

    /**
     * 回滚范围：
     * - `COMMIT`（写意图已落盘、进度未知）→ 全量（无法证明"没写过"就必须覆盖）；
     * - 否则取有已写证据的分区（`WRITTEN`/`RESTORED`/`WRITE_UNRECOVERED`，或 observed 非空
     *   且尚未 `ROLLED_BACK`）；
     * - 证据全缺（旧 state.json）→ 保守全量。
     */
    private fun rollbackTargets(state: UltraSwitchStateFile, plan: UltraSwitchPlanFile): List<String> {
        if (state.state == SwitchStateNames.COMMIT) return plan.partitions.map { it.name }
        val written = state.partitions
            .filter {
                it.state in WRITTEN_STATES ||
                    (it.observedSha256.isNotEmpty() && it.state != "ROLLED_BACK")
            }
            .map { it.name }
            .toSet()
        val ordered = plan.partitions.map { it.name }.filter { it in written }
        return ordered.ifEmpty { plan.partitions.map { it.name } }
    }

    // ------------------------------------------------------------------ abort

    private fun abortInternal(switchId: String): ClientResult {
        if (!SWITCH_ID.matches(switchId)) throw Reject(ModeSwitchCodes.PLAN_INVALID, "switchId")
        requireAbsoluteWorkRoot()
        val state = loadState(switchId)
        if (state != null && state.state !in ABORT_ALLOWED) {
            throw Reject(UltraRootSwitchCodes.STATE_INVALID, state.state)
        }
        val dir = txDir(switchId)
        if (dir.parentFile?.canonicalFile != txRoot.canonicalFile) {
            throw Reject(ModeSwitchCodes.PLAN_INVALID, "tx path")
        }
        val result = runScript(script(Mark.ABORT, "rm -rf ${q(dir.absolutePath)}"))
        if (!ok(result)) throw Reject(ModeSwitchCodes.CONTROL_DIR_INVALID, "cleanup failed")
        synchronized(this) {
            planCache.remove(switchId)
            stateCache.remove(switchId)
        }
        logger("ultra-switch abort cleaned switch=$switchId")
        val cancelled = state?.copy(state = SwitchStateNames.CANCELLED, stage = "RB", updatedAtMillis = clock())
        return ClientResult(true, status = cancelled?.let { statusOf(it) })
    }

    // ------------------------------------------------------------------ verify

    private fun verifyInternal(switchId: String): ClientResult {
        val state = loadStateOrThrow(switchId)
        if (state.state !in setOf(SwitchStateNames.REBOOT_REQUIRED, SwitchStateNames.VERIFYING)) {
            throw Reject(UltraRootSwitchCodes.STATE_INVALID, state.state)
        }
        val current = runCatching { bootIdSource() }.getOrDefault("")
        if (current.isEmpty() || state.startBootId.isEmpty() || current == state.startBootId) {
            throw Reject(ModeSwitchCodes.VERIFY_BOOT_NOT_OBSERVED, "boot id unchanged")
        }
        val plan = loadPlanOrThrow(switchId)
        validatePlan(plan)
        requireCurrentSlot(plan)
        val target = plan.targetPartition
        val verifying = state.copy(
            state = SwitchStateNames.VERIFYING,
            stage = "S6",
            step = SwitchSteps.VERIFY_PARTITIONS,
            updatedAtMillis = clock(),
        )
        if (!persist(verifying)) throw Reject(UltraRootSwitchCodes.STATE_WRITE_FAILED, "verify intent")
        val observedShas = LinkedHashMap<String, String>()
        for (row in plan.partitions) {
            val stateRow = state.partitions.firstOrNull { it.name == row.name }
                ?: throw Reject(ModeSwitchCodes.PLAN_INVALID, "no row ${row.name}")
            val expected = when {
                row.name == target -> stateRow.targetSha256
                stateRow.restoreRequired -> stateRow.baseSha256.ifEmpty { stateRow.baselineSha256 }
                else -> stateRow.preSha256
            }
            val check = runScript(script(Mark.VERIFY, "sha256sum ${q(row.device)}"))
            val observed = if (ok(check)) shaOf(check.output) else null
            if (!ModeSwitchHashes.isHash(expected) || !expected.equals(observed, ignoreCase = true)) {
                val failed = verifying.copy(
                    state = SwitchStateNames.RECOVERY_REQUIRED,
                    verifyFailures = verifying.verifyFailures + 1,
                    error = ModeSwitchCodes.VERIFY_PARTITION_MISMATCH,
                    errorDetail = "partition=${row.name} observed=$observed expected=$expected",
                    updatedAtMillis = clock(),
                )
                persist(failed)
                appendJournal(failed, "RECOVERY_REQUIRED", SwitchSteps.VERIFY_PARTITIONS,
                    partition = row.name, code = failed.error, expected = expected, observed = observed.orEmpty())
                return ClientResult(false, ModeSwitchCodes.VERIFY_PARTITION_MISMATCH, failed.errorDetail, statusOf(failed))
            }
            observedShas[row.name] = requireNotNull(observed)
        }
        val verified = verifying.copy(
            state = SwitchStateNames.VERIFIED,
            step = SwitchSteps.VERIFY_SUPERUSER,
            updatedAtMillis = clock(),
            error = "",
            errorDetail = "",
            partitions = verifying.partitions.map { existing ->
                val observed = observedShas[existing.name]
                if (observed != null) existing.copy(targetSha256 = observed, observedSha256 = observed, state = "VERIFIED") else existing
            },
        )
        if (!persist(verified)) throw Reject(UltraRootSwitchCodes.STATE_WRITE_FAILED, "state.json")
        appendJournal(verified, "VERIFIED", SwitchSteps.VERIFY_PARTITIONS, observed = observedShas[target].orEmpty())
        logger("ultra-switch verify ok switch=$switchId partitions=${observedShas.keys.joinToString(",")}")
        return ClientResult(true, status = statusOf(verified))
    }

    // ------------------------------------------------------------------ 事务目录与脚本

    private fun txDir(switchId: String): File = File(txRoot, switchId)

    /** `/dev/block/by-name/<node>`；node 由白名单 + 槽位拼出（调用方已在提交前校验）。 */
    private fun devicePath(node: String): String = DEVICE_PREFIX + node

    private fun planFile(switchId: String): File = File(txDir(switchId), PLAN_FILE)

    private fun stateFile(switchId: String): File = File(txDir(switchId), STATE_FILE)

    private fun journalFile(switchId: String): File = File(txDir(switchId), JOURNAL_FILE)

    private fun preImage(switchId: String, partition: String): File =
        File(txDir(switchId), "pre-$partition.img")

    private fun baseImage(switchId: String, partition: String): File =
        File(txDir(switchId), "base-$partition.img")

    private fun candidateImage(switchId: String, partition: String): File =
        File(txDir(switchId), "candidate-$partition.img")

    private fun snapshotScript(partition: String, device: String, image: File): String {
        val body = buildString {
            appendLine("umask 077")
            append("mkdir -p ${q(image.parentFile?.absolutePath.orEmpty())}")
            append(" && dd if=${q(device)} of=${q(image.absolutePath)} bs=1M 2>/dev/null")
            append(" && sync")
            append(" && sha256sum ${q(image.absolutePath)}")
            append(" && wc -c < ${q(image.absolutePath)}")
        }
        return script(Mark.SNAPSHOT_PRE + partition, body)
    }

    /** tx 目录内的镜像路径；缺失/越界一律 fail closed（不做任何 fallback 猜测）。 */
    private fun txImage(state: UltraSwitchStateFile, kind: String, partition: String, code: String): File {
        val path = state.images["$kind:$partition"]
        if (path.isNullOrBlank()) throw Reject(code, "$kind image missing for $partition")
        val file = File(path)
        if (!isInside(file, txRoot)) throw Reject(code, "$kind image outside tx for $partition")
        return file
    }

    /** 写前复核 `dd` 要用的镜像：存在 + 大小 == 分区大小 + sha 命中（只读）。 */
    private fun verifyWriteImage(op: WriteOp) {
        val marker = if (op.restore) {
            Mark.COMMIT_CHECK + "base:" + op.name
        } else {
            Mark.COMMIT_CHECK + "candidate"
        }
        val check = runScript(checkScript(marker, op.image))
        if (op.restore) {
            if (!ok(check)) {
                throw Reject(UltraRootSwitchCodes.BASELINE_IMAGE_INVALID, "baseline image unreadable ${op.name}")
            }
            if (firstInteger(check.output) != op.size) {
                throw Reject(UltraRootSwitchCodes.BASELINE_IMAGE_INVALID, "baseline image size ${op.name}")
            }
            if (!op.expectedSha.equals(shaOf(check.output), ignoreCase = true)) {
                throw Reject(UltraRootSwitchCodes.BASELINE_IMAGE_INVALID, "baseline image changed ${op.name}")
            }
            return
        }
        if (!ok(check)) throw Reject(ModeSwitchCodes.PRODUCE_CANDIDATE_CHANGED, "candidate unreadable")
        if (firstInteger(check.output) != op.size) {
            throw Reject(ModeSwitchCodes.PRODUCE_OUTPUT_SIZE_INVALID, "candidate size")
        }
        if (!op.expectedSha.equals(shaOf(check.output), ignoreCase = true)) {
            throw Reject(ModeSwitchCodes.PRODUCE_CANDIDATE_CHANGED, "candidate sha")
        }
    }

    /** 写前复核（只读）：存在 + 大小 + sha。 */
    private fun checkScript(marker: String, file: File): String {
        val body = buildString {
            append("test -f ${q(file.absolutePath)}")
            append(" && wc -c < ${q(file.absolutePath)}")
            append(" && sha256sum ${q(file.absolutePath)}")
        }
        return script(marker, body)
    }

    /** 唯一写块设备的脚本：dd → sync → flushbufs（尽力）→ sha256sum 回读。 */
    private fun writeScript(marker: String, image: File, device: String): String {
        val body = buildString {
            append("dd if=${q(image.absolutePath)} of=${q(device)} bs=1M 2>/dev/null")
            append(" && sync")
            append(" && (blockdev --flushbufs ${q(device)} 2>/dev/null || true)")
            append(" && sha256sum ${q(device)}")
        }
        return script(marker, body)
    }

    private fun writeFileAtomically(file: File, content: String, marker: String): Boolean {
        val parent = file.parentFile ?: return false
        val temp = File(parent, ".${file.name}.tmp")
        val body = buildString {
            appendLine("umask 077")
            append("mkdir -p ${q(parent.absolutePath)}")
            append(" && printf '%s' ${q(content)} > ${q(temp.absolutePath)}")
            append(" && mv -f ${q(temp.absolutePath)} ${q(file.absolutePath)}")
            append(" && chmod 0600 ${q(file.absolutePath)}")
        }
        return ok(runScript(script(marker, body)))
    }

    private fun appendJournal(
        state: UltraSwitchStateFile,
        op: String,
        step: String,
        partition: String = "",
        code: String = "",
        expected: String = "",
        observed: String = "",
    ) {
        val record = UltraSwitchJournalRecord(
            atMillis = clock(),
            op = op,
            state = state.state,
            stage = state.stage,
            step = step,
            partition = partition,
            code = code,
            expectedSha256 = expected,
            observedSha256 = observed,
        )
        val json = ControlJsonCodec.encode(record)
        val file = journalFile(state.switchId)
        val body = buildString {
            appendLine("umask 077")
            append("mkdir -p ${q(file.parentFile?.absolutePath.orEmpty())}")
            append(" && printf '%s\\n' ${q(json)} >> ${q(file.absolutePath)}")
            append(" && chmod 0600 ${q(file.absolutePath)}")
        }
        runScript(script(Mark.JOURNAL, body))
    }

    // ------------------------------------------------------------------ 状态读取

    private fun loadPlanOrThrow(switchId: String): UltraSwitchPlanFile =
        loadPlan(switchId) ?: throw Reject(ModeSwitchCodes.PLAN_INVALID, "no plan")

    private fun loadStateOrThrow(switchId: String): UltraSwitchStateFile =
        loadState(switchId) ?: throw Reject(ModeSwitchCodes.PLAN_INVALID, "no transaction")

    private fun loadPlan(switchId: String): UltraSwitchPlanFile? {
        synchronized(this) { planCache[switchId] }?.let { return it }
        val raw = readTextQuietly(planFile(switchId), Mark.PLAN_READ) ?: return null
        val parsed = ControlJsonCodec.decodeOrNull<UltraSwitchPlanFile>(raw) ?: return null
        synchronized(this) { planCache[switchId] = parsed }
        return parsed
    }

    private fun loadState(switchId: String): UltraSwitchStateFile? {
        synchronized(this) { stateCache[switchId] }?.let { return it }
        return readStateFile(switchId)?.also { parsed -> synchronized(this) { stateCache[switchId] = parsed } }
    }

    /** 只读磁盘（本地可读则直接读；否则 root `cat`；主线程不发起 root 调用）。 */
    private fun readStateFile(switchId: String): UltraSwitchStateFile? {
        val raw = readTextQuietly(stateFile(switchId), Mark.STATE_READ) ?: return null
        return ControlJsonCodec.decodeOrNull<UltraSwitchStateFile>(raw)
    }

    private fun readTextQuietly(file: File, marker: String): String? {
        val local = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
        if (local != null) return local
        if (Thread.currentThread().name.contains("main")) return null
        val result = runScript(script(marker, "cat ${q(file.absolutePath)} 2>/dev/null"))
        return if (ok(result) && result.output.isNotBlank()) result.output else null
    }

    private fun journalHead(switchId: String): ClientStatus? {
        val raw = readTextQuietly(journalFile(switchId), Mark.JOURNAL_READ) ?: return null
        return journalHead(switchId, raw)
    }

    private fun readTextExact(file: File, marker: String): String? {
        val local = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
        if (local != null) return local
        check(!Thread.currentThread().name.contains("main")) { "SWITCH_STATUS_READ_FAILED" }
        val missing = "MAPLESU_SWITCH_RECORD_ABSENT"
        val path = q(file.absolutePath)
        val result = runScript(script(marker,
            "if [ -e $path ]; then [ -f $path ] && cat $path; else echo $missing; fi"))
        check(ok(result)) { "SWITCH_STATUS_READ_FAILED" }
        return if (result.output.trim() == missing) null else result.output
    }

    private fun journalHead(switchId: String, raw: String): ClientStatus? {
        val last = raw.lineSequence().filter { it.isNotBlank() }.lastOrNull() ?: return null
        val record = ControlJsonCodec.decodeOrNull<UltraSwitchJournalRecord>(last) ?: return null
        return ClientStatus(
            switchId = switchId,
            state = record.state,
            stage = record.stage,
            step = record.step,
            fromMode = "",
            toMode = "",
            error = record.code,
            errorDetail = "journal-head",
            planSha256 = "",
            rebootRequired = record.state == SwitchStateNames.REBOOT_REQUIRED,
            bootObserved = false,
            canRollback = record.state in ROLLBACK_ALLOWED,
            canResumeReboot = record.state == SwitchStateNames.REBOOT_REQUIRED,
            verifyFailures = 0,
            partitions = emptyList(),
        )
    }

    private fun latestStatusExact(): ClientStatus? {
        val local = txRoot.listFiles()
        if (local != null) {
            val candidates = local.filter { it.isDirectory && SWITCH_ID.matches(it.name) &&
                (File(it, STATE_FILE).isFile || File(it, JOURNAL_FILE).isFile) }
                .sortedByDescending { maxOf(File(it, STATE_FILE).lastModified(), File(it, JOURNAL_FILE).lastModified()) }
            for (candidate in candidates) statusExact(candidate.name)?.let { return it }
            return null
        }
        check(!Thread.currentThread().name.contains("main")) { "SWITCH_STATUS_READ_FAILED" }
        val path = q(txRoot.absolutePath)
        val complete = "MAPLESU_SWITCH_LIST_COMPLETE"
        val result = runScript(script(Mark.LIST_TX,
            "if [ -e $path ]; then [ -d $path ] && ls -1t $path; else :; fi && printf '\\n$complete\\n'"))
        check(ok(result)) { result.detail.ifBlank { "SWITCH_STATUS_READ_FAILED" } }
        val entries = result.output.trim().lineSequence().map(String::trim).toList()
        check(entries.lastOrNull() == complete) { "SWITCH_STATUS_READ_FAILED" }
        for (id in entries.dropLast(1).filter(SWITCH_ID::matches)) {
            statusExact(id)?.let { return it }
        }
        return null
    }

    private fun latestSwitchId(): String? {
        val local = runCatching {
            txRoot.listFiles { file -> file.isDirectory }?.toList()
        }.getOrNull()
        if (local != null && local.isNotEmpty()) {
            return local
                .maxByOrNull { File(it, STATE_FILE).lastModified() }
                ?.name
                ?.takeIf { SWITCH_ID.matches(it) }
        }
        if (Thread.currentThread().name.contains("main")) return null
        val result = runScript(script(Mark.LIST_TX, "ls -1t ${q(txRoot.absolutePath)} 2>/dev/null | head -n 1"))
        if (!ok(result)) return null
        return result.output.trim().takeIf { SWITCH_ID.matches(it) }
    }

    private fun listDirectory(directory: File): List<String> {
        val local = runCatching {
            directory.takeIf { it.isDirectory }?.listFiles()?.map { it.name }
        }.getOrNull()
        if (local != null && local.isNotEmpty()) return local
        if (Thread.currentThread().name.contains("main")) return emptyList()
        val result = runScript(script(Mark.BASELINE_LIST, "ls -1 ${q(directory.absolutePath)} 2>/dev/null"))
        if (!ok(result)) return emptyList()
        return result.output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    private fun persist(state: UltraSwitchStateFile): Boolean {
        val json = ControlJsonCodec.encode(state)
        val written = writeFileAtomically(stateFile(state.switchId), json, Mark.STATE_WRITE)
        if (written) {
            synchronized(this) { stateCache[state.switchId] = state }
        }
        return written
    }

    // ------------------------------------------------------------------ 校验

    private fun parseRequest(switchId: String, planFields: Map<String, String>): Request {
        if (!SWITCH_ID.matches(switchId)) throw Reject(ModeSwitchCodes.PLAN_INVALID, "switchId")
        val from = WorkMode.fromWire(planFields["fromMode"])
            ?: throw Reject(ModeSwitchCodes.PLAN_INVALID, "fromMode")
        val to = WorkMode.fromWire(planFields["toMode"])
            ?: throw Reject(ModeSwitchCodes.PLAN_INVALID, "toMode")
        if (from == to) throw Reject(ModeSwitchCodes.SAME_MODE, "from==to")
        val baselineId = planFields["baselineId"].orEmpty()
        if (baselineId.isNotEmpty() && !BASELINE_ID.matches(baselineId)) {
            throw Reject(ModeSwitchCodes.PLAN_INVALID, "baselineId")
        }
        val target = ModeSwitchPreflight.resolveTarget(to, planFields["targetPartition"])
            ?: throw Reject(ModeSwitchCodes.PLAN_INVALID, "targetPartition=${planFields["targetPartition"]}")
        val source = planFields["sourcePartition"]?.trim()?.takeIf(String::isNotEmpty)
        if (source != null && source !in ModeSwitchPreflight.allowedTargets(from)) {
            throw Reject(ModeSwitchCodes.PLAN_INVALID, "sourcePartition=$source")
        }
        return Request(switchId, from, to, baselineId, target, source)
    }

    private fun knownSourcePartition(from: WorkMode, slot: String): String? {
        val recent = latestSwitchId()?.let(::loadState)
        val records = (synchronized(this) { stateCache.values.toList() } + listOfNotNull(recent))
            .distinctBy { it.switchId }.sortedByDescending { it.updatedAtMillis }
        return records.firstNotNullOfOrNull { state ->
            if (state.state != SwitchStateNames.VERIFIED || WorkMode.fromWire(state.toMode) != from) null
            else loadPlan(state.switchId)?.takeIf { plan ->
                plan.slotSuffix == slot && plan.toMode == state.toMode &&
                    runCatching { validatePlan(plan) }.isSuccess
            }
                ?.targetPartition?.takeIf { it in ModeSwitchPreflight.allowedTargets(from) }
        }
    }

    private fun validatePlan(plan: UltraSwitchPlanFile) {
        if (plan.schema != PLAN_SCHEMA) throw Reject(ModeSwitchCodes.PLAN_INVALID, "schema")
        if (!SWITCH_ID.matches(plan.switchId)) throw Reject(ModeSwitchCodes.PLAN_INVALID, "switchId")
        val to = WorkMode.fromWire(plan.toMode) ?: throw Reject(ModeSwitchCodes.PLAN_INVALID, "toMode")
        WorkMode.fromWire(plan.fromMode) ?: throw Reject(ModeSwitchCodes.PLAN_INVALID, "fromMode")
        val expectedTarget = ModeSwitchPreflight.resolveTarget(to, plan.targetPartition)
            ?: throw Reject(ModeSwitchCodes.PLAN_INVALID, "target=${plan.targetPartition}")
        if (plan.targetPartition != expectedTarget) {
            throw Reject(ModeSwitchCodes.PLAN_INVALID, "target=${plan.targetPartition}")
        }
        val from = WorkMode.fromWire(plan.fromMode) ?: throw Reject(ModeSwitchCodes.PLAN_INVALID, "fromMode")
        if (!plan.sourcePartition.isNullOrEmpty() && plan.sourcePartition !in ModeSwitchPreflight.allowedTargets(from)) {
            throw Reject(ModeSwitchCodes.PLAN_INVALID, "source=${plan.sourcePartition}")
        }
        val expectedOrder = ModeSwitchPreflight.writeOrder(to, plan.targetPartition, plan.sourcePartition,
            plan.partitions.map { it.name }.toSet())
        if (plan.partitions.map { it.name } != expectedOrder) {
            throw Reject(ModeSwitchCodes.PLAN_INVALID, "partition order")
        }
        plan.partitions.forEach { row ->
            if (row.name !in PARTITIONS) throw Reject(UltraRootSwitchCodes.PARTITION_FORBIDDEN, row.name)
            if (!isAllowedDevicePath(row.device, row.name, plan.slotSuffix)) {
                throw Reject(UltraRootSwitchCodes.PARTITION_FORBIDDEN, row.device)
            }
            if (row.size <= 0 || row.size > MAX_PARTITION_BYTES) {
                throw Reject(ModeSwitchCodes.LAYOUT_UNSUPPORTED, "size=${row.size}")
            }
            if (row.role != ROLE_WRITE && row.role != ROLE_RESTORE) {
                throw Reject(ModeSwitchCodes.PLAN_INVALID, "role=${row.role}")
            }
        }
    }

    private fun requireAbsoluteWorkRoot() {
        if (!workRoot.isAbsolute) throw Reject(ModeSwitchCodes.CONTROL_DIR_INVALID, "relative workRoot")
        val path = workRoot.path
        if (path.contains('\u0000') || path.contains('\n')) {
            throw Reject(ModeSwitchCodes.CONTROL_DIR_INVALID, "invalid workRoot")
        }
    }

    /**
     * 诊断导出只要求"App 自己可写的绝对路径"：
     * 不落进 root-only 的事务根即可（UI 可能导出到外部存储，不能过度限制）。
     */
    private fun writeDestinationAllowed(destination: File): Boolean {
        if (!destination.isAbsolute) return false
        val path = destination.path
        if (path.contains('\u0000') || path.contains('\n')) return false
        val canonical = runCatching { destination.canonicalFile }.getOrNull() ?: return false
        val rootCanonical = runCatching { workRoot.canonicalFile }.getOrNull()
        if (rootCanonical != null && isInside(canonical, rootCanonical)) return false
        return true
    }

    private fun destinationAllowed(destination: File): Boolean {
        if (!destination.isAbsolute) return false
        val path = destination.path
        if (path.contains('\u0000') || path.contains('\n')) return false
        val canonical = runCatching { destination.canonicalFile }.getOrNull() ?: return false
        val rootCanonical = runCatching { txRoot.canonicalFile }.getOrNull()
        if (rootCanonical != null && isInside(canonical, rootCanonical)) return false
        val workCanonical = runCatching { workRoot.canonicalFile }.getOrNull()
        if (workCanonical != null && isInside(canonical, workCanonical)) return false
        if (destinationRoots.isNotEmpty()) {
            val allowed = destinationRoots.any { root ->
                val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return@any false
                isInside(canonical, canonicalRoot)
            }
            if (!allowed) return false
        }
        return true
    }

    private fun isAllowedDevicePath(path: String, partition: String, slot: String): Boolean {
        if (!path.startsWith(DEVICE_PREFIX)) return false
        val node = path.removePrefix(DEVICE_PREFIX)
        if (node.isEmpty() || node.contains('/') || node.contains("..")) return false
        if (node.any { it.isWhitespace() || it == '\u0000' || it == '\'' || it == '"' }) return false
        if (!PARTITION_NODE.matches(node)) return false
        val allowed = buildSet {
            add(partition)
            if (slot.isNotEmpty()) add(partition + slot)
        }
        return node in allowed
    }

    private fun isInside(file: File, root: File): Boolean {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return false
        return canonical.path == canonicalRoot.path ||
            canonical.path.startsWith(canonicalRoot.path + File.separator)
    }

    // ------------------------------------------------------------------ 结果与工具

    private fun guarded(block: () -> ClientResult): ClientResult = try {
        block()
    } catch (reject: Reject) {
        logger("ultra-switch rejected code=${reject.code} detail=${reject.detail}")
        ClientResult(false, reject.code, reject.detail)
    } catch (error: Throwable) {
        logger("ultra-switch failed: ${error.javaClass.simpleName}")
        ClientResult(false, ModeSwitchCodes.ROOT_UNAVAILABLE, error.javaClass.simpleName)
    }

    private fun statusOf(state: UltraSwitchStateFile): ClientStatus {
        val current = runCatching { bootIdSource() }.getOrDefault("")
        val bootObserved = current.isNotEmpty() && state.startBootId.isNotEmpty() && current != state.startBootId
        return ClientStatus(
            switchId = state.switchId,
            state = state.state,
            stage = state.stage,
            step = state.step,
            fromMode = state.fromMode,
            toMode = state.toMode,
            error = state.error,
            errorDetail = state.errorDetail,
            planSha256 = state.planSha256,
            rebootRequired = state.state == SwitchStateNames.REBOOT_REQUIRED,
            bootObserved = bootObserved,
            canRollback = state.state in ROLLBACK_ALLOWED,
            canResumeReboot = state.state == SwitchStateNames.REBOOT_REQUIRED && !bootObserved,
            verifyFailures = state.verifyFailures,
            slotSuffix = loadPlan(state.switchId)?.slotSuffix,
            targetPartition = loadPlan(state.switchId)?.targetPartition,
            signingMode = "",
            partitions = state.partitions.map { row ->
                ClientPartition(
                    name = row.name,
                    role = row.role,
                    size = row.size,
                    preSha256 = row.preSha256,
                    targetSha256 = row.targetSha256,
                    observedSha256 = row.observedSha256,
                    state = row.state,
                )
            },
        )
    }

    private fun runScript(script: String): RootExecResult = runCatching { runner(script) }.getOrElse { error ->
        RootExecResult(RootBackend.NONE, -1, "", "ULTRA_RUNNER_EXCEPTION:${error.javaClass.simpleName}")
    }

    private fun ok(result: RootExecResult): Boolean =
        result.backend != RootBackend.NONE && result.code == 0

    private fun script(marker: String, body: String): String = "$marker\n$body"

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun shaOf(output: String): String? =
        SHA_PATTERN.find(output.lowercase())?.value

    private fun firstInteger(output: String): Long? = output.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.matches(INTEGER) }
        ?.toLongOrNull()

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    // ------------------------------------------------------------------ 内部类型

    private data class Request(
        val switchId: String,
        val from: WorkMode,
        val to: WorkMode,
        val baselineId: String,
        /** 目标分区（LITE 固定 boot；KSU 默认 init_boot，安装时可选 boot）。 */
        val targetPartition: String,
        val sourcePartition: String? = null,
        val availablePartitions: Set<String>? = null,
    ) {
        val plannedPartitions: List<String> get() = ModeSwitchPreflight.writeOrder(to, targetPartition,
            sourcePartition, availablePartitions)
    }

    private data class UltraProbe(
        val slot: String,
        val devices: Map<String, String>,
        val sizes: Map<String, Long>,
        val request: Request,
    )

    /** commit 的一个写动作：目标分区写候选（restore=false）/ 源分区还原基线（restore=true）。 */
    private data class WriteOp(
        val name: String,
        val restore: Boolean,
        val device: String,
        val image: File,
        val expectedSha: String,
        val size: Long,
    )

    /** 逆序回滚结果：成功恢复的分区摘要 + 失败分区（`name:reason`）+ 是否发生过 dd 失败。 */
    private data class RollbackOutcome(
        val recovered: Map<String, String>,
        val failures: List<String>,
        val sawWriteFailure: Boolean,
    )

    private class Reject(val code: String, val detail: String) : Exception("$code: $detail")

    private object Mark {
        const val ROOT = "#maplesu:preflight-root"
        const val SLOT = "#maplesu:preflight-slot"
        const val KERNEL = "#maplesu:preflight-kernel"
        const val DEVICE = "#maplesu:preflight-device:"
        const val PLAN_WRITE = "#maplesu:begin-plan"
        const val MIRRORS = "#maplesu:begin-mirrors"
        const val STATE_WRITE = "#maplesu:state-write"
        const val STATE_READ = "#maplesu:state-read"
        const val PLAN_READ = "#maplesu:plan-read"
        const val JOURNAL = "#maplesu:journal"
        const val JOURNAL_READ = "#maplesu:journal-read"

        /** 每个计划分区的 PRE 快照（marker 后缀 = 分区名）。 */
        const val SNAPSHOT_PRE = "#maplesu:snapshot-pre:"
        const val BASELINE_LIST = "#maplesu:baseline-list"
        const val BASELINE_CAT = "#maplesu:baseline-cat"
        const val BASELINE_COPY = "#maplesu:baseline-copy"
        const val SOURCE_IMPORT = "#maplesu:source-import"
        const val EXPORT = "#maplesu:export"
        const val SUBMIT_COPY = "#maplesu:submit-copy"
        const val COMMIT_CHECK = "#maplesu:commit-check:"
        /** 目标分区写候选。 */
        const val COMMIT_WRITE = "#maplesu:commit-write"
        /** 非目标分区还原基线（BASE 镜像）。 */
        const val RESTORE_WRITE = "#maplesu:restore-write"
        /** commit 失败补偿 / 显式 rollback 的 PRE 回写（两者共用，靠脚本里的 of= 区分分区）。 */
        const val ROLLBACK_WRITE = "#maplesu:rollback-write"
        const val VERIFY = "#maplesu:verify"
        const val ABORT = "#maplesu:abort-clean"
        const val LIST_TX = "#maplesu:list-tx"
    }

    companion object {
        const val PLAN_FILE = "plan.json"
        const val STATE_FILE = "state.json"
        const val JOURNAL_FILE = "journal.jsonl"
        const val SUPERUSER_MIRROR = "superuser-mirror.json"
        const val SETTINGS_MIRROR = "settings-mirror.json"

        const val ROLE_WRITE = "WRITE_CANDIDATE"
        const val ROLE_RESTORE = "RESTORE_BASELINE"
        const val PLAN_SCHEMA = 1

        const val DEVICE_PREFIX = "/dev/block/by-name/"
        const val MAX_PARTITION_BYTES = 1024L * 1024 * 1024
        const val MAX_BASELINE_CANDIDATES = 16

        val SWITCH_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        val BASELINE_ID = Regex("^[A-Za-z0-9._-]{1,64}$")
        val PARTITIONS = setOf("boot", "init_boot", "vendor_boot")
        val IMAGE_KINDS = setOf("PRE", "BASE", "CANDIDATE")
        val PARTITION_NODE = Regex("^(boot|init_boot|vendor_boot)(_[a-z])?$")
        val ROLLBACK_ALLOWED = setOf(
            SwitchStateNames.COMMIT,
            SwitchStateNames.REBOOT_REQUIRED,
            SwitchStateNames.VERIFYING,
            SwitchStateNames.RECOVERY_REQUIRED,
            SwitchStateNames.ROLLING_BACK,
        )
        val ABORT_ALLOWED = setOf(
            SwitchStateNames.BEGIN,
            SwitchStateNames.SNAPSHOT,
            SwitchStateNames.PRODUCE,
        )
        /** 分区级"已写/未确认恢复"的状态值（rollback 据此缩小范围；证据缺失时保守全量）。 */
        const val WRITE_UNRECOVERED = "WRITE_UNRECOVERED"
        val WRITTEN_STATES = setOf("WRITTEN", "RESTORED", WRITE_UNRECOVERED)

        private val INTEGER = Regex("^[0-9]{1,15}$")
        private val SHA_PATTERN = Regex("[0-9a-f]{64}")
    }
}

/**
 * Ultra 根通道切换的**新增稳定码**（UI 原样展示 code）。
 *
 * 既有码能表达的一律复用 `ModeSwitchCodes`，这里只补 Ultra 路径特有的失败面：
 * 写失败 vs 回读失败 vs 回滚失败必须能区分，否则真机事故无法定级。
 */
object UltraRootSwitchCodes {
    /** 分区不在 plan 白名单，或设备节点越界（防注入/防错刷）。 */
    const val PARTITION_FORBIDDEN = "SWITCH_PARTITION_FORBIDDEN"
    /** 事务目录 state.json 写失败（fail-closed，不继续）。 */
    const val STATE_WRITE_FAILED = "SWITCH_STATE_WRITE_FAILED"
    /** 当前状态不允许该动作（如已写分区还 abort）。 */
    const val STATE_INVALID = "SWITCH_STATE_INVALID"
    /** 快照镜像大小与分区大小不一致（dd 短读）。 */
    const val SNAPSHOT_VERIFY_FAILED = "SWITCH_SNAPSHOT_VERIFY_FAILED"
    /** commit 的 dd 本身失败（未进入回读）。 */
    const val COMMIT_WRITE_FAILED = "SWITCH_COMMIT_WRITE_FAILED"
    /** commit 的 dd 成功但回读摘要不一致（已尝试回滚）。 */
    const val COMMIT_VERIFY_FAILED = "SWITCH_COMMIT_VERIFY_FAILED"
    /** 回滚 dd 失败。 */
    const val ROLLBACK_FAILED = "SWITCH_ROLLBACK_FAILED"
    /** 回滚 dd 成功但回读摘要不一致（进入 RECOVERY_REQUIRED）。 */
    const val ROLLBACK_VERIFY_FAILED = "SWITCH_ROLLBACK_VERIFY_FAILED"
    /**
     * 历史码（**commit 不再返回**）：源分区还原已在本实现内完成，需要还原时会按
     * `BASE:<name>` 自动还原；保留常量只为旧日志/旧 UI 文案兼容。
     */
    const val RESTORE_REQUIRED = "SWITCH_SOURCE_RESTORE_REQUIRED"
    /**
     * 基线闸门：非目标分区缺少基线 sha，或需要还原却缺 `BASE:<name>` 镜像 →
     * commit 在**任何 dd 之前**拒绝（不知道还原成什么就不允许写目标分区）。
     */
    const val BASELINE_REQUIRED = "SWITCH_BASELINE_REQUIRED"
    /** 基线镜像存在但写前本地复核失败（不可读/大小不符/sha 不符）→ 不写任何分区。 */
    const val BASELINE_IMAGE_INVALID = "SWITCH_BASELINE_IMAGE_INVALID"
}

// ---------------------------------------------------------------------------
// plan / state / journal（Ultra 路径自有事务文件；与切换服务的 wire 语义对齐）
// ---------------------------------------------------------------------------

@Serializable
data class UltraSwitchPlanPartition(
    val name: String,
    val role: String,
    val device: String,
    val size: Long,
)

@Serializable
data class UltraSwitchPlanFile(
    val schema: Int = UltraRootModeSwitchClient.PLAN_SCHEMA,
    val switchId: String,
    val fromMode: String,
    val toMode: String,
    val baselineId: String = "",
    val slotSuffix: String = "",
    val targetPartition: String,
    val createdAtMillis: Long = 0L,
    val partitions: List<UltraSwitchPlanPartition> = emptyList(),
    /** null = old plan; empty = source unknown, observed partitions form the read set. */
    val sourcePartition: String? = null,
)

@Serializable
data class UltraSwitchPartitionState(
    val name: String,
    val role: String,
    val device: String,
    val size: Long,
    val preSha256: String = "",
    /** PRE 镜像字节数（`dd` 后 `wc -c`）；0 = 旧版本 state.json 未记录。 */
    val preSize: Long = 0L,
    val targetSha256: String = "",
    val observedSha256: String = "",
    val baseSha256: String = "",
    val baselineSha256: String = "",
    val restoreRequired: Boolean = false,
    val state: String = "PENDING",
)

/**
 * 生产基线闸门判据（纯函数）：非目标分区里**缺基线 sha** 的名单；空 = 都有基线。
 *
 * 缺基线 sha 时源码无法证明"源分区能还原成 stock"，也无法给出可验证的回读预期；
 * 生产装配（`requireBaselineForCommit=true`）据此在 commit 的**任何 dd 之前**拒绝。
 * 需要还原却缺 `BASE:<name>` 镜像的检查在 `commitInternal` 里（不受本开关影响）。
 */
internal fun baselineMissingPartitions(state: UltraSwitchStateFile, target: String): List<String> =
    state.partitions
        .filter { it.name != target && it.baselineSha256.isBlank() }
        .map { it.name }

@Serializable
data class UltraSwitchStateFile(    val schema: Int = 1,
    val switchId: String,
    val state: String,
    val stage: String = "S1",
    val step: String = "begin",
    val fromMode: String,
    val toMode: String,
    val baselineId: String = "",
    val planSha256: String = "",
    val startBootId: String = "",
    val error: String = "",
    val errorDetail: String = "",
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
    val rebootRequested: Boolean = false,
    val verifyFailures: Int = 0,
    val targetPartition: String,
    val partitions: List<UltraSwitchPartitionState> = emptyList(),
    val images: Map<String, String> = emptyMap(),
    val sourcePartition: String? = null,
)

@Serializable
data class UltraSwitchJournalRecord(
    val op: String,
    val state: String,
    val stage: String,
    val step: String,
    val schema: Int = 1,
    val atMillis: Long = 0L,
    val partition: String = "",
    val code: String = "",
    val expectedSha256: String = "",
    val observedSha256: String = "",
)
