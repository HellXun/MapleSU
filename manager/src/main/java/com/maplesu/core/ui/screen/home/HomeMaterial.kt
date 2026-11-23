package com.maplesu.core.ui.screen.home

import com.maplesu.core.ui.screen.settings.UltraEnvironmentInstallCardMaterial
import com.maplesu.core.ui.screen.settings.rememberUltraEnvironmentInstallPanel
import com.maplesu.core.ui.screen.settings.UltraEnvironmentUpdatePanel
import com.maplesu.core.ui.screen.settings.UltraEnvironmentUpdateCardMaterial
import com.maplesu.core.ui.screen.settings.ultraEnvironmentLabel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maplesu.core.KernelVersion
import com.maplesu.core.Natives
import com.maplesu.core.ui.component.maple.*
import com.maplesu.core.R
import com.maplesu.core.ui.component.WarningLevel
import com.maplesu.core.ui.component.dialog.rememberConfirmDialog
import com.maplesu.core.ui.component.material.ExpressiveScaffold
import com.maplesu.core.ui.component.material.SegmentedColumn
import com.maplesu.core.ui.component.material.SegmentedListItem
import com.maplesu.core.ui.component.material.WarningCard
import com.maplesu.core.ui.component.material.expressiveTopAppBarColors
import com.maplesu.core.ui.component.rebootlistpopup.RebootListPopup
import com.maplesu.core.ui.component.statustag.StatusTag
import com.maplesu.core.ui.util.LegacyDataMigration
import com.maplesu.core.ui.util.rememberMigrationRestartPending
import com.maplesu.core.ui.util.StatusDecorationImage
import com.maplesu.core.ui.util.WallpaperPrefs
import com.maplesu.core.data.repository.SettingsRepositoryImpl

