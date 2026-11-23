package com.maplesu.core.ui

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults.flingBehavior
import androidx.compose.foundation.pager.PagerDefaults.pageNestedScrollConnection
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.splashscreen.SplashScreenViewProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.maplesu.core.CrashReporter
import com.maplesu.core.KernelSUApplication
import com.maplesu.core.ksuApp
import com.maplesu.core.Natives
import com.maplesu.core.R
import com.maplesu.core.ui.component.maple.*
import com.maplesu.core.ui.screen.settings.AppearanceIntroScreen
import com.maplesu.core.ui.component.bottombar.BottomBar
import com.maplesu.core.ui.component.bottombar.MainPagerState
import com.maplesu.core.ui.component.bottombar.NavigationBadgeState
import com.maplesu.core.ui.component.bottombar.SideRail
import com.maplesu.core.ui.component.bottombar.rememberMainPagerState
import com.maplesu.core.ui.component.bottombar.useNavigationRail
import com.maplesu.core.ui.navigation3.IntentDispatcher
import com.maplesu.core.ui.navigation3.AppNavDisplay
import com.maplesu.core.ui.navigation3.LocalNavigator
import com.maplesu.core.ui.navigation3.Navigator
import com.maplesu.core.ui.navigation3.Route
import com.maplesu.core.ui.navigation3.rememberNavigator
import com.maplesu.core.ui.screen.about.AboutScreen
import com.maplesu.core.ui.screen.appprofile.AppProfileScreen
import com.maplesu.core.ui.screen.colorpalette.ColorPaletteScreen
import com.maplesu.core.ui.screen.executemoduleaction.ExecuteModuleActionScreen
import com.maplesu.core.ui.screen.flash.FlashScreen
import com.maplesu.core.ui.screen.home.HomePager
import com.maplesu.core.ui.screen.install.InstallScreen
import com.maplesu.core.ui.screen.module.ModulePager
import com.maplesu.core.ui.screen.modulerepo.ModuleRepoDetailScreen
import com.maplesu.core.ui.screen.modulerepo.ModuleRepoScreen
import com.maplesu.core.ui.screen.maplemarket.MapleMarketScreen
import com.maplesu.core.ui.screen.settings.AppearanceImagesScreen
import com.maplesu.core.ui.screen.settings.AppearanceScreen
import com.maplesu.core.ui.screen.settings.SettingPager
import com.maplesu.core.ui.security.Stealth
import com.maplesu.core.ui.security.StealthReceiver
import com.maplesu.core.ui.security.restartUiFresh

