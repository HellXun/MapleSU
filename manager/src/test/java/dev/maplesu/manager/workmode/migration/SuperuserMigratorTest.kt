package dev.maplesu.manager.workmode.migration

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.control.schema.GrantIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 05 §5.5：allowlist 解析 / 身份核验 / 意图 pending（T-11…T-14）。 */
class SuperuserMigratorTest {

    private val fixedNow = 1_793_260_800_000L
    private val migrator = SuperuserMigrator(clock = { fixedNow })

    private fun allowlist(
        version: Int = 4,
        records: List<Triple<String, Int, Boolean>>,
        magic: Long = 0x7f4b5355L,
    ): ByteArray {
        val recordSize = if (version == 4) KsuAllowlistReader.RECORD_SIZE_V4 else KsuAllowlistReader.RECORD_SIZE_PRE_V4
        val buffer = ByteBuffer.allocate(8 + recordSize * records.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(magic.toInt())
        buffer.putInt(version)
        records.forEach { (key, uid, allow) ->
            val base = buffer.position()
            buffer.putInt(4) // record.version
            val keyBytes = key.toByteArray(Charsets.US_ASCII)
            buffer.position(base + 4)
            buffer.put(keyBytes)
            buffer.position(base + 260)
            buffer.putInt(uid)
            buffer.position(base + 264)
            buffer.put(if (allow) 1 else 0)
            buffer.position(base + recordSize)
        }
        return buffer.array()
    }

    private fun inventory(vararg facts: PackageFacts) = PackageInventory(facts.toList())

    @Test
    fun parsesVersion4AllowlistRecords() {
        val bytes = allowlist(
            records = listOf(
                Triple("com.example.a", 10123, true),
                Triple("com.example.b", 10124, false),
                Triple("com.example.c", 10125, true),
            ),
        )
        val result = KsuAllowlistReader.parse(bytes)
        assertTrue(result is KsuAllowlistReader.ParseResult.Parsed)
        val parsed = result as KsuAllowlistReader.ParseResult.Parsed
        assertEquals(3, parsed.records.size)
        assertEquals(784, parsed.recordSize)
        assertEquals("com.example.a", parsed.records[0].key)
        assertFalse(parsed.records[1].allowSu)
    }

    @Test
    fun rejectsBadMagicVersionAndLength() {
        assertTrue(
            KsuAllowlistReader.parse(allowlist(magic = 0x12345678L, records = listOf(Triple("com.example.a", 10123, true))))
                is KsuAllowlistReader.ParseResult.Unsupported,
        )
        assertTrue(
            KsuAllowlistReader.parse(allowlist(version = 5, records = listOf(Triple("com.example.a", 10123, true))))
                is KsuAllowlistReader.ParseResult.Unsupported,
        )
        val truncated = allowlist(records = listOf(Triple("com.example.a", 10123, true))).copyOf(100)
        assertTrue(KsuAllowlistReader.parse(truncated) is KsuAllowlistReader.ParseResult.Unsupported)
    }

    @Test
    fun allowRecordBecomesPendingIntentForLite() {
        val bytes = allowlist(records = listOf(Triple("com.example.a", 10123, true)))
        val plan = migrator.plan(
            Fixtures.MIGRATION_ID,
            WorkMode.LITE,
            AuthSource.KSU_ALLOWLIST,
            inventory(Fixtures.packageFacts(10123, "com.example.a")),
            allowlistBytes = bytes,
        )
        val intent = plan.intents.single()
        assertEquals(GrantIntent.ALLOW, intent.decision)
        assertEquals("com.example.a", intent.packageName)
        assertEquals("A_DIRECT", plan.pendingBackend)
        assertNotNull(intent.pending)
        assertEquals("grant", intent.pending!!.op)
        assertEquals("MIGRATION:${Fixtures.MIGRATION_ID}", intent.origin)
        assertFalse(intent.identityStale)
        assertEquals(1, plan.pendingCount)
    }

    @Test
    fun sharedUidIsStaleNotGranted() {
        val bytes = allowlist(records = listOf(Triple("com.example.a", 10123, true)))
        val plan = migrator.plan(
            Fixtures.MIGRATION_ID,
            WorkMode.LITE,
            AuthSource.KSU_ALLOWLIST,
            inventory(Fixtures.packageFacts(10123, "com.example.a", "com.example.a2")),
            allowlistBytes = bytes,
        )
        assertTrue(plan.intents.isEmpty())
        assertEquals("SHARED_UID", plan.stale.single().reason)
    }

    @Test
    fun packageMismatchAndUnknownUidAreStale() {
        val bytes = allowlist(
            records = listOf(
                Triple("com.example.other", 10123, true),
                Triple("com.example.missing", 10199, true),
            ),
        )
        val plan = migrator.plan(
            Fixtures.MIGRATION_ID,
            WorkMode.LITE,
            AuthSource.KSU_ALLOWLIST,
            inventory(Fixtures.packageFacts(10123, "com.example.a")),
            allowlistBytes = bytes,
        )
        assertTrue(plan.intents.isEmpty())
        assertTrue(plan.stale.any { it.reason == "PACKAGE_MISMATCH" })
        assertTrue(plan.stale.any { it.reason == "UID_NOT_INSTALLED" })
    }

    @Test
    fun managerUidIsDropped() {
        val bytes = allowlist(records = listOf(Triple("com.example.mgr", 10155, true)))
        val plan = migrator.plan(
            Fixtures.MIGRATION_ID,
            WorkMode.LITE,
            AuthSource.KSU_ALLOWLIST,
            inventory(Fixtures.packageFacts(10155, "com.example.mgr")),
            allowlistBytes = bytes,
            managerUids = setOf(10155),
        )
        assertTrue(plan.intents.isEmpty())
        assertEquals("MANAGER_UID", plan.stale.single().reason)
    }

    @Test
    fun invalidSignerOrInstallTimesAreStale() {
        val bytes = allowlist(records = listOf(Triple("com.example.a", 10123, true)))
        val badSigner = migrator.plan(
            Fixtures.MIGRATION_ID,
            WorkMode.LITE,
            AuthSource.KSU_ALLOWLIST,
            inventory(Fixtures.packageFacts(10123, "com.example.a", signer = "not-hex")),
            allowlistBytes = bytes,
        )
        assertEquals("SIGNER_INVALID", badSigner.stale.single().reason)
        val badTimes = migrator.plan(
            Fixtures.MIGRATION_ID,
            WorkMode.LITE,
            AuthSource.KSU_ALLOWLIST,
            inventory(
                Fixtures.packageFacts(
                    10123, "com.example.a",
                    firstInstallTime = 2_000, lastUpdateTime = 1_000,
                ),
            ),
            allowlistBytes = bytes,
        )
        assertEquals("INSTALL_TIME_INVALID", badTimes.stale.single().reason)
    }

    @Test
    fun unsupportedAllowlistIsReportedNotGuessed() {
        val plan = migrator.plan(
            Fixtures.MIGRATION_ID,
            WorkMode.LITE,
            AuthSource.KSU_ALLOWLIST,
            inventory(Fixtures.packageFacts(10123, "com.example.a")),
            allowlistBytes = allowlist(version = 3, records = emptyList()).copyOf(9),
        )
        assertTrue(plan.intents.isEmpty())
        assertEquals(MigrationCodes.ALLOWLIST_UNSUPPORTED, plan.unsupportedReason)
    }

    @Test
    fun magiskPolicyRowsMapToIntentsAndStale() {
        val rows = listOf(
            MagiskPolicyReader.Row(10123, MagiskPolicyReader.ALLOW),
            MagiskPolicyReader.Row(10124, MagiskPolicyReader.DENY),
            MagiskPolicyReader.Row(10125, MagiskPolicyReader.QUERY),
            MagiskPolicyReader.Row(10126, MagiskPolicyReader.ALLOW, untilEpochSeconds = 1),
        )
        val plan = migrator.plan(
            Fixtures.MIGRATION_ID,
            WorkMode.LITE,
            AuthSource.MAGISK_DB,
            inventory(
                Fixtures.packageFacts(10123, "com.example.a"),
                Fixtures.packageFacts(10124, "com.example.b"),
                Fixtures.packageFacts(10125, "com.example.c"),
                Fixtures.packageFacts(10126, "com.example.d"),
            ),
            magiskRows = rows,
        )
        assertEquals(2, plan.intents.size)
        assertEquals(
            GrantIntent.ALLOW,
            plan.intents.single { it.packageName == "com.example.a" }.decision,
        )
        assertEquals(
            GrantIntent.DENY,
            plan.intents.single { it.packageName == "com.example.b" }.decision,
        )
        assertTrue(plan.stale.any { it.uid == 10125 && it.reason == "POLICY_NOT_DECIDED" })
        assertTrue(plan.stale.any { it.uid == 10126 })
    }

    @Test
    fun parserAcceptanceAndUnsupportedFixtures() {
        // 版本 3（pre-V4 记录长度）也必须被自校验解析，而不是按 784 猜。
        val v3 = allowlist(version = 3, records = listOf(Triple("com.example.a", 10123, true)))
        val parsed = KsuAllowlistReader.parse(v3) as KsuAllowlistReader.ParseResult.Parsed
        assertEquals(776, parsed.recordSize)
        // key 含非可打印字符 → Unsupported
        val bytes = allowlist(records = listOf(Triple("com.example.a", 10123, true)))
        bytes[8 + 4] = 0x01
        assertTrue(KsuAllowlistReader.parse(bytes) is KsuAllowlistReader.ParseResult.Unsupported)
    }
}
