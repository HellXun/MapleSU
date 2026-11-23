// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自愈触发条件矩阵 + 限频 + 执行序（JVM，全 fake）。
 *
 * 覆盖需求 2 的 a–e：模式、根状态、环境状态、key/备份来源、防环限频、失败不外抛。
 */
class UltraSelfHealPolicyTest {

    private fun facts(
        mode: WorkMode? = WorkMode.LITE,
        evidence: UltraLiteEvidence.Result = UltraLiteEvidence.Result(true, listOf("SU_ROOT")),
        quiescing: Boolean = false,
        suspended: Boolean = false,
        attempted: Boolean = false,
        now: Long = 1_000_000L,
        limitedUntil: Long = 0L,
        rootUid: Int? = 0,
        key: StoredKeyState = StoredKeyState.ABSENT,
        backup: BackupState = BackupState.ABSENT,
    ) = UltraSelfHealPolicy.Facts(
        mode = mode,
        liteEvidence = evidence,
        quiescing = quiescing,
        suspended = suspended,
        attemptedThisProcess = attempted,
        nowMillis = now,
        limitedUntilMillis = limitedUntil,
        rootUid = rootUid,
        storedKeyState = key,
        backup = backup,
    )

    @Test
    fun secondAttemptInSameProcessIsSkipped() {
        val decision = UltraSelfHealPolicy.decide(facts(attempted = true, key = StoredKeyState.OK))
        assertEquals(SelfHealDecision.Skip(SelfHealSkip.ALREADY_ATTEMPTED), decision)
    }

    @Test
    fun ksuModeNeverActs() {
        val decision = UltraSelfHealPolicy.decide(facts(mode = WorkMode.LITE, rootUid = 0))
        assertEquals(SelfHealSkip.KSU_MODE, (decision as SelfHealDecision.Skip).reason)
    }

    @Test
    fun unresolvedModeWithoutDiskEvidenceIsSkipped() {
        val decision = UltraSelfHealPolicy.decide(
            facts(
                mode = null,
                evidence = UltraLiteEvidence.Result(false, listOf("NO_KEYLESS_ROOT")),
                key = StoredKeyState.OK,
            ),
        )
        assertEquals(SelfHealSkip.MODE_UNRESOLVED, (decision as SelfHealDecision.Skip).reason)
    }

    @Test
    fun unresolvedModeWithDiskEvidenceProceedsAsLite() {
        val decision = UltraSelfHealPolicy.decide(
            facts(
                mode = null,
                evidence = UltraLiteEvidence.Result(true, listOf("SU_ROOT", "MODE_FILE_A")),
                key = StoredKeyState.OK,
            ),
        ) as SelfHealDecision.Proceed
        assertEquals(ModeSource.LITE_DISK_EVIDENCE, decision.modeSource)
        assertEquals(SelfHealAction.ENSURE_WHITELIST_AND_BACKUP, decision.action)
    }

    @Test
    fun quiescingAndSuspendedAreSkipped() {
        assertEquals(
            SelfHealSkip.QUIESCING,
            (UltraSelfHealPolicy.decide(facts(quiescing = true, key = StoredKeyState.OK)) as SelfHealDecision.Skip).reason,
        )
        assertEquals(
            SelfHealSkip.SUSPENDED,
            (UltraSelfHealPolicy.decide(facts(suspended = true, key = StoredKeyState.OK)) as SelfHealDecision.Skip).reason,
        )
    }

    @Test
    fun rateLimitWindowBlocksThenExpires() {
        val limited = UltraSelfHealPolicy.decide(
            facts(now = 1_000L, limitedUntil = 2_000L, key = StoredKeyState.OK),
        )
        assertEquals(SelfHealSkip.RATE_LIMITED, (limited as SelfHealDecision.Skip).reason)

        val expired = UltraSelfHealPolicy.decide(
            facts(now = 2_000L, limitedUntil = 2_000L, key = StoredKeyState.OK),
        ) as SelfHealDecision.Proceed
        assertEquals(SelfHealAction.ENSURE_WHITELIST_AND_BACKUP, expired.action)
    }

    @Test
    fun noKeylessRootChannelBlocksRecovery() {
        listOf<Int?>(null, 2000, 10123).forEach { uid ->
            val decision = UltraSelfHealPolicy.decide(facts(rootUid = uid, backup = BackupState.OK))
            assertEquals(
                "uid=$uid must not proceed",
                SelfHealSkip.NO_ROOT,
                (decision as SelfHealDecision.Skip).reason,
            )
        }
    }

