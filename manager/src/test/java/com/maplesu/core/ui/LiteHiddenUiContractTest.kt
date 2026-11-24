package com.maplesu.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * H3/H7 隐藏设置页的 UI 契约测试（源码/资源断言，JVM）：
 * 设置项→独立页导航存在；文案/长度/禁止词；开启后入口不可见；确认弹窗与恢复链接按钮。
 */
class LiteHiddenUiContractTest {

    private fun read(path: String): String? = File(path).takeIf { it.isFile }?.readText()

    private fun sourceAvailable(vararg paths: String): Boolean = paths.all { read(it) != null }

    @Test
    fun settingsRowNavigatesToDedicatedHiddenPage() {
        assumeTrue(sourceAvailable(
            "src/main/java/com/maplesu/core/ui/navigation3/Routes.kt",
            "src/main/java/com/maplesu/core/ui/MainActivity.kt",
            "src/main/java/com/maplesu/core/ui/screen/settings/SettingsScreen.kt",
        ))
        val routes = read("src/main/java/com/maplesu/core/ui/navigation3/Routes.kt")!!
        val main = read("src/main/java/com/maplesu/core/ui/MainActivity.kt")!!
        val settings = read("src/main/java/com/maplesu/core/ui/screen/settings/SettingsScreen.kt")!!
        assertTrue("Routes 缺 LiteHidden", routes.contains("data object LiteHidden : Route"))
        assertTrue("MainActivity 缺 entry<Route.LiteHidden>", main.contains("entry<Route.LiteHidden>"))
        assertTrue("设置页未跳隐藏页", settings.contains("navigator.push(Route.LiteHidden)"))
    }

    @Test
    fun settingsShowsSingleRowEntryNotInlineCard() {
        val miuix = read("src/main/java/com/maplesu/core/ui/screen/settings/SettingsMiuix.kt")
        val material = read("src/main/java/com/maplesu/core/ui/screen/settings/SettingsMaterial.kt")
        assumeTrue(miuix != null && material != null)
        listOf(miuix!!, material!!).forEach { text ->
            assertTrue("必须用设置项入口 LiteHiddenEntry", text.contains("LiteHiddenEntry("))
            assertFalse("不得内嵌整张隐藏卡", text.contains("LiteHiddenCard("))
            assertTrue(text.contains("onOpenLiteHidden"))
        }
    }

    @Test
    fun zhCopyIsShortAndExact() {
        val zh = read("src/main/res/values-zh-rCN/maplesu_litehidden.xml")
        assumeTrue(zh != null)
        val text = zh!!
        assertTrue(text.contains("<string name=\"lite_hidden_title\">隐身与寄生</string>"))
        assertTrue(text.contains("开启隐藏模式"))
        assertTrue(text.contains("开启后所有管理将寄生至 WebUI"))
        assertTrue(text.contains("WebUI 端口"))
        assertTrue(text.contains("WebUI 链接"))
        assertTrue(text.contains("开启隐藏？"))
        assertTrue(text.contains("将寄生至所选应用，请保存 WebUI 链接"))
        assertTrue("宿主选择页标题", text.contains("选择寄生应用"))
        assertTrue("宿主选择页说明", text.contains("作为管理入口的启动载体"))
        assertTrue("一行风险提示", text.contains("会修改该应用原生库，可解除"))
        assertTrue(text.contains("解除寄生"))
        assertTrue(text.contains("复制恢复链接"))
    }

    @Test
    fun hintIsWithinFifteenCharacters() {
        val zh = read("src/main/res/values-zh-rCN/maplesu_litehidden.xml")
        assumeTrue(zh != null)
        val hint = Regex("<string name=\"lite_hidden_button_hint\">([^<]*)</string>")
            .find(zh!!)?.groupValues?.get(1)
        assertEquals("开启后所有管理将寄生至 WebUI", hint)
        val cjk = hint!!.trim().count { it.code in 0x4E00..0x9FFF }
        assertTrue("小字（中文字符）必须 ≤15 字", cjk <= 15)
    }

