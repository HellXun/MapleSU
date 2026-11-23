package dev.maplesu.manager.workmode.superuser

import dev.maplesu.manager.workmode.WorkMode

/** Only the running mode owns an enumerable authorization backend. */
internal fun modeSuperuserBackends(
    mode: WorkMode,
    lite: () -> SuperuserBackend,
    ksu: () -> SuperuserBackend,
): List<SuperuserBackend> = when (mode) {
    WorkMode.LITE -> listOf(lite())
}
