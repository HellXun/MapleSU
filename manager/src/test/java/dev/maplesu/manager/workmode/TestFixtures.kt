package dev.maplesu.manager.workmode

import dev.maplesu.manager.workmode.control.AtomicControlFile
import dev.maplesu.manager.workmode.control.ControlJsonCodec
import dev.maplesu.manager.workmode.control.schema.AppSnapshotFacts
import dev.maplesu.manager.workmode.control.schema.BootFacts
import dev.maplesu.manager.workmode.control.schema.GrantIntent
import dev.maplesu.manager.workmode.control.schema.InstalledFile
import dev.maplesu.manager.workmode.control.schema.KernelFacts
import dev.maplesu.manager.workmode.control.schema.ManagerFacts
import dev.maplesu.manager.workmode.control.schema.ModeEntry
import dev.maplesu.manager.workmode.control.schema.ModesFile
import dev.maplesu.manager.workmode.control.schema.PathOwner
import dev.maplesu.manager.workmode.control.schema.PublishFile
import dev.maplesu.manager.workmode.control.schema.SelinuxFile
import dev.maplesu.manager.workmode.control.schema.SharedPolicy
import dev.maplesu.manager.workmode.control.schema.SharedSuperuserFile
import dev.maplesu.manager.workmode.control.schema.Transports
import dev.maplesu.manager.workmode.detect.DetectionResult
import dev.maplesu.manager.workmode.detect.DetectionRules
import dev.maplesu.manager.workmode.detect.KsuFacts
import dev.maplesu.manager.workmode.detect.LiteFacts
import dev.maplesu.manager.workmode.detect.LocalFacts
import dev.maplesu.manager.workmode.detect.ModeState
import dev.maplesu.manager.workmode.detect.UltraState
import dev.maplesu.manager.workmode.detect.RecoveryReadiness
import dev.maplesu.manager.workmode.detect.group
import dev.maplesu.manager.workmode.detect.RootChannelKind
import dev.maplesu.manager.workmode.detect.RootFacts
import dev.maplesu.manager.workmode.switch.AvbFacts
import dev.maplesu.manager.workmode.switch.BaselineManifest
import dev.maplesu.manager.workmode.switch.BaselinePartition
import dev.maplesu.manager.workmode.switch.PatchMarkers
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** 主机测试共用 fixture（纯数据，无 Android 依赖）。 */
internal object TestFixtures {
    val HASH_64: String = "a".repeat(64)
    val ZERO_64: String = "0".repeat(64)

    fun hash(char: Char): String = char.toString().repeat(64)

    fun localFacts(
        bootId: String = "boot-1",
        ageMs: Long = Long.MAX_VALUE,
        lastKnownMode: String? = null,
        installerLastTaskId: String? = null,
        freshInstallWindowMs: Long = 5 * 60 * 1000L,
    ) = LocalFacts(
        bootId = bootId,
        managerFirstInstallAgeMs = ageMs,
        lastKnownMode = lastKnownMode,
        installerLastTaskId = installerLastTaskId,
        freshInstallWindowMs = freshInstallWindowMs,
    )

    fun ksuFacts(
        responded: Boolean = false,
        version: Int = -1,
        uapi: Int = -1,
        isManager: Boolean = false,
        isLkm: Boolean = false,
    ) = KsuFacts(responded, version, uapi, isManager, isLkm)

    fun liteFacts(
        credentialPresent: Boolean = false,
        handshakeOk: Boolean? = null,
        ultraState: UltraState = UltraState.UNKNOWN,
        ultraKeyPresent: Boolean = false,
    ) = LiteFacts(credentialPresent, handshakeOk, ultraState, ultraKeyPresent)

