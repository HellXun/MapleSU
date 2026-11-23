package dev.maplesu.manager.parasite

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** H4 裁决：Lite runtime CLI 端口的参数/退出码映射/独立结果/生产选择。 */
class LiteParasiteSdkPortTest {
    private val credential = ByteArray(48) { 'k'.code.toByte() }

    private class FakeInvoker : RuntimeCliInvoker {
        var result: RuntimeCliInvocation = RuntimeCliInvocation(0, "", "")
        var calls = mutableListOf<List<String>>()
        var credentials = mutableListOf<Int>()
        var executable: File? = null

        override fun invoke(
            executable: File,
            credential: ByteArray,
            vararg arguments: String,
        ): RuntimeCliInvocation {
            this.executable = executable
            calls += arguments.toList()
            credentials += credential.size
            return result
        }
    }

    private fun executableFile(): File =
        Files.createTempDirectory("maplesu-parasite-cli-").toFile().resolve("libmaplesu_runtime.so")
            .apply { writeText("") }

    private fun port(
        invoker: FakeInvoker,
        executable: File = executableFile(),
        credentialProvider: () -> ByteArray? = { credential },
        quiescing: () -> Boolean = { false },
    ) = LiteParasiteSdkPort(executable, credentialProvider, invoker, quiescing)

    @Test
    fun precheckUsesWireSuPrecheckAndParsesDynlibs() {
        val invoker = FakeInvoker().apply {
            result = RuntimeCliInvocation(0, "1 /data/app/lib/liba.so\n2 /data/app/lib/libb.so\n", "")
        }
        val result = port(invoker).precheck("com.example.host")
        assertTrue(result is ParasiteSdkResult.Ok)
        val dynlibs = (result as ParasiteSdkResult.Ok).dynlibs
        assertEquals(listOf("/data/app/lib/liba.so", "/data/app/lib/libb.so"), dynlibs.map { it.path })
        assertEquals(AppDynlibStatus.RUNNING, dynlibs[0].status)
        assertEquals(AppDynlibStatus.NOT_RUNNING, dynlibs[1].status)
        assertEquals(listOf("--wire-su-precheck", "com.example.host"), invoker.calls.single())
        assertEquals(listOf(48), invoker.credentials)
        assertTrue(invoker.executable!!.name == "libmaplesu_runtime.so")
    }

    @Test
    fun precheckUnknownStatusIntegerIsStructuredFailure() {
        val invoker = FakeInvoker().apply { result = RuntimeCliInvocation(0, "9 /lib/x.so\n", "") }
        val result = port(invoker).precheck("com.example.host")
        assertEquals(ParasiteCodes.STATUS_UNKNOWN, (result as ParasiteSdkResult.Failed).code)
    }

    @Test
    fun precheckUnknownStatusZeroStaysUnknownNotNotRunning() {
        val invoker = FakeInvoker().apply { result = RuntimeCliInvocation(0, "0 /lib/x.so\n", "") }
        val result = port(invoker).precheck("com.example.host")
        assertTrue(result is ParasiteSdkResult.Ok)
        assertEquals(AppDynlibStatus.UNKNOWN, (result as ParasiteSdkResult.Ok).dynlibs.single().status)
    }

    @Test
    fun precheckEmptyOutputIsNoEligibleDynlib() {
        val invoker = FakeInvoker().apply { result = RuntimeCliInvocation(0, "", "") }
        val result = port(invoker).precheck("com.example.host")
        assertEquals(ParasiteCodes.NO_ELIGIBLE_DYNLIB, (result as ParasiteSdkResult.Failed).code)
    }

    @Test
    fun exitCodesMapToStructuredCodes() {
        val invoker = FakeInvoker()
        // 65 → 凭证不可用
        invoker.result = RuntimeCliInvocation(65, "", "credential frame required")
        assertEquals(
            ParasiteCodes.CREDENTIAL_UNAVAILABLE,
            (port(invoker).precheck("cmd") as ParasiteSdkResult.Failed).code,
        )
        // 66 → 预检查失败
        invoker.result = RuntimeCliInvocation(66, "", "precheck rejected")
        assertEquals(
            ParasiteCodes.PRECHECK_FAILED,
            (port(invoker).precheck("cmd") as ParasiteSdkResult.Failed).code,
        )
        // 68 → implant-app 失败
        invoker.result = RuntimeCliInvocation(68, "", "implant app rejected")
        assertEquals(
            ParasiteCodes.IMPLANT_FAILED,
            (port(invoker).implantApp("cmd", "/so") as ParasiteSdkResult.Failed).code,
        )
        // 67 → su env 失败
        invoker.result = RuntimeCliInvocation(67, "", "implant rejected")
        assertEquals(
            ParasiteCodes.SU_ENV_FAILED,
            (port(invoker).implantSuEnv("cmd", "/so", "/su") as ParasiteSdkResult.Failed).code,
        )
        // 其它非 0 → 各自子命令的失败码（不伪造成功）
        invoker.result = RuntimeCliInvocation(1, "", "unknown")
        assertEquals(
            ParasiteCodes.PRECHECK_FAILED,
            (port(invoker).precheck("cmd") as ParasiteSdkResult.Failed).code,
        )
    }

