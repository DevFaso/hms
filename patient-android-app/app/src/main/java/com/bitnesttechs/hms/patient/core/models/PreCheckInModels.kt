package com.bitnesttechs.hms.patient.core.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * GET /me/patient/appointments/{id}/questionnaires. `questions` is a JSON
 * array the hospital authored; see [QuestionnaireQuestion] for its shape.
 */
@JsonClass(generateAdapter = true)
data class QuestionnaireDto(
    @Json(name = "id") val id: String,
    @Json(name = "title") val title: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "questions") val questions: String? = null,
    @Json(name = "version") val version: Int? = null,
    @Json(name = "departmentId") val departmentId: String? = null,
    @Json(name = "departmentName") val departmentName: String? = null
)

/**
 * One entry of a questionnaire's `questions` JSON, the shape the web parses:
 * id, text, type (YES_NO, TEXT, NUMBER, SCALE, MULTI_CHOICE), required,
 * options, min, max. Parsed with org.json in the view model, so a malformed
 * questionnaire degrades to "no questions" exactly as it does on the web.
 */
data class QuestionnaireQuestion(
    val id: String,
    val text: String,
    val type: String,
    val required: Boolean,
    val options: List<String>,
    val min: Double?,
    val max: Double?
)

/** One questionnaire's answers: a JSON object of questionId -> answer, as the web sends it. */
@JsonClass(generateAdapter = true)
data class QuestionnaireSubmission(
    @Json(name = "questionnaireId") val questionnaireId: String,
    @Json(name = "responses") val responses: String
)

/**
 * POST /me/patient/appointments/{id}/pre-checkin (PreCheckInRequestDTO). Every
 * demographic field is optional: null keeps the current value.
 */
@JsonClass(generateAdapter = true)
data class PreCheckInRequest(
    @Json(name = "appointmentId") val appointmentId: String,
    @Json(name = "phoneNumber") val phoneNumber: String? = null,
    @Json(name = "email") val email: String? = null,
    @Json(name = "addressLine1") val addressLine1: String? = null,
    @Json(name = "city") val city: String? = null,
    @Json(name = "state") val state: String? = null,
    @Json(name = "zipCode") val zipCode: String? = null,
    @Json(name = "emergencyContactName") val emergencyContactName: String? = null,
    @Json(name = "emergencyContactPhone") val emergencyContactPhone: String? = null,
    @Json(name = "emergencyContactRelationship") val emergencyContactRelationship: String? = null,
    @Json(name = "insuranceProvider") val insuranceProvider: String? = null,
    @Json(name = "insuranceMemberId") val insuranceMemberId: String? = null,
    @Json(name = "insurancePlan") val insurancePlan: String? = null,
    @Json(name = "questionnaireResponses") val questionnaireResponses: List<QuestionnaireSubmission> = emptyList(),
    @Json(name = "consentAcknowledged") val consentAcknowledged: Boolean = false
)

@JsonClass(generateAdapter = true)
data class PreCheckInResponse(
    @Json(name = "appointmentId") val appointmentId: String? = null,
    @Json(name = "appointmentStatus") val appointmentStatus: String? = null,
    @Json(name = "preCheckedIn") val preCheckedIn: Boolean? = null,
    @Json(name = "preCheckinTimestamp") val preCheckinTimestamp: String? = null,
    @Json(name = "questionnaireResponsesSubmitted") val questionnaireResponsesSubmitted: Int? = null,
    @Json(name = "demographicsUpdated") val demographicsUpdated: Boolean? = null
)
