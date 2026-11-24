// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.workmode.detect

import com.maplesu.core.ui.screen.home.HomeUiState
import com.maplesu.core.ui.screen.home.SystemInfo
import com.maplesu.core.ui.screen.home.asLiteHiddenNotInstalled
import com.maplesu.core.ui.util.module.LatestVersionInfo
import com.maplesu.core.KernelVersion
import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「第二事实源 readiness」的主机回归（纯 JVM：无 Android、无 root、无 Context）。
 *
 * 对应 2026-11-02 实机缺陷：DirtyFrag×Normal 激活成功后重启一次，首页显示「未安装」，
 * 但 `su -c 'id -u'` 返回 0、`kernelsu` 仍在 `/proc/modules`。根因是旧探针只看
 * `/system/bin/su` 是否存在，并把"读不到"折叠成 false。本文件锁定三条不变量：
 *  1. 只有 exit 0 + stdout uid=0 才算 USABLE；起不来/超时是 UNKNOWN，绝不等于 NOT_USABLE；
 *  2. UNKNOWN 不得产生 NOT_INSTALLED，也不得覆盖上次状态；
 *  3. SU 可用不代表已安装 MapleSU；模式未知不落盘，不增加首页状态。
 */
class SuReadinessTest {

    private companion object {
        const val BOOT = "boot-current"
        const val OTHER_BOOT = "boot-previous"
    }

    // ---------------------------------------------------------------------
    // 1) 单候选三态真值表
    // ---------------------------------------------------------------------

    @Test
    fun onlyExitZeroWithUidZeroIsUsable() {
        assertEquals(SuReadiness.USABLE, SuProbeDecision.classify(true, false, 0, "0"))
        assertEquals(SuReadiness.USABLE, SuProbeDecision.classify(true, false, 0, "0\n"))
        assertEquals(SuReadiness.USABLE, SuProbeDecision.classify(true, false, 0, "warning\n0\n"))
    }

    @Test
    fun ranButNotRootIsNotUsable() {
        assertEquals(SuReadiness.NOT_USABLE, SuProbeDecision.classify(true, false, 0, "2000"))
        assertEquals(SuReadiness.NOT_USABLE, SuProbeDecision.classify(true, false, 1, ""))
        assertEquals(SuReadiness.NOT_USABLE, SuProbeDecision.classify(true, false, 126, ""))
        // 进程跑完却没有 uid 证据：不算可用（但也不是"起不来"）。
        assertEquals(SuReadiness.NOT_USABLE, SuProbeDecision.classify(true, false, 0, ""))
    }

    @Test
    fun cannotStartOrTimeoutIsUnknownNeverNotUsable() {
        assertEquals(SuReadiness.UNKNOWN, SuProbeDecision.classify(false, false, null, null))
        assertEquals(SuReadiness.UNKNOWN, SuProbeDecision.classify(true, true, null, null))
        assertEquals(SuReadiness.UNKNOWN, SuProbeDecision.classify(true, false, null, "0"))
    }

    // ---------------------------------------------------------------------
    // 2) stdout 解析
    // ---------------------------------------------------------------------

    @Test
    fun parseUidTakesLastNumericLine() {
        assertEquals(0, SuProbeDecision.parseUid("0"))
        assertEquals(0, SuProbeDecision.parseUid("uid=2000\n0\n"))
        assertEquals(2000, SuProbeDecision.parseUid("2000"))
        assertNull(SuProbeDecision.parseUid(null))
        assertNull(SuProbeDecision.parseUid(""))
        assertNull(SuProbeDecision.parseUid("not a number"))
    }

    // ---------------------------------------------------------------------
    // 3) 多候选合成：USABLE 优先；否则 UNKNOWN 优先于 NOT_USABLE
    // ---------------------------------------------------------------------

