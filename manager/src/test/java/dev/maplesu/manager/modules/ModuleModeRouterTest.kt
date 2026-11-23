// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.modules

import dev.maplesu.manager.workmode.WorkMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W13 模块后端模式路由表驱动测试（纯 JVM，无 Android 依赖）。
 *
 * 覆盖：LITE 原实现路径、KSU 路由、未判定阻断、端口缺失/不可用、异常降级。
 */
class ModuleModeRouterTest {

    private class FakePort(
        private val label: String,
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

        override fun toString(): String = "FakePort($label)"
    }

    private val lite = FakePort("lite")
    private val ksu = FakePort("ksu")

    private fun router(
        mode: () -> WorkMode?,
        litePort: ModuleOpsPort? = lite,
        ksuPort: ModuleOpsPort? = ksu,
    ) = ModuleBackendRouter(mode = mode, litePort = { litePort }, ksuPort = { ksuPort })

    // ── LITE：原实现路径 ────────────────────────────────────────────────

    @Test
    fun liteListDelegatesToLitePortOnly() {
        val result = router({ WorkMode.LITE }).listJson()
        assertEquals(ModuleRouteResult.Ok("[]"), result)
        assertEquals(listOf("listJson"), lite.calls)
        assertTrue(ksu.calls.isEmpty())
    }

    @Test
    fun liteToggleUninstallUndoInstallDelegateToLitePort() {
        val r = router({ WorkMode.LITE })
        assertEquals(ModuleRouteResult.Ok(true), r.toggle("m1", true))
        assertEquals(ModuleRouteResult.Ok(true), r.uninstall("m1"))
        assertEquals(ModuleRouteResult.Ok(true), r.undoUninstall("m1"))
        assertEquals(ModuleRouteResult.Ok(true), r.install(File("m1.zip")))
        assertEquals(
            listOf("toggle:m1:true", "uninstall:m1", "undoUninstall:m1", "install:m1.zip"),
            lite.calls,
        )
        assertTrue(ksu.calls.isEmpty())
    }

    // ── KSU：路由到 KsuModuleOps 端口 ───────────────────────────────────

    @Test
    fun ksuListDelegatesToKsuPortOnly() {
        val ksuJson = """[{"id":"ksu-mod","enabled":"true"}]"""
        val port = FakePort("ksu-list", json = ksuJson)
        val result = router({ WorkMode.LITE }, ksuPort = port).listJson()
        assertEquals(ModuleRouteResult.Ok(ksuJson), result)
        assertEquals(listOf("listJson"), port.calls)
        assertTrue(lite.calls.isEmpty())
    }

    @Test
    fun ksuToggleUninstallUndoInstallDelegateToKsuPort() {
        val port = FakePort("ksu-ops")
        val r = router({ WorkMode.LITE }, ksuPort = port)
        assertEquals(ModuleRouteResult.Ok(true), r.toggle("mod", false))
        assertEquals(ModuleRouteResult.Ok(true), r.uninstall("mod"))
        assertEquals(ModuleRouteResult.Ok(true), r.undoUninstall("mod"))
        assertEquals(ModuleRouteResult.Ok(true), r.install(File("mod.zip")))
        assertEquals(
            listOf("toggle:mod:false", "uninstall:mod", "undoUninstall:mod", "install:mod.zip"),
            port.calls,
        )
        assertTrue(lite.calls.isEmpty())
    }

    // ── 未判定：禁止任一后端 ───────────────────────────────────────────

    @Test
    fun unresolvedBlocksEveryOperationAndCallsNoPort() {
        val r = router({ null })
        assertEquals(ModuleRouteResult.Unresolved, r.listJson())
        assertEquals(ModuleRouteResult.Unresolved, r.toggle("m1", true))
        assertEquals(ModuleRouteResult.Unresolved, r.uninstall("m1"))
        assertEquals(ModuleRouteResult.Unresolved, r.undoUninstall("m1"))
        assertEquals(ModuleRouteResult.Unresolved, r.install(File("m1.zip")))
        assertTrue(lite.calls.isEmpty())
        assertTrue(ksu.calls.isEmpty())
    }

    @Test
    fun modeProviderFailureIsUnresolvedNotLite() {
        val r = ModuleBackendRouter(
            mode = { error("probe crashed") },
            litePort = { lite },
            ksuPort = { ksu },
        )
        assertEquals(ModuleRouteResult.Unresolved, r.listJson())
        assertTrue(lite.calls.isEmpty())
        assertNull(r.routedMode())
    }

    // ── 后端缺失/不可用/异常：显式降级 ─────────────────────────────────

    @Test
    fun missingKsuPortIsExplicitlyUnavailable() {
        val result = router({ WorkMode.LITE }, ksuPort = null).listJson()
        assertTrue(result is ModuleRouteResult.Unavailable)
        assertTrue((result as ModuleRouteResult.Unavailable).reason.contains("ksu"))
    }

    @Test
    fun unavailablePortIsReportedAsUnavailable() {
        val port = FakePort("ksu-down", available = false)
        val result = router({ WorkMode.LITE }, ksuPort = port).toggle("m1", true)
        assertTrue(result is ModuleRouteResult.Unavailable)
    }

    @Test
    fun portExceptionDegradesToFailed() {
        val port = FakePort("ksu-boom", failWith = IllegalStateException("ksud crashed"))
        val result = router({ WorkMode.LITE }, ksuPort = port).listJson()
        assertTrue(result is ModuleRouteResult.Failed)
        assertEquals("ksud crashed", (result as ModuleRouteResult.Failed).error.message)
    }

    @Test
    fun litePortExceptionAlsoDegrades() {
        val port = FakePort("lite-boom", failWith = RuntimeException("lite backend"))
        val result = router({ WorkMode.LITE }, litePort = port).uninstall("m1")
        assertTrue(result is ModuleRouteResult.Failed)
    }

    @Test
    fun routedModeExposesResolvedMode() {
        assertNull(router({ null }).routedMode())
        assertEquals(WorkMode.LITE, router({ WorkMode.LITE }).routedMode())
        assertEquals(WorkMode.LITE, router({ WorkMode.LITE }).routedMode())
    }
}