    @Test
    fun storedKeyOnlyEnsuresWhitelistAndBackup() {
        val decision = UltraSelfHealPolicy.decide(facts(key = StoredKeyState.OK)) as SelfHealDecision.Proceed
        assertEquals(SelfHealAction.ENSURE_WHITELIST_AND_BACKUP, decision.action)
        assertEquals(KeySource.STORE, decision.keySource)
        assertFalse(decision.installEnvironmentIfMissing)
    }

    @Test
    fun storedKeyDoesNotRequireKeylessRootToEnsureWhitelist() {
        // 设备实测：已有 key + 环境 Running + 从未进过 su_auth 时，su 拿不到 uid 0；
        // 白名单只需要 SDK+key，不能被 NO_ROOT 拦掉（否则永远无法自举）。
        listOf<Int?>(null, 2000, 10123).forEach { uid ->
            val decision = UltraSelfHealPolicy.decide(
                facts(key = StoredKeyState.OK, rootUid = uid),
            ) as SelfHealDecision.Proceed
            assertEquals(SelfHealAction.ENSURE_WHITELIST_AND_BACKUP, decision.action)
            assertEquals(KeySource.STORE, decision.keySource)
        }
    }

    @Test
    fun absentKeyWithoutBackupIsBlocked() {
        assertEquals(
            SelfHealSkip.NO_KEY,
            (UltraSelfHealPolicy.decide(facts(key = StoredKeyState.ABSENT)) as SelfHealDecision.Skip).reason,
        )
    }

    @Test
    fun unreadableOrForeignBackupIsReportedHonestly() {
        assertEquals(
            SelfHealSkip.BACKUP_UNREADABLE,
            (UltraSelfHealPolicy.decide(
                facts(key = StoredKeyState.ABSENT, backup = BackupState.UNREADABLE),
            ) as SelfHealDecision.Skip).reason,
        )
        assertEquals(
            SelfHealSkip.BACKUP_INVALID,
            (UltraSelfHealPolicy.decide(
                facts(key = StoredKeyState.ABSENT, backup = BackupState.INVALID),
            ) as SelfHealDecision.Skip).reason,
        )
    }

    @Test
    fun backupKeyTriggersRecoveryWithInstallWhenMissing() {
        val decision = UltraSelfHealPolicy.decide(
            facts(key = StoredKeyState.ABSENT, backup = BackupState.OK),
        ) as SelfHealDecision.Proceed
        assertEquals(SelfHealAction.RECOVER_FROM_BACKUP, decision.action)
        assertEquals(KeySource.BACKUP, decision.keySource)
        assertTrue(decision.installEnvironmentIfMissing)
        assertFalse(decision.replaceMalformedKey)
    }

    @Test
    fun malformedStoredKeyIsReplacedByBackup() {
        val decision = UltraSelfHealPolicy.decide(
            facts(key = StoredKeyState.MALFORMED, backup = BackupState.OK),
        ) as SelfHealDecision.Proceed
        assertEquals(SelfHealAction.RECOVER_FROM_BACKUP, decision.action)
        assertTrue(decision.replaceMalformedKey)
    }
}

/** 免 key 判据（[UltraLiteEvidence]）与脚本解析。 */
class UltraLiteEvidenceTest {

    @Test
    fun noKeylessRootIsNeverStrong() {
        val result = UltraLiteEvidence.evaluate(
            UltraLiteEvidence.Signals(suRootUid = 2000, modeFileCurrent = "A"),
        )
        assertFalse(result.strong)
        assertTrue(result.reasons.contains("NO_KEYLESS_ROOT"))
    }

    @Test
    fun modeFileAWins() {
        val result = UltraLiteEvidence.evaluate(
            UltraLiteEvidence.Signals(suRootUid = 0, modeFileCurrent = "A"),
        )
        assertTrue(result.strong)
        assertTrue(result.reasons.contains("MODE_FILE_A"))
    }

    @Test
    fun modeFileBBlocksEvenWithRoot() {
        val result = UltraLiteEvidence.evaluate(
            UltraLiteEvidence.Signals(suRootUid = 0, modeFileCurrent = "B", modeADirectoryPresent = true),
        )
        assertFalse(result.strong)
        assertTrue(result.reasons.contains("MODE_FILE_B"))
    }

    @Test
    fun modeDirectoryOrUltraEnvironmentFilesCorroborate() {
        assertTrue(
            UltraLiteEvidence.evaluate(
                UltraLiteEvidence.Signals(suRootUid = 0, modeADirectoryPresent = true),
            ).strong,
        )
        assertTrue(
            UltraLiteEvidence.evaluate(
                UltraLiteEvidence.Signals(suRootUid = 0, ultraEnvironmentMarker = true),
            ).strong,
        )
    }

    @Test
    fun rootAloneIsNotEnough() {
        val result = UltraLiteEvidence.evaluate(UltraLiteEvidence.Signals(suRootUid = 0))
        assertFalse(result.strong)
    }

