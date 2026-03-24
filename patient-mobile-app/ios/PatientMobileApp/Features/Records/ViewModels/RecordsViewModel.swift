import Foundation

@MainActor
final class RecordsViewModel: ObservableObject {
    @Published var encounters: [EncounterDTO] = []
    @Published var visitSummaries: [VisitSummaryDTO] = []
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var selectedSegment = 0

    private let api = APIClient.shared

    func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            async let encountersTask = api.request(.encounters, type: [EncounterDTO].self)
            async let summariesTask = api.request(.afterVisitSummaries, type: [VisitSummaryDTO].self)

            encounters = try await encountersTask
            visitSummaries = try await summariesTask
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - DTOs

struct EncounterDTO: Decodable, Identifiable {
    let id: String
    let encounterDate: String?
    let encounterType: String?
    let status: String?
    let chiefComplaint: String?
    let diagnosis: String?
    let doctorName: String?
    let departmentName: String?
    let hospitalName: String?
    let notes: String?

    var displayDate: String {
        guard let dateStr = encounterDate else { return "N/A" }
        let inFmt = DateFormatter()
        inFmt.dateFormat = "yyyy-MM-dd"
        guard let date = inFmt.date(from: dateStr) else { return dateStr }
        let outFmt = DateFormatter()
        outFmt.dateStyle = .medium
        return outFmt.string(from: date)
    }
}

struct VisitSummaryDTO: Decodable, Identifiable {
    let id: String
    let visitDate: String?
    let summary: String?
    let diagnosis: String?
    let instructions: String?
    let followUpDate: String?
    let doctorName: String?
}
