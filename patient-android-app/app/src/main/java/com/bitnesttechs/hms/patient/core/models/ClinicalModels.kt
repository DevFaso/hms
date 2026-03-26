package com.bitnesttechs.hms.patient.core.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class EncounterDto(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "encounterDate") val encounterDate: String = "",
    @Json(name = "type") val type: String? = null,
    @Json(name = "status") val status: String = "",
    @Json(name = "chiefComplaint") val chiefComplaint: String? = null,
    @Json(name = "diagnosis") val diagnosis: String? = null,
    @Json(name = "doctorName") val doctorName: String? = null,
    @Json(name = "departmentName") val departmentName: String? = null,
    @Json(name = "hospitalName") val hospitalName: String? = null,
    @Json(name = "notes") val notes: String? = null
)

@JsonClass(generateAdapter = true)
data class DischargeSummaryDto(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "admissionDate") val admissionDate: String? = null,
    @Json(name = "dischargeDate") val dischargeDate: String? = null,
    @Json(name = "primaryDiagnosis") val primaryDiagnosis: String? = null,
    @Json(name = "dischargeInstructions") val dischargeInstructions: String? = null,
    @Json(name = "followUpInstructions") val followUpInstructions: String? = null,
    @Json(name = "doctorName") val doctorName: String? = null,
    @Json(name = "departmentName") val departmentName: String? = null
)

@JsonClass(generateAdapter = true)
data class CareTeamDto(
    @Json(name = "primaryPhysician") val primaryPhysician: CareTeamMemberDto? = null,
    @Json(name = "members") val members: List<CareTeamMemberDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class CareTeamMemberDto(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "name") val name: String = "",
    @Json(name = "role") val role: String? = null,
    @Json(name = "specialty") val specialty: String? = null,
    @Json(name = "phone") val phone: String? = null,
    @Json(name = "email") val email: String? = null,
    @Json(name = "department") val department: String? = null
)

@JsonClass(generateAdapter = true)
data class DocumentDto(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "name") val name: String = "",
    @Json(name = "type") val type: String? = null,
    @Json(name = "uploadedAt") val uploadedAt: String? = null,
    @Json(name = "fileUrl") val fileUrl: String? = null,
    @Json(name = "fileSize") val fileSize: Long? = null,
    @Json(name = "description") val description: String? = null
)

@JsonClass(generateAdapter = true)
data class NotificationDto(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "title") val title: String = "",
    @Json(name = "message") val message: String = "",
    @Json(name = "type") val type: String? = null,
    @Json(name = "isRead") val isRead: Boolean = false,
    @Json(name = "createdAt") val createdAt: String = "",
    @Json(name = "actionUrl") val actionUrl: String? = null
)

@JsonClass(generateAdapter = true)
data class ChatThreadDto(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "subject") val subject: String = "",
    @Json(name = "participantName") val participantName: String? = null,
    @Json(name = "lastMessage") val lastMessage: String? = null,
    @Json(name = "lastMessageAt") val lastMessageAt: String? = null,
    @Json(name = "unreadCount") val unreadCount: Int = 0
)

@JsonClass(generateAdapter = true)
data class ChatMessageDto(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "threadId") val threadId: Long = 0,
    @Json(name = "content") val content: String = "",
    @Json(name = "senderName") val senderName: String? = null,
    @Json(name = "sentAt") val sentAt: String = "",
    @Json(name = "isFromPatient") val isFromPatient: Boolean = false,
    @Json(name = "isRead") val isRead: Boolean = false
)

@JsonClass(generateAdapter = true)
data class SendMessageRequest(
    @Json(name = "content") val content: String,
    @Json(name = "subject") val subject: String? = null
)

@JsonClass(generateAdapter = true)
data class ReferralDto(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "referralDate") val referralDate: String? = null,
    @Json(name = "referredTo") val referredTo: String? = null,
    @Json(name = "specialty") val specialty: String? = null,
    @Json(name = "reason") val reason: String? = null,
    @Json(name = "status") val status: String = "",
    @Json(name = "notes") val notes: String? = null
)

@JsonClass(generateAdapter = true)
data class TreatmentPlanDto(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "title") val title: String = "",
    @Json(name = "description") val description: String? = null,
    @Json(name = "startDate") val startDate: String? = null,
    @Json(name = "endDate") val endDate: String? = null,
    @Json(name = "status") val status: String = "",
    @Json(name = "goals") val goals: List<String> = emptyList(),
    @Json(name = "createdBy") val createdBy: String? = null
)

@JsonClass(generateAdapter = true)
data class ConsentDto(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "type") val type: String = "",
    @Json(name = "title") val title: String = "",
    @Json(name = "description") val description: String? = null,
    @Json(name = "isGranted") val isGranted: Boolean = false,
    @Json(name = "grantedAt") val grantedAt: String? = null,
    @Json(name = "expiresAt") val expiresAt: String? = null
)

@JsonClass(generateAdapter = true)
data class GrantConsentRequest(
    @Json(name = "granted") val granted: Boolean,
    @Json(name = "notes") val notes: String? = null
)

@JsonClass(generateAdapter = true)
data class AccessLogDto(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "accessedBy") val accessedBy: String = "",
    @Json(name = "accessedAt") val accessedAt: String = "",
    @Json(name = "action") val action: String = "",
    @Json(name = "resourceType") val resourceType: String? = null,
    @Json(name = "ipAddress") val ipAddress: String? = null
)

@JsonClass(generateAdapter = true)
data class ImmunizationDto(
    @Json(name = "id") val id: Long = 0,
    @Json(name = "vaccineName") val vaccineName: String = "",
    @Json(name = "administeredDate") val administeredDate: String? = null,
    @Json(name = "administeredBy") val administeredBy: String? = null,
    @Json(name = "lotNumber") val lotNumber: String? = null,
    @Json(name = "nextDueDate") val nextDueDate: String? = null,
    @Json(name = "notes") val notes: String? = null
)
