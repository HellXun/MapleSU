package dev.maplesu.manager.workmode.migration

import dev.maplesu.manager.workmode.Hashes
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.control.schema.GrantIntent
import dev.maplesu.manager.workmode.control.schema.PendingOp
import dev.maplesu.manager.workmode.detect.Clock
import dev.maplesu.manager.workmode.detect.WallClock

/**
 * 超级用户授权意图迁移（05 §5.5）。
 *
 * 原则：**不静默转换、不写 live 原始文件**。
 * 1. 源侧只读解析（KSU `.allowlist` 字节级 / Magisk `policies` 行）；
 * 2. uid → 包名+签名+安装时间 身份核验；
 * 3. 通过者写成 `GrantIntent` 意图（`pending=true`），目标后端可用后 apply（M9）。
 */
object KsuAllowlistReader {
    const val MAGIC = 0x7f4b5355
    const val SUPPORTED_VERSION = 4
    const val RECORD_SIZE_V4 = 784
    const val RECORD_SIZE_PRE_V4 = 776
    private const val MAX_KEY = 256
    private const val MAX_RECORDS = 4096

    sealed interface ParseResult {
        data class Parsed(val records: List<KsuProfile>, val recordSize: Int) : ParseResult
        data class Unsupported(val reason: String) : ParseResult
    }

    data class KsuProfile(val version: Int, val key: String, val currUid: Int, val allowSu: Boolean)

    /**
     * 自校验式解析：magic/version/长度整除 + 逐条字段合法性，任一失败整份 `Unsupported`
     * （**绝不"猜一半"**；主文档:1246 "版本未知时不猜格式"）。
     */
    fun parse(bytes: ByteArray): ParseResult {
        if (bytes.size < 8) return ParseResult.Unsupported(MigrationCodes.ALLOWLIST_UNSUPPORTED)
        val magic = readU32(bytes, 0)
        if (magic != MAGIC.toLong()) return ParseResult.Unsupported(MigrationCodes.ALLOWLIST_UNSUPPORTED)
        val headerVersion = readU32(bytes, 4)
        if (headerVersion < 2L || headerVersion > SUPPORTED_VERSION.toLong()) {
            return ParseResult.Unsupported(MigrationCodes.ALLOWLIST_UNSUPPORTED)
        }
        val version = headerVersion.toInt()
        val recordSize = if (version == SUPPORTED_VERSION) RECORD_SIZE_V4 else RECORD_SIZE_PRE_V4
        val payload = bytes.size - 8
        if (payload % recordSize != 0) return ParseResult.Unsupported(MigrationCodes.ALLOWLIST_UNSUPPORTED)
        val count = payload / recordSize
        if (count > MAX_RECORDS) return ParseResult.Unsupported(MigrationCodes.ALLOWLIST_UNSUPPORTED)
        val records = ArrayList<KsuProfile>(count)
        for (index in 0 until count) {
            val base = 8 + index * recordSize
            val recordVersion = readU32(bytes, base).toInt()
            if (recordVersion < 2 || recordVersion > SUPPORTED_VERSION) {
                return ParseResult.Unsupported(MigrationCodes.ALLOWLIST_UNSUPPORTED)
            }
            val key = readKey(bytes, base + 4)
                ?: return ParseResult.Unsupported(MigrationCodes.ALLOWLIST_UNSUPPORTED)
            if (!key.matches(Regex("[A-Za-z0-9._-]+"))) {
                return ParseResult.Unsupported(MigrationCodes.ALLOWLIST_UNSUPPORTED)
            }
            val uid = readS32(bytes, base + 260)
            if (uid < 0 || uid > 1_000_000) {
                return ParseResult.Unsupported(MigrationCodes.ALLOWLIST_UNSUPPORTED)
            }
            val allow = bytes[base + 264].toInt() and 0xff
            if (allow != 0 && allow != 1) {
                return ParseResult.Unsupported(MigrationCodes.ALLOWLIST_UNSUPPORTED)
            }
            records += KsuProfile(recordVersion, key, uid, allow == 1)
        }
        return ParseResult.Parsed(records, recordSize)
    }

    private fun readKey(bytes: ByteArray, offset: Int): String? {
        val end = offset + MAX_KEY
        if (end > bytes.size) return null
        var length = 0
        while (length < MAX_KEY) {
            val value = bytes[offset + length].toInt() and 0xff
            if (value == 0) break
            if (value < 0x20 || value > 0x7e) return null
            length++
        }
        if (length == 0 || length == MAX_KEY) return null
        return String(bytes, offset, length, Charsets.US_ASCII)
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun readS32(bytes: ByteArray, offset: Int): Int = readU32(bytes, offset).toInt()
}

/** Magisk `policies(uid, policy, until, logging, notification)`（sqlite.cpp:203-207）。 */
object MagiskPolicyReader {
    /** SuPolicy 判别值（`.research/Magisk-v30.7/native/src/core/lib.rs:100-105`）。 */
    const val QUERY = 0
    const val DENY = 1
    const val ALLOW = 2
    const val RESTRICT = 3

