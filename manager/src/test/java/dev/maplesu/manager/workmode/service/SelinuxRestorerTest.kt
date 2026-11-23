package dev.maplesu.manager.workmode.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-1 第 1 项：SELinux 自动恢复的纯逻辑单测（全部 fake 根通道，无 Android / 无 root / 无设备）。
 *
 * 断言的核心语义：
 * - 入口已 `Enforcing` → **零根调用**（不在每次切换里 setenforce）；
 * - 非 Enforcing → 只执行一条自包含脚本（`setenforce 1` + 兜底写 enforce 文件 + 回读）；
 * - 回读拿不到 `Enforcing`/`1` → 如实返回真实状态（**绝不伪造成功**）；
 * - 恢复成功后用**同一通道**做 `id -u` 活性探针，结果由 [SelinuxRestoreResult.rootAlive] 带出。
 */
class SelinuxRestorerTest {

    private class FakeChannel(
        private val enforceOutput: String = "Enforcing",
        private val livenessOutput: String = "0",
        private val fail: Boolean = false,
    ) : SelinuxRootChannel {
        val scripts = mutableListOf<String>()

        override fun run(script: String): SelinuxShellResult {
            scripts += script
            if (fail) return SelinuxShellResult(false, "", 1, "DENIED")
            return if (script.contains("setenforce")) {
                SelinuxShellResult(true, enforceOutput)
            } else {
                SelinuxShellResult(true, livenessOutput)
            }
        }
    }

    @Test
    fun alreadyEnforcingDoesNotTouchRoot() {
        val ultra = FakeChannel()
        val result = AndroidSelinuxRestorer(ultra) { "Enforcing" }.ensureEnforcing()
        assertTrue(result.enforcing)
        assertFalse("已 Enforcing 不得执行任何根命令", result.attempted)
        assertTrue(ultra.scripts.isEmpty())
    }

    @Test
    fun permissiveRunsSetenforceAndConfirmsByReadBack() {
        val ultra = FakeChannel(enforceOutput = "Enforcing")
        val result = AndroidSelinuxRestorer(ultra) { "Permissive" }.ensureEnforcing()

        assertTrue(result.enforcing)
        assertTrue(result.attempted)
        assertTrue("恢复成功后必须做 id -u 活性探针", result.rootAlive)
        assertEquals(2, ultra.scripts.size)

        val script = ultra.scripts.first()
        assertTrue("必须包含 setenforce 1: $script", script.contains("setenforce 1"))
        assertTrue("必须有 enforce 文件兜底: $script", script.contains("/sys/fs/selinux/enforce"))
        assertTrue("必须回读 getenforce: $script", script.contains("getenforce"))
        assertTrue("必须有 </dev/null: $script", script.contains("</dev/null"))
        assertFalse("必须是单行脚本: $script", script.contains("\n"))
        assertEquals(AndroidSelinuxRestorer.LIVENESS_SCRIPT, ultra.scripts[1])
    }

    @Test
    fun enforceFileOneIsAcceptedAsReadBack() {
        // setenforce 被域策略拒绝、写 enforce 文件成功时，回读是 "1"。
        val result = AndroidSelinuxRestorer(FakeChannel(enforceOutput = "1")) { "Permissive" }
            .ensureEnforcing()
        assertTrue(result.enforcing)
        assertTrue(result.rootAlive)
    }

    @Test
    fun readBackNotEnforcingIsFailure() {
        val result = AndroidSelinuxRestorer(FakeChannel(enforceOutput = "Permissive")) { "Permissive" }
            .ensureEnforcing()
        assertFalse("回读仍是 Permissive 不得当成功", result.enforcing)
        assertEquals(SelinuxRestoreResult.PERMISSIVE, result.status)
        assertTrue(result.attempted)
    }

    @Test
    fun blankReadBackIsNotSuccess() {
        // 命令 rc=0 但输出空 → 不猜、不算成功。
        val result = AndroidSelinuxRestorer(FakeChannel(enforceOutput = "")) { "Unknown" }
            .ensureEnforcing()
        assertFalse(result.enforcing)
        assertEquals(SelinuxRestoreResult.UNKNOWN, result.status)
    }

