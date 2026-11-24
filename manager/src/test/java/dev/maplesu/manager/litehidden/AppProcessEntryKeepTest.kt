package dev.maplesu.manager.litehidden

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R8 回归：`app_process` 按**类名字符串**加载入口，release minify 一旦把类改名/裁掉，
 * daemon 与宿主库注入端就会在真机上以 `Unable to find class` 失败（且 debug 构建看不出来）。
 *
 * 这里直接断言 proguard 规则存在；比“记得加规则”可靠，也不需要跑 release 构建。
 */
class AppProcessEntryKeepTest {

    private fun proguardRules(): String {
        val candidates = listOf(
            File("proguard-rules.ultra"),          // Gradle 单测工作目录 = manager 模块
            File("manager/proguard-rules.ultra"),  // 从仓库根运行时的兜底
            File("../manager/proguard-rules.ultra"),
        )
        return candidates.firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
            ?: error("proguard-rules.ultra not found from ${File(".").absolutePath}")
    }

    @Test
    fun keepsTheTwoAppProcessEntryPointsByTheirStableNames() {
        val rules = proguardRules()
        assertTrue(
            "daemon 入口必须 keep（app_process 按名字加载）",
            rules.contains("-keep class dev.maplesu.manager.liteweb.daemon.LiteWebDaemonMain { *; }"),
        )
        assertTrue(
            "宿主原生库注入入口必须 keep",
            rules.contains("-keep class dev.maplesu.manager.litehidden.HostSoPatchMain { *; }"),
        )
        assertTrue(
            "daemon 包内按名字实例化的类也要 keep",
            rules.contains("-keep class dev.maplesu.manager.liteweb.daemon.** { *; }"),
        )
    }

    @Test
    fun keepsRootBootstrapAndModeSwitchEntryPointsByTheirStableNames() {
        val rules = proguardRules()
        for (entry in listOf(
            "dev.maplesu.manager.runtime.NativeSuBootstrap",
            "dev.maplesu.manager.workmode.service.ModeSwitchRootMain",
        )) {
            assertTrue("app_process entry must retain its stable name and members: $entry",
                rules.contains("-keep class $entry { *; }"))
        }
    }

    @Test
    fun keepsAuthorizationMainMethodsByTheirStableNames() {
        val rules = proguardRules()
        for (entry in listOf(
            "dev.maplesu.manager.runtime.authorization.AuthorizationTool",
            "dev.maplesu.manager.runtime.authorization.SuperuserTool",
        )) {
            assertTrue("app_process entry must retain its stable main method: $entry",
                Regex("-keep\\s+class\\s+${Regex.escape(entry)}\\s*\\{\\s*" +
                    "public\\s+static\\s+void\\s+main\\(java\\.lang\\.String\\[\\]\\);\\s*}")
                    .containsMatchIn(rules))
        }
    }
}
