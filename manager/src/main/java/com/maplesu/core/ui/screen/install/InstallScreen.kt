package com.maplesu.core.ui.screen.install

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.maplesu.core.R
import com.maplesu.core.ui.LocalUiMode
import com.maplesu.core.ui.UiMode
import com.maplesu.core.ui.navigation3.LocalNavigator
import com.maplesu.core.ui.navigation3.Route
import com.maplesu.core.ui.screen.flash.FlashIt
import com.maplesu.core.ui.screen.home.HomeInstallSignal
import com.maplesu.core.ui.screen.home.readInstallSignal
import com.maplesu.core.ui.security.HiddenFactsSignal
import com.maplesu.core.ui.security.HiddenStartupConfirm
import com.maplesu.core.ui.util.LkmSelection
import com.maplesu.core.ui.util.managerRootAvailable
import dev.maplesu.manager.installer.InstallPartitionPolicy
import dev.maplesu.manager.installer.InstallPartition
import dev.maplesu.manager.installer.LenovoSigningOptions
import dev.maplesu.manager.installer.lenovoFingerprint
import dev.maplesu.manager.installer.InstallPartitionSelection
import dev.maplesu.manager.installer.InstallTargetDecision
import dev.maplesu.manager.installer.InstallTargetDispatch
import dev.maplesu.manager.installer.InstallTargetMode
import dev.maplesu.manager.installer.InstallTargetModePolicy
import dev.maplesu.manager.installer.InstallerBootTarget
import dev.maplesu.manager.installer.InstallerServiceClient
import dev.maplesu.manager.installer.StockInstallPreflight
import dev.maplesu.manager.installer.UltraDirectInstallPolicy
import dev.maplesu.manager.installer.installTargetBlockerMessage
import dev.maplesu.manager.workmode.ui.WorkModeSwitchSession
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.detect.probes.EnvironmentCheckProduction
import dev.maplesu.manager.workmode.detect.probes.EnvironmentProbesAdapter
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.RootExec
import top.yukonga.miuix.kmp.basic.SnackbarHostState as MiuixSnackbarHostState
import java.io.File

/**
 * 安装页 = 四步里的前三步，刷写页 = 第四步（修补成功/失败）。
 *
 * ```
 * ①  目标工作模式 + 分区  Ultra（默认）/ Normal；按实际载体选择 init_boot / boot
 * ②  选择 boot           选文件 / 当前 boot（当前 boot = 直接安装，需要 root 会话）
 * ③  选择内核版本         Ultra 只列能被我们修补的那个版本；Normal 列 KMI × 分区的结论
 *     「下一步」          选文件 = 修补并导出；直接安装 = 修补并写入
 * ④  修补成功            FlashScreen 的成功态：导出路径或写入结果
 * ```
 *
 * 目标模式与分区的语义（与 KernelSU 一致）：
 *  - 选择**不持久化**：每次打开本页都从 Ultra 开始（默认值不会被"上次选过 Normal"改写）；
 *  - Normal + 直接安装：源镜像由 root 从**当前槽的目标分区**读出来再修补，**不要求先选文件**；
 *  - Ultra 的当前载体和槽位来自安装服务实际观测，操作和目标分区在提交时快照。
 *
 * 与旧页面的差别（用户口径：「不需要什么记录乱七八糟的东西」）：
 *  - 原管理器迁移并入直接安装，高级选项控制是否自动还原匹配的原始镜像；
 *  - 没有等级徽标 / 补丁条数 / 备选档 / 确认摘要 / 事务回读卡片。
 *
 * fail-closed 的位置**一个都没少**：`InstallTargetDispatch` 仍在点「下一步」时纯函数裁决，
 * `canInstall` 只是"步骤 ③ 还没选出来"，不是新的产品级拦截。
 */
