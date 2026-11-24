package dev.maplesu.manager.litehidden

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H3 Lite 隐藏模式纯策略测试（JVM，≥18）。
 *
 * 覆盖：状态矩阵 / ON 隐藏入口 / OFF 等价基线 / KSU 不显示此卡 /
 * 密令路径∪并集 / 限流 / 四态文案 / 痕迹白名单与禁止删除项 /
 * 路径脚本（不碰 KSU 的 /data/adb/ksu）/ 默认关闭。
 */
class LiteHiddenPolicyTest {

    // ── 状态矩阵 ──────────────────────────────────────────────────────────

    @Test
    fun stateMatrixAndConservativeUnknown() {
        assertFalse(LiteHiddenState.OFF.isEffectivelyOn)
        assertTrue(LiteHiddenState.ON.isEffectivelyOn)
        assertTrue("UNKNOWN 保守按 ON", LiteHiddenState.UNKNOWN.isEffectivelyOn)
        assertEquals(LiteHiddenState.ON, LiteHiddenState.fromWire("1\n"))
        assertEquals(LiteHiddenState.OFF, LiteHiddenState.fromWire("0"))
        assertEquals(LiteHiddenState.UNKNOWN, LiteHiddenState.fromWire(null))
        assertEquals(LiteHiddenState.UNKNOWN, LiteHiddenState.fromWire("garbage"))
    }

    @Test
    fun liteOffIsByteBehaviourBaseline() {
        val ui = LiteHiddenPolicy.evaluate(WorkMode.LITE, LiteHiddenState.OFF)
        assertTrue("A 显示隐藏卡", ui.cardVisible)
        assertFalse(ui.disguisedAsNotInstalled)
        assertTrue(ui.showSuperUserEntry && ui.showModuleEntry && ui.showRootSettings)
        assertTrue(ui.showDirectInstall && ui.showUltraEnvironment)
        assertTrue(ui.showModuleActions && ui.showRecoveryEntry)
        assertFalse("桌面图标永不隐藏", ui.allowLauncherAliasToggle)
        assertEquals(LiteHiddenPolicy.BASELINE.copy(cardVisible = true), ui)
    }

    @Test
    fun liteOnHidesRootEntriesAndDisguisesNotInstalled() {
        val ui = LiteHiddenPolicy.evaluate(WorkMode.LITE, LiteHiddenState.ON)
        assertTrue(ui.disguisedAsNotInstalled)
        assertFalse(ui.showSuperUserEntry)
        assertFalse(ui.showModuleEntry)
        assertFalse(ui.showRootSettings)
        assertFalse(ui.showDirectInstall)
        assertFalse(ui.showUltraEnvironment)
        assertFalse(ui.showModuleActions)
        assertFalse(ui.showRecoveryEntry)
        assertFalse("桌面图标不动", ui.allowLauncherAliasToggle)
    }

    @Test
    fun unknownTreatedAsOnConservatively() {
        assertEquals(
            LiteHiddenPolicy.evaluate(WorkMode.LITE, LiteHiddenState.ON),
            LiteHiddenPolicy.evaluate(WorkMode.LITE, LiteHiddenState.UNKNOWN),
        )
    }

    @Test
    fun ksuModeNeverShowsLiteHiddenCard() {
        for (state in LiteHiddenState.entries) {
            val ui = LiteHiddenPolicy.evaluate(WorkMode.LITE, state)
            assertFalse("B 不显示 Lite 隐藏卡", ui.cardVisible)
            assertFalse("B 不用 Lite App 级伪装", ui.disguisedAsNotInstalled)
            assertTrue(ui.showSuperUserEntry)
        }
        assertFalse(LiteHiddenPolicy.masksAsNotInstalled(WorkMode.LITE, LiteHiddenState.ON))
    }

    @Test
    fun unresolvedModeShowsNoCardAndNoDisguise() {
        val ui = LiteHiddenPolicy.evaluate(null, LiteHiddenState.ON)
        assertFalse(ui.cardVisible)
        assertFalse(ui.disguisedAsNotInstalled)
    }

    @Test
    fun allowsRootFeatureFollowsVisibility() {
        for (feature in RootFeature.entries) {
            assertTrue("OFF 允许 $feature", LiteHiddenPolicy.allowsRootFeature(WorkMode.LITE, LiteHiddenState.OFF, feature))
            assertFalse("ON 禁止 $feature", LiteHiddenPolicy.allowsRootFeature(WorkMode.LITE, LiteHiddenState.ON, feature))
            assertTrue(
                "B 不改变 $feature",
                LiteHiddenPolicy.allowsRootFeature(WorkMode.LITE, LiteHiddenState.ON, feature),
            )
        }
    }

    // ── 密令：路径 / 并集 / 限流 / 四态文案 ──────────────────────────────

