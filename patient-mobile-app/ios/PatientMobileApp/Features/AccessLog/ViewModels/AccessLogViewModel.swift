import Foundation

@MainActor
final class AccessLogViewModel: ObservableObject {
    @Published var logs: [AccessLogEntry] = []
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var currentPage = 0
    @Published var hasMore = true

    private let pageSize = 20
    private let api = APIClient.shared

    func loadLogs(reset: Bool = false) async {
        if reset {
            currentPage = 0
            hasMore = true
            logs = []
        }
        guard hasMore else { return }

        isLoading = true
        errorMessage = nil
        do {
            let page = try await api.request(
                .accessLog(page: currentPage, size: pageSize),
                type: [AccessLogEntry].self
            )
            if reset {
                logs = page
            } else {
                logs.append(contentsOf: page)
            }
            hasMore = page.count >= pageSize
            currentPage += 1
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }
}

// MARK: - DTOs

struct AccessLogEntry: Decodable, Identifiable {
    let id: String
    let accessedBy: String?
    let accessedByRole: String?
    let accessType: String?
    let resourceType: String?
    let accessDate: String?
    let ipAddress: String?
    let hospitalName: String?
    let description: String?
}