@Composable
fun InstallScreen(
    initialTarget: InstallTargetMode = InstallTargetMode.DEFAULT,
    migrationEntry: Boolean = false,
    onBack: (() -> Unit)? = null,
    deviceFingerprint: String? = null,
) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    val miuixSnackbarHost = remember { MiuixSnackbarHostState() }
    val uiMode = LocalUiMode.current
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val moduleChoiceModel = viewModel<UltraModuleChoiceViewModel>()
    val moduleChoice by moduleChoiceModel.state.collectAsStateWithLifecycle()
    var installAfterModuleChoice by rememberSaveable { mutableStateOf(false) }

    var installMethod by rememberSaveable { mutableStateOf<InstallMethod?>(null) }
    var installerTarget by remember { mutableStateOf<InstallerBootTarget?>(null) }
    var sourceLoading by remember { mutableStateOf(false) }
    var sourceAccessFailure by remember { mutableStateOf<InstallSourceAccessFailure?>(null) }
    var sourceRequest by remember { mutableIntStateOf(0) }
    var pageResumed by remember { mutableStateOf(false) }
    var existingRootConflictAccepted by rememberSaveable { mutableStateOf(false) }
    var restoreOriginalImage by rememberSaveable { mutableStateOf(false) }
    var originalManagerPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var originalManagers by remember { mutableStateOf(emptyList<OriginalRootManager>()) }
    var signing by rememberSaveable { mutableStateOf(LenovoSigningOptions()) }
    val vivoDevice = remember { dev.maplesu.manager.installer.VivoCompatibility.deviceSupported() }
    var vivoInputs by rememberSaveable { mutableStateOf(dev.maplesu.manager.installer.VivoCompatibilityInputs()) }
    var vivoImageName by rememberSaveable { mutableStateOf<String?>(null) }
    var vivoBootWarning by rememberSaveable { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var signingTouched by rememberSaveable { mutableStateOf(false) }
    var signingDialog by rememberSaveable { mutableStateOf(false) }
    var aospDraft by rememberSaveable { mutableStateOf(false) }
    var signingImageName by rememberSaveable { mutableStateOf<String?>(null) }
    var managerInstalled by remember { mutableStateOf<Boolean?>(null) }
    var rootGranted by remember { mutableStateOf(false) }
    val detectionRevision by ModeRuntime.detectionRevision.collectAsStateWithLifecycle()
    val hiddenRevision by HiddenFactsSignal.revision.collectAsStateWithLifecycle()
    val sdkRootAvailable by UltraEnvironmentProbe.rootAvailability.collectAsStateWithLifecycle(
        initialValue = UltraEnvironmentProbe.currentSnapshot(context.applicationContext).rootAvailable,
    )
    // 只读已确认的隐藏事实；变化当帧收起入口，不等后台权限检查返回。
    val directInstallSuppressed = remember(context, detectionRevision, hiddenRevision) {
        val mapleModeKnown = ModeRuntime.active != null ||
            EnvironmentCheckProduction.store(context).snapshot()?.mode != null
        !ModeRuntime.detectionComplete || HiddenStartupConfirm.masking(context) ||
            HiddenStartupConfirm.isPending(context) ||
            (mapleModeKnown && HiddenStartupConfirm.current(context))
    }
    val hasRoot = rootGranted && !directInstallSuppressed
    // 事务进行中：只用于挡连点。
    var installInFlight by rememberSaveable { mutableStateOf(false) }
    // Consume the shared home fact without starting or awaiting another probe.
    LaunchedEffect(deviceFingerprint, installInFlight) {
        if (!installInFlight && !signingTouched && lenovoFingerprint(deviceFingerprint)) {
            signingTouched = true
            advancedExpanded = true
            signing = signing.copy(enabled = true)
        }
    }

    // ── 步骤 ①：目标工作模式 + 目标分区 ────────────────────────────────────
    // 默认 Ultra（每次打开本页都是 Ultra：选择**不持久化**，避免"上次选过 Normal 就再也回不到
    // 默认值"）。Normal 按实际分区和 KMI 选择；Ultra 更新保持已观测载体。
    var targetMode by rememberSaveable { mutableStateOf(initialTarget) }
    val ultraAction = UltraInstallAction.AUTOMATIC
    var observedCarrier by remember { mutableStateOf<String?>(null) }
    var bridgeFailure by remember { mutableStateOf<String?>("ULTRA_KERNEL_INIT_BOOT_ARTIFACT_REQUIRED") }
    var storedPartition by rememberSaveable { mutableStateOf(InstallPartition.BOOT) }
    var partitionTouched by rememberSaveable { mutableStateOf(false) }
    var imagePartition by remember { mutableStateOf<InstallPartition?>(null) }
    // 事务进行中：选择器仍然可见但不可操作（`InstallTargetModePolicy` 是既有策略，纯函数）。
    val selectionsLocked = !InstallTargetModePolicy.selectable(
        installRunning = installInFlight,
        switchRunning = WorkModeSwitchSession.executing,
    ) || sourceLoading || moduleChoice.show || moduleChoice.busy

    // 设备当前运行内核：步骤 ③ 的"当前 boot"来源直接用它的 release（不开 root）。
    var deviceKernelRelease by remember { mutableStateOf<String?>(null) }
    var kernelReleaseLoading by remember { mutableStateOf(true) }
    val partition = if (installMethod is InstallMethod.SelectFile && imagePartition != null) imagePartition!!
        else {
            val current = InstallPartition.fromWire(observedCarrier)
            if (partitionTouched) storedPartition else current ?: InstallPartition.BOOT
        }
    LaunchedEffect(Unit) {
        deviceKernelRelease = withContext(Dispatchers.IO) { readDeviceKernelRelease() }
        kernelReleaseLoading = false
    }
    LifecycleResumeEffect(Unit) {
        // 从刷写页回来 → 允许下一次安装。
        installInFlight = false
        onPauseOrDispose { }
    }
    LifecycleResumeEffect(context, detectionRevision, hiddenRevision) {
        pageResumed = true
        val job = scope.launch {
            managerInstalled = withContext(Dispatchers.IO) {
                when (readInstallSignal(ModeRuntime.active)) {
                    HomeInstallSignal.INSTALLED -> true
                    HomeInstallSignal.NOT_INSTALLED -> false
                    HomeInstallSignal.UNKNOWN -> null
                }
            }
            originalManagers = withContext(Dispatchers.IO) { originalRootManagers(context) }
            if (originalManagerPackage != null && originalManagers.none { it.packageName == originalManagerPackage }) {
                originalManagerPackage = null
                installerTarget = null
                sourceRequest++
            }
        }
        onPauseOrDispose {
            pageResumed = false
            // 返回手势/暂时离开不改变显示事实；恢复后静默复核 SU，操作仍由 pageResumed 限制。
            job.cancel()
            installerTarget = null
        }
    }
    LaunchedEffect(pageResumed, detectionRevision, hiddenRevision, sdkRootAvailable) {
        if (!pageResumed) return@LaunchedEffect
        rootGranted = withContext(Dispatchers.IO) {
            runCatching {
                !directInstallSuppressed && managerRootAvailable(
                    mode = ModeRuntime.active,
                    ultraIdentity = UltraEnvironmentProbe.usesUltraBackend(context),
                    capability = RootExec.current()?.capability(forceRefresh = true) ?: RootExec.capability(context),
                    legacyShellRoot = { EnvironmentProbesAdapter(context).suPresent() },
                )
            }.getOrDefault(false)
        }
        if ((!rootGranted || directInstallSuppressed) && installMethod == InstallMethod.DirectInstall) {
            installMethod = null
            existingRootConflictAccepted = false
            installerTarget = null
        }
    }
    LaunchedEffect(sourceRequest, installMethod, targetMode, pageResumed, hasRoot,
        moduleChoice.show, moduleChoice.busy) {
        if (moduleChoice.show || moduleChoice.busy) return@LaunchedEffect
        if (!hasRoot || !pageResumed || installMethod != InstallMethod.DirectInstall || !existingRootConflictAccepted) return@LaunchedEffect
        sourceLoading = true
        sourceAccessFailure = null
        installerTarget = null
        observedCarrier = null
        val rootWasRunning = UltraEnvironmentProbe.currentSnapshot(context.applicationContext).rootAvailable
        try {
            val (discovered, carrier) = withContext(Dispatchers.IO) {
                val topology = StockInstallPreflight.discoverTarget(context, targetMode)
                val installed = if (targetMode == InstallTargetMode.ULTRA) {
                    val snapshot = InstallerServiceClient.observeBootChain(context)
                    check(topology.slotSuffix == snapshot.slotSuffix &&
                        topology.availablePartitions.all { it in snapshot.partitions }) { "INSTALLER_DEVICE_CHANGED" }
                    InstallerServiceClient.installedCarrierPartition(context, snapshot)
                } else null
                topology to installed
            }
            installerTarget = discovered
            observedCarrier = carrier
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("MapleSUInstaller", "Installation access check failed", error)
            sourceAccessFailure = installSourceAccessFailure(error,
                rootWasRunning || UltraEnvironmentProbe.currentSnapshot(context.applicationContext).rootAvailable)
        } finally {
            sourceLoading = false
        }
    }
    val selectFileTip = stringResource(id = R.string.install_select_image_summary)

    // ── 步骤 ③：boot 来源的内核事实（只读探测，不写 files/images/imported.img）──────────
    // 识别完立刻删临时副本：`imported.img` 在确认之前必须保持"现场 boot 的备份"语义（47 报告 R3）。
    var bootFlow by remember { mutableStateOf<BootFlowProfile?>(null) }
    var pairedBootObservation by remember { mutableStateOf<Pair<String, Bundle>?>(null) }
    val bootSourceKey = when (val method = installMethod) {
        is InstallMethod.SelectFile -> method.uri?.toString().orEmpty()
        InstallMethod.DirectInstall -> "current"
        else -> ""
    }
    LaunchedEffect(targetMode, bootSourceKey, sourceRequest, pageResumed) {
        bridgeFailure = "ULTRA_KERNEL_INIT_BOOT_ARTIFACT_REQUIRED"
        if (!pageResumed || targetMode != InstallTargetMode.ULTRA || installMethod == null) return@LaunchedEffect
        bridgeFailure = withContext(Dispatchers.IO) {
            try {
                if (dev.maplesu.manager.ultrakernel.UltraKernelFlow.initBootArtifact(context) == null)
                    "ULTRA_KERNEL_INIT_BOOT_ARTIFACT_REQUIRED" else null
            } catch (error: Exception) {
                error.message?.takeIf(String::isNotBlank) ?: com.maplesu.core.ui.util.ultraKernelStableCode(error)
            }
        }
    }
    LaunchedEffect(bootSourceKey) {
        imagePartition = null
        val uri = (installMethod as? InstallMethod.SelectFile)?.uri ?: return@LaunchedEffect
        imagePartition = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use(InstallPartitionPolicy::imagePartition)
            }.getOrNull()
        }
    }
    // 文件来源不随 uname 变化重跑（避免为了显示一行版本把几百 MB 镜像拷两遍）。
    val bootProbeKey = when (installMethod) {
        InstallMethod.DirectInstall -> deviceKernelRelease
        else -> null
    }
    LaunchedEffect(bootSourceKey, bootProbeKey, kernelReleaseLoading, targetMode, detectionRevision, pageResumed, hasRoot) {
        pairedBootObservation = null
        if (!pageResumed) return@LaunchedEffect
        // 等设备内核读完再探测：否则"当前 boot"来源会先闪一个"读不到内核版本"再变成真的。
        if (kernelReleaseLoading) return@LaunchedEffect
        val method = installMethod
        val uri = (method as? InstallMethod.SelectFile)?.uri
        if (method !is InstallMethod.DirectInstall && uri == null) {
            bootFlow = null
            return@LaunchedEffect
        }
        val device = deviceKernelRelease.orEmpty()
        bootFlow = InstallBootFlow.reading(bootSourceKey, device)
        val probed = withContext(Dispatchers.IO) {
            try {
                if (uri != null) probeInstallBoot(context.applicationContext, uri, device)
                else probeCurrentBoot(device) to null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val code = error.message.orEmpty().substringBefore(':').takeIf {
                    it.startsWith("ULTRA_INIT_") || it.startsWith("ULTRA_KERNEL_") || it.startsWith("INSTALLER_")
                } ?: "ULTRA_KERNEL_BASE_UNAVAILABLE"
                InstallBootFlow.failed(bootSourceKey, device, code, error.message.orEmpty()) to null
            }
        }
        bootFlow = probed.first
        pairedBootObservation = probed.second?.let { bootSourceKey to it }
    }

    val installMethodOptions = remember(selectFileTip, hasRoot) {
        buildList {
            add(InstallMethod.SelectFile(summary = selectFileTip))
            if (hasRoot) add(InstallMethod.DirectInstall)
        }
    }

    fun showMessage(message: String) {
        scope.launch {
            if (uiMode == UiMode.Material) {
                snackbarHost.showSnackbar(message)
            } else {
                miuixSnackbarHost.showSnackbar(message)
            }
        }
    }

    val onInstall = onInstall@{
        // UI 尚未重组时也拦住连点，以及其他入口刚发起的切换。
        if (!pageResumed || installInFlight || WorkModeSwitchSession.executing) return@onInstall
        if (moduleChoice.show || moduleChoice.busy) return@onInstall
        val method = installMethod ?: return@onInstall
        if (method == InstallMethod.DirectInstall && !hasRoot) return@onInstall
        if (method == InstallMethod.DirectInstall && !existingRootConflictAccepted) return@onInstall
        if (targetMode == InstallTargetMode.ULTRA) {
            val route = ultraInstallRoute(ultraAction, partition.wire, observedCarrier,
                installerTarget?.availablePartitions, method == InstallMethod.DirectInstall, bridgeFailure)
            if (route.blocker != null) {
                showMessage(route.blocker)
                return@onInstall
            }
        }
        if (method == InstallMethod.DirectInstall &&
            UltraDirectInstallPolicy.requiresModuleChoice(targetMode, ModeRuntime.active) &&
            moduleChoice.keepModules == null) {
            installAfterModuleChoice = true
            moduleChoiceModel.request()
            return@onInstall
        }
        if (installMethodOptions.none { it::class == method::class }) {
            showMessage(resources.getString(R.string.install_method_stale))
            return@onInstall
        }
        // 步骤 ① 的选择在事务开始这一刻快照（之后再改不影响本次事务）。
        val mode = targetMode
        val selectedPartition = partition
        val requestedPartition = selectedPartition.wire
        val resolvedAction = resolvedUltraInstallAction(ultraAction, requestedPartition, observedCarrier,
            method == InstallMethod.DirectInstall)
        val signingSnapshot = signing
        if (signingSnapshot.enabled && method is InstallMethod.SelectFile &&
                !signingSnapshot.fileInputsReady(selectedPartition.wire)) {
            showMessage(resources.getString(R.string.install_lenovo_images_required))
            return@onInstall
        }
        // Ultra：步骤 ③ 没选出目标时按钮本来就是不可点的；这里再挡一次（状态可能在两帧之间变了）。
        // Normal：目标来自设备 KMI × 分区，判据是同一个纯函数（见下面的 decide）。
        val target = if (mode == InstallTargetMode.ULTRA) {
            bootFlow?.target ?: return@onInstall
        } else {
            null
        }
        // 分派是纯函数 fail-closed：Ultra 按实际载体走镜像链路，Normal 走 KSU LKM 链路
        // 且分区就是用户选的那一个 —— 页面不自己做 if。
        val decision = InstallTargetDispatch.decide(
            installTargetRequestFor(
                method = method,
                target = mode,
                kernelRelease = deviceKernelRelease,
                sdkInt = Build.VERSION.SDK_INT,
                partition = requestedPartition,
            ).copy(availablePartitions = installerTarget?.availablePartitions.takeIf { method == InstallMethod.DirectInstall }),
        )
        if (decision is InstallTargetDecision.Blocked) {
            showMessage(installTargetBlockerMessage(resources, decision))
            return@onInstall
        }
        // 事务开始前快照目标 id。现场生成档的伪 id 不作为目录资产 id 传下去
        // （写不写盘由 release 有没有资产决定，不由这个 id 决定）。
        val variantSnapshot = if (target != null && !target.generatedOnDevice) target.id else null
        val generatedOnDevice = target != null && target.generatedOnDevice
        val request = when (method) {
            is InstallMethod.SelectFile -> {
                val image = method.uri ?: return@onInstall
                val paired = pairedBootObservation?.takeIf { it.first == image.toString() }?.second
                if (mode == InstallTargetMode.ULTRA && selectedPartition == InstallPartition.INIT_BOOT && paired == null)
                    return@onInstall
                FlashIt.FlashBoot(
                    boot = image,
                    pairedBootSnapshot = paired?.let(::Bundle),
                    lkm = LkmSelection.KmiNone,
                    ota = false,
                    partition = requestedPartition,
                    targetMode = mode,
                    variantId = variantSnapshot,
                    generatedOnDevice = generatedOnDevice,
                    lenovoSigning = signingSnapshot,
                    vivoInputs = vivoInputs,
                    forceRepatch = mode == InstallTargetMode.ULTRA && resolvedAction.forceRepatch,
                )
            }

            InstallMethod.DirectInstall -> {
                if (installerTarget == null) return@onInstall
                FlashIt.FlashBoot(
                    lkm = LkmSelection.KmiNone,
                    ota = false,
                    partition = requestedPartition,
                    targetMode = mode,
                    variantId = variantSnapshot,
                    generatedOnDevice = generatedOnDevice,
                    existingRootConflictAccepted = existingRootConflictAccepted,
                    restoreOriginalImage = restoreOriginalImage ||
                        mode == InstallTargetMode.ULTRA && resolvedAction.restoresOtherCarrier,
                    originalManagerPackage = originalManagerPackage,
                    lenovoSigning = signingSnapshot,
                    vivoInputs = vivoInputs,
                    forceRepatch = mode == InstallTargetMode.ULTRA && resolvedAction.forceRepatch,
                )
            }

            else -> return@onInstall
        }
        installInFlight = true
        navigator.push(Route.Flash(request))
    }

    val selectImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            it.data?.data?.let { uri ->
                moduleChoiceModel.reset()
                installAfterModuleChoice = false
                existingRootConflictAccepted = false
                installerTarget = null
                sourceAccessFailure = null
                imagePartition = null
                bootFlow = null
                restoreOriginalImage = false
                installMethod = InstallMethod.SelectFile(uri, summary = selectFileTip)
                if (vivoDevice) advancedExpanded = true
            }
        }
    }
    val vivoImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val name = vivoImageName; vivoImageName = null
        if (uri != null && name != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vivoInputs = if (name == "vendor_boot") vivoInputs.copy(vendorBoot = uri) else vivoInputs.copy(initBoot = uri)
        }
    }
    val signingImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val name = signingImageName
        signingImageName = null
        if (uri != null && name != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            signing = when (name) {
                "vbmeta" -> signing.copy(vbmeta = uri)
                "recovery" -> signing.copy(recovery = uri)
                "vbmeta_system" -> signing.copy(vbmetaSystem = uri)
                else -> signing
            }
        }
    }

    val state = InstallUiState(
        installMethod = installMethod,
        installMethodOptions = installMethodOptions,
        bootFlow = bootFlow,
        targetMode = targetMode,
        partition = partition,
        partitionOptions = if (installMethod is InstallMethod.SelectFile && imagePartition != null) listOf(imagePartition!!)
        else InstallPartition.ALLOWED.filter { installerTarget?.availablePartitions?.contains(it.wire) ?: (it == partition) },
        targetSelectable = !selectionsLocked,
        migrationEntry = migrationEntry,
        managerInstalled = managerInstalled,
        directAccessReady = hasRoot && pageResumed && installerTarget != null && existingRootConflictAccepted,
        sourceLoading = sourceLoading,
        sourceAccessFailed = sourceAccessFailure != null,
        sourceAccessFailure = sourceAccessFailure,
        sourceUsesManagedRoot = ModeRuntime.active != null || UltraEnvironmentProbe.usesUltraBackend(context),
        restoreOriginalImage = restoreOriginalImage,
        originalManagers = originalManagers,
        originalManagerPackage = originalManagerPackage,
        advancedExpanded = advancedExpanded,
        lenovoSigning = signing,
        vivoDevice = vivoDevice,
        vivoInputs = vivoInputs,
        observedCarrier = observedCarrier,
        observedSlotSuffix = installerTarget?.slotSuffix,
        availableCarriers = installerTarget?.availablePartitions,
        bridgeFailure = bridgeFailure,
    )
    LaunchedEffect(moduleChoice.keepModules, moduleChoice.show, pageResumed, state.canInstall, sourceLoading) {
        if (installAfterModuleChoice && pageResumed && state.canInstall && !sourceLoading &&
            installerTarget != null && !moduleChoice.show && moduleChoice.keepModules != null) {
            installAfterModuleChoice = false
            onInstall()
        }
    }
    val actions = InstallScreenActions(
        onBack = dropUnlessResumed { if (!moduleChoice.busy) onBack?.invoke() ?: navigator.pop() },
        onSelectMethod = { method ->
            if (!selectionsLocked && pageResumed) {
                if (method == InstallMethod.DirectInstall) {
                    if (hasRoot) {
                        if (installMethod != InstallMethod.DirectInstall) {
                            restoreOriginalImage = false
                        }
                        existingRootConflictAccepted = true
                        installerTarget = null
                        installMethod = InstallMethod.DirectInstall
                        sourceRequest++
                        if (UltraDirectInstallPolicy.requiresModuleChoice(targetMode, ModeRuntime.active) &&
                            moduleChoiceModel.state.value.keepModules == null) moduleChoiceModel.request()
                    }
                }
                else {
                    moduleChoiceModel.reset()
                    installAfterModuleChoice = false
                    existingRootConflictAccepted = false
                    restoreOriginalImage = false
                    installMethod = method
                }
            }
        },
        onSelectBootImage = {
            selectImageLauncher.launch(
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
            )
        },
        onSelectPartition = { requested ->
            val next =
                if (!selectionsLocked && requested in state.partitionOptions && !state.partitionFixed) requested else storedPartition
            if (next != storedPartition) {
                installerTarget = null
                sourceRequest++
            }
            storedPartition = next
            if (!selectionsLocked && !state.partitionFixed) partitionTouched = true
        },
        onNext = {
            if (!sourceLoading && !installInFlight && !WorkModeSwitchSession.executing) {
                onInstall()
            }
        },
        onToggleAdvanced = { advancedExpanded = !advancedExpanded },
        onLenovoSigningChange = { enabled ->
            if (!selectionsLocked) {
                signingTouched = true
                if (enabled) { aospDraft = signing.aosp; signingDialog = true }
                else signing = signing.copy(enabled = false)
            }
        },
        onConfigureLenovoSigning = {
            if (!selectionsLocked) { signingTouched = true; aospDraft = signing.aosp; signingDialog = true }
        },
        onSelectVivoImage = { name ->
            if (!selectionsLocked) { vivoImageName = name; vivoImageLauncher.launch(arrayOf("*/*")) }
        },
        onSelectSigningImage = { name ->
            if (!selectionsLocked) { signingImageName = name; signingImageLauncher.launch(arrayOf("*/*")) }
        },
        onRestoreOriginalImageChange = { restore ->
            if (!selectionsLocked) {
                restoreOriginalImage = restore
            }
        },
        onOriginalManagerChange = { managerPackage ->
            if (!selectionsLocked) {
                originalManagerPackage = managerPackage
            }
        },
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> InstallScreenMiuix(state, actions, miuixSnackbarHost)
        UiMode.Material -> InstallScreenMaterial(state, actions, snackbarHost)
    }
    UltraModuleChoiceDialog(
        state = moduleChoice,
        onKeep = moduleChoiceModel::keep,
        onRemove = { moduleChoiceModel.remove(context) },
        onDismiss = {
            installAfterModuleChoice = false
            moduleChoiceModel.dismiss()
        },
    )
    LenovoSigningDialog(
        show = signingDialog, aosp = aospDraft,
        onAospChange = { aospDraft = it },
        onConfirm = { signing = signing.copy(enabled = true, aosp = aospDraft); signingDialog = false },
        onDismiss = { signingDialog = false },
    )
    VivoBootChoiceDialog(show = vivoBootWarning,
        onConfirm = {
            vivoBootWarning = false
        }, onDismiss = { vivoBootWarning = false })
}

