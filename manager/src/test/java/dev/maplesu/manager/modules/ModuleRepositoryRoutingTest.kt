// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.modules

import com.maplesu.core.data.model.Module
import com.maplesu.core.data.repository.ModuleBackendUnavailableException
import com.maplesu.core.data.repository.ModuleModeUnresolvedException
import com.maplesu.core.data.repository.ModuleRepositoryImpl
import dev.maplesu.manager.workmode.WorkMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W13 `ModuleRepositoryImpl` 模式路由（纯 JVM）：LITE 等价、KSU 路由、未判定阻断、异常降级。
 *
 * JSON 解析通过 [ModuleListParser] 注入 fake —— JVM 上不能加载 Android 的 `org.json`
 *（android.jar 是 stub，会抛 "Method length in org.json.JSONArray not mocked"）。
 * 生产解析器（`JsonModuleListParser`）在同包 `ModuleListParsers.kt`，由 Android 侧使用。
 */
class ModuleRepositoryRoutingTest {

    private class FakePort(
        private val json: String = "[]",
        private val available: Boolean = true,
        private val result: Boolean = true,
        private val failWith: Throwable? = null,
    ) : ModuleOpsPort {
        val calls = mutableListOf<String>()

        override fun isAvailable(): Boolean = available

        override fun listJson(): String {
            record("listJson")
            return json
        }

        override fun toggle(id: String, enable: Boolean): Boolean {
            record("toggle:$id:$enable")
            return result
        }

        override fun uninstall(id: String): Boolean {
            record("uninstall:$id")
            return result
        }

        override fun undoUninstall(id: String): Boolean {
            record("undoUninstall:$id")
            return result
        }

        override fun install(zip: File): Boolean {
            record("install:${zip.name}")
            return result
        }

        private fun record(call: String) {
            calls += call
            failWith?.let { throw it }
        }
    }

    /** 注入用解析器：记录收到的 JSON，返回预置模型；可配置抛错。 */
    private class FakeParser(
        private val modules: List<Module> = emptyList(),
        private val failWith: Throwable? = null,
    ) : ModuleListParser {
        var lastJson: String? = null

        override fun parse(json: String): List<Module> {
            lastJson = json
            failWith?.let { throw it }
            return modules
        }
    }

    private fun module(
        id: String = "m",
        name: String = id,
        author: String = "A",
        version: String = "1.0",
        versionCode: Int = 100,
        description: String = "",
        enabled: Boolean = true,
        update: Boolean = false,
        remove: Boolean = false,
        hasWebUi: Boolean = false,
        hasActionScript: Boolean = false,
        metamodule: Boolean = false,
        canToggle: Boolean = true,
        unsupported: List<String> = emptyList(),
        propsApplied: List<String> = emptyList(),
        propsFailed: List<String> = emptyList(),
        builtin: Boolean = false,
    ) = Module(
        id = id,
        name = name,
        author = author,
        version = version,
        versionCode = versionCode,
        description = description,
        enabled = enabled,
        update = update,
        remove = remove,
        updateJson = "",
        hasWebUi = hasWebUi,
        hasActionScript = hasActionScript,
        metamodule = metamodule,
        actionIconPath = null,
        webUiIconPath = null,
        canToggle = canToggle,
        unsupported = unsupported,
        propsApplied = propsApplied,
        propsFailed = propsFailed,
        builtin = builtin,
    )

    private fun repository(
        mode: () -> WorkMode?,
        lite: FakePort,
        ksu: FakePort? = null,
        parser: ModuleListParser = FakeParser(),
    ): ModuleRepositoryImpl = ModuleRepositoryImpl(
        mode = mode,
        litePort = lite,
        ksuPortFactory = { ksu },
        listParser = parser,
    )

    @Test
    fun liteListUsesLiteBackendAndParsesRows() = runBlocking {
        val liteJson = """[{"id":"lite-a","name":"Lite A","enabled":true,"remove":false}]"""
        val lite = FakePort(json = liteJson)
        val parser = FakeParser(listOf(module(id = "lite-a", name = "Lite A")))
        val modules = repository({ WorkMode.LITE }, lite, parser = parser).getModules().getOrThrow()
        assertEquals(1, modules.size)
        assertEquals("lite-a", modules[0].id)
        assertEquals("Lite A", modules[0].name)
        assertTrue(modules[0].enabled)
        assertEquals(listOf("listJson"), lite.calls)
        assertEquals("parser must receive the LITE backend JSON", liteJson, parser.lastJson)
    }

