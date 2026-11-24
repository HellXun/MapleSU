package dev.maplesu.manager.installer

import dev.maplesu.manager.workmode.control.AtomicControlFile
import dev.maplesu.manager.workmode.detect.Clock
import dev.maplesu.manager.workmode.detect.WallClock
import dev.maplesu.manager.workmode.switch.BaselineCaptureRequest
import dev.maplesu.manager.workmode.switch.BaselineCheck
import dev.maplesu.manager.workmode.switch.BaselineErrors
import dev.maplesu.manager.workmode.switch.BaselineImageMaterializer
import dev.maplesu.manager.workmode.switch.BaselineImportRequest
import dev.maplesu.manager.workmode.switch.BaselineManifest
import dev.maplesu.manager.workmode.switch.BaselineProvenance
import dev.maplesu.manager.workmode.switch.BaselineResult
import dev.maplesu.manager.workmode.switch.BaselineRules
import dev.maplesu.manager.workmode.switch.BaselineState
import dev.maplesu.manager.workmode.switch.BaselineStore
import dev.maplesu.manager.workmode.switch.ModeSwitchPreflight
import dev.maplesu.manager.workmode.switch.PatchMarkers
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * SELinux 观察落地（2026-11-01 用户指令）：非 `Enforcing` 时把事实交给 [note] 记录，**绝不阻断**。
 *
 * 取值语义与 `ModeSwitchPreflight.selinuxCheck` 相同（同一份 `getenforce` 事实），
 * 但这里只产生一行日志/诊断，不产生任何判定结果；只有不可信来源可拒绝刷写。
 */
private fun recordSelinux(selinux: String, note: (String) -> Unit) {
    if (selinux != ModeSwitchPreflight.SELINUX_ENFORCING) {
        note("selinux=" + selinux.ifBlank { "Unknown" })
    }
}

/**
 * 抓取阶段的**带码失败**：`BaselinePartitionSourcePort` 的实现（例如"root dd 到 App 暂存"的
 * 生产实现）在 size/sha 不符时抛出它，materializer 原样把 `code` 交给调用方。
 *
 * 为什么不是普通 `IOException`：`InstallerBaselineMaterializer` 原来把所有异常都降级成
 * `BASELINE_CAPTURE_READ_FAILED`，于是"长度不符 / 摘要不符"这两个**可行动**的码在抓取路径上
 * 永远看不到（用户只能看到"读失败"）。这里只补一个"异常带码"的通道，不新增任何错误码。
 */
class BaselineCaptureFault(
    val code: String,
    detail: String,
    cause: Throwable? = null,
) : IOException(detail, cause)

/** 异常 → 错误码：带码的就用它自己的码，其余保持原来的兜底码（不改变旧语义）。 */
private fun faultCode(error: Throwable, fallback: String): String =
    (error as? BaselineCaptureFault)?.code ?: fallback


/**
 * P0：真实镜像字节来源的 materializer（由 `FileBaselineStore.capture/import` 调用）。
 *
 * 捕获：只读读取当前槽 boot/init_boot 整分区 → 满长/sha 复核 → 结构+cleanliness 证据 →
 * 原子写入 `baseline/<id>/<partition>.img`（tmp + fsync + ATOMIC_MOVE + 流式回读 sha）→
 * 返回"已修正 avbTailPreserved/patchMarkers"的 manifest 供 store 落 manifest。
 *
 * 导入：从 OEM/外部目录读取同长度镜像 → 同样的结构/cleanliness 断言（import 的 clean 证明
 * 只看镜像字节 + ramdisk residue；控制面 patch 证据只适用于"本机捕获"）→ 原子写入。
 *
 * 任何一步失败都不留下半成品目录（临时目录 finally 清理；已提交目录由 store 失败路径清理）。
 */