    @Test
    fun webUiLinkRowExistsAndCopiesOnTap() {
        val screen = read("src/main/java/dev/maplesu/manager/workmode/ui/LiteHiddenScreen.kt")
        val vm = read("src/main/java/dev/maplesu/manager/workmode/ui/LiteHiddenViewModel.kt")
        assumeTrue(screen != null && vm != null)
        val s = screen!!
        val v = vm!!
        assertTrue("链接行缺失", s.contains("WebUiLinkRow("))
        assertTrue(s.contains("lite_hidden_webui_link"))
        assertTrue("整行点击复制", s.contains("clickable(enabled = ui.webUiLinkAvailable, onClick = onCopy)"))
        assertTrue("链接来自只读预览", s.contains("ui.webUiLink"))
        assertTrue("复制未接 VM", s.contains("copyPreviewLink"))
        assertTrue("有独立浏览器入口", s.contains("viewModel::openRecoveryLink"))
        assertTrue("准备恢复链接动作接线", s.contains("viewModel::prepareRecoveryLink"))
        assertTrue("仅真实探活后解锁", v.contains("HttpHiddenDaemonProbe().ping"))
        assertTrue("复制成功后才能确认", v.contains("recoveryLinkSaved = true"))
        assertTrue("无 WebView 预览块", !s.contains("WebView("))
        assertTrue("链接预览按端口/token 生成", v.contains("webUiLinkPreview"))
    }

    @Test
    fun webUiLinkDegradesWhenUnavailable() {
        val vm = read("src/main/java/dev/maplesu/manager/workmode/ui/LiteHiddenViewModel.kt")
        val zh = read("src/main/res/values-zh-rCN/maplesu_litehidden.xml")
        assumeTrue(vm != null && zh != null)
        assertTrue("无链接必须显式提示", vm!!.contains("R.string.lite_hidden_no_link"))
        assertTrue("复制成功提示", zh!!.contains("链接已复制"))
        assertTrue("不可用显示 —", vm.contains("webUiLinkPreview"))
    }

    @Test
    fun hiddenPageHasNoDialCodeOrRecoveryCodeElements() {
        val screen = read("src/main/java/dev/maplesu/manager/workmode/ui/LiteHiddenScreen.kt")
        val vm = read("src/main/java/dev/maplesu/manager/workmode/ui/LiteHiddenViewModel.kt")
        val zh = read("src/main/res/values-zh-rCN/maplesu_litehidden.xml")
        assumeTrue(screen != null && vm != null && zh != null)
        listOf(screen!!, vm!!, zh!!).forEach { text ->
            assertFalse("隐藏页不得有密令/拨号元素", text.contains("恢复密令") || text.contains("拨号"))
            assertFalse("隐藏页不得有 dialCode", text.contains("dialCode"))
        }
    }

    @Test
    fun noForbiddenWordsInHiddenResources() {
        val zh = read("src/main/res/values-zh-rCN/maplesu_litehidden.xml")
        assumeTrue(zh != null)
        listOf("演练", "伪卸载", "痕迹", "等级", "高级", "Intent", "Logcat", "恢复端口").forEach {
            assertFalse("禁止出现「$it」", zh!!.contains(it))
        }
    }

    @Test
    fun confirmDialogHasCopyRecoveryLink() {
        val screen = read("src/main/java/dev/maplesu/manager/workmode/ui/LiteHiddenScreen.kt")
        assumeTrue(screen != null)
        val text = screen!!
        assertTrue(text.contains("lite_hidden_confirm_title"))
        assertTrue(text.contains("lite_hidden_confirm_body"))
        assertTrue(text.contains("lite_hidden_confirm_enable"))
        assertTrue(text.contains("lite_hidden_confirm_cancel"))
        assertTrue(text.contains("lite_hidden_copy_link"))
        assertTrue(text.contains("onCopyRecoveryLink"))
    }

    @Test
    fun pageDisappearsAfterEnable() {
        val screen = read("src/main/java/dev/maplesu/manager/workmode/ui/LiteHiddenScreen.kt")
        val policy = read("src/main/java/dev/maplesu/manager/litehidden/HiddenModePolicy.kt")
        assumeTrue(screen != null && policy != null)
        assertTrue("开启成功后必须退回/消失", screen!!.contains("LaunchedEffect(ui.hidden)"))
        assertTrue(policy!!.contains("fun cardVisible"))
        assertTrue(policy.contains("!state.isEffectivelyOn"))
    }

