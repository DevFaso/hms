import Foundation

// MARK: - User / Auth Models

struct UserDTO: Codable, Identifiable {
    let id: String?
    let username: String?
    let email: String?
    let firstName: String?
    let lastName: String?
    let role: String?
    let organizationId: String?
    let hospitalId: String?

    var fullName: String {
        [firstName, lastName].compactMap { $0 }.joined(separator: " ")
    }
}

// MARK: - Patient Profile (matches PatientProfileDTO)

struct PatientProfileDTO: Codable, Identifiable {
    var id: String? { patientId }
    let patientId: String?
    let firstName: String?
    let lastName: String?
    let dateOfBirth: String?
    let gender: String?
    let email: String?
    let phone: String?
    let address: String?
    let emergencyContactName: String?
    let emergencyContactPhone: String?
    let emergencyContactRelationship: String?
    let insuranceProvider: String?
    let insuranceMemberId: String?
    let insurancePlan: String?
    let bloodType: String?
    let allergies: [String]?
    let preferredLanguage: String?
    let primaryCareProvider: String?
    let facility: String?
    let memberSince: String?
    let profileImageUrl: String?

    // Legacy compat
    let phoneNumberPrimary: String?
    let lastVisit: String?
    let addressLine1: String?
    let addressLine2: String?
    let city: String?
    let state: String?
    let zipCode: String?
    let insurancePolicyNumber: String?
    let insuranceGroupNumber: String?
    let insurancePlanType: String?

    var fullName: String {
        [firstName, lastName].compactMap { $0 }.joined(separator: " ")
    }

    var allergiesList: [String] {
        allergies ?? []
    }

    var displayPhone: String? { phone ?? phoneNumberPrimary }
}

// MARK: - Health Summary (matches HealthSummaryDTO)

struct HealthSummaryDTO: Codable {
    let profile: PatientProfileDTO?
    let recentLabResults: [LabResultDTO]?
    let currentMedications: [MedicationDTO]?
    let latestVitals: [VitalSignDTO]?
    let immunizations: [ImmunizationDTO]?
    let allergies: [String]?
    let activeDiagnoses: [String]?

    // Legacy compat
    let recentVitals: VitalSignDTO?
    let upcomingAppointments: [AppointmentDTO]?
    let pendingLabResults: Int?
    let unreadMessages: Int?
    let outstandingBalance: Double?
}
