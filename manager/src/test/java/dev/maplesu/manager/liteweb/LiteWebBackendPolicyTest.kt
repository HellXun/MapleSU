package dev.maplesu.manager.liteweb

import dev.maplesu.manager.litehidden.ParasiteMarkers
import dev.maplesu.manager.litehidden.RecoveryIdentity
import dev.maplesu.manager.runtime.RootBackend
import dev.maplesu.manager.runtime.RootExecResult
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.ModeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0 liteweb 启动策略三态 + ULTRA_ENV daemon 尝试失败语义（不假装 running）。 */
class LiteWebBackendPolicyTest {
    private val identity = RecoveryIdentity("0123456789abcdef", "dev.maplesu.manager", 10_123)
    private val started = "${ParasiteMarkers.IDENTITY_OK}\nLITEWEB_DAEMON_STARTED port=18437\n"
    @Test
    fun liteKernelUsesRootService() {
        assertEquals(
            LiteWebStartStrategy.ROOT_SERVICE,
            LiteWebBackendPolicy.strategy(WorkMode.LITE, RootBackend.LITE_KERNEL),
        )
    }

    @Test
    fun ultraEnvUsesDaemonAttempt() {
        assertEquals(
            LiteWebStartStrategy.ULTRA_ENV_DAEMON,
            LiteWebBackendPolicy.strategy(WorkMode.LITE, RootBackend.ULTRA_ENV),
        )
    }

    @Test
    fun noneBackendIsUnavailableWithExplicitCode() {
        assertEquals(
            LiteWebStartStrategy.UNAVAILABLE,
            LiteWebBackendPolicy.strategy(WorkMode.LITE, RootBackend.NONE),
        )
        assertEquals(
            LiteWebBackendPolicy.ROOT_BACKEND_UNAVAILABLE,
            LiteWebBackendPolicy.unavailableCode(WorkMode.LITE, RootBackend.NONE),
        )
    }

    @Test
    fun ksuIsAlwaysRefusedButUnresolvedWithoutEvidenceGetsItsOwnCode() {
        assertEquals(
            LiteWebStartStrategy.UNAVAILABLE,
            LiteWebBackendPolicy.strategy(WorkMode.LITE, RootBackend.ULTRA_ENV),
        )
        // 未判定且没有 LITE 证据：仍然拒绝（不放宽成"任何模式都能起 daemon"）。
        assertEquals(
            LiteWebStartStrategy.UNAVAILABLE,
            LiteWebBackendPolicy.strategy(null, RootBackend.ULTRA_ENV),
        )
        assertEquals(
            LiteWebBackendPolicy.MODE_UNRESOLVED,
            LiteWebBackendPolicy.unavailableCode(null, RootBackend.NONE),
        )
        // 明确是 B 模式 → 依然是"模式不是 Lite"，不是"未判定"。
        assertEquals(
            LiteWebBackendPolicy.MODE_NOT_LITE,
            LiteWebBackendPolicy.unavailableCode(WorkMode.LITE, RootBackend.ULTRA_ENV),
        )
    }

    @Test
    fun unresolvedWithLiteEvidenceInfersLiteAndEmitsWarning() {
        // 真机缺陷 3：冷启动 Unresolved，但设备上装的是 Ultra 内核 / Ultra 环境 → 可以启动。
        assertEquals(
            LiteWebStartStrategy.ROOT_SERVICE,
            LiteWebBackendPolicy.strategy(null, RootBackend.LITE_KERNEL, liteInstalled = true),
        )
        assertEquals(
            LiteWebStartStrategy.ULTRA_ENV_DAEMON,
            LiteWebBackendPolicy.strategy(null, RootBackend.ULTRA_ENV, liteInstalled = true),
        )
        assertEquals(
            LiteWebStartStrategy.UNAVAILABLE,
            LiteWebBackendPolicy.strategy(null, RootBackend.NONE, liteInstalled = true),
        )
        assertEquals(
            LiteWebBackendPolicy.ROOT_BACKEND_UNAVAILABLE,
            LiteWebBackendPolicy.unavailableCode(null, RootBackend.NONE, liteInstalled = true),
        )
        assertEquals(
            LiteWebBackendPolicy.MODE_INFERRED_LITE,
            LiteWebBackendPolicy.inferredWarning(null, liteInstalled = true),
        )
        assertEquals("", LiteWebBackendPolicy.inferredWarning(null, liteInstalled = false))
        assertEquals("", LiteWebBackendPolicy.inferredWarning(WorkMode.LITE, liteInstalled = true))
        assertEquals("", LiteWebBackendPolicy.inferredWarning(WorkMode.LITE, liteInstalled = true))
    }