import com.maplesu.core.ui.screen.superuser.SuperUserPager
import com.maplesu.core.ui.screen.template.AppProfileTemplateScreen
import com.maplesu.core.ui.screen.templateeditor.TemplateEditorScreen
import com.maplesu.core.ui.theme.KernelSUTheme
import com.maplesu.core.ui.theme.LocalColorMode
import com.maplesu.core.ui.theme.LocalEnableBlur
import com.maplesu.core.ui.theme.LocalEnableFloatingBottomBar
import com.maplesu.core.ui.theme.LocalEnableFloatingBottomBarBlur
import com.maplesu.core.ui.theme.LocalEnableNavigationBadge
import com.maplesu.core.ui.theme.LocalFlatUi
import com.maplesu.core.ui.theme.LocalModuleDescriptionMaxLines
import com.maplesu.core.ui.theme.rememberSkinContent
import com.maplesu.core.ui.theme.rememberSkinContentWithParameter
import com.maplesu.core.ui.util.install
import com.maplesu.core.ui.util.rememberBlurBackdrop
import com.maplesu.core.ui.util.rememberContentReady
import com.maplesu.core.ui.viewmodel.HomeViewModel
import com.maplesu.core.ui.viewmodel.MainActivityViewModel
import com.maplesu.core.ui.viewmodel.MainNavigationPolicy
import com.maplesu.core.ui.viewmodel.MainPagerConfig
import com.maplesu.core.ui.viewmodel.ModuleViewModel
import com.maplesu.core.ui.viewmodel.SuperUserViewModel
import dev.maplesu.manager.t3.T3Runtime
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.PagerInterceptionMode
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.pagerGestureOverride

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        val tags = if (Build.VERSION.SDK_INT < 33) newBase.getSharedPreferences(
            com.maplesu.core.ui.util.AppearancePreferences.NAME, 0).getString("theme_language", "") else null
        val localized = if (!tags.isNullOrEmpty()) {
            val configuration = android.content.res.Configuration(newBase.resources.configuration)
            configuration.setLocales(android.os.LocaleList.forLanguageTags(tags))
            newBase.createConfigurationContext(configuration)
        } else newBase
        super.attachBaseContext(localized)
    }

    private var soundDownX = 0f
    private var soundDownY = 0f
    private var soundTap = false
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { soundDownX = event.x; soundDownY = event.y; soundTap = true }
            android.view.MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(event.x-soundDownX) > slop || kotlin.math.abs(event.y-soundDownY) > slop) soundTap = false
            android.view.MotionEvent.ACTION_CANCEL, android.view.MotionEvent.ACTION_POINTER_DOWN -> soundTap = false
        }
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP && soundTap &&
            event.eventTime-event.downTime < android.view.ViewConfiguration.getLongPressTimeout()) {
            val document = com.maplesu.core.theme.ThemeRuntime.document(this)
            if (document.optString("soundEffectScope", "global") == "global")
                com.maplesu.core.theme.ThemeSound.play(this, bottomBar = false)
        }
        return super.dispatchTouchEvent(event)
    }


    private val intentChannel = Channel<Intent>(capacity = Channel.BUFFERED)
    private var contentReady = false
    private val t3 by lazy { T3Runtime.get(applicationContext) }
    private val t3ForegroundOwner = Any()

    /**
     * 崩溃上报：只要这个 Activity 至少走到过一次前台，正常收尾时就把会话标记删掉。
     * 没删掉 = 上次进程没正常收尾（可能崩了）→ 下次启动由 Application 触发后台抓取。
     * 允许误判（用户划掉 App 也可能没删到），所以最终是否上报由「日志里有没有我们自己的
     * 崩溃痕迹」决定，不靠这个标记。
     */
    private var cameToForeground = false

    /**
     * 「上次渲染这个界面时用的隐身状态」，记的是**内核 stealthState 的原始值**：
     * `0` = 已关、`1` = 已开，[STEALTH_STATE_UNKNOWN] = 基线本身不可信（构造期就没读到）。
     *
     * 为什么不用 Boolean：`Stealth.isEnabled()` 内部就是 `kernelState() == 1`，
     * 而 `kernelState()` 把「读失败」折成 `-1` —— 所以 `isEnabled()` **永远不会抛异常**，
     * 它的语义其实是"读不到 = false"。一次 ioctl 读失败（本项目已知会发生：appid 重扫
     * 期间标志翻转）就会被当成"隐身已关" → 白重建一次界面（500ms 闪屏）还把基线写错。
     * 只有记原始 Int，才能把「0」「1」「读不到」三种情况分开处理。
     *
     * 为什么需要它：本项目的界面状态是 **ViewModel 构造时算一次** 的，而 ViewModel 会跨
     * resume/recreate 存活。隐身标志却可能在本进程处于后台时被别处改掉
     * （拨号密令 [com.maplesu.core.ui.security.StealthReceiver]、内置网页管理器），
     * 于是「关掉隐身 → 切回前台」时界面拿的还是旧状态算出来的东西 = 看起来没刷新
     * （用户实测：小米/MIUI 上只有把 App 挂成小窗、真正重新可见一次才恢复）。
     *
     * 这里记下"渲染时用的值"，在 [refreshIfStealthChanged] 里回前台对比，
     * 只有**真的变了**才重建界面。
     *
     * 初值恒为 [STEALTH_STATE_UNKNOWN]：Activity 构造期在主线程上，KSU 模式下那次读取是 JNI。
     * 基线由第一次 onResume 在 IO 线程读回（见 [refreshIfStealthChanged] 的第 ③ 步）：
     * 界面本来就是按当前状态渲染的，所以只对齐、不重建。
     */
    private var lastSeenStealthState: Int = STEALTH_STATE_UNKNOWN

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        t3.attach(t3ForegroundOwner)
        lifecycleScope.launch {
            t3.secondsRemaining.collect { seconds ->
                if (seconds == 0) {
                    finishAffinity()
                    finishAndRemoveTask()
                }
            }
        }
        // 当前主题的首帧资源就绪后放行，不再有最短展示时长。
        splashScreen.setKeepOnScreenCondition { !contentReady }
        splashScreen.setOnExitAnimationListener(::playSplashExit)

        // ══════════════════════════════════════════════════════════════════
        // 🔴 结构性约束（v2.15 立，v2.16 修正措辞 —— 请勿违反）：
        //
        //   **App 的启动路径（Application.onCreate / MainActivity.onCreate 以及它们
        //     同步调用到的东西）不得发起 root shell / ksud 调用。**
        //   需要内核信息的界面（模块页 / 超级用户页 / 安装页 / 设置页 / 首页诊断），
        //   一律在**用户真的进入那个界面 / 点那个按钮**时按需查询。
        //
        // 为什么：v2.1 → v2.13 把启动期的 ksud 调用从个位数涨到十几个，
        //   每一次都是一次"可能失败"的机会；而失败值一旦流进 native / libsu，
        //   就会变成**真崩溃**。v2.15 定案的那条就是活例子：
        //   libsu 的 `RootService`（`KsuService`）往 cache 写一个可写 dex
        //   (`main.jar`) 再用当前 shell 跑 app_process；shell 没 root 时
        //   app_process 以 App 的 uid 加载它 → ART 拒绝 → abort()
        //   → `signal 6 (SIGABRT), code -1 (SI_QUEUE)`（真机 tombstone 实证）。
        //
        // 🟢 v2.16 修正：v2.15 那版注释写的是"唯二例外"，**漏了 `LegacyDataMigration`
        //    （在 Application.onCreate 里起后台线程跑 4 类 root 调用）** —— 注释与代码不符。
        //    现在把**全部**例外列全（这是启动链的权威清单，改代码时请同步维护）：
        //
        //   ① `install()`（见 [deployRuntimeOffMainThread]，**后台线程**）：门控与上游一致 ——
        //      上游 `MainActivity.onCreate` 就是 `if (isManager && UAPI 相等) install()`。
        //      不装 ksud 就完全没有 root 能力，必须启动时试一次。失败只留一行日志。
        //   ② 崩溃上报的后台抓取（`Application.onCreate` → daemon 线程）：
        //      只在"上次会话没正常收尾"时才跑，全程 runCatching。
        //   ③ `LegacyDataMigration.ensureMigratedOnce()`（`Application.onCreate` →
        //      daemon 线程）：数据目录检查、兼容软链维护与首页诊断。
        //      **在后台线程上、不阻塞首帧、失败只记录日志**；保留现有数据，
        //      拿不到 root 就什么都不做（留给 ksud 开机自己迁）。
        //   ④ 首页 `HomeViewModel` 的 root / SELinux 探测：**已挪到 IO 线程**
        //      （首帧用 `probeRoot = false`，见 HomeViewModel 注释）。上游是在
        //      ViewModel 构造里**同步**做的（更糟），我们保持现在这样。
        //   ⑤ 首页 `LaunchedEffect(isFullFeatured)` 的预加载 → `loadAppList()`：
        //      **这是上游自己的代码路径**（上游 `MainActivity.kt` 同位置同逻辑），
        //      被 `isFullFeatured`（= 内核认我们 && UAPI 够 && 有 root）门控。
        //
        //   ⇒ 净结果：启动期 ksud/root 调用从 v2.14 的十几个降到 **4 处**（①~④），
        //     全部不在主线程，②③ 有条件。
        // ══════════════════════════════════════════════════════════════════

        // 🔴 2026-10-30：内置默认壁纸已移除 —— 启动时**不再**播种任何默认图。
        //    壁纸为空就是纯主题背景，只有用户自己选的壁纸才会被渲染（见 WallpaperHost）。
        deployRuntimeOffMainThread()

        // 确保桌面图标一直在(顺便把旧版本"隐藏图标"藏起来的图标找回来)
        Stealth.ensureLauncherVisible(this)

        // 🟢 v2.15 删除（原本是 v2.13 新增的启动期工作）：
        //   这里以前每次启动都跑一次 `WebAdminCli.syncPref(true)` —— 那是一条 root shell
        //   命令（`ksud webadmin sync`）。而**设置页自己**在打开网页管理器那一项时就会
        //   同步并读取地址（SettingsMiuix/SettingsMaterial 的 LaunchedEffect 里已补
        //   `WebAdminCli.syncPref(true)`），所以启动时那次是多余的 root 调用，已删。
        //   语义不变：升级安装后用户一进设置页就会把开关同步给 ksud。

        // 🟢 v2.15 删除（原本是 v2.12 新增的启动期工作）：
        //   这里以前每次启动都跑一次 `ksud module migrate-markers`，一条 root shell 命令。
        //   现在**登记簿整套已经删掉**：App 侧不再有任何相关代码，也**不会**再往模块目录
        //   里写我们自己的任何文件；模块目录由 ksud / 上游逻辑自己管。

        if (savedInstanceState == null) intent?.let { intentChannel.trySend(it) }

        setContent {
            val announcementLaunch = rememberSaveable { savedInstanceState == null }
            val disabledSeconds by t3.secondsRemaining.collectAsStateWithLifecycle()
            val remoteNotice by t3.notice.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) {
                repeat(2) { withFrameNanos { } }
                dev.maplesu.manager.workmode.boot.ModeBootstrap.onManagerFirstFrame(this@MainActivity)
            }
            val viewModel = viewModel<MainActivityViewModel>()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(uiState.enablePredictiveBack) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Refresh existing inputs; setOnBackInvokedDispatcher would add duplicate inputs.
                    val dispatcher = navigationEventDispatcher
                    val wasEnabled = dispatcher.isEnabled
                    dispatcher.isEnabled = false
                    try {
                        KernelSUApplication.setEnableOnBackInvokedCallback(applicationInfo, uiState.enablePredictiveBack)
                    } finally {
                        dispatcher.isEnabled = wasEnabled
                    }
                }
            }
            val isFullFeatured by viewModel.isFullFeatured.collectAsStateWithLifecycle()
            val skin = rememberSkinTransitionState(uiState.uiMode, uiState.appSettings, uiState.appearanceIntroComplete)
            val brandSession = rememberBrandSession()
            val appSettings = skin.appliedSettings
            val uiMode = skin.appliedMode
            SideEffect { if (!skin.appliedScene) brandSession.onboardingEntered = true }
            val darkMode = appSettings.colorMode.isDark || (appSettings.colorMode.isSystem && isSystemInDarkTheme())

            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                )
                window.isNavigationBarContrastEnforced = false
                onDispose { }
            }

            val navigator = rememberNavigator(Route.Main)
            val context = LocalContext.current
            val uriHandler = LocalUriHandler.current
            val systemDensity = LocalDensity.current
            val density = remember(systemDensity, uiState.pageScale) {
                Density(systemDensity.density * uiState.pageScale, systemDensity.fontScale)
            }
            // 「界面扁平化」已下线（2026-10-30）：设置页不再有开关，代码写死 false，
            // 旧键 `flat_home` 只保留兼容读写，读出来恒 false（见 AppearancePrefsPolicy）。
            val flatUi = false
            val themePage by viewModel.selectedMainPage.collectAsStateWithLifecycle()

            // ⚠️ v2.20：「检测到旧版 7kimisu」那套**整套已删除**（用户要求：退回上游做法）。
            //    启动弹窗、设置页那一行、首页常驻卡全部撤掉；检测/卸载相关的代码文件也已
            //    从仓库里删干净，不再有任何入口。
            //
            //    ⚠️ v2.2 起"旧包还在就先不弹公告"的让位逻辑也随之撤掉：
            //    不再有第二个弹窗跟公告抢同一个 WindowDialog 通道（见
            //    ui/component/miuix/ModuleIcon.kt 顶部注释），公告恢复成"每次全新打开都弹"。

            com.maplesu.core.theme.ThemeStartupHost(onReady = { contentReady = true }) {
            SkinTransitionHost(skin) {
            CompositionLocalProvider(
                LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) {
                        try {
                            uriHandler.openUri(uri)
                        } catch (_: IllegalArgumentException) {
                            Toast.makeText(context, R.string.external_link_open_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                LocalBrandSession provides brandSession,
                LocalSkinTransition provides skin,
                LocalNavigator provides navigator,
                LocalDensity provides density,
                LocalColorMode provides appSettings.colorMode.value,
                LocalEnableBlur provides uiState.enableBlur,
                LocalEnableFloatingBottomBar provides uiState.enableFloatingBottomBar,
                LocalEnableFloatingBottomBarBlur provides uiState.enableFloatingBottomBarBlur,
                LocalEnableNavigationBadge provides uiState.enableNavigationBadge,
                LocalFlatUi provides flatUi,
                LocalModuleDescriptionMaxLines provides uiState.moduleDescriptionMaxLines,
                LocalUiMode provides uiMode,
                com.maplesu.core.theme.LocalThemeWallpaperPage provides themePage,
            ) {
                KernelSUTheme(appSettings = appSettings, uiMode = uiMode) {
                  if (disabledSeconds != null) {
                    com.maplesu.core.ui.component.T3DisabledDialog(disabledSeconds!!)
                  } else {
                  // 崩溃上报弹窗：放在整套 UI 的最前面，且不受隐身/扁平化/公告等任何开关影响
                  // （隐身时也要能弹）。Dialog 是独立窗口，不参与下面的布局。
                  CrashReportDialog()
                  Box(modifier = Modifier.fillMaxSize()) {
                    if (!skin.appliedScene) {
                        AppearanceIntroScreen(uiMode, viewModel::selectUiMode, viewModel::finishAppearanceIntro,
                            uiState.introComic, viewModel::setIntroComic)
                    } else {
                    // 个性化壁纸:在界面底下画一层背景(图片/视频),没设置时零开销
                    com.maplesu.core.ui.screen.settings.ThemeOperationsHost {
                    com.maplesu.core.theme.ThemeMediaHost()
                    com.maplesu.core.ui.util.WallpaperHost(themePage) {
                    IntentDispatcher(intentChannel = intentChannel)
                    val homeViewModel = viewModel<HomeViewModel>()
                    LaunchedEffect(homeViewModel, viewModel) {
                        combine(
                            viewModel.isFullFeatured,
                            homeViewModel.uiState.map { it.systemInfo.kernelVersion }.distinctUntilChanged(),
                        ) { working, kernelVersion -> working to kernelVersion }
                            .collect { (working, kernelVersion) ->
                            t3.updateCoreSnapshot(working, kernelVersion, Build.MODEL)
                        }
                    }
                    managerRestoreConfirmation.Content(onRestoreObserved = {
                        homeViewModel.refreshAfterManagerRestore()
                        viewModel.refreshFullFeatured()
                    })
                    com.maplesu.core.ui.component.T3AnnouncementPopup(
                        content = remoteNotice,
                        eligible = announcementLaunch && !skin.isRunning && !brandSession.onboardingEntered,
                    )
                    val swipeDismiss = if (uiState.enableSwipeDismiss) {
                        if (LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) {
                            NavSwipeDirection.RightToLeft
                        } else {
                            NavSwipeDirection.LeftToRight
                        }
                    } else {
                        NavSwipeDirection.None
                    }
                    val flashRoute = navigator.current() as? Route.Flash
                    var flashBackEnabled by remember(flashRoute) { mutableStateOf(false) }
                    val modeSwitchRoute = navigator.current() as? Route.ModeSwitch
                    var modeSwitchBackEnabled by remember(modeSwitchRoute) { mutableStateOf(false) }
                    val migrationRoute = navigator.current() as? Route.Migration
                    var migrationBackEnabled by remember(migrationRoute) { mutableStateOf(false) }
                    val recoveryRoute = navigator.current() as? Route.Recovery
                    var recoveryBackEnabled by remember(recoveryRoute) { mutableStateOf(false) }
                    val mainScreenEntry = @Composable {
                        MainScreen(
                            homeViewModel = homeViewModel,
                            // 只在进入时用一次：不订阅它，否则每次切页都要重组整个 MainScreen。
                            initialPage = viewModel.selectedMainPage.value,
                            pagerInterceptionMode = uiState.pagerInterceptionMode,
                            isFullFeatured = isFullFeatured,
                            onPageChanged = viewModel::setSelectedMainPage,
                            onRefreshFacts = viewModel::refreshFullFeatured,
                        )
                    }

                    // Both native Scaffolds place the same entry tree, preserving its VM/store keys.
                    val navDisplay = rememberSkinContent {
                        AppNavDisplay(
                            backStack = navigator.backStack,
                            effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
                            onBack = { navigator.pop() }) {
                            entry<Route.Main>(swipeDismiss = swipeDismiss) { mainScreenEntry() }
                            entry<Route.Appearance>(swipeDismiss = swipeDismiss) { AppearanceScreen() }
                            entry<Route.AppearanceImages>(swipeDismiss = swipeDismiss) { AppearanceImagesScreen() }
                            entry<Route.Wallpaper>(swipeDismiss = swipeDismiss) { com.maplesu.core.ui.screen.settings.WallpaperScreen() }
                            entry<Route.ThemeStore>(swipeDismiss = swipeDismiss) { com.maplesu.core.ui.screen.settings.ThemeStoreScreen() }
                            entry<Route.MyThemes>(swipeDismiss = swipeDismiss) { com.maplesu.core.ui.screen.settings.ThemeStoreScreen(localOnly = true) }
                            entry<Route.ThemeFont>(swipeDismiss = swipeDismiss) { com.maplesu.core.ui.screen.settings.ThemeFontScreen() }
                            entry<Route.ThemeLayout>(swipeDismiss = swipeDismiss) { com.maplesu.core.ui.screen.settings.ThemeLayoutScreen() }
                            entry<Route.ThemeMedia>(swipeDismiss = swipeDismiss) { com.maplesu.core.ui.screen.settings.ThemeMediaScreen() }
                            entry<Route.About>(swipeDismiss = swipeDismiss) { AboutScreen() }
                            entry<Route.ColorPalette>(swipeDismiss = swipeDismiss) { ColorPaletteScreen() }
                            entry<Route.AppProfileTemplate>(swipeDismiss = swipeDismiss) { AppProfileTemplateScreen() }
                            // Draft confirmation is owned by the editor, not a direct swipe pop.
                            entry<Route.TemplateEditor>(swipeDismiss = NavSwipeDirection.None) { key -> TemplateEditorScreen(key.template, key.readOnly) }
                            entry<Route.AppProfile>(swipeDismiss = swipeDismiss) { key -> AppProfileScreen(key.uid) }
                            entry<Route.ModuleRepo>(swipeDismiss = swipeDismiss) { ModuleRepoScreen() }
                            entry<Route.MapleMarket>(swipeDismiss = swipeDismiss) { MapleMarketScreen() }
                            entry<Route.ModuleRepoDetail>(swipeDismiss = swipeDismiss) { key -> ModuleRepoDetailScreen(key.module) }
                            // ✅ 2026-09-20(v0.13.150):这里**不再**在隐身时把安装页弹回去。
                            //
                            // v0.13.149 曾在这里加了"隐身兜底 pop",同时把首页的安装入口点击也摘掉。
                            // 用户实测反馈:"点不了本身就很可疑"。
                            // 正确做法是**让安装页自己显示成"真·没 root 的设备"该有的样子**:
                            // 安装页(InstallScreen)会在隐身时把 root 当不可用,
                            // 「直接安装(推荐)」「安装到未使用槽位」(这两个要 root)自动消失,
                            // 只留下真·没 root 的 KernelSU 用户也会看到的
                            // 「选择文件并修补 / 下载文件并修补 / 使用本地 LKM 文件」。
                            // 所以这里照常进安装页,不放任何隐身判断(也避免误伤
                            // 「从别的 root 管理器转过来」——他们本来就该能进安装页)。
                            entry<Route.Install>(swipeDismiss = swipeDismiss) {
                                val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
                                InstallScreen(deviceFingerprint = homeState.systemInfo.fingerprint)
                            }
                            // H3/H7：Lite 隐藏设置页（独立子页；设置项点入）。
                            entry<Route.LiteHidden>(swipeDismiss = swipeDismiss) {
                                dev.maplesu.manager.workmode.ui.LiteHiddenScreen(
                                    onBack = { navigator.pop() },
                                )
                            }
                            // H7：WebUI 自检页（内部验证；A/B 都可达）。
                            entry<Route.WebUiSelfCheck>(swipeDismiss = swipeDismiss) {
                                dev.maplesu.manager.workmode.ui.WebUiSelfCheckScreen(
                                    onBack = { navigator.pop() },
                                )
                            }
                            entry<Route.Flash>(swipeDismiss = if (flashBackEnabled) swipeDismiss else NavSwipeDirection.None) { key ->
                                FlashScreen(
                                    key.flashIt,
                                    onBackEnabledChange = { enabled ->
                                        if (navigator.current() == key) flashBackEnabled = enabled
                                    },
                                )
                            }
                            // Running actions use the same explicit return feedback for every back entry.
                            entry<Route.ExecuteModuleAction>(swipeDismiss = NavSwipeDirection.None) { key ->
                                ExecuteModuleActionScreen(
                                    key.moduleId,
                                    key.fromShortcut
                                )
                            }
                            entry<Route.Home>(swipeDismiss = swipeDismiss) { mainScreenEntry() }
                            entry<Route.SuperUser>(swipeDismiss = swipeDismiss) { mainScreenEntry() }
                            entry<Route.Module>(swipeDismiss = swipeDismiss) { mainScreenEntry() }
                            entry<Route.Settings>(swipeDismiss = swipeDismiss) { mainScreenEntry() }
                        }
                    }

                    when (uiMode) {
                        UiMode.Material -> androidx.compose.material3.Scaffold(
                            containerColor = MaterialTheme.colorScheme.background
                        ) { navDisplay() }

                        UiMode.Miuix -> Scaffold { navDisplay() }
                    }
                    }
                    }
                    } // ThemeOperationsHost
                    // 枫叶特效:铺在最上层,纯视觉、不吃触摸;关掉时完全不参与组合(零开销)
                    // ⚠️ v0.13.157:隐身时也照常画(没 root 的用户也有这个特效,不是 root 特征)
                    if ((skin.appliedScene && uiState.snowEnabled) ||
                        (!skin.appliedScene && uiMode == UiMode.Miuix && uiState.introComic)) {
                        com.maplesu.core.ui.component.SnowfallOverlay(
                            sizeScale = uiState.snowSize,
                            speedScale = uiState.snowSpeed,
                            tiltEnabled = skin.appliedScene && uiState.gravityEnabled,
                            gravityMode = uiState.gravityMode,
                        )
                    }
                    // 巨魔雨:蓝巨魔图标翻滚下落,同样不吃触摸
                    if (skin.appliedScene && uiState.trollRainEnabled) {
                        com.maplesu.core.ui.component.TrollRainOverlay(
                            sizeScale = uiState.trollRainSize,
                            speedScale = uiState.trollRainSpeed,
                            tiltEnabled = uiState.gravityEnabled,
                            gravityMode = uiState.gravityMode,
                        )
                    }
                  }
                  } // Confirmed T3 disable replaces interactive content until the task exits.
                }
            }
            }
            } // ThemeStartupHost
        }
    }

    /**
     * 已识别后提前调度 MapleSU 的异步运行时部署。
     * 首次后台 Root 探测成功时也会触发部署，首帧缓存不决定最终状态。
     *
     * 门控条件与搬到后台前完全相同，判定顺序也不变，唯一变化是线程：
     * `Natives.isManager` / UAPI 读取 / `install()` 内部的运行时探测都可能碰文件或 JNI，不该占主线程。
     *
     * 主线程先做两道纯内存判断，拦下的情况后台线程根本不会启动（冷启动时模式尚未判定，走的就是这里）。
     * 后台线程里 **Ultra 判定在 `install()` 之前重读一次**，所以这里只可能比原来更严，不会更松。
     */
    private fun deployRuntimeOffMainThread() {
        val modeRuntime = dev.maplesu.manager.workmode.ModeRuntime
        val ultraProbe = dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
        // mode-gate: main.install (04-ui-contract §1.4) —— install() 是 A（LITE）唯一入口；
        // KSU/未判定不执行（未判定态禁止启动任一专属任务，04 §1.7）。
        if (modeRuntime.active != dev.maplesu.manager.workmode.WorkMode.LITE) return
        if (runCatching { ultraProbe.usesUltraBackend(this) }.getOrDefault(false)) return

        Thread({
            // ⚠️ 2026-09-19 修:`Natives.isManager` 是 JNI 调用(打到内核 / 读 /data/adb),
            //    native 库没加载、ioctl 被拒时都会抛。失败按"不是管理器"处理,走非 root 的安全路径。
            val isManager = runCatching { Natives.isManager }.getOrDefault(false)
            val stealthOn = runCatching { Stealth.isEnabled() }.getOrDefault(false)
            if (!isManager && !stealthOn) return@Thread
            // 提前调度仍检查当前后端 API；部署失败由运行时状态报告。
            val uapiOk = runCatching {
                Natives.kernelUAPIVersion >= Natives.managerUAPIVersion
            }.getOrDefault(false)
            // 🛑 Ultra 环境：`install()` = `RuntimeDeployment` → `MapleSuCredentialStore` /
            //    `MapleSuCommandExecutor` → `libmaplesu_runtime.so`。在 MapleSU Ultra 内核上那条
            //    路径会触发系统重启（2026-10-29 实测），而 Ultra 的部署完全由 SDK 自己负责
            //    （设置页「安装/激活环境」）。这里用缓存的 Ultra 探针结果挡住它：
            //    `Natives.isManager` 现在在 Ultra Running 时也为 true，所以必须在这里再拦一次。
            val ultraRunning = runCatching { ultraProbe.usesUltraBackend(this) }.getOrDefault(false)
            if (uapiOk && !ultraRunning) {
                modeRuntime.runIfLite {
                    runCatching { install() }
                        .onFailure {
                            android.util.Log.w("KernelSU", "启动时 install() 失败(忽略,不闪退): ", it)
                        }
                }
            }
        }, "maplesu-startup-deploy").apply { isDaemon = true }.start()
    }

    /** 品牌图轻微上浮，背景直接淡入已就绪的首页；结束和取消都移除闪屏层。 */
    private fun playSplashExit(provider: SplashScreenViewProvider) {
        val splashView = provider.view
        var removed = false
        fun removeSplash() {
            if (removed) return
            removed = true
            provider.iconView.animate().cancel()
            runCatching { provider.remove() }
        }
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
            removeSplash()
            return
        }
        provider.iconView.animate()
            .scaleX(SPLASH_EXIT_SCALE)
            .scaleY(SPLASH_EXIT_SCALE)
            .translationY(-6f * resources.displayMetrics.density)
            .setDuration(SPLASH_EXIT_DURATION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        splashView.animate()
            .alpha(0f)
            .setDuration(SPLASH_EXIT_DURATION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = removeSplash()
                override fun onAnimationCancel(animation: android.animation.Animator) = removeSplash()
            })
            .start()
    }

    override fun onStart() {
        super.onStart()
        t3.foreground(t3ForegroundOwner)
    }

    override fun onResume() {
        super.onResume()
        managerRestoreConfirmation.check()
        ksuApp.requestUltraRecovery()
        cameToForeground = true
        // 已经回到管理器前台 → 拨号密令留下的「已关闭隐身」辅助通知就没用了，撤掉。
        // 通知 id 由 [StealthReceiver.NOTIFICATION_ID] 统一提供（同一个常量，不用各写一份）。
        // 整段 runCatching：撤通知失败（系统通知服务异常）绝不能影响 App 启动后的任何行为。
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.cancel(StealthReceiver.NOTIFICATION_ID)
        }.onFailure {
            android.util.Log.w(TAG, "撤掉「已关闭隐身」通知失败(忽略，不影响使用)", it)
        }
        // 回前台再自检一次：隐身标志可能在后台被拨号密令 / 网页管理器改掉了
        refreshIfStealthChanged()
    }

    private val managerRestoreConfirmation by lazy {
        dev.maplesu.manager.recovery.ManagerRestoreConfirmation(this)
    }

    /**
     * 回前台核对「内核里的隐身状态」是否和上次渲染时用的不一样，不一样就整界面重建。
     *
     * 为什么必须补这一手：Android 10+ 禁止后台启动 Activity，拨号密令在后台把内核标志
     * 关掉后，[com.maplesu.core.ui.security.StealthReceiver] 里的 startActivity 会被
     * 系统拦下；而本项目的界面状态是 ViewModel 构造时算一次的、ViewModel 又跨 resume
     * 存活 —— 所以"光切回前台"拿的还是隐身时算出来的旧状态，界面不会刷新
     * （用户实测：小米/MIUI 上必须把 App 挂成小窗才恢复）。这里用
     * [restartUiFresh]（清 ViewModelStore 再 recreate）把这个缺口补上。
     *
     * ⚠️ 读失败 ≠ 隐身已关（[Stealth.kernelState] 读不到时返回 -1，注意
     * `Stealth.isEnabled()` 只是 `kernelState() == 1`，它**不会抛异常**，
     * 拿它做兜底等于把"读不到"当成 false）：一次读失败绝不能触发重建、也不能写坏基线。
     *
     * 防抖（为什么不会 recreate 循环）：**先写字段、再重建**。
     * recreate 之后那一次 onResume 读到的 [lastSeenStealthState] 已经等于当前真值，
     * `now == lastSeenStealthState` 成立 → 不会再触发一次，循环从根上断开。
     * 状态没变时**一次重建都不做**，不闪屏、不打断用户。
     *
     * 内核读取放 IO 线程，比较与重建回主线程做。
     * 整段 runCatching：只留日志，绝不把 App 带崩。
     */
    private fun refreshIfStealthChanged() {
        lifecycleScope.launch {
            // [Stealth.kernelState] 内部已 runCatching（读失败返回 -1），本身不会抛。
            val now = withContext(Dispatchers.IO) { Stealth.kernelState() }
            applyStealthState(now)
        }
    }

    private fun applyStealthState(now: Int) {
        runCatching {
            // ① 读失败（-1，或任何非 0/1 的意外值）：**绝不能**算成"隐身已关"。
            //    不动基线、不重建，等下一次 onResume 再对一次 —— 宁可晚一轮，也不误重建。
            if (now != 0 && now != 1) {
                android.util.Log.w(TAG, "回前台读隐身状态失败(state=$now)，保持基线不动、不重建")
                return@runCatching
            }

            // ② 和上次渲染时用的值一样：什么都不做。
            if (now == lastSeenStealthState) return@runCatching

            // ③ 基线本身未知（构造期就没读到）：界面本来就是照**当前**状态渲染的，
            //    所以只把基线对齐到 now，**不重建**（避免启动后无谓地闪一次）。
            if (lastSeenStealthState == STEALTH_STATE_UNKNOWN) {
                android.util.Log.i(
                    TAG,
                    "隐身基线未知，对齐为 $now（界面本就是按当前状态渲染的，不重建）"
                )
                lastSeenStealthState = now
                return@runCatching
            }

            // ④ 真的变了：先记下新值（防抖），再重建界面。
            android.util.Log.i(
                TAG,
                "回前台发现隐身状态变了：$lastSeenStealthState -> $now，重建界面"
            )
            // ⚠️ 顺序不能反：先记新值，再重建（防抖，见上面的说明）
            lastSeenStealthState = now
            // 已经要销毁的界面不再重建（那次 recreate 之后会有全新实例读到新状态）
            if (!isFinishing && !isDestroyed) {
                restartUiFresh(this)
            }
        }.onFailure {
            android.util.Log.w(TAG, "回前台核对隐身状态失败(忽略，不影响使用)", it)
        }
    }

    // 正常收尾的兜底（崩溃上报用）：删掉会话标记 = 这次不是崩着退出的。
    // ⚠️ 允许误判 —— 用户从最近任务划掉 App、隐身重建界面都可能走到这里，
    //    标记没了就只是"这次不上报"，绝不会误报；最终是否上报由日志内容决定。
    override fun onStop() {
        t3.background(t3ForegroundOwner)
        super.onStop()
        com.maplesu.core.theme.ThemeSound.stop()
        clearSessionMarkerIfForeground()
    }

    override fun onDestroy() {
        t3.destroy(t3ForegroundOwner, isFinishing)
        super.onDestroy()
        clearSessionMarkerIfForeground()
    }

    /** 删会话标记；整段 runCatching，绝不让收尾路径把 App 带崩。 */
    private fun clearSessionMarkerIfForeground() {
        if (!cameToForeground) return
        runCatching { CrashReporter.clearSessionMarker(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("maplesu_restore", false)) managerRestoreConfirmation.check(restart = true)
        intentChannel.trySend(intent)
    }

    private companion object {
        /**
         * 「基线不可信」：构造这一刻 / 回前台那一次就没读到内核隐身标志（读失败、内核不支持）。
         * 故意和内核自己的 `-1` 分开：`-1` 表示"**这一次**读失败"（下次还能再读），
         * [STEALTH_STATE_UNKNOWN] 表示"**基线本身**没有可信值"（只能对齐、不能据此重建）。
         */
        const val STEALTH_STATE_UNKNOWN = -2

        const val SPLASH_EXIT_DURATION_MS = 220L
        const val SPLASH_EXIT_SCALE = 1.06f

        /** 隐身相关日志统一用这个 tag（与 StealthReceiver 一致） */
        const val TAG = "maplesu-stealth"
    }

}