    @Test
    fun portRowIsRightAlignedEditableInPage() {
        val screen = read("src/main/java/dev/maplesu/manager/workmode/ui/LiteHiddenScreen.kt")
        assumeTrue(screen != null)
        val text = screen!!
        assertTrue(text.contains("BasicTextField("))
        assertTrue(text.contains("TextAlign.End"))
        assertTrue(text.contains("lite_hidden_webui_port"))
        assertFalse("端口不叫恢复端口", text.contains("恢复端口"))
    }

    @Test
    fun statusRowsUseOffOnCopy() {
        val zh = read("src/main/res/values-zh-rCN/maplesu_litehidden.xml")
        val page = read("src/main/java/dev/maplesu/manager/workmode/ui/LiteHiddenViewModel.kt")
        assumeTrue(zh != null && page != null)
        assertTrue(zh!!.contains(">未开启</string>"))
        assertTrue(zh.contains(">已开启</string>"))
        assertTrue(page!!.contains("R.string.lite_hidden_status_off"))
        assertTrue(page.contains("R.string.lite_hidden_status_on"))
    }

    @Test
    fun hiddenEntryInvisibleAfterSuccess() {
        val settingsVm = read("src/main/java/com/maplesu/core/ui/viewmodel/SettingsViewModel.kt")
        assumeTrue(settingsVm != null)
        val text = settingsVm!!
        assertTrue(
            "设置项可见性必须与隐藏状态联动（隐藏后 visible=false）",
            text.contains("visible = !hidden"),
        )
        // 2026-10-31 模式独立性：Lite 隐藏状态只允许 Ultra 读取（Normal 走 KSU stealth）。
        assertTrue(
            "隐藏状态读取必须受模式策略许可",
            text.contains("mayRead(SettingsDataSource.LITE_HIDDEN)"),
        )
        assertTrue("隐藏状态必须用三态文案", text.contains("LiteHiddenStatus.of"))
    }
}

class WebUiSelfCheckUiContractTest {
    private fun read(path: String): String? = File(path).takeIf { it.isFile }?.readText()

    @Test
    fun selfCheckRouteAndEntryExistInBothModes() {
        val routes = read("src/main/java/com/maplesu/core/ui/navigation3/Routes.kt")
        val main = read("src/main/java/com/maplesu/core/ui/MainActivity.kt")
        val vm = read("src/main/java/com/maplesu/core/ui/viewmodel/SettingsViewModel.kt")
        assumeTrue(routes != null && main != null && vm != null)
        assertTrue(routes!!.contains("data object WebUiSelfCheck : Route"))
        assertTrue(main!!.contains("entry<Route.WebUiSelfCheck>"))
        assertTrue(main.contains("WebUiSelfCheckScreen("))
        // 自检入口是通用项（A/B 后端不同），但仍必须由模式策略门控；隐藏后不可见。
        assertTrue(vm!!.contains("showWebUiSelfCheck"))
        assertTrue(vm.contains("policy.shows(SettingsSection.WEBUI_SELF_CHECK)"))
    }

    @Test
    fun selfCheckFailureReasonsAreShownHonestly() {
        val vm = read("src/main/java/dev/maplesu/manager/workmode/ui/WebUiSelfCheckViewModel.kt")
        val zh = read("src/main/res/values-zh-rCN/maplesu_webui_selfcheck.xml")
        assumeTrue(vm != null && zh != null)
        assertTrue(vm!!.contains("BACKEND_UNAVAILABLE") || vm.contains("MODE_BACKEND_UNAVAILABLE"))
        assertTrue(vm.contains("fail: "))
        assertTrue(zh!!.contains("WebUI 自检"))
        listOf("启动 WebUI", "检查状态", "记录重启验证", "恢复演练", "生成恢复备份").forEach {
            assertTrue("短文案缺失 $it", zh.contains(it))
        }
    }

    @Test
    fun selfCheckEntriesAreShortAndNoDialCode() {
        val zh = read("src/main/res/values-zh-rCN/maplesu_webui_selfcheck.xml")
        assumeTrue(zh != null)
        val text = zh!!
        assertFalse("不得出现密令/拨号", text.contains("密令") || text.contains("拨号"))
        val values = Regex("<string [^>]*>([^<]*)</string>").findAll(text).map { it.groupValues[1] }.toList()
        assertTrue("每行 ≤15 字", values.all { it.trim().length <= 15 })
    }
}
