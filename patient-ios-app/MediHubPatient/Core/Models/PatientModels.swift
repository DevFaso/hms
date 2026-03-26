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

// MARK: - Patient Profile (matches PatientPortalController /me/patient/profile)

struct PatientProfileDTO: Codable, Identifiable {
    // API returns "id" directly — also accept "patientId" for compat
    let rawId: String?
    let patientId: String?
    var id: String? { rawId ?? patientId }

    let firstName: String?
    let lastName: String?
    let middleName: String?
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
    let preferredLanguage: String?
    let primaryCareProvider: String?
    let facility: String?
    let memberSince: String?
    let profileImageUrl: String?
    let username: String?
    let country: String?
    let chronicConditions: String?
    let preferredPharmacy: String?
    let mrn: String?

    // Legacy compat fields
    let phoneNumberPrimary: String?
    let phoneNumberSecondary: String?
    let lastVisit: String?
    let addressLine1: String?
    let addressLine2: String?
    let city: String?
    let state: String?
    let zipCode: String?
    let insurancePolicyNumber: String?
    let insuranceGroupNumber: String?
    let insurancePlanType: String?

    // allergies comes as String from /profile, [String] from /health-summary
    let allergies: FlexibleAllergies

    enum CodingKeys: String, CodingKey {
        case rawId = "id"
        case patientId, firstName, lastName, middleName, dateOfBirth, gender
        case email, phone, address
        case emergencyContactName, emergencyContactPhone, emergencyContactRelationship
        case insuranceProvider, insuranceMemberId, insurancePlan
        case bloodType, preferredLanguage, primaryCareProvider
        case facility, memberSince, profileImageUrl, username
        case country, chronicConditions, preferredPharmacy, mrn
        case phoneNumberPrimary, phoneNumberSecondary, lastVisit
        case addressLine1, addressLine2, city, state, zipCode
        case insurancePolicyNumber, insuranceGroupNumber, insurancePlanType
        case allergies
    }

    var fullName: String {
        [firstName, lastName].compactMap { $0 }.joined(separator: " ")
    }

    var allergiesList: [String] {
        allergies.values
    }

    var displayPhone: String? { phone ?? phoneNumberPrimary }

    var displayAddress: String? {
        if let a = address, !a.isEmpty { return a }
        let parts = [addressLine1, city, state, zipCode, country].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: ", ")
    }
}

/// Decodes allergies that can be a String, [String], or null from the API
struct FlexibleAllergies: Codable {
    let values: [String]

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            values = []
        } else if let arr = try? container.decode([String].self) {
            values = arr
        } else if let str = try? container.decode(String.self) {
            values = str.isEmpty ? [] : str.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
        } else {
            values = []
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(values)
    }

    init(values: [String] = []) {
        self.values = values
    }
}

// MARK: - Health Summary (matches HealthSummaryDTO)

struct HealthSummaryDTO: Codable {
    let profile: PatientProfileDTO?
    let recentLabResults: [LabResultDTO]?
    let currentMedications: [MedicationDTO]?
    let recentVitals: [VitalSignDTO]?
    let immunizations: [ImmunizationDTO]?
    let allergies: [String]?
    let activeDiagnoses: [String]?
    let chronicConditions: [String]?

    // Legacy compat
    let latestVitals: [VitalSignDTO]?
    let upcomingAppointments: [AppointmentDTO]?
    let pendingLabResults: Int?
    let unreadMessages: Int?
    let outstandingBalance: Double?

    /// Unified vitals — prefer recentVitals, fall back to latestVitals
    var allVitals: [VitalSignDTO] { recentVitals ?? latestVitals ?? [] }
}
