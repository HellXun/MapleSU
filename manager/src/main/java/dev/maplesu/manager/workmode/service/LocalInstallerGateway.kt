package dev.maplesu.manager.workmode.service

import dev.maplesu.installer.InstallerMultiPartitionTransaction
import dev.maplesu.installer.SwitchFailure
import dev.maplesu.installer.SwitchFacts
import dev.maplesu.installer.SwitchPlan
import dev.maplesu.manager.installer.AndroidSwitchPartitionProvider
import dev.maplesu.manager.installer.AndroidWritablePartition
import dev.maplesu.manager.installer.InstallerPartitionReader
import dev.maplesu.manager.installer.InstallerServiceClient
import dev.maplesu.manager.installer.InstallerSnapshot
import dev.maplesu.manager.workmode.switch.BeginInputs
import dev.maplesu.manager.workmode.switch.AuthorityRead
import dev.maplesu.manager.workmode.switch.ExportedImage
import dev.maplesu.manager.workmode.switch.GatewayOutcome
import dev.maplesu.manager.workmode.switch.GatewayPreflight
import dev.maplesu.manager.workmode.switch.GatewayState
import dev.maplesu.manager.workmode.switch.GatewayStatus
import dev.maplesu.manager.workmode.switch.InstallerGateway
import dev.maplesu.manager.workmode.switch.ModeSwitchCodes
import dev.maplesu.manager.workmode.switch.ModeSwitchHashes
import dev.maplesu.manager.workmode.switch.ModeSwitchJournalSink
import dev.maplesu.manager.workmode.switch.ModeSwitchPreflight
import dev.maplesu.manager.workmode.switch.ModeSwitchRequest
import dev.maplesu.manager.workmode.switch.PartitionInventory
import dev.maplesu.manager.workmode.switch.PartitionObservation
import dev.maplesu.manager.workmode.switch.SwitchDeviceFacts
import dev.maplesu.manager.workmode.switch.SwitchAuthority
import dev.maplesu.manager.workmode.switch.SwitchJournal
import dev.maplesu.manager.workmode.switch.SwitchPartitionStatus
import dev.maplesu.manager.workmode.switch.SwitchRecord
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.control.AtomicControlFile
import java.io.File
import java.io.IOException

/**
 * 进程内 `InstallerGateway`：root 服务自己执行 S0b/S1 校验、S2 只读副本、S3 prepare、
 * S4 commit/rollback、S6 verify。
 *
 * 字节写入仍然只发生在 `InstallerMultiPartitionTransaction` → `BlockDeviceTransaction`
 * （红线 4）；本类只做"分区身份观测 + 只读复制 + 编排调用"。
 */
