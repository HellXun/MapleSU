package dev.maplesu.manager.runtime

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** P0 根路由：LITE 下 Ultra 优先/回退、失败不伪造、capability 缓存、RootExec 门面。 */
class RootExecutionRouterTest {
    private class FakeExecutor(
        override val backend: RootBackend,
        var available: Boolean = true,
        var result: RootExecResult? = null,
        var throwOnRun: Boolean = false,
    ) : RootExecutor {
        var calls = 0
        var lastScript: String? = null

        override fun available(): Boolean = available

        override fun run(script: String): RootExecResult {
            calls++
            lastScript = script
            if (throwOnRun) throw IllegalStateException("boom")
            return result ?: RootExecResult(backend, 0, "out:$script")
        }
    }

    private fun router(
        lite: RootExecutor?,
        ultra: RootExecutor?,
        mode: () -> WorkMode? = { WorkMode.LITE },
        clock: () -> Long = { 0L },
        ksu: RootExecutor? = null,
    ) = RootExecutionRouter(
        lite, ultra,
        modeProvider = mode,
        clock = clock,
        capabilityTtlMillis = 2_000L,
        ksuProvider = { ksu },
    )

    @After
    fun tearDown() {
        RootExec.resetForTest()
    }

    @Test
    fun liteModePrefersUltraEnvAndNeverTouchesLiteKernelOnSuccess() {
        val ultra = FakeExecutor(RootBackend.ULTRA_ENV, result = RootExecResult(RootBackend.ULTRA_ENV, 0, "ultra-out"))
        val lite = FakeExecutor(RootBackend.LITE_KERNEL)
        val result = router(lite, ultra).execute("id")
        assertEquals(RootBackend.ULTRA_ENV, result.backend)
        assertEquals("ultra-out", result.output)
        assertEquals(0, lite.calls)
        assertEquals(1, ultra.calls)
    }

    @Test
    fun aSubmittedUltraFailureKeepsItsOriginalResultWithoutALegacyReplay() {
        val ultra = FakeExecutor(
            RootBackend.ULTRA_ENV, available = true,
            result = RootExecResult(RootBackend.ULTRA_ENV, 12, "", "ULTRA_ROOT_FAILED:ERR"),
        )
        val lite = FakeExecutor(RootBackend.LITE_KERNEL, result = RootExecResult(RootBackend.LITE_KERNEL, 0, "lite-out"))
        val result = router(lite, ultra).execute("id")
        assertEquals(RootBackend.ULTRA_ENV, result.backend)
        assertEquals(12, result.code)
        assertEquals("ULTRA_ROOT_FAILED:ERR", result.detail)
        assertEquals(1, ultra.calls)
        assertEquals(0, lite.calls)
    }

    @Test
    fun liteModeUltraUnavailableDoesNotBlockLiteKernelFallback() {
        val ultra = FakeExecutor(RootBackend.ULTRA_ENV, available = false)
        val lite = FakeExecutor(RootBackend.LITE_KERNEL)
        val result = router(lite, ultra).execute("id")
        assertEquals(RootBackend.LITE_KERNEL, result.backend)
        assertEquals(0, ultra.calls)
    }

    /**
     * KSU 语义（2026-11-01 变更）：Normal 下**先**用 KSU executor，且**不再**把
     * LITE_KERNEL 留在候选里 —— KSU 设备上没有 Lite 凭据/`libmaplesu_runtime`，
     * 留着它只会让每次 root 往返多起一个必然失败的进程。
     * （旧断言是"非 LITE 模式 Lite 内核优先"，属于 KSU 后端未接线时代的语义。）
     */
    @Test
    fun ksuModeUsesKsuExecutorAndSkipsLiteKernel() {
        val ksu = FakeExecutor(RootBackend.KSU, result = RootExecResult(RootBackend.KSU, 0, "ksu-out"))
        val lite = FakeExecutor(RootBackend.LITE_KERNEL)
        val ultra = FakeExecutor(RootBackend.ULTRA_ENV)
        val result = router(lite, ultra, mode = { WorkMode.LITE }, ksu = ksu).execute("id")
        assertEquals(RootBackend.KSU, result.backend)
        assertEquals("ksu-out", result.output)
        assertEquals(1, ksu.calls)
        assertEquals(0, lite.calls)
        assertEquals(0, ultra.calls)
    }

