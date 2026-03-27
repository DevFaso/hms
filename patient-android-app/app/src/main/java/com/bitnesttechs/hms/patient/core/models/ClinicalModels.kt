package com.bitnesttechs.hms.patient.core.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class EncounterDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "encounterDate") val encounterDate: String = "",
    @Json(name = "type") val type: String? = null,
    @Json(name = "encounterType") val encounterType: String = "",
    @Json(name = "status") val status: String = "",
    @Json(name = "chiefComplaint") val chiefComplaint: String? = null,
    @Json(name = "diagnosis") val diagnosis: String? = null,
    @Json(name = "doctorName") val doctorName: String? = null,
    @Json(name = "department") val department: String? = null,
    @Json(name = "departmentName") val departmentName: String? = null,
    @Json(name = "hospitalName") val hospitalName: String? = null,
    @Json(name = "notes") val notes: String? = null
)

@JsonClass(generateAdapter = true)
data class DischargeSummaryDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "encounterId") val encounterId: String? = null,
    @Json(name = "admissionDate") val admissionDate: String? = null,
    @Json(name = "dischargeDate") val dischargeDate: String? = null,
    @Json(name = "primaryDiagnosis") val primaryDiagnosis: String? = null,
    @Json(name = "instructions") val instructions: String? = null,
    @Json(name = "dischargeInstructions") val dischargeInstructions: String? = null,
    @Json(name = "followUpDate") val followUpDate: String? = null,
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
    @Json(name = "id") val id: String = "",
    @Json(name = "name") val name: String = "",
    @Json(name = "role") val role: String? = null,
    @Json(name = "specialty") val specialty: String? = null,
    @Json(name = "phone") val phone: String? = null,
    @Json(name = "email") val email: String? = null,
    @Json(name = "department") val department: String? = null
)

@JsonClass(generateAdapter = true)
data class DocumentDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "name") val name: String = "",
    @Json(name = "type") val type: String? = null,
    @Json(name = "fileType") val fileType: String? = null,
    @Json(name = "documentType") val documentType: String? = null,
    @Json(name = "uploadedAt") val uploadedAt: String = "",
    @Json(name = "fileUrl") val fileUrl: String? = null,
    @Json(name = "fileSize") val fileSize: Long? = null,
    @Json(name = "description") val description: String? = null
)

@JsonClass(generateAdapter = true)
data class NotificationDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "title") val title: String = "",
    @Json(name = "message") val message: String = "",
    @Json(name = "type") val type: String? = null,
    @Json(name = "isRead") val isRead: Boolean = false,
    @Json(name = "createdAt") val createdAt: String = "",
    @Json(name = "actionUrl") val actionUrl: String? = null
)

@JsonClass(generateAdapter = true)
data class ChatThreadDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "subject") val subject: String = "",
    @Json(name = "participantName") val participantName: String? = null,
    @Json(name = "lastMessage") val lastMessage: String? = null,
    @Json(name = "lastMessageAt") val lastMessageAt: String? = null,
    @Json(name = "unreadCount") val unreadCount: Int = 0
)

@JsonClass(generateAdapter = true)
data class ChatMessageDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "threadId") val threadId: String = "",
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
    @Json(name = "id") val id: String = "",
    @Json(name = "referralDate") val referralDate: String? = null,
    @Json(name = "referralType") val referralType: String? = null,
    @Json(name = "referredTo") val referredTo: String? = null,
    @Json(name = "specialistName") val specialistName: String? = null,
    @Json(name = "specialty") val specialty: String? = null,
    @Json(name = "reason") val reason: String? = null,
    @Json(name = "status") val status: String = "",
    @Json(name = "notes") val notes: String? = null
)

@JsonClass(generateAdapter = true)
data class TreatmentPlanDto(
    @Json(name = "id") val id: String = "",
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
    @Json(name = "id") val id: String = "",
    @Json(name = "type") val type: String = "",
    @Json(name = "consentType") val consentType: String = "",
    @Json(name = "title") val title: String = "",
    @Json(name = "description") val description: String? = null,
    @Json(name = "status") val status: String = "",
    @Json(name = "isGranted") val isGranted: Boolean = false,
    @Json(name = "recipientName") val recipientName: String? = null,
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
    @Json(name = "id") val id: String = "",
    @Json(name = "accessedBy") val accessedBy: String = "",
    @Json(name = "accessedAt") val accessedAt: String = "",
    @Json(name = "action") val action: String = "",
    @Json(name = "resourceType") val resourceType: String? = null,
    @Json(name = "ipAddress") val ipAddress: String? = null
)

@JsonClass(generateAdapter = true)
data class ImmunizationDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "vaccineName") val vaccineName: String = "",
    @Json(name = "administeredDate") val administeredDate: String = "",
    @Json(name = "administeredBy") val administeredBy: String? = null,
    @Json(name = "manufacturer") val manufacturer: String? = null,
    @Json(name = "lotNumber") val lotNumber: String? = null,
    @Json(name = "nextDoseDate") val nextDoseDate: String? = null,
    @Json(name = "nextDueDate") val nextDueDate: String? = null,
    @Json(name = "notes") val notes: String? = null
)
