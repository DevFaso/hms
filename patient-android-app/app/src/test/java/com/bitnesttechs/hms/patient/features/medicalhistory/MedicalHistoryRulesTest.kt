package com.bitnesttechs.hms.patient.features.medicalhistory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.util.Locale
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class MedicalHistoryRulesTest {

    private fun jwt(payload: String): String {
        val header = Base64.UrlSafe.encode("""{"alg":"RS256","typ":"JWT"}""".toByteArray()).trimEnd('=')
        val body = Base64.UrlSafe.encode(payload.toByteArray()).trimEnd('=')
        return "$header.$body.signature"
    }

    @Test
    fun jwtSubjectReadsTheSubClaimWithoutPadding() {
        val token = jwt("""{"exp":1790000000,"sub":"6f1c2b6e-1111-4c2a-9d6b-0a0a0a0a0a0a","name":"Awa"}""")
        assertEquals("6f1c2b6e-1111-4c2a-9d6b-0a0a0a0a0a0a", HistoryNotesStore.jwtSubject(token))
    }

    @Test
    fun jwtSubjectIsNullForAnythingThatIsNotAJwtWithASub() {
        assertNull(HistoryNotesStore.jwtSubject(null))
        assertNull(HistoryNotesStore.jwtSubject(""))
        assertNull(HistoryNotesStore.jwtSubject("not-a-jwt"))
        assertNull(HistoryNotesStore.jwtSubject("a.b"))
        assertNull(HistoryNotesStore.jwtSubject(jwt("""{"exp":1790000000,"name":"Awa"}""")))
        assertNull(HistoryNotesStore.jwtSubject(jwt("""{"sub":""}""")))
        assertNull(HistoryNotesStore.jwtSubject("x.%%%not-base64%%%.y"))
    }

    @Test
    fun timestampsAreConvertedToTheDeviceZoneBeforeTheDateIsTaken() {
        // 23:30 UTC on the 21st is still the 21st in Ouagadougou (UTC+0) but already the 22nd in Paris (UTC+2 in September).
        val iso = "2026-09-21T23:30:00+00:00"
        assertEquals("21/09/2026", MedicalHistoryDates.formatDateTime(iso, ZoneId.of("Africa/Ouagadougou"), Locale.FRANCE))
        assertEquals("22/09/2026", MedicalHistoryDates.formatDateTime(iso, ZoneId.of("Europe/Paris"), Locale.FRANCE))
        // The naive first-10-characters read would have said the 21st everywhere.
        assertEquals("9/22/26", MedicalHistoryDates.formatDateTime(iso, ZoneId.of("Europe/Paris"), Locale.US))
    }

    @Test
    fun dateOnlyFieldsAreNeverShiftedByTheZone() {
        assertEquals("01/06/2019", MedicalHistoryDates.formatDate("2019-06-01", Locale.FRANCE))
        assertEquals("6/1/19", MedicalHistoryDates.formatDate("2019-06-01", Locale.US))
    }

    @Test
    fun absentAndUnparsableDatesFallBackAsTheWebDoes() {
        assertEquals("-", MedicalHistoryDates.formatDate(null))
        assertEquals("-", MedicalHistoryDates.formatDateTime(" "))
        assertEquals("soon", MedicalHistoryDates.formatDate("soon"))
        assertEquals("2026-09-21", MedicalHistoryDates.formatDateTime("2026-09-21"))
    }
}