    @Test
    fun combinePrefersUsableThenUnknown() {
        assertEquals(
            SuReadiness.USABLE,
            SuProbeDecision.combine(listOf(SuReadiness.NOT_USABLE, SuReadiness.USABLE)),
        )
        assertEquals(
            SuReadiness.UNKNOWN,
            SuProbeDecision.combine(listOf(SuReadiness.NOT_USABLE, SuReadiness.UNKNOWN)),
        )
        assertEquals(
            SuReadiness.NOT_USABLE,
            SuProbeDecision.combine(listOf(SuReadiness.NOT_USABLE)),
        )
        assertEquals(SuReadiness.NOT_USABLE, SuProbeDecision.combine(emptyList()))
    }

    @Test
    fun reportKeepsEveryAttemptAndNamesTheUsableSource() {
        val report = SuProbeDecision.report(
            listOf(
                SuProbeAttempt("maplesu-su:/data/x/su", SuReadiness.NOT_USABLE, exitCode = 1),
                SuProbeAttempt("ksu-su:/system/bin/su", SuReadiness.USABLE, exitCode = 0, uid = 0),
            ),
        )
        assertEquals(SuReadiness.USABLE, report.readiness)
        assertEquals("ksu-su:/system/bin/su", report.usableSource)
        assertEquals(2, report.attempts.size)
        assertTrue(report.describe.contains("ksu-su:/system/bin/su:USABLE"))
    }

    // ---------------------------------------------------------------------
    // 4) 首页纯投影：SU 能力与已安装模式分离
    // ---------------------------------------------------------------------

    private fun snapshot(
        mode: WorkMode?,
        bootId: String = BOOT,
        su: Boolean = false,
        ultra: String? = null,
        dirty: Boolean = false,
    ) = EnvironmentSnapshot(
        mode = mode,
        suAvailable = su,
        ultraEnvFingerprint = ultra,
        bootId = bootId,
        checkedAtMillis = 1_000L,
        dirty = dirty,
    )

    @Test
    fun staleNotInstalledRecordRequiresFreshProbe() {
        val result = EnvironmentHomeProjection.decide(
            EnvironmentHomeProjection.Facts(snapshot(null, bootId = OTHER_BOOT), BOOT),
        )
        assertFalse("跨 boot 的旧记录不能短路成未安装", result.confirmedNotInstalled)
        assertTrue(result.bootChanged)
    }

    @Test
    fun sameBootNotInstalledRecordDoesNotInventInstalledMode() {
        val result = EnvironmentHomeProjection.decide(
            EnvironmentHomeProjection.Facts(snapshot(null), BOOT),
        )
        assertTrue(result.confirmedNotInstalled)
        assertFalse(result.bootChanged)
    }

    @Test
    fun invalidatedNotInstalledRecordIsNoLongerAConfirmedAbsence() {
        val state = store(snapshot(null))
        state.markDirty(EnvironmentInvalidation.INSTALLER_COMPLETED)
        val result = EnvironmentHomeProjection.decide(
            EnvironmentHomeProjection.Facts(state.snapshot(), BOOT),
        )
        assertFalse(result.confirmedNotInstalled)
        assertNull(result.cachedMode)
    }

    @Test
    fun unreadableCurrentBootCannotConfirmAnOldNotInstalledRecord() {
        val result = EnvironmentHomeProjection.decide(
            EnvironmentHomeProjection.Facts(snapshot(null), ""),
        )
        assertFalse(result.confirmedNotInstalled)
    }

    @Test
    fun installedRecordWinsOverLiveProbe() {
        val result = EnvironmentHomeProjection.decide(
            EnvironmentHomeProjection.Facts(snapshot(WorkMode.LITE), BOOT, liveReadiness = SuReadiness.USABLE),
        )
        assertFalse(result.confirmedNotInstalled)
    }

    @Test
    fun liveUsableProbeOverridesNotInstalledRecord() {
        val result = EnvironmentHomeProjection.decide(
            EnvironmentHomeProjection.Facts(snapshot(null), BOOT, liveReadiness = SuReadiness.USABLE),
        )
        assertFalse(result.confirmedNotInstalled)
    }

