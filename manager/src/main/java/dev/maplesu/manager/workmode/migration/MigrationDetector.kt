package dev.maplesu.manager.workmode.migration

import dev.maplesu.manager.workmode.WorkMode

/**
 * 迁移前检测（05 §1.5 判定树 + §1.9 阻断条件）。
 *
 * 纯函数：只吃 [ExternalRootProbe] / [PackageInventory] / [DeviceFacts]，不 import Android、
 * 不执行命令、不读块设备。探针由服务侧只读实现（[ExternalRootProbeReader]）。
 */
object MigrationDetector {

    const val TARGET_REASON_KSU = "MIGRATION_TARGET_REASON_KSU_MODULES_AND_AUTH"
    const val TARGET_REASON_LITE = "MIGRATION_TARGET_REASON_LITE_ONLY"

    /**
     * 家族判定。证据优先级：镜像 marker > 数据面（目录/二进制）> `su -v` 文本 > 包名。
     * 已知家族要求"证据充分"（主文档:1136）；任何歧义按 UNKNOWN best-effort（主文档:1149）。
     */
    fun detect(
        probe: ExternalRootProbe,
        inventory: PackageInventory = PackageInventory(),
        selfPackages: Set<String> = setOf(MigrationPaths.SELF_PACKAGE),
    ): ExternalRootInfo {
        val evidence = mutableListOf<Evidence>()

        // ---- 0) 硬前提：外部 root 可建立会话（主文档:1058）
        if (probe.suUid != 0 || !probe.device.externalRootAvailable) {
            evidence += Evidence(
                EvidenceKind.SU_VERSION, "id -u", "uid=${probe.suUid}",
                EvidenceStrength.DEFINITIVE,
            )
            return ExternalRootInfo(
                family = Family.UNKNOWN,
                blockers = listOf(Blocker.NoRoot),
                supported = false,
                bestEffort = true,
                evidence = evidence,
            )
        }
        evidence += Evidence(EvidenceKind.SU_VERSION, "id -u", "0", EvidenceStrength.DEFINITIVE)

        // ---- 1) 镜像证据（最不可伪造；主文档:1141-1144）
        val present = probe.partitions.values.filter { it.present }
        val ksuMarked = present.filter {
            ImageMarker.KSU_KO in it.markers || ImageMarker.SEVENK_KO in it.markers ||
                ImageMarker.KSU_INIT_REAL in it.markers
        }
        val magiskMarked = present.filter {
            it.markers.any { marker ->
                marker == ImageMarker.MAGISK_BACKUP || marker == ImageMarker.MAGISK_OVERLAY ||
                    marker == ImageMarker.MAGISK_INIT
            }
        }
        ksuMarked.forEach { p ->
            evidence += Evidence(
                EvidenceKind.IMAGE_MARKER, "${p.name} ramdisk markers",
                p.markers.joinToString(","), EvidenceStrength.DEFINITIVE,
            )
        }
        magiskMarked.forEach { p ->
            evidence += Evidence(
                EvidenceKind.IMAGE_MARKER, "${p.name} ramdisk markers",
                p.markers.joinToString(","), EvidenceStrength.DEFINITIVE,
            )
        }

        // ---- 2) 数据面证据
        val sevenkDir = probe.files[MigrationPaths.SEVENK_DIR]
        val ksuDir = probe.files[MigrationPaths.KSU_DIR]
        val magiskDir = probe.files[MigrationPaths.MAGISK_DIR]
        val ksud = probe.tools["ksud"]
        val magisk = probe.tools["magisk"]
        val magiskboot = probe.tools["magiskboot"]
        val suVersion = probe.suVersion
        val sevenkKoMarker = ksuMarked.any { ImageMarker.SEVENK_KO in it.markers }
        val ksuCompatSymlink = ksuDir?.let { it.isSymlink && it.linkTarget?.contains("sevenk") == true } == true

        val ksuSignal = ksuDir?.exists == true || ksud?.executable == true || ksuMarked.isNotEmpty()
        val magiskSignal = magiskDir?.exists == true || magisk?.executable == true || magiskMarked.isNotEmpty()
        // 目录存在即记为证据（即使后面被歧义降级为 UNKNOWN）
        if (ksuDir?.exists == true) evidence += Evidence(EvidenceKind.DATA_DIR, MigrationPaths.KSU_DIR, "exists", EvidenceStrength.STRONG)
        if (sevenkDir?.exists == true) evidence += Evidence(EvidenceKind.DATA_DIR, MigrationPaths.SEVENK_DIR, "exists", EvidenceStrength.DEFINITIVE)
        if (magiskDir?.exists == true) evidence += Evidence(EvidenceKind.DATA_DIR, MigrationPaths.MAGISK_DIR, "exists", EvidenceStrength.STRONG)
        if (ksud?.executable == true) evidence += Evidence(EvidenceKind.TOOL_BINARY, MigrationPaths.KSUD, "executable", EvidenceStrength.STRONG)
        if (magisk?.executable == true) evidence += Evidence(EvidenceKind.TOOL_BINARY, "${MigrationPaths.MAGISK_DIR}/magisk", "executable", EvidenceStrength.STRONG)
        if (suVersion != null) evidence += Evidence(EvidenceKind.SU_VERSION, "su -v", suVersion, EvidenceStrength.WEAK)

        // ---- 3) 家族判定（镜像 > 数据面 > 版本文本 > 包名）
        val apatchSignal = suVersion?.contains("APatch", ignoreCase = true) == true
        val ambiguous = ksuSignal && magiskSignal
        val family: Family = when {
            ambiguous -> Family.UNKNOWN
            sevenkDir?.exists == true || sevenkKoMarker || ksuCompatSymlink -> Family.KSU_7K
            ksuSignal -> Family.KSU_UPSTREAM
            magiskSignal -> Family.MAGISK
            apatchSignal -> Family.APATCH
            else -> Family.UNKNOWN
        }
        if (ambiguous) {
            evidence += Evidence(
                EvidenceKind.DATA_DIR, "family", "KSU and Magisk evidence coexist -> best-effort",
                EvidenceStrength.WEAK,
            )
        }
        if (ksuCompatSymlink) {
            evidence += Evidence(
                EvidenceKind.DATA_DIR, MigrationPaths.KSU_DIR,
                "symlink -> ${ksuDir?.linkTarget}", EvidenceStrength.DEFINITIVE,
            )
        }

        // ---- 4) 版本与 UAPI（ksud -V = "{VERSION_NAME} (uapi: {N})"，KSU/defs.rs:84-86）
        val version: String? = when (family) {
            Family.KSU_UPSTREAM, Family.KSU_7K -> ksud?.version ?: suVersion
            Family.MAGISK -> magisk?.version ?: suVersion
            else -> suVersion
        }
        val uapi = version?.let { Regex("uapi:\\s*(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        // ---- 5) 管理器包名（只取外部；绝不含自己）
        val knownPackages = MigrationPaths.ALL_MANAGER_PACKAGES - selfPackages
        val managerPackages = inventory.allPackages().filter { it in knownPackages }.distinct().sorted()
        managerPackages.forEach {
            evidence += Evidence(EvidenceKind.PACKAGE, "pm list packages", it, EvidenceStrength.WEAK)
        }

        // ---- 6) stock 备份可用性（§1.7）
        val backups = probe.stockBackups
        val usableBackupPartitions = backups.values
            .filter { it.state == BackupState.USABLE }
            .mapNotNull { it.partitionHint }
            .toSet()

        // ---- 7) 还原策略阶梯（§3.3）
        val strategy = RestoreLadder.choose(
            family = family,
            usableBackups = backups.values.any { it.state == BackupState.USABLE },
            ksudExecutable = ksud?.executable == true,
            magiskbootExecutable = magiskboot?.executable == true,
        )

        // ---- 8) 已改分区与"干净镜像可得"分区
        val patchedPartitions = present
            .filter { it.markers.isNotEmpty() }
            .map { it.name }
            .toSet()
        val cleanImagePartitions = when (strategy) {
            RestoreStrategy.KSU_STOCK_COPY, RestoreStrategy.MAGISK_STOCK_GZ -> usableBackupPartitions
            RestoreStrategy.KSU_EXTERNAL_TOOL_ON_COPY, RestoreStrategy.MAGISK_INTERNAL_REBUILD -> patchedPartitions
            else -> emptySet()
        }

        val supported = family.isKnown
        val bestEffort = !supported || strategy == RestoreStrategy.GENERIC_STOCK_SCAN || strategy == RestoreStrategy.NONE
        val dataDirs = probe.files.filter { (path, fact) ->
            fact.exists && MigrationPaths.PROBE_PATHS.contains(path) && path != "/cache" && path != "/data/local/tmp"
        }
        val blockers = buildList {
            if (!probe.device.layoutSupported) add(Blocker.LayoutUnsupported)
            if (probe.device.pendingTransaction) add(Blocker.PendingTransaction)
            if (probe.ownControlPlanePresent) add(Blocker.OwnManagerInstalled)
        }
        return ExternalRootInfo(
            family = family,
            version = version,
            managerPackages = managerPackages,
            uapi = uapi,
            patchedPartitions = patchedPartitions,
            stockBackups = backups,
            restoreStrategy = strategy,
            supported = supported,
            bestEffort = bestEffort,
            blockers = blockers,
            evidence = evidence,
            dataDirs = dataDirs,
            tools = probe.tools,
            allowlist = probe.allowlist,
            magiskDb = probe.magiskDb,
            cleanImagePartitions = cleanImagePartitions,
        )
    }

    /**
     * 顶层可用性判定（05 §1.9）。空间、root、pending、布局是硬阻断；
     * 未知管理器**不阻断**（D16）；已知家族缺干净镜像阻断（本册 C11 立场）。
     */
    fun eligibility(
        info: ExternalRootInfo,
        device: DeviceFacts,
        space: SpaceEstimate,
        baselinePartitions: Set<String> = emptySet(),
    ): MigrationEligibility {
        val blockers = mutableListOf<Blocker>()
        if (!device.externalRootAvailable) blockers += Blocker.NoRoot
        if (!space.adequate) blockers += Blocker.NoSpace(space.requiredBytes, space.freeBytesOnData)
        if (device.pendingTransaction) blockers += Blocker.PendingTransaction
        if (!device.layoutSupported) blockers += Blocker.LayoutUnsupported
        if (info.family == Family.UNKNOWN && info.blockers.any { it is Blocker.OwnManagerInstalled }) {
            blockers += Blocker.OwnManagerInstalled
        }
        if (info.blockers.any { it is Blocker.OwnManagerInstalled }) blockers += Blocker.OwnManagerInstalled

        // 每个目标模式：restoreSet 与 targetSet 的差集需要干净镜像；相交分区需要 producer 输入。
        val targetAllowed = LinkedHashMap<WorkMode, Boolean>()
        for (target in WorkMode.entries) {
            val targetSet = target.targetPartitions
            val needClean = (info.patchedPartitions - targetSet) +
                (info.patchedPartitions intersect targetSet)
            val missing = needClean - info.cleanImagePartitions - baselinePartitions
            val knownFamilyBlocked = info.family.isKnown && missing.isNotEmpty()
            if (knownFamilyBlocked) blockers += Blocker.CleanImageUnavailable(missing.sorted())
            targetAllowed[target] = !knownFamilyBlocked
        }

        val preferredTarget = if (targetAllowed[WorkMode.LITE] == true) WorkMode.LITE else null
        val recommendedReason = when (preferredTarget) {
            WorkMode.LITE -> TARGET_REASON_LITE
            null -> null
        }
        val hardBlocked = blockers.any { it !is Blocker.CleanImageUnavailable }
        val enabled = !hardBlocked && preferredTarget != null && info.blockers.none {
            it is Blocker.OwnManagerInstalled
        }
        return MigrationEligibility(
            enabled = enabled,
            blockers = blockers.distinctBy { it.code },
            recommendedTarget = preferredTarget,
            recommendedReason = recommendedReason,
            space = space,
            targetAllowed = targetAllowed,
        )
    }
}

/** 还原策略阶梯（05 §3.3 / §1.5 第 7 步）。 */
object RestoreLadder {
    fun choose(
        family: Family,
        usableBackups: Boolean,
        ksudExecutable: Boolean,
        magiskbootExecutable: Boolean,
    ): RestoreStrategy = when {
        family.isKsuFamily && usableBackups -> RestoreStrategy.KSU_STOCK_COPY
        family == Family.MAGISK && usableBackups -> RestoreStrategy.MAGISK_STOCK_GZ
        family.isKsuFamily && ksudExecutable -> RestoreStrategy.KSU_EXTERNAL_TOOL_ON_COPY
        family == Family.MAGISK && magiskbootExecutable -> RestoreStrategy.MAGISK_INTERNAL_REBUILD
        else -> RestoreStrategy.GENERIC_STOCK_SCAN
    }
}

/** stock 备份可用性判据（05 §1.7）。 */
object StockBackupRules {
    /** USABLE 的硬条件：存在、可读、大小合法、摘要可算。`partitionSize <= 0` 表示无法核长。 */
    fun stateFor(
        exists: Boolean,
        readable: Boolean,
        size: Long,
        partitionSize: Long,
        sha256: String?,
    ): BackupState = when {
        !exists -> BackupState.MISSING
        !readable -> BackupState.UNREADABLE
        size <= 0 || (partitionSize > 0 && size != partitionSize) -> BackupState.SIZE_MISMATCH
        sha256 == null || !MigrationHashes.isHash(sha256) -> BackupState.CORRUPT
        else -> BackupState.USABLE
    }

    /** 尝试按文件名推断分区：KSU 备份文件名只带 sha1，需由 ramdisk 索引给出；此处仅启发式。 */
    fun partitionHintFor(path: String, bootSize: Long, initBootSize: Long): String? = null

    /** KSU 备份文件名 = "ksu_backup_<sha1>"（KSU/defs.rs:72-73）。 */
    fun isKsuBackupName(name: String): Boolean =
        name.startsWith(MigrationPaths.KSU_BACKUP_PREFIX) &&
            name.removePrefix(MigrationPaths.KSU_BACKUP_PREFIX).matches(Regex("[0-9a-f]{40}"))

    /** Magisk 备份目录（util_functions.sh:569-573）。 */
    fun isMagiskBackupPath(path: String): Boolean =
        path.startsWith(MigrationPaths.MAGISK_BACKUP_PREFIX) ||
            path.substringAfterLast('/').startsWith("stock_boot_")
}

/** 服务侧只读探针端口（Android 实现由安装器服务提供；主机测试用 fake）。 */
fun interface ExternalRootProbeReader {
    fun read(): ExternalRootProbe
}

/** 不可用探针：绝不伪造证据（UNKNOWN ≠ 负面证据）。 */
object UnavailableExternalRootProbeReader : ExternalRootProbeReader {
    override fun read(): ExternalRootProbe = ExternalRootProbe(suUid = -1, device = DeviceFacts(externalRootAvailable = false))
}
