package com.bitnesttechs.hms.patient.core.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * GET /me/patient/medical-history (PatientDiagnosisSummaryDTO, wrapped).
 * Problem-list entries and diagnoses merged newest first; read-only for the
 * patient, exactly as the web's My Medical History.
 */
@JsonClass(generateAdapter = true)
data class PatientDiagnosisSummary(
    @Json(name = "id") val id: String,
    @Json(name = "description") val description: String? = null,
    @Json(name = "icdCode") val icdCode: String? = null,
    @Json(name = "status") val status: String? = null,
    /** "yyyy-MM-dd'T'HH:mm:ssXXX" */
    @Json(name = "diagnosedAt") val diagnosedAt: String? = null,
    @Json(name = "diagnosedByName") val diagnosedByName: String? = null
)

/** GET /me/patient/surgical-history (PatientSurgicalHistoryResponseDTO, wrapped, NON_NULL fields only). */
@JsonClass(generateAdapter = true)
data class SurgicalHistoryEntry(
    @Json(name = "id") val id: String,
    @Json(name = "patientId") val patientId: String? = null,
    @Json(name = "hospitalId") val hospitalId: String? = null,
    @Json(name = "hospitalName") val hospitalName: String? = null,
    @Json(name = "procedureCode") val procedureCode: String? = null,
    @Json(name = "procedureDisplay") val procedureDisplay: String? = null,
    /** "yyyy-MM-dd" */
    @Json(name = "procedureDate") val procedureDate: String? = null,
    @Json(name = "outcome") val outcome: String? = null,
    @Json(name = "performedBy") val performedBy: String? = null,
    @Json(name = "location") val location: String? = null,
    @Json(name = "notes") val notes: String? = null
)

/** GET /me/patient/family-history (FamilyHistoryResponseDTO, wrapped). Only the fields the web shows are modelled. */
@JsonClass(generateAdapter = true)
data class FamilyHistoryEntry(
    @Json(name = "id") val id: String,
    @Json(name = "relationship") val relationship: String? = null,
    @Json(name = "relativeName") val relativeName: String? = null,
    @Json(name = "relativeGender") val relativeGender: String? = null,
    @Json(name = "relativeLiving") val relativeLiving: Boolean? = null,
    @Json(name = "relativeAge") val relativeAge: Int? = null,
    @Json(name = "relativeAgeAtDeath") val relativeAgeAtDeath: Int? = null,
    @Json(name = "causeOfDeath") val causeOfDeath: String? = null,
    @Json(name = "conditionCode") val conditionCode: String? = null,
    @Json(name = "conditionDisplay") val conditionDisplay: String? = null,
    @Json(name = "conditionCategory") val conditionCategory: String? = null,
    @Json(name = "ageAtOnset") val ageAtOnset: Int? = null,
    @Json(name = "severity") val severity: String? = null,
    @Json(name = "notes") val notes: String? = null,
    @Json(name = "active") val active: Boolean? = null
)

/**
 * GET /me/patient/social-history (SocialHistoryResponseDTO, wrapped; `data`
 * is null when nothing active is on record). Only the fields the web shows.
 */
@JsonClass(generateAdapter = true)
data class SocialHistory(
    @Json(name = "id") val id: String? = null,
    @Json(name = "tobaccoUse") val tobaccoUse: Boolean? = null,
    @Json(name = "tobaccoType") val tobaccoType: String? = null,
    @Json(name = "tobaccoPacksPerDay") val tobaccoPacksPerDay: Double? = null,
    @Json(name = "tobaccoYearsUsed") val tobaccoYearsUsed: Int? = null,
    /** "yyyy-MM-dd" */
    @Json(name = "tobaccoQuitDate") val tobaccoQuitDate: String? = null,
    @Json(name = "tobaccoNotes") val tobaccoNotes: String? = null,
    @Json(name = "alcoholUse") val alcoholUse: Boolean? = null,
    @Json(name = "alcoholFrequency") val alcoholFrequency: String? = null,
    @Json(name = "alcoholDrinksPerWeek") val alcoholDrinksPerWeek: Int? = null,
    @Json(name = "alcoholBingeDrinking") val alcoholBingeDrinking: Boolean? = null,
    @Json(name = "alcoholNotes") val alcoholNotes: String? = null,
    @Json(name = "recreationalDrugUse") val recreationalDrugUse: Boolean? = null,
    @Json(name = "exerciseFrequency") val exerciseFrequency: String? = null,
    @Json(name = "exerciseType") val exerciseType: String? = null,
    @Json(name = "occupation") val occupation: String? = null,
    @Json(name = "employmentStatus") val employmentStatus: String? = null,
    @Json(name = "maritalStatus") val maritalStatus: String? = null,
    @Json(name = "active") val active: Boolean? = null
)