    fun rootFacts(
        channel: RootChannelKind = RootChannelKind.KSU_NATIVE,
        modesJson: ModesFile? = null,
        installedJson: InstalledFile? = null,
        switchHead: dev.maplesu.manager.workmode.control.schema.SwitchHeadView? = null,
        aAuthorizationTable: Boolean? = null,
        aInstallerRecord: Boolean? = null,
        aPublishedDirectory: Boolean? = null,
        ksuDirHasContent: Boolean? = null,
        ksudExecutable: Boolean? = null,
        foreignManagerMarkers: Boolean? = null,
    ) = RootFacts(
        channel = channel,
        modesJson = modesJson,
        installedJson = installedJson,
        switchHead = switchHead,
        aAuthorizationTable = aAuthorizationTable,
        aInstallerRecord = aInstallerRecord,
        aPublishedDirectory = aPublishedDirectory,
        ksuDirHasContent = ksuDirHasContent,
        ksudExecutable = ksudExecutable,
        foreignManagerMarkers = foreignManagerMarkers,
    )

    fun detection(state: ModeState, bootId: String = "boot-1"): DetectionResult = DetectionResult(
        state = state,
        group = state.group,
        rule = when (state) {
            ModeState.MODE_B_ACTIVE -> DetectionRules.Rule.R1_KSU_ACTIVE
            ModeState.MODE_B_KERNEL_ONLY -> DetectionRules.Rule.R2_KSU_KERNEL_ONLY
            ModeState.MODE_A_ACTIVE -> DetectionRules.Rule.R5_A_HANDSHAKE
            ModeState.MODE_A_INSTALLED_UNBOUND -> DetectionRules.Rule.R8_A_DISK_STRONG_UNBOUND
            ModeState.FOREIGN_SU -> DetectionRules.Rule.R4_FOREIGN_KSU
            ModeState.NOT_INSTALLED -> DetectionRules.Rule.R11_FRESH_INSTALL
            ModeState.INDETERMINATE -> DetectionRules.Rule.R12_INDETERMINATE
        },
        bootId = bootId,
        observedAtMillis = 1_000L,
        trace = emptyList(),
        recovery = RecoveryReadiness.NONE,
        rootChannel = null,
    )

    fun modeEntry(
        suffix: String,
        settingsRevision: Long = 1,
        syncState: String = "ok",
        snapshot: dev.maplesu.manager.workmode.control.schema.SnapshotRef? = null,
        dataRoot: String = if (suffix == "A") "/data/adb/maplesu" else "/data/adb/ksu",
    ) = ModeEntry(
        suffix = suffix,
        implementation = if (suffix == "A") "maplesu-ultra" else "maplesu-ksu-uapi7",
        settingsRevision = settingsRevision,
        superuserSyncState = syncState,
        lastActiveBootId = null,
        lastActiveAt = null,
        snapshot = snapshot,
        dataRoot = dataRoot,
    )

    fun modesFile(
        schema: Int = 1,
        revision: Long = 1,
        current: String = "A",
        last: String? = null,
        sharedRevision: Long = 1,
        owners: List<PathOwner> = emptyList(),
        modes: List<ModeEntry> = listOf(modeEntry("A"), modeEntry("B")),
        updatedAt: Long = 1_000L,
    ) = ModesFile(
        schema = schema,
        revision = revision,
        updatedAt = updatedAt,
        bootId = "boot-1",
        manager = ManagerFacts(
            uid = 10_100,
            packageName = "dev.maplesu.manager",
            apkSha256 = HASH_64,
            signerSha256 = listOf(hash('b'), hash('a')),
            versionCode = 1,
        ),
        current = current,
        last = last,
        pending = null,
        sharedRevision = sharedRevision,
        owners = owners,
        modes = modes,
        boot = BootFacts(
            slot = "_a",
            bootSha256 = HASH_64,
            initBootSha256 = HASH_64,
            activeMode = current,
            baselineId = "bl-1",
            verifiedBootId = "boot-1",
            kernelRelease = "5.10.101",
            firmware = "fw-1",
        ),
        migration = null,
    )

