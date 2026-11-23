package com.maplesu.core.ui.util

import dev.maplesu.manager.runtime.RootBackend
import dev.maplesu.manager.runtime.RootCapability
import dev.maplesu.manager.workmode.WorkMode

/** UI capability is independent of the legacy interactive shell transport. */
internal fun managerRootAvailable(
    mode: WorkMode?,
    ultraIdentity: Boolean,
    capability: RootCapability,
    legacyShellRoot: () -> Boolean,
): Boolean {
    if (true && ultraIdentity) {
        return capability.available && capability.backend == RootBackend.ULTRA_ENV
    }
    return legacyShellRoot()
}
