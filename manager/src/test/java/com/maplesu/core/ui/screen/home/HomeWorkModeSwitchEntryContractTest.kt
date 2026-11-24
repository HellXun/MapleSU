package com.maplesu.core.ui.screen.home

import com.maplesu.core.ui.navigation3.WorkModeNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 首页「切换工作模式」入口的 UI/导航契约（JVM，读源码做结构断言 —— 与 `WorkModeRoutesTest` 同法）。
 *
 * 为什么不是 Compose UI 测试：本仓库没有 instrumentation/Compose 测试运行器；
 * 但"入口存在、双皮肤都画、点击打开同一选择框、安装页只选目标"这些都是
 * **结构契约**，用源码断言即可稳定卡住回归（尤其是"又建了一个平行页面"）。
 */
class HomeWorkModeSwitchEntryContractTest {

    private val homeMaterial = File("src/main/java/com/maplesu/core/ui/screen/home/HomeMaterial.kt")
    private val homeMiuix = File("src/main/java/com/maplesu/core/ui/screen/home/HomeMiuix.kt")
    private val homeScreen = File("src/main/java/com/maplesu/core/ui/screen/home/HomeScreen.kt")
    private val homeViewModel = File("src/main/java/com/maplesu/core/ui/viewmodel/HomeViewModel.kt")
    private val installScreen = File("src/main/java/com/maplesu/core/ui/screen/install/InstallScreen.kt")
    private val installMaterial = File("src/main/java/com/maplesu/core/ui/screen/install/InstallMaterial.kt")
    private val installMiuix = File("src/main/java/com/maplesu/core/ui/screen/install/InstallMiuix.kt")
    private val routes = File("src/main/java/com/maplesu/core/ui/navigation3/Routes.kt")
    private val stringsZh = File("src/main/res/values-zh-rCN/strings_workmode.xml")

    private fun text(file: File): String {
        assumeTrue("${file.path} 不存在（工作目录不是 :manager？）", file.isFile)
        return file.readText()
    }

    @Test
    fun homeEntryOpensTheSharedSwitchSheet() {
        assertEquals(WorkModeNavigation.Destination.SWITCH_SHEET, WorkModeNavigation.destination)
    }

    @Test
    fun homeScreenFeedsTheSameStateToBothSkins() {
        val screen = text(homeScreen)
        assertEquals(
            "两个皮肤都必须收到同一份 workModeSwitch 状态",
            2,
            "workModeSwitch = workModeSwitch".toRegex().findAll(screen).count(),
        )
        assertTrue("皮肤拿到的状态必须来自 ViewModel 快照", screen.contains("entryFacts.workModeSwitch"))
        assertFalse(
            "组合期不得读事实（§1.5 / §10.2）：判据收到 HomeViewModel 的 IO 线程里",
            screen.contains("HomeWorkModeSwitchRuntime.current()"),
        )
        assertTrue(
            "状态必须来自 HomeWorkModeSwitchRuntime.current()（ModeRuntime.active 判据）",
            text(homeViewModel).contains("HomeWorkModeSwitchRuntime.current()"),
        )
    }

    @Test
    fun installPageUsesSelectionOnlyRatherThanStartingASwitch() {
        // 安装页可以选「目标工作模式」（Ultra / Normal），但那是**这一次修补的目标**，
        // 不是"切换当前已激活模式"：它绝不从这里发起一次模式切换（入口在首页）。
        assertFalse(text(installScreen).contains("WorkModeNavigation.open("))
        assertFalse(text(installScreen).contains("InstallTargetModeRow"))
        listOf(installMaterial, installMiuix).forEach {
            assertFalse(text(it).contains("R.string.work_mode_switch_button"))
            assertFalse(text(it).contains("InstallTargetModeRow"))
        }
    }

    @Test
    fun homeEntryNeverJumpsStraightToTheProgressPage() {
        // 入口只去流程页；进度页 Route.ModeSwitch 必须由流程页内部 push（switchId 不接受外部注入）。
        for (file in listOf(homeMaterial, homeMiuix, homeScreen)) {
            assertFalse("${file.name} 不得直接跳进度页", text(file).contains("Route.ModeSwitch"))
        }
    }

    @Test
    fun noParallelWorkModePageWasAdded() {
        val text = text(routes)
        assertEquals(
            "旧工作模式路由必须移除",
            0,
            "data object WorkMode : Route".toRegex().findAll(text).count(),
        )
        assertEquals(
            "进度页仍然只有 Route.ModeSwitch 一个",
            1,
            Regex(Regex.escape("data class ModeSwitch(")).findAll(text).count(),
        )
        assertFalse("不得新增首页专用路由", text.contains("HomeWorkMode"))
    }

    @Test
    fun entryLabelAndCurrentModeCaptionAreCorrect() {
        // 文案落在入口组件本身（两个皮肤各一份），首页只负责摆放它。
        val materialEntry = text(File("src/main/java/com/maplesu/core/ui/screen/home/HomeWorkModeSwitchMaterial.kt"))
        val miuixEntry = text(File("src/main/java/com/maplesu/core/ui/screen/home/HomeWorkModeSwitchMiuix.kt"))
        assertTrue(materialEntry.contains("WorkModeTargetRow("))
        assertTrue(materialEntry.contains("if (!state.visible || current == null) return"))
        assertTrue(materialEntry.contains("enabled = state.enabled"))
        assertTrue(miuixEntry.contains("WorkModeTargetRow("))
        assertTrue(miuixEntry.contains("if (!state.visible || current == null) return"))
        assertTrue(miuixEntry.contains("enabled = state.enabled"))
        // 首页真的把入口摆出来了（而不是写了个没人用的组件）。
        assertTrue(text(homeMaterial).contains("HomeWorkModeSwitchEntryMaterial("))
        assertTrue(text(homeMiuix).contains("HomeWorkModeSwitchEntryMiuix("))

        val zh = text(stringsZh)
        val button = Regex("""<string name="work_mode_switch_button">([^<]*)</string>""")
            .find(zh)?.groupValues?.get(1)
        assertEquals("切换工作模式", button)
        // 小字 ≤15 字：当前：Ultra / 当前：Normal
        val captions = listOf("work_mode_switch_current_lite", "work_mode_switch_current_ksu").map { key ->
            Regex("""<string name="$key">([^<]*)</string>""").find(zh)?.groupValues?.get(1)
        }
        assertEquals(listOf("当前：Ultra", "当前：Normal"), captions)
        captions.forEach { caption -> assertTrue("小字不得超过 15 字：$caption", caption!!.length <= 15) }
    }
}