    @Test
    fun liveNotUsableProbeDoesNotInventTemporary() {
        val result = EnvironmentHomeProjection.decide(
            EnvironmentHomeProjection.Facts(snapshot(null), BOOT, liveReadiness = SuReadiness.NOT_USABLE),
        )
        assertTrue(result.confirmedNotInstalled)
    }

    @Test
    fun liveUnknownProbeKeepsPreviousInstalledRecord() {
        val previous = snapshot(WorkMode.LITE)
        val result = EnvironmentHomeProjection.decide(
            EnvironmentHomeProjection.Facts(previous, BOOT, liveReadiness = SuReadiness.UNKNOWN),
        )
        assertFalse(result.confirmedNotInstalled)
    }

    // ---------------------------------------------------------------------
    // 5) Resolver：UNKNOWN ≠ NOT_INSTALLED，UNKNOWN 不落盘
    // ---------------------------------------------------------------------

    private class FakeProbes(
        var readiness: SuReadiness,
        var ultra: String? = null,
        var ksu: Boolean? = null,
    ) : EnvironmentProbes {
        val calls = mutableListOf<String>()

        override fun suReadiness(): SuReadiness {
            calls += "su"
            return readiness
        }

        override fun suPresent(): Boolean = suReadiness() == SuReadiness.USABLE

        override fun ultraFingerprint(): String? {
            calls += "ultra"
            return ultra
        }

        override fun ksuNormal(): Boolean? {
            calls += "ksu"
            return ksu
        }
    }

    private object FixedClock : Clock {
        override fun nowMillis(): Long = 2_000L
    }

    private fun store(initial: EnvironmentSnapshot? = null) =
        EnvironmentStateStore(InMemoryEnvironmentStatePersistence(initial))

    private fun resolver(probes: EnvironmentProbes, store: EnvironmentStateStore) =
        EnvironmentResolver(probes, store, clock = FixedClock)

    @Test fun anInconclusiveSuProbeNeverTurnsAFreshInstallIntoNotInstalled() {
        val state = store()
        val probes = FakeProbes(SuReadiness.UNKNOWN, ultra = null, ksu = false)
        val outcome = resolver(probes, state).resolve(BOOT)
        assertEquals(EnvironmentVerdict.UNKNOWN, outcome.verdict)
        assertNull(outcome.mode)
        assertNull("读不到就什么都不落盘", state.snapshot())
        assertEquals(listOf("su", "ultra"), probes.calls)
    }

    @Test fun anUnreadableUltraProbeDoesNotEraseItsPriorModeOrGrantRoot() {
        val previous = snapshot(WorkMode.LITE, ultra = "ultra:running")
        val state = store(previous)
        val probes = object : EnvironmentProbes {
            override fun suPresent() = true // a foreign manager may grant this
            override fun ultraFingerprint(): String? = error("ULTRA_RECOVERY_PROBE_UNKNOWN")
            override fun ksuNormal() = false
        }
        val outcome = resolver(probes, state).resolve(BOOT)
        assertEquals(EnvironmentVerdict.UNKNOWN, outcome.verdict)
        assertEquals(previous, state.snapshot())
    }

    @Test
    fun suUnknownNeverBecomesNotInstalled() {
        val state = store()
        val probes = FakeProbes(SuReadiness.UNKNOWN, ultra = null, ksu = null)

        val outcome = resolver(probes, state).resolve(BOOT)

        assertEquals(EnvironmentVerdict.UNKNOWN, outcome.verdict)
        assertNull("不得落盘未安装", state.snapshot())
    }

    @Test
    fun inheritedInstalledStateSurvivesUnknownProbe() {
        val previous = snapshot(WorkMode.LITE)
        val state = store(previous)
        val probes = FakeProbes(SuReadiness.UNKNOWN, ultra = "lite:credential", ksu = true)

        val outcome = resolver(probes, state).resolve(BOOT)

        assertEquals(EnvironmentVerdict.INHERITED, outcome.verdict)
        assertEquals(WorkMode.LITE, outcome.mode)
        assertEquals("读不到 SU 不能推翻已有安装记录", previous, state.snapshot())
        assertEquals(listOf("su", "ultra"), probes.calls)
    }