@Composable
fun HomePagerMaterial(
    state: HomeUiState,
    actions: HomeActions,
    bottomInnerPadding: Dp,
    // mode-gate: 工作模式入口（04-ui-contract §2.2 入口 B）；可见性由 decideWorkModeEntry 算好传入。
    workModeEntry: dev.maplesu.manager.workmode.ui.WorkModeEntryPolicy =
        dev.maplesu.manager.workmode.ui.WorkModeEntryPolicy.NONE,
    onOpenWorkMode: () -> Unit = {},
    // 用户要求(2026-10-30)：首页「切换工作模式」入口必须是**看得见的按钮**，
    // 不能只靠 workModeEntry 那张卡（判定态不满足时它整个不画）。判据见 HomeWorkModeSwitchState。
    workModeSwitch: HomeWorkModeSwitchState = HomeWorkModeSwitchState.HIDDEN,
    aboutShowsModes: Boolean = false,
    // 外部 Root 已确认可用时，进入共用的直接安装流程。
    ultra: UltraWordmarkState? = null,
    // Ultra 环境版本与更新提示；非 Ultra / 隐藏时为 null。
    ultraEnvironment: UltraEnvironmentUpdatePanel? = null,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    // 「旧版 7kimisu」提示卡要用它拉起系统卸载界面(v2.2)
    val context = androidx.compose.ui.platform.LocalContext.current
    // ⚠️ v0.13.157:这里原来有个 `stealthOn`(隐身时藏「关于 7kimisu」/「支持开发·了解 KernelSU」
    //    两张卡),**已删除**。用户判据:这两张卡是"一个**没有 root 的用户**点进来"就会看到的
    //    东西,隐身必须照常显示 —— 隐身只改"显示什么内容"(比如安装页里需要 root 的两个方法),
    //    不该把没 root 的人也看得到的卡片藏掉。
    //    (「伪装成内核未就绪」仍然由 HomeViewModel 把 UiState 归一化完成,
    //     见 HomeUiState.asStealthKernelNotReady —— 那是内容侧,不是外观侧。)

    ExpressiveScaffold(
        topBar = {
            TopBar(
                scrollBehavior = scrollBehavior,
                ultra = ultra,
                showReboot = com.maplesu.core.ui.util.RebootDispatcher.showMenu(
                    state.liteInstalled || state.isManager && state.ksuVersion != null,
                    state.showLiteChecking,
                ),
                lateLoad = state.isLateLoadMode,
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            HomeBetaBanner(onClick = actions.onFeedbackClick)
            if (state.showSafeModeBanner) {
                HomeSafeModeBanner(onManageModules = actions.onManageModules)
            }
            if (state.checkUpdateEnabled) {
                HomeManagerUpdateBannerMaterial(state = state, actions = actions)
            }
            if (state.showManagerPrBuildWarning) {
                WarningCard(stringResource(id = R.string.home_pr_build_warning), level = WarningLevel.Notice)
            } else if (state.showKernelPrBuildWarning) {
                WarningCard(stringResource(id = R.string.home_pr_kernel_warning), level = WarningLevel.Notice)
            }
            if (state.showGkiWarning) {
                WarningCard(stringResource(id = R.string.home_gki_warning), level = WarningLevel.Notice)
            }
            if (state.requiresNewKernel) {
                WarningCard(
                    stringResource(
                        id = if (state.lkmMode == true) R.string.require_kernel_version else R.string.require_kernel_version_gki
                    ),
                    // 隐身下本来就不会出现(requiresNewKernel = isManager && …)
                    onClick = if (state.lkmMode == true) actions.onInstallClick else null
                )
            }
            if (state.requiresNewManager) {
                WarningCard(
                    stringResource(
                        id = R.string.require_manager_version
                    )
                )
            }
            if (state.showLkmUpdate) {
                WarningCard(
                    message = stringResource(R.string.home_lkm_update_available),
                    level = WarningLevel.Notice,
                    onClick = actions.onInstallClick,
                )
            }
            if (state.showRootWarning) {
                WarningCard(stringResource(id = R.string.grant_root_failed))
            }
            // 🔴 D3(v2.2)：数据目录刚迁移过 → 提示"请重启一次设备"。
            // 与 Miuix 版语义完全一致，见 HomeMiuix.kt 同名段落。
            if (rememberMigrationRestartPending()) {
                WarningCard(
                    message = stringResource(id = R.string.migration_restart_notice),
                    onClick = { LegacyDataMigration.acknowledgeRestartNotice(context) },
                )
            }
            // P0：Lite 已安装但后台 root 获取失败 → 状态卡（图片）**上方**固定一行文案。
            // 用现有警告风格（WarningLevel.Error，与 grant_root_failed 同款），双皮肤一致；
            // 条件走 showLiteRootFailure：未安装 / KSU / 隐身一律不画，安装态绝不回退成未安装。
            if (state.showLiteRootFailure) {
                WarningCard(message = stringResource(R.string.home_lite_root_failure))
            }
            if (state.showEnvironmentRefreshWarning) {
                val summary = stringResource(when (state.environmentFailure) {
                    HomeEnvironmentFailure.TIMED_OUT -> R.string.home_environment_check_timeout_summary
                    HomeEnvironmentFailure.UNCONFIRMED -> R.string.home_environment_check_unconfirmed_summary
                    HomeEnvironmentFailure.BUSY -> R.string.home_environment_check_busy_summary
                    else -> R.string.home_environment_check_failed_summary
                })
                WarningCard(
                    message = stringResource(R.string.home_environment_refresh_warning, summary),
                    level = WarningLevel.Notice,
                    onClick = actions.onEnvironmentRetry,
                )
            }
            ThemeHomeStatus(state, actions) { MaterialStatusCard(
                state = state,
                actions = actions,
                ultraVersionLabel = ultraEnvironment?.let { ultraEnvironmentLabel(it.version) },
            ) }
            // 「发现 Ultra 环境更新」：紧贴状态卡下方，写法与交互同 KSU 的管理器更新卡。
            if (ultraEnvironment != null) {
                UltraEnvironmentUpdateCardMaterial(ultraEnvironment)
            }
            // 「安装 / 激活系统环境」：2026-10-29 按用户要求从设置页移到首页。
            UltraEnvironmentInstallCardMaterial(
                rememberUltraEnvironmentInstallPanel(state.environmentMode),
                onInstallImage = actions.onInstallClick,
            )
            InfoCard(systemInfo = state.systemInfo)
            About7kCard(showModes = aboutShowsModes)
            // 底栏恒在，bottomInnerPadding 已含导航条高度；不再按 root 就绪与否加减，
            // 否则模式判定落定时列表底部会跳一下，组合期也不用再碰 Natives。
            Spacer(Modifier.height(bottomInnerPadding))
        }
    }
}

@Composable
internal fun HomeManagerUpdateBannerMaterial(
    state: HomeUiState,
    actions: HomeActions,
) {
    val newVersion = state.latestVersionInfo
    val title = stringResource(id = R.string.module_changelog)
    val updateText = stringResource(id = R.string.module_update)

    AnimatedVisibility(
        visible = state.hasUpdate,
        enter = fadeIn() + expandVertically(),
        exit = shrinkVertically() + fadeOut()
    ) {
        val updateDialog = rememberConfirmDialog(onConfirm = { actions.onOpenUrl(newVersion.downloadUrl) })
        WarningCard(
            message = stringResource(id = R.string.new_version_available, newVersion.versionCode),
            level = WarningLevel.Warning,
            modifier = Modifier.testTag("home_manager_update_banner"),
        ) {
            if (newVersion.changelog.isEmpty()) {
                actions.onOpenUrl(newVersion.downloadUrl)
            } else {
                updateDialog.showConfirm(
                    title = title,
                    content = newVersion.changelog,
                    markdown = true,
                    confirm = updateText
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    scrollBehavior: TopAppBarScrollBehavior? = null,
    ultra: UltraWordmarkState? = null,
    showReboot: Boolean = false,
    lateLoad: Boolean = false,
) {
    LargeFlexibleTopAppBar(
        title = { ThemeHomeTitle { UltraTitleMaterial(ultra, ThemeHomeTitleText(stringResource(R.string.app_name))) } },
        actions = { if (showReboot) RebootListPopup(lateLoad = lateLoad) },
        colors = expressiveTopAppBarColors(),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        scrollBehavior = scrollBehavior
    )
}

@Composable
internal fun MaterialStatusCard(
    state: HomeUiState,
    actions: HomeActions,
    // Ultra 下的版本行（例如 `MapleSU_Ultra 5.0.5`）；为 null 时沿用原来的版本行。
    ultraVersionLabel: String? = null,
) {
    // P0（冷启动闪烁修复）：模式还在判定中、且没有种子/证据 → 画 loading 占位。
    // 这里**绝不**回落到下面的「未安装」分支 —— 那正是用户看到的"进 App 先闪一下未安装"。
    if (state.showEnvironmentStatusCard) {
        CheckingStatusCard(state.environmentFailure, actions.onEnvironmentRetry)
        return
    }
    val themeArt = statusThemeArt()
    val decorationRevision = WallpaperPrefs.observe()
    val decorationPrefs = remember(decorationRevision) { SettingsRepositoryImpl() }
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val targetWidth = with(LocalDensity.current) { screenWidth.dp.roundToPx() }
    val decoration = com.maplesu.core.ui.util.rememberStatusDecoration(targetWidth)
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        val ksuActive = state.showInstalledStatus
        val notInstalled = !ksuActive && state.kernelVersion.isGKI()
        val artwork = decoration.takeIf { ksuActive && themeArt.file == null }

        // 2026-10-14 用户定：撤掉「内核未就绪」弹窗与状态文案。
        // 没认主/没 root 时一律回落到原版「未安装 / 点击安装」——点了直接进安装页，
        // 否则没 root 的用户连修补 init_boot 的入口都摸不到。

        val containerColor = if (ksuActive) {
            homeStatusContainerColor()
        } else {
            MaterialTheme.colorScheme.errorContainer
        }
        val contentColor = if (ksuActive && themeArt.file != null) {
            themeArt.foreground
        } else if (artwork != null) {
            Color.White
        } else if (ksuActive) {
            homeStatusContentColor()
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        }

        val statusIcon = when {
            ksuActive -> Icons.Rounded.CheckCircle
            notInstalled -> Icons.Rounded.Warning
            else -> Icons.Rounded.Block
        }
        // 状态卡文字固定,不再提供自定义
        val customStatusTitle = if (state.showSafeModeBanner) stringResource(R.string.home_safe_mode_title) else STATUS_CARD_TEXT
        val statusTitle = when {
            ksuActive -> customStatusTitle
            // 有 root 但还没认主 → 这是「从别的管理器转过来」的人,该去安装
            notInstalled && state.managerNotRecognized && state.isRootAvailable ->
                stringResource(R.string.home_can_install)
            notInstalled -> stringResource(R.string.home_not_installed)
            else -> stringResource(R.string.home_unsupported)
        }
        val statusSummary = when {
            ksuActive -> stringResource(R.string.home_working_version,
                ultraVersionLabel ?: stringResource(R.string.maplesu_backend_version, state.kernelUAPIVersion ?: 1))
            // 有 root 但没有认主 → 直达安装
            notInstalled && state.managerNotRecognized && state.isRootAvailable ->
                stringResource(R.string.home_can_install_summary)
            // 2026-10-14 用户定：没 root 时也显示原版「点击安装」，别再弹说明挡路
            notInstalled -> stringResource(R.string.home_click_to_install)
            else -> stringResource(R.string.home_unsupported_reason)
        }
        val workingMode = if (ksuActive) {
            when (state.lkmMode) {
                null -> ""
                true -> "LKM"
                else -> "GKI"
            }
        } else ""

        val keepProtectedText = state.showSafeModeBanner || state.isSafeMode || state.isLateLoadMode
        val statusTrailing: (@Composable () -> Unit)? = if (ksuActive && workingMode.isNotEmpty() && !themeArt.hideMode) {
            {
                StatusTag(
                    label = workingMode,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    backgroundColor = MaterialTheme.colorScheme.primary
                )
            }
        } else if (notInstalled) {
            {
                // Ultra-only：未安装时的主动作是安装 Ultra（越狱入口已移除）。
                Button(
                    onClick = actions.onInstallClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2563EB),
                        contentColor = Color.White,
                    ),
                ) {
                    Text(stringResource(R.string.home_click_to_install))
                }
            }
        } else null

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    // 2026-09-16 修两轮:
                    //   ① 以前 onClick「什么都不做」,而 combinedClickable 照样消费点击、
                    //      照样画水波纹 → 用户看到有反馈却什么都没发生 = 「点了没反应」。
                    //   ② 后来改成一律弹「重启一次」→ 又把**从别的管理器转过来**的人堵死了。
                    //   现在按「本应用有没有拿到 root」分流(唯一可靠判据)。
                    //
                    // ✅ 这张卡**照常可点** —— v0.13.149 曾把点击整个摘掉,
                    //    但用户指出"点不了"本身就可疑。
                    //
                    // ⚠️ v0.13.157:隐身时**不再是特例**。v0.13.156 曾在这里插一个
                    //    `stealthOn ->` 首选分支,只弹一句「暂时无法处理，请稍后再试」、
                    //    同时堵住安装页入口 —— **已撤**。用户判据:点「内核未就绪」弹出的那段
                    //    原始说明,正是一个**没有 root 的用户**点进来会看到的东西,
                    //    所以隐身时必须弹它(而不是弹一句"什么都不漏"的自造提示)。
                    //    唯一保留的隐身差异:右边的「越狱」按钮不画(见上面的 statusTrailing)。
                    onClick = {
                        when {
                            state.isSafeMode -> actions.onManageModules()
                            state.isLateLoadMode -> Unit
                            else -> actions.onInstallClick()
                        }
                    },
                ),
            color = if (themeArt.file != null && ksuActive) Color.Transparent else containerColor,
            contentColor = contentColor,
            shape = MaterialTheme.shapes.large,
        ) {
            Box(Modifier.defaultMinSize(minHeight = if (artwork != null) 112.dp else 0.dp)) {
            if (ksuActive) StatusThemeBackdrop(themeArt)
            if (artwork != null) {
                StatusDecorationImage(
                    bitmap = artwork,
                    biasX = if (decorationPrefs.statusDecorationAnchor == 3) decorationPrefs.statusDecorationBiasX else 0f,
                    biasY = when (decorationPrefs.statusDecorationAnchor) {
                        1 -> -1f
                        2 -> 1f
                        3 -> decorationPrefs.statusDecorationBiasY
                        else -> 0f
                    },
                    zoom = decorationPrefs.statusDecorationZoom,
                    modifier = Modifier.matchParentSize(),
                )
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = maxOf(
                    decorationPrefs.statusDecorationDim,
                    if (decorationPrefs.statusDecorationHideText && !state.showSafeModeBanner) 0f else 0.56f,
                ))))
            }
            if (artwork == null || !decorationPrefs.statusDecorationHideText || keepProtectedText) {
            ListItem(
                leadingContent = if (ksuActive && (themeArt.hideIcon || themeArt.file != null || artwork != null)) null else ({
                    Icon(statusIcon, contentDescription = statusTitle,
                        modifier = if (ksuActive) Modifier.testTag("home-status-default-check") else Modifier)
                }),
                trailingContent = statusTrailing,
                overlineContent = null,
                supportingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = statusSummary,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (state.showCustomLkmBadge) {
                            Spacer(Modifier.width(8.dp))
                            StatusTag(
                                label = stringResource(R.string.home_lkm_custom),
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                backgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
                            )
                        }
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = contentColor,
                    leadingContentColor = if (ksuActive && artwork == null && themeArt.file == null) Color(0xFF36D167) else contentColor,
                    trailingContentColor = contentColor,
                    supportingContentColor = contentColor
                ),
                elevation = ListItemDefaults.elevation(),
                content = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (statusTitle.isNotBlank() && (!ksuActive || !themeArt.hideText || keepProtectedText)) {
                            Text(
                                text = statusTitle,
                                style = MaterialTheme.typography.titleMediumEmphasized
                            )
                        }
                        if (ksuActive && state.isSafeMode && state.environmentMode != dev.maplesu.manager.workmode.WorkMode.LITE) {
                            Spacer(Modifier.width(8.dp))
                            StatusTag(
                                label = stringResource(id = R.string.safe_mode),
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                backgroundColor = MaterialTheme.colorScheme.errorContainer
                            )
                        }
                        if (ksuActive && state.isLateLoadMode) {
                            Spacer(Modifier.width(8.dp))
                            StatusTag(
                                label = stringResource(id = R.string.jailbreak_mode),
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                backgroundColor = MaterialTheme.colorScheme.errorContainer
                            )
                        }
                    }
                },
            )
            } else {
                Spacer(Modifier.fillMaxWidth().height(112.dp))
            }
            }
        }
    }
}

