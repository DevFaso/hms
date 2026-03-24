import Foundation

// MARK: - DTOs

struct DocumentDto: Codable, Identifiable {
    let id: String
    let documentName: String?
    let documentType: String?
    let category: String?
    let description: String?
    let fileName: String?
    let fileSize: Int?
    let mimeType: String?
    let uploadedBy: String?
    let uploadedByRole: String?
    let hospitalName: String?
    let departmentName: String?
    let status: String?
    let isConfidential: Bool?
    let uploadDate: String?
    let createdAt: String?
}

struct DocumentPage: Codable {
    let content: [DocumentDto]
    let totalPages: Int
    let totalElements: Int
    let number: Int
}

// MARK: - ViewModel

@MainActor
final class DocumentsViewModel: ObservableObject {
    @Published var documents: [DocumentDto] = []
    @Published var isLoading = false
    @Published var error: String?
    @Published var hasMore = true

    private var currentPage = 0
    private let pageSize = 20
    private let cache = CacheManager.shared

    func loadDocuments(reset: Bool = false) async {
        if reset {
            currentPage = 0
            hasMore = true
            documents = []
        }
        guard hasMore, !isLoading else { return }
        isLoading = true
        error = nil

        do {
            let page: DocumentPage = try await APIClient.shared.request(
                .documents(page: currentPage, size: pageSize),
                type: DocumentPage.self
            )
            if reset { documents = page.content } else { documents.append(contentsOf: page.content) }
            hasMore = currentPage < page.totalPages - 1
            currentPage += 1
            cache.store(documents, forKey: "documents")
        } catch {
            if documents.isEmpty, let cached = cache.retrieve(forKey: "documents", as: [DocumentDto].self) {
                documents = cached
            }
            self.error = error.localizedDescription
        }
        isLoading = false
    }

    var fileSizeFormatted: (Int?) -> String {
        { bytes in
            guard let bytes else { return "" }
            if bytes < 1024 { return "\(bytes) B" }
            if bytes < 1_048_576 { return "\(bytes / 1024) KB" }
            return String(format: "%.1f MB", Double(bytes) / 1_048_576)
        }
    }

    var documentIcon: (String?) -> String {
        { mimeType in
            guard let mime = mimeType?.lowercased() else { return "doc" }
            if mime.contains("pdf") { return "doc.richtext" }
            if mime.contains("image") { return "photo" }
            if mime.contains("dicom") { return "waveform.path.ecg" }
            if mime.contains("word") || mime.contains("document") { return "doc.text" }
            if mime.contains("spreadsheet") || mime.contains("excel") { return "tablecells" }
            return "doc"
        }
    }
}
