import Foundation

// MARK: - Encounter / Visit Models

struct EncounterDTO: Codable, Identifiable, Hashable {
    let id: String?

    // Backend field names (EncounterResponseDTO.java)
    let encounterDate: String?
    let encounterType: String?
    let staffName: String?
    let departmentName: String?
    let chiefComplaint: String?
    let diagnosisSummary: String?
    let status: String?
    let notes: String?
    let hospitalName: String?
    let appointmentReason: String?

    // Computed aliases used by views
    var date: String? { encounterDate }
    var type: String? { encounterType?.replacingOccurrences(of: "_", with: " ") }
    var providerName: String? { staffName }
    var department: String? { departmentName }
    var reason: String? { chiefComplaint ?? appointmentReason }
    var diagnosis: String? { diagnosisSummary }
    var doctorName: String? { staffName }
}

// MARK: - After Visit Summary

struct AfterVisitSummaryDTO: Codable, Identifiable {
    let id: String?

    // Patient
    let patientName: String?
    let patientMrn: String?

    // Encounter
    let encounterId: String?
    let encounterType: String?

    // Hospital
    let hospitalName: String?

    // Provider
    let dischargingProviderName: String?

    // Dates
    let dischargeDate: String?
    let dischargeTime: String?

    // Disposition / diagnoses
    let disposition: String?
    let dischargeDiagnosis: String?
    let hospitalCourse: String?
    let dischargeCondition: String?

    // Instructions
    let activityRestrictions: String?
    let dietInstructions: String?
    let woundCareInstructions: String?
    let followUpInstructions: String?
    let warningSigns: String?
    let patientEducationProvided: String?

    // Medication reconciliation (keep generic for now)
    let medicationReconciliation: [MedicationReconciliationDTO]?
    let pendingTestResults: [PendingTestResultDTO]?
    let followUpAppointments: [FollowUpAppointmentDTO]?
    let equipmentAndSupplies: [String]?

    // Finalization
    let isFinalized: Bool?
    let finalizedAt: String?
    let additionalNotes: String?

    // Convenience aliases for views
    var providerName: String? { dischargingProviderName }
    var encounterDate: String? { dischargeDate ?? dischargeTime }
    var department: String? { nil }
    var chiefComplaint: String? { hospitalCourse }
    var diagnoses: [String]? {
        guard let d = dischargeDiagnosis, !d.isEmpty else { return nil }
        return [d]
    }
    var treatmentSummary: String? { hospitalCourse }
    var instructions: String? { followUpInstructions }
    var followUpDate: String? {
        followUpAppointments?.first?.appointmentDate
    }
    var medications: [String]? {
        medicationReconciliation?.compactMap {
            [$0.medicationName, $0.dosage, $0.frequency].compactMap { $0 }.joined(separator: " ")
        }
    }
    var status: String? {
        isFinalized == true ? "Finalized" : "Draft"
    }
}

// MARK: - Discharge sub-DTOs

struct MedicationReconciliationDTO: Codable {
    let medicationName: String?
    let dosage: String?
    let frequency: String?
    let route: String?
    let action: String? // CONTINUE, STOP, NEW, MODIFIED
}

struct PendingTestResultDTO: Codable {
    let testName: String?
    let expectedDate: String?
    let notes: String?
}

struct FollowUpAppointmentDTO: Codable {
    let providerName: String?
    let specialty: String?
    let appointmentDate: String?
    let notes: String?
}

// MARK: - Care Team (matches backend CareTeamDTO.java)

struct CareTeamDTO: Codable {
    let primaryCare: PrimaryCareEntry?
    let primaryCareHistory: [PrimaryCareEntry]?

    /// Flatten into displayable members list
    var allMembers: [PrimaryCareEntry] {
        var list: [PrimaryCareEntry] = []
        if let pc = primaryCare { list.append(pc) }
        if let history = primaryCareHistory {
            list.append(contentsOf: history.filter { !$0.current })
        }
        return list
    }
}

struct PrimaryCareEntry: Codable, Identifiable {
    let id: String?
    let hospitalId: String?
    let hospitalName: String?
    let doctorUserId: String?
    let doctorDisplay: String?
    let startDate: String?
    let endDate: String?
    let current: Bool

    // Convenience for views
    var displayName: String { doctorDisplay ?? "Provider" }
}

// Legacy — kept so CareTeamMemberRow still compiles if referenced
struct CareTeamMemberDTO: Codable, Identifiable {
    var id: String? { name }
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

    var isRead: Bool {
        read ?? false
    }
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
    let permissions: String?
    let expiresAt: String?
    let revokedAt: String?
    let notes: String?
    let createdAt: String?

    /// Convenience: split comma-separated permissions into an array for UI display
    var permissionsList: [String] {
        permissions?.split(separator: ",").map { String($0).trimmingCharacters(in: .whitespaces) } ?? []
    }
}

struct ProxyGrantRequest: Encodable {
    let proxyUsername: String
    let relationship: String
    let permissions: String // comma-separated: "VIEW_RECORDS,VIEW_APPOINTMENTS"
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