class InstallerBaselineMaterializer(
    private val source: BaselinePartitionSourcePort,
    private val cleanliness: BaselineCleanlinessPort,
    private val evidence: BaselinePatchEvidencePort,
    private val clock: Clock = WallClock,
    /**
     * 非阻断观察的落地口（默认丢弃）：只写日志/诊断，**不参与任何判定**。
     *
     * 2026-11-01 用户指令：SELinux 不再是 baseline 判据。非 `Enforcing` 时把
     * `selinux=<值>` 交给本回调记录，捕获照常进行（真正的硬判据仍是分区字节/长度/摘要
     * 与 cleanliness 的 patch markers / avb tail）。
     */
    private val notes: (String) -> Unit = {},
) : BaselineImageMaterializer {

    override fun capture(request: BaselineCaptureRequest, directory: File): BaselineResult {
        val manifest = request.manifest
        if (manifest.partitions.isEmpty()) {
            return BaselineResult.Failed(BaselineErrors.BASELINE_CORRUPT, "manifest has no partitions")
        }
        val snapshot = runCatching { source.snapshot() }.getOrElse {
            return BaselineResult.Failed(
                faultCode(it, BaselineCaptureCodes.CAPTURE_READ_FAILED), it.message.orEmpty(),
            )
        }
        // SELinux 事实（只记录，**不阻断**）：baseline 是否干净由下面的字节/摘要/cleanliness 判据决定。
        recordSelinux(snapshot.selinux, notes)
        val parent = directory.parentFile
            ?: return BaselineResult.Failed(BaselineErrors.BASELINE_CORRUPT, "no parent directory")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            return BaselineResult.Failed(BaselineErrors.BASELINE_CORRUPT, "parent: ${parent.absolutePath}")
        }
        val temporary = File(parent, ".${directory.name}.tmp-${UUID.randomUUID()}")
        if (!temporary.mkdirs()) {
            return BaselineResult.Failed(BaselineErrors.BASELINE_CORRUPT, "temp dir")
        }
        try {
            val images = LinkedHashMap<String, File>()
            val sizes = LinkedHashMap<String, Long>()
            for (partition in manifest.partitions) {
                val live = snapshot.partitions[partition.name]
                    ?: return BaselineResult.Failed(
                        BaselineErrors.BASELINE_CORRUPT, "${partition.name}: not observed",
                    )
                val temporaryImage = File(temporary, BaselineImageNames.imageName(partition.name))
                val read = runCatching { source.readPartition(partition.name, temporaryImage) }
                    .getOrElse {
                        return BaselineResult.Failed(
                            faultCode(it, BaselineCaptureCodes.CAPTURE_READ_FAILED),
                            "${partition.name}: ${it.message.orEmpty()}",
                        )
                    }
                if (read.size != partition.size || temporaryImage.length() != partition.size) {
                    return BaselineResult.Failed(
                        BaselineCaptureCodes.CAPTURE_SIZE_MISMATCH,
                        "${partition.name}: read=${read.size} file=${temporaryImage.length()} " +
                            "expected=${partition.size}",
                    )
                }
                val actualSha = BaselineAtomicFiles.sha256(temporaryImage)
                if (!actualSha.equals(partition.sha256, ignoreCase = true) ||
                    !actualSha.equals(read.sha256, ignoreCase = true)
                ) {
                    return BaselineResult.Failed(
                        BaselineCaptureCodes.CAPTURE_SHA_MISMATCH,
                        "${partition.name}: $actualSha != ${partition.sha256}",
                    )
                }
                if (live.size != partition.size ||
                    !live.sha256.equals(partition.sha256, ignoreCase = true)
                ) {
                    return BaselineResult.Failed(
                        BaselineErrors.BASELINE_STALE,
                        "${partition.name}: live facts changed since manifest was built",
                    )
                }
                if (manifest.provenance == BaselineSources.CAPTURED_PRE_WRITE) {
                    // 首次写入前捕获要求镜像就是当前分区内容（不允许"先改后捕"）。
                    if (!actualSha.equals(live.sha256, ignoreCase = true)) {
                        return BaselineResult.Failed(
                            BaselineErrors.BASELINE_STALE,
                            "${partition.name}: captured bytes do not match the live partition",
                        )
                    }
                }
                images[partition.name] = temporaryImage
                sizes[partition.name] = partition.size
            }
            val evidenceValue = if (manifest.provenance == BaselineSources.CAPTURED_PRE_WRITE) {
                evidence.read()
            } else {
                // 导入外部 OEM 镜像：clean 证明只看镜像字节（控制面能读到与否与外部镜像无关）。
                BaselinePatchEvidence(true, false, null, "import:image-only")
            }
            val cleanReport = cleanliness.verify(images, sizes, evidenceValue)
            if (!cleanReport.clean) {
                return BaselineResult.Failed(
                    cleanReport.code.ifEmpty { BaselineErrors.BASELINE_UNCLEAN },
                    cleanReport.detail,
                )
            }
            // ★ 这里**不再**校验/沿用请求 manifest 的 `clean` / `patchMarkers`：
            // 它们只是"请求形状"（占位），真实结论唯一来自上面的 cleanReport。
            // 以前这里硬写 `clean = true` 并把请求声明当成断言，等于让"调用方说干净"参与判定；
            // 现在落盘 manifest 的 clean/patchMarkers/avbTailPreserved 全部由 cleanReport 派生，
            // 请求里写 true 不会让任何脏镜像变成基线，写 false 也不会误拒已证明干净的镜像。
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                return BaselineResult.Failed(BaselineErrors.BASELINE_CORRUPT, "baseline dir")
            }
            for ((name, temporaryImage) in images) {
                val target = File(directory, BaselineImageNames.imageName(name))
                if (target.exists()) {
                    return BaselineResult.Failed(
                        BaselineErrors.BASELINE_PROMOTION_FORBIDDEN, "$name image already exists",
                    )
                }
                if (!BaselineAtomicFiles.copyVerified(
                        temporaryImage,
                        target,
                        manifest.partitions.first { part -> part.name == name }.sha256,
                    )
                ) {
                    target.delete()
                    return BaselineResult.Failed(
                        BaselineCaptureCodes.CAPTURE_READBACK_FAILED, "$name readback mismatch",
                    )
                }
            }
            val normalized = manifest.copy(
                // 派生自 cleanliness 结论，绝不硬写 true。
                clean = cleanReport.clean,
                patchMarkers = cleanReport.patchMarkers,
                partitions = manifest.partitions.map { part ->
                    part.copy(avbTailPreserved = cleanReport.avbTailPreserved[part.name] ?: true)
                },
                verifiedAt = clock.nowMillis(),
            )
            return BaselineResult.Ok(normalized)
        } catch (error: IOException) {
            return BaselineResult.Failed(BaselineCaptureCodes.CAPTURE_WRITE_FAILED, error.message.orEmpty())
        } finally {
            temporary.deleteRecursively()
        }
    }

    override fun import(request: BaselineImportRequest, directory: File): BaselineResult {
        val manifest = request.manifest
        if (manifest.partitions.isEmpty()) {
            return BaselineResult.Failed(BaselineErrors.BASELINE_CORRUPT, "manifest has no partitions")
        }
        if (BaselineRules.isStudyImage(request.sourcePath)) {
            return BaselineResult.Failed(BaselineErrors.BASELINE_PROVENANCE_UNKNOWN, request.sourcePath)
        }
        val sourceRoot = File(request.sourcePath)
        val parent = directory.parentFile
            ?: return BaselineResult.Failed(BaselineErrors.BASELINE_CORRUPT, "no parent directory")
        parent.mkdirs()
        val temporary = File(parent, ".${directory.name}.tmp-${UUID.randomUUID()}")
        if (!temporary.mkdirs()) return BaselineResult.Failed(BaselineErrors.BASELINE_CORRUPT, "temp dir")
        try {
            val images = LinkedHashMap<String, File>()
            val sizes = LinkedHashMap<String, Long>()
            for (partition in manifest.partitions) {
                val candidate = resolveImportFile(sourceRoot, partition.name)
                    ?: return BaselineResult.Failed(
                        BaselineCaptureCodes.IMPORT_SOURCE_MISSING,
                        "${partition.name}: ${sourceRoot.absolutePath}",
                    )
                if (!candidate.isFile || candidate.length() != partition.size) {
                    return BaselineResult.Failed(
                        BaselineCaptureCodes.CAPTURE_SIZE_MISMATCH,
                        "${partition.name}: ${candidate.length()} != ${partition.size}",
                    )
                }
                val actualSha = BaselineAtomicFiles.sha256(candidate)
                if (!actualSha.equals(partition.sha256, ignoreCase = true)) {
                    return BaselineResult.Failed(
                        BaselineCaptureCodes.CAPTURE_SHA_MISMATCH,
                        "${partition.name}: $actualSha != ${partition.sha256}",
                    )
                }
                val copy = File(temporary, BaselineImageNames.imageName(partition.name))
                candidate.copyTo(copy, overwrite = true)
                images[partition.name] = copy
                sizes[partition.name] = partition.size
            }
            val cleanReport = cleanliness.verify(
                images, sizes, BaselinePatchEvidence(true, false, null, "import:image-only"),
            )
            val selectedPatch = manifest.provenance == BaselineProvenance.USER_SELECTED &&
                cleanReport.code == BaselineErrors.BASELINE_UNCLEAN
            if (!cleanReport.clean && !selectedPatch) {
                return BaselineResult.Failed(
                    cleanReport.code.ifEmpty { BaselineErrors.BASELINE_UNCLEAN }, cleanReport.detail,
                )
            }
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                return BaselineResult.Failed(BaselineErrors.BASELINE_CORRUPT, "baseline dir")
            }
            for ((name, copy) in images) {
                val target = File(directory, BaselineImageNames.imageName(name))
                if (target.exists()) {
                    return BaselineResult.Failed(
                        BaselineErrors.BASELINE_PROMOTION_FORBIDDEN, "$name image already exists",
                    )
                }
                val expected = manifest.partitions.first { it.name == name }.sha256
                if (!BaselineAtomicFiles.copyVerified(copy, target, expected)) {
                    target.delete()
                    return BaselineResult.Failed(
                        BaselineCaptureCodes.CAPTURE_READBACK_FAILED, "$name readback mismatch",
                    )
                }
            }
            return BaselineResult.Ok(
                manifest.copy(
                    // 派生自 cleanliness 结论（见 capture() 的同一处说明）。
                    clean = cleanReport.clean,
                    patchMarkers = cleanReport.patchMarkers,
                    partitions = manifest.partitions.map { part ->
                        part.copy(avbTailPreserved = cleanReport.avbTailPreserved[part.name] ?: true)
                    },
                    verifiedAt = clock.nowMillis(),
                ),
            )
        } catch (error: IOException) {
            return BaselineResult.Failed(BaselineCaptureCodes.CAPTURE_WRITE_FAILED, error.message.orEmpty())
        } finally {
            temporary.deleteRecursively()
        }
    }

    private fun resolveImportFile(sourceRoot: File, partition: String): File? {
        if (sourceRoot.isDirectory) {
            val expected = File(sourceRoot, BaselineImageNames.imageName(partition))
            return expected.takeIf { it.isFile }
        }
        // 单文件只允许单分区 baseline（OEM boot.img / init_boot.img 一次一个）。
        return sourceRoot.takeIf { it.isFile }
    }
}

