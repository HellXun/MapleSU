package dev.maplesu.manager.runtime.ultra

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ready/current-boot Hook must supersede old SDK pairing at every shared UI/router call site. */
class UltraHookIdentityRoutingTest {
    @Test fun readyHookIsCheckedBeforeHistoricalPairingWithoutInvokingAnExecutor() {
        val relative = "src/main/java/dev/maplesu/manager/runtime/ultra/UltraEnvironmentProbe.kt"
        val source = listOf(File(relative), File("manager/$relative")).first { it.isFile }.readText()
        val body = source.substringAfter("fun usesUltraBackend(context: Context): Boolean {")
            .substringBefore("/** Coalesced background probe")
        val normal = body.indexOf("ModeRuntime.active == WorkMode.LITE")
        val hook = body.indexOf("if (UltraSdkCredentials.hookWithoutSdk(context)) return false")
        val suppression = body.indexOf("UltraSdkCredentials.suspended(context)")
        val pairing = body.indexOf("UltraKeyPairedStore.matchesVerifiedKey(context, key)")
        assertTrue("Normal stays on its own route", normal >= 0 && hook > normal)
        assertTrue("only current Ready Hook supersedes old pairing", suppression > hook && pairing > suppression)
        assertTrue("backend selection performs no command", !body.contains(".execute("))
    }
}
