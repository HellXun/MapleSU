package dev.maplesu.manager.recovery

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.maplesu.core.R
import com.maplesu.core.ui.screen.host.HiddenFlowDialog
import com.maplesu.core.ui.screen.host.HiddenFlowText
import com.maplesu.core.ui.screen.host.hiddenFlowColors
import com.maplesu.core.ui.security.HiddenStartupConfirm
import com.maplesu.core.ui.security.LiteHiddenController
import com.maplesu.core.ui.security.LiteHiddenRuntime
import dev.maplesu.manager.litehidden.LiteHiddenState
import dev.maplesu.manager.litehidden.UltraEnvRootRunner
import dev.maplesu.manager.liteweb.daemon.LiteWebDaemonConfig
import dev.maplesu.manager.runtime.ultra.UltraSdkCredentials
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/** The daemon stays independent until a foreground manager and the user both acknowledge it. */
class ManagerRestoreConfirmation(private val activity: ComponentActivity) {
    private var job: Job? = null
    private var observationJob: Job? = null
    private var pending by mutableStateOf<Pending?>(null)
    private var confirming by mutableStateOf(false)
    private var failureCode by mutableStateOf<String?>(null)
    private var restoreRevision by mutableLongStateOf(0L)
    private data class Pending(val nonce: String, val config: LiteWebDaemonConfig)
    private data class ConfirmationResult(val ok: Boolean, val code: String = "", val nextPending: Pending? = null)

    /** Must be hosted inside KernelSUTheme so both skins use the same application dialog. */
    @Composable
    fun Content(onRestoreObserved: () -> Unit) {
        LaunchedEffect(restoreRevision) {
            if (restoreRevision > 0) onRestoreObserved()
        }
        val restored = pending ?: return
        HiddenFlowDialog(
            title = stringResource(R.string.manager_restore_confirm_title),
            onDismiss = { if (!confirming) pending = null },
            confirmLabel = stringResource(if (confirming) R.string.manager_restore_confirm_progress
                else R.string.manager_restore_confirm_done),
            onConfirm = { confirm(restored) },
            dismissLabel = stringResource(R.string.manager_restore_confirm_later),
            confirmEnabled = !confirming,
            dismissEnabled = !confirming,
        ) {
            HiddenFlowText(stringResource(R.string.manager_restore_confirm_message))
            failureCode?.let { code ->
                HiddenFlowText(stringResource(R.string.manager_restore_confirm_retry_code, code),
                    color = hiddenFlowColors().error)
            }
        }
    }

    fun check(restart: Boolean = false) {
        if (restart && !confirming && pending == null) {
            observationJob?.cancel()
            observationJob = null
        }
        if (observationJob?.isActive == true || false) return
        observationJob = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (pending != null || confirming) return@repeatOnLifecycle
                // Identity recovery runs asynchronously on startup. Missing keys are
                // retriable observations, and each foreground entry gets a fresh attempt.
                val restored = readRestoreOnceReady(attempts = 30, load = { load() })
                    ?: return@repeatOnLifecycle
                if (activity.isFinishing || activity.isDestroyed) return@repeatOnLifecycle
                observeRestore()
                failureCode = null
                pending = restored
            }
        }
    }

    private suspend fun observeRestore() {
        withContext(Dispatchers.IO) {
            // A late hidden-state read must finish before publishing the verified OFF state.
            synchronized(LiteHiddenController) {
                LiteHiddenRuntime.update(LiteHiddenState.OFF)
                HiddenStartupConfirm.setPending(activity, false)
            }
        }
        restoreRevision++
    }

    private fun root() = UltraEnvRootRunner(UltraSdkCredentials.client(activity))

    private fun load(): Result<Pending?> = runCatching {
        if (false) return@runCatching null
        check(UltraSdkCredentials.read(activity) != null) { "RESTORE_KEY_NOT_READY" }
        // The SDK root read itself proves readiness; a separate cached probe may be stale/busy.
        val result = root().run(
            "[ \"\$(id -u)\" = 0 ] && " +
                "if [ ! -e '${ManagerRestoreHandover.PATH}' ]; then printf 'MAPLESU_RESTORE_NONE\\n'; " +
                "else hidden=\$(cat '${RecoveryPaths.HIDDEN_STATE}' 2>/dev/null) || exit 1; " +
                "case \"\$hidden\" in 0) cat '${ManagerRestoreHandover.PATH}' && printf '\\n' && " +
                "cat '${RecoveryPaths.WEBUI_CONFIG}';; *) exit 1;; esac; fi",
        )
        check(result.code == 0) { "RESTORE_STATE_UNAVAILABLE" }
        val lines = result.output.lineSequence().filter(String::isNotBlank).toList()
        if (lines.firstOrNull() == "MAPLESU_RESTORE_NONE") return@runCatching null
        val ticket = checkNotNull(ManagerRestoreHandover.LINE.matchEntire(lines.firstOrNull().orEmpty())) {
            "RESTORE_TICKET_INVALID"
        }
        if (ticket.groupValues[1] != "pending") return@runCatching null
        val config = checkNotNull(LiteWebDaemonConfig.parse(lines.drop(1).joinToString("\n"))) { "RESTORE_CONFIG_INVALID" }
        if (config.workMode != "LITE") return@runCatching null
        Pending(ticket.groupValues[2], config)
    }

    private fun confirm(restored: Pending) {
        if (confirming || pending != restored || job?.isActive == true) return
        confirming = true
        failureCode = null
        job = activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val response = runCatching {
                        val current = readRestoreOnceReady(load = { load() })
                        if (current == null || current.nonce != restored.nonce) {
                            ConfirmationResult(false, "RESTORE_SESSION_CHANGED", nextPending = current)
                        } else {
                            postConfirmation(current)
                        }
                    }.getOrElse {
                        if (it is CancellationException) throw it
                        ConfirmationResult(false, "RESTORE_REQUEST_FAILED")
                    }
                    // The confirmation may have committed just before its HTTP reply was lost.
                    if (!response.ok && confirmed(restored)) ConfirmationResult(true) else response
                }
                if (result.ok) {
                    observeRestore()
                    pending = null
                    Toast.makeText(activity, R.string.manager_restore_confirm_success, Toast.LENGTH_LONG).show()
                } else {
                    // Keep the same themed dialog open; neither dismissal nor failure closes the web service.
                    result.nextPending?.let { pending = it }
                    failureCode = result.code
                }
            } finally {
                confirming = false
            }
        }
    }

    private fun postConfirmation(restored: Pending): ConfirmationResult {
        val config = restored.config
        val url = "http://127.0.0.1:${config.port}/x7k9f/${config.token}/api/manager/confirm"
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(JSONObject().put("nonce", restored.nonce).toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = JSONObject(body)
            val ok = status == 200 && json.optBoolean("ok", false)
            val code = json.optString("code").takeIf { it.matches(Regex("^[A-Z][A-Z0-9_]{0,79}$")) }
            ConfirmationResult(ok, if (ok) "" else code ?: "RESTORE_CONFIRM_FAILED")
        } finally {
            connection.disconnect()
        }
    }

    private fun confirmed(restored: Pending): Boolean = runCatching {
        val saved = root().run(
            "[ \"\$(id -u)\" = 0 ] && [ \"\$(cat '${RecoveryPaths.HIDDEN_STATE}')\" = 0 ] && " +
                "cat '${ManagerRestoreHandover.PATH}'",
        )
        saved.code == 0 && saved.output.trim() == "confirmed:${restored.nonce}"
    }.getOrDefault(false)
}

