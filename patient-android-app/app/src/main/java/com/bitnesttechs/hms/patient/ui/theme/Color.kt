package com.bitnesttechs.hms.patient.ui.theme

import androidx.compose.ui.graphics.Color

// e-Keneya teal, the portal's tokens (--primary and friends). Contrast, WCAG AA:
// white on BrandPrimary 5.10:1; BrandPrimary on white 5.10:1, on SurfaceGrey
// 4.68:1, on BrandSoft 4.51:1; BrandPrimaryDark on BrandTint 5.98:1.
val BrandPrimary = Color(0xFF0E7C6B)
val BrandPrimaryDark = Color(0xFF0A5F52)
/** Fills only, never text on a light surface (2.6:1 on white). */
val BrandPrimaryLight = Color(0xFF23B79C)
val BrandTint = Color(0xFFCCEBE4)
/** The pale container behind brand icons and text. */
val BrandSoft = Color(0xFFE6F4F1)
/** Secondary text on a BrandPrimary fill: 4.73:1, where white at 70-80 % alpha was 3.3-3.9:1. */
val OnBrandMuted = Color(0xFFF0F8F6)

// Semantic
val SuccessGreen = Color(0xFF34A853)
val WarningAmber = Color(0xFFFBBC05)
val WarningOrange = Color(0xFFFF9800)
val ErrorRed = Color(0xFFEA4335)
val CriticalRed = Color(0xFFD32F2F)
val NeutralGrey = Color(0xFF757575)
val SurfaceGrey = Color(0xFFF5F5F5)

// Dark theme variants: the light teal reads at 6.8:1 on the #1C1B1F
// background, and black on it at 8.3:1.
val BrandPrimaryOnDark = BrandPrimaryLight
val BrandSecondaryOnDark = BrandTint

// Status-badge content colours (WCAG AA on the badge's pale fill).
// The bright semantic colours above are the FILL; using them as the text
// colour too put ~1.7:1 amber on pale amber at 11 sp.
val StatusPositiveOnLight = Color(0xFF2E7D32)
val StatusAttentionOnLight = Color(0xFF8C5A00)
val StatusNegativeOnLight = Color(0xFFC62828)
val StatusNeutralOnLight = Color(0xFF616161)
val StatusPositiveOnDark = Color(0xFF81C995)
val StatusAttentionOnDark = Color(0xFFFDD663)
val StatusNegativeOnDark = Color(0xFFF28B82)
val StatusNeutralOnDark = Color(0xFFBDBDBD)