    @Test
    fun signalScriptParsingIsMarkerDriven() {
        val output = """
            M_MODE_FILE
            {"schema":1,"current":"A","migration":{"current":"B"}}
            M_MODE_A_DIR
            YES
            M_ULTRA_ENV
            NO
            M_END
        """.trimIndent()
        val signals = UltraLiteEvidence.parseSignals(output, 0)
        assertEquals("A", signals.modeFileCurrent)
        assertEquals(true, signals.modeADirectoryPresent)
        assertEquals(false, signals.ultraEnvironmentMarker)
        assertTrue(UltraLiteEvidence.evaluate(signals).strong)
    }

    @Test
    fun missingOutputYieldsNoSignals() {
        val signals = UltraLiteEvidence.parseSignals(null, 0)
        assertEquals(null, signals.modeFileCurrent)
        assertNull(signals.modeADirectoryPresent)
        assertFalse(UltraLiteEvidence.evaluate(signals).strong)
    }
}

/** 失败退避阶梯。 */
class UltraSelfHealBackoffTest {

    @Test
    fun ladderStartsAtFifteenMinutesAndCapsAtOneDay() {
        val minute = 60_000L
        assertEquals(15 * minute, UltraSelfHealBackoff.delayMillis(1))
        assertEquals(60 * minute, UltraSelfHealBackoff.delayMillis(2))
        assertEquals(6 * 60 * minute, UltraSelfHealBackoff.delayMillis(3))
        assertEquals(24 * 60 * minute, UltraSelfHealBackoff.delayMillis(4))
        assertEquals(24 * 60 * minute, UltraSelfHealBackoff.delayMillis(40))
        assertEquals(0L, UltraSelfHealBackoff.delayMillis(0))
    }

    @Test
    fun limitedWindowUsesLastFailureAndCount() {
        val now = 10_000_000L
        assertEquals(0L, UltraSelfHealBackoff.limitedUntilMillis(0L, 0))
        assertEquals(now + 900_000L, UltraSelfHealBackoff.limitedUntilMillis(now, 1))
        assertFalse(UltraSelfHealBackoff.isAllowed(now + 899_999L, now, 1))
        assertTrue(UltraSelfHealBackoff.isAllowed(now + 900_000L, now, 1))
    }
}

/** `su` 命令行/输出解析与超时语义。 */
class UltraSuChannelTest {

    @Test
    fun uidParsingHandlesBothIdForms() {
        assertEquals(0, SuCommands.parseUid("0\n"))
        assertEquals(0, SuCommands.parseUid("uid=0(root) gid=0(root) groups=0(root)"))
        assertEquals(2000, SuCommands.parseUid("2000\n"))
        assertNull(SuCommands.parseUid(""))
        assertNull(SuCommands.parseUid("not found"))
    }

    @Test
    fun candidatesStartWithSystemSuAndFewAttempts() {
        val candidates = SuCommands.probeCandidates()
        assertEquals(listOf("/system/bin/su", "-c", "id -u"), candidates.first())
        assertTrue(candidates.size <= 5)
    }

    @Test
    fun rootProbeReportsZeroFromFakeExec() {
        val channel = UltraSuChannel(exec = { _, _, _ -> SuExecResult(0, "0\n") })
        assertEquals(0, channel.rootUid())
        assertNull(channel.lastFailure)
    }

    @Test
    fun nonRootUidIsNotTreatedAsRoot() {
        val channel = UltraSuChannel(exec = { _, _, _ -> SuExecResult(0, "2000\n") })
        assertNull(channel.rootUid())
        assertTrue(channel.lastFailure.orEmpty().startsWith("SU_NOT_ROOT"))
    }

    @Test
    fun timeoutIsReportedAndNeverThrows() {
        val channel = UltraSuChannel(exec = { _, _, _ -> SuExecResult(-1, "", timedOut = true) })
        assertNull(channel.rootUid())
        assertEquals("SU_TIMEOUT", channel.lastFailure)
    }

    @Test
    fun execExceptionCollapsesToNull() {
        val channel = UltraSuChannel(exec = { _, _, _ -> throw IllegalStateException("boom") })
        assertNull(channel.rootUid())
        assertNull(channel.runRoot("id"))
    }

    @Test
    fun runRootUsesDiscoveredPathAndReturnsOutput() {
        var seen: List<String>? = null
        val channel = UltraSuChannel(
            exec = { argv, _, _ ->
                seen = argv
                SuExecResult(0, if (argv.last() == "id -u") "0\n" else "hello")
            },
        )
        assertEquals(0, channel.rootUid())
        val result = channel.runRoot("echo hello")
        assertEquals("hello", result?.output)
        assertEquals(listOf("/system/bin/su", "-c", "echo hello"), seen)
    }
}