    @Test
    fun liteHiddenPathsAreAppLevelAndNotUnderKsu() {
        assertEquals("/data/adb/maplesu/lite_hidden", LiteHiddenPaths.STATE_PATH)
        assertEquals("/data/adb/maplesu/lite_hidden_code", LiteHiddenPaths.CODE_PATH)
        assertFalse(LiteHiddenPaths.STATE_PATH.contains("/data/adb/ksu"))
        assertFalse(LiteHiddenPaths.CODE_PATH.contains("/data/adb/ksu"))
    }

    @Test
    fun stateWriteScriptTargetsStatePathAndReportsOk() {
        val script = LiteHiddenPaths.stateScript(write = true, value = "1")
        assertTrue(script.contains(LiteHiddenPaths.STATE_PATH))
        assertTrue(script.contains("LITE_OK"))
        assertTrue(script.contains("chmod 600"))
        // 读脚本只 cat，不写。
        val read = LiteHiddenPaths.stateScript()
        assertTrue(read.contains("cat "))
    }

    @Test
    fun codeWriteScriptFiltersToDigits() {
        val script = LiteHiddenPaths.codeScript(write = true, code = "12ab34")
        assertTrue(script.contains("1234"))
        assertFalse(script.contains("12ab34"))
    }

    @Test
    fun codeSanitizeKeepsDigitsAndMaxLength() {
        assertEquals("123456", LiteHiddenCodePolicy.sanitize(" 12a34b56 "))
        assertEquals(12, LiteHiddenCodePolicy.sanitize("1".repeat(30))!!.length)
        assertNull(LiteHiddenCodePolicy.sanitize("abc"))
        assertNull(LiteHiddenCodePolicy.sanitize(null))
    }

    @Test
    fun acceptedCodesUnionOfDiskAndPrefs() {
        assertEquals(setOf("111", "222"), LiteHiddenCodePolicy.acceptedCodes("111", "222"))
        assertEquals(setOf("111"), LiteHiddenCodePolicy.acceptedCodes("111", null))
        assertEquals(setOf("222"), LiteHiddenCodePolicy.acceptedCodes(null, "222"))
        // 两边都没配才用默认值。
        assertEquals(setOf(LiteHiddenCodePolicy.DEFAULT_CODE), LiteHiddenCodePolicy.acceptedCodes(null, null))
    }

    @Test
    fun matchesAnySourceButNotMismatch() {
        assertTrue(LiteHiddenCodePolicy.matches("111", "111", null))
        assertTrue("disk 写入失败时 prefs 仍能开锁", LiteHiddenCodePolicy.matches("222", "111", "222"))
        assertFalse(LiteHiddenCodePolicy.matches("333", "111", "222"))
        assertFalse(LiteHiddenCodePolicy.matches(null, "111", "222"))
    }

    @Test
    fun userCodeNormalizationBounds() {
        assertEquals("123", LiteHiddenCodePolicy.normalizeUserCode("123"))
        assertNull(LiteHiddenCodePolicy.normalizeUserCode("12"))
        assertNull(LiteHiddenCodePolicy.normalizeUserCode("1".repeat(13)))
        assertNull(LiteHiddenCodePolicy.normalizeUserCode("abc"))
        assertEquals("*#*#70707#*#*", LiteHiddenCodePolicy.codeHint(LiteHiddenCodePolicy.DEFAULT_CODE))
    }

    @Test
    fun restoreOutcomeFourStates() {
        // 非 Ultra 模式
        assertEquals(
            LiteHiddenOutcome.NOT_SUPPORTED,
            LiteHiddenCodePolicy.outcomeFor(false, true, "70707", "70707", null),
        )
        // 隐藏未开启
        assertEquals(
            LiteHiddenOutcome.NOT_SUPPORTED,
            LiteHiddenCodePolicy.outcomeFor(true, false, "70707", "70707", null),
        )
        // 密令不匹配
        assertEquals(
            LiteHiddenOutcome.NOT_AUTHORIZED,
            LiteHiddenCodePolicy.outcomeFor(true, true, "9", "70707", null),
        )
        // 写入失败
        assertEquals(
            LiteHiddenOutcome.FAILED,
            LiteHiddenCodePolicy.outcomeFor(true, true, "70707", "70707", null, writeSucceeded = false),
        )
        // 成功
        assertEquals(
            LiteHiddenOutcome.SUCCESS,
            LiteHiddenCodePolicy.outcomeFor(true, true, "70707", null, "70707"),
        )
        assertEquals(4, LiteHiddenOutcome.entries.map { LiteHiddenCodePolicy.messageKey(it) }.toSet().size)
    }