    @Test
    fun liteEvidenceNeverComesFromKsuOrForeignRoot() {
        assertFalse(LiteWebBackendPolicy.hasLiteEvidence(RootBackend.ULTRA_ENV, ModeState.MODE_B_ACTIVE))
        assertFalse(LiteWebBackendPolicy.hasLiteEvidence(RootBackend.ULTRA_ENV, ModeState.MODE_B_KERNEL_ONLY))
        assertFalse(LiteWebBackendPolicy.hasLiteEvidence(RootBackend.ULTRA_ENV, ModeState.FOREIGN_SU))
        assertFalse(LiteWebBackendPolicy.hasLiteEvidence(RootBackend.LITE_KERNEL, ModeState.NOT_INSTALLED))
        // A 已装/正在跑：有证据，但仍要有可用的已装实现。
        assertTrue(LiteWebBackendPolicy.hasLiteEvidence(RootBackend.ULTRA_ENV, ModeState.MODE_A_ACTIVE))
        assertTrue(
            LiteWebBackendPolicy.hasLiteEvidence(RootBackend.LITE_KERNEL, ModeState.MODE_A_INSTALLED_UNBOUND),
        )
        // 未判定 / 读不到：按"当前安装的 root 实现"推断。
        assertTrue(LiteWebBackendPolicy.hasLiteEvidence(RootBackend.LITE_KERNEL, null))
        assertTrue(LiteWebBackendPolicy.hasLiteEvidence(RootBackend.ULTRA_ENV, ModeState.INDETERMINATE))
        assertFalse(LiteWebBackendPolicy.hasLiteEvidence(RootBackend.NONE, null))
        assertFalse(LiteWebBackendPolicy.hasLiteEvidence(RootBackend.NONE, ModeState.INDETERMINATE))
    }

    @Test
    fun missingBackendNeverReportsReady() {
        assertEquals(
            LiteWebStartStrategy.UNAVAILABLE,
            LiteWebBackendPolicy.strategy(WorkMode.LITE, RootBackend.NONE, liteInstalled = true),
        )
        assertEquals(
            LiteWebBackendPolicy.ROOT_BACKEND_UNAVAILABLE,
            LiteWebBackendPolicy.unavailableCode(WorkMode.LITE, RootBackend.NONE, liteInstalled = true),
        )
    }

    @Test
    fun daemonSuccessRequiresAuthenticatedPortReceipt() {
        val daemon = RootCmdUltraEnvLiteWebDaemon(
            execute = { RootExecResult(RootBackend.ULTRA_ENV, 0, started) },

        )
        val result = daemon.startForApk("/data/app/base.apk", identity)
        assertTrue(result.started)
        assertEquals(18437, result.port)
    }

    @Test
    fun daemonWithoutStartMarkerIsUnsupported() {
        val daemon = RootCmdUltraEnvLiteWebDaemon(
            execute = { RootExecResult(RootBackend.ULTRA_ENV, 0, "nothing") },

        )
        val result = daemon.startForApk("/data/app/base.apk", identity)
        assertFalse(result.started)
        assertEquals(LiteWebBackendPolicy.ULTRA_ENV_DAEMON_UNSUPPORTED, result.code)
    }

    @Test
    fun daemonRejectedRootIsUnsupported() {
        val daemon = RootCmdUltraEnvLiteWebDaemon(
            execute = { RootExecResult(RootBackend.ULTRA_ENV, 7, "", "ULTRA_ROOT_FAILED:ERR") },
        )
        val result = daemon.startForApk("/data/app/base.apk", identity)
        assertFalse(result.started)
        assertEquals(LiteWebBackendPolicy.ULTRA_ENV_DAEMON_UNSUPPORTED, result.code)
        assertTrue(result.detail.contains("ULTRA_ROOT_FAILED"))
    }

