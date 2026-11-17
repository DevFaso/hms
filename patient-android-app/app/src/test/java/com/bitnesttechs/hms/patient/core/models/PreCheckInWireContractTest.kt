package com.bitnesttechs.hms.patient.core.models

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PreCheckInRequestDTO wire shape: omitted demographics keep existing values; consent stays explicit. */
class PreCheckInWireContractTest {
    private val moshi = Moshi.Builder().build()

    @Test
    fun unchangedDemographicsAreOmittedAndConsentIsNotAssumed() {
        val encoded = moshi.adapter(PreCheckInRequest::class.java)
            .toJson(PreCheckInRequest(appointmentId = "cdx-appointment"))
        val fields = moshi.adapter(Map::class.java).fromJson(encoded)!!

        assertEquals("cdx-appointment", fields["appointmentId"])
        assertEquals(false, fields["consentAcknowledged"])
        assertEquals(emptyList<Any>(), fields["questionnaireResponses"])
        assertFalse(fields.containsKey("phoneNumber"))
        assertFalse(fields.containsKey("emergencyContactName"))
        assertFalse(fields.containsKey("insuranceMemberId"))
    }

    @Test
    fun questionnaireAnswersStayInsideTheirQuestionnaireWithTypedJsonContent() {
        val answers = """{"cdx-yes":false,"cdx-number":0,"cdx-text":"cdx Testeur"}"""
        val request = PreCheckInRequest(
            appointmentId = "cdx-appointment",
            questionnaireResponses = listOf(QuestionnaireSubmission("cdx-questionnaire", answers)),
            consentAcknowledged = true
        )
        val encoded = moshi.adapter(PreCheckInRequest::class.java).toJson(request)
        val fields = moshi.adapter(Map::class.java).fromJson(encoded)!!
        val questionnaires = fields["questionnaireResponses"] as List<*>
        val submission = questionnaires.single() as Map<*, *>

        assertEquals(true, fields["consentAcknowledged"])
        assertEquals("cdx-questionnaire", submission["questionnaireId"])
        assertEquals(answers, submission["responses"])
        assertFalse(fields.containsKey("cdx-yes"))
        val decoded = moshi.adapter(Map::class.java).fromJson(submission["responses"] as String)!!
        assertEquals(false, decoded["cdx-yes"])
        assertEquals(0.0, decoded["cdx-number"])
    }

    @Test
    fun responseSeparatesAppointmentStateFromDemographicUpdateOutcome() {
        val response = moshi.adapter(PreCheckInResponse::class.java).fromJson(
            """{"appointmentId":"cdx-appointment","appointmentStatus":"CONFIRMED",
               "preCheckedIn":true,"preCheckinTimestamp":"2026-11-11T12:00:00",
               "questionnaireResponsesSubmitted":2,"demographicsUpdated":false}"""
        )!!

        assertEquals("cdx-appointment", response.appointmentId)
        assertEquals("CONFIRMED", response.appointmentStatus)
        assertTrue(response.preCheckedIn == true)
        assertFalse(response.demographicsUpdated == true)
        assertEquals(2, response.questionnaireResponsesSubmitted)
        assertEquals("2026-11-11T12:00:00", response.preCheckinTimestamp)
    }
}
