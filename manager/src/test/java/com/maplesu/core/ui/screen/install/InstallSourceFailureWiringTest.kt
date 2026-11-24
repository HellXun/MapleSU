package com.maplesu.core.ui.screen.install

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Access errors must keep their meaning rather than becoming a missing-root assertion. */
class InstallSourceFailureWiringTest {
    @Test fun directSourceDoesNotMapEveryFailureToMissingRoot() {
        val source = File("src/main/java/com/maplesu/core/ui/screen/install/InstallSteps.kt").readText()
        val status = source.substringAfter("internal fun InstallSourceStatusSection")
            .substringBefore("internal fun InstallOriginalImageSection")
        assertFalse(status.contains("else -> R.string.install_source_root_required"))
        assertTrue(status.contains("InstallSourceAccessFailure.CHANNEL_UNAVAILABLE"))
    }
}