val LocalMainPagerState = staticCompositionLocalOf<MainPagerState> { error("LocalMainPagerState not provided") }

/** 预组合相邻页前先让闪屏退出动画（220ms）跑完。 */
private const val PAGER_WARMUP_START_DELAY_MS = 260L

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainScreen(
    homeViewModel: HomeViewModel,
    initialPage: Int = 0,
    pagerInterceptionMode: Int = PagerInterceptionMode.CrossAxisInterceptor.ordinal,
    isFullFeatured: Boolean = false,
    onPageChanged: (Int) -> Unit = {},
    onRefreshFacts: () -> Unit = {},
) {
    val navController = LocalNavigator.current
    val enableBlur = LocalEnableBlur.current
    val enableFloatingBottomBar = LocalEnableFloatingBottomBar.current
    val enableFloatingBottomBarBlur = LocalEnableFloatingBottomBarBlur.current
    val useNavigationRail = useNavigationRail(enableFloatingBottomBar)

    // Use the same ViewModel and hidden-state projection as the home card. Startup checks and
    // a persisted Ultra installation remain distinct from a settled "not installed" result.
    val homeUiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val homeEntryFacts by homeViewModel.entryFacts.collectAsStateWithLifecycle()
    val homeOnly = MainNavigationPolicy.isHomeOnly(
        hasInstalledVersion = homeUiState.showInstalledStatus,
        liteInstalled = homeUiState.liteInstalled,
        isGkiKernel = homeUiState.kernelVersion.isGKI(),
        litePending = homeUiState.litePending,
        hiddenMasking = homeEntryFacts.hidden.masking,
        hiddenConfirming = homeUiState.hiddenConfirming || homeEntryFacts.hidden.confirming,
    )
    val pageCount = if (homeOnly) 1 else MainPagerConfig.PAGE_COUNT
    val pagerState = rememberPagerState(
        initialPage = MainNavigationPolicy.resolvePage(initialPage, homeOnly),
        pageCount = { pageCount },
    )
    val mainPagerState = rememberMainPagerState(
        pagerState = pagerState,
        animatePageChanges = !useNavigationRail,
    )
    val pagerMode = PagerInterceptionMode.entries.getOrElse(pagerInterceptionMode) {
        PagerInterceptionMode.Native
    }
    val interceptPagerGestures = pagerMode == PagerInterceptionMode.CrossAxisInterceptor
    // Root readiness gates background work and badges; the home status gates navigation.
    // 事实来自 MainActivityViewModel（IO 线程读取）：组合期不碰 JNI（§1.5 / §10.2）。
    LifecycleResumeEffect(Unit) {
        onRefreshFacts()
        onPauseOrDispose { }
    }
    val enableNavigationBadge = LocalEnableNavigationBadge.current
    val badgeEnabled = enableNavigationBadge && isFullFeatured && !homeOnly
    val themeCountsEnabled = !homeOnly
    val moduleViewModel = viewModel<ModuleViewModel>()
    val moduleUiState by moduleViewModel.uiState.collectAsStateWithLifecycle()

    val superUserViewModel = viewModel<SuperUserViewModel>()
    val grantedUidCount by remember(superUserViewModel) {
        superUserViewModel.uiState
            .map { state ->
                if (state.hasLoaded && !state.isRefreshing && state.blockedReason == null && state.error == null)
                    state.groupedApps.count { it.anyAllowSu } else null
            }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(null)

    var startupPreloadStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isFullFeatured, homeOnly) {
        if (!isFullFeatured || homeOnly || startupPreloadStarted) {
            return@LaunchedEffect
        }

        moduleViewModel.initializePreferences()
        val moduleState = moduleViewModel.uiState.value
        if (!moduleState.hasLoaded) {
            if (!moduleState.isRefreshing && !moduleState.pending) moduleViewModel.fetchModuleList()
            // T10（D4）：环境未接线时列表会停在"启动中"（pending）。这里只等"任一结论"——
            // 死等 hasLoaded 会在未判定时把后面的预取一起挂住；接线后 ViewModel 自动重取一次。
            moduleViewModel.uiState.first { it.hasLoaded || it.pending }
        }
        moduleViewModel.syncModuleUpdateInfo(moduleViewModel.uiState.value.modules)

        // Ultra 环境也能安全预加载：`SuperUserRepositoryImpl.getAppList()` 在 Ultra 下走
        // PackageManager + SDK 白名单，不会碰 libmaplesu_runtime。
        val superUserState = superUserViewModel.uiState.value
        if (!superUserState.hasLoaded) {
            superUserViewModel.initializePreferences()
            if (superUserState.isRefreshing) {
                superUserViewModel.uiState.first { it.hasLoaded }
            } else {
                superUserViewModel.loadAppList().join()
            }
        }

        startupPreloadStarted = true
    }

    // Home tiles and navigation badges share the visible-list counts. A refresh/error
    // keeps the last successful values, including a legitimate zero, scoped by work mode.
    val countPrefs = remember { com.maplesu.core.ksuApp.getSharedPreferences("maplesu_navigation_counts",android.content.Context.MODE_PRIVATE) }
    val countScope = dev.maplesu.manager.workmode.ModeRuntime.active?.name.orEmpty()
    var superuserCount by remember(countScope) { mutableIntStateOf(countPrefs.getInt("$countScope.superuser",0)) }
    var enabledModuleCount by remember(countScope) { mutableIntStateOf(countPrefs.getInt("$countScope.modules",0)) }
    val loadedModuleCount = moduleUiState.modules.count { it.enabled }.takeIf {
        moduleUiState.hasLoaded && !moduleUiState.isRefreshing && moduleUiState.loadError == null && !moduleUiState.pending
    }
    LaunchedEffect(countScope,themeCountsEnabled,grantedUidCount,loadedModuleCount) {
        if (!themeCountsEnabled || countScope.isEmpty()) return@LaunchedEffect
        val editor = countPrefs.edit()
        grantedUidCount?.let { superuserCount=it; editor.putInt("$countScope.superuser",it) }
        loadedModuleCount?.let { enabledModuleCount=it; editor.putInt("$countScope.modules",it) }
        if (grantedUidCount != null || loadedModuleCount != null) editor.apply()
    }

    val navigationBadge = if (badgeEnabled) {
        NavigationBadgeState(
            superuserCount = superuserCount,
            moduleEnabledCount = enabledModuleCount,
            moduleUpdatableCount = moduleUiState.updateInfo.count { it.value.downloadUrl.isNotBlank() },
        )
    } else {
        NavigationBadgeState()
    }

    val uiMode = LocalUiMode.current
    val surfaceColor = when (uiMode) {
        UiMode.Material -> MaterialTheme.colorScheme.surface // Blur is not used in Material, this is just a placeholder
        UiMode.Miuix -> MiuixTheme.colorScheme.surface
    }
    val blurBackdrop = rememberBlurBackdrop(enableBlur)

    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    // 页码只在协程里观察：在 MainScreen 函数体里读会让每次切页都重组整个 MainScreen
    // （底栏、Scaffold、四个页面的外层一起），这是切页时整页卡一下的来源之一。
    val latestOnPageChanged by rememberUpdatedState(onPageChanged)
    val latestHomeOnly by rememberUpdatedState(homeOnly)
    LaunchedEffect(homeOnly, mainPagerState) {
        if (homeOnly) {
            mainPagerState.resetToHome()
            latestOnPageChanged(0)
        }
    }
    LaunchedEffect(mainPagerState) {
        snapshotFlow { mainPagerState.pagerState.settledPage }
            .collect { latestOnPageChanged(MainNavigationPolicy.resolvePage(it, latestHomeOnly)) }
    }
    LaunchedEffect(mainPagerState) {
        snapshotFlow { mainPagerState.pagerState.currentPage }
            .collect { mainPagerState.syncPage() }
    }

    MainScreenBackHandler(mainPagerState, navController)

    CompositionLocalProvider(
        LocalMainPagerState provides mainPagerState
    ) {
        val contentReady = rememberContentReady()
        // 预组合相邻页：原来首帧后 beyondViewportPageCount 从 0 一步跳到 3，同一帧里组合另外
        // 三整页（含超级用户/模块列表与设置页），正好压在闪屏退出动画上。现在等闪屏退完，
        // 每帧多放一页，单帧的组合量降到一页。
        var warmPages by remember { mutableIntStateOf(0) }
        LaunchedEffect(contentReady, homeOnly) {
            if (homeOnly) {
                warmPages = 0
                return@LaunchedEffect
            }
            if (!contentReady || warmPages >= MainPagerConfig.LAST_PAGE_INDEX) return@LaunchedEffect
            delay(PAGER_WARMUP_START_DELAY_MS)
            while (warmPages < MainPagerConfig.LAST_PAGE_INDEX) {
                withFrameNanos { }
                warmPages += 1
            }
        }
        val pagerContent = rememberSkinContentWithParameter { bottomInnerPadding: Dp ->
            Box(modifier = if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier) {
                HorizontalPager(
                    modifier = Modifier
                        .pagerGestureOverride(
                            pagerState = mainPagerState.pagerState,
                            mode = pagerMode,
                            enabled = !homeOnly,
                        )
                        .then(if (enableFloatingBottomBar && enableFloatingBottomBarBlur) Modifier.layerBackdrop(backdrop) else Modifier),
                    state = mainPagerState.pagerState,
                    beyondViewportPageCount = warmPages.coerceAtMost(pageCount - 1),
                    overscrollEffect = null,
                    userScrollEnabled = !homeOnly && !interceptPagerGestures,
                    pageNestedScrollConnection = if (interceptPagerGestures) {
                        PagerGestureNestedScrollConnection
                    } else {
                        pageNestedScrollConnection(
                            state = mainPagerState.pagerState,
                            orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                        )
                    },
                    flingBehavior = flingBehavior(
                        state = mainPagerState.pagerState,
                        snapAnimationSpec = PagerNavigationSpringSpec,
                    ),
                ) { page ->
                    // 只在单页作用域里读 settledPage：切页只重组受影响的页，不再波及 MainScreen。
                    val isCurrentPage = page == mainPagerState.pagerState.settledPage
                    // 页面是否参与组合由 beyondViewportPageCount 决定（warmPages 逐帧放开）；
                    // 用户在预组合完成前就滑过去时，当前页总是要画。
                    when (page) {
                        0 -> HomePager(homeViewModel, navController, bottomInnerPadding, isCurrentPage,
                            authorizedCount = superuserCount.takeIf { themeCountsEnabled },
                            moduleEnabledCount = enabledModuleCount.takeIf { themeCountsEnabled })
                        1 -> SuperUserPager(navController, bottomInnerPadding, isCurrentPage)
                        2 -> ModulePager(bottomInnerPadding, isCurrentPage)
                        3 -> SettingPager(navController, bottomInnerPadding, isCurrentPage)
                    }
                }
            }
        }

        if (useNavigationRail) {
            val startInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Start)
            val navBarBottomPadding = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

            when (uiMode) {
                UiMode.Material -> androidx.compose.material3.Scaffold(
                    containerColor = MaterialTheme.colorScheme.background
                ) {
                    Row {
                        if (!homeOnly) SideRail(navigationBadge)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .consumeWindowInsets(startInsets)
                        ) {
                            pagerContent(navBarBottomPadding)
                        }
                    }
                }

                UiMode.Miuix -> Scaffold { _ ->
                    Row {
                        if (!homeOnly) SideRail(navigationBadge)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .consumeWindowInsets(startInsets)
                        ) {
                            pagerContent(navBarBottomPadding)
                        }
                    }
                }
            }
        } else {
            val bottomBar = @Composable {
                if (!homeOnly) Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BottomBar(
                        blurBackdrop = blurBackdrop,
                        backdrop = backdrop,
                        navigationBadge = navigationBadge,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            when (uiMode) {
                UiMode.Material -> androidx.compose.material3.Scaffold(
                    bottomBar = bottomBar,
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    pagerContent(innerPadding.calculateBottomPadding())
                }

                UiMode.Miuix -> Scaffold(bottomBar = bottomBar) { innerPadding ->
                    pagerContent(innerPadding.calculateBottomPadding())
                }
            }
        }
    }
}

@Composable
private fun MainScreenBackHandler(
    mainState: MainPagerState,
    navController: Navigator,
) {
    val isPagerBackHandlerEnabled by remember {
        derivedStateOf {
            navController.current() is Route.Main && navController.backStackSize() == 1 && mainState.selectedPage != 0
        }
    }

    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)

    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isPagerBackHandlerEnabled,
        onBackCompleted = {
            mainState.animateToPage(0)
        }
    )
}