    fun grantIntent(
        uid: Int = 10_200,
        userId: Int = 0,
        packageName: String = "com.example.app",
        decision: String = GrantIntent.ALLOW,
        decidedAt: Long = 1_000L,
        signers: List<String> = listOf(hash('c'), hash('b')),
        applied: List<dev.maplesu.manager.workmode.control.schema.AppliedMark> = emptyList(),
        identityStale: Boolean = false,
    ) = GrantIntent(
        uid = uid,
        userId = userId,
        packageName = packageName,
        signerSha256 = signers,
        firstInstallTime = 1,
        lastUpdateTime = 2,
        apkPathHash = hash('d').take(32),
        decision = decision,
        origin = "A",
        decidedAt = decidedAt,
        applied = applied,
        pending = null,
        identityStale = identityStale,
        degraded = emptyList(),
    )

    fun sharedSuperuser(
        revision: Long = 1,
        entries: List<GrantIntent> = listOf(grantIntent()),
        tombs: List<dev.maplesu.manager.workmode.control.schema.Tomb> = emptyList(),
    ) = SharedSuperuserFile(
        revision = revision,
        updatedAt = 1_000L,
        lastWriter = "A",
        entries = entries,
        tombs = tombs,
        policy = SharedPolicy(),
    )

    fun installedFile() = InstalledFile(
        schema = 1,
        updatedAt = 1_000L,
        bootId = "boot-1",
        managerUid = 10_100,
        packageName = "dev.maplesu.manager",
        apkSha256 = HASH_64,
        signerSha256 = listOf(hash('a')),
        transports = Transports(a1 = true, ultra = false),
        kernel = KernelFacts(liteMarker = null, liteCredentialSha256 = null, ksuUapi = null, ksuVersion = null),
        writtenBy = "A_DIRECT",
    )

    fun selinuxFile(policyWritten: Boolean = false) = SelinuxFile(
        schema = 1,
        updatedAt = 1_000L,
        bootId = "boot-1",
        entries = emptyList(),
        policyWritten = policyWritten,
    )

    fun publishFile() = PublishFile(
        schema = 1,
        updatedAt = 1_000L,
        bootId = "boot-1",
        current = "0123456789abcdef",
        previous = emptyList(),
        authorizedSha256 = HASH_64,
        entries = emptyList(),
    )

    fun appSnapshotFacts() = AppSnapshotFacts(
        packageName = "dev.maplesu.manager",
        versionCode = 1,
        apkSha256 = HASH_64,
        signerSha256 = listOf(hash('a')),
        settingsRevision = 3,
        appPrefsRevision = 3,
    )

    fun baselineManifest(
        id: String = "bl-1",
        slot: String = "_a",
        firmware: String = "fw-1",
        kernelRelease: String = "5.10.101",
        provenance: String = dev.maplesu.manager.workmode.switch.BaselineProvenance.OEM_PACKAGE,
        clean: Boolean = true,
        markers: PatchMarkers = PatchMarkers(),
        bootSha: String = hash('e'),
        initBootSha: String = hash('f'),
        avbTailPreserved: Boolean = true,
        immutable: Boolean = true,
    ) = BaselineManifest(
        schema = BaselineManifest.SCHEMA,
        id = id,
        createdAt = 1_000L,
        provenance = provenance,
        clean = clean,
        verifiedAt = 1_000L,
        slot = slot,
        firmware = firmware,
        kernelRelease = kernelRelease,
        partitions = listOf(
            BaselinePartition("boot", 100_000_000L, bootSha, avbTailPreserved),
            BaselinePartition("init_boot", 8_000_000L, initBootSha, avbTailPreserved),
        ),
        avb = AvbFacts(vbmetaPresent = true, vbmetaSha256 = hash('1'), verificationDisabled = false),
        patchMarkers = markers,
        immutable = immutable,
    )

    fun writeJsonFile(file: File, value: kotlinx.serialization.json.JsonElement) {
        file.parentFile?.mkdirs()
        AtomicControlFile.writeJson(file, value.toString())
    }

    inline fun <reified T> writeControlJson(file: File, value: T) {
        file.parentFile?.mkdirs()
        AtomicControlFile.writeJson(file, ControlJsonCodec.encode(value))
    }

    fun tempDir(prefix: String): Path = Files.createTempDirectory("maplesu-$prefix")
}

internal fun Path.file(relative: String = ""): File =
    if (relative.isEmpty()) toFile() else resolve(relative).toFile()