    @Test
    fun ksuModeWithoutExecutorFallsToUltraThenStructuredNone() {
        val lite = FakeExecutor(RootBackend.LITE_KERNEL)
        val ultra = FakeExecutor(RootBackend.ULTRA_ENV, available = false)
        val result = router(lite, ultra, mode = { WorkMode.LITE }).execute("id")
        assertEquals(RootBackend.NONE, result.backend)
        assertFalse(result.ok)
        // 绝不因为"KSU 后端没装"就偷偷回落到 Lite 内核凭据。
        assertEquals(0, lite.calls)
        assertTrue(result.detail.contains("ULTRA_ENV"))
        assertTrue(result.detail.contains("BACKEND_UNAVAILABLE"))
    }

    @Test
    fun ksuExecutorFailureIsNotFabricated() {
        val ksu = FakeExecutor(
            RootBackend.KSU,
            result = RootExecResult(RootBackend.KSU, -1, "", "KSU_ROOT_CHANNEL_UNAVAILABLE:SHELL_NOT_ROOT"),
        )
        val ultra = FakeExecutor(RootBackend.ULTRA_ENV, available = false)
        val result = router(FakeExecutor(RootBackend.LITE_KERNEL), ultra, mode = { WorkMode.LITE }, ksu = ksu)
            .execute("id")
        assertEquals(RootBackend.KSU, result.backend)
        assertFalse(result.ok)
        assertTrue(result.detail.contains("KSU_ROOT_CHANNEL_UNAVAILABLE"))
    }

    @Test
    fun rootExecInstallKsuIsWhatTheRouterSees() {
        val ksu = FakeExecutor(RootBackend.KSU, result = RootExecResult(RootBackend.KSU, 0, "ok"))
        RootExec.installForTest(
            RootExecutionRouter(
                liteKernel = FakeExecutor(RootBackend.LITE_KERNEL),
                ultraEnv = null,
                modeProvider = { WorkMode.LITE },
            ),
        )
        // 接线前：没有 KSU executor → 结构化 NONE（不伪造）
        assertEquals(RootBackend.NONE, RootExec.execute("id").backend)
        RootExec.installKsu(ksu)
        assertEquals(RootBackend.KSU, RootExec.execute("id").backend)
        assertEquals(RootBackend.KSU, RootExec.preferredBackend())
        RootExec.installKsu(null)
        assertEquals(RootBackend.NONE, RootExec.execute("id").backend)
    }

    @Test
    fun aStartedBackendFailureDoesNotTryAnAvailableFallback() {
        val ultra = FakeExecutor(
            RootBackend.ULTRA_ENV,
            result = RootExecResult(RootBackend.ULTRA_ENV, 9, "", "ULTRA_ROOT_FAILED:ERR"),
        )
        val lite = FakeExecutor(
            RootBackend.LITE_KERNEL,
            result = RootExecResult(RootBackend.LITE_KERNEL, -1, "", "no paired credential"),
        )
        val result = router(lite, ultra).execute("id")
        assertEquals(RootBackend.ULTRA_ENV, result.backend)
        assertFalse(result.ok)
        assertEquals(9, result.code)
        assertEquals("ULTRA_ROOT_FAILED:ERR", result.detail)
        assertEquals(0, lite.calls)
    }

    @Test
    fun executorExceptionDoesNotReplayTheScriptOnAnotherBackend() {
        val ultra = FakeExecutor(RootBackend.ULTRA_ENV, throwOnRun = true)
        val lite = FakeExecutor(RootBackend.LITE_KERNEL)
        val result = router(lite, ultra).execute("id")
        assertEquals(RootBackend.ULTRA_ENV, result.backend)
        assertFalse(result.ok)
        assertEquals("BACKEND_EXCEPTION:IllegalStateException", result.detail)
        assertEquals(0, lite.calls)
    }

    @Test
    fun scriptIsPassedThroughVerbatim() {
        val ultra = FakeExecutor(RootBackend.ULTRA_ENV)
        router(FakeExecutor(RootBackend.LITE_KERNEL), ultra).execute("cat /data/adb/x; echo done")
        assertEquals("cat /data/adb/x; echo done", ultra.lastScript)
    }

