package dev.maplesu.manager.runtime

import android.content.Context
import android.util.Log
import dev.maplesu.manager.runtime.authorization.DirectPolicy
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.workmode.superuser.ImplantPreparation
import dev.maplesu.manager.workmode.superuser.LiteImplantCodes
import dev.maplesu.manager.workmode.superuser.LiteImplantQuiesceGate
import dev.maplesu.manager.workmode.superuser.LiteImplantRecorderProvider
import java.io.File
import java.security.MessageDigest

/**
 * Delivers a self-admitting client image to **one** authorised application.
 *
 * <p>The image is written into that application's own native library directory, which
 * only that application and root can read, and it carries that application's credential
 * as a fixed trailer. A delivered image admits itself (`get_root`) and executes directly,
 * so an authorised application no longer needs the Manager UI, the directory owner, the
 * Binder endpoint or any resident root service.
 *
 * <p>Boundaries enforced here:
 *  - only an identity with an active grant is delivered to; everything else is refused
 *    before a single byte is written;
 *  - the Manager never delivers to itself;
 *  - the credential is written to a private temporary file that is deleted in the same
 *    root operation, and it never appears in argv, the environment or a log line;
 *  - [withdraw] removes the image, which is the whole of the revocation mechanism under
 *    the accepted soft-revocation contract (a copy already taken cannot be recalled).
 */
object SuDelivery {
    /** Trailer the client looks for: eight byte marker plus the 48 credential bytes. */
    private val MAGIC = "MPLSUKEY".toByteArray(Charsets.US_ASCII)
    private const val CREDENTIAL = 48
    private val TRAILER = MAGIC.size + CREDENTIAL

    data class Result(val ok: Boolean, val path: String? = null, val detail: String = "")

    /** Filesystem name inside the target's library directory. */
    private const val NAME = "su"