    @Test
    fun aConfirmedMissingUltraIdentityDropsAnInstalledRecordEvenWhenSuIsNotUsable() {
        val previous = snapshot(WorkMode.LITE)
        val state = store(previous)
        val probes = FakeProbes(SuReadiness.NOT_USABLE, ultra = null, ksu = null)

        val outcome = resolver(probes, state).resolve(BOOT)

        assertEquals(EnvironmentVerdict.NOT_INSTALLED, outcome.verdict)
        assertNull(state.snapshot()?.mode)
        assertTrue(probes.calls.contains("ultra"))
    }

    @Test
    fun trustedInstalledRecordIsClearedWhenTheUltraIdentityIsGone() {
        val state = store(snapshot(WorkMode.LITE))
        val probes = FakeProbes(SuReadiness.NOT_USABLE, ultra = null, ksu = false)

        val outcome = resolver(probes, state).resolve(BOOT)

        assertEquals(EnvironmentVerdict.NOT_INSTALLED, outcome.verdict)
        assertEquals(listOf("su", "ultra"), probes.calls)
        assertNull(state.snapshot()?.mode)
    }

    @Test
    fun bootChangeWithoutModeEvidenceDoesNotCreateAnInstallationRecord() {
        val previous = snapshot(null, bootId = OTHER_BOOT)
        val state = store(previous)
        val probes = FakeProbes(SuReadiness.USABLE, ultra = null, ksu = null)

        val outcome = resolver(probes, state).resolve(BOOT)

        assertEquals(EnvironmentVerdict.UNKNOWN, outcome.verdict)
        assertEquals(previous, state.snapshot())
    }

    @Test
    fun codecPreservesConfirmedModeAndIgnoresRetiredFalseMarker() {
        val original = snapshot(WorkMode.LITE, su = true)
        val encoded = EnvironmentSnapshotCodec.encode(original)
        assertFalse(encoded.containsKey("env.suTemp"))
        assertEquals(original, EnvironmentSnapshotCodec.decode(encoded))
        assertEquals(original, EnvironmentSnapshotCodec.decode(encoded + ("env.suTemp" to "0")))
    }

    // ---------------------------------------------------------------------
    // 7) 首页状态投影：只有实际模式证据才能展示已安装，隐藏仍优先
    // ---------------------------------------------------------------------

    private fun homeState() = HomeUiState(
        kernelVersion = KernelVersion(6, 6, 89),
        ksuVersion = null,
        managerUAPIVersion = 1,
        kernelUAPIVersion = null,
        lkmMode = null,
        isLkmBundled = false,
        isManager = false,
        isManagerPrBuild = false,
        isKernelPrBuild = false,
        requiresNewKernel = false,
        requiresNewManager = false,
        isRootAvailable = true,
        isSafeMode = false,
        checkUpdateEnabled = false,
        latestVersionInfo = LatestVersionInfo(),
        currentManagerVersionCode = 1L,
        systemInfo = SystemInfo("6.6.89", "5.0.5", "device", "fixture", "Unknown", 0),
    )

    @Test
    fun rootCapabilityAloneDoesNotRenderInstalledBranch() {
        val state = homeState()
        assertTrue(state.isRootAvailable)
        assertFalse(state.showInstalledStatus)
        assertNull(state.environmentMode)
    }

    @Test
    fun confirmedModeIsDisplayedAndHiddenFactsStillMaskIt() {
        val state = homeState().copy(environmentMode = WorkMode.LITE)
        assertTrue(state.showInstalledStatus)
        val masked = state.asLiteHiddenNotInstalled()
        assertFalse(masked.showInstalledStatus)
        assertFalse(masked.isRootAvailable)
        assertNull(masked.environmentMode)
    }
}