    @Test
    fun capabilityPrefersUltraForLiteAndCachesWithinTtl() {
        var now = 0L
        val ultra = FakeExecutor(RootBackend.ULTRA_ENV)
        val lite = FakeExecutor(RootBackend.LITE_KERNEL)
        val router = router(lite, ultra, clock = { now })
        assertEquals(RootBackend.ULTRA_ENV, router.capability().backend)
        ultra.available = false
        now = 1_000L
        // TTL 内返回缓存（不重新探测）；过期后重新探测 → Lite。
        assertEquals(RootBackend.ULTRA_ENV, router.capability().backend)
        now = 2_500L
        assertEquals(RootBackend.LITE_KERNEL, router.capability().backend)
    }

    @Test
    fun capabilityForceRefreshBypassesTtl() {
        val ultra = FakeExecutor(RootBackend.ULTRA_ENV)
        val router = router(FakeExecutor(RootBackend.LITE_KERNEL), ultra, clock = { 0L })
        assertEquals(RootBackend.ULTRA_ENV, router.capability().backend)
        ultra.available = false
        assertEquals(RootBackend.ULTRA_ENV, router.capability().backend)
        assertEquals(RootBackend.LITE_KERNEL, router.capability(forceRefresh = true).backend)
    }

    @Test
    fun noneCapabilityWhenNoBackendAvailable() {
        val router = router(
            FakeExecutor(RootBackend.LITE_KERNEL, available = false),
            FakeExecutor(RootBackend.ULTRA_ENV, available = false),
        )
        assertEquals(RootCapability.NONE, router.capability())
        assertEquals("无", router.capability().label)
    }

    @Test
    fun rootAvailableForLiteIsUltraStrictRunningOnly() {
        val ultra = FakeExecutor(RootBackend.ULTRA_ENV)
        val router = router(FakeExecutor(RootBackend.LITE_KERNEL), ultra)
        assertTrue(router.rootAvailableForLite())
        ultra.available = false
        assertFalse(router.rootAvailableForLite())
        // 即使 Lite 凭据后端可用，Lite 根语义也只认 Ultra（形态 A2）。
        assertTrue(router(FakeExecutor(RootBackend.LITE_KERNEL), null).run {
            !rootAvailableForLite()
        })
    }

    @Test
    fun preferredBackendMatchesDeviceShape() {
        val router = router(FakeExecutor(RootBackend.LITE_KERNEL), FakeExecutor(RootBackend.ULTRA_ENV))
        assertEquals(RootBackend.ULTRA_ENV, router.preferredBackend())
        // KSU：装了 KSU executor → KSU；没装 → ULTRA_ENV（且真的执行时才失败，不提前假装）。
        val ksuWired = router(
            FakeExecutor(RootBackend.LITE_KERNEL),
            FakeExecutor(RootBackend.ULTRA_ENV),
            mode = { WorkMode.LITE },
            ksu = FakeExecutor(RootBackend.KSU),
        )
        assertEquals(RootBackend.KSU, ksuWired.preferredBackend())
        val ksuUnwired = router(
            FakeExecutor(RootBackend.LITE_KERNEL),
            FakeExecutor(RootBackend.ULTRA_ENV),
            mode = { WorkMode.LITE },
        )
        assertEquals(RootBackend.ULTRA_ENV, ksuUnwired.preferredBackend())
    }

    @Test
    fun rootExecWithoutInstallIsStructuredNone() {
        val result = RootExec.execute("id")
        assertEquals(RootBackend.NONE, result.backend)
        assertFalse(result.ok)
        assertEquals(RootCapability.NONE, RootExec.capability())
        assertFalse(RootExec.rootAvailableForLite())
    }

    @Test
    fun rootExecFacadeDelegatesToInstalledRouter() {
        val ultra = FakeExecutor(RootBackend.ULTRA_ENV, result = RootExecResult(RootBackend.ULTRA_ENV, 0, "ok"))
        val router = router(FakeExecutor(RootBackend.LITE_KERNEL), ultra)
        RootExec.installForTest(router)
        assertEquals("ok", RootExec.execute("id").output)
        assertEquals(RootCapability(RootBackend.ULTRA_ENV, available = true), RootExec.capability())
        assertTrue(RootExec.rootAvailableForLite())
        assertEquals(RootBackend.ULTRA_ENV, RootExec.preferredBackend())
    }

