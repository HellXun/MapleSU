package dev.maplesu.manager.installer

import com.maplesu.core.ui.util.FlashOutcome
import com.maplesu.core.ui.util.FlashResult
import dev.maplesu.manager.workmode.service.SwitchOutcome

/** ImagesRequired is an input request, never an image patch failure or a completed write. */
internal fun installerModeSwitchResult(outcome: SwitchOutcome): FlashResult = when (outcome) {
    is SwitchOutcome.ImagesRequired -> FlashResult(0, "", false,
        FlashOutcome.IMAGES_REQUIRED, imagesRequired = outcome)
    is SwitchOutcome.AwaitingReboot -> FlashResult(0, "", true,
        FlashOutcome.WRITTEN, bootCandidate = outcome.candidate)
    is SwitchOutcome.Verified -> FlashResult(0, "", false, FlashOutcome.WRITTEN)
    is SwitchOutcome.Blocked -> FlashResult(1, outcome.code, false, FlashOutcome.FAILED)
    is SwitchOutcome.Incomplete -> FlashResult(1, outcome.code, false, FlashOutcome.RECOVERY_REQUIRED)
    is SwitchOutcome.Failed -> FlashResult(1, outcome.code, false,
        if (outcome.status?.state == "RECOVERY_REQUIRED") FlashOutcome.RECOVERY_REQUIRED else FlashOutcome.FAILED)
}
