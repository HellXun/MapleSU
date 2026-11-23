package dev.maplesu.manager.runtime.ultra

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UltraBootSdkInstallTest {
    private companion object {
        fun receipt(error: String = "OK", code: Int = 0): NativeReceipt = requireNotNull(
            NativeReceipt.parse(JSONObject().put("backend", "maplesu_ultra")
                .put("operation", "install_maplesu_environment").put("ok", error == "OK")
                .put("sdkError", error).put("sdkErrorCode", code).toString()),
        )
    }

    private class Port : UltraBootSdkInstall.Port {
        val calls = mutableListOf<String>()
        var valid = true
        var channel = true
        var metadata = true
        var state: String? = "NotInstalled"
        var prepareOk = true
        var guardOk = true
        var guardStored = false
        var submissions = 0
        var stateAfterSubmit: String? = "Running"
        var submitError: Exception? = null
        var submitReceipt = receipt()
        var root = true
        var whitelist = true
        var modules = true
        var after: (String) -> Unit = {}
        private fun <T> observed(name: String, value: T): T = value.also {
            calls += name
            after(name)
        }
        override fun current() = valid && !Thread.currentThread().isInterrupted
        override fun channelVerified() = observed("channel", channel)
        override fun kernelBaseVerified() = observed("metadata", metadata)
        override fun environmentState() = observed("state", state)
        override fun prepare() = observed("prepare", prepareOk)
        override fun markSubmitted(): Boolean {
            if (guardOk) guardStored = true
            return observed("guard", guardOk)
        }
        override fun submit(): NativeReceipt {
            assertTrue("The guard must be durable before submitting", guardStored)
            submissions++
            state = stateAfterSubmit
            observed("submit", Unit)
            submitError?.let { throw it }
            return submitReceipt
        }
        override fun rootVerified() = observed("root", root)
        override fun whitelistVerified() = observed("whitelist", whitelist)
        override fun moduleListVerified() = observed("modules", modules)
        override fun managerSuVerified() = true
        fun run() = UltraBootSdkInstall.ensure(guardStored, this)
    }

    @Test fun hotLoadIsGuardedAndAllReadbacksAreRequired() {
        val port = Port()
        val result = port.run()
        assertTrue(result.ready)
        assertTrue(result.submitted)
        assertEquals("RUNNING_VERIFIED", result.reason)
        assertTrue(requireNotNull(result.submission).succeeded)
        assertEquals(listOf("channel", "metadata", "state", "prepare", "guard", "submit",
            "state", "root", "whitelist", "modules"), port.calls)
        assertTrue(port.run().ready)
        assertEquals(1, port.submissions)
    }

    @Test fun healthyEnvironmentDoesNotConsumeASubmission() {
        val port = Port().apply { state = "Running" }
        val result = port.run()
        assertTrue(result.ready)
        assertFalse(result.submitted)
        assertFalse(port.guardStored)
        assertEquals(0, port.submissions)
    }

    @Test fun faultAndUnknownStatesNeverInstall() {
        for (state in listOf("Fault", "Unknown", null)) {
            val port = Port().apply { this.state = state }
            assertFalse(port.run().ready)
            assertFalse(port.guardStored)
            assertEquals(0, port.submissions)
        }
    }

    @Test fun unverifiedChannelOrMetadataNeverPreparesOrSubmits() {
        for (failedProbe in listOf("channel", "metadata")) {
            val port = Port().apply {
                channel = failedProbe != "channel"
                metadata = failedProbe != "metadata"
            }
            assertFalse(port.run().ready)
            assertFalse(port.calls.contains("prepare"))
            assertFalse(port.guardStored)
        }
    }

    @Test fun preparationAndGuardFailurePreventSubmission() {
        for (failure in listOf("prepare", "guard")) {
            val port = Port().apply {
                prepareOk = failure != "prepare"
                guardOk = failure != "guard"
            }
            assertFalse(port.run().ready)
            assertEquals(0, port.submissions)
            assertFalse(port.guardStored)
        }
    }

    @Test fun lostReplyIsReconciledWithoutReplayingTheInstall() {
        val port = Port().apply { submitError = IllegalStateException("reply lost") }
        assertTrue(port.run().ready)
        assertTrue(port.guardStored)
        assertTrue(port.run().ready)
        assertEquals(1, port.submissions)
    }

    @Test fun unresolvedLostReplyRetainsTheGuardAcrossReload() {
        val port = Port().apply {
            submitError = IllegalStateException("reply lost")
            stateAfterSubmit = "NotInstalled"
        }
        assertEquals("HOTLOAD_RECONCILIATION_REQUIRED", port.run().reason)
        assertEquals("HOTLOAD_RECONCILIATION_REQUIRED", port.run().reason)
        assertEquals(1, port.submissions)
        val reloaded = Port().apply { guardStored = port.guardStored }
        assertEquals("HOTLOAD_RECONCILIATION_REQUIRED", reloaded.run().reason)
        assertEquals(0, reloaded.submissions)
    }

    @Test fun failedHotLoadKeepsTheSdkOperationAndErrorWithoutRetrying() {
        val port = Port().apply {
            submitReceipt = receipt("ERR_MODULE_NO_BOOT", -19998)
            stateAfterSubmit = "NotInstalled"
        }
        val result = port.run()
        assertFalse(result.ready)
        assertTrue(result.submitted)
        assertEquals("HOTLOAD_RECONCILIATION_REQUIRED:install_maplesu_environment:ERR_MODULE_NO_BOOT:-19998",
            result.reason)
        val submission = requireNotNull(result.submission)
        assertFalse(submission.succeeded)
        assertEquals("install_maplesu_environment", submission.operation)
        assertEquals("ERR_MODULE_NO_BOOT", submission.sdkError)
        assertEquals(-19998, submission.sdkErrorCode)
        assertEquals("HOTLOAD_RECONCILIATION_REQUIRED", port.run().reason)
        assertEquals(1, port.submissions)
        assertTrue(port.guardStored)
    }

    @Test fun failedReceiptStillReconcilesARunningEnvironment() {
        val port = Port().apply { submitReceipt = receipt("MAPLESU_SDK_PROCESS_ERROR", -1006) }
        val result = port.run()
        assertTrue(result.ready)
        assertEquals("RUNNING_VERIFIED", result.reason)
        assertEquals("MAPLESU_SDK_PROCESS_ERROR", requireNotNull(result.submission).sdkError)
        assertTrue(port.run().ready)
        assertEquals(1, port.submissions)
    }

    @Test fun acceptedHotLoadStillNeedsRunningAndDoesNotReplay() {
        val port = Port().apply { stateAfterSubmit = "Unknown" }
        val result = port.run()
        assertFalse(result.ready)
        assertEquals("HOTLOAD_RECONCILIATION_REQUIRED", result.reason)
        assertTrue(requireNotNull(result.submission).succeeded)
        assertFalse(port.run().ready)
        assertEquals(1, port.submissions)
    }

    @Test fun submissionDiagnosticsExcludeRawDetailsAndCredentialPaths() {
        val secret = "Aa1".repeat(16)
        val source = receipt("ERR_MODULE_NO_BOOT", -19998)
        val port = Port().apply {
            submitReceipt = source.copy(reason = secret,
                data = JSONObject().put("path", "/data/${secret.take(16)}/${secret}"), raw = secret)
            stateAfterSubmit = "NotInstalled"
        }
        val result = port.run()
        assertFalse(result.toString().contains(secret))
        assertFalse(result.toString().contains(secret.take(16)))
        assertEquals(-19998, requireNotNull(result.submission).sdkErrorCode)
    }

    @Test fun sessionOrAdmissionChangesDuringProbesAbortBeforeMutation() {
        for (boundary in listOf("channel", "metadata", "state", "prepare")) {
            val port = Port().apply { after = { if (it == boundary) valid = false } }
            assertEquals(boundary, "SESSION_CHANGED", port.run().reason)
            assertFalse(port.guardStored)
            assertEquals(0, port.submissions)
        }
    }

    @Test fun sessionChangeAfterGuardKeepsTheGuardWithoutSubmitting() {
        val port = Port().apply { after = { if (it == "guard") valid = false } }
        assertEquals("SESSION_CHANGED", port.run().reason)
        assertTrue(port.guardStored)
        assertEquals(0, port.submissions)
        port.valid = true
        assertEquals("HOTLOAD_RECONCILIATION_REQUIRED", port.run().reason)
    }

    @Test fun sessionChangesDuringReadyReadbacksNeverReportReady() {
        for (boundary in listOf("state", "root", "whitelist", "modules")) {
            val port = Port().apply {
                state = "Running"
                after = { if (it == boundary) valid = false }
            }
            val result = port.run()
            assertEquals(boundary, "SESSION_CHANGED", result.reason)
            assertFalse(result.ready)
            assertEquals(0, port.submissions)
        }
    }

    @Test fun missingRootWhitelistOrModulesNeverReportsReady() {
        for (boundary in listOf("root", "whitelist", "modules")) {
            val port = Port().apply {
                state = "Running"
                root = boundary != "root"
                whitelist = boundary != "whitelist"
                modules = boundary != "modules"
            }
            assertFalse(port.run().ready)
            assertEquals(0, port.submissions)
        }
    }

    @Test fun interruptedSubmissionDoesNotContinueIntoOtherSdkOperations() {
        val port = Port().apply { submitError = InterruptedException("cancelled") }
        try {
            assertEquals("SESSION_CHANGED", port.run().reason)
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, port.submissions)
            assertFalse(port.calls.contains("root"))
            assertFalse(port.calls.contains("whitelist"))
        } finally {
            Thread.interrupted()
        }
    }
}
