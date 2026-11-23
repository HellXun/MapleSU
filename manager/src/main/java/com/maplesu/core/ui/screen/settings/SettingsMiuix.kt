package com.maplesu.core.ui.screen.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Adb
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maplesu.core.R
import com.maplesu.core.ui.component.dialog.rememberLoadingDialog
import com.maplesu.core.ui.component.miuix.SendLogDialog
import com.maplesu.core.ui.component.uninstalldialog.UninstallDialog
import com.maplesu.core.ui.theme.LocalEnableBlur
import com.maplesu.core.ui.util.BlurredBar
import com.maplesu.core.ui.util.miuixBarColor
import com.maplesu.core.ui.util.rememberBlurBackdrop
import dev.maplesu.manager.workmode.ui.SettingsSection
import dev.maplesu.manager.workmode.ui.SettingsStatusCopy
import dev.maplesu.manager.workmode.ui.workModeNameRes
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 设置主页（Miuix）。
 *
 * 分组（§8）：外观 / 工作模式与隐藏 / 工作环境 / 通用 / 模式专属 / 卸载与关于。
 * 卡片间距统一 12dp，同一张卡片里每行都有图标，文字左缘对齐。
 * 整页只消费 [SettingsUiState]：组合期不读 prefs / JNI，首帧结构由缓存快照决定。
 *
 * @author weishu
 * @date 2023/1/1.
 */
