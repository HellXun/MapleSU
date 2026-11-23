// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自愈编排器 [UltraSelfHealEngine] 的执行序（JVM，全套 fake [UltraSelfHealEngine.Ports]）。
 *
 * <p>重点不是重复 [UltraSelfHealPolicyTest] 的条件矩阵，而是证明：
 *  - 恢复路径真的按「证明 key 被接受 → 写回 store → 补装 → 白名单 → 备份 → 探针」走；
 *  - 任何一步失败都收敛成机器码，**绝不外抛**（启动路径不得被打断）；
 *  - 日志/结果里不出现 key；
 *  - 前置跳过时**不读 root 备份**（避免每次冷启动白跑一次 su）。
 */
class UltraSelfHealEngineTest {

    private val key = "K3x".repeat(16)

    private class FakePorts(
        var modeValue: WorkMode? = WorkMode.LITE,
        var rootUidValue: Int? = 0,
        var storedKeyValue: String? = null,
        var backupSnapshot: UltraSelfHealEngine.BackupSnapshot =
            UltraSelfHealEngine.BackupSnapshot(BackupState.ABSENT),
        var envState: String? = "Running",
        var environmentStateThrows: Boolean = false,
        var installOutcome: UltraEnvironmentInstaller.Outcome =
            UltraEnvironmentInstaller.Outcome(true, "OK"),
        var whitelistResult: UltraSelfWhitelist.Result = UltraSelfWhitelist.Result(
            inList = true,
            added = true,
            alreadyPresent = false,
            attempted = true,
            listVerified = true,
        ),
        var whitelistThrows: Boolean = false,
        var backupOutcome: UltraRecoveryKeyStore.BackupOutcome? =
            UltraRecoveryKeyStore.BackupOutcome(true, skipped = true, reason = UltraRecoveryKeyStore.REASON_ALREADY),
        var probeState: String? = "Running",
        var limitedUntil: Long = 0L,
        var attempted: Boolean = false,
        var quiescingValue: Boolean = false,
        var suspendedValue: Boolean = false,
    ) : UltraSelfHealEngine.Ports {
        var rootUidCalls = 0
        var storedKeyReads = 0
        var backupReads = 0
        var storeCalls = 0
        var storedRunningFlag: Boolean? = null
        var installCalls = 0
        var whitelistCalls = 0
        var backupWrites = 0
        var probeCalls = 0
        var markAttemptedCalls = 0
        var lastWhitelistForce: Boolean? = null
        val records = mutableListOf<String>()
        val logs = mutableListOf<String>()

        override fun mode(): WorkMode? = modeValue

        override fun liteSignals(suRootUid: Int?): UltraLiteEvidence.Signals =
            UltraLiteEvidence.Signals(suRootUid = suRootUid)

        override fun quiescing(): Boolean = quiescingValue

        override fun suspended(): Boolean = suspendedValue

        override fun attemptedThisProcess(): Boolean = attempted

        override fun markAttempted() {
            markAttemptedCalls++
            attempted = true
        }

        override fun rootUid(): Int? {
            rootUidCalls++
            return rootUidValue
        }

        override fun storedKey(): String? {
            storedKeyReads++
            return storedKeyValue
        }

        override fun backup(): UltraSelfHealEngine.BackupSnapshot {
            backupReads++
            return backupSnapshot
        }

        override fun environmentState(key: String): String? {
            if (environmentStateThrows) throw IllegalStateException("sdk exploded")
            return envState
        }

        override fun storeRecoveredKey(key: String, running: Boolean): Boolean {
            storeCalls++
            storedKeyValue = key
            storedRunningFlag = running
            return true
        }

        override fun installEnvironment(key: String): UltraEnvironmentInstaller.Outcome {
            installCalls++
            return installOutcome
        }

        override fun ensureWhitelisted(key: String, force: Boolean): UltraSelfWhitelist.Result {
            whitelistCalls++
            lastWhitelistForce = force
            if (whitelistThrows) throw IllegalStateException("su_auth exploded")
            return whitelistResult
        }

        override fun ensureBackup(key: String): UltraRecoveryKeyStore.BackupOutcome? {
            backupWrites++
            return backupOutcome
        }

        override fun probeEnvironment(): String? {
            probeCalls++
            return probeState
        }

        override fun limitedUntilMillis(nowMillis: Long): Long = limitedUntil

        override fun recordAttempt(nowMillis: Long) {
            records += "attempt"
        }

        override fun recordSuccess(nowMillis: Long, code: String) {
            records += "success:$code"
        }

        override fun recordFailure(nowMillis: Long, code: String) {
            records += "failure:$code"
        }

        override fun log(message: String) {
            logs += message
        }
    }

