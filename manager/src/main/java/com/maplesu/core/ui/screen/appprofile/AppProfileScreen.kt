package com.maplesu.core.ui.screen.appprofile

import android.util.Log
import android.widget.Toast
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.maplesu.core.Natives
import com.maplesu.core.R
import com.maplesu.core.ui.LocalUiMode
import com.maplesu.core.ui.UiMode
import com.maplesu.core.ui.navigation3.LocalNavigator
import com.maplesu.core.ui.navigation3.Route
import com.maplesu.core.ui.util.forceStopApp
import com.maplesu.core.ui.util.launchApp
import com.maplesu.core.ui.util.restartApp
import com.maplesu.core.ui.viewmodel.SuperUserViewModel
import dev.maplesu.manager.runtime.State
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.authorization.DirectPolicy
import dev.maplesu.manager.runtime.stateFor

@Composable
fun AppProfileScreen(uid: Int) {
    val uiMode = LocalUiMode.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val viewModel: SuperUserViewModel = viewModel()
    val appGroupState = remember(uid) {
        derivedStateOf {
            viewModel.uiState.value.groupedApps.find { it.uid == uid } ?: SuperUserViewModel.getGroupedApp(uid)
        }
    }
    val appGroup = appGroupState.value
    val primaryAppInfo = appGroup?.primary
    if (primaryAppInfo == null) {
        LaunchedEffect(Unit) {
            navigator.pop()
        }
        return
    }

    val packageName = primaryAppInfo.profileKey
    val sharedUserId = remember(uid) {
        primaryAppInfo.packageInfo.sharedUserId
            ?: appGroup.apps.firstOrNull { it.packageInfo.sharedUserId != null }?.packageInfo?.sharedUserId
            ?: ""
    }

    var profile by remember(uid, packageName, primaryAppInfo.special) {
        mutableStateOf<Natives.Profile?>(null)
    }
    var loading by remember(uid, packageName, primaryAppInfo.special) { mutableStateOf(true) }
    var loadAttempt by remember(uid, packageName, primaryAppInfo.special) { mutableStateOf(0) }
    var writePolicy by remember(uid, packageName, primaryAppInfo.special) {
        mutableStateOf<ProfileWritePolicy<Natives.Profile>?>(null)
    }
    val saveMutex = remember(uid, packageName, primaryAppInfo.special) { Mutex() }
    var saving by remember(uid, packageName, primaryAppInfo.special) { mutableStateOf(false) }
    LaunchedEffect(uid, packageName, primaryAppInfo.special, loadAttempt) {
        loading = true
        profile = withContext(Dispatchers.IO) {
            // Special system rows are informational, not package-based authorization records.
            if (primaryAppInfo.special) Natives.Profile(packageName, currentUid = uid)
            else if (loadAttempt > 0) Natives.getAppProfileFresh(packageName, uid)
            else Natives.getAppProfile(packageName, uid)
        }
        writePolicy = profile?.let { ProfileWritePolicy(it) }
        loading = false
    }
    val failToUpdateAppProfile = stringResource(R.string.failed_to_update_app_profile).format(primaryAppInfo.label)
    val deliveryIncompleteMessage = stringResource(R.string.su_grants_saved_delivery_incomplete)
    val suNotAllowed = stringResource(R.string.su_not_allowed).format(primaryAppInfo.label)
    val suDisabled = stringResource(R.string.maplesu_su_disabled_grant)
    // Soft revocation is a visible property of the contract, not a footnote.
    val revokeDisclosure = stringResource(R.string.maplesu_revoke_disclosure)

    // The state is read off the main thread: it starts one root process for the wiring
    // signal. It is keyed on the grant so toggling the switch shows the new truth.
    var suState by remember(uid, packageName) { mutableStateOf(State.NOT_AUTHORIZED) }
    LaunchedEffect(uid, packageName, profile?.allowSu, loadAttempt, saving) {
        if (saving) return@LaunchedEffect
        val granted = profile?.allowSu == true
        suState = withContext(Dispatchers.IO) {
            // Ultra：白名单是包名维度、没有签名绑定，不存在「旧安装的残留记录」这种状态；
            // 而 DirectPolicy.grantedUids 会起一次性 root 进程（Ultra 内核上会重启系统）。
            val stale = if (UltraEnvironmentProbe.usesUltraBackend(context)) false
            else !granted && DirectPolicy.grantedUids(context).orEmpty().contains(uid)
            stateFor(context, packageName, granted, stale)
        }
    }

    fun showMessage(message: String) {
        scope.launch {
            if (uiMode == UiMode.Material) {
                snackbarHost.showSnackbar(message)
            } else {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val state = AppProfileUiState(
        uid = uid,
        packageName = packageName,
        profile = profile ?: Natives.Profile(packageName, currentUid = uid),
        appGroup = appGroup,
        sharedUserId = sharedUserId,
        isLoading = loading,
        loadFailed = !loading && profile == null,
        isSaving = saving,
        suState = suState,
    )

    val actions = AppProfileActions(
        onBack = dropUnlessResumed { navigator.pop() },
        // 🔴（v2.19）这三个都是 **root shell 往返**（getRootShell + am/cmd package ...）。
        // 旧写法用函数引用直接挂在 onClick 上 ⇒ 在**主线程**同步跑 su，shell 没缓存时
        // 还要 fork/exec，最坏数秒 ⇒ 点一下白屏/ANR。现在挪到 IO 协程，主线程只负责发起。
        onLaunchApp = { pkg, userId -> scope.launch { withContext(Dispatchers.IO) { launchApp(pkg, userId) } } },
        onForceStopApp = { pkg, userId -> scope.launch { withContext(Dispatchers.IO) { forceStopApp(pkg, userId) } } },
        onRestartApp = { pkg, userId -> scope.launch { withContext(Dispatchers.IO) { restartApp(pkg, userId) } } },
        // 模板入口（2026-11-01 收尾）：以前是 `{}` 空实现 —— 点了没反应，等于功能缺失。
        // 现在走真实的模板链路：Normal 由 ksud `profile` 子命令提供（KSU 模板），
        // Ultra 走既有的 Lite 实现；"查看"打开对应模板编辑器，"管理"进入模板列表。
        onViewTemplate = { templateId ->
            scope.launch {
                val info = withContext(Dispatchers.IO) {
                    runCatching {
                        com.maplesu.core.data.repository.TemplateRepositoryImpl()
                            .getTemplate(templateId)
                            .getOrNull()
                    }.getOrNull()
                }
                if (info != null) {
                    navigator.push(Route.TemplateEditor(info, readOnly = !info.local))
                } else {
                    // 读不到就显式说"读不到"，不假装打开了模板。
                    showMessage(
                        context.getString(R.string.app_profile_template_not_found, templateId),
                    )
                }
            }
        },
        onManageTemplate = { navigator.push(Route.AppProfileTemplate) },
        onRetry = { loadAttempt++ },
        onProfileChange = change@{ updatedProfile ->
            val policy = writePolicy ?: return@change
            val profileToSave = updatedProfile.copy(name = packageName, currentUid = uid,
                allowSu = !primaryAppInfo.special && updatedProfile.allowSu)
            if (profileToSave.allowSu && uid < 2000 && uid != 1000) {
                showMessage(suNotAllowed)
            } else {
                // 🟢 先更新界面，再写后端（2026-10-29）：点击开关必须"零等待"。
                //    以前的顺序是「等 `Natives.setAppProfile` 返回 → 才改 profile」，
                //    而这次写入要进 JNI（Ultra 是 SDK 的 add_su_auth / remove_su_auth，
                //    KSU 是一次性 root 进程），几十到几百毫秒里开关纹丝不动，
                //    用户看到的就是「点了没反应」。现在先把目标状态落到 UI（开关 + 摘要
                //    文案同帧翻转），写入与验证在后台做；只有失败才回滚并提示。
                val request = policy.request()
                profile = profileToSave
                saving = true
                scope.launch {
                    saveMutex.withLock {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                Natives.setAppProfileDetailed(profileToSave)
                            }
                                .getOrElse { Natives.ProfileWriteResult(false, commitUnknown = true,
                                    code = "PROFILE_WRITE_EXCEPTION", detail = it.javaClass.simpleName) }
                        }
                        val saved = result.decisionCommitted
                        val completion = policy.complete(request, profileToSave, saved)
                            ?: return@withLock
                        profile = completion.profile
                        saving = false
                        if (!result.succeeded) {
                            Log.w("AppProfileScreen", dev.maplesu.manager.runtime.ultra.UltraSdkLogging.redact(
                                "profile write code=${result.code} committed=${result.decisionCommitted} " +
                                    "pending=${result.pendingApply} unknown=${result.commitUnknown} ${result.detail}"))
                            if (result.decisionCommitted && result.pendingApply) showMessage(deliveryIncompleteMessage)
                            else showMessage(failToUpdateAppProfile)
                            if (result.pendingApply || result.commitUnknown) loadAttempt++
                        } else {
                            if (profileToSave.allowSu) {
                                val enabled = withContext(Dispatchers.IO) { Natives.isSuEnabled() }
                                if (policy.isLatest(request) && !enabled) showMessage(suDisabled)
                            }
                            if (uiMode == UiMode.Material) viewModel.loadAppList()
                        }
                    }
                }
            }
        },
    )

    when (uiMode) {
        UiMode.Miuix -> AppProfileScreenMiuix(
            state = state,
            actions = actions,
        )

        UiMode.Material -> AppProfileScreenMaterial(
            state = state,
            actions = actions,
            snackBarHost = snackbarHost,
        )
    }
}