/**
 * P0（冷启动闪烁修复）：模式判定中的状态卡占位（Material 皮肤）。
 *
 * 与 Miuix 皮肤同一条判据（[HomeUiState.showLiteChecking]）、同一套文案；
 * 只是画法不同。判定落定后由状态驱动换成绿卡 / 未安装卡，两个方向都会纠正。
 */
@Composable
private fun CheckingStatusCard(failure: HomeEnvironmentFailure? = null, onRetry: () -> Unit = {}) {
    val containerColor = MaterialTheme.colorScheme.secondaryContainer
    val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        modifier = Modifier.fillMaxWidth().then(
            if (failure != null) Modifier.clickable(onClick = onRetry) else Modifier),
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (failure == null) CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = contentColor,
            )
            else Icon(Icons.Rounded.Warning, contentDescription = null, tint = contentColor)
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(
                    text = stringResource(if (failure == null) R.string.home_status_checking
                        else R.string.home_environment_check_failed),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Text(
                    text = stringResource(when (failure) {
                        HomeEnvironmentFailure.TIMED_OUT -> R.string.home_environment_check_timeout_summary
                        HomeEnvironmentFailure.FAILED -> R.string.home_environment_check_failed_summary
                        HomeEnvironmentFailure.UNCONFIRMED -> R.string.home_environment_check_unconfirmed_summary
                        HomeEnvironmentFailure.BUSY -> R.string.home_environment_check_busy_summary
                        null -> R.string.home_status_checking_summary
                    }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                )
            }
        }
    }
}

