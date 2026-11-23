package dev.maplesu.manager.workmode.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 设置页模式独立性的 **UI 契约**（源码/资源断言，纯 JVM）。
 *
 * 覆盖：两套皮肤只按 SettingsModePolicy 渲染、数据读取先过 mayRead()、Ultra 面板仅在
 * Ultra 构造、隐藏状态行只陈述事实、关键行文案存在且 ≤15 字。
 */
class SettingsModeUiContractTest {

    private fun read(path: String): String? = File(path).takeIf { it.isFile }?.readText()

    private fun readAll(vararg paths: String): String? {
        val texts = paths.mapNotNull { read(it) }
        return if (texts.size == paths.size) texts.joinToString("\n") else null
    }

    private fun zhValue(name: String): String? {
        val text = readAll(
            "src/main/res/values-zh-rCN/strings.xml",
            "src/main/res/values-zh-rCN/strings_workmode.xml",
            "src/main/res/values-zh-rCN/maplesu_litehidden.xml",
        ) ?: return null
        return Regex("<string name=\"$name\"[^>]*>([^<]*)</string>")
            .find(text)?.groupValues?.get(1)?.trim()
    }

    private fun enValue(name: String): String? {
        val text = readAll(
            "src/main/res/values/strings.xml",
            "src/main/res/values/strings_workmode.xml",
            "src/main/res/values/maplesu_litehidden.xml",
        ) ?: return null
        return Regex("<string name=\"$name\"[^>]*>([^<]*)</string>")
            .find(text)?.groupValues?.get(1)?.trim()
    }

    @Test
    fun bothSkinsRenderOnlyThroughPolicy() {
        val material = read("src/main/java/com/maplesu/core/ui/screen/settings/SettingsMaterial.kt")
        val miuix = read("src/main/java/com/maplesu/core/ui/screen/settings/SettingsMiuix.kt")
        assumeTrue(material != null && miuix != null)
        listOf(material!!, miuix!!).forEach { text ->
            assertTrue("皮肤必须从 uiState 取唯一裁决", text.contains("val modePolicy = uiState.modePolicy"))
            assertFalse("皮肤不得自行读 ModeRuntime.active", text.contains("ModeRuntime.active"))
            // 模式专属 section 必须逐一被 policy 门控（不是渲染后隐藏）。
            listOf(
                "SettingsSection.MODULE_UPDATE",
                "SettingsSection.SU_COMPAT",
                "SettingsSection.GENERAL_TUNING",
                "SettingsSection.WORK_MODE",
                "SettingsSection.KSU_FEATURES",
                "SettingsSection.KSU_STEALTH",
                "SettingsSection.KSU_APP_LOCK",
                "SettingsSection.KSU_WEB_ADMIN",
                "SettingsSection.KSU_APP_PROFILE",
                "SettingsSection.KSU_KERNEL_MODE",
                "SettingsSection.ULTRA_ULTRA_ENV",
                "SettingsSection.ULTRA_HIDDEN",
                "SettingsSection.LOG_AND_ABOUT",
            ).forEach { section ->
                assertTrue("皮肤缺 $section 门控", text.contains(section))
            }
            assertTrue(
                "Ultra 面板构造必须带 Ultra 许可位",
                text.contains("enabled = !appearanceOnly && modePolicy.shows(SettingsSection.ULTRA_ULTRA_ENV) && !uiState.hiddenMasked"),
            )
            assertTrue("Ultra 面板必须可空渲染", text.contains("ultraPanel?.let {"))
            assertTrue("不得在组合期读取管理器 JNI", !text.contains("KsuIsValid {"))
            // 新增的 Normal 专属行在两套皮肤都要有。
            assertTrue(text.contains("settings_profile_template"))
            assertTrue(text.contains("settings_kernel_mode"))
            assertTrue(text.contains("actions.onOpenProfileTemplate"))
        }
    }

