package com.bitnesttechs.hms.patient.ui.theme

import androidx.compose.ui.graphics.Color
import com.bitnesttechs.hms.patient.core.models.StatusTone

/**
 * One brand colour per [StatusTone], so a lab result, a prescription and a
 * refill that mean the same thing look the same wherever they are shown. The
 * `when` is exhaustive on purpose: a new tone must be given a colour here.
 */
fun StatusTone.brandColor(): Color = when (this) {
    StatusTone.POSITIVE -> SuccessGreen
    StatusTone.ATTENTION -> WarningAmber
    StatusTone.NEGATIVE -> CriticalRed
    StatusTone.NEUTRAL -> NeutralGrey
}
