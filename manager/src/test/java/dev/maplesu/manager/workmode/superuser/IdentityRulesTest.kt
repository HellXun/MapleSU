package dev.maplesu.manager.workmode.superuser

import dev.maplesu.manager.workmode.control.schema.GrantIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityRulesTest {
    private val identity = Fixtures.identity()
    private val intent = Fixtures.intentFrom(identity)

    @Test
    fun matchExact() {
        assertEquals(IdentityVerdict.MATCH, IdentityRules.verdict(intent, identity))
    }

    @Test
    fun nullIdentityIsUnknown() {
        assertEquals(IdentityVerdict.UNKNOWN, IdentityRules.verdict(intent, null))
    }

    @Test
    fun updateTimeOnlyIsRotatedSoft() {
        val upgraded = identity.copy(lastUpdateTime = identity.lastUpdateTime + 1)
        assertEquals(IdentityVerdict.ROTATED_SOFT, IdentityRules.verdict(intent, upgraded))
    }

    @Test
    fun apkPathChangeIsRotatedSoft() {
        val moved = identity.copy(apkPathHash = IdentityRules.apkPathHash("/data/app/${Fixtures.APP}-1/base.apk"))
        assertEquals(IdentityVerdict.ROTATED_SOFT, IdentityRules.verdict(intent, moved))
    }

    @Test
    fun signerChangeIsStaleHard() {
        val resigned = identity.copy(signerSha256 = listOf(Fixtures.hash('e')))
        assertEquals(IdentityVerdict.STALE_HARD, IdentityRules.verdict(intent, resigned))
    }

    @Test
    fun packageRenameIsStaleHard() {
        val renamed = identity.copy(packageName = Fixtures.OTHER)
        assertEquals(IdentityVerdict.STALE_HARD, IdentityRules.verdict(intent, renamed))
    }

    @Test
    fun uidChangeIsStaleHard() {
        val reassigned = identity.copy(uid = Fixtures.UID + 1)
        assertEquals(IdentityVerdict.STALE_HARD, IdentityRules.verdict(intent, reassigned))
    }

    @Test
    fun reinstallIsStaleHard() {
        val reinstalled = identity.copy(firstInstallTime = identity.firstInstallTime + 1)
        assertEquals(IdentityVerdict.STALE_HARD, IdentityRules.verdict(intent, reinstalled))
    }

    @Test
    fun signerOrderDoesNotMatter() {
        val reordered = identity.copy(signerSha256 = identity.signerSha256.reversed())
        assertEquals(IdentityVerdict.MATCH, IdentityRules.verdict(intent, reordered))
    }

    @Test
    fun apkPathHashIs32CharsAndPathNotStored() {
        val hash = IdentityRules.apkPathHash("/data/app/x.apk")
        assertEquals(32, hash.length)
        assertTrue(hash.matches(Regex("[0-9a-f]{32}")))
        assertTrue(!hash.contains("/data"))
    }

    // ------------------------------------------------------------------ granularity / expansion

    @Test
    fun sharedUidIsRejectedForADirect() {
        assertNull(IdentityRules.expand(intent, BackendId.A_DIRECT, listOf(Fixtures.APP, Fixtures.OTHER)))
        // 恰好一个包才可表达。
        val expanded = IdentityRules.expand(intent, BackendId.A_DIRECT, listOf(Fixtures.APP))
        assertEquals(true, expanded?.allow)
        assertEquals(BackendId.A_DIRECT, expanded?.target)
    }

    @Test
    fun aUltraAllOrNoneWhenOnlyOnePackageAllowed() {
        // 只授权 APP；同 uid 的 OTHER 没有 allow 意图 → 该 uid 整体不 allow（all-or-none）。
        val expanded = IdentityRules.expandAll(listOf(intent), BackendId.A_ULTRA,
            mapOf(Fixtures.UID to listOf(Fixtures.APP, Fixtures.OTHER)))
        assertEquals(2, expanded.size)
        assertTrue(expanded.all { !it.allow })
        assertTrue(expanded.all { it.degraded == IdentityRules.ALL_OR_NONE_MARKER })
    }

    @Test
    fun aUltraAllowsWholeUidWhenEveryPackageAllowed() {
        val entries = listOf(intent, Fixtures.intent(packageName = Fixtures.OTHER, uid = Fixtures.UID))
        val expanded = IdentityRules.expandAll(entries, BackendId.A_ULTRA,
            mapOf(Fixtures.UID to listOf(Fixtures.APP, Fixtures.OTHER)))
        assertTrue(expanded.all { it.allow })
        assertTrue(expanded.all { it.degraded == IdentityRules.MULTI_PACKAGE_UID_MARKER })
    }

    @Test
    fun aUltraDenyRevokesWholeUid() {
        val entries = listOf(
            intent,
            Fixtures.intent(packageName = Fixtures.OTHER, uid = Fixtures.UID, decision = GrantIntent.DENY),
        )
        val expanded = IdentityRules.expandAll(entries, BackendId.A_ULTRA,
            mapOf(Fixtures.UID to listOf(Fixtures.APP, Fixtures.OTHER)))
        assertTrue(expanded.all { !it.allow })
    }

    @Test
    fun userIdIsDerivedFromFullUid() {
        assertEquals(0, IdentityRules.userIdOf(10_200))
        assertEquals(10, IdentityRules.userIdOf(1_010_200))
        assertEquals(10, identity.copy(uid = 1_010_200).userId)
    }
}