    @Test
    fun viewModelReadsOnlyAfterPolicyGate() {
        val vm = read("src/main/java/com/maplesu/core/ui/viewmodel/SettingsViewModel.kt")
        assumeTrue(vm != null)
        val text = vm!!
        assertTrue("VM 必须用模式策略", text.contains("SettingsModePolicy.forMode"))
        assertTrue(text.contains("modePolicy = policy"))

        // 模式专属读取必须在 mayRead() 之后（源码顺序即调用顺序）。
        val stealthGate = text.indexOf("mayRead(SettingsDataSource.KSU_STEALTH)")
        val stealthCall = text.indexOf("stealthRaw = if (readKsuStealth) Stealth.kernelState()")
        assertTrue("缺 KSU stealth 许可位", stealthGate >= 0)
        assertTrue("缺 stealth 读取", stealthCall >= 0)
        assertTrue("Stealth.kernelState() 必须先过 mayRead(KSU_STEALTH)", stealthGate < stealthCall)

        val hiddenGate = text.indexOf("mayRead(SettingsDataSource.LITE_HIDDEN)")
        val hiddenCall = text.indexOf("if (startupHiddenRead) LiteHiddenRuntimeProxy.readState()")
        assertTrue("缺 Lite 隐藏许可位", hiddenGate >= 0)
        assertTrue("缺 Lite 隐藏读取", hiddenCall >= 0)
        assertTrue("LiteHiddenRuntimeProxy.readState() 必须先过 mayRead(LITE_HIDDEN)", hiddenGate < hiddenCall)

        // 旧缺陷：Ultra 下调 Stealth.isEnabled()（LITE 恒 false），Normal 下构造 Ultra 面板。
        assertFalse("设置 VM 不得再直接调用 Stealth.isEnabled()", text.contains("Stealth.isEnabled()"))
        assertFalse("设置 VM 不应触碰 Ultra 环境探测", text.contains("UltraEnvironmentProbe"))
        assertTrue(text.contains("val readBoot = if (policy.mode == dev.maplesu.manager.workmode.WorkMode.LITE)"))
        assertTrue(text.contains("UltraEnvironmentAutoSetup.currentBootId() else null"))
        assertFalse("设置 VM 不得无条件读 Model 六项", text.contains("if (capabilities.supportsKernelUmount) {"))
    }

    @Test
    fun ultraPanelIsOnlyConstructedForUltra() {
        val ultra = read("src/main/java/com/maplesu/core/ui/screen/settings/UltraEnvironmentSettings.kt")
        assumeTrue(ultra != null)
        val text = ultra!!
        assertTrue(
            "rememberUltraEnvironmentPanel 必须是可空 + enabled 参数",
            text.contains("fun rememberUltraEnvironmentPanel(enabled: Boolean): UltraEnvironmentPanel?"),
        )
        assertTrue(text.contains("if (!enabled) return null"))
        assertTrue(
            "许可位判断必须发生在任何 Ultra 探测之前",
            text.indexOf("if (!enabled) return null") <
                text.indexOf("initialSnapshot = UltraEnvironmentProbe.currentSnapshot(context.applicationContext)"),
        )
        assertTrue(
            "许可位判断必须发生在 loadInitialState 之前",
            text.indexOf("if (!enabled) return null") < text.indexOf("panel.loadInitialState()"),
        )
    }

    @Test
    fun hiddenStatusLineUsesFactOnlyCopy() {
        val card = read("src/main/java/dev/maplesu/manager/workmode/ui/LiteHiddenCard.kt")
        assumeTrue(card != null)
        val text = card!!
        assertTrue("隐藏入口状态行必须走三态文案", text.contains("LiteHiddenStatusPolicy.statusRes(ui.status)"))
        assertFalse("不得再把状态行写死成「未开启」", text.contains("R.string.lite_hidden_status_off"))

        val copy = read("src/main/java/dev/maplesu/manager/workmode/ui/SettingsStatusCopy.kt")
        assumeTrue(copy != null)
        assertTrue(
            "UNKNOWN 必须映射到「状态未知」资源",
            copy!!.contains("SettingsStatus.UNKNOWN -> R.string.settings_status_unknown"),
        )
        assertTrue(copy.contains("SettingsStatus.ON -> R.string.settings_status_on"))
    }

    @Test
    fun policyStaysPureKotlin() {
        val policy = read("src/main/java/dev/maplesu/manager/workmode/ui/SettingsModePolicy.kt")
        assumeTrue(policy != null)
        val text = policy!!
        assertFalse("策略不得依赖 Android", text.contains("import android."))
        assertFalse("策略不得依赖 Compose", text.contains("import androidx."))
        assertFalse("策略不得依赖 R", text.contains("com.maplesu.core.R"))
    }