    data class Row(val uid: Int, val policy: Int, val untilEpochSeconds: Long = 0)

    /** 把源 `policy` 整数映射为意图；Query/Restrict/过期 → null（不猜）。 */
    fun decision(row: Row, nowEpochSeconds: Long): AllowDecision? {
        if (row.untilEpochSeconds != 0L && row.untilEpochSeconds <= nowEpochSeconds) return null
        return when (row.policy) {
            ALLOW -> AllowDecision.ALLOW
            DENY -> AllowDecision.DENY
            else -> null
        }
    }
}

data class AuthImportPlan(
    val source: AuthSource,
    /** canonical：按 (uid, packageName) 排序。 */
    val intents: List<GrantIntent>,
    val stale: List<AuthStale>,
    /** 未支持/未导入的整份原因（例如 allowlist 版本不支持）。 */
    val unsupportedReason: String? = null,
    val pendingBackend: String? = null,
) {
    val pendingCount: Int get() = intents.count { it.pending != null }
}

/**
 * 授权意图迁移器。输入是只读源快照（allowlist bytes / policies rows）+ 已采集的身份目录；
 * 输出是 `GrantIntent`（05 §5.5.2），apply 由超级用户层在 M9 执行。
 */
class SuperuserMigrator(private val clock: Clock = WallClock) {

    fun plan(
        migrationId: String,
        target: WorkMode,
        source: AuthSource,
        inventory: PackageInventory,
        allowlistBytes: ByteArray? = null,
        magiskRows: List<MagiskPolicyReader.Row> = emptyList(),
        nowEpochSeconds: Long = clock.nowMillis() / 1000,
        managerUids: Set<Int> = emptySet(),
    ): AuthImportPlan {
        val backend = backendFor(target)
        return when (source) {
            AuthSource.KSU_ALLOWLIST -> fromKsuAllowlist(
                migrationId, backend, inventory, allowlistBytes ?: ByteArray(0), managerUids,
            )
            AuthSource.MAGISK_DB -> fromMagiskRows(
                migrationId, backend, inventory, magiskRows, nowEpochSeconds, managerUids,
            )
            AuthSource.NONE -> AuthImportPlan(AuthSource.NONE, emptyList(), emptyList(), pendingBackend = backend)
        }
    }

    private fun fromKsuAllowlist(
        migrationId: String,
        backend: String,
        inventory: PackageInventory,
        bytes: ByteArray,
        managerUids: Set<Int>,
    ): AuthImportPlan {
        val parsed = KsuAllowlistReader.parse(bytes)
        return when (parsed) {
            is KsuAllowlistReader.ParseResult.Unsupported -> AuthImportPlan(
                source = AuthSource.KSU_ALLOWLIST,
                intents = emptyList(),
                stale = emptyList(),
                unsupportedReason = parsed.reason,
                pendingBackend = backend,
            )
            is KsuAllowlistReader.ParseResult.Parsed -> {
                val intents = mutableListOf<GrantIntent>()
                val stale = mutableListOf<AuthStale>()
                for (profile in parsed.records) {
                    val decision = if (profile.allowSu) AllowDecision.ALLOW else AllowDecision.DENY
                    val verdict = IdentityRules.verify(
                        profile.currUid, profile.key, decision, inventory, managerUids,
                    )
                    when (verdict) {
                        is IdentityVerdict.Accepted -> intents += intent(
                            migrationId, backend, verdict.intent, verdict.facts,
                        )
                        is IdentityVerdict.Stale -> stale += verdict.stale
                    }
                }
                return AuthImportPlan(
                    source = AuthSource.KSU_ALLOWLIST,
                    intents = intents.distinctBy { it.uid to it.packageName }
                        .sortedWith(compareBy({ it.uid }, { it.packageName })),
                    stale = stale.distinctBy { it.uid to it.packageName }.sortedBy { it.uid },
                    pendingBackend = backend,
                )
            }
        }
    }

