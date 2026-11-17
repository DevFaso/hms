package com.bitnesttechs.hms.patient.features.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * The inbox printed `lastMessageTimestamp` verbatim ("2026-09-19T10:30:00").
 * It is now a localized, relative label in each of the app's languages.
 */
class ChatTimeTest {

    // Saturday 26 September 2026, noon.
    private val now = LocalDateTime.of(2026, 9, 26, 12, 0)
    private val fr = Locale.FRENCH
    private val es = Locale("es")
    private val en = Locale.ENGLISH
    private val utc = ZoneId.of("UTC")

    private fun inbox(raw: String?, locale: Locale, yesterday: String = "Yesterday") =
        ChatTime.inboxLabel(raw, now, locale, yesterday, utc)

    @Test
    fun `today is the local short time, never the wire string`() {
        assertEquals("10:30", inbox("2026-09-26T10:30:00", fr))
        assertEquals("10:30", inbox("2026-09-26T10:30:00", es))
        val english = inbox("2026-09-26T10:30:00", en)!!
        assertTrue(english, english.startsWith("10:30") && english.contains("AM"))
        assertFalse(english.contains("T"))
    }

    @Test
    fun `yesterday is the localized word the caller passes in`() {
        assertEquals("Hier", inbox("2026-09-25T23:59:00", fr, yesterday = "Hier"))
        assertEquals("Ayer", inbox("2026-09-25T08:00:00", es, yesterday = "Ayer"))
    }

    @Test
    fun `within the last week it is the weekday in the app language`() {
        // 21 September 2026 is a Monday.
        assertEquals("Monday", inbox("2026-09-21T09:00:00", en))
        assertEquals("Lundi", inbox("2026-09-21T09:00:00", fr))
        assertEquals("Lunes", inbox("2026-09-21T09:00:00", es))
    }

    @Test
    fun `older messages show a localized date`() {
        assertEquals("19 sept. 2025", inbox("2025-09-19T10:30:00", fr))
        val english = inbox("2025-09-19T10:30:00", en)!!
        assertTrue(english, english.contains("2025") && english.contains("Sep"))
        val spanish = inbox("2025-09-19T10:30:00", es)!!
        assertTrue(spanish, spanish.startsWith("19") && spanish.contains("sept") && spanish.contains("2025"))
    }

    @Test
    fun `fractional seconds, minutes-only and offset forms all parse`() {
        assertEquals("10:30", inbox("2026-09-26T10:30:00.123456", fr))
        assertEquals("10:30", inbox("2026-09-26T10:30", fr))
        assertEquals("10:30", inbox("2026-09-26T10:30:00Z", fr))
    }

    @Test
    fun `absent or unparseable times render as nothing rather than the raw string`() {
        assertNull(inbox(null, fr))
        assertNull(inbox("  ", fr))
        assertNull(inbox("yesterday-ish", fr))
    }

    @Test
    fun `a bubble shows the time today and the date with the time otherwise`() {
        assertEquals("10:30", ChatTime.bubbleLabel("2026-09-26T10:30:00", now, fr, utc))
        val older = ChatTime.bubbleLabel("2026-09-20T10:30:00", now, fr, utc)!!
        assertTrue(older, older.contains("20 sept. 2026") && older.contains("10:30"))
    }
}