    @Test
    fun keyRowCopyExistsAndIsShort() {
        val expected = linkedMapOf(
            "settings_selinux_hide" to "隐藏 SELinux 修改",
            "settings_umount_modules_default" to "默认卸载模块",
            "settings_sucompat" to "传统 SU 命令支持",
            "settings_profile_template" to "App Profile 模板",
            "work_mode_webadmin_title" to "网页管理器",
            "settings_module_check_update" to "检查模块更新",
            "settings_kernel_mode" to "内核模式",
            "work_mode_stealth_title" to "隐身",
            "lite_hidden_title" to "隐身与寄生",
        )
        expected.forEach { (key, copy) ->
            assertEquals("关键行文案不符：$key", copy, zhValue(key))
            assertTrue("关键行文案必须 ≤15 字：$key=$copy", copy.length <= 15)
        }
    }

    @Test
    fun hiddenEntryAndPageTitlesReuseModeSpecificCopyAcrossBothSkins() {
        val entry = read("src/main/java/dev/maplesu/manager/workmode/ui/LiteHiddenCard.kt")
        val page = read("src/main/java/dev/maplesu/manager/workmode/ui/LiteHiddenScreen.kt")
        val material = read("src/main/java/com/maplesu/core/ui/screen/settings/SettingsMaterial.kt")
        val miuix = read("src/main/java/com/maplesu/core/ui/screen/settings/SettingsMiuix.kt")
        assumeTrue(entry != null && page != null && material != null && miuix != null)

        assertEquals("隐身与寄生", zhValue("lite_hidden_title"))
        assertEquals("隐身", zhValue("work_mode_stealth_title"))
        assertEquals("Stealth and hosting", enValue("lite_hidden_title"))
        assertEquals("Stealth", enValue("work_mode_stealth_title"))
        assertTrue("Ultra 入口与目标页必须共用标题资源", entry!!.contains("stringResource(R.string.lite_hidden_title)"))
        assertEquals(
            "Ultra 目标页两套皮肤必须共用入口标题资源",
            2,
            Regex("stringResource\\(R\\.string\\.lite_hidden_title\\)").findAll(page!!).count(),
        )
        listOf(material!!, miuix!!).forEach { skin ->
            assertTrue("两套皮肤必须复用 Ultra 隐身与寄生入口", skin.contains("LiteHiddenEntry("))
            assertTrue("Normal 标题不得复用 Ultra 的寄生文案", skin.contains("stringResource(R.string.work_mode_stealth_title)"))
            assertFalse("设置皮肤不得绕过 Ultra 共用入口单写标题", skin.contains("R.string.lite_hidden_title"))
        }
    }

    @Test
    fun statusCopyIsHonestAndTranslated() {
        assertEquals("已开启", zhValue("settings_status_on"))
        assertEquals("未开启", zhValue("settings_status_off"))
        val unknown = zhValue("settings_status_unknown")
        assertNotNull("缺 UNKNOWN 文案", unknown)
        assertTrue("UNKNOWN 必须说读取失败", unknown!!.contains("状态未知"))
        assertFalse("UNKNOWN 不得写成已开启", unknown == zhValue("settings_status_on"))

        // values/ 与 values-zh-rCN/ 的 workmode 键必须一一对应（该文件头部的约定）。
        listOf(
            "settings_status_on",
            "settings_status_off",
            "settings_status_unknown",
            "settings_kernel_mode",
            "settings_kernel_mode_lkm",
            "settings_kernel_mode_builtin",
        ).forEach { key ->
            assertNotNull("values/strings_workmode.xml 缺 $key", enValue(key))
            assertNotNull("values-zh-rCN/strings_workmode.xml 缺 $key", zhValue(key))
        }
    }