/** 基线镜像的原子落盘：tmp + fsync + ATOMIC_MOVE + 流式 sha 回读。 */
object BaselineAtomicFiles {
    fun sha256(file: File): String = digestOf(file)

    fun copyVerified(source: File, target: File, expectedSha256: String): Boolean {
        val parent = target.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return false
        val temporary = File(parent, ".${target.name}.tmp-${UUID.randomUUID()}")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            val actual = hex(digest.digest())
            if (!actual.equals(expectedSha256, ignoreCase = true)) return false
            // Read the synced copy before replacing an existing recovery image.
            if (!sha256(temporary).equals(expectedSha256, ignoreCase = true)) return false
            try {
                Files.move(
                    temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                )
            }
            AtomicControlFile.syncDirectory(parent)
            return sha256(target).equals(expectedSha256, ignoreCase = true)
        } catch (_: IOException) {
            return false
        } finally {
            temporary.delete()
        }
    }

    private fun digestOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/**
 * App 侧 baseline 门面（P0 第 3 条）：`status()` / `ensureBaseline()` / 捕获与导入入口。
 *
 * S0 基线门槛：`ensureBaseline().blocker != null` 时禁止进入切换；
 * 状态全部来自 [BaselineStore.verify]（manifest+镜像 sha），不做缓存推断。
 */