    private fun engine(ports: FakePorts): UltraSelfHealEngine =
        UltraSelfHealEngine(ports, clock = { 1_000_000L })

    @Test
    fun recoversKeyFromBackupAndRepairsWhitelistBackupProbe() {
        val ports = FakePorts(
            storedKeyValue = null,
            backupSnapshot = UltraSelfHealEngine.BackupSnapshot(BackupState.OK, key),
        )
        val outcome = engine(ports).runOnce()

        assertTrue(outcome.attempted)
        assertTrue(outcome.keyRestored)
        assertEquals("RECOVER_FROM_BACKUP", outcome.action)
        assertEquals(ModeSource.LITE_RUNTIME, outcome.modeSource)
        assertEquals(KeySource.BACKUP, outcome.keySource)
        assertNull(outcome.failure)
        assertEquals("Running", outcome.environment)

        // 写回 store 时环境 Running → 走 pair() 语义（running=true）。
        assertEquals(1, ports.storeCalls)
        assertEquals(true, ports.storedRunningFlag)
        assertEquals(key, ports.storedKeyValue)
        // Running 不补装；白名单/备份/探针各自执行一次。
        assertEquals(0, ports.installCalls)
        assertEquals(1, ports.whitelistCalls)
        assertEquals(1, ports.backupWrites)
        assertEquals(1, ports.probeCalls)
        assertEquals(1, ports.markAttemptedCalls)
        assertTrue(ports.records.contains("attempt"))
        assertTrue(ports.records.any { it == "success:RECOVERED" })
    }

    @Test
    fun installsEnvironmentWhenBackupKeyVerifiesButEnvironmentMissing() {
        val ports = FakePorts(
            storedKeyValue = null,
            backupSnapshot = UltraSelfHealEngine.BackupSnapshot(BackupState.OK, key),
            envState = "NotInstalled",
        )
        val outcome = engine(ports).runOnce()

        assertEquals("RECOVER_FROM_BACKUP", outcome.action)
        // NotInstalled → 只写 key（不置 keyVerified），然后补装。
        assertEquals(false, ports.storedRunningFlag)
        assertEquals(1, ports.installCalls)
        assertEquals("Running", outcome.environment)
        assertNull(outcome.failure)
        assertTrue("install detail missing: ${outcome.detail}", outcome.detail.orEmpty().contains("ok=true"))
    }

    @Test
    fun rejectedBackupKeyNeverOverwritesStore() {
        val ports = FakePorts(
            storedKeyValue = null,
            backupSnapshot = UltraSelfHealEngine.BackupSnapshot(BackupState.OK, key),
            envState = null,
        )
        val outcome = engine(ports).runOnce()

        assertTrue(outcome.attempted)
        assertFalse(outcome.keyRestored)
        assertEquals("BACKUP_KEY_REJECTED", outcome.failure)
        // 未被 SDK 接受的 key 绝不写回本地（否则会把「不认主」变成「认错主」）。
        assertEquals(0, ports.storeCalls)
        assertEquals(0, ports.whitelistCalls)
        assertTrue(ports.records.contains("failure:BACKUP_KEY_REJECTED"))
    }

    @Test
    fun storedKeyNeverWritesAuthorizationOrBackupThroughFaultOrUnknownState() {
        for (state in listOf("Fault", "Unknown", null)) {
            val ports = FakePorts(storedKeyValue = key, envState = state)
            val outcome = engine(ports).runOnce()

            assertEquals("ENVIRONMENT_${state ?: "UNKNOWN"}", outcome.failure)
            assertEquals(0, ports.whitelistCalls)
            assertEquals(0, ports.backupWrites)
            assertEquals(0, ports.installCalls)
            assertEquals(0, ports.storeCalls)
        }
    }

    @Test
    fun backupCandidateNeverReplacesIdentityOrWritesThroughFaultOrUnknownState() {
        for (state in listOf("Fault", "Unknown")) {
            val ports = FakePorts(
                backupSnapshot = UltraSelfHealEngine.BackupSnapshot(BackupState.OK, key),
                envState = state,
            )
            val outcome = engine(ports).runOnce()

            assertFalse(outcome.keyRestored)
            assertEquals("ENVIRONMENT_$state", outcome.failure)
            assertEquals(0, ports.storeCalls)
            assertEquals(0, ports.installCalls)
            assertEquals(0, ports.whitelistCalls)
            assertEquals(0, ports.backupWrites)
        }
    }

