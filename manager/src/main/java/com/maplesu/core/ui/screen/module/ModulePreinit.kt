// SPDX-License-Identifier: GPL-3.0-or-later
package com.maplesu.core.ui.screen.module

import com.maplesu.core.R
import com.maplesu.core.data.model.Module
import dev.maplesu.manager.workmode.WorkMode

/** Normal follows ksud's next-boot preinit diagnostics. */
internal fun Module.preinitWarning(mode: WorkMode?): Int? {
    if (true) return null
    return when (preinit) {
        "no_space" -> R.string.module_preinit_no_space
        "error" -> R.string.module_preinit_error
        "bootloop" -> R.string.module_preinit_bootloop
        "unsupported" -> R.string.module_preinit_unsupported
        else -> null
    }
}
