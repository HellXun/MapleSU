package dev.maplesu.manager.recovery

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A/B 后端选择、模式隔离、隐藏读写、卸载/重装、boot hook 安装接线失败语义。 */
class HiddenModeBackendsTest {
    private class FakeRoot {
        val scripts = mutableListOf<String>()
        var handler: (String) -> RecoveryCommandResult = { RecoveryCommandResult(0, "") }
        fun run(script: String): RecoveryCommandResult {
            scripts += script
            return handler(script)
        }
    }

    private class FakeInstaller(
        var installResult: BootHookInstallResult = BootHookInstallResult.Ok("maplesu_boot_hook"),
        var verifyResult: BootHookVerifyResult = BootHookVerifyResult.Installed("maplesu_boot_hook"),
    ) : BootHookInstaller {
        var installs = 0
        var verifies = 0
        override fun install(moduleZip: ByteArray, moduleId: String): BootHookInstallResult {
            installs++
            return installResult
        }

        override fun verify(moduleId: String): BootHookVerifyResult {
            verifies++
            return verifyResult
        }

        override fun uninstall(moduleId: String): BootHookInstallResult =
            BootHookInstallResult.Ok(moduleId)
    }

    @Test
    fun modeSelectionMapsLiteToUltraAndKsuToKsu() {
        val root: (String) -> RecoveryCommandResult = { RecoveryCommandResult(0, "") }
        assertEquals(
            HiddenMode.A_LITE_ULTRA_ENV,
            HiddenModeBackends.select(WorkMode.LITE, root, root)!!.mode,
        )
        assertEquals(
            HiddenMode.B_KSU,
            HiddenModeBackends.select(WorkMode.LITE, root, root)!!.mode,
        )
        assertNull(HiddenModeBackends.select(null, root, root))
    }

    @Test
    fun ultraBackendWritesLiteHiddenOnly() {
        val root = FakeRoot().apply {
            handler = { script ->
                if (script.startsWith("printf")) RecoveryCommandResult(0, "LITE_OK") else RecoveryCommandResult(0, "0")
            }
        }
        val backend = UltraEnvBackend(root::run)
        assertTrue(backend.writeHidden(true).ok)
        assertTrue(root.scripts.last().contains("/data/adb/maplesu/lite_hidden"))
        assertFalse(root.scripts.any { it.contains("/data/adb/modules") })
        assertEquals(false, backend.currentHidden())
    }

    @Test
    fun ksuBackendNeverTouchesUltraPaths() {
        val root = FakeRoot().apply {
            handler = { script ->
                if (script.startsWith("printf")) RecoveryCommandResult(0, "LITE_OK") else RecoveryCommandResult(0, "1")
            }
        }
        val backend = KsuBackend(root::run)
        assertTrue(backend.writeHidden(true).ok)
        assertEquals(true, backend.currentHidden())
        assertFalse(root.scripts.any { it.contains("/data/adb/maplesu/workmode") })
        // B 的隐藏 ioctl 由上层 Stealth 执行；本层只维护兼容状态位，不调用 Ultra SDK。
        assertFalse(root.scripts.any { it.contains(dev.maplesu.manager.compat.StoredInputs.ENVIRONMENT_NAME_PATTERN) })
    }

    @Test
    fun uninstallAndInstallUsePmThroughBackendRoot() {
        val root = FakeRoot().apply {
            handler = { script ->
                when {
                    // 安装前必须先把 APK 搬到 /data/local/tmp（/data/adb 是 adb_data_file）。
                    script.startsWith("cp -f") || script.startsWith("rm -f") ->
                        RecoveryCommandResult(0, "LITE_OK")
                    else -> RecoveryCommandResult(0, "Success")
                }
            }
        }
        val backend = UltraEnvBackend(root::run)
        assertTrue(backend.uninstallManager().ok)
        assertTrue(backend.installManagerApk("/data/adb/maplesu/recovery/manager.apk").ok)
        assertTrue(root.scripts[0].startsWith("pm uninstall"))
        assertTrue(root.scripts[1].startsWith("cp -f"))
        assertTrue(root.scripts[1].contains("/data/local/tmp/"))
        assertTrue(root.scripts[2].contains("pm install -r '/data/local/tmp/"))
    }

    @Test
    fun uninstallFailureIsNotSuccess() {
        val root = FakeRoot().apply { handler = { RecoveryCommandResult(1, "Failure") } }
        val result = KsuBackend(root::run).uninstallManager()
        assertFalse(result.ok)
        assertEquals("UNINSTALL_FAILED", (result as BackendStepResult.Failed).code)
    }

    @Test
    fun ultraBootHookWithoutInstallerFailsClosed() {
        val root: (String) -> RecoveryCommandResult = { RecoveryCommandResult(0, "") }
        val result = UltraEnvBackend(root).installBootHook(byteArrayOf(1), "id")
        assertFalse(result.ok)
        assertEquals("ULTRA_MODULE_INSTALL_NOT_WIRED", (result as BackendStepResult.Failed).code)
    }

    @Test
    fun bootHookInstallationUsesInjectedInstaller() {
        val root: (String) -> RecoveryCommandResult = { RecoveryCommandResult(0, "") }
        val installer = FakeInstaller()
        val ultra = UltraEnvBackend(root, installer)
        val ksu = KsuBackend(root, installer)
        assertTrue(ultra.installBootHook(byteArrayOf(1), "id").ok)
        assertTrue(ultra.verifyBootHook("id").ok)
        assertTrue(ksu.installBootHook(byteArrayOf(1), "id").ok)
        assertTrue(ksu.verifyBootHook("id").ok)
        assertEquals(2, installer.installs)
        assertEquals(2, installer.verifies)
    }

    @Test
    fun bootHookInstallFailureIsStructured() {
        val root: (String) -> RecoveryCommandResult = { RecoveryCommandResult(0, "") }
        val installer = FakeInstaller(
            installResult = BootHookInstallResult.Failed("MODULE_REJECTED", "not a module"),
        )
        val result = KsuBackend(root, installer).installBootHook(byteArrayOf(1), "id")
        assertFalse(result.ok)
        assertEquals("MODULE_REJECTED", (result as BackendStepResult.Failed).code)
    }

    @Test
    fun bootPersistenceEvidenceParsesOnlyCompleteProof() {
        val root = FakeRoot().apply {
            handler = {
                RecoveryCommandResult(
                    0,
                    """{"bootId":"b1","recordedAt":5,"daemonPid":99,"port":18427,""" +
                        """"serviceScriptRan":true,"daemonListening":true,"moduleLog":"started"}""",
                )
            }
        }
        val evidence = KsuBackend(root::run).bootPersistence()
        assertTrue(BootHookEvidence.proven(evidence))
        val missing = FakeRoot().apply { handler = { RecoveryCommandResult(0, "") } }
        assertNull(KsuBackend(missing::run).bootPersistence())
    }
}
