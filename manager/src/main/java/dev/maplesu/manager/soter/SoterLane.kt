package dev.maplesu.manager.soter

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SOTER lane — app side, bind half.
 *
 * Why the manager holds the binding: the module daemon is a bare `app_process`, and AMS
 * refuses its `bindService` ("Unable to find app for caller IApplicationThread ... uid0").
 * A real application identity can bind `com.tencent.soter.soterserver`; the daemon (root)
 * then takes that binder, performs the root-gated `0xDEADBEEF` backdoor handshake and
 * registers the module's existing 13-code interceptor. The forge itself stays entirely in
 * the module dex/native.
 *
 * Hand-off: the marshalled binder is written to `files/soter_target.bin` (0600) right
 * after `onServiceConnected` and deleted on unbind/disable; [SoterTargetProvider] stays
 * as the (root-only) fallback leg the daemon reaches through AMS without an app record.
 *
 * Toggle: the marker directory, then a state mirror the daemon writes into our files dir.
 * The module's own `get_soter_forge_toggle()` endpoint is read **only** after the user has
 * already opened the module's WebUI themselves ([onExplicitWebUiOpened]); this lane never
 * calls `start_module_web_ui_server_async` on its own. That host call is "open the module
 * WebUI", not a passive read: the module/SDK launches the page (verified on-device as a
 * uid-0 `am start -a VIEW http://127.0.0.1:<port>/`), so doing it from process start made
 * MapleSU jump to the module's WebUI on every launch. Off/unresolved = no bind.
 *
 * Everything is best-effort: no UI, no exceptions into the app, at most one debug line per
 * state change.
 */
object SoterLane {
    private const val TAG = "SoterLane"

    private const val SOTER_PACKAGE = "com.tencent.soter.soterserver"
    private const val SOTER_ACTION = "com.tencent.soter.soterserver.ISoterService"
    private const val MODULE_ID32 = "teesimulator601skpro202607210000"

    private const val TOGGLE_POLL_MS = 10_000L
    private const val RESOLVE_RETRY_MS = 30_000L
    private const val RESOLVE_RETRY_MAX_MS = 300_000L
    private const val HTTP_TIMEOUT_MS = 2_000

    /** Binder hand-off file: marshalled `writeStrongBinder(target)`, owner-only. */
    private const val TARGET_FILE = "soter_target.bin"
    private const val TARGET_FILE_MODE = 384 // 0600

    private val started = AtomicBoolean(false)
    private val resolving = AtomicBoolean(false)

    @Volatile private var appContext: Context? = null
    @Volatile private var webUiPort = -1
    @Volatile private var lastResolveAt = 0L
    @Volatile private var resolveRetryMs = RESOLVE_RETRY_MS
    @Volatile private var targetBinder: IBinder? = null

