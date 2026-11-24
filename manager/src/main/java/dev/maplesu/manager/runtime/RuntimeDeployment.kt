package dev.maplesu.manager.runtime

import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import android.system.Os
import android.util.Log
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentAutoSetup
import dev.maplesu.manager.runtime.ultra.UltraSdkSession
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Publishes the credential-free traditional client after unlock.
 *
 * ## Where the entry lives, and why not `/dev`
 *
 * Earlier revisions published `/dev/maplesu/bin/su` (first a bind mount, then a
 * symlink). A product-named directory in `/dev` is the single most obvious thing an
 * ordinary application can look for, so the entry now lives where the MapleSU Lite
 * baseline puts its own client: under `/data`, named by a derived token rather than
 * by the product.
 *
 *  - `/data` is mode 0771, so an ordinary app can traverse it but cannot list it.
 *    Without the name, the entry is not discoverable at all.
 *  - The name is 16 hex characters derived from SHA-256 of the installed credential
 *    (or a persisted random value when no credential is installed).
 *    It therefore looks like any other opaque directory and leaks no key characters -
 *    unlike the baseline, whose directory name is the first 16 characters of the key.
 *  - The directory is root-owned 0755 and carries the pre-existing `system_file` type
 *    so an ordinary app domain can traverse and execute. No policy is loaded and no
 *    new type, allow rule or attribute is added.
 *  - The entry itself is a **symbolic link** to `<apkd>/lib/<abi>/libmaplesu_su.so`,
 *    so `readlink /proc/self/exe` inside the client resolves to that image and the
 *    client derives the manager APK and bootstrap library from its own location. That
 *    is why there are no metadata files.
 *
 * Nothing about the client is copied or modified: the executable bytes are the
 * package manager's own file, byte-identical to the APK payload.
 *
 * ## Credential-backed publication
 *
 * The description below is the credential-backed path. Ultra Hook traditional
 * clients use [HookSuCompatibility]'s boot-scoped SDK UID redirect, without an overlay.
 * No mount, no overlay, no tmpfs entry is created by the credential-backed path. `standard_path` is permanently
 * `none`, and the publication script removes the artefacts of earlier revisions by
 * exact ownership test (an overlay whose source is `maplesu-su`, a bind mount whose
 * mount point is a known former client path, the former `/dev/maplesu` tree).
 */
object RuntimeDeployment {
    /** Every path an earlier revision could have published a client at. */
    private val LEGACY_CLIENT_PATHS = listOf(
        "/dev/maplesu/bin/su",
        "/system/xbin/su",
        "/product/bin/su",
        "/system_ext/bin/su",
    )

    /** Every path an earlier revision could have overlaid with a standard `su`. */
    private val LEGACY_STANDARD_PATHS = listOf("/product/bin", "/system/xbin", "/system_ext/bin", "/system/bin")

    private const val PREFERENCES = "runtime_deployment"
    private const val KEY_NAME = "directory_name"
    private const val KEY_LAST_NAME = "last_directory_name"

