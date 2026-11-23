package dev.maplesu.manager.runtime

import android.content.Context
import android.util.Log
import dev.maplesu.manager.runtime.authorization.AndroidApplicationIdentities
import dev.maplesu.manager.runtime.authorization.DirectPolicy
import java.io.File

/**
 * Reconciles PATH wiring for applications that were granted but not wired.
 *
 * <p>Plan A wires at authorization time, which only works when the target process is
 * already running. Granting a tool such as a terminal while it is closed leaves it
 * "delivered but not wired", and a bare `su` inside it then fails even though the grant is
 * valid. This pass runs on the Manager's own startup (one short-lived root process per
 * candidate, and only for candidates that are currently running) and completes the wiring.
 *
 * <p>It never grants anything: an application without a current grant is skipped, and the
 * decision still comes from [DirectPolicy.hasGrantStrict] at operation time.
 */
object SuAutoWire {
    /** The probe separates its fields with single spaces; shell diagnostics share the stream. */
    private val WHITESPACE = Regex("\\s+")

    fun reconcile(context: Context) {
        val application = context.applicationContext
        runCatching {
            val policy = DirectPolicy
            if (policy.isEnabled(application) != true) return
            val granted = grantedUids(application) ?: return
            // MapleSU shape: one shared client plus the uid list that authorizes callers.
            if (granted.isEmpty()) SuPublication.withdraw(application) else SuPublication.publish(application)
            // Resolve every granted identity first, then ask root once for the whole set.
            // Asking per application cost two short root processes each on every Manager
            // start, which is exactly the kind of hidden cost this pass exists to remove.
            val packages = granted.mapNotNull { uid -> packageOf(application, uid) }
                .filter { it != application.packageName }
            val probes = if (packages.isEmpty()) null else probeWiring(application, packages)
            if (packages.isNotEmpty()) tightenAll(application, packages)
            var wired = 0
            for (uid in granted) {
                val packageName = packageOf(application, uid) ?: continue
                if (packageName == application.packageName) continue
                // A grant can exist without an image: it was made before delivery existed,
                // or a previous run withdrew it. Heal it here instead of waiting for the
                // user to revoke and grant again.
                // A stale image is worse than a missing one: the application keeps using an
                // old client (older protocol, no terminal relay) while its grant looks fine.
                if (!SuDelivery.imagePresent(application, packageName) || imageIsStale(application, packageName)) {
                    val delivered = SuDelivery.deliver(application, packageName)
                    Log.i("MapleSU", "SU_AUTO_WIRE deliver package=$packageName ok=${delivered.ok} ${delivered.detail}")
                    // An application with no library directory can never hold a delivered
                    // image, and it is exactly the population PATH injection serves, so a
                    // failed delivery must not skip it. Anything with libraries still stops
                    // here: without the image it cannot use the grant.
                    if (!delivered.ok && !SuPathWiring.hasNoNativeLibraries(application, packageName)) continue
                }
                // The OR below is this pass's own guard, not a definition of "wired".
                // A second implant stacks another side library onto the target (MT grew to
                // 13 MB and a target was corrupted that way), so anything that may already
                // have been implanted must be left alone even when its PATH never changed.
                // The UI answers a different question - "can this app resolve su right now?"
                // - and must not reuse this predicate; see [probeWiring] and SuDelivery.State.
                // The same answer the batched probe already returned: a side library means an
                // implant already happened (leave it alone), and PATH signal needs a live
                // process. Reading both from one probe keeps the cold-start cost constant.
                val probe = probes?.get(packageName)
                if (probe == null || probe.sideLibrary || probe.pathWired) continue
                if (!probe.running) continue
                val result = SuPathWiring.wire(application, packageName)
                if (result.ok) {
                    wired++
                } else if (result.reason == SuPathWiring.Reason.NO_CANDIDATES ||
                    result.reason == SuPathWiring.Reason.NO_PATCHABLE ||
                    SuPathWiring.hasNoNativeLibraries(application, packageName)) {
                    // No native library at all (or only our own root-owned side libraries):
                    // the only mechanism left is a PATH injection into the running process,
                    // which is what upstream does for a WebUI. The decision is on the
                    // machine-readable reason: the human text here used to be compared
                    // against "no patchable JNI library", which a library-free application
                    // never produces, so this fallback never ran.
                    val injected = SuPathWiring.injectPath(application, packageName)
                    if (injected.ok) wired++
                    else Log.i("MapleSU", "SU_AUTO_WIRE package=$packageName mechanism=inject " +
                        "ok=false detail=${injected.detail}")
                }
            }
            if (wired > 0) Log.i("MapleSU", "SU_AUTO_WIRE wired=$wired")
        }.onFailure {
            Log.w("MapleSU", "SU_AUTO_WIRE_FAILED ${it.javaClass.simpleName}")
        }
    }


    /**
     * The upstream implant installs its side library world-writable and root-owned.
     * Nothing writes it afterwards, so existing installations are tightened here; newly
     * wired ones are tightened by [SuPathWiring] right after the implant.
     */
    private fun tightenAll(context: Context, packageNames: Collection<String>) {
        val directories = packageNames.mapNotNull { packageName ->
            runCatching {
                File(context.packageManager.getApplicationInfo(packageName, 0).nativeLibraryDir)
            }.getOrNull()
        }
        if (directories.isEmpty()) return
        val targets = directories.flatMap { directory ->
            directory.listFiles()?.filter { it.name.startsWith("lib") }.orEmpty()
        }
        if (targets.isEmpty()) return
        DirectRoot.run(context, targets.joinToString(" ; ") {
            "test -e '${it.absolutePath}' && chmod 0755 '${it.absolutePath}'"
        })
    }