@Composable
fun SettingPagerMiuix(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
    bottomInnerPadding: Dp,
    // true = 只渲染「界面与外观」这一张卡（独立子页面）；false = 常规设置页。
    appearanceOnly: Boolean = false,
    onBack: (() -> Unit)? = null,
    listState: LazyListState,
    topBarState: TopAppBarState,
) {
    val scrollBehavior = MiuixScrollBehavior(topBarState)
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = miuixBarColor(blurActive)
    val loadingDialog = rememberLoadingDialog()
    var showSendLogDialog by rememberSaveable { mutableStateOf(false) }
    var showUninstall by rememberSaveable { mutableStateOf(false) }
    // 2026-10-31 模式独立性：设置页只按 uiState.modePolicy 渲染；
    // 工作环境面板只在 Ultra 专属 section 可见时才构造（Normal/未判定零 Ultra SDK 探测），
    // 隐藏/隐身生效时不构造：这张卡带模式字样（不变量 §2.1）。
    val modePolicy = uiState.modePolicy
    val ultraPanel = rememberUltraEnvironmentPanel(
        enabled = !appearanceOnly && modePolicy.shows(SettingsSection.ULTRA_ULTRA_ENV) && !uiState.hiddenMasked,
    )
    val workModeEntry = uiState.workModeEntry

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(if (appearanceOnly) R.string.ui_d_appearance_title else R.string.settings),
                    navigationIcon = {
                        onBack?.let { back ->
                            IconButton(onClick = back) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(R.string.ui_d_back),
                                    tint = colorScheme.onSurface,
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                if (appearanceOnly) {
                    item(key = "appearance") {
                        AppearanceBodyMiuix(uiState, actions)
                        Spacer(
                            Modifier.height(
                                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp,
                            ),
                        )
                    }
                    return@LazyColumn
                }

                item(key = "appearance-entry") {
                    SettingsCardMiuix(Modifier.animateItem(fadeInSpec = tween(120), placementSpec = tween(160), fadeOutSpec = null)) {
                        ArrowRowMiuix(
                            title = stringResource(R.string.ui_d_appearance_title),
                            summary = stringResource(R.string.ui_d_appearance_summary),
                            icon = Icons.Rounded.Palette,
                            onClick = actions.onOpenAppearance,
                        )
                    }
                }

                // 工作模式与隐藏：同组一张卡片。入口是否可见只看 policy / WorkModeEntryPolicy。
                val showWorkMode = workModeEntry.showSettingsRow && modePolicy.shows(SettingsSection.WORK_MODE)
                val showHidden = modePolicy.shows(SettingsSection.ULTRA_HIDDEN) && uiState.liteHiddenCard.showsRow
                if (showWorkMode || showHidden) {
                    item(key = "work-mode") {
                        SettingsCardMiuix(Modifier.animateItem(fadeInSpec = tween(120), placementSpec = tween(160), fadeOutSpec = null)) {
                            if (showWorkMode) {
                                ArrowRowMiuix(
                                    title = stringResource(R.string.work_mode),
                                    summary = modePolicy.mode?.let { mode ->
                                        stringResource(R.string.work_mode_entry_summary, stringResource(workModeNameRes(mode)))
                                    } ?: stringResource(R.string.work_mode_settings_row_summary),
                                    icon = Icons.Rounded.SwapHoriz,
                                    onClick = actions.onOpenWorkMode,
                                )
                            }
                            // H3/H7：Lite 隐藏「设置项」（独立页；仅 Ultra 且未隐藏时可见）。
                            // Normal 的隐藏语义是 KSU stealth，不使用 Lite 寄生入口。
                            if (showHidden) {
                                dev.maplesu.manager.workmode.ui.LiteHiddenEntry(
                                    ui = uiState.liteHiddenCard,
                                    onClick = actions.onOpenLiteHidden,
                                )
                            }
                        }
                    }
                }

                // Ultra 专属：MapleSU_Ultra工作环境（panel 为 null 时说明未获许可，连组合都不发生）。
                ultraPanel?.let { panel ->
                    item(key = "ultra-env") {
                        Box(Modifier.animateItem(fadeInSpec = tween(120), placementSpec = tween(160), fadeOutSpec = null)) {
                            UltraEnvironmentSectionMiuix(panel)
                        }
                    }
                }

                if (modePolicy.shows(SettingsSection.ULTRA_MODULE_POLICY) && !uiState.hiddenMasked) {
                    item(key = "ultra-module-policy") {
                        val policy = uiState.ultraModulePolicy
                        SettingsCardMiuix(Modifier.animateItem()) {
                            SwitchRowMiuix(
                                title = stringResource(R.string.ultra_module_policy_load),
                                summary = stringResource(R.string.ultra_module_policy_load_summary) +
                                    policy.statusRes()?.let { "\n" + stringResource(it) }.orEmpty(),
                                icon = Icons.Rounded.Shield, checked = policy.loadRequested,
                                enabled = policy.operable, onCheckedChange = actions.onSetUltraModulePolicyLoad,
                            )
                            AnimatedVisibility(
                                visible = policy.loadRequested,
                                enter = expandVertically(tween(180)) + fadeIn(tween(120)),
                                exit = shrinkVertically(tween(180)) + fadeOut(tween(100)),
                            ) {
                                SwitchRowMiuix(
                                    title = stringResource(R.string.ultra_module_policy_hide),
                                    summary = stringResource(R.string.ultra_module_policy_hide_summary) +
                                        if (policy.hideNeedsRiskConfirmation) "\n" +
                                            stringResource(R.string.ultra_module_policy_hide_try)
                                        else if (policy.hideOperable) "" else "\n" +
                                            stringResource(policy.hideUnavailableRes()),
                                    icon = Icons.Rounded.VisibilityOff, checked = policy.hideRequested,
                                    enabled = policy.hideOperable, onCheckedChange = actions.onSetUltraModulePolicyHide,
                                )
                            }
                        }
                    }
                }

                // 通用：检查模块更新 + 软重启 + WebView 调试（两模式都消费）。
                val showModuleUpdate = modePolicy.shows(SettingsSection.MODULE_UPDATE) && uiState.managerValid
                val showTuning = modePolicy.shows(SettingsSection.GENERAL_TUNING) && uiState.managerValid
                if (showModuleUpdate || showTuning) {
                    item(key = "general") {
                            SettingsCardMiuix(Modifier.animateItem(fadeInSpec = tween(120), placementSpec = tween(160), fadeOutSpec = null)) {
                                if (showModuleUpdate) {
                                    SwitchRowMiuix(
                                        title = stringResource(R.string.settings_module_check_update),
                                        summary = stringResource(R.string.settings_check_update_summary),
                                        icon = Icons.Rounded.SystemUpdateAlt,
                                        checked = uiState.checkModuleUpdate,
                                        onCheckedChange = actions.onSetCheckModuleUpdate,
                                    )
                                }
                                if (showTuning) {
                                    SwitchRowMiuix(
                                        title = stringResource(R.string.settings_soft_reboot),
                                        summary = stringResource(R.string.settings_soft_reboot_summary),
                                        icon = Icons.Rounded.RestartAlt,
                                        checked = uiState.useSoftReboot,
                                        onCheckedChange = actions.onSetUseSoftReboot,
                                    )
                                    SwitchRowMiuix(
                                        title = stringResource(R.string.enable_web_debugging),
                                        summary = stringResource(R.string.enable_web_debugging_summary),
                                        icon = Icons.Rounded.DeveloperMode,
                                        checked = uiState.enableWebDebugging,
                                        onCheckedChange = actions.onSetEnableWebDebugging,
                                    )
                                }
                            }
                    }
                }

                // 卸载 + 日志 + 关于放在最后。
                item(key = "last") {
                    if (ultraPanel != null || modePolicy.shows(SettingsSection.LOG_AND_ABOUT)) {
                        SettingsCardMiuix(Modifier.animateItem(fadeInSpec = tween(120), placementSpec = tween(160), fadeOutSpec = null)) {
                            ultraPanel?.let { panel -> UltraUninstallRowMiuix(panel) }
                            if (modePolicy.shows(SettingsSection.LOG_AND_ABOUT)) {
                                ArrowRowMiuix(
                                    title = stringResource(R.string.send_log),
                                    summary = null,
                                    icon = Icons.Rounded.BugReport,
                                    onClick = { showSendLogDialog = true },
                                )
                                SendLogDialog(
                                    show = showSendLogDialog,
                                    onDismissRequest = { showSendLogDialog = false },
                                    loadingDialog = loadingDialog,
                                )
                                ArrowRowMiuix(
                                    title = stringResource(R.string.about),
                                    summary = null,
                                    icon = Icons.Rounded.Info,
                                    onClick = actions.onOpenAbout,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(bottomInnerPadding + 12.dp))
                }
            }
        }
    }
}

/** 卡片之间统一 12dp。 */
@Composable
private fun SettingsCardMiuix(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(160)),
        content = content,
    )
}

@Composable
private fun RowIconMiuix(icon: ImageVector, description: String?) {
    Icon(
        icon,
        modifier = Modifier.padding(end = 6.dp),
        contentDescription = description,
        tint = colorScheme.onBackground,
    )
}

@Composable
private fun ArrowRowMiuix(title: String, summary: String?, icon: ImageVector, onClick: () -> Unit) {
    ArrowPreference(
        title = title,
        summary = summary,
        startAction = { RowIconMiuix(icon, title) },
        onClick = onClick,
    )
}

@Composable
private fun SwitchRowMiuix(
    title: String,
    summary: String?,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    SwitchPreference(
        title = title,
        summary = summary,
        startAction = { RowIconMiuix(icon, title) },
        checked = checked,
        enabled = enabled,
        onCheckedChange = onCheckedChange,
    )
}




@Composable
private fun AppearanceBodyMiuix(uiState: SettingsUiState, actions: SettingsScreenActions) {
    AppearanceSettingsBody(uiState, actions)
}