    @Test
    fun liteListKeepsParserModelsUnchanged() = runBlocking {
        val expected = module(
            id = "m",
            name = "M",
            author = "A",
            version = "1.2",
            versionCode = 120,
            description = "d",
            enabled = false,
            update = true,
            hasWebUi = true,
            hasActionScript = true,
            metamodule = true,
            canToggle = false,
            unsupported = listOf("stop"),
            propsApplied = listOf("a"),
            propsFailed = listOf("b"),
            builtin = true,
        )
        val lite = FakePort(json = """[{"id":"m"}]""")
        val module = repository({ WorkMode.LITE }, lite, parser = FakeParser(listOf(expected)))
            .getModules().getOrThrow().single()
        assertSame("repository must not re-map parsed models", expected, module)
        assertFalse(module.enabled)
        assertEquals(listOf("stop"), module.unsupported)
        assertEquals(listOf("a"), module.propsApplied)
        assertEquals(listOf("b"), module.propsFailed)
        assertTrue(module.builtin)
    }

    @Test
    fun ksuListRoutesToKsuBackend() = runBlocking {
        val ksuJson = """[{"id":"ksu-a","enabled":true,"remove":false,"backend":"ksu_ksud"}]"""
        val lite = FakePort(json = """[{"id":"lite-a","enabled":true,"remove":false}]""")
        val ksu = FakePort(json = ksuJson)
        val parser = FakeParser(listOf(module(id = "ksu-a")))
        val modules = repository({ WorkMode.LITE }, lite, ksu, parser).getModules().getOrThrow()
        assertEquals("ksu-a", modules.single().id)
        assertEquals(listOf("listJson"), ksu.calls)
        assertTrue("LITE backend must not be touched in KSU mode", lite.calls.isEmpty())
        assertEquals("parser must receive the KSU backend JSON", ksuJson, parser.lastJson)
    }

    @Test
    fun unresolvedModeReturnsExplicitFailureAndTouchesNoBackend() = runBlocking {
        val lite = FakePort()
        val ksu = FakePort()
        val parser = FakeParser(listOf(module()))
        val result = repository({ null }, lite, ksu, parser).getModules()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ModuleModeUnresolvedException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("MODE_UNRESOLVED"))
        assertTrue(lite.calls.isEmpty())
        assertTrue(ksu.calls.isEmpty())
        assertNull("parser must not be called in unresolved state", parser.lastJson)
    }

    @Test
    fun unresolvedModeAlsoBlocksOperations() = runBlocking {
        val lite = FakePort()
        val repo = repository({ null }, lite)
        assertTrue(repo.toggleModule("m", true).isFailure)
        assertTrue(repo.uninstallModule("m").isFailure)
        assertTrue(repo.undoUninstallModule("m").isFailure)
        assertTrue(repo.installModule(File("m.zip")).isFailure)
        assertTrue(lite.calls.isEmpty())
    }

    @Test
    fun missingKsuPortIsBackendUnavailable() = runBlocking {
        val result = repository({ WorkMode.LITE }, FakePort(), ksu = null).getModules()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ModuleBackendUnavailableException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("BACKEND_UNAVAILABLE"))
    }

    @Test
    fun backendExceptionDegradesToFailure() = runBlocking {
        val lite = FakePort(failWith = IllegalStateException("backend crashed"))
        val result = repository({ WorkMode.LITE }, lite).getModules()
        assertTrue(result.isFailure)
        assertEquals("backend crashed", result.exceptionOrNull()!!.message)
    }

    @Test
    fun malformedJsonDegradesToFailureInsteadOfCrash() = runBlocking {
        val lite = FakePort(json = "not-json")
        val parser = FakeParser(failWith = IllegalArgumentException("bad json"))
        val result = repository({ WorkMode.LITE }, lite, parser = parser).getModules()
        assertTrue(result.isFailure)
        assertEquals("bad json", result.exceptionOrNull()!!.message)
    }

    @Test
    fun liteOperationsDelegate() = runBlocking {
        val lite = FakePort(result = true)
        val repo = repository({ WorkMode.LITE }, lite)
        assertTrue(repo.toggleModule("m", false).getOrThrow())
        assertTrue(repo.uninstallModule("m").getOrThrow())
        assertTrue(repo.undoUninstallModule("m").getOrThrow())
        assertEquals(listOf("toggle:m:false", "uninstall:m", "undoUninstall:m"), lite.calls)
    }

    @Test
    fun ksuOperationsDelegateToKsuPort() = runBlocking {
        val lite = FakePort()
        val ksu = FakePort(result = true)
        val repo = repository({ WorkMode.LITE }, lite, ksu)
        assertTrue(repo.toggleModule("m", true).getOrThrow())
        assertTrue(repo.uninstallModule("m").getOrThrow())
        assertTrue(repo.undoUninstallModule("m").getOrThrow())
        assertTrue(repo.installModule(File("downloaded.zip")).getOrThrow())
        assertEquals(
            listOf("toggle:m:true", "uninstall:m", "undoUninstall:m", "install:downloaded.zip"),
            ksu.calls,
        )
        assertTrue(lite.calls.isEmpty())
    }

    @Test
    fun emptyListIsSuccessInResolvedMode() = runBlocking {
        val repo = repository({ WorkMode.LITE }, FakePort(json = "[]"), parser = FakeParser(emptyList()))
        assertEquals(emptyList<Any>(), repo.getModules().getOrThrow())
    }
}