    @Test
    fun implantAppUsesNewImplantAppSubcommand() {
        val invoker = FakeInvoker().apply { result = RuntimeCliInvocation(0, "IMPLANTED:/lib/libfoo.so\n", "") }
        assertTrue(port(invoker).implantApp("com.example.host", "/lib/libfoo.so") is ParasiteSdkResult.Ok)
        assertEquals(
            listOf("--implant-app", "com.example.host", "/lib/libfoo.so"),
            invoker.calls.single(),
        )
    }

    @Test
    fun implantSuEnvUsesWireSuEnvSubcommand() {
        val invoker = FakeInvoker().apply { result = RuntimeCliInvocation(0, "WIRED:/lib/libfoo.so\n", "") }
        assertTrue(port(invoker).implantSuEnv("com.example.host", "/lib/libfoo.so", "/su") is ParasiteSdkResult.Ok)
        assertEquals(
            listOf("--wire-su-env", "com.example.host", "/lib/libfoo.so", "/su"),
            invoker.calls.single(),
        )
    }

    @Test
    fun missingOrMalformedCredentialIsCredentialCodeAndNeverInvokes() {
        val invoker = FakeInvoker()
        assertTrue(port(invoker, credentialProvider = { null }).precheck("cmd") is ParasiteSdkResult.Failed)
        assertEquals(
            ParasiteCodes.CREDENTIAL_UNAVAILABLE,
            (port(invoker, credentialProvider = { null }).precheck("cmd") as ParasiteSdkResult.Failed).code,
        )
        assertEquals(
            ParasiteCodes.CREDENTIAL_UNAVAILABLE,
            (port(invoker, credentialProvider = { ByteArray(47) }).implantApp("cmd", "/so") as ParasiteSdkResult.Failed).code,
        )
        assertTrue("credential failure must not start the runtime", invoker.calls.isEmpty())
    }

    @Test
    fun missingRuntimeImageIsUnavailableWithoutInvoking() {
        val invoker = FakeInvoker()
        val missing = File("definitely-missing-libmaplesu_runtime.so")
        assertEquals(
            ParasiteCodes.ULTRA_UNAVAILABLE,
            (port(invoker, executable = missing).precheck("cmd") as ParasiteSdkResult.Failed).code,
        )
        assertTrue(invoker.calls.isEmpty())
    }

    @Test
    fun quiescingGateRejectsEverythingWithoutInvoking() {
        val invoker = FakeInvoker()
        val quiescing = port(invoker, quiescing = { true })
        for (result in listOf(
            quiescing.precheck("cmd"),
            quiescing.implantApp("cmd", "/so"),
            quiescing.implantSuEnv("cmd", "/so", "/su"),
        )) {
            assertEquals(ParasiteCodes.QUIESCING, (result as ParasiteSdkResult.Failed).code)
        }
        assertTrue(invoker.calls.isEmpty())
    }

    @Test
    fun invalidArgumentsAreRejectedBeforeCredentialAndInvocation() {
        val invoker = FakeInvoker()
        val p = port(invoker, credentialProvider = { null })
        assertEquals(ParasiteCodes.INVALID_ARGUMENT, (p.precheck(" ") as ParasiteSdkResult.Failed).code)
        assertEquals(ParasiteCodes.INVALID_ARGUMENT, (p.implantApp("cmd", "") as ParasiteSdkResult.Failed).code)
        assertEquals(ParasiteCodes.INVALID_ARGUMENT, (p.implantSuEnv("cmd", "/so", "") as ParasiteSdkResult.Failed).code)
        assertTrue(invoker.calls.isEmpty())
    }

    @Test
    fun fourResultsRemainIndependentOnPort() {
        // 端口只回答"单次调用结果"；四结果位属于宿主记录，不被单次成功掩盖。
        val invoker = FakeInvoker().apply { result = RuntimeCliInvocation(0, "1 /lib/a.so\n", "") }
        val p = port(invoker)
        assertTrue(p.precheck("cmd") is ParasiteSdkResult.Ok)
        val caps = ParasiteCapabilities()
        assertFalse(caps.soLoaded || caps.webReachable || caps.serviceEstablished || caps.rootCommandOk)
        assertTrue(p.implantApp("cmd", "/so") is ParasiteSdkResult.Ok)
        assertFalse(ParasiteCapabilities().allFour)
    }

    @Test
    fun productionSelectionOnlyLiteUsesRuntimeCli() {
        val lite = object : ParasiteSdkPort {
            override fun precheck(targetPidCmdline: String) = ParasiteSdkResult.Failed("LITE")
            override fun implantApp(targetPidCmdline: String, soPath: String) = ParasiteSdkResult.Failed("LITE")
            override fun implantSuEnv(targetPidCmdline: String, soPath: String, suDir: String) =
                ParasiteSdkResult.Failed("LITE")
        }
        val unavailable = UnavailableParasiteSdkPort(UltraAvailability.RUNNING)
        assertSame(lite, selectProductionParasitePort(WorkMode.LITE, { lite }, { unavailable }))
        assertSame(unavailable, selectProductionParasitePort(WorkMode.LITE, { lite }, { unavailable }))
        assertSame(unavailable, selectProductionParasitePort(null, { lite }, { unavailable }))
        // 未判定/ KSU 不默认 LITE，且给出 ULTRA_UNAVAILABLE（不是编造的 LITE 失败）。
        assertEquals(
            ParasiteCodes.ULTRA_UNAVAILABLE,
            (unavailable.precheck("cmd") as ParasiteSdkResult.Failed).code,
        )
    }
}
