import Foundation

// MARK: - Encounter / Visit Models

struct EncounterDTO: Codable, Identifiable, Hashable {
    let id: Int?
    let encounterDate: String?
    let status: String?
    let type: String?
    let reason: String?
    let diagnosis: String?
    let doctorName: String?
    let departmentName: String?
    let hospitalName: String?
    let notes: String?
}

// MARK: - After Visit Summary / Discharge Summary

struct DischargeSummaryDTO: Codable, Identifiable {
    let id: Int?
    let encounterId: Int?
    let dischargeDate: String?
    let diagnosis: String?
    let procedures: String?
    let followUpInstructions: String?
    let medications: [String]?
    let restrictions: String?
    let doctorName: String?
    let hospitalName: String?
}

// MARK: - Care Team

struct CareTeamDTO: Codable {
    let members: [CareTeamMemberDTO]?
}

struct CareTeamMemberDTO: Codable, Identifiable {
    let id: Int?
    let name: String?
    let role: String?
    let specialty: String?
    let phone: String?
    let email: String?
    let hospitalName: String?
    let isPrimary: Bool?
}

// MARK: - Document Models

struct DocumentDTO: Codable, Identifiable {
    let id: Int?
    let fileName: String?
    let fileType: String?
    let fileSize: Int?
    let uploadedAt: String?
    let description: String?
    let category: String?
    let uploadedBy: String?
    let downloadUrl: String?
}

// MARK: - Notification Models

struct NotificationDTO: Codable, Identifiable {
    let id: Int?
    let title: String?
    let message: String?
    let type: String?
    let read: Bool?
    let createdAt: String?
    let actionUrl: String?

    var isRead: Bool { read ?? false }
}

// MARK: - Chat / Message Models

struct ChatThreadDTO: Codable, Identifiable, Hashable {
    let id: String?
    let recipientName: String?
    let recipientRole: String?
    let lastMessage: String?
    let lastMessageAt: String?
    let unreadCount: Int?
}

struct ChatMessageDTO: Codable, Identifiable {
    let id: String?
    let senderId: Int?
    let senderName: String?
    let content: String?
    let sentAt: String?
    let read: Bool?
    let attachmentUrl: String?
}

struct SendMessageRequest: Encodable {
    let content: String
    let attachmentUrl: String?
}

// MARK: - Referral Models

struct ReferralDTO: Codable, Identifiable {
    let id: Int?
    let referralDate: String?
    let status: String?
    let reason: String?
    let fromDoctorName: String?
    let toDoctorName: String?
    let toSpecialty: String?
    let toHospitalName: String?
    let notes: String?
    let urgency: String?
}

// MARK: - Treatment Plan Models

struct TreatmentPlanDTO: Codable, Identifiable {
    let id: Int?
    let title: String?
    let startDate: String?
    let endDate: String?
    let status: String?
    let goals: String?
    let interventions: String?
    let doctorName: String?
    let notes: String?
}

// MARK: - Consent Models

struct ConsentDTO: Codable, Identifiable {
    let id: Int?
    let fromHospitalId: Int?
    let fromHospitalName: String?
    let toHospitalId: Int?
    let toHospitalName: String?
    let grantedAt: String?
    let expiresAt: String?
    let status: String?
}

struct GrantConsentRequest: Encodable {
    let fromHospitalId: Int
    let toHospitalId: Int
    let expiresAt: String?
}

// MARK: - Access Log Models

struct AccessLogDTO: Codable, Identifiable {
    let id: Int?
    let accessedAt: String?
    let accessedBy: String?
    let accessedByRole: String?
    let hospitalName: String?
    let action: String?
    let ipAddress: String?
}

// MARK: - Immunization Models

struct ImmunizationDTO: Codable, Identifiable {
    let id: Int?
    let vaccineName: String?
    let administeredDate: String?
    let doseNumber: Int?
    let nextDueDate: String?
    let administeredBy: String?
    let lotNumber: String?
    let site: String?
    let status: String?
}