@Composable
private fun About7kCard(
    showModes: Boolean,
    modifier: Modifier = Modifier,
) {
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.about_maple_title)
    SegmentedColumn(modifier = modifier.fillMaxWidth()) {
        item {
            SegmentedListItem(
                onClick = { showAbout = true },
                headlineContent = { Text(title) },
                supportingContent = { Text(stringResource(R.string.about_maple_entry_summary)) },
                leadingContent = { Icon(Icons.Rounded.Info, title) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            )
        }
    }
    if (showAbout) AboutMapleDialogMaterial(showModes = showModes, onDismiss = { showAbout = false })
}

@Composable
private fun InfoCard(
    systemInfo: SystemInfo,
    modifier: Modifier = Modifier,
) {
    @Composable
    fun InfoCardItem(
        icon: ImageVector,
        label: String,
        content: String,
        modifier: Modifier = Modifier,
    ) {
        SegmentedListItem(
            modifier = modifier,
            headlineContent = { Text(text = label, style = MaterialTheme.typography.bodyLarge) },
            leadingContent = { Icon(imageVector = icon, contentDescription = label) },
            supportingContent = {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }

    val selinuxDisplay = when (systemInfo.selinuxStatus) {
        "Enforcing" -> stringResource(R.string.selinux_status_enforcing)
        "Permissive" -> stringResource(R.string.selinux_status_permissive)
        "Disabled" -> stringResource(R.string.selinux_status_disabled)
        else -> stringResource(R.string.selinux_status_unknown)
    }
    val seccompDisplay = when (systemInfo.seccompStatus) {
        -1 -> stringResource(R.string.seccomp_status_not_supported)
        0 -> stringResource(R.string.seccomp_status_disabled)
        1 -> stringResource(R.string.seccomp_status_strict)
        2 -> stringResource(R.string.seccomp_status_filter)
        else -> stringResource(R.string.seccomp_status_unknown)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        SegmentedColumn(modifier = Modifier.fillMaxWidth()) {
            item {
                InfoCardItem(
                    icon = Icons.Filled.Tag,
                    label = stringResource(R.string.home_manager_version),
                    content = systemInfo.managerVersion,
                )
            }
            item {
                InfoCardItem(
                    icon = Icons.Filled.DeveloperBoard,
                    label = stringResource(R.string.home_kernel),
                    content = systemInfo.kernelVersion,
                    modifier = Modifier,
                )
            }
            item {
                InfoCardItem(
                    icon = Icons.Filled.Smartphone,
                    label = stringResource(R.string.home_device_model),
                    content = systemInfo.deviceModel,
                )
            }
            item {
                InfoCardItem(
                    icon = Icons.Filled.Fingerprint,
                    label = stringResource(R.string.home_fingerprint),
                    content = systemInfo.fingerprint,
                )
            }
        }
        SegmentedColumn(modifier = Modifier.fillMaxWidth()) {
            item {
                InfoCardItem(
                    icon = Icons.Filled.Security,
                    label = stringResource(R.string.home_selinux_status),
                    content = selinuxDisplay,
                )
            }
            item {
                InfoCardItem(
                    icon = Icons.Filled.FilterList,
                    label = stringResource(R.string.home_seccomp_status),
                    content = seccompDisplay,
                )
            }
        }
    }
}

@Preview(name = "Activated")
@Composable
private fun StatusCardActivatedPreview() {
    MaterialStatusCard(
        state = previewHomeScreenState(ksuVersion = 12345, lkmMode = true),
        actions = HomeActions({}, {})
    )
}

@Preview(name = "Not Activated")
@Composable
private fun StatusCardNotActivatedPreview() {
    MaterialStatusCard(state = previewHomeScreenState(ksuVersion = null, lkmMode = null), actions = HomeActions({}, {}))
}

@Preview(name = "Permissive")
@Composable
private fun StatusCardPermissivePreview() {
    MaterialStatusCard(
        state = previewHomeScreenState(ksuVersion = null, lkmMode = null, selinuxStatus = "Permissive"),
        actions = HomeActions({}, {})
    )
}

@Preview(name = "Jailbreak")
@Composable
private fun StatusCardJailbreakPreview() {
    MaterialStatusCard(
        state = previewHomeScreenState(ksuVersion = 12345, lkmMode = true, isLateLoadMode = true),
        actions = HomeActions({}, {})
    )
}

private val previewSystemInfo = SystemInfo(
    kernelVersion = "6.1.0-android14-0-g123456789000-ab12345678",
    managerVersion = "3.0.0 (30000)",
    deviceModel = "Google Pixel 6 Pro",
    fingerprint = "google/raven/raven:14/AP1A.240305.019:user/release-keys",
    selinuxStatus = "Enforcing",
    seccompStatus = 2
)

private val previewUriHandler = object : UriHandler {
    override fun openUri(uri: String) {}
}

@Composable
private fun HomeScreenPreviewContent(
    ksuVersion: Int?,
    lkmMode: Boolean?,
    isSafeMode: Boolean = false,
    isLateLoadMode: Boolean = false,
    selinuxStatus: String = "Enforcing",
) {
    CompositionLocalProvider(LocalUriHandler provides previewUriHandler) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val actions = HomeActions({}, {})
            MaterialStatusCard(
                state = previewHomeScreenState(
                    ksuVersion = ksuVersion,
                    lkmMode = lkmMode,
                    isSafeMode = isSafeMode,
                    isLateLoadMode = isLateLoadMode,
                    selinuxStatus = selinuxStatus,
                ),
                actions = actions
            )
            InfoCard(previewSystemInfo.copy(selinuxStatus = selinuxStatus))
        }
    }
}

