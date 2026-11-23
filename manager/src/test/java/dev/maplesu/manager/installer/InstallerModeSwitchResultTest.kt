package dev.maplesu.manager.installer

import com.maplesu.core.ui.util.FlashOutcome
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.service.SwitchOutcome
import org.junit.Assert.*
import org.junit.Test

class InstallerModeSwitchResultTest {
    @Test fun missingBaselineRequestsImagesWithoutReportingPatchFailure() {
        val request = SwitchOutcome.ImagesRequired(WorkMode.LITE, false)
        val result = installerModeSwitchResult(request)
        assertEquals(FlashOutcome.IMAGES_REQUIRED, result.outcome)
        assertSame(request, result.imagesRequired)
        assertFalse(result.showReboot)
        assertTrue(result.err.isEmpty())
    }
    @Test fun noInitBootRequiresOriginalBootInsteadOfTheNormalPatchedPartition() {
        assertTrue(SwitchOutcome.ImagesRequired(WorkMode.LITE, false).requiresBoot)
        assertFalse(SwitchOutcome.ImagesRequired(WorkMode.LITE, true).requiresBoot)
        assertTrue(SwitchOutcome.ImagesRequired(WorkMode.LITE, false).requiresBoot)
    }
    @Test fun onlyCompletedTransactionReportsWritten() {
        val result = installerModeSwitchResult(SwitchOutcome.AwaitingReboot("test", null))
        assertEquals(FlashOutcome.WRITTEN, result.outcome)
        assertTrue(result.showReboot)
        val failure = installerModeSwitchResult(SwitchOutcome.Failed(null, "BASELINE_INVALID", "", null))
        assertEquals(FlashOutcome.FAILED, failure.outcome)
        assertEquals("BASELINE_INVALID", failure.err)
    }
}
