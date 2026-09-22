package com.bitnesttechs.hms.patient.core.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** GET /me/patient/education (PatientEducationItemDTO): a resource assigned to the patient, with their progress. */
@JsonClass(generateAdapter = true)
data class EducationItemDto(
    @Json(name = "resourceId") val resourceId: String,
    @Json(name = "progressId") val progressId: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "resourceType") val resourceType: String? = null,
    @Json(name = "category") val category: String? = null,
    @Json(name = "comprehensionStatus") val comprehensionStatus: String? = null,
    @Json(name = "progressPercentage") val progressPercentage: Int? = null,
    @Json(name = "startedAt") val startedAt: String? = null,
    @Json(name = "completedAt") val completedAt: String? = null,
    @Json(name = "lastAccessedAt") val lastAccessedAt: String? = null,
    @Json(name = "rating") val rating: Int? = null,
    @Json(name = "feedback") val feedback: String? = null,
    @Json(name = "needsClarification") val needsClarification: Boolean? = null,
    @Json(name = "clarificationRequest") val clarificationRequest: String? = null,
    @Json(name = "confirmedUnderstanding") val confirmedUnderstanding: Boolean? = null,
    @Json(name = "contentUrl") val contentUrl: String? = null,
    @Json(name = "textContent") val textContent: String? = null,
    @Json(name = "thumbnailUrl") val thumbnailUrl: String? = null,
    @Json(name = "videoUrl") val videoUrl: String? = null,
    @Json(name = "estimatedDuration") val estimatedDuration: Int? = null,
    @Json(name = "tags") val tags: List<String>? = null,
    @Json(name = "primaryLanguage") val primaryLanguage: String? = null,
    @Json(name = "isWarningSignContent") val isWarningSignContent: Boolean? = null
) {
    val isCompleted: Boolean get() = !completedAt.isNullOrBlank()
    val hasContent: Boolean get() = !textContent.isNullOrBlank() || !videoUrl.isNullOrBlank() || !contentUrl.isNullOrBlank()
}

/** PUT /me/patient/education/{id}/progress (PatientEducationProgressUpdateDTO); every field optional. */
@JsonClass(generateAdapter = true)
data class EducationProgressUpdate(
    @Json(name = "progressPercentage") val progressPercentage: Int? = null,
    @Json(name = "rating") val rating: Int? = null,
    @Json(name = "feedback") val feedback: String? = null,
    @Json(name = "confirmedUnderstanding") val confirmedUnderstanding: Boolean? = null,
    @Json(name = "needsClarification") val needsClarification: Boolean? = null,
    @Json(name = "clarificationRequest") val clarificationRequest: String? = null
)

/** GET/POST /me/patient/education/questions (PatientEducationQuestionResponseDTO). */
@JsonClass(generateAdapter = true)
data class EducationQuestionDto(
    @Json(name = "id") val id: String,
    @Json(name = "resourceId") val resourceId: String? = null,
    @Json(name = "questionText") val questionText: String? = null,
    @Json(name = "isUrgent") val isUrgent: Boolean? = null,
    @Json(name = "isAnswered") val isAnswered: Boolean? = null,
    @Json(name = "answerText") val answerText: String? = null,
    @Json(name = "answeredAt") val answeredAt: String? = null,
    @Json(name = "requiresInPersonDiscussion") val requiresInPersonDiscussion: Boolean? = null,
    @Json(name = "appointmentScheduled") val appointmentScheduled: Boolean? = null,
    @Json(name = "createdAt") val createdAt: String? = null
)

/** PatientEducationQuestionSubmitDTO: questionText 5..2000, resourceId optional (a general question). */
@JsonClass(generateAdapter = true)
data class EducationQuestionSubmit(
    @Json(name = "resourceId") val resourceId: String? = null,
    @Json(name = "questionText") val questionText: String,
    @Json(name = "isUrgent") val isUrgent: Boolean = false
)