    @Test
    fun backendLabelsAreStableForUi() {
        assertEquals("Ultra 内核", RootBackend.LITE_KERNEL.label)
        assertEquals("Ultra 环境", RootBackend.ULTRA_ENV.label)
        assertEquals("KSU 内核", RootBackend.KSU.label)
        assertEquals("无", RootBackend.NONE.label)
        assertEquals(RootBackend.ULTRA_ENV, RootBackend.valueOf("ULTRA_ENV"))
        assertEquals(RootBackend.KSU, RootBackend.valueOf("KSU"))
    }

    @Test fun aPairedUltraChannelWithAnUnknownProbeNeverStartsTheLegacyCredentialProcess() {
        val lite = FakeExecutor(RootBackend.LITE_KERNEL)
        val ultra = FakeExecutor(RootBackend.ULTRA_ENV, available = false)
        val router = RootExecutionRouter(lite, ultra, modeProvider = { WorkMode.LITE }, ultraIdentity = { true })
        assertFalse(router.execute("install-once").ok)
        assertEquals(0, lite.calls)
        ultra.available = true
        assertTrue(router.execute("next-user-operation").ok)
        assertEquals(1, ultra.calls)
        assertEquals(0, lite.calls)
    }

    @Test fun aFailedUltraMutationIsNotReplayedOnTheLegacyBackend() {
        val lite = FakeExecutor(RootBackend.LITE_KERNEL)
        val ultra = FakeExecutor(RootBackend.ULTRA_ENV, result = RootExecResult(RootBackend.ULTRA_ENV, 137, "", "PROCESS_DIED"))
        val router = RootExecutionRouter(lite, ultra, modeProvider = { WorkMode.LITE }, ultraIdentity = { true })
        assertFalse(router.execute("write-once").ok)
        assertEquals(1, ultra.calls)
        assertEquals(0, lite.calls)
    }

    @Test fun executeReprobesBeforeTheNextOperationWithoutPuttingProbesInCapabilityReads() {
        var probes = 0
        var commands = 0
        var invalidations = 0
        var live = false
        val ultra = UltraEnvRootExecutor(
            runner = { commands++; UltraRootCommandResult(commands > 1, if (commands > 1) 0 else 69) },
            running = { false },
            prepare = { probes++; live },
            onFailure = { invalidations++ },
        )
        val router = RootExecutionRouter(null, ultra, modeProvider = { WorkMode.LITE }, ultraIdentity = { true })
        assertFalse(router.capability().available)
        assertEquals(0, probes)
        assertFalse(router.execute("first").ok)
        assertEquals(0, commands)
        live = true
        assertFalse(router.execute("second").ok)
        assertEquals(1, invalidations)
        assertTrue(router.execute("third").ok)
        assertEquals(3, probes)
        assertEquals(2, commands)
    }

    @Test fun rootCommandsShareOneExecutionSlotAndReleaseItAfterFailure() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val calls = AtomicInteger()
        val ultra = object : RootExecutor {
            override val backend = RootBackend.ULTRA_ENV
            override fun available() = true
            override fun run(script: String): RootExecResult {
                val number = calls.incrementAndGet()
                val current = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, current) }
                try {
                    if (number == 1) { started.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }
                    return RootExecResult(backend, if (number == 1) 137 else 0, "")
                } finally { active.decrementAndGet() }
            }
        }
        val router = RootExecutionRouter(null, ultra, modeProvider = { WorkMode.LITE }, ultraIdentity = { true })
        val firstResult = FutureTask { router.execute("first") }
        val secondStarted = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val secondResult = FutureTask {
            secondStarted.countDown()
            try { router.execute("second") } finally { secondFinished.countDown() }
        }
        val first = Thread(firstResult).apply { start() }
        val second = Thread(secondResult)
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            second.start()
            assertTrue(secondStarted.await(1, TimeUnit.SECONDS))
            assertFalse(secondFinished.await(100, TimeUnit.MILLISECONDS))
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            first.join(3000)
            if (second.state != Thread.State.NEW) second.join(3000)
        }
        assertFalse(firstResult.get(3, TimeUnit.SECONDS).ok)
        assertTrue(secondResult.get(3, TimeUnit.SECONDS).ok)
        assertEquals(2, calls.get())
        assertEquals(1, peak.get())
    }
}