internal class LocalInstallerGateway(
    private val baselineRoot: File,
    private val authority: SwitchAuthority? = null,
    private val journalFactory: (String) -> SwitchJournal,
) : InstallerGateway {

    private val plans = LinkedHashMap<String, SwitchPlan>()

    override fun preflight(request: ModeSwitchRequest): GatewayPreflight {
        return preflight(request, observeSnapshot())
    }

    internal fun preflight(request: ModeSwitchRequest, snapshot: InstallerSnapshot): GatewayPreflight {
        val device = deviceFacts(snapshot)
        // SELinux 不再是判据（2026-11-01 用户指令：只有不可信来源可拒绝刷写）。
        // 非 Enforcing 时把事实放进 S0-12 的 advisory（证据），预检继续；
        // 恢复动作仍只在 `ModeSwitchFacade.startSwitch` 入口执行一次。
        val selinuxCheck = ModeSwitchPreflight.selinuxCheck(device.selinux)
        val inventory = PartitionInventory(
            present = snapshot.partitions.keys.toSet(),
            sizes = snapshot.partitions.mapValues { it.value.size },
        )
        if ("boot" !in snapshot.partitions) {
            return GatewayPreflight(false, ModeSwitchCodes.LAYOUT_UNSUPPORTED, "boot-missing")
        }
        val resolved = request.resolve(inventory.present, device.kernelRelease)
        val missing = resolved.plannedPartitions(inventory.present).filter { it !in inventory.present }
        if (missing.isNotEmpty()) {
            return GatewayPreflight(false, ModeSwitchCodes.LAYOUT_UNSUPPORTED, missing.joinToString(","))
        }
        return GatewayPreflight(
            ok = true,
            device = device,
            partitionInventory = inventory,
            // S0b 也要留下 SELinux 事实（S0-12 恒 ok=true，advisory 非空=非 Enforcing）。
            checks = listOf(selinuxCheck),
        )
    }

    override fun begin(request: ModeSwitchRequest, inputs: BeginInputs): GatewayOutcome =
        GatewayOutcome(true, GatewayState.WRITTEN)

    override fun snapshot(request: ModeSwitchRequest): GatewayOutcome {
        val snapshot = observeSnapshot()
        val status = GatewayStatus(
            switchId = request.switchId,
            state = "SNAPSHOT",
            stage = "S2",
            step = "snapshot",
            partitions = snapshot.partitions.map { (name, partition) ->
                SwitchPartitionStatus(
                    name = name,
                    role = "",
                    size = partition.size,
                    preSha256 = partition.sha256,
                    targetSha256 = "",
                    tailSha256 = "",
                    observedSha256 = partition.sha256,
                    state = "PENDING",
                )
            },
        )
        return GatewayOutcome(true, GatewayState.WRITTEN, status = status)
    }

    override fun exportImage(request: ModeSwitchRequest, kind: String, partition: String,
                             destination: File): ExportedImage {
        destination.parentFile?.mkdirs()
        when (kind) {
            "PRE" -> copyPartition(request, partition, destination)
            "BASE" -> {
                val source = File(baselineRoot, "${request.baselineId}/$partition.img")
                if (!source.isFile) throw IOException("${ModeSwitchCodes.PRE_IMAGE_INVALID}: base $partition")
                if (source.canonicalFile != destination.canonicalFile) {
                    source.inputStream().use { input ->
                        destination.outputStream().use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                }
            }
            else -> throw IOException("${ModeSwitchCodes.PRE_IMAGE_INVALID}: kind $kind")
        }
        destination.absoluteFile.parentFile?.let(AtomicControlFile::syncDirectory)
        val sha = ModeSwitchHashes.sha256(destination)
        return ExportedImage(destination, destination.length(), sha)
    }

    override fun submitCandidate(request: ModeSwitchRequest, partition: String,
                                 image: ExportedImage): GatewayOutcome = GatewayOutcome(true, GatewayState.WRITTEN)

    override fun preparePlan(request: ModeSwitchRequest, planFlatFields: Map<String, String>): GatewayOutcome {
        return try {
            val parsed = SwitchPlan.parse(planFlatFields)
            requireRequestPlan(parsed, request)
            val planRequest = SwitchPlan.Request(
                parsed.schema, parsed.switchId, parsed.fromMode, parsed.toMode, parsed.baselineId,
                parsed.startBootId, parsed.device, parsed.steps,
            )
            val provider = AndroidSwitchPartitionProvider(observeSnapshot())
            val sink = ModeSwitchJournalSink(journalFactory(request.switchId), request.switchId)
            plans[request.switchId] = InstallerMultiPartitionTransaction.prepare(planRequest, provider, sink)
            GatewayOutcome(true, GatewayState.WRITTEN)
        } catch (failure: SwitchFailure) {
            GatewayOutcome(false, "", failure.code, failure.detail)
        } catch (error: Throwable) {
            if (error is Error) throw error
            GatewayOutcome(false, "", ModeSwitchCodes.PLAN_INVALID, error.message.orEmpty())
        }
    }

    override fun commit(request: ModeSwitchRequest): GatewayOutcome {
        return try {
            val plan = preparedPlan(request)
                ?: return GatewayOutcome(false, "", ModeSwitchCodes.PLAN_INVALID, "no prepared plan")
            val provider = AndroidSwitchPartitionProvider(observeSnapshot())
            val result = InstallerMultiPartitionTransaction.commit(
                plan, provider, ModeSwitchJournalSink(journalFactory(request.switchId), request.switchId),
            )
            when (result.state) {
                InstallerMultiPartitionTransaction.State.WRITTEN ->
                    GatewayOutcome(true, GatewayState.WRITTEN,
                        status = statusFor(plan, observedSha256 = result.observedSha256))
                InstallerMultiPartitionTransaction.State.ROLLED_BACK ->
                    GatewayOutcome(false, GatewayState.ROLLED_BACK, result.code, result.detail,
                        statusFor(plan, "ROLLED_BACK"))
                else ->
                    GatewayOutcome(false, GatewayState.RECOVERY_REQUIRED, result.code, result.detail,
                        statusFor(plan, "RECOVERY_REQUIRED"))
            }
        } catch (failure: SwitchFailure) {
            GatewayOutcome(false, GatewayState.RECOVERY_REQUIRED, failure.code, failure.detail)
        } catch (error: Throwable) {
            if (error is Error) throw error
            GatewayOutcome(false, GatewayState.RECOVERY_REQUIRED, ModeSwitchCodes.WRITE_FAILED,
                error.message.orEmpty())
        }
    }

    override fun rollback(request: ModeSwitchRequest): GatewayOutcome {
        return try {
            val plan = preparedPlan(request)
                ?: return GatewayOutcome(false, GatewayState.RECOVERY_REQUIRED,
                    ModeSwitchCodes.PLAN_INVALID, "no prepared plan")
            val provider = AndroidSwitchPartitionProvider(observeSnapshot())
            val result = InstallerMultiPartitionTransaction.rollback(
                plan, provider, ModeSwitchJournalSink(journalFactory(request.switchId), request.switchId),
            )
            if (result.state == InstallerMultiPartitionTransaction.State.ROLLED_BACK) {
                GatewayOutcome(true, GatewayState.ROLLED_BACK)
            } else {
                GatewayOutcome(false, GatewayState.RECOVERY_REQUIRED, result.code, result.detail)
            }
        } catch (failure: SwitchFailure) {
            GatewayOutcome(false, GatewayState.RECOVERY_REQUIRED, failure.code, failure.detail)
        } catch (error: Throwable) {
            if (error is Error) throw error
            GatewayOutcome(false, GatewayState.RECOVERY_REQUIRED, ModeSwitchCodes.ROLLBACK_FAILED,
                error.message.orEmpty())
        }
    }

    override fun abort(request: ModeSwitchRequest): GatewayOutcome =
        GatewayOutcome(true, GatewayState.CANCELLED)

    override fun observePartitions(request: ModeSwitchRequest,
                                   names: List<String>): List<PartitionObservation> {
        val snapshot = runCatching { observeSnapshot() }.getOrNull() ?: return emptyList()
        return names.mapNotNull { name ->
            snapshot.partitions[name]?.let { PartitionObservation(name, it.size, it.sha256) }
        }
    }

    override fun verify(request: ModeSwitchRequest): GatewayOutcome {
        return try {
            val plan = preparedPlan(request)
                ?: return GatewayOutcome(false, "", ModeSwitchCodes.PLAN_INVALID, "no prepared plan")
            val snapshot = observeSnapshot()
            val provider = AndroidSwitchPartitionProvider(snapshot)
            val result = InstallerMultiPartitionTransaction.verify(
                plan, provider, facts(snapshot),
                ModeSwitchJournalSink(journalFactory(request.switchId), request.switchId),
            )
            if (result.state == InstallerMultiPartitionTransaction.State.VERIFIED) {
                GatewayOutcome(true, GatewayState.VERIFIED)
            } else {
                GatewayOutcome(false, GatewayState.RECOVERY_REQUIRED, result.code, result.detail)
            }
        } catch (failure: SwitchFailure) {
            GatewayOutcome(false, GatewayState.RECOVERY_REQUIRED, failure.code, failure.detail)
        } catch (error: Throwable) {
            if (error is Error) throw error
            GatewayOutcome(false, "", ModeSwitchCodes.WRITE_IO_FAILED, error.message.orEmpty())
        }
    }

    override fun reboot(request: ModeSwitchRequest): Boolean = false

    override fun status(switchId: String): GatewayStatus? = null

    override fun exportDiagnostics(request: ModeSwitchRequest, destination: File): File {
        destination.parentFile?.mkdirs()
        destination.writeText("switchId=${request.switchId}\n")
        return destination
    }

    // ------------------------------------------------------------------ primitives

    private fun preparedPlan(request: ModeSwitchRequest): SwitchPlan? {
        plans[request.switchId]?.let { requireRequestPlan(it, request); return it }
        val record = when (val read = authority?.read()) {
            null, AuthorityRead.Missing -> return null
            is AuthorityRead.Corrupt -> throw SwitchFailure(ModeSwitchCodes.RECOVERY_JOURNAL_CORRUPT,
                "${read.code}:${read.detail}")
            is AuthorityRead.Ok -> read.record
        }
        if (record.switchId != request.switchId) return null
        val fields = record.artifacts.filterKeys { it.startsWith(SwitchRecord.PREPARED_PLAN_PREFIX) }
            .mapKeys { (key, _) -> key.removePrefix(SwitchRecord.PREPARED_PLAN_PREFIX) }
        if (fields.isEmpty()) return null
        val plan = SwitchPlan.parse(fields)
        requireRequestPlan(plan, request)
        if (plan.planSha256 != record.planSha256 || plan.startBootId != record.startBootId ||
            plan.fromMode.name != record.fromMode || plan.toMode.name != record.toMode ||
            plan.baselineId != record.baselineId) {
            throw SwitchFailure(ModeSwitchCodes.PLAN_INVALID, "prepared plan record mismatch")
        }
        for (step in plan.steps) {
            val imageKey = if (step.role == dev.maplesu.installer.StepRole.WRITE_CANDIDATE)
                "candidate-${step.partition}" else "base-${step.partition}"
            val image = record.artifacts[imageKey]
            val preImage = record.artifacts["pre-${step.partition}"]
            if (image == null || preImage == null ||
                step.image.toAbsolutePath().normalize() != File(image).toPath().toAbsolutePath().normalize() ||
                step.preImage.toAbsolutePath().normalize() != File(preImage).toPath().toAbsolutePath().normalize()) {
                throw SwitchFailure(ModeSwitchCodes.PLAN_INVALID, "prepared plan artifact mismatch")
            }
        }
        plans[request.switchId] = plan
        return plan
    }

    private fun requireRequestPlan(plan: SwitchPlan, request: ModeSwitchRequest) {
        if (plan.schema != SwitchPlan.SCHEMA || plan.switchId != request.switchId ||
            plan.fromMode.name != request.fromMode.name || plan.toMode.name != request.toMode.name ||
            plan.baselineId != request.baselineId) {
            throw SwitchFailure(ModeSwitchCodes.PLAN_INVALID, "prepared plan request mismatch")
        }
    }

    /** 当前设备事实（S0b 预检与 baseline 校验共用）。 */
    fun deviceFactsNow(): SwitchDeviceFacts = deviceFacts(observeSnapshot())

    private fun observeSnapshot(): InstallerSnapshot =
        InstallerServiceClient.decodeSnapshot(InstallerPartitionReader.observe())

    private fun deviceFacts(snapshot: InstallerSnapshot) = SwitchDeviceFacts(
        snapshot.slotSuffix, snapshot.firmware, snapshot.kernelRelease, snapshot.selinux,
    )

    private fun facts(snapshot: InstallerSnapshot): SwitchFacts {
        val device = deviceFacts(snapshot)
        return object : SwitchFacts {
            override fun bootId(): String = this@LocalInstallerGateway.bootId()
            override fun slotSuffix(): String = device.slot
            override fun firmware(): String = device.firmware
            override fun kernelRelease(): String = device.kernelRelease
            override fun selinux(): String = device.selinux
        }
    }

    private fun bootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("")

    /** 只读复制一整块分区（经 WritablePartition.Device.read，绝不写块设备）。 */
    private fun copyPartition(request: ModeSwitchRequest, partition: String, destination: File) {
        val snapshot = observeSnapshot()
        val writable = AndroidWritablePartition(snapshot, partition)
        val buffer = ByteArray(64 * 1024)
        var offset = 0L
        writable.open().use { device ->
            destination.outputStream().use { output ->
                while (offset < writable.size()) {
                    val count = device.read(offset, buffer, minOf(buffer.size.toLong(),
                        writable.size() - offset).toInt())
                    if (count <= 0) throw IOException("${ModeSwitchCodes.PRE_IMAGE_INVALID}: short read")
                    output.write(buffer, 0, count)
                    offset += count
                }
                output.fd.sync()
            }
        }
    }

    private fun statusFor(plan: SwitchPlan, state: String = "DONE",
                          observedSha256: Map<String, String> = emptyMap()): GatewayStatus = GatewayStatus(
        switchId = plan.switchId,
        state = state,
        stage = "S4",
        step = "4g",
        planSha256 = plan.planSha256,
        partitions = plan.steps.map { step ->
            SwitchPartitionStatus(
                name = step.partition,
                role = step.role.name,
                size = step.size,
                preSha256 = step.preSha256,
                targetSha256 = step.imageSha256,
                tailSha256 = step.tailSha256,
                observedSha256 = observedSha256[step.partition].orEmpty(),
                state = state,
            )
        },
    )
}