    data class Status(val clientPath: String? = null, val standardPath: String? = null,
        val brokerReady: Boolean = false, val error: String? = null)
    private val _status = MutableStateFlow(Status())
    val statusChanges: StateFlow<Status> = _status.asStateFlow()
    private var current: Status
        get() = _status.value
        set(value) { _status.value = value }
    private val requests = RuntimeDeploymentRequests()
    private val completions = ConcurrentLinkedQueue<() -> Unit>()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "maplesu-runtime-deploy").apply { isDaemon = true }
    }
    /** Separate from [worker]: a bounded readiness retry must not delay a real deploy. */
    private val retryExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "maplesu-runtime-ready").apply { isDaemon = true }
    }
    private val publicationRetryExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "maplesu-su-retry").apply { isDaemon = true }
    }
    private val publicationGeneration = AtomicLong(0)
    private val retrying = AtomicBoolean(false)
    private val retryDeadline = AtomicLong(0)
    private const val RETRY_WINDOW_MS = 60_000L
    private const val RETRY_INTERVAL_MS = 2_000L

    fun status(): Status = current

    /**
     * The published entry for this installation. The name is stable, so callers can
     * compute it without waiting for a deployment; the value is only usable once the
     * publisher created it.
     */
    fun clientPath(context: Context): String = "/data/${directoryName(context)}/su"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /**
     * 16 hex characters that name the published directory.
     *
     * <p>Derived from the installed credential so it stays stable across app data
     * clearing and reboots without any stored mapping, and so it reveals nothing about
     * the credential itself (one-way digest prefix). When no credential exists a
     * persisted random name is used instead - still opaque, still not product-derived.
     */
    private fun directoryName(context: Context): String {
        runCatching { MapleSuCredentialStore(context).load() }.getOrNull()?.let { credential ->
            try {
                val digest = MessageDigest.getInstance("SHA-256").digest(credential)
                return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            } finally {
                credential.fill(0)
            }
        }
        val stored = preferences(context).getString(KEY_NAME, null)
        if (stored != null && stored.length == 16 && stored.all { it in "0123456789abcdef" }) return stored
        val random = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val generated = random.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        preferences(context).edit().putString(KEY_NAME, generated).commit()
        return generated
    }

    fun start(context: Context, onReady: (() -> Unit)? = null) =
        start(context, onReady, RuntimePublicationRetry(publicationGeneration.incrementAndGet()), null)

    /** A verified foreground recovery resumes only publication for that same SDK session. */
    fun resumeBootSdk(context: Context) {
        val application = context.applicationContext
        val session = publicationSession(application) ?: return
        if (session.entryMode != SuEntryMode.Mode.TRADITIONAL_COMPAT) return
        start(application, null, RuntimePublicationRetry(publicationGeneration.incrementAndGet()), session)
    }

    private fun start(context: Context, onReady: (() -> Unit)?, retry: RuntimePublicationRetry,
        expectedSession: PublicationSession?) {
        val application = context.applicationContext
        if (!publicationCurrent(application, retry, expectedSession)) return
        if (application.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return
        // A current, verified runtime owns publication; Hook entries are boot-scoped.
        // A persisted success from another boot must never create a client.
        val runtime = TrustedRuntime.state(application)
        if (!runtime.ready) {
            if (expectedSession == null) retryWhenReady(application)
            return
        }
        if (onReady != null) completions.add(onReady)
        if (!requests.request()) return
        worker.execute {
            val generation = requests.snapshot()
            var brokerReady = false
            var ready = false
            val mode = SuEntryMode.read(application)
            val publishClient = mode == SuEntryMode.Mode.TRADITIONAL_COMPAT
            Log.i("MapleSU", "SU_ENTRY_MODE:${mode.wire} publishClient=$publishClient")
            try {
                // Ultra-only：发布走 SuAutoWire/SuPublication。
                if (!publicationCurrent(application, retry, expectedSession)) return@execute
                brokerReady = false
                current = current.copy(clientPath = null, brokerReady = false)
                ready = true
                // Completes PATH wiring for applications granted while they were closed.
                SuAutoWire.reconcile(application)
                current = current.copy(brokerReady = brokerReady,
                    error = if (ready) current.error else (current.error ?: "SU_CLIENT_NOT_PUBLISHED"))
                if (ready) {
                    while (true) {
                        val completion = completions.poll() ?: break
                        runCatching { completion() }
                    }
                }
            } catch (error: Exception) {
                Log.e("MapleSU", "Native su deployment failed", error)
                ready = false
                val reason = error.message?.substringBefore(':')?.takeIf {
                    it.startsWith("SU_") || it.startsWith("HOOK_") ||
                        it.startsWith("SDK_") || it.startsWith("BOOT_SDK_") ||
                        it.startsWith("MODULE_POLICY_LAYOUT_")
                } ?: "SU_DEPLOYMENT_FAILED"
                current = current.copy(brokerReady = brokerReady, error = reason)
                SuAuditLog.append(application, SuAuditEvent(backend = "runtime",
                    event = "entry", decision = "error", reason = reason))
            } finally {
                val superseded = expectedSession != null && retry.generation != publicationGeneration.get()
                if (!ready && !superseded) {
                    completions.clear()
                }
                val changed = requests.finish(generation)
                if (changed || superseded || completions.isNotEmpty()) start(application)
                else if (!ready && mode == SuEntryMode.Mode.TRADITIONAL_COMPAT) {
                    val session = publicationSession(application)
                    val latestGeneration = publicationGeneration.get()
                    val next = (if (expectedSession == null) RuntimePublicationRetry(latestGeneration) else retry)
                        .next(latestGeneration)
                    if (session != null && next != null && session.entryMode == mode &&
                        (expectedSession == null || expectedSession == session)) {
                        publicationRetryExecutor.schedule({ start(application, null, next, session) },
                            next.delayMillis, TimeUnit.MILLISECONDS)
                    }
                }
            }
        }
    }

    private data class PublicationSession(val bootId: String, val origin: TrustedRuntime.Origin,
        val workMode: WorkMode?, val entryMode: SuEntryMode.Mode, val sdkKey: String) {
        override fun toString() = "PublicationSession"
    }

    private fun publicationSession(context: Context): PublicationSession? {
        val sdk = UltraSdkSession.current(context) ?: return null
        val bootId = UltraEnvironmentAutoSetup.currentBootId() ?: return null
        val runtime = TrustedRuntime.state(context)
        if (!runtime.ready || sdk.bootId != bootId ||
            UltraEnvironmentAutoSetup.blockedReason(context, bootSession = true) != null) return null
        return PublicationSession(bootId, runtime.origin, ModeRuntime.active,
            SuEntryMode.read(context), sdk.key)
    }

    private fun publicationCurrent(context: Context, retry: RuntimePublicationRetry,
        expected: PublicationSession?): Boolean = expected == null ||
        (retry.generation == publicationGeneration.get() && publicationSession(context) == expected)

    /**
     * A boot or package-replace broadcast can arrive before the internal credential
     * probe has finished, and `start` is also what that probe calls when it succeeds.
     * If the process is torn down in between, nothing publishes at all. Retry on a
     * bounded window so recovery does not depend on that race.
     */
    private fun retryWhenReady(context: Context) {
        val now = SystemClock.elapsedRealtime()
        val existing = retryDeadline.get()
        if (existing == 0L) {
            retryDeadline.compareAndSet(0L, now + RETRY_WINDOW_MS)
        } else if (now > existing) {
            return
        }
        if (!retrying.compareAndSet(false, true)) return
        retryExecutor.execute {
            try {
                while (true) {
                    val deadline = retryDeadline.get()
                    if (deadline == 0L || SystemClock.elapsedRealtime() > deadline) return@execute
                    if (TrustedRuntime.state(context).ready) {
                        retryDeadline.set(0L)
                        start(context)
                        return@execute
                    }
                    SystemClock.sleep(RETRY_INTERVAL_MS)
                }
            } finally {
                retrying.set(false)
            }
        }
    }

    /**
     * True when the published entry does not already resolve to this installation's
     * client image. Cheap (`readlink` only) and safe to call on every start.
     */
    private fun needsDeploy(context: Context): Boolean {
        if (current.clientPath == null) return true
        val expected = File(context.applicationInfo.nativeLibraryDir, "libmaplesu_su.so").absolutePath
        val actual = runCatching { Os.readlink(clientPath(context)) }.getOrNull()
        return actual != expected
    }

    private data class ScriptRun(val code: Int, val out: List<String>, val err: String)

    /**
     * Runs the publication script through the transport that owns root for this
     * mode. The script is fixed text built here; neither branch accepts a caller
     * supplied command.
     */
    private fun runScript(context: Context, script: String, @Suppress("UNUSED_PARAMETER") origin: TrustedRuntime.Origin): ScriptRun {
        val result = MapleSuRuntime.openShell(context, globalMnt = true).use { shell ->
            shell.newJob().add(script).to(ArrayList<String>(), ArrayList<String>()).exec()
        }
        return ScriptRun(result.code, result.out, result.err.joinToString("\n"))
    }

    internal fun deploy(context: Context, origin: TrustedRuntime.Origin = TrustedRuntime.Origin.ULTRA): Status {
        val source = File(context.applicationInfo.nativeLibraryDir, "libmaplesu_su.so")
        check(source.isFile && source.canExecute()) { "SU_CLIENT_UNAVAILABLE" }
        check(File(context.applicationInfo.sourceDir).isFile) { "SU_CLIENT_APK_UNRESOLVED" }
        val name = directoryName(context)
        val base = "/data/$name"
        val previousName = preferences(context).getString(KEY_LAST_NAME, null)
        val previous = previousName?.takeIf { it != name && it.length == 16 }?.let { "/data/$it" }
        val standardCase = LEGACY_STANDARD_PATHS.joinToString("|")
        val script = """
            set -eu
            stage=legacy_standard_path
            # Remove every standard-path overlay an earlier revision created. The
            # ownership test is the exact pair (fstype=overlay, source=maplesu-su);
            # an unrelated overlay is never touched.
            while true; do
                line=${'$'}(/system/bin/grep -E " - overlay maplesu-su " /proc/self/mountinfo 2>/dev/null | /system/bin/head -n 1 || true)
                test -n "${'$'}line" || break
                mount_point=${'$'}(/system/bin/printf '%s' "${'$'}line" | /system/bin/cut -d' ' -f5)
                case "${'$'}mount_point" in
                    $standardCase) ;;
                    *) break ;;
                esac
                /system/bin/umount "${'$'}mount_point" || break
            done
            stage=legacy_dev_tree
            # The former /dev/maplesu tree is a product-named path; remove it. Only a
            # real (non-symlink) root-owned directory is considered.
            if [ -d /dev/maplesu ] && [ ! -L /dev/maplesu ]; then
                test "${'$'}(/system/bin/stat -c %u /dev/maplesu)" = 0
                /system/bin/rm -rf /dev/maplesu
            fi
            stage=legacy_client_path
            source=${quote(source.absolutePath)}
            for legacy in ${LEGACY_CLIENT_PATHS.joinToString(" ") { quote(it) }}; do
                while /system/bin/grep -qF " ${'$'}legacy " /proc/self/mountinfo 2>/dev/null; do
                    /system/bin/umount "${'$'}legacy" || break
                done
                if [ -L "${'$'}legacy" ] && [ "${'$'}(/system/bin/readlink "${'$'}legacy")" = "${'$'}source" ]; then
                    /system/bin/rm -f "${'$'}legacy"
                fi
            done
            stage=legacy_directory
            # A re-paired credential changes the derived name; remove the directory the
            # previous name published, but only when it is provably ours.
            previous=${quote(previous ?: "")}
            if [ -n "${'$'}previous" ] && [ "${'$'}previous" != ${quote(base)} ] && [ -d "${'$'}previous" ]; then
                if [ "${'$'}(/system/bin/stat -c %u "${'$'}previous")" = 0 ] &&
                   [ "${'$'}(/system/bin/readlink "${'$'}previous/su" 2>/dev/null)" = "${'$'}source" ]; then
                    /system/bin/rm -f "${'$'}previous/su"
                    /system/bin/rmdir "${'$'}previous" 2>/dev/null || true
                fi
            fi
            base=${quote(base)}
            test ! -L "${'$'}base"
            if [ -e "${'$'}base" ]; then test "${'$'}(/system/bin/stat -c %u "${'$'}base")" = 0; fi
            /system/bin/mkdir -p "${'$'}base"
            /system/bin/chown 0:0 "${'$'}base"
            /system/bin/chmod 0755 "${'$'}base"
            stage=client_link
            target="${'$'}base/su"
            while /system/bin/grep -qF " ${'$'}target " /proc/self/mountinfo 2>/dev/null; do
                /system/bin/umount "${'$'}target" || break
            done
            test -f "${'$'}source"
            test -x "${'$'}source"
            /system/bin/rm -f "${'$'}target"
            /system/bin/ln -s "${'$'}source" "${'$'}target"
            test -L "${'$'}target"
            test "${'$'}(/system/bin/readlink "${'$'}target")" = "${'$'}source"
            test -x "${'$'}target"
            # An ordinary app domain must be able to traverse into the directory. The
            # type is pre-existing; no policy is loaded and no new type or allow rule is
            # added. The link target keeps the package manager's own label.
            /system/bin/chcon u:object_r:system_file:s0 "${'$'}base"
            /system/bin/printf 'PUBLISHED:%s\n' "${'$'}target"
        """.trimIndent()
        val result = runScript(context, script, origin)
        check(result.code == 0) { "SU_PUBLISH_FAILED exit=${result.code}: ${result.err}" }
        val reported = result.out.lastOrNull { it.startsWith("PUBLISHED:") }
            ?.substringAfter(':') ?: throw IllegalStateException("SU_PUBLISH_NOT_CONFIRMED")
        // Read through the ordinary Manager namespace too; a root-only view is insufficient.
        val original = Os.stat(source.absolutePath)
        fun matches(path: String): Boolean = runCatching {
            val candidate = Os.stat(path)
            candidate.st_dev == original.st_dev && candidate.st_ino == original.st_ino && File(path).canExecute()
        }.getOrDefault(false)
        val clientPath = clientPath(context)
        check(matches(clientPath)) { "SU_PUBLIC_NAMESPACE_NOT_VISIBLE" }
        check(reported == clientPath) { "SU_PUBLISH_PATH_MISMATCH" }
        // Deployment identity is verified by the publish script itself: the link target
        // and every attribute write are read back under `set -eu`, so a reachable exit
        // code 0 already proves the link resolves to the library. No metadata file is
        // read here, because none exists.
        preferences(context).edit().putString(KEY_LAST_NAME, name).commit()
        val standard = null
        if (standard == null) Log.w("MapleSU", "Native su standard path not published: ${result.err}")
        return Status(clientPath, standard, error = "SU_STANDARD_PATH_NOT_PUBLISHED")
            .also { current = it }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

/** Three publication-only retries after 2, 4 and 8 seconds; a new request cancels the old chain. */
internal data class RuntimePublicationRetry(val generation: Long, val attempt: Int = 0) {
    val delayMillis: Long get() = 1_000L shl attempt

    fun next(currentGeneration: Long): RuntimePublicationRetry? =
        if (generation == currentGeneration && attempt < 3) copy(attempt = attempt + 1) else null
}

/** Coalesces a busy deploy without dropping a grant arriving after its state snapshot. */
internal class RuntimeDeploymentRequests {
    private val queued = AtomicBoolean(false)
    private val generation = AtomicLong(0)

    fun request(): Boolean {
        generation.incrementAndGet()
        return queued.compareAndSet(false, true)
    }

    fun snapshot(): Long = generation.get()

    fun finish(observed: Long): Boolean {
        queued.set(false)
        return generation.get() != observed
    }
}
