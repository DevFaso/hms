import Foundation

// MARK: - Medication Models

struct MedicationDTO: Codable, Identifiable {
    let id: Int?
    let medicationName: String?
    let genericName: String?
    let dosage: String?
    let frequency: String?
    let route: String?
    let startDate: String?
    let endDate: String?
    let status: String?
    let prescribedBy: String?
    let instructions: String?
    let refillsRemaining: Int?
}

// MARK: - Prescription Models

struct PrescriptionDTO: Codable, Identifiable {
    let id: Int?
    let medicationName: String?
    let dosage: String?
    let frequency: String?
    let quantity: Int?
    let refills: Int?
    let prescribedDate: String?
    let expiryDate: String?
    let status: String?
    let prescribedBy: String?
    let instructions: String?
    let diagnosisCode: String?
    let diagnosisDescription: String?
}

// MARK: - Refill Models

struct RefillDTO: Codable, Identifiable {
    let id: Int?
    let medicationName: String?
    let requestedDate: String?
    let status: String?
    let pharmacyName: String?
    let notes: String?
}

struct RefillRequest: Encodable {
    let prescriptionId: Int
    let pharmacyId: Int?
    let notes: String?
}
