package dev.maplesu.manager.installer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maplesu.core.ui.MainActivity
import com.maplesu.core.ui.util.UltraImageBuild
import com.maplesu.core.ui.util.ultraImageBuildCandidate
import dev.maplesu.manager.ImageRepository
import dev.maplesu.manager.ultrakernel.UltraKernelFlow
import dev.maplesu.manager.ultrakernel.UltraKernelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs the real current-boot builder and cancels every transaction before commit. */
@RunWith(AndroidJUnit4::class)
class UltraDirectInstallPreflightAndroidTest {
    /** Exercises the source FD used by the real flash page, then cancels before commit. */
    @Test fun nativeTransactionSourceReachesTheProductionBuilderWithoutWriting() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("existingRootInstaller") == "true")
        val context = instrumentation.targetContext
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val taskId = java.util.UUID.randomUUID().toString()
        val before = InstallerServiceClient.withService(context, rootTarget = InstallTargetMode.ULTRA) {
            InstallerServiceClient.decode(InstallerServiceClient.observe(it))
        }
        var buildResult = ""
        try {
            StockInstallFlow.install(context, taskId, InstallTargetMode.ULTRA, "boot", null,
                restoreOriginal = InstrumentationRegistry.getArguments().getString("restoreMigrationOriginals") == "true",
                selectedManager = null, conflictAccepted = true,
                onPrepared = { assertEquals("PREPARED", it.getString("state")) },
                produce = { source, _ ->
                    if (InstrumentationRegistry.getArguments().getString("restoreMigrationOriginals") != "true") {
                        assertEquals(before.partitions.getValue("boot").sha256, UltraKernelSpec.sha256(source))
                    }
                    val repository = ImageRepository(context)
                    val imported = repository.import(Uri.fromFile(source))
                    when (val result = ultraImageBuildCandidate(repository, imported.report.sha256, {})) {
                        is UltraImageBuild.AlreadyPatched -> {
                            assertEquals(result.candidate.bootSha256, UltraKernelSpec.sha256(result.candidate.image))
                            assertTrue("Migration must keep the identity embedded in the patched source",
                                result.candidate.credential != null)
                            buildResult = "REUSABLE_PATCHED_CANDIDATE"
                        }
                        is UltraImageBuild.Candidate -> {
                            assertEquals(result.bootSha256, UltraKernelSpec.sha256(result.image))
                            buildResult = "CANDIDATE_VERIFIED"
                        }
                        is UltraImageBuild.Refused -> throw AssertionError("Production builder refused: ${result.message}")
                    }
                    throw PreflightComplete()
                },
                onWriting = { throw AssertionError("Read-only preflight reached the write stage") })
            throw AssertionError("Read-only preflight returned without its deliberate stop")
        } catch (_: PreflightComplete) {
            // The production StockInstallAttempt finally block cancels this PREPARED transaction.
        }
        InstallerServiceClient.withService(context, rootTarget = InstallTargetMode.ULTRA) { service ->
            val status = service.getBootInstall(taskId)
            assertEquals("CANCELLED", status.getString("state"))
            assertEquals(0, status.getInt("attemptedPartitionCount", -1))
            val after = InstallerServiceClient.decode(InstallerServiceClient.observe(service))
            assertEquals(before.partitions, after.partitions)
            instrumentation.sendStatus(2, Bundle().apply {
                putString("nativeDirectTaskId", taskId)
                putString("nativeDirectResult", buildResult)
                putString("nativeDirectState", status.getString("state"))
                putInt("nativeDirectAttemptedPartitionCount", status.getInt("attemptedPartitionCount", -1))
                putBoolean("nativeDirectPartitionsUnchanged", true)
            })
        }
    }

    private class PreflightComplete : RuntimeException()

    @Test fun currentBootReachesTheUltraBuilderAndLeavesPartitionsUnchanged() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("existingRootInstaller") == "true")
        val context = instrumentation.targetContext
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val work = File(context.cacheDir, "ultra-direct-preflight-${java.util.UUID.randomUUID()}")
        assertTrue(work.mkdir())
        try {
            InstallerServiceClient.withService(context, rootTarget = InstallTargetMode.ULTRA) { service ->
                val response = Bundle()
                val source = File(work, "current-boot.img")
                val before = ParcelFileDescriptor.AutoCloseInputStream(service.openBootImage(response)).use { input ->
                    InstallerServiceClient.decode(response).also { snapshot ->
                        source.outputStream().use { output ->
                            input.copyTo(output, 64 * 1024)
                            output.fd.sync()
                        }
                        val boot = snapshot.partitions.getValue("boot")
                        assertEquals(boot.size, source.length())
                        assertEquals(boot.sha256, UltraKernelSpec.sha256(source))
                    }
                }
                val flow = UltraKernelFlow(File(work, "builder"),
                    dev.maplesu.manager.ultrakernel.UltraKernelContextAssets(context))
                val release = flow.releaseOf(source)
                assertEquals(before.kernelRelease, release)
                val selection = requireNotNull(flow.selectForRelease(release)) {
                    "This selected-device regression requires a shipped kernel-series asset"
                }
                val preparation = flow.prepare(source, selection) {}
                if (preparation.alreadyInstalled) {
                    assertNull(preparation.candidate)
                } else {
                    val candidate = requireNotNull(preparation.candidate)
                    assertEquals(release, candidate.kernelRelease)
                    assertTrue(candidate.image.length() in 1..before.partitions.getValue("boot").size)
                    assertEquals(candidate.bootSha256, UltraKernelSpec.sha256(candidate.image))
                    val reopened = flow.prepare(candidate.image, selection) {}
                    assertTrue("The produced template must be recognized on a second install", reopened.alreadyInstalled)
                    assertNull(reopened.candidate)
                }
                // The production service reads the devices again, rather than trusting our file.
                val after = InstallerServiceClient.decode(InstallerServiceClient.observe(service))
                assertEquals(before.kernelRelease, after.kernelRelease)
                assertEquals(before.firmware, after.firmware)
                assertEquals(before.slotSuffix, after.slotSuffix)
                assertEquals(before.selinux, after.selinux)
                assertEquals(before.partitions, after.partitions)
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("ultraDirectRelease", release)
                    putString("ultraDirectSourceSha256", before.partitions.getValue("boot").sha256)
                    putString("ultraDirectResult", if (preparation.alreadyInstalled) "ALREADY_PATCHED" else "CANDIDATE_VERIFIED")
                    preparation.candidate?.let {
                        putString("ultraDirectCandidateSha256", it.bootSha256)
                        putBoolean("ultraDirectExpectedPinned", it.expectedPinned)
                    }
                    putBoolean("ultraDirectPartitionsUnchanged", true)
                })
            }
        } finally {
            work.deleteRecursively()
        }
    }
}
