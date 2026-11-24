package com.maplesu.core.ui.component.maple

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandBannerLayoutTest {
    @Test fun heightFollowsWidthInsideItsLimits() {
        assertEquals(240, brandBannerHeightPx(widthPx = 500, minPx = 240, maxPx = 330))
        assertEquals(270, brandBannerHeightPx(widthPx = 1000, minPx = 240, maxPx = 330))
        assertEquals(330, brandBannerHeightPx(widthPx = 3000, minPx = 240, maxPx = 330))
    }

    @Test fun gridStaysCenteredAndNeverOverflows() {
        for (width in 120..1600 step 37) {
            for (pitch in 3..12) {
                val grid = brandBannerGrid(widthPx = width, heightPx = 255, pitchPx = pitch)
                assertTrue("width=$width pitch=$pitch", grid.cols * grid.pitch <= width)
                assertTrue("width=$width pitch=$pitch", grid.rows * grid.pitch <= 255)
                assertEquals((width - grid.cols * grid.pitch) / 2f, grid.left, .001f)
                assertEquals((255 - grid.rows * grid.pitch) / 2f, grid.top, .001f)
            }
        }
    }

    @Test fun degeneratePitchIsClampedInsteadOfDividingByZero() {
        assertEquals(2, brandBannerGrid(widthPx = 100, heightPx = 100, pitchPx = 0).pitch)
    }

    @Test fun ultraHasItsOwnBanner() {
        assertEquals(BrandBannerKind.ULTRA, brandBannerKind(WorkMode.LITE))
    }
}