    @Test
    fun protectionObservedAfterInstallationStopsRemainingBackgroundWrites() {
        val ports = FakePorts(
            backupSnapshot = UltraSelfHealEngine.BackupSnapshot(BackupState.OK, key),
            envState = "NotInstalled",
            probeState = "Fault",
        )
        val outcome = engine(ports).runOnce()

        assertEquals("ENVIRONMENT_Fault", outcome.failure)
        assertTrue(outcome.keyRestored)
        assertEquals(1, ports.installCalls)
        assertEquals(0, ports.whitelistCalls)
        assertEquals(0, ports.backupWrites)
    }

    @Test
    fun storedKeyOnlyEnsuresWhitelistAndRefreshesBackup() {
        val ports = FakePorts(storedKeyValue = key)
        val outcome = engine(ports).runOnce()

        assertEquals("ENSURE_WHITELIST_AND_BACKUP", outcome.action)
        assertEquals(KeySource.STORE, outcome.keySource)
        assertFalse(outcome.keyRestored)
        assertNull(outcome.failure)
        // 本地 key 已经可用：不读备份、不写 store、不装环境。
        assertEquals(0, ports.backupReads)
        assertEquals(0, ports.storeCalls)
        assertEquals(0, ports.installCalls)
        assertEquals(1, ports.whitelistCalls)
        assertEquals(1, ports.backupWrites)
        assertTrue(ports.records.any { it == "success:WHITELIST_ENSURED" })
        // 根通道正常时保持幂等，不做无意义的 remove+add。
        assertEquals(false, ports.lastWhitelistForce)
    }

    @Test
    fun preflightSkipsWithoutTouchingStoredKeyOrRootBackup() {
        val ksu = FakePorts(modeValue = WorkMode.LITE, storedKeyValue = null)
        val outcome = engine(ksu).runOnce()

        assertFalse(outcome.attempted)
        assertEquals("SKIP:KSU_MODE", outcome.action)
        // 模式/限频等前置不过时不允许读本地 key / 起 root 备份。
        assertEquals(0, ksu.rootUidCalls)
        assertEquals(0, ksu.storedKeyReads)
        assertEquals(0, ksu.backupReads)
        assertEquals(0, ksu.markAttemptedCalls)
    }

    @Test
    fun storedKeyWithoutKeylessRootStillEnsuresWhitelist() {
        // 设备实测补出来的路径：已有 key、环境 Running、但从未进过 su_auth。
        // 此时 su 必然拿不到 uid 0；如果在这里跳过，就永远无法自举出免 key 根通道。
        val ports = FakePorts(rootUidValue = 2000, storedKeyValue = key)
        val outcome = engine(ports).runOnce()

        assertTrue(outcome.attempted)
        assertEquals("ENSURE_WHITELIST_AND_BACKUP", outcome.action)
        assertEquals(1, ports.whitelistCalls)
        // 根通道不可用 → 强制刷新 su_auth（设备实测：重装换 UID 后旧条目失效）。
        assertEquals(true, ports.lastWhitelistForce)
        // 备份走 best-effort：无根时调用方返回 null，但白名单成功即算自愈成功。
        assertEquals(1, ports.backupWrites)
        assertTrue(ports.records.any { it.startsWith("success:") })
    }

    @Test
    fun noRootAndNoStoredKeySkipsWithoutReadingRootBackup() {
        val ports = FakePorts(rootUidValue = 2000, storedKeyValue = null)
        val outcome = engine(ports).runOnce()

        assertFalse(outcome.attempted)
        assertEquals("SKIP:NO_ROOT", outcome.action)
        assertEquals(SelfHealSkip.NO_ROOT.name, outcome.failure)
        // key 必须从 root 备份恢复：没有根通道就不去读（不做无效 su）。
        assertEquals(1, ports.storedKeyReads)
        assertEquals(0, ports.backupReads)
        assertEquals(0, ports.markAttemptedCalls)
    }

    @Test
    fun ksuModeAndRateLimitAndRepeatAreSkipped() {
        val ksu = FakePorts(modeValue = WorkMode.LITE)
        assertEquals("SKIP:KSU_MODE", engine(ksu).runOnce().action)
        assertFalse(ksu.attempted)

        val limited = FakePorts(limitedUntil = 1_000_001L)
        val limitedOutcome = engine(limited).runOnce()
        assertEquals("SKIP:RATE_LIMITED", limitedOutcome.action)
        assertEquals(0, limited.rootUidCalls)
        assertEquals(0, limited.storedKeyReads)

        val repeated = FakePorts(attempted = true, storedKeyValue = key)
        assertEquals("SKIP:ALREADY_ATTEMPTED", engine(repeated).runOnce().action)
        assertEquals(0, repeated.rootUidCalls)
        assertEquals(0, repeated.markAttemptedCalls)
    }