    @Test
    fun daemonStartedWithoutAuthenticatedPortIsUnsupported() {
        val daemon = RootCmdUltraEnvLiteWebDaemon(
            execute = { RootExecResult(RootBackend.ULTRA_ENV, 0, "${ParasiteMarkers.IDENTITY_OK}\nLITEWEB_DAEMON_STARTED") },

        )
        val result = daemon.startForApk("/data/app/base.apk", identity)
        assertFalse(result.started)
        assertTrue(result.detail.contains("port receipt invalid"))
    }

    @Test
    fun daemonScriptUsesAppProcessWithApkClasspath() {
        var script = ""
        val daemon = RootCmdUltraEnvLiteWebDaemon(
            execute = { value ->
                script = value
                RootExecResult(RootBackend.ULTRA_ENV, 0, started)
            },
        )
        daemon.startForApk("/data/app/base.apk", identity)
        assertTrue(script.contains("app_process"))
        // 真机缺陷回归：`app_process` 不认 `-Djava.class.path=`（会被当成主类名），
        // classpath 必须走 CLASSPATH 环境变量；主类必须是 daemon 包下的完整类名。
        assertFalse(script.contains("-Djava.class.path="))
        assertTrue(script.contains("APK='/data/app/base.apk'"))
        assertTrue(script.contains("CLASSPATH=\"\$APK\" nohup /system/bin/app_process /system/bin "))
        assertTrue(script.contains("dev.maplesu.manager.liteweb.daemon.LiteWebDaemonMain"))
    }

    @Test
    fun daemonScriptWritesPidFileSoStopCanFindIt() {
        // 真机缺陷 1 的第二个根因：这条启动路径从不写 liteweb.pid，解除寄生时只能靠 argv 兜底。
        var script = ""
        val daemon = RootCmdUltraEnvLiteWebDaemon(
            execute = { value ->
                script = value
                RootExecResult(RootBackend.ULTRA_ENV, 0, started)
            },
        )
        daemon.startForApk("/data/app/base.apk", identity)
        assertTrue(script, script.contains("liteweb.pid"))
        assertTrue(script, script.contains("--await-ready"))
        assertFalse("only the daemon lock owner may publish the PID", script.contains("echo \"\$P\" >"))
        assertTrue(script.contains("umask 077"))
    }

    @Test
    fun daemonPreparesOnlyIdentityBeforeLaunching() {
        var script = ""
        val daemon = RootCmdUltraEnvLiteWebDaemon(
            execute = { value ->
                script = value
                RootExecResult(RootBackend.ULTRA_ENV, 0, started)
            },
        )
        val result = daemon.startForApk("/data/app/base.apk", identity)
        assertTrue(result.started)
        assertTrue(script.contains("identity.json.tmp"))
        assertTrue(script.contains("stat -c '%a %u'"))
        assertTrue(script.indexOf("echo ${ParasiteMarkers.IDENTITY_OK}") < script.indexOf(" nohup "))
        assertFalse(script.contains("rootkey"))
        assertFalse(script.contains("host/"))
        assertFalse(script.contains("lite_hidden"))
        assertFalse(result.detail.contains(identity.androidId))
    }

    @Test
    fun daemonMissingIdentityNeverExecutesOrProbes() {
        var executed = false
        val daemon = RootCmdUltraEnvLiteWebDaemon(
            execute = { executed = true; error("unexpected execution") },

        )
        assertFalse(daemon.startForApk("/data/app/base.apk", null).started)
        assertFalse(daemon.startForApk("/data/app/base.apk", identity.copy(androidId = "bad id")).started)
        assertFalse(executed)
    }

    @Test
    fun daemonIdentityRequiresItsOwnReadbackMarker() {
        val daemon = RootCmdUltraEnvLiteWebDaemon(
            execute = { RootExecResult(RootBackend.ULTRA_ENV, 0, "LITEWEB_DAEMON_STARTED") },

        )
        val result = daemon.startForApk("/data/app/base.apk", identity)
        assertFalse(result.started)
        assertTrue(result.detail.contains("identity preparation failed"))
    }
}
