import Foundation

// MARK: - Medication Models (matches MedicationSummary)

struct MedicationDTO: Codable, Identifiable {
    let id: String?
    let name: String?
    let dosage: String?
    let frequency: String?
    let prescribedBy: String?
    let startDate: String?
    let status: String?

    // Legacy compat
    let medicationName: String?
    let genericName: String?
    let route: String?
    let endDate: String?
    let instructions: String?
    let refillsRemaining: Int?

    /// Display name: prefer `name`, fall back to `medicationName`
    var displayName: String {
        name ?? medicationName ?? "Medication"
    }
}

// MARK: - Prescription Models (matches PortalPrescription)

struct PrescriptionDTO: Codable, Identifiable, Hashable {
    let id: String?
    let medicationName: String?
    let dosage: String?
    let frequency: String?
    let prescribedBy: String?
    let prescribedDate: String?
    let refillsRemaining: Int?
    let status: String?

    // Legacy compat
    let quantity: Int?
    let refills: Int?
    let expiryDate: String?
    let instructions: String?
    let diagnosisCode: String?
    let diagnosisDescription: String?
}

// MARK: - Refill Models (matches MedicationRefill)

struct RefillDTO: Codable, Identifiable {
    let id: String?
    let prescriptionId: String?
    let medicationName: String?
    let dosage: String?
    let preferredPharmacy: String?
    let status: String?
    let requestedAt: String?
    let completedAt: String?
    let notes: String?
}

struct RefillRequest: Encodable {
    let prescriptionId: String
    let preferredPharmacy: String?
    let notes: String?
}