    @Test
    fun quiescingAndSuspendedSkipBeforeAnyRootWork() {
        val quiescing = FakePorts(quiescingValue = true, storedKeyValue = key)
        assertEquals("SKIP:QUIESCING", engine(quiescing).runOnce().action)
        assertEquals(0, quiescing.rootUidCalls)
        assertEquals(0, quiescing.backupReads)

        val suspended = FakePorts(suspendedValue = true, storedKeyValue = key)
        assertEquals("SKIP:SUSPENDED", engine(suspended).runOnce().action)
        assertEquals(0, suspended.rootUidCalls)
        assertEquals(0, suspended.whitelistCalls)
    }

    @Test
    fun exceptionsInPortsNeverEscapeAndAreRecorded() {
        val ports = FakePorts(
            storedKeyValue = null,
            backupSnapshot = UltraSelfHealEngine.BackupSnapshot(BackupState.OK, key),
            environmentStateThrows = true,
        )
        val outcome = engine(ports).runOnce()

        assertTrue(outcome.attempted)
        assertEquals("BACKUP_KEY_REJECTED", outcome.failure)
        assertTrue(ports.records.contains("failure:BACKUP_KEY_REJECTED"))
    }

    @Test
    fun whitelistExceptionCollapsesToFailureNotCrash() {
        val ports = FakePorts(storedKeyValue = key, whitelistThrows = true)
        val outcome = engine(ports).runOnce()

        assertEquals("WHITELIST_FAILED", outcome.failure)
        assertEquals(1, ports.backupWrites)
        assertTrue(ports.records.contains("failure:WHITELIST_FAILED"))
    }

    @Test
    fun recoveredKeyStillGetsAnEnvironmentBackupWhenAuthorizationThrows() {
        val ports = FakePorts(storedKeyValue = null,
            backupSnapshot = UltraSelfHealEngine.BackupSnapshot(BackupState.OK, key),
            whitelistThrows = true)
        val outcome = engine(ports).runOnce()
        assertTrue(outcome.keyRestored)
        assertEquals("WHITELIST_FAILED", outcome.failure)
        assertEquals(1, ports.backupWrites)
    }

    @Test
    fun logsAndSummaryNeverContainRootKey() {
        val ports = FakePorts(
            storedKeyValue = null,
            backupSnapshot = UltraSelfHealEngine.BackupSnapshot(BackupState.OK, key),
        )
        val outcome = engine(ports).runOnce()

        assertFalse(outcome.summary.contains(key))
        assertTrue(ports.logs.isNotEmpty())
        ports.logs.forEach { line ->
            assertFalse("log leaked key: $line", line.contains(key))
            assertFalse(UltraSdkLogging.containsKeyLike(line))
        }
    }

    @Test
    fun missingBackupIsReportedAsNoKeyWithoutGuessing() {
        val ports = FakePorts(
            storedKeyValue = null,
            backupSnapshot = UltraSelfHealEngine.BackupSnapshot(BackupState.ABSENT),
        )
        val outcome = engine(ports).runOnce()

        assertFalse(outcome.attempted)
        assertEquals("SKIP:NO_KEY", outcome.action)
        assertEquals(0, ports.storeCalls)
    }
}

/**
 * 模式等待：超时**绝不默认 LITE**（避免在 KSU 或未判定设备上误写 Ultra 状态）。
 */
class UltraSelfHealAwaitModeTest {

    @Test
    fun returnsResolvedModeWithoutWaiting() {
        var sleeps = 0
        val mode = UltraSelfHeal.awaitModeResolved(
            timeoutMillis = 1_000,
            sleeper = { sleeps++ },
            modeProvider = { WorkMode.LITE },
        )
        assertEquals(WorkMode.LITE, mode)
        assertEquals(0, sleeps)
    }

    @Test
    fun timeoutReturnsNullInsteadOfDefaultingToLite() {
        val mode = UltraSelfHeal.awaitModeResolved(
            timeoutMillis = 30,
            pollMillis = 10,
            sleeper = { Thread.sleep(10) },
            modeProvider = { null },
        )
        assertNull(mode)
    }

    @Test
    fun providerExceptionIsNotTreatedAsResolved() {
        val mode = UltraSelfHeal.awaitModeResolved(
            timeoutMillis = 0,
            sleeper = {},
            modeProvider = { throw IllegalStateException("boom") },
        )
        assertNull(mode)
    }

    @Test
    fun eventuallyResolvesAfterSleeping() {
        var calls = 0
        val mode = UltraSelfHeal.awaitModeResolved(
            timeoutMillis = 1_000,
            pollMillis = 100,
            sleeper = {},
            modeProvider = { if (++calls >= 3) WorkMode.LITE else null },
        )
        assertEquals(WorkMode.LITE, mode)
    }
}