    @Test
    fun livenessFailureIsReportedButStatusStaysTruthful() {
        val result = AndroidSelinuxRestorer(
            FakeChannel(enforceOutput = "Enforcing", livenessOutput = "1000"),
        ) { "Permissive" }.ensureEnforcing()
        // 状态确实变成 Enforcing，但根通道已不可用 → facade 必须报 SWITCH_ROOT_UNAVAILABLE。
        assertTrue(result.enforcing)
        assertTrue(result.attempted)
        assertFalse("id -u != 0 必须体现为 rootAlive=false", result.rootAlive)
    }

    @Test
    fun allChannelsFailReturnsRealStateNotFakeSuccess() {
        val ultra = FakeChannel(fail = true)
        val result = AndroidSelinuxRestorer(ultra) { "Permissive" }.ensureEnforcing()

        assertFalse(result.enforcing)
        assertEquals(SelinuxRestoreResult.PERMISSIVE, result.status)
        assertTrue(result.attempted)
        assertFalse(result.rootAlive)
    }

    @Test
    fun localProbeThrowingDoesNotEscape() {
        // 探测异常不得让切换入口崩溃：按 Unknown 处理并走根通道。
        val result = AndroidSelinuxRestorer(FakeChannel(enforceOutput = "Enforcing")) {
            error("probe boom")
        }.ensureEnforcing()
        assertTrue(result.enforcing)
        assertTrue(result.rootAlive)
    }

    // ------------------------------------------------------------------ M-2：UNWIRED 哨兵

    @Test
    fun unwiredSentinelIsNeitherEnforcingNorAttempted() {
        // 空 status 必须可被门面识别为"未接线"，绝不能当成"修好了"。
        assertTrue(SelinuxRestoreResult.UNWIRED.status.isEmpty())
        assertFalse(SelinuxRestoreResult.UNWIRED.enforcing)
        assertFalse(SelinuxRestoreResult.UNWIRED.attempted)
    }

    @Test
    fun unwiredRestorerIsTheSentinelAndNeverReportsEnforcing() {
        // 门面按 M-2 规则消费它：已 Enforcing ⇒ 继续；非 Enforcing ⇒ 显式失败。
        val result = SelinuxRestorer.UNWIRED.ensureEnforcing()
        assertTrue(result.status.isEmpty())
        assertFalse(result.enforcing)
        assertFalse("未接线不得声称执行过根命令", result.attempted)
    }

    @Test
    fun channelThrowingIsFailureNotFakeSuccess() {
        val throwing = SelinuxRootChannel { error("channel boom") }
        val result = AndroidSelinuxRestorer(throwing) { "Permissive" }.ensureEnforcing()
        assertFalse("通道抛异常绝不算成功", result.enforcing)
        assertTrue(result.attempted)
    }

    // ------------------------------------------------------------------ M-3：硬超时（绝不永挂）

    @Test
    fun hardTimeoutBoundsARunawayRootCall() {
        // KsuInstallGlue.kt:207-228 从不使用 timeoutMs（死参数），所以恢复器自带看护：
        // 一个永不返回的根调用必须在超时后返回失败，而不是把 startSwitch 永久挂在 RUNNING。
        val startedNs = System.nanoTime()
        val result = runWithTimeout(80L) {
            Thread.sleep(30_000L)
            SelinuxShellResult(true, "Enforcing")
        }
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
        assertFalse("超时必须算失败，绝不伪造成功", result.ok)
        assertEquals(TIMEOUT_DETAIL, result.detail)
        assertTrue("必须在超时附近返回（实际 ${elapsedMs}ms）", elapsedMs < 5_000L)
    }

    @Test
    fun hardTimeoutPassesThroughFastResults() {
        val result = runWithTimeout(5_000L) { SelinuxShellResult(true, "Enforcing", 0) }
        assertTrue(result.ok)
        assertEquals("Enforcing", result.output)
    }

    @Test
    fun hardTimeoutConvertsThrowingCallIntoFailure() {
        val result = runWithTimeout(5_000L) { error("root call boom") }
        assertFalse(result.ok)
        assertTrue("detail 必须可排障: ${result.detail}", result.detail.isNotEmpty())
    }
}
