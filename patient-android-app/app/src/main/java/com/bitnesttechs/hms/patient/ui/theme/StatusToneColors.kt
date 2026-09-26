package com.bitnesttechs.hms.patient.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.bitnesttechs.hms.patient.core.models.StatusTone

/**
 * One pair of colours per [StatusTone], so a lab result, a prescription and a
 * refill that mean the same thing look the same wherever they are shown. Both
 * `when`s are exhaustive on purpose: a new tone must be given colours here.
 *
 * [badgeFill] is the bright brand colour the badge is tinted with;
 * [onBadge] is what the label and the icon are drawn in. They are NOT the
 * same colour: `WarningAmber` (#FBBC05) reads at about 1.7:1 on white and
 * `SuccessGreen` at about 3.1:1, so using the fill as the text colour left
 * an 11 sp label below the 4.5:1 WCAG AA floor.
 */
@Composable
fun StatusTone.badgeFill(): Color = when (this) {
    StatusTone.POSITIVE -> SuccessGreen
    StatusTone.ATTENTION -> WarningAmber
    StatusTone.NEGATIVE -> CriticalRed
    StatusTone.NEUTRAL -> NeutralGrey
}

/**
 * The label/icon colour: a darkened variant in light mode, a lightened one on
 * the dark theme's #2C2C2E surface, where the dark variants would be the
 * unreadable ones.
 */
@Composable
fun StatusTone.onBadge(): Color {
    val dark = isSystemInDarkTheme()
    return when (this) {
        StatusTone.POSITIVE -> if (dark) StatusPositiveOnDark else StatusPositiveOnLight
        StatusTone.ATTENTION -> if (dark) StatusAttentionOnDark else StatusAttentionOnLight
        StatusTone.NEGATIVE -> if (dark) StatusNegativeOnDark else StatusNegativeOnLight
        StatusTone.NEUTRAL -> if (dark) StatusNeutralOnDark else StatusNeutralOnLight
    }
}
