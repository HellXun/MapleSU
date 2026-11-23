package dev.maplesu.manager.runtime.ultra

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class UltraExplicitRepairPreparationTest {
    private val manager = File(System.getProperty("user.dir") ?: ".").let {
        if (File(it, "src/main/java").isDirectory) it else File(it, "manager")
    }
    private val recovery = File(manager,
        "src/main/java/dev/maplesu/manager/runtime/ultra/UltraIdentityRecovery.kt").readText().replace("\r\n", "\n")
    private val installer = File(manager,
        "src/main/java/dev/maplesu/manager/runtime/ultra/UltraEnvironmentInstaller.kt").readText().replace("\r\n", "\n")

    @Test fun onlyExplicitPreparationCanPassTheFaultGuard() {
        val guard = recovery.substringAfter("internal fun blocked(").substringBefore("\n\n")
        assertTrue(guard.contains("preparingUpgrade: Boolean = false"))
        assertTrue(Regex("!preparingUpgrade\\s*&&\\s*UltraEnvironmentProbe.currentSnapshot\\(context\\).state == UltraEnvironmentProbe.State.Fault")
            .containsMatchIn(guard))
        for (required in listOf("LiteImplantQuiesceGate.isBlocked()", "UltraRootKeyStore.isSuspended(context)",
            "WorkModeSwitchSession.executing", "ModeRuntime.active == WorkMode.LITE",
            "UltraSdkSession.current(context) != null", "HookRootExecutor.production(context).available()",
            "ModeRuntime.latestDetection?.trace?.firstOrNull { it.id == \"S1\" }?.ok == true")) {
            assertTrue(required, guard.contains(required))
        }
    }

    @Test fun backupPreparationKeepsLiveRootAndBootKeyProofsWhilePassingItsExplicitIntent() {
        val body = installer.substringAfter("fun backupKey(")
        val eligible = body.substringAfter("fun ineligible()").substringBefore("fun rejected(")
        assertTrue(eligible.contains("UltraIdentityRecovery.blocked(context, preparingUpgrade)"))
        assertTrue(eligible.contains("UltraEnvironmentAutoSetup.currentBootId() != boot"))
        assertTrue(eligible.contains("Thread.currentThread().isInterrupted"))
        assertTrue(eligible.contains("UltraRootKeyStore.read(context) != resolved"))
        val proof = body.indexOf("val proof = client.runRootCommand(")
        val beforeProof = body.substring(0, proof)
        val afterProof = body.substring(proof)
        assertTrue(beforeProof.contains("eligible-before"))
        assertTrue(afterProof.contains("eligible-after"))
        assertTrue(afterProof.contains("if (!proof.succeeded)"))
        assertTrue(afterProof.contains("if (uid != 0)"))
        assertTrue(body.contains("preparingUpgrade = preparingUpgrade"))
        // Explicit preparation is forced work and never seeds the background cache.
        assertTrue(body.contains("if (!preparingUpgrade && boot != null"))
        assertTrue(body.contains("force = forceIdentity || preparingUpgrade"))
    }

    @Test fun identityPreparationPreservesIntentAtEveryEligibilityBoundary() {
        val body = recovery.substringAfter("fun ensure(").substringBefore("private fun prepareDeployment(")
        assertEquals(2, Regex("blocked\\(context, preparingUpgrade\\)").findAll(body).count())
        assertTrue(body.contains("UltraRootKeyStore.read(context) == key"))
        assertTrue(body.contains("completeDeployment(context, key, client, preparingUpgrade)"))
        val complete = recovery.substringAfter("private fun completeDeployment(")
            .substringBefore("/** Only the authenticated root socket")
        assertTrue(complete.contains("blocked(context, preparingUpgrade)"))
        assertTrue(complete.contains("UltraRootKeyStore.read(context) != key"))
        assertTrue(complete.contains("UltraIdentityBootHook.ensure(environment.verification.verified, preparingUpgrade)"))
    }

    @Test fun backgroundRecoveryKeepsTheDefaultFaultBlock() {
        val body = recovery.substringAfter("fun recover(context: Context): String")
            .substringBefore("internal fun uid(")
        assertTrue(body.contains("state == UltraEnvironmentProbe.State.Fault) return \"RECOVERY_SUSPENDED\""))
        assertTrue(body.contains("blocked(app)"))
        assertFalse(body.contains("preparingUpgrade"))
    }

    @Test fun onlyBundledUpgradeRequestsTheProductionPreparationException() {
        val preparing = installer.substringAfter("prepareUpgrade = {").substringBefore("prepareInstall =")
        assertTrue(preparing.contains("backupKey(context, forceIdentity = true, preparingUpgrade = true)"))
        val dispatch = installer.substringAfter("fun install(port: InstallPort, intent: Intent, attachments: Attachments)")
        assertTrue(dispatch.contains("if (intent == Intent.UpgradeToBundledBuild)"))
        assertTrue(dispatch.indexOf("if (intent == Intent.UpgradeToBundledBuild)") <
            dispatch.indexOf("attachments.prepareUpgrade()"))
        assertTrue(dispatch.indexOf("attachments.prepareUpgrade()") < dispatch.indexOf("port.submitInstall()"))
        assertEquals(1, Regex("preparingUpgrade = true").findAll(installer).count())
    }
}
