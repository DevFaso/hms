import Foundation

// MARK: - User / Auth Models

struct UserDTO: Codable, Identifiable {
    let id: Int?
    let username: String?
    let email: String?
    let firstName: String?
    let lastName: String?
    let role: String?
    let organizationId: Int?
    let hospitalId: Int?

    var fullName: String {
        [firstName, lastName].compactMap { $0 }.joined(separator: " ")
    }
}

// MARK: - Patient Profile

struct PatientProfileDTO: Codable, Identifiable {
    let id: Int?
    let firstName: String?
    let lastName: String?
    let dateOfBirth: String?
    let gender: String?
    let bloodType: String?
    let preferredLanguage: String?
    let phoneNumberPrimary: String?
    let phone: String?
    let email: String?
    let memberSince: String?
    let primaryCareProvider: String?
    let facility: String?
    let lastVisit: String?
    let addressLine1: String?
    let addressLine2: String?
    let city: String?
    let state: String?
    let zipCode: String?
    let allergies: String?          // comma-separated or JSON array
    let emergencyContactName: String?
    let emergencyContactPhone: String?
    let insuranceProvider: String?
    let insurancePolicyNumber: String?
    let insuranceGroupNumber: String?
    let insurancePlanType: String?

    var fullName: String {
        [firstName, lastName].compactMap { $0 }.joined(separator: " ")
    }

    var allergiesList: [String] {
        guard let raw = allergies, !raw.isEmpty else { return [] }
        return raw.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
    }
}

// MARK: - Health Summary

struct HealthSummaryDTO: Codable {
    let allergies: [String]?
    let activeDiagnoses: [String]?
    let currentMedications: [String]?
    let recentVitals: VitalSignDTO?
    let upcomingAppointments: [AppointmentDTO]?
    let pendingLabResults: Int?
    let unreadMessages: Int?
    let outstandingBalance: Double?
}
