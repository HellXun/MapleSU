// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.workmode.detect

import dev.maplesu.manager.workmode.WorkMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T14「环境检测继承式重写」的主机测试（纯 JVM：无 Android、无 root、无 UI）。
 *
 * 每个用例都对应一条用户要求，断言用**计数探针**证明"零探测"/"短路"/"不抖动"，
 * 而不是只比较返回值 —— 少了这一步，"继承"很容易退化成"跑完探测后返回同一个值"。
 */
class EnvironmentInheritanceTest {

    private companion object {
        const val BOOT = "boot-current"
        const val OTHER_BOOT = "boot-previous"
    }

    /** 计数探针：记录调用顺序与次数，`rootShellAvailable()` 单独计数。 */
    private class CountingProbes(
        var su: Boolean = true,
        var ultra: String? = null,
        var ksu: Boolean? = null,
        var suDelayMillis: Long = 0,
    ) : EnvironmentProbes {
        val calls = mutableListOf<String>()
        var rootShellCalls = 0

        override fun suPresent(): Boolean {
            calls += "su"
            if (suDelayMillis > 0) Thread.sleep(suDelayMillis)
            return su
        }

        override fun ultraFingerprint(): String? {
            calls += "ultra"
            return ultra
        }

        override fun ksuNormal(): Boolean? {
            calls += "ksu"
            return ksu
        }

        override fun rootShellAvailable(): Boolean? {
            calls += "rootShell"
            rootShellCalls++
            return true
        }

        fun count(name: String): Int = calls.count { it == name }
    }

    private object FixedClock : Clock {
        override fun nowMillis(): Long = 1_000L
    }

    private fun snapshot(
        mode: WorkMode?,
        bootId: String = BOOT,
        dirty: Boolean = false,
        su: Boolean = true,
        ultra: String? = null,
        checkedAt: Long = 500L,
    ) = EnvironmentSnapshot(
        mode = mode,
        suAvailable = su,
        ultraEnvFingerprint = ultra,
        bootId = bootId,
        checkedAtMillis = checkedAt,
        dirty = dirty,
    )

    private fun store(initial: EnvironmentSnapshot? = null) =
        EnvironmentStateStore(InMemoryEnvironmentStatePersistence(initial))

    private fun runner(
        probes: EnvironmentProbes,
        store: EnvironmentStateStore,
        bootId: String = BOOT,
        timeoutMillis: Long = 2_000L,
    ) = EnvironmentCheckRunner(
        resolver = EnvironmentResolver(probes, store, clock = FixedClock),
        store = store,
        bootIdSource = { bootId },
        clock = FixedClock,
        timeoutMillis = timeoutMillis,
    )

    @Test fun foreignSuCannotResurrectThePreviousUltraMode() = runBlocking {
        for (previous in listOf(null, snapshot(WorkMode.LITE, ultra = "ultra:running"))) {
            val store = store(previous)
            val probes = CountingProbes(su = true, ultra = null, ksu = false)
            val outcome = runner(probes, store).check()
            assertEquals(EnvironmentVerdict.NOT_INSTALLED, outcome.verdict)
            assertNull(outcome.mode)
            assertNull(store.snapshot()?.mode)
            assertEquals(true, store.snapshot()?.suAvailable)
            assertEquals(listOf("su", "ultra"), probes.calls)
        }
    }

    @Test fun cachedUltraIsReusedOnlyAfterItsOwnLiveChannelIsVerified() = runBlocking {
        val previous = snapshot(WorkMode.LITE, ultra = "ultra:running")
        val store = store(previous)
        val probes = CountingProbes(su = true, ultra = "ultra:running")
        val outcome = runner(probes, store).check()
        assertEquals(EnvironmentVerdict.INHERITED, outcome.verdict)
        assertEquals(previous, store.snapshot())
        assertEquals(listOf("su", "ultra"), probes.calls)
    }

    // 2) 没有状态 → 走完整降级探测链。
    @Test
    fun dirtyStateReprobes() = runBlocking {
        val store = store(snapshot(mode = WorkMode.LITE, dirty = true))
        val probes = CountingProbes(su = true, ultra = "lite:credential", ksu = true)

        val outcome = runner(probes, store).check()

        assertEquals(EnvironmentVerdict.ULTRA, outcome.verdict)
        assertEquals(WorkMode.LITE, outcome.mode)
        assertEquals(EnvironmentCheckTrigger.DIRTY, outcome.trigger)
        assertEquals(1, probes.count("su"))
        assertEquals(1, probes.count("ultra"))
        assertFalse("重新判定后脏位必须清掉", store.isDirty())
        assertEquals(WorkMode.LITE, store.snapshot()?.mode)
    }

    // 4) Ultra 与 Normal 均排除后才确认未安装；不调用 root shell。
    @Test
    fun absentSuAndNoUltraIsTerminalNotInstalled() = runBlocking {
        val store = store()
        // 普通 SU 的失败不排除 Normal；此处必须由内核探针明确阴性。
        val probes = CountingProbes(su = false, ultra = null, ksu = false)

        val outcome = runner(probes, store).check()

        assertEquals(EnvironmentVerdict.NOT_INSTALLED, outcome.verdict)
        assertEquals("ENV_NOT_INSTALLED", outcome.code)
        assertNull(outcome.mode)
        assertTrue(outcome.terminal)
        assertEquals("Ultra 身份必须排除", listOf("su", "ultra"), probes.calls)
        assertEquals("不得起 root shell", 0, probes.rootShellCalls)
        assertNull("未安装也要落盘（mode=null）", store.snapshot()?.mode)
        assertNotNull(store.snapshot())
    }