    @Test
    fun rateLimiterBlocksAfterMaxFailuresAndResetsOnSuccess() {
        var now = 0L
        val limiter = LiteHiddenRateLimiter(maxFailures = 3, blockMillis = 1_000, clock = { now })
        assertFalse(limiter.isBlocked())
        assertFalse(limiter.recordFailure())
        assertFalse(limiter.recordFailure())
        assertTrue("第 3 次失败后进入冷却", limiter.recordFailure())
        assertTrue(limiter.isBlocked())
        // 冷却内继续失败不重置窗口
        assertTrue(limiter.recordFailure())
        now = 1_001
        assertFalse("冷却结束", limiter.isBlocked())
        limiter.recordSuccess()
        assertEquals(0, limiter.failureCount())
    }

    // ── 痕迹收敛：白名单 / 禁止项 / 默认关 ──────────────────────────────

    @Test
    fun traceDefaultsAreAllOff() {
        assertTrue(LiteHiddenTracePolicy.defaults().values.all { !it })
        assertEquals(LiteHiddenTraceItem.entries.size, LiteHiddenTracePolicy.defaults().size)
        assertTrue(LiteHiddenTracePolicy.selectionFromWire(null).values.all { !it })
    }

    @Test
    fun traceWireRoundTrip() {
        val selection = LiteHiddenTracePolicy.selectionFromWire(
            setOf(LiteHiddenTraceItem.LOGCAT_TAGS.wire, LiteHiddenTraceItem.DEBUG_EXPORTS.wire, "unknown"),
        )
        assertTrue(selection[LiteHiddenTraceItem.LOGCAT_TAGS] == true)
        assertTrue(selection[LiteHiddenTraceItem.DEBUG_EXPORTS] == true)
        assertFalse(selection[LiteHiddenTraceItem.RECENT_INSTALL_LOG] == true)
        assertEquals(
            setOf(LiteHiddenTraceItem.LOGCAT_TAGS.wire, LiteHiddenTraceItem.DEBUG_EXPORTS.wire),
            LiteHiddenTracePolicy.toWire(selection),
        )
    }

    @Test
    fun canDeleteOnlyAppPrivateWhitelistDirs() {
        val root = "/data/user/0/com.maplesu.core/files"
        assertTrue(LiteHiddenTracePolicy.canDelete(root, "$root/logs/a.log"))
        assertTrue(LiteHiddenTracePolicy.canDelete(root, "$root/diagnostics/a.json"))
        assertTrue(LiteHiddenTracePolicy.canDelete(root, "$root/debug-exports/x.txt"))
        assertFalse("模块实体禁止", LiteHiddenTracePolicy.canDelete(root, "$root/module-catalog/a"))
        assertFalse("noBackup 凭证禁止", LiteHiddenTracePolicy.canDelete(root, "$root/../no_backup/maplesu-credential.bin"))
        assertFalse("app 根目录本身不在白名单", LiteHiddenTracePolicy.canDelete(root, "$root/other/a"))
        assertFalse("目录穿越拒绝", LiteHiddenTracePolicy.canDelete(root, "$root/logs/../../etc/x"))
        assertFalse("路径不在 filesDir 下", LiteHiddenTracePolicy.canDelete(root, "/data/adb/maplesu/lite_hidden"))
    }

    @Test
    fun forbiddenPatternsCoverCredentialsKernelBackupsModules() {
        assertTrue(LiteHiddenTracePolicy.isForbidden("/x/maplesu-credential.bin"))
        assertTrue(LiteHiddenTracePolicy.isForbidden("/x/${dev.maplesu.manager.compat.StoredInputs.CREDENTIAL_FILE}"))
        assertTrue(LiteHiddenTracePolicy.isForbidden("/data/adb/maplesu/authorization"))
        assertTrue(LiteHiddenTracePolicy.isForbidden("/x/backup/b.tar"))
        assertTrue(LiteHiddenTracePolicy.isForbidden("/x/modules/m"))
        assertFalse(LiteHiddenTracePolicy.isForbidden("/x/diagnostics/a.json"))
    }

    @Test
    fun traceItemsHonestlyReportArtifactsAndRootNeeds() {
        assertFalse("最近安装无持久化痕迹", LiteHiddenTracePolicy.hasPersistentArtifact(LiteHiddenTraceItem.RECENT_INSTALL_LOG))
        assertFalse("只存在 LauncherAlias，图标永不禁用", LiteHiddenTracePolicy.hasPersistentArtifact(LiteHiddenTraceItem.INTENT_ALIASES))
        assertTrue(LiteHiddenTracePolicy.hasPersistentArtifact(LiteHiddenTraceItem.DEBUG_EXPORTS))
        assertTrue(LiteHiddenTracePolicy.requiresRoot(LiteHiddenTraceItem.LOGCAT_TAGS))
    }
}