class BaselineCaptureService(
    private val store: BaselineStore,
    private val source: BaselinePartitionSourcePort,
    private val clock: Clock = WallClock,
    /**
     * 内容派生 id（`bl-<slot>-<fw8>-<boot12>`）：**必须**用同一份 [BaselineDeviceSnapshot] 里的
     * 分区摘要派生 ⇒ 同槽/同固件/同字节永远得到同一个 id ⇒ "已存在即复用（零 dd）"可判定。
     *
     * 为什么参数是 snapshot 而不是时间戳：时间戳 id 让"同一个分区字节"每次抓都得到新 id，
     * 于是每次切换都会重新 dd 96–200 MiB，且 `BASELINE_PROMOTION_FORBIDDEN` 与并发冲突面无限增长。
     */
    private val newBaselineId: (BaselineDeviceSnapshot) -> String = BaselineIdentity::fromSnapshot,
    /** 非阻断观察的落地口（默认丢弃）：只写日志/诊断，**不参与任何判定**。 */
    private val notes: (String) -> Unit = {},
) {

    fun status(): BaselineFacadeStatus {
        val verification = runCatching { store.verify() }.getOrElse {
            return BaselineFacadeStatus(
                state = BaselineFacadeState.CORRUPT,
                code = BaselineErrors.BASELINE_CORRUPT,
                detail = it.message.orEmpty(),
            )
        }
        val state = when (verification.state) {
            BaselineState.PRESENT_OK -> BaselineFacadeState.PRESENT_OK
            BaselineState.MISSING_NEVER_CAPTURED -> BaselineFacadeState.MISSING
            BaselineState.IMPORT_REQUIRED -> BaselineFacadeState.IMPORT_REQUIRED
            BaselineState.STALE -> BaselineFacadeState.STALE
            BaselineState.CORRUPT -> BaselineFacadeState.CORRUPT
        }
        return BaselineFacadeStatus(
            state = state,
            baselineId = verification.id,
            code = verification.blocker.orEmpty(),
            detail = verification.mismatch.joinToString(","),
            canCapture = state == BaselineFacadeState.MISSING,
            requiresImport = state in setOf(
                BaselineFacadeState.IMPORT_REQUIRED,
                BaselineFacadeState.STALE,
                BaselineFacadeState.CORRUPT,
            ),
            check = verification.check,
        )
    }

    /** UI/启动门控入口：返回当前状态 + 下一步动作 + S0 阻断码（null = 可切换）。 */
    fun ensureBaseline(): BaselineEnsureResult {
        val current = status()
        val action = when (current.state) {
            BaselineFacadeState.PRESENT_OK -> "NONE"
            BaselineFacadeState.MISSING -> "CAPTURE"
            else -> "IMPORT"
        }
        val blocker = when (current.state) {
            BaselineFacadeState.PRESENT_OK -> null
            BaselineFacadeState.MISSING -> BaselineErrors.BASELINE_MISSING
            BaselineFacadeState.IMPORT_REQUIRED -> BaselineErrors.BASELINE_UNCLEAN
            BaselineFacadeState.STALE -> BaselineErrors.BASELINE_STALE
            BaselineFacadeState.CORRUPT -> BaselineErrors.BASELINE_CORRUPT
        }
        return BaselineEnsureResult(current, action, blocker)
    }

    /**
     * 来源①：**抓取优先** —— 首次写入前捕获当前槽真实镜像（`provenance=PRE_FIRST_WRITE_CAPTURE`）。
     *
     * 语义（2026-11-01 W4a）：
     * 1. **已存在即复用**：`store.verify()` 判 `PRESENT_OK` ⇒ 直接把那份 manifest 原样返回
     *    （`BaselineResult.Ok`），**一次 dd 都不做**，也绝不覆盖（`immutable` 红线仍在）。
     *    以前这里返回 `BASELINE_PROMOTION_FORBIDDEN`，等价于"第二次点切换必定失败"。
     * 2. **id 内容派生**：`id == null` 时按 `slot + firmware + sha256(boot)` 派生（见 [BaselineIdentity]），
     *    不再用时间戳 ⇒ 同一份字节永远落到同一个目录。
     * 3. `clean` 在**请求**里是占位（false）：真实结论由 materializer 跑完
     *    `BaselineCleanlinessPort` 之后写回（`FileBaselineStore` 传 `requireCleanProof = false`）。
     *    因此本类**没有**任何地方硬写 `clean = true`。
     */
    fun capture(
        id: String? = null,
        provenance: String = BaselineSources.CAPTURED_PRE_WRITE,
    ): BaselineResult {
        val existing = runCatching { store.verify() }.getOrElse {
            return BaselineResult.Failed(BaselineErrors.BASELINE_CORRUPT, it.message.orEmpty())
        }
        if (existing.state == BaselineState.PRESENT_OK) {
            store.manifest(existing.id)?.let { return BaselineResult.Ok(it) }
            return BaselineResult.Failed(
                BaselineErrors.BASELINE_PROMOTION_FORBIDDEN,
                "baseline already present but its manifest is unreadable: ${existing.id}",
            )
        }
        if (existing.state == BaselineState.STALE || existing.state == BaselineState.CORRUPT) {
            return BaselineResult.Failed(
                existing.blocker ?: BaselineErrors.BASELINE_STALE,
                "existing baseline state=${existing.state}; resolve before capturing",
            )
        }
        val snapshot = runCatching { source.snapshot() }.getOrElse {
            return BaselineResult.Failed(
                faultCode(it, BaselineCaptureCodes.CAPTURE_READ_FAILED), it.message.orEmpty(),
            )
        }
        // SELinux 事实（只记录，**不阻断**）：非 Enforcing 只写 note，捕获继续。
        // baseline 的硬判据仍是分区字节/长度/摘要与 cleanliness（patch markers / avb tail）。
        recordSelinux(snapshot.selinux, notes)
        if ("boot" !in snapshot.partitions) {
            return BaselineResult.Failed(BaselineErrors.BASELINE_CORRUPT, "boot partition not observed")
        }
        val baselineId = id ?: runCatching { newBaselineId(snapshot) }.getOrElse {
            return BaselineResult.Failed(BaselineErrors.BASELINE_CORRUPT, "id derivation failed")
        }
        if (!BaselineIdentity.isValidId(baselineId)) {
            return BaselineResult.Failed(
                BaselineErrors.BASELINE_CORRUPT, "invalid baseline id: $baselineId",
            )
        }
        val manifest = BaselineManifest(
            id = baselineId,
            createdAt = clock.nowMillis(),
            provenance = provenance,
            // 请求占位：真实值由 materializer 的 cleanliness 结论覆盖（这里写 true 才是"硬写"）。
            clean = false,
            verifiedAt = clock.nowMillis(),
            slot = snapshot.slot,
            firmware = snapshot.firmware,
            kernelRelease = snapshot.kernelRelease,
            partitions = snapshot.requiredPartitions().map { name ->
                baselinePartition(snapshot.partitions.getValue(name), avbTailPreserved = true)
            },
            avb = baselineAvbFacts(snapshot.vbmeta),
            patchMarkers = PatchMarkers(),
            immutable = true,
        )
        return store.capture(BaselineCaptureRequest(baselineId, manifest))
    }

    /** 来源②：用户/外部导入 OEM stock 备份（目录含 boot.img/init_boot.img，或单分区单文件）。 */
    fun import(
        id: String,
        sourcePath: File,
        provenance: String = BaselineSources.OEM_PACKAGE,
    ): BaselineResult {
        if (!sourcePath.exists()) {
            return BaselineResult.Failed(
                BaselineCaptureCodes.IMPORT_SOURCE_MISSING, sourcePath.absolutePath,
            )
        }
        if (BaselineRules.isStudyImage(sourcePath.absolutePath)) {
            return BaselineResult.Failed(
                BaselineErrors.BASELINE_PROVENANCE_UNKNOWN, "study image: ${sourcePath.absolutePath}",
            )
        }
        val snapshot = runCatching { source.snapshot() }.getOrElse {
            return BaselineResult.Failed(
                faultCode(it, BaselineCaptureCodes.CAPTURE_READ_FAILED), it.message.orEmpty(),
            )
        }
        val partitions = ArrayList<dev.maplesu.manager.workmode.switch.BaselinePartition>()
        for (name in snapshot.requiredPartitions()) {
            val file = if (sourcePath.isDirectory) File(sourcePath, BaselineImageNames.imageName(name))
            else sourcePath
            if (!file.isFile) {
                return BaselineResult.Failed(
                    BaselineCaptureCodes.IMPORT_SOURCE_MISSING, "${name}: ${file.absolutePath}",
                )
            }
            val expectedSize = snapshot.partitions.getValue(name).size
            if (file.length() != expectedSize) {
                return BaselineResult.Failed(
                    BaselineCaptureCodes.CAPTURE_SIZE_MISMATCH,
                    "${name}: ${file.length()} != $expectedSize",
                )
            }
            partitions += baselinePartition(
                BaselinePartitionFacts(name, expectedSize, BaselineAtomicFiles.sha256(file)),
                avbTailPreserved = true,
            )
        }
        val manifest = BaselineManifest(
            id = id,
            createdAt = clock.nowMillis(),
            provenance = provenance,
            // 导入同样是"请求占位"：真实 clean 由 materializer 对镜像字节跑 cleanliness 之后写回。
            clean = false,
            verifiedAt = clock.nowMillis(),
            slot = snapshot.slot,
            firmware = snapshot.firmware,
            kernelRelease = snapshot.kernelRelease,
            partitions = partitions,
            avb = baselineAvbFacts(snapshot.vbmeta),
            patchMarkers = PatchMarkers(),
            immutable = true,
        )
        return store.import(BaselineImportRequest(sourcePath.absolutePath, manifest))
    }

    /** S0-04 硬门槛：null = 可以进入切换；非 null = 必须阻断（02 错误码）。 */
    fun blockerForS0(): String? = ensureBaseline().blocker

    fun check(): BaselineCheck = runCatching { store.check() }.getOrDefault(BaselineCheck("", "", "", "", false))
}