    private var handler: Handler? = null
    private var laneThread: HandlerThread? = null
    private val callbackExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "soter-lane-cb") }
    private val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "soter-lane-io") }
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "soter-lane-poll") }

    // State below is only touched on the lane handler thread.
    private var enabled = false
    private var bound = false
    private var connection: ServiceConnection? = null
    @Volatile private var loggedSource = false

    /** The current ISoterService binder, or null while unbound/not connected. */
    fun targetBinder(): IBinder? = targetBinder

    /** Idempotent; safe from any thread and from process start. */
    fun start(context: Context) {
        // Application.onCreate also runs in secondary processes; the lane may only own a
        // binding in the main process, which is also the provider's process.
        val processName = runCatching { android.app.Application.getProcessName() }.getOrNull()
        if (processName != null && processName != context.packageName) return
        if (!started.compareAndSet(false, true)) return
        appContext = context.applicationContext
        val thread = HandlerThread("soter-lane").also { it.start() }
        laneThread = thread
        handler = Handler(thread.looper)
        scheduleWatchdog()
        resolveOnce()
        scheduler.scheduleWithFixedDelay({ pollToggle() }, TOGGLE_POLL_MS, TOGGLE_POLL_MS, TimeUnit.MILLISECONDS)
        Log.d(TAG, "lane started")
    }

    // ---------------------------------------------------------------- toggle side

    private fun pollToggle() {
        runCatching {
            val port = webUiPort
            if (port > 0) {
                val value = httpToggle(port)
                if (value != null) handler?.post { apply(value) }
            } else if (SystemClock.elapsedRealtime() - lastResolveAt >= resolveRetryMs) {
                resolveOnce()
            }
        }
    }

    private fun resolveOnce() {
        if (!resolving.compareAndSet(false, true)) return
        lastResolveAt = SystemClock.elapsedRealtime()
        ioExecutor.execute {
            val value = try {
                resolveToggle()
            } catch (error: Throwable) {
                Log.d(TAG, "toggle unresolved: ${error.javaClass.simpleName}")
                null
            } finally {
                resolving.set(false)
            }
            if (value != null) {
                resolveRetryMs = RESOLVE_RETRY_MS
                handler?.post { apply(value) }
            } else {
                // Fail-closed: stay unbound, back off up to 5 minutes on a device where
                // the module control plane is unavailable.
                resolveRetryMs = (resolveRetryMs * 2).coerceAtMost(RESOLVE_RETRY_MAX_MS)
            }
        }
    }

    /**
     * 用户**显式**打开本 lane 所属模块的 WebUI 后，由启动器回传端口。
     *
     * 这是本进程唯一允许 lane 触碰模块 WebUI 的入口：只对已运行的服务做只读 POST，
     * 绝不调用 `start_module_web_ui_server_async`（那会由模块拉起页面）。
     * 端口只存在内存里，进程重启即失效；拿不到就按「开关未知 → 不 bind」处理。
     *
     * @return true 表示端口被接受（模块匹配且端口有效）。
     */
    fun onExplicitWebUiOpened(moduleId: String, port: Int): Boolean {
        if (moduleId != MODULE_ID32) return false
        if (port !in 1..65535) return false
        webUiPort = port
        loggedSource = false
        resolveOnce()
        return true
    }

    /** true = forge on, false = off, null = cannot tell (stay off, retry later). */
    private fun resolveToggle(): Boolean? {
        val context = appContext ?: return null
        val mode = ModeRuntime.active
        if (mode != null && mode != WorkMode.LITE) return null

        // 1) 用户自己已经打开过模块 WebUI，端口由 onExplicitWebUiOpened 提供。
        //    这里只读模块自己的 `get_soter_forge_toggle()`，不启动服务、不拉起页面。
        val port = webUiPort
        if (port > 0) {
            httpToggle(port)?.let { value ->
                if (!loggedSource) {
                    loggedSource = true
                    Log.d(TAG, "toggle via module webui port=$port value=$value")
                }
                return value
            }
        }

        // 2) Marker directory, only when this process can actually list it (root-only on
        //    the Ultra layout, so this normally fails and stays null).
        val names = runCatching { File("/data/adb/tricky_store").list() }.getOrNull()
        if (names != null) {
            if (!loggedSource) {
                loggedSource = true
                Log.d(TAG, "toggle via marker dir")
            }
            return !names.contains("soter_forge_disabled")
        }

        // 3) State mirror written by the module injector into our own files dir (no root).
        readStateFile(File(context.filesDir, "soter.state").absolutePath)?.let {
            if (!loggedSource) {
                loggedSource = true
                Log.d(TAG, "toggle via daemon state file value=$it")
            }
            return it
        }
        return null
    }

    private fun httpToggle(port: Int): Boolean? {
        val body = httpPost("http://127.0.0.1:$port/getSoterToggle") ?: return null
        return when (body.trim()) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }

    private fun httpPost(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                doOutput = true
                setFixedLengthStreamingMode(0)
            }
            connection.outputStream.use { }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun readStateFile(path: String): Boolean? = runCatching {
        val file = File(path)
        if (!file.isFile) return null
        when (file.readText().trim()) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }.getOrNull()

    // ---------------------------------------------------------------- binder side

    private fun apply(value: Boolean) {
        if (value == enabled) {
            if (value && !bound) bind()
            return
        }
        enabled = value
        Log.d(TAG, if (value) "enabled" else "disabled")
        if (value) bind() else teardown()
    }

    private fun bind() {
        val context = appContext ?: return
        if (bound || !enabled) return
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                handler?.post { onConnected(service) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                handler?.post { onLost() }
            }

            override fun onBindingDied(name: ComponentName?) {
                handler?.post { onLost() }
            }

            override fun onNullBinding(name: ComponentName?) {
                handler?.post { onLost() }
            }
        }
        connection = serviceConnection
        val intent = Intent(SOTER_ACTION).setPackage(SOTER_PACKAGE)
        bound = try {
            context.bindService(intent, Context.BIND_AUTO_CREATE, callbackExecutor, serviceConnection)
        } catch (error: Throwable) {
            Log.d(TAG, "bind failed: ${error.javaClass.simpleName}")
            false
        }
        if (!bound) {
            connection = null
            scheduleRebind()
        }
    }

    private fun onConnected(service: IBinder?) {
        if (!enabled) {
            teardown()
            return
        }
        if (service == null) {
            onLost()
            return
        }
        targetBinder = service
        Log.d(TAG, "bound")
        writeHandoff(service)
    }

    private fun onLost() {
        targetBinder = null
        bound = false
        removeHandoff()
        connection?.let { existing -> runCatching { appContext?.unbindService(existing) } }
        connection = null
        if (enabled) scheduleRebind()
    }

    private fun teardown() {
        targetBinder = null
        removeHandoff()
        connection?.let { existing -> runCatching { appContext?.unbindService(existing) } }
        connection = null
        bound = false
    }

    private var rebindDelay = 1_000L

    private fun scheduleRebind() {
        if (!enabled) return
        val delay = rebindDelay
        rebindDelay = (rebindDelay * 2).coerceAtMost(30_000L)
        handler?.postDelayed({
            if (enabled && !bound) bind()
        }, delay)
    }

    // ---------------------------------------------------------------- hand-off file

    /**
     * The requested "binder through a file" leg: marshal `writeStrongBinder(target)` and
     * drop the bytes in our private files dir (0600).
     *
     * Measured on Android 16: this can never produce bytes. `Parcel.marshall()` - and the
     * underlying nativeMarshall - refuse any parcel that contains binder objects with
     * "Tried to marshall a Parcel that contains objects (binders or FDs)". The binding is
     * still kept alive by [targetBinder] and handed to the daemon through
     * [SoterTargetProvider] (root-only, reached by AMS without an app record), which is
     * the leg that actually registers the 13 codes.
     */
    private fun writeHandoff(service: IBinder) {
        val context = appContext ?: return
        val path = File(context.filesDir, TARGET_FILE).absolutePath
        ioExecutor.execute {
            try {
                val parcel = Parcel.obtain()
                val bytes: ByteArray
                try {
                    parcel.writeStrongBinder(service)
                    bytes = parcel.marshall()
                } finally {
                    parcel.recycle()
                }
                writePrivate(path, bytes)
                Log.d(TAG, "handoff written ${bytes.size}B")
            } catch (error: Throwable) {
                // Expected on Android 16: see the KDoc above. Log the platform's own
                // reason instead of pretending the file leg might work.
                Log.d(TAG, "handoff not serialisable: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun writePrivate(path: String, bytes: ByteArray) {
        val fd = Os.open(
            path,
            OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC,
            TARGET_FILE_MODE,
        )
        try {
            var offset = 0
            while (offset < bytes.size) {
                val written = Os.write(fd, bytes, offset, bytes.size - offset)
                if (written <= 0) break
                offset += written
            }
            Os.fsync(fd)
        } finally {
            runCatching { Os.close(fd) }
        }
        runCatching { Os.chmod(path, TARGET_FILE_MODE) }
    }

    private fun removeHandoff() {
        val context = appContext ?: return
        ioExecutor.execute {
            runCatching { File(context.filesDir, TARGET_FILE).delete() }
        }
    }

    // ---------------------------------------------------------------- watchdog

    private fun scheduleWatchdog() {
        handler?.postDelayed({
            runCatching { watchdog() }
            scheduleWatchdog()
        }, 15_000L)
    }

    private fun watchdog() {
        if (!enabled) return
        if (!bound) {
            bind()
            return
        }
        val service = targetBinder
        if (service == null || !service.pingBinder()) onLost()
    }
}
