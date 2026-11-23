package dev.maplesu.manager.workmode.superuser

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.control.schema.AppliedMark
import dev.maplesu.manager.workmode.control.schema.GrantIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrantMergeTest {
    private val identity = Fixtures.identity()
    private val now = 5_000L

    private fun merge(
        current: dev.maplesu.manager.workmode.control.schema.SharedSuperuserFile,
        observed: Map<BackendId, List<GrantView>> = emptyMap(),
        writer: WorkMode = WorkMode.LITE,
        at: Long = now,
    ) = GrantMerge.merge(current, observed, at, writer)

    private fun allowObserved(): Map<BackendId, List<GrantView>> =
        mapOf(BackendId.A_DIRECT to listOf(Fixtures.view(identity = identity)))

    @Test
    fun observedAllowCreatesEntry() {
        val merged = merge(Fixtures.shared(entries = emptyList()), allowObserved())
        assertEquals(1, merged.entries.size)
        val entry = merged.entries.single()
        assertEquals(GrantIntent.ALLOW, entry.decision)
        assertEquals("A", entry.origin)
        assertEquals(now, entry.decidedAt)
        assertEquals(2, merged.revision)
    }

    @Test
    fun tombstoneWinsOverOlderAllow() {
        val current = Fixtures.shared(
            entries = listOf(Fixtures.intentFrom(identity, decidedAt = 1_000L)),
            tombs = listOf(Fixtures.tomb(revokedAt = 2_000L, rev = 2)),
        )
        val merged = merge(current, allowObserved())
        val entry = merged.entries.single()
        assertEquals(GrantIntent.DENY, entry.decision)
        assertEquals(2_000L, entry.decidedAt)
        assertTrue(entry.applied.isEmpty())
    }

    @Test
    fun tombstoneLoosesToNewerDecision() {
        val current = Fixtures.shared(
            entries = listOf(Fixtures.intentFrom(identity, decidedAt = 3_000L)),
            tombs = listOf(Fixtures.tomb(revokedAt = 2_000L, rev = 2)),
        )
        val merged = merge(current, allowObserved())
        assertEquals(GrantIntent.ALLOW, merged.entries.single().decision)
    }

    @Test
    fun missingFromBackendBecomesPendingNotDeny() {
        val current = Fixtures.shared(entries = listOf(Fixtures.intentFrom(identity)))
        val merged = merge(current, mapOf(BackendId.A_DIRECT to emptyList()))
        val entry = merged.entries.single()
        assertEquals(GrantIntent.ALLOW, entry.decision)
        assertEquals(GrantMerge.MISSING_OP, entry.pending?.op)
        assertEquals(1, entry.pending?.attempts)
    }

    @Test
    fun missingTwiceBecomesDenyWithTomb() {
        val current = Fixtures.shared(entries = listOf(Fixtures.intentFrom(identity)))
        val first = merge(current, mapOf(BackendId.A_DIRECT to emptyList()), at = 5_000L)
        assertEquals(1, first.entries.single().pending?.attempts)
        val second = merge(first, mapOf(BackendId.A_DIRECT to emptyList()), at = 6_000L)
        val entry = second.entries.single()
        assertEquals(GrantIntent.DENY, entry.decision)
        assertEquals(1, second.tombs.size)
        assertEquals(6_000L, second.tombs.single().revokedAt)
        assertEquals(second.revision, second.tombs.single().rev)
        assertNull(entry.pending)
    }

    @Test
    fun observedAllowDoesNotReviveDeny() {
        // 后端仍是旧 allow，但 shared 已有 deny：保守粘滞，防 E5 复活。
        val current = Fixtures.shared(entries = listOf(Fixtures.intentFrom(identity, GrantIntent.DENY)))
        val merged = merge(current, allowObserved())
        assertEquals(GrantIntent.DENY, merged.entries.single().decision)
    }

    @Test
    fun rotatedSoftKeepsIntentAndMarksStale() {
        val current = Fixtures.shared(entries = listOf(Fixtures.intentFrom(identity)))
        val upgraded = identity.copy(lastUpdateTime = identity.lastUpdateTime + 1)
        val merged = merge(current, mapOf(BackendId.A_DIRECT to listOf(Fixtures.view(identity = upgraded))))
        val entry = merged.entries.single()
        assertEquals(GrantIntent.ALLOW, entry.decision)
        assertTrue(entry.degraded.contains(GrantMerge.ROTATED_MARKER))
        assertFalse(entry.identityStale)
        // 旧身份快照保留，等待 A 侧重新确认。
        assertEquals(identity.lastUpdateTime, entry.lastUpdateTime)
        assertNull(entry.pending)
    }

    @Test
    fun staleHardMarksIdentityStale() {
        val current = Fixtures.shared(entries = listOf(Fixtures.intentFrom(identity)))
        val resigned = identity.copy(signerSha256 = listOf(Fixtures.hash('e')))
        val merged = merge(current, mapOf(BackendId.A_DIRECT to listOf(Fixtures.view(identity = resigned))))
        assertTrue(merged.entries.single().identityStale)
        assertNull(merged.entries.single().pending)
    }

    @Test
    fun matchRefreshesIdentitySnapshot() {
        val current = Fixtures.shared(entries = listOf(Fixtures.intentFrom(identity)))
        val merged = merge(current, mapOf(BackendId.A_ULTRA to listOf(Fixtures.view(identity = identity))))
        val entry = merged.entries.single()
        assertEquals(identity.lastUpdateTime, entry.lastUpdateTime)
        assertEquals(identity.apkPathHash, entry.apkPathHash)
        assertFalse(entry.identityStale)
        assertEquals(GrantIntent.ALLOW, entry.decision)
    }

    @Test
    fun revisionIncreasesAndWriterRecorded() {
        val current = Fixtures.shared(revision = 5)
        val merged = merge(current, allowObserved(), writer = WorkMode.LITE, at = 9_000L)
        assertEquals(6, merged.revision)
        assertEquals("B", merged.lastWriter)
        assertEquals(9_000L, merged.updatedAt)
    }

    @Test
    fun tombstoneClearsWatermarkForReplay() {
        val current = Fixtures.shared(
            entries = listOf(Fixtures.intentFrom(identity).copy(
                applied = listOf(AppliedMark("A_DIRECT", 2, 1_000, true)),
            )),
            tombs = listOf(Fixtures.tomb(revokedAt = 2_000L, rev = 3)),
        )
        val merged = merge(current, allowObserved())
        val entry = merged.entries.single()
        assertEquals(GrantIntent.DENY, entry.decision)
        assertNotNull(entry)
        assertTrue(entry.applied.isEmpty())
    }

    @Test
    fun unobservedDenyEntryIsPreserved() {
        val current = Fixtures.shared(
            entries = listOf(Fixtures.intentFrom(identity, GrantIntent.DENY)),
            tombs = listOf(Fixtures.tomb(revokedAt = 2_000L)),
        )
        val merged = merge(current, emptyMap())
        assertEquals(GrantIntent.DENY, merged.entries.single().decision)
        assertEquals(1, merged.tombs.size)
    }
}
