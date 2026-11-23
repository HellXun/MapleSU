package dev.maplesu.manager.workmode.ui

import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maplesu.core.R
import com.maplesu.core.ksuApp
import dev.maplesu.manager.liteweb.LiteWebAdminController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** WebUI 自检页状态（A/B 同一套 UI；后端不可用显示原因，不假装成功）。 */
data class WebUiSelfCheckUi(
    val modeLabel: String = "",
    val rootBackendLabel: String = "",
    val startResult: String = "",
    val webUiLink: String = "",
    val statusLines: List<String> = emptyList(),
    val bootEvidenceLines: List<String> = emptyList(),
    val dryRunLines: List<String> = emptyList(),
    val backupLines: List<String> = emptyList(),
    val busy: Boolean = false,
)

/**
 * WebUI 自检（内部验证；入口仅设置页，普通路径不出现）。
 * 后端：A=LiteWebAdminController（Ultra daemon）；B=KSU WebAdminCli（内核守护）。
 * 未就绪接口一律显示 `BACKEND_UNAVAILABLE`（fail-closed）。
 */
class WebUiSelfCheckViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(WebUiSelfCheckUi())
    val uiState: StateFlow<WebUiSelfCheckUi> = _uiState.asStateFlow()

    init {
        refreshBasics()
    }

    private fun refreshBasics() {
        viewModelScope.launch(Dispatchers.IO) {
            val mode = when (dev.maplesu.manager.workmode.ModeRuntime.active) {
                dev.maplesu.manager.workmode.WorkMode.LITE -> "Ultra 环境"
                null -> "未判定"
            }
            val backend = runCatching { LiteHiddenRuntimeProxy.rootBackendLabel() }.getOrDefault("UNKNOWN")
            val link = runCatching { LiteWebAdminController(ksuApp).url() }.getOrNull().orEmpty()
            _uiState.update {
                it.copy(
                    modeLabel = mode,
                    rootBackendLabel = backend,
                    webUiLink = link,
                )
            }
        }
    }

    /** 启动 WebUI（A=Ultra daemon；B=KSU 守护 webadmin on），结果显示 ok/fail+原因。 */
    fun startWebUi() {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val controller = LiteWebAdminController(ksuApp)
                    val info = controller.preparePreviewUrl()
                    val status = controller.start()
                    if (status.running) {
                        "ok port=${status.port ?: info?.port}"
                    } else {
                        "fail: " + (status.error.ifBlank { "START_FAILED" })
                    }
                }.getOrDefault("fail: BACKEND_UNAVAILABLE")
            }
            val link = withContext(Dispatchers.IO) {
                runCatching { LiteWebAdminController(ksuApp).url().orEmpty() }.getOrDefault("")
            }
            _uiState.update {
                it.copy(busy = false, startResult = result, webUiLink = if (result.startsWith("ok")) link else "")
            }
        }
    }

    fun copyLink() {
        val link = _uiState.value.webUiLink
        if (link.isBlank()) {
            Toast.makeText(ksuApp, R.string.webui_selfcheck_no_link, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val cm = ksuApp.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("MapleSU", link))
        }.onSuccess {
            Toast.makeText(ksuApp, R.string.webui_selfcheck_link_copied, Toast.LENGTH_SHORT).show()
        }
    }

    /** 只读状态：mode/rootBackend/daemon/端口/备份/bootPersistence/隐藏状态。 */
    fun checkStatus() {
        viewModelScope.launch {
            val lines = withContext(Dispatchers.IO) {
                buildList {
                    add("mode=${modeWire()}")
                    add("rootBackend=${_uiState.value.rootBackendLabel}")
                    val status = runCatching { LiteWebAdminController(ksuApp).status() }.getOrNull()
                    add("daemonRunning=${status?.running ?: "UNKNOWN"}")
                    add("port=${status?.port ?: "UNKNOWN"}")
                    add("hidden=${runCatching { com.maplesu.core.ui.security.Stealth.isEnabled() }.getOrDefault(false)}")
                    add("backup=MODE_BACKEND_UNAVAILABLE")
                }
            }
            _uiState.update { it.copy(statusLines = lines) }
        }
    }

    /** 重启证据（只读；后端未接线显示原因）。 */
    fun loadBootEvidence() {
        _uiState.update {
            it.copy(
                bootEvidenceLines = listOf(
                    "bootEvidence=MODE_BACKEND_UNAVAILABLE",
                    "detail=未接线（wm-core 提供后显示 bootId/persistent）",
                ),
            )
        }
    }

    /** 恢复演练 dry-run：仅返回计划步骤，不改变系统。 */
    fun dryRunRestore() {
        viewModelScope.launch {
            val lines = withContext(Dispatchers.IO) {
                runCatching {
                    dev.maplesu.manager.liteweb.LiteWebModeRestoreTransaction(
                        dev.maplesu.manager.liteweb.UnavailableHiddenModeBackend,
                    ).plan().map { "${it.name}:${if (it.ok) "ok" else "fail:${it.error}"}" }
                }.getOrDefault(listOf("restorePlan=DRY_RUN_UNAVAILABLE"))
            }
            _uiState.update {
                it.copy(dryRunLines = listOf("仅校验，不改变系统") + lines)
            }
        }
    }

    /** 生成/更新恢复备份（prepareBackup；未接线显示原因）。 */
    fun prepareBackup() {
        _uiState.update {
            it.copy(
                backupLines = listOf(
                    "prepareBackup=MODE_BACKEND_UNAVAILABLE",
                    "path=-",
                    "sha256=-",
                ),
            )
        }
    }

    private fun modeWire(): String = when (dev.maplesu.manager.workmode.ModeRuntime.active) {
        dev.maplesu.manager.workmode.WorkMode.LITE -> "LITE"
        null -> "UNRESOLVED"
    }
}
