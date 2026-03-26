import Foundation

// MARK: - Encounter / Visit Models

struct EncounterDTO: Codable, Identifiable, Hashable {
    let id: String?
    let date: String?
    let type: String?
    let providerName: String?
    let department: String?
    let chiefComplaint: String?
    let diagnosisSummary: String?
    let status: String?

    // Legacy compat aliases
    var encounterDate: String? { date }
    var doctorName: String? { providerName }
    var departmentName: String? { department }
    var reason: String? { chiefComplaint }
    var diagnosis: String? { diagnosisSummary }
}

// MARK: - After Visit Summary

struct AfterVisitSummaryDTO: Codable, Identifiable {
    let id: String?
    let encounterDate: String?
    let providerName: String?
    let department: String?
    let chiefComplaint: String?
    let diagnoses: [String]?
    let treatmentSummary: String?
    let instructions: String?
    let followUpDate: String?
    let medications: [String]?
    let status: String?
}

// MARK: - Care Team

struct CareTeamDTO: Codable {
    let members: [CareTeamMemberDTO]?
}

struct CareTeamMemberDTO: Codable, Identifiable {
    var id: String? { name }   // use name as stable id
    let name: String?
    let role: String?
    let specialty: String?
    let phone: String?
    let email: String?
    let isPrimary: Bool?
}

// MARK: - Document Models

struct DocumentDTO: Codable, Identifiable {
    let id: String?
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
    let id: String?
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
    let senderId: String?
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
    let id: String?
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
    let id: String?
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
    let id: String?
    let fromHospitalId: String?
    let fromHospitalName: String?
    let toHospitalId: String?
    let toHospitalName: String?
    let consentType: String?
    let purpose: String?
    let grantedAt: String?
    let expiresAt: String?
    let status: String?
}

struct GrantConsentRequest: Encodable {
    let fromHospitalId: String
    let toHospitalId: String
    let purpose: String?
    let consentExpiration: String?
}

// MARK: - Access Log Models

struct AccessLogDTO: Codable, Identifiable {
    let id: String?
    let accessedBy: String?
    let accessedByRole: String?
    let accessType: String?
    let resourceAccessed: String?
    let accessedAt: String?
    let ipAddress: String?
}

// MARK: - Immunization Models

struct ImmunizationDTO: Codable, Identifiable {
    let id: String?
    let vaccineName: String?
    let dateAdministered: String?
    let provider: String?
    let status: String?
}

// MARK: - Proxy / Family Access Models

struct ProxyResponse: Codable, Identifiable {
    let id: String?
    let grantorPatientId: String?
    let grantorName: String?
    let proxyUserId: String?
    let proxyUsername: String?
    let proxyDisplayName: String?
    let relationship: String?
    let status: String?
    let permissions: [String]?
    let expiresAt: String?
    let revokedAt: String?
    let notes: String?
    let createdAt: String?
}

struct ProxyGrantRequest: Encodable {
    let proxyUsername: String
    let relationship: String
    let permissions: [String]
    let expiresAt: String?
    let notes: String?
}

// MARK: - Patient Payment

struct PatientPaymentRequest: Encodable {
    let amount: Double
    let paymentMethod: String
    let transactionReference: String?
    let notes: String?
}