    // 5) 有 Ultra 环境 → Ultra，短路（不再探测 KSU）。
    @Test
    fun ultraEnvironmentShortCircuitsBeforeKsu() = runBlocking {
        val store = store()
        val probes = CountingProbes(su = true, ultra = "ultra:running", ksu = true)

        val outcome = runner(probes, store).check()

        assertEquals(EnvironmentVerdict.ULTRA, outcome.verdict)
        assertEquals("ENV_ULTRA", outcome.code)
        assertEquals(WorkMode.LITE, outcome.mode)
        assertEquals(0, probes.rootShellCalls)
        assertEquals("ultra:running", store.snapshot()?.ultraEnvFingerprint)
    }

    // 6) 有 su 无 Ultra + KSU 探针（JNI/ksud）成功 → MapleSU_Normal。
    @Test
    fun deadlineExceededReturnsUnknownAndKeepsPreviousState() = runBlocking {
        val previous = snapshot(mode = WorkMode.LITE, dirty = true, ultra = "lite:credential", checkedAt = 7_000L)
        val store = store(previous)
        val probes = CountingProbes(su = true, ultra = "lite:credential", ksu = true, suDelayMillis = 1_500L)

        val outcome = runner(probes, store, timeoutMillis = 150L).check()

        assertEquals(EnvironmentVerdict.UNKNOWN, outcome.verdict)
        assertEquals("ENV_UNKNOWN_TIMEOUT", outcome.code)
        assertEquals(EnvironmentCheckDetail.DEADLINE_EXCEEDED, outcome.detail)
        assertEquals("超时不得覆盖上次状态", previous, store.snapshot())
        assertEquals(WorkMode.LITE, outcome.mode)
        assertEquals(0, probes.rootShellCalls)
        assertEquals("生产默认预算必须是用户要求的 8 s", 8_000L, EnvironmentCheckRunner.DEFAULT_TIMEOUT_MILLIS)
    }

    // 9) 并发两次 refresh → 只跑一次探测（single-flight）。
    @Test
    fun retryDoesNotFlap() = runBlocking {
        val firstStore = store()
        val firstProbes = CountingProbes(su = true, ultra = null, ksu = true)
        val first = runner(firstProbes, firstStore).check()

        val retryProbes = CountingProbes(su = true, ultra = null, ksu = true)
        val retry = runner(retryProbes, firstStore).check()

        // 未安装态不是安装身份，重试会重新确认，但结论必须一致 —— 界面不会在两种状态之间跳。
        assertEquals(EnvironmentVerdict.NOT_INSTALLED, retry.verdict)
        assertEquals("重试必须给出与首次相同的终态", first.mode, retry.mode)
        assertEquals("重试重新核对 SU 与 Ultra 身份", listOf("su", "ultra"), retryProbes.calls)
        assertTrue("未安装是终态", retry.terminal)

        // 相同输入、全新状态下重复一次，终态必须逐字相同（无随机/顺序依赖）。
        val repeatStore = store()
        val repeat = runner(CountingProbes(su = true, ultra = null, ksu = true), repeatStore).check()
        assertEquals(first.verdict, repeat.verdict)
        assertEquals(first.code, repeat.code)
        assertEquals(first.mode, repeat.mode)
    }

    // 11) 换了 boot（可能绕过管理器刷过内核）→ 必须重新探测一次。
    @Test
    fun bootChangeInvalidatesInheritance() = runBlocking {
        val store = store(snapshot(mode = WorkMode.LITE, bootId = OTHER_BOOT, ultra = "lite:credential"))
        val probes = CountingProbes(su = false, ultra = null, ksu = false)

        val outcome = runner(probes, store).check()

        assertEquals(EnvironmentCheckTrigger.BOOT_CHANGED, outcome.trigger)
        assertEquals(EnvironmentVerdict.NOT_INSTALLED, outcome.verdict)
        assertEquals(1, probes.count("su"))
        assertEquals(BOOT, store.snapshot()?.bootId)
    }

    // 12) 置脏只打开 dirty，不动上次结论；没有状态时不产生记录。
    @Test
    fun markDirtyPreservesLastVerdict() {
        val store = store(snapshot(mode = WorkMode.LITE, ultra = null, su = true))
        val marked = store.markDirty(EnvironmentInvalidation.MODE_SWITCHED)

        assertNotNull(marked)
        assertEquals(WorkMode.LITE, marked?.mode)
        assertTrue(store.isDirty())
        assertEquals("LITE", store.snapshot()?.mode?.name)

        val empty = store()
        assertNull(empty.markDirty(EnvironmentInvalidation.FLASHED))
        assertNull(empty.snapshot())
    }

    // 13) 持久化编码：往返一致；损坏/未知 schema → "没有状态"（退化成探测，不当未安装）。
    @Test
    fun snapshotCodecRoundTripAndCorruption() {
        val original = snapshot(mode = WorkMode.LITE, dirty = true, su = true, ultra = "ultra:running", checkedAt = 42L)
        val decoded = EnvironmentSnapshotCodec.decode(EnvironmentSnapshotCodec.encode(original))
        assertEquals(original, decoded)

        val noneMode = snapshot(mode = null)
        assertEquals(noneMode, EnvironmentSnapshotCodec.decode(EnvironmentSnapshotCodec.encode(noneMode)))

        assertNull(EnvironmentSnapshotCodec.decode(emptyMap()))
        assertNull(
            EnvironmentSnapshotCodec.decode(
                EnvironmentSnapshotCodec.encode(original) + (EnvironmentSnapshotCodec.SCHEMA_KEY to "99"),
            ),
        )
        assertNull(
            EnvironmentSnapshotCodec.decode(
                EnvironmentSnapshotCodec.encode(original) + (EnvironmentSnapshotCodec.MODE_KEY to "Z"),
            ),
        )
    }
}