    @Test
    fun settingsUsesSharedSheetAndMaskedCache() {
        val screen = read("src/main/java/com/maplesu/core/ui/screen/settings/SettingsScreen.kt")
        val vm = read("src/main/java/com/maplesu/core/ui/viewmodel/SettingsViewModel.kt")
        assumeTrue(screen != null && vm != null)
        assertTrue(screen!!.contains("WorkModeSwitchSheet(show = showWorkModeSheet && !uiState.hiddenMasked"))
        assertFalse(screen.contains("navigator.push(Route.WorkMode)"))
        assertFalse(screen.contains("currentWorkModeEntryPolicy()"))
        assertTrue(vm!!.contains("SettingsSnapshotCache.initial(policy)"))
        assertTrue(vm.contains("viewModelScope.launch(Dispatchers.IO)"))
        assertTrue(vm.contains("HiddenFactsSignal.revision.collectLatest"))
        assertTrue(vm.contains("if (hiddenRevision != com.maplesu.core.ui.security.HiddenFactsSignal.revision.value ||"))
        assertTrue(vm.contains("fun currentReadScope(): Boolean = policy.mode == dev.maplesu.manager.workmode.ModeRuntime.active"))
        assertTrue(vm.contains("ModeRuntime.activation === readActivation"))
        assertTrue(vm.contains("UltraEnvironmentAutoSetup.currentBootId() == readBoot"))
        assertTrue(vm.contains("readFeaturePort.readRevision == readFeatureRevision"))
        assertEquals(2, Regex("!currentReadScope\\(\\)").findAll(vm).count())
        assertTrue(vm.contains("workModeEntry = if (hiddenMasked) WorkModeEntryPolicy.NONE"))
    }

    @Test
    fun environmentActionsUseSdkTemporaryRemovalAndStockPermanentRemoval() {
        val ultra = read("src/main/java/com/maplesu/core/ui/screen/settings/UltraEnvironmentSettings.kt")
        val chooser = read("src/main/java/com/maplesu/core/ui/component/uninstalldialog/UninstallDialog.kt")
        assumeTrue(ultra != null && chooser != null)
        val uninstaller = read("src/main/java/dev/maplesu/manager/runtime/ultra/UltraEnvironmentUninstaller.kt")
        assumeTrue(uninstaller != null)
        assertTrue(ultra!!.contains("fun removeEnvironment() = uninstall(permanent = true)"))
        assertTrue(ultra.contains("fun suspendLocal() = uninstall(permanent = false)"))
        assertTrue(uninstaller!!.contains("removeSdk = client::uninstallEnvironment"))
        assertTrue(uninstaller.contains("InstallerServiceClient.restoreStockImages"))
        assertFalse(ultra.contains("UltraRootKeyStore.suspend(context)"))
        assertTrue(ultra.contains("UltraRootKeyStore.allowEnvironmentInstall(context)"))
        assertTrue(ultra.contains("UninstallChooser("))
        assertFalse(ultra.contains("Color(0x"))
        assertTrue(chooser!!.contains("rememberConfirmDialog("))
        assertTrue(chooser.contains("confirmDialog.showConfirm(title = choice.title, content = choice.message)"))
        assertTrue(chooser.contains("FlashIt.FlashUninstall"))
        assertTrue(chooser.contains("FlashIt.FlashRestore"))
    }

    @Test
    fun permanentUninstallUsesTheSameDurableTaskForExecutionAndReboot() {
        val screen = requireNotNull(read("src/main/java/com/maplesu/core/ui/screen/flash/FlashScreen.kt"))
        val dispatch = requireNotNull(read("src/main/java/com/maplesu/core/ui/screen/flash/FlashUtils.kt"))
        val cli = requireNotNull(read("src/main/java/com/maplesu/core/ui/util/KsuCli.kt"))
        val chooser = requireNotNull(read("src/main/java/com/maplesu/core/ui/component/uninstalldialog/UninstallDialog.kt"))
        assertTrue(screen.contains("val restoringInstallation = flashIt == FlashIt.FlashRestore || flashIt == FlashIt.FlashUninstall"))
        assertTrue(screen.contains("flashIt is FlashIt.DownloadBoot || restoringInstallation"))
        assertTrue(screen.contains("installation.attach(context.applicationContext, installationTaskId, resume,"))
        assertTrue(dispatch.contains("FlashIt.FlashUninstall -> uninstallPermanently(onStdout, onStderr, installationTaskId)"))
        val uninstall = cli.substringAfter("fun uninstallPermanently(").substringBefore("@Parcelize")
        assertTrue(uninstall.contains("restoreBoot(onStdout, onStderr, installationTaskId)"))
        assertFalse(chooser.contains("R.string.ui_polish_uninstall_not_ready"))
    }

}