    private fun grantedUids(context: Context): Set<Int>? =
        DirectPolicy.grantedUids(context)

    private fun packageOf(context: Context, uid: Int): String? =
        AndroidApplicationIdentities(context).read(uid)?.packageName

    /**
     * Durable wiring evidence for one application.
     * <p>The two signals answer different questions and must not be merged here:
     * [pathWired] is the only one that means "this application can resolve a bare `su` right
     * now"; [sideLibrary] only proves that an implant happened at some point (it survives on
     * disk even when the patched library is never loaded, which is exactly MT's state). The
     * display therefore reads [pathWired], while [implanted] - which [reconcile] uses as its
     * "do not implant again" guard - deliberately ORs both.
     */
    internal data class WiringProbe(
        val running: Boolean,
        val sideLibrary: Boolean,
        val pathWired: Boolean,
    )

    /**
     * Reads both durable wiring signals for [packageNames] in **one** root process.
     *
     * <p>One process serves a whole screen: a per-row lookup is exactly what made the
     * superuser list take tens of seconds, so callers must not loop this function and must
     * not fall back to one process per application. This cannot run on the caller's thread
     * either: it starts a process.
     *
     * <p>`null` means the probe could not run at all. A caller must not turn that into
     * "not wired" evidence for anything it shows as authoritative; the display treats it as
     * [WiringProbe.pathWired] = false, i.e. it never claims wiring it did not observe.
     */
    internal fun probeWiring(context: Context, packageNames: Collection<String>): Map<String, WiringProbe>? {
        val application = context.applicationContext
        val targets = packageNames.distinct().map { packageName ->
            val directory = runCatching {
                File(application.packageManager.getApplicationInfo(packageName, 0).nativeLibraryDir)
            }.getOrNull()
            packageName to directory
        }
        if (targets.isEmpty()) return emptyMap()
        // The implant injects the shared publication directory, not the application's own
        // library directory: checking the latter could never succeed and made every Manager
        // start re-implant another side library. The trailing colon is part of PATH, so
        // matching it keeps a directory whose name merely starts the same way from counting.
        val shared = SuPublication.suDirectory(application) + ":"
        val script = buildString {
            append("PS=\"\$(/system/bin/ps -A -o PID,NAME 2>/dev/null)\"; ")
            targets.forEachIndexed { index, (packageName, directory) ->
                val pattern = packageName.replace(".", "\\.")
                append("P=\$(/system/bin/printf '%s\\n' \"\$PS\" | /system/bin/grep -E ' ").append(pattern)
                append("\$' | /system/bin/head -1 | /system/bin/awk '{print \$1}'); ")
                append("R=0; [ -n \"\$P\" ] && R=1; ")
                append("W=0; [ -n \"\$P\" ] && /system/bin/grep -a -q '").append(shared)
                append("' \"/proc/\$P/environ\" 2>/dev/null && W=1; ")
                append("S=0; ")
                if (directory != null) {
                    append("[ -n \"\$(/system/bin/find '").append(directory.absolutePath)
                    append("' -maxdepth 1 -name 'lib*' -user 0 2>/dev/null | /system/bin/head -1)\" ] && S=1; ")
                }
                append("/system/bin/echo \"MPPROBE").append(index).append(" \$R \$S \$W\"; ")
            }
        }
        val result = DirectRoot.run(application, script)
        if (result.code != 0) return null
        val probes = HashMap<String, WiringProbe>(targets.size)
        for (line in result.output.lineSequence()) {
            val fields = line.trim().split(WHITESPACE)
            if (fields.size != 4 || !fields[0].startsWith("MPPROBE")) continue
            val index = fields[0].removePrefix("MPPROBE").toIntOrNull() ?: continue
            val (packageName, _) = targets.getOrNull(index) ?: continue
            probes[packageName] = WiringProbe(
                running = fields[1] == "1",
                sideLibrary = fields[2] == "1",
                pathWired = fields[3] == "1",
            )
        }
        return probes
    }

    /**
     * True when the delivered image no longer matches the client this build ships.
     *
     * <p>The image is the client plus a 56-byte trailer, so a size mismatch means the
     * application is holding an older build - which is exactly how a granted application
     * ends up with root but without the newer terminal handling.
     */
    private fun imageIsStale(context: Context, packageName: String): Boolean {
        val image = SuDelivery.deliveredPath(context, packageName) ?: return false
        val shipped = File(context.applicationInfo.nativeLibraryDir, "libmaplesu_su.so")
        if (!shipped.isFile) return false
        val delivered = File(image)
        if (!delivered.isFile) return true
        // Size alone misses a rebuild that happens to produce the same length, and the
        // application then keeps running last build's client forever. An image older than
        // the shipped client is stale too.
        if (delivered.length() != shipped.length() + 56) return true
        // Timestamps inside an APK are the build time, so two rebuilds can compare equal.
        // Compare content instead: a different client must always replace the image.
        val head = minOf(65536L, delivered.length())
        return !samePrefix(delivered, shipped, head)
    }


    /** Compares the first [count] bytes of the delivered image and the shipped client. */
    private fun samePrefix(delivered: java.io.File, shipped: java.io.File, count: Long): Boolean {
        return runCatching {
            java.io.FileInputStream(delivered).use { left ->
                java.io.FileInputStream(shipped).use { right ->
                    val a = ByteArray(count.toInt())
                    val b = ByteArray(count.toInt())
                    left.read(a)
                    right.read(b)
                    a.contentEquals(b)
                }
            }
        }.getOrDefault(false)
    }

}