/** Pair current init_boot or its byte-exact owned inverse with the snapshot's actual boot. */
private fun probeInstallBoot(context: Context, uri: Uri, deviceRelease: String): Pair<BootFlowProfile, Bundle?> {
    val carrier = context.contentResolver.openInputStream(uri)?.use(InstallPartitionPolicy::imagePartition)
    if (carrier != InstallPartition.INIT_BOOT) return probeChosenBoot(context, uri, deviceRelease) to null
    val observed = InstallerServiceClient.observeBootChain(context)
    check("init_boot" in observed.partitions) { "ULTRA_INIT_PAIRED_BOOT_REQUIRED" }
    val boot = observed.partitions["boot"] ?: error("ULTRA_INIT_PAIRED_BOOT_REQUIRED")
    val source = File.createTempFile("init-boot-probe-", ".img", context.cacheDir)
    try {
        val input = context.contentResolver.openInputStream(uri) ?: error("BOOT_SOURCE_READ_FAILED")
        input.use { stream ->
            source.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var size = 0L
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    check(count > 0 && count <= dev.maplesu.manager.ImageRepository.MAX_IMAGE_BYTES - size) {
                        "ULTRA_INIT_PAIRED_BOOT_SOURCE_MISMATCH"
                    }
                    output.write(buffer, 0, count)
                    size += count
                }
                check(size > 0) { "ULTRA_INIT_PAIRED_BOOT_SOURCE_MISMATCH" }
            }
        }
        InstallerServiceClient.verifyInitBootSource(context, observed, source)
        val paired = InstallerServiceClient.capturePairedBoot(context, observed,
            File(context.filesDir, "ultra-init-paired-${boot.sha256}.img"))
        val profile = probeChosenBoot(context, Uri.fromFile(source), deviceRelease, paired)
            .copy(source = uri.lastPathSegment?.substringAfterLast('/').orEmpty())
        check(!profile.selectable || profile.release == observed.kernelRelease) { "ULTRA_INIT_PAIRED_BOOT_KERNEL_MISMATCH" }
        return profile to InstallerServiceClient.encode(observed)
    } finally {
        source.delete()
    }
}

/**
 * 设备内核 release（与 `uname -r` 等价）。
 *
 * 读不到 → null：步骤 ③ 的"当前 boot"来源会显示"读不到内核版本"，不猜。
 * 只读 `/proc`，不开 root、不 exec ksud（未安装 root 时本页也要能渲染）。
 */
private fun readDeviceKernelRelease(): String? {
    val fromProc = runCatching {
        java.io.File("/proc/sys/kernel/osrelease").readText().trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }
    if (fromProc != null) return fromProc
    return runCatching { System.getProperty("os.version")?.trim() }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }
}