@Preview(name = "Home Activated", showBackground = true)
@Composable
private fun HomeScreenActivatedPreview() {
    HomeScreenPreviewContent(ksuVersion = 12345, lkmMode = true)
}

@Preview(name = "Home Not Activated", showBackground = true)
@Composable
private fun HomeScreenNotActivatedPreview() {
    HomeScreenPreviewContent(ksuVersion = null, lkmMode = null)
}

@Preview(name = "Home Permissive", showBackground = true)
@Composable
private fun HomeScreenPermissivePreview() {
    HomeScreenPreviewContent(ksuVersion = null, lkmMode = null, selinuxStatus = "Permissive")
}

@Preview(name = "Home Jailbreak", showBackground = true)
@Composable
private fun HomeScreenJailbreakPreview() {
    HomeScreenPreviewContent(ksuVersion = 12345, lkmMode = true, isLateLoadMode = true)
}

private fun previewHomeScreenState(
    ksuVersion: Int?,
    lkmMode: Boolean?,
    isSafeMode: Boolean = false,
    isLateLoadMode: Boolean = false,
    selinuxStatus: String = "Enforcing",
) = HomeUiState(
    kernelVersion = KernelVersion(6, 1, 0),
    ksuVersion = ksuVersion,
    lkmMode = lkmMode,
    isLkmBundled = lkmMode == true,
    isManager = true,
    isManagerPrBuild = false,
    isKernelPrBuild = false,
    requiresNewKernel = false,
    requiresNewManager = false,
    isRootAvailable = ksuVersion != null,
    isSafeMode = isSafeMode,
    isLateLoadMode = isLateLoadMode,
    checkUpdateEnabled = false,
    latestVersionInfo = com.maplesu.core.ui.util.module.LatestVersionInfo(),
    currentManagerVersionCode = 10000,
    systemInfo = previewSystemInfo.copy(selinuxStatus = selinuxStatus),
    kernelUAPIVersion = 1,
    managerUAPIVersion = 1,
)
