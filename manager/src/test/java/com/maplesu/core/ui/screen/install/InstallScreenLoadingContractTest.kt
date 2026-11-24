package com.maplesu.core.ui.screen.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Guards the install page's non-blocking reads, click guards and the single confirm. */
class InstallScreenLoadingContractTest {
    private fun screen(): String {
        val file = File("src/main/java/com/maplesu/core/ui/screen/install/InstallScreen.kt")
        assumeTrue(file.path, file.isFile)
        return file.readText()
    }

    private fun uiState(): String {
        val file = File("src/main/java/com/maplesu/core/ui/screen/install/InstallUiState.kt")
        assumeTrue(file.path, file.isFile)
        return file.readText()
    }

    /**
     * 去掉 Kotlin 注释后的源码（字符串字面量原样保留）。
     *
     * 四步流程的改动让 `InstallScreen.kt` 的文档注释开始**解释**"为什么这页没有迁移入口"
     * （`HomeScreen` 的 `Route.Migration` 才是它的家）。注释不是这页构造的路由，
     * 但 `source.contains("Route.Migration")` 分不出来。这里先把注释剥掉再断言，
     * 断言本身仍然是真实的：只要**代码**里出现 `Route.Migration` 就会失败。
     */
    private fun code(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when {
                c == '/' && next == '/' -> while (i < source.length && source[i] != '\n') i++
                c == '/' && next == '*' -> {
                    i += 2
                    while (i < source.length && !(source[i] == '*' && source.getOrNull(i + 1) == '/')) i++
                    i += 2
                }
                c == '"' -> {
                    out.append(c)
                    i++
                    while (i < source.length && source[i] != '"') {
                        if (source[i] == '\\' && i + 1 < source.length) {
                            out.append(source[i]).append(source[i + 1])
                            i += 2
                        } else {
                            out.append(source[i])
                            i++
                        }
                    }
                    if (i < source.length) {
                        out.append(source[i])
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    @Test
    fun pendingUsesTheExistingSessionWithoutReadingHomeFacts() {
        val source = screen()
        assertTrue(source.contains("WorkModeSwitchSession.executing"))
        assertFalse(source.contains("HomeWorkModeSwitchRuntime"))
        assertFalse(source.contains("isSwitchPending()"))
        assertFalse(source.contains("statusAsync("))
    }

    @Test
    fun kernelReleaseReadRunsOffMainAndHasAnExplicitLoadingState() {
        val source = screen()
        assertTrue(source.contains("var kernelReleaseLoading by remember { mutableStateOf(true) }"))
        assertTrue(source.contains("withContext(Dispatchers.IO) { readDeviceKernelRelease() }"))
        assertFalse(source.contains("remember { readDeviceKernelRelease() }"))
        val effect = source.substringAfter("LaunchedEffect(Unit) {").substringBefore("LifecycleResumeEffect(Unit)")
        assertTrue(effect.contains("deviceKernelRelease = withContext(Dispatchers.IO)"))
        assertTrue(effect.indexOf("readDeviceKernelRelease()") < effect.indexOf("kernelReleaseLoading = false"))
    }

    @Test
    fun bootTargetDiscoveryIsCancelledWhenThePagePauses() {
        val source = screen()
        val effect = source.substringAfter("LifecycleResumeEffect(context, detectionRevision, hiddenRevision) {")
            .substringBefore("val selectFileTip")
        assertTrue(effect.contains("withContext(Dispatchers.IO)"))
        assertTrue(effect.contains("StockInstallPreflight.discoverTarget(context, targetMode)"))
        assertTrue(effect.contains("if (!hasRoot || !pageResumed || installMethod != InstallMethod.DirectInstall || !existingRootConflictAccepted) return@LaunchedEffect"))
        val discoveryKeys = Regex("LaunchedEffect\\(([^)]*)\\)").findAll(effect)
            .firstOrNull { it.groupValues[1].trimStart().startsWith("sourceRequest,") }
            ?.groupValues?.get(1)?.split(',')?.map(String::trim)
        assertEquals(listOf("sourceRequest", "installMethod", "targetMode", "pageResumed", "hasRoot",
            "moduleChoice.show", "moduleChoice.busy"), discoveryKeys)
        assertTrue(effect.contains("if (moduleChoice.show || moduleChoice.busy) return@LaunchedEffect"))
        assertTrue(effect.contains("pageResumed = false"))
        assertFalse("Pausing must not make the direct-install option blink", effect.contains("rootGranted = false"))
        assertTrue(effect.contains("EnvironmentProbesAdapter(context).suPresent()"))
        val rootCheck = effect.substringAfter("rootGranted = withContext(Dispatchers.IO)")
            .substringBefore("if ((!rootGranted")
        assertTrue(rootCheck.contains("managerRootAvailable("))
        assertTrue(rootCheck.contains("RootExec.current()?.capability(forceRefresh = true) ?: RootExec.capability(context)"))
        assertFalse(rootCheck.contains("UltraKeyPairedStore"))
        assertFalse(rootCheck.contains("UltraRootKeyStore"))
        assertTrue(effect.contains("catch (error: CancellationException)"))
        assertTrue(effect.contains("throw error"))
        assertTrue(effect.contains("onPauseOrDispose"))
        assertTrue(effect.contains("job.cancel()"))
    }

    @Test
    fun kernelLoadingHintExistsInBothLocales() {
        listOf("values", "values-zh-rCN").forEach { locale ->
            val file = File("src/main/res/$locale/strings_workmode.xml")
            assumeTrue(file.path, file.isFile)
            assertTrue(file.readText().contains("<string name=\"install_target_kernel_loading\">"))
        }
    }

    @Test
    fun theInstallPageNoLongerBuildsTheMigrationRoute() {
        // 只看代码：文档注释里写着"迁移入口在首页的 `Route.Migration`"是**解释**，
        // 不是这页构造了那条路由（60-ui-four-steps §2）。注释剥掉后断言从头到尾都真实。
        val source = code(screen())
        assertFalse("迁移入口在首页，不在四步流程里", source.contains("Route.Migration"))
        assertFalse(source.contains("MigrationViewModel"))
        // 「下一步」仍然只有一个落点：刷写页。
        assertEquals(1, Regex(Regex.escape("navigator.push(Route.Flash(request))")).findAll(source).count())
    }
}
