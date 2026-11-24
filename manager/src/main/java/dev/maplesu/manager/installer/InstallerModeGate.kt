package dev.maplesu.manager.installer

import java.io.File

/**
 * Mode gate for partition transactions, evaluated inside the trusted installer service.
 *
 * <p>The service runs as UID 0 and is launched by the Root shell that bound it, so its own ancestor
 * chain says which transport produced it: a normal-mode MapleSU shell, the KSU native channel, or
 * an untrusted transient. The App cannot choose its ancestors, so this is not a UI flag and
 * it cannot be cleared by deleting App data.
 *
 * <p>Product decision (2026-11-01, user directive): the <em>only</em> refusing origin is
 * [Origin.UNTRUSTED], and it is refused in both write scopes. An untrusted transient borrows
 * somebody else's exploit session, so it is never a licence for a partition write; the MapleSU
 * Lite runtime, the KSU native channel and an unreadable ancestor chain may all write. Both
 * scopes (`LITE_INSTALL`, `MODE_SWITCH`) share that one rule.
 *
 * <p>Deliberately not consulted here: bootloader/AVB lock state. On this device it is a
 * `resetprop` disguise, so using it as a gate would be a fabricated refusal. The only remaining
 * use of the in-service kernel proof is root-channel selection (`RootSourcePolicy` /
 * `InstallerRootSession`), never the write gate.
 *
 * <p>Pure classification ([classify]) and the refusal matrix ([refusal]) are host-testable; only
 * [origin] touches /proc.
 */
internal object InstallerModeGate {
    /** The single refusal code left. Kept stable: the UI maps it to a user-facing message. */
    const val REFUSED = "INSTALLER_UNTRUSTED_PARTITION_WRITE_REFUSED"
    private const val MAX_DEPTH = 12

    /** Partition-write scope. LITE_INSTALL is the pre-existing boot-install path. */
    enum class WriteScope { LITE_INSTALL, MODE_SWITCH }

    /** Which transport launched this service process. */
    enum class Origin { LITE, KSU, UNTRUSTED, UNKNOWN }

    private val UNTRUSTED_TRANSIENTS = listOf("libmaplesu_root_consumer.so", "libmaplesu_dirtyfrag_bootstrap.so")
    private val LITE = listOf("libmaplesu_runtime.so", "libmaplesu_su.so", "maplesu-runtime", "maplesu-su")
    // Classification hints only: their presence changes nothing about whether a write is allowed.
    private val KSU = listOf("ksud", "kernelsu", "libksu", "sucompat")

    /** Classifies a nearest-first ancestor command list; markers are never trusted alone. */
    fun classify(chain: List<String>): Origin {
        // An untrusted transient anywhere in the chain dominates: it is never a licence for a write.
        if (chain.any { command -> UNTRUSTED_TRANSIENTS.any { command.contains(it) } }) return Origin.UNTRUSTED
        for (command in chain) {
            if (KSU.any { command.contains(it) }) return Origin.KSU
            if (LITE.any { command.contains(it) }) return Origin.LITE
        }
        return Origin.UNKNOWN
    }

    /**
     * Pure refusal matrix; `null` means the write may proceed. Only an untrusted transient is
     * refused, identically in both scopes, so a user-initiated flash is never blocked by origin.
     *
     * `scope` is still a parameter so every call site keeps declaring which write it is about to
     * perform (and the AIDL legacy entry point keeps its scope); the policy itself is now
     * scope-independent, which is why nothing branches on it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun refusal(origin: Origin, scope: WriteScope): String? =
        if (origin == Origin.UNTRUSTED) REFUSED else null

    /** Resolves the transport that started this process, without trusting the caller. */
    fun origin(): Origin {
        var pid = android.os.Process.myPid().toLong()
        val chain = ArrayList<String>(MAX_DEPTH)
        repeat(MAX_DEPTH) {
            val (parent, command) = readStat(pid) ?: return classifyExhausted(chain)
            chain.add(command)
            if (classify(chain) != Origin.UNKNOWN) return classify(chain)
            if (parent <= 1) return Origin.UNKNOWN
            pid = parent
        }
        return Origin.UNKNOWN
    }

    /**
     * Refusal reason for a partition write in the requested scope, or null when it may proceed.
     * The in-service kernel proof is intentionally not read here: it selects the root channel, it
     * does not gate a flash.
     */
    fun writeRefusal(scope: WriteScope): String? = refusal(origin(), scope)

    /**
     * Backwards-compatible entry point for the pre-existing boot-install methods (AIDL:8-17):
     * they are `LITE_INSTALL` scope. Under the current policy that accepts every origin except
     * [Origin.UNTRUSTED], in either scope.
     */
    fun refusal(): String? = writeRefusal(WriteScope.LITE_INSTALL)

    private fun classifyExhausted(chain: List<String>): Origin =
        if (chain.isEmpty()) Origin.UNKNOWN else classify(chain)

    private fun readStat(pid: Long): Pair<Long, String>? {
        val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return null
        // The command name may contain spaces and parentheses: parse after the last ')'.
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        val fields = stat.substring(close + 1).trim().split(" ")
        val parent = fields.getOrNull(1)?.toLongOrNull() ?: return null
        val command = runCatching { File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8) }
            .getOrDefault("")
            .replace('\u0000', ' ')
        return parent to command
    }
}
