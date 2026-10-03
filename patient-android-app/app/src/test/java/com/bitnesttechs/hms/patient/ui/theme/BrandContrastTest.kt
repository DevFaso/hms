package com.bitnesttechs.hms.patient.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-app theme moved from the pre-brand blue to the e-Keneya teal. Every
 * text/background pair the screens actually draw is held to WCAG AA body
 * text (4.5:1) here, so a later palette tweak cannot quietly drop one.
 */
class BrandContrastTest {

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb) / minOf(la, lb).toDouble()
    }

    private fun assertAA(label: String, text: Color, background: Color) {
        val ratio = contrast(text, background)
        assertTrue("$label is %.2f:1, below 4.5:1".format(ratio), ratio >= 4.5)
    }

    @Test
    fun `the palette is the portal's teal, not the old blue`() {
        assertEquals(Color(0xFF0E7C6B), BrandPrimary)
        assertEquals(Color(0xFF0A5F52), BrandPrimaryDark)
        assertEquals(Color(0xFF23B79C), BrandPrimaryLight)
        assertEquals(Color(0xFFCCEBE4), BrandTint)
        assertEquals(Color(0xFFE6F4F1), BrandSoft)
    }

    @Test
    fun `text drawn on or in the brand colour meets AA`() {
        assertAA("white on the top bars and buttons", Color.White, BrandPrimary)
        assertAA("white on the login gradient's dark end", Color.White, BrandPrimaryDark)
        assertAA("muted secondary text on brand fills", OnBrandMuted, BrandPrimary)
        assertAA("brand text on white", BrandPrimary, Color.White)
        assertAA("brand text on the grey surface", BrandPrimary, SurfaceGrey)
        assertAA("brand text on the soft containers", BrandPrimary, BrandSoft)
        assertAA("dark brand text on the tint", BrandPrimaryDark, BrandTint)
        assertAA("light teal as the dark theme's primary text", BrandPrimaryOnDark, Color(0xFF1C1B1F))
        assertAA("black on the dark theme's primary", Color.Black, BrandPrimaryOnDark)
    }

    @Test
    fun `the light teal is a fill, never light-surface text`() {
        assertTrue(contrast(BrandPrimaryLight, Color.White) < 4.5)
    }
}
