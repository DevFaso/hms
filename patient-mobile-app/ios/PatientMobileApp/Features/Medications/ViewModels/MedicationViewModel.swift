import Foundation

@MainActor
final class MedicationViewModel: ObservableObject {
    @Published var medications: [MedicationDTO] = []
    @Published var prescriptions: [PrescriptionDTO] = []
    @Published var refills: [RefillDTO] = []
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var selectedSegment = 0

    private let api = APIClient.shared

    func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            async let medsTask = api.request(.medications(limit: 50), type: [MedicationDTO].self)
            async let rxTask = api.request(.prescriptions, type: [PrescriptionDTO].self)
            async let refillsTask = api.request(.refills(page: 0, size: 20), type: [RefillDTO].self)

            medications = try await medsTask
            prescriptions = try await rxTask
            refills = try await refillsTask
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func requestRefill(prescriptionId: String) async {
        do {
            try await api.request(.requestRefill, body: RefillRequest(prescriptionId: prescriptionId))
            await load()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func cancelRefill(id: String) async {
        do {
            try await api.request(.cancelRefill(id: id))
            await load()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - DTOs

struct MedicationDTO: Decodable, Identifiable {
    let id: String
    let name: String?
    let genericName: String?
    let dosage: String?
    let frequency: String?
    let route: String?
    let startDate: String?
    let endDate: String?
    let status: String?
    let prescribedBy: String?
    let instructions: String?

    var isActive: Bool {
        status?.uppercased() == "ACTIVE"
    }
}

struct PrescriptionDTO: Decodable, Identifiable {
    let id: String
    let medicationName: String?
    let dosage: String?
    let frequency: String?
    let quantity: Int?
    let refillsRemaining: Int?
    let prescribedDate: String?
    let expiryDate: String?
    let status: String?
    let prescribedBy: String?
}

struct RefillDTO: Decodable, Identifiable {
    let id: String
    let prescriptionId: String?
    let medicationName: String?
    let status: String?
    let requestedDate: String?
    let completedDate: String?
}

struct RefillRequest: Encodable {
    let prescriptionId: String
}