    fun deliveredPath(context: Context, packageName: String): String? = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        File(info.nativeLibraryDir, NAME).absolutePath
    }.getOrNull()

    /**
     * True when the delivered image is present.
     *
     * <p>This is the cheapest durable delivery signal: it needs no root, because the library
     * directory is traversable and only the image itself is unreadable to other identities.
     * It says nothing about wiring, and it never decides access - the grant does that.
     */
    fun imagePresent(context: Context, packageName: String): Boolean {
        val path = deliveredPath(context, packageName) ?: return false
        return File(path).isFile
    }

    fun deliver(context: Context, packageName: String): Result {
        val application = context.applicationContext
        // W19 写者静默：切换期间拒绝任何新投递（fail closed，不写目标 App）。
        LiteImplantQuiesceGate.blockedCode()?.let { code ->
            return Result(false, detail = code)
        }
        if (packageName == application.packageName) return Result(false, detail = "refusing to deliver to the manager itself")
        val uid = runCatching { application.packageManager.getApplicationInfo(packageName, 0).uid }.getOrNull()
            ?: return Result(false, detail = "unknown package")
        // Short-lived root tool: the grant check must not start (or keep) a resident
        // executor merely to answer one question.
        val enabled = DirectPolicy.isEnabled(application)
            ?: return Result(false, detail = "AUTH_STATE_UNAVAILABLE")
        if (!enabled) return Result(false, detail = "AUTH_DISABLED")
        if (DirectPolicy.hasGrantStrict(application, uid) != true) return Result(false, detail = "AUTH_NOT_GRANTED")

        val source = File(application.applicationInfo.nativeLibraryDir, "libmaplesu_su.so")
        if (!source.isFile) return Result(false, detail = "client image unavailable")
        val directory = runCatching {
            File(application.packageManager.getApplicationInfo(packageName, 0).nativeLibraryDir)
        }.getOrNull() ?: return Result(false, detail = "target library directory unknown")
        // An application with no native code has no library directory at all. It is still a
        // valid target: root creates the directory and the client then has somewhere to live,
        // which is what makes a bare `su` possible for web UIs and pure-Java tools.
        // Normalise every time, not only when creating: an earlier run may have left the
        // directory unreadable to the application, and a directory the application cannot
        // traverse makes its own image unreachable.
        run {
            val made = DirectRoot.run(application,
                // Directories stay traversable and root-owned, exactly like an installed
                // application's own library directory; only the image inside is private.
                "/system/bin/mkdir -p '${directory.absolutePath}/arm64' && " +
                "/system/bin/chown 0:0 '${directory.absolutePath}' '${directory.absolutePath}/arm64' && " +
                "/system/bin/chmod 0755 '${directory.absolutePath}' '${directory.absolutePath}/arm64' && " +
                "/system/bin/chcon u:object_r:apk_data_file:s0 '${directory.absolutePath}' '${directory.absolutePath}/arm64' && " +
                // The parent `lib` directory is created too; if it stays unreadable the
                // application cannot traverse into it and its own image is unreachable.
                "/system/bin/chmod 0755 '${File(directory.absolutePath).parent}' && " +
                "/system/bin/chcon u:object_r:apk_data_file:s0 '${File(directory.absolutePath).parent}'")
            if (made.code != 0) return Result(false, detail = "target library directory unavailable")
        }
        val target = File(directory, NAME).absolutePath

        val credential = runCatching { MapleSuCredentialStore(application).load() }.getOrNull()
            ?: return Result(false, detail = "no paired credential")
        // W19：**修改前**备份原件并登记（fail closed：备份失败则一个字节都不写）。
        val preparation = prepareImplantRecord(packageName, uid, target, source, credential, directory)
        if (preparation != null && !preparation.ok) {
            credential.fill(0)
            return Result(false, detail = preparation.code)
        }
        // Build the delivered image privately, then let the root operation install it and
        // delete the staging copy. This keeps the credential out of every command line.
        val staging = File(application.cacheDir, "deliver-${System.nanoTime()}.img")
        try {
            val expectedSize = source.length() + TRAILER
            staging.outputStream().use { output ->
                source.inputStream().use { it.copyTo(output) }
                output.write(MAGIC)
                output.write(credential)
                output.flush()
            }
            if (staging.length() != expectedSize) return Result(false, detail = "staging image incomplete")
            staging.setReadable(false, false)
            staging.setReadable(true, true)
            staging.setWritable(false, false)
            staging.setWritable(true, true)

            val script = """
                set -eu
                /system/bin/mkdir -p ${quote(directory.absolutePath)}
                /system/bin/chmod 0755 ${quote(directory.absolutePath)}
                /system/bin/chcon u:object_r:apk_data_file:s0 ${quote(directory.absolutePath)}
                /system/bin/cp -f ${quote(staging.absolutePath)} ${quote(target)}
                # Owned by the target application and unreadable to every other
                # application: the library directory itself is traversable, so the mode
                # is what keeps the delivered credential private to its owner.
                /system/bin/chown $uid:$uid ${quote(target)}
                /system/bin/chmod 0700 ${quote(target)}
                /system/bin/chcon u:object_r:apk_data_file:s0 ${quote(target)}
                test -x ${quote(target)}
                /system/bin/rm -f ${quote(staging.absolutePath)}
                /system/bin/printf 'DELIVERED:%s\n' ${quote(target)}
            """.trimIndent()
            val outcome = runRoot(application, script)
            if (outcome.first != 0) {
                staging.delete()
                return Result(false, detail = "delivery failed (${outcome.first}): ${outcome.second.take(160)}")
            }
            Log.i("MapleSU", "SU_CLIENT_DELIVERED package=$packageName uid=$uid")
            if (preparation != null) {
                runCatching { LiteImplantRecorderProvider.current()?.afterDelivery(preparation) }
            }
            return Result(true, target, "delivered")
        } finally {
            credential.fill(0)
            staging.delete()
        }
    }

    /** Removes the delivered image; the whole revocation mechanism under the contract. */
    fun withdraw(context: Context, packageName: String): Result {
        val application = context.applicationContext
        val path = deliveredPath(application, packageName) ?: return Result(false, detail = "unknown package")
        val script = """
            set -eu
            /system/bin/rm -f ${quote(path)}
            if [ -e ${quote(path)} ]; then exit 3; fi
            /system/bin/printf 'WITHDRAWN\n'
        """.trimIndent()
        val outcome = runRoot(application, script)
        return if (outcome.first == 0) Result(true, null, "withdrawn")
        else Result(false, detail = "withdraw failed (${outcome.first}): ${outcome.second.take(160)}")
    }

    /** Digest of the delivered image, for records that must not contain the credential. */
    fun deliveredDigest(context: Context, packageName: String): String? {
        val path = deliveredPath(context, packageName) ?: return null
        val bytes = runCatching { File(path).readBytes() }.getOrNull() ?: return null
        if (bytes.size <= TRAILER) return null
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes.copyOfRange(0, bytes.size - TRAILER))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * W19：投递前备份 `nativeLibraryDir/su`（若已存在）并登记未激活条目。
     * 未安装 recorder（旧行为）→ null；安装后任何失败 → 调用方必须放弃写入。
     */
    private fun prepareImplantRecord(
        packageName: String,
        uid: Int,
        target: String,
        source: File,
        credential: ByteArray,
        directory: File,
    ): ImplantPreparation? {
        val recorder = LiteImplantRecorderProvider.current() ?: return null
        val sha = deliveredImageSha(source, credential)
            ?: return ImplantPreparation.failed(LiteImplantCodes.BACKUP_FAILED, "image digest")
        val createdDirs = buildList {
            if (!directory.exists()) add(directory.absolutePath)
            val arm64 = File(directory, "arm64")
            if (!arm64.exists()) add(arm64.absolutePath)
        }
        return runCatching {
            recorder.beforeDelivery(packageName, uid, target, sha, createdDirs, System.currentTimeMillis())
        }.getOrElse { ImplantPreparation.failed(LiteImplantCodes.BACKUP_FAILED, it.javaClass.simpleName) }
    }

    /** sha256(source + MAGIC + credential)：与真正写进目标的字节完全一致，且不落盘凭据。 */
    private fun deliveredImageSha(source: File, credential: ByteArray): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.update(MAGIC)
        digest.update(credential)
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }.getOrNull()

    /**
     * Every delivery step runs as one short-lived direct-admission process: no root
     * service, no Binder connection and nothing left behind between operations.
     */
    private fun runRoot(context: Context, script: String): Pair<Int, String> {
        val result = DirectRoot.run(context, script)
        return result.code to result.output
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

/**
 * How far one identity actually got, for display; it never decides access.
 *
 * <p>The three layers are separate facts and the row must not merge them:
 *  1. a grant (authorization),
 *  2. the delivered image inside the target's library directory,
 *  3. PATH wiring, which only exists inside a live process and therefore cannot be
 *     completed while the target is closed.
 *
 * <p>[WIRED] is never inferred from "some `lib*.so` exists": every application with native
 * code has those, so that reading reported every delivered application as wired. It needs the
 * one signal that means the application can actually resolve a bare `su` right now - the
 * published directory inside the live process environment ([SuAutoWire.probeWiring]'s
 * `pathWired`). A root-owned side library is *not* that signal: it only proves an implant
 * happened at some point (MT keeps one while its PATH never changed), which is why
 * [SuAutoWire.implanted] - the OR that [SuAutoWire.reconcile] uses to avoid implanting twice -
 * must not be reused here.
 */
enum class State {
    /** No grant: nothing was delivered to this identity, and nothing will be. */
    NOT_AUTHORIZED,

    /**
     * Granted, image in place, target running, and the live PATH does not contain the
     * published directory yet: the copy exists and nothing is stale - wiring just is not in
     * effect yet. The row claims the copy, not a working `su`.
     */
    DELIVERED,

    /** A Direct grant exists, but delivery or PATH wiring has not been confirmed. */
    PENDING_WIRING,

    /** The authority was saved but its backend has not applied the revision. */
    AUTHORIZATION_PENDING,

    /** The Hook authority exists but the traditional su entry is not ready. */
    ENTRY_UNAVAILABLE,

    /** Granted, delivered and PATH-wired: a bare `su` resolves inside the application. */
    WIRED,

    /**
     * A grant record exists but it belongs to a different installation of the package: the
     * application was updated, reinstalled or re-signed. Authorization is bound to the
     * installed identity, so the old record no longer admits it and nothing is delivered.
     * Reporting "not authorized" would hide why it stopped working; the remedy is to
     * authorize the new installation again.
     */
    STALE,
}

/** Reports saved authority and publication separately; it never grants or deploys. */
internal fun managerSuState(granted: Boolean, stale: Boolean, pending: Boolean, entryReady: Boolean): State = when {
    stale -> State.STALE
    !granted -> State.NOT_AUTHORIZED
    pending -> State.AUTHORIZATION_PENDING
    !entryReady -> State.ENTRY_UNAVAILABLE
    else -> State.WIRED
}

/**
 * What one row should show.
 *
 * @param granted the grant bit the caller already knows (the profile snapshot the row was
 *   built from). Passed in so a screen never has to read policy per row.
 */
fun stateFor(
    context: Context,
    packageName: String,
    granted: Boolean,
    stale: Boolean = false,
): State = statesFor(context, mapOf(packageName to granted), stale = setOfNotNull(packageName.takeIf { stale }))[packageName]
    ?: State.NOT_AUTHORIZED

/**
 * The display state of a whole screen's rows.
 *
 * <p>[identities] maps a package name to the grant bit its row already carries. Nothing here
 * grants or decides anything: it describes what is on disk.
 *
 * <p>Cost is why this exists as a batch. The delivery check is one `stat` per granted row,
 * but the wiring signal needs root, and a root process per row is exactly what made the
 * superuser list take tens of seconds. Every granted row on the screen is therefore answered
 * by one [SuAutoWire.probeWiring] call; call it off the main thread.
 */
fun statesFor(
    context: Context,
    identities: Map<String, Boolean>,
    stale: Set<String> = emptySet(),
): Map<String, State> {
    if (identities.isEmpty()) return emptyMap()
    // 🟢 Ultra 环境（2026-10-29）：授权真相是 MapleSU SDK 的 su_auth 白名单，内核在应用请求
    //    root 时直接放行。这里**没有** su 客户端要投递、也没有 PATH 要注入，所以既不存在
    //    「已投递」也不存在「待运行时接线」—— 在白名单就是 wired，否则就是未授权。
    //    下面的 `SuAutoWire.probeWiring` / `SuDelivery.imagePresent` 都会起 root 进程，
    //    在 Ultra 内核上会重启系统，必须在最前面分流。
    if (UltraEnvironmentProbe.usesUltraBackend(context)) {
        val entryReady = !dev.maplesu.manager.runtime.ultra.UltraAuthorization.isDeliveryIncomplete()
        return identities.mapValues { (_, isGranted) ->
            managerSuState(isGranted, stale = false, pending = false, entryReady = entryReady)
        }
    }
    val application = context.applicationContext
    val granted = identities.filterValues { it }.keys
    // Nothing is delivered to an identity without a grant, so asking root about those rows
    // would only spend a process to learn that the answer is "nothing there".
    if (granted.isEmpty()) return identities.keys.associateWith { State.NOT_AUTHORIZED }
    val probes = SuAutoWire.probeWiring(application, granted)
    return identities.mapValues { (packageName, isGranted) ->
        val probe = probes?.get(packageName)
        when {
            // A record that no longer matches the installed package: say so, because the
            // application stopped working for a reason the user can act on.
            packageName in stale -> State.STALE
            !isGranted -> State.NOT_AUTHORIZED
            // Delivery follows the grant, so a grant without an image is a delivery that has
            // not happened yet (or failed). The row must not claim a copy it cannot see.
            !SuDelivery.imagePresent(application, packageName) -> State.PENDING_WIRING
            // Without a probe there is no wiring evidence at all; reporting "wired" then
            // would be a guess, and guessing is the defect this state machine replaces.
            probe == null -> State.PENDING_WIRING
            // PATH only. A side library proves an implant happened, not that this application
            // resolves `su` (MT keeps one with an untouched PATH); claiming "wired" there is
            // the lie this item exists to remove. The OR belongs to reconcile's re-implant
            // guard, not to what the user is told.
            probe.pathWired -> State.WIRED
            // The PATH signal exists only inside a live process, so a closed target is pending:
            // it has to run once before wiring can take effect.
            !probe.running -> State.PENDING_WIRING
            // An implant is on disk but the environment never changed - MT's state after an
            // earlier implant. Something is wired on paper and nothing works: pending, and
            // re-wiring is the action, not "look at the copy".
            probe.sideLibrary -> State.PENDING_WIRING
            // Running, nothing stale, delivery in place; the live PATH just does not show the
            // published directory (a wiring attempt that has not run yet or did not take).
            else -> State.DELIVERED
        }
    }
}