    private fun fromMagiskRows(
        migrationId: String,
        backend: String,
        inventory: PackageInventory,
        rows: List<MagiskPolicyReader.Row>,
        nowEpochSeconds: Long,
        managerUids: Set<Int>,
    ): AuthImportPlan {
        val intents = mutableListOf<GrantIntent>()
        val stale = mutableListOf<AuthStale>()
        for (row in rows) {
            val decision = MagiskPolicyReader.decision(row, nowEpochSeconds)
            if (decision == null) {
                stale += AuthStale(row.uid, null, "POLICY_NOT_DECIDED")
                continue
            }
            val facts = inventory.byUid(row.uid)
            val packageName = facts?.packages?.singleOrNull()
            if (packageName == null) {
                stale += AuthStale(row.uid, null, if (facts == null) "UID_NOT_INSTALLED" else "SHARED_UID")
                continue
            }
            val verdict = IdentityRules.verify(row.uid, packageName, decision, inventory, managerUids)
            when (verdict) {
                is IdentityVerdict.Accepted -> intents += intent(migrationId, backend, verdict.intent, verdict.facts)
                is IdentityVerdict.Stale -> stale += verdict.stale
            }
        }
        return AuthImportPlan(
            source = AuthSource.MAGISK_DB,
            intents = intents.distinctBy { it.uid to it.packageName }
                .sortedWith(compareBy({ it.uid }, { it.packageName })),
            stale = stale.distinctBy { it.uid }.sortedBy { it.uid },
            pendingBackend = backend,
        )
    }

    private fun backendFor(target: WorkMode): String = when (target) {
        WorkMode.LITE -> "A_DIRECT"
    }

    private fun intent(
        migrationId: String,
        backend: String,
        decision: AllowDecision,
        facts: PackageFacts,
    ): GrantIntent {
        val now = clock.nowMillis()
        return GrantIntent(
            uid = facts.uid,
            userId = facts.uid / 100_000,
            packageName = facts.packages.single(),
            signerSha256 = facts.signerSha256.sorted(),
            firstInstallTime = facts.firstInstallTime,
            lastUpdateTime = facts.lastUpdateTime,
            apkPathHash = Hashes.shortSha256(facts.apkPath, 32),
            decision = if (decision == AllowDecision.ALLOW) GrantIntent.ALLOW else GrantIntent.DENY,
            origin = "MIGRATION:$migrationId",
            decidedAt = now,
            applied = emptyList(),
            pending = if (decision == AllowDecision.ALLOW) {
                PendingOp(backend = backend, op = "grant", attempts = 0, lastError = "", since = now)
            } else {
                null
            },
            identityStale = false,
            degraded = emptyList(),
        )
    }
}

sealed interface IdentityVerdict {
    data class Accepted(val intent: AllowDecision, val facts: PackageFacts) : IdentityVerdict
    data class Stale(val stale: AuthStale) : IdentityVerdict
}

/**
 * 身份核验（05 §5.5.2）：uid → 唯一包 + 签名 64-hex + 安装时间有效 + apk 绝对路径。
 * 任一不符**不自动授权**（主文档:1251）。
 */
object IdentityRules {
    @Suppress("UNUSED_PARAMETER")
    fun verify(
        uid: Int,
        packageName: String,
        decision: AllowDecision,
        inventory: PackageInventory,
        managerUids: Set<Int> = emptySet(),
    ): IdentityVerdict {
        if (uid in managerUids) {
            return IdentityVerdict.Stale(AuthStale(uid, packageName, "MANAGER_UID"))
        }
        if (uid == 0) {
            // uid 0（root）不是应用授权对象，源里有也丢弃。
            return IdentityVerdict.Stale(AuthStale(uid, packageName, "ROOT_UID"))
        }
        val facts = inventory.byUid(uid)
            ?: return IdentityVerdict.Stale(AuthStale(uid, packageName, "UID_NOT_INSTALLED"))
        if (facts.packages.size != 1) {
            return IdentityVerdict.Stale(AuthStale(uid, packageName, "SHARED_UID"))
        }
        if (facts.packages.single() != packageName) {
            return IdentityVerdict.Stale(AuthStale(uid, packageName, "PACKAGE_MISMATCH"))
        }
        if (facts.signerSha256.isEmpty() || facts.signerSha256.any { !it.matches(Regex("[0-9a-f]{64}")) }) {
            return IdentityVerdict.Stale(AuthStale(uid, packageName, "SIGNER_INVALID"))
        }
        if (facts.firstInstallTime <= 0 || facts.lastUpdateTime < facts.firstInstallTime) {
            return IdentityVerdict.Stale(AuthStale(uid, packageName, "INSTALL_TIME_INVALID"))
        }
        if (!facts.apkPath.startsWith("/")) {
            return IdentityVerdict.Stale(AuthStale(uid, packageName, "APK_PATH_INVALID"))
        }
        return IdentityVerdict.Accepted(decision, facts)
    }
}
