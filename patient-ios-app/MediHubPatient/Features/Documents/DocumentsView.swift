import PhotosUI
import QuickLook
import SwiftUI
import UniformTypeIdentifiers

// MARK: - Document types (PatientDocumentType on the backend)

enum PatientDocumentType: String, CaseIterable, Identifiable {
    case labResult = "LAB_RESULT"
    case imagingReport = "IMAGING_REPORT"
    case dischargeSummary = "DISCHARGE_SUMMARY"
    case referralLetter = "REFERRAL_LETTER"
    case prescription = "PRESCRIPTION"
    case insuranceDocument = "INSURANCE_DOCUMENT"
    case invoice = "INVOICE"
    case immunizationRecord = "IMMUNIZATION_RECORD"
    case other = "OTHER"

    var id: String { rawValue }

    var label: String { "document_type_\(rawValue.lowercased())".localized }

    /// The list shows the server's enum as a label; an unknown value is
    /// shown as-is rather than hidden.
    static func label(for raw: String?) -> String? {
        guard let raw else { return nil }
        return PatientDocumentType(rawValue: raw)?.label ?? raw
    }
}

// MARK: - Upload rules (mirrors FileUploadService.validatePatientDocumentFile)

enum DocumentUploadRules {
    /// `FileUploadService.ALLOWED_ATTACHMENT_EXTENSIONS` — the server checks
    /// the extension, not the media type.
    static let allowedExtensions: Set<String> = [
        "pdf", "jpg", "jpeg", "png", "gif", "bmp", "tiff", "txt", "rtf", "doc", "docx",
    ]

    /// The service allows 20 MB but `spring.servlet.multipart.max-file-size`
    /// is 10 MB, so 10 MB is what actually gets through.
    static let maxMegabytes = 10
    static let maxBytes = maxMegabytes * 1024 * 1024

    /// `patient_uploaded_documents.notes` is varchar(2048); the DTO itself
    /// does not check, so the app does.
    static let maxNotesLength = 2048

    static var contentTypes: [UTType] {
        allowedExtensions.sorted().compactMap { UTType(filenameExtension: $0) }
    }

    static func mimeType(forExtension ext: String) -> String {
        UTType(filenameExtension: ext)?.preferredMIMEType ?? "application/octet-stream"
    }
}

/// A file read from the picker and waiting for its metadata.
struct PendingDocument: Identifiable {
    let id = UUID()
    let fileName: String
    let mimeType: String
    let data: Data
}

// MARK: - Documents

struct DocumentsView: View {
    var embeddedInNav: Bool = true
    @StateObject private var vm = DocumentsViewModel()
    @State private var showFileImporter = false
    @State private var showPhotoPicker = false
    @State private var selectedPhoto: PhotosPickerItem?

    var body: some View {
        if embeddedInNav {
            NavigationStack { content }
                .task { await vm.load() }
        } else {
            content
                .task { await vm.load() }
        }
    }

    private var content: some View {
        Group {
            if vm.isLoading, vm.documents.isEmpty { ProgressView("loading".localized) }
            else if let error = vm.errorMessage, vm.documents.isEmpty {
                ContentUnavailableView {
                    Label("documents_load_failed".localized, systemImage: "wifi.exclamationmark")
                } description: {
                    Text(error)
                } actions: {
                    Button("retry".localized) { Task { await vm.load() } }
                }
            } else if vm.documents.isEmpty {
                ContentUnavailableView("no_documents".localized, systemImage: "doc.fill",
                                       description: Text("no_documents_desc".localized))
            } else {
                List(vm.documents) { doc in
                    DocumentRow(doc: doc, isOpening: vm.openingId == doc.id, isDeleting: vm.deletingId == doc.id) {
                        Task { await vm.open(doc) }
                    }
                    .disabled(vm.openingId != nil || vm.deletingId != nil)
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button(role: .destructive) {
                            vm.deleteCandidate = doc
                        } label: {
                            Label("delete".localized, systemImage: "trash")
                        }
                        .disabled(vm.deletingId != nil)
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
        .navigationTitle("documents".localized)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button {
                        showFileImporter = true
                    } label: {
                        Label("choose_file".localized, systemImage: "folder")
                    }
                    Button {
                        showPhotoPicker = true
                    } label: {
                        Label("choose_photo".localized, systemImage: "photo")
                    }
                } label: {
                    Label("upload_document".localized, systemImage: "plus")
                }
                .disabled(vm.pending != nil)
            }
        }
        .refreshable { await vm.load() }
        .fileImporter(isPresented: $showFileImporter,
                      allowedContentTypes: DocumentUploadRules.contentTypes,
                      allowsMultipleSelection: false) { result in
            switch result {
            case .success(let urls):
                if let url = urls.first { vm.stage(fileURL: url) }
            case .failure(let error):
                vm.actionError = error.localizedDescription
            }
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $selectedPhoto, matching: .images)
        .onChange(of: selectedPhoto) { _, newItem in
            guard let newItem else { return }
            selectedPhoto = nil
            Task { await vm.stage(photo: newItem) }
        }
        .sheet(item: $vm.pending, onDismiss: { vm.uploadSheetDismissed() }) { staged in
            DocumentUploadSheet(vm: vm, staged: staged)
        }
        .sheet(item: $vm.preview, onDismiss: { vm.discardPreview() }) { item in
            DocumentPreview(url: item.url)
                .ignoresSafeArea()
        }
        .confirmationDialog("delete_document".localized,
                            isPresented: Binding(
                                get: { vm.deleteCandidate != nil },
                                set: { if !$0 { vm.deleteCandidate = nil } }
                            ),
                            titleVisibility: .visible,
                            presenting: vm.deleteCandidate) { doc in
            Button("delete".localized, role: .destructive) { Task { await vm.delete(doc) } }
            Button("cancel".localized, role: .cancel) {}
        } message: { _ in
            Text("document_delete_confirm".localized)
        }
        .alert("document_open_failed".localized, isPresented: Binding(
            get: { vm.openError != nil },
            set: { if !$0 { vm.openError = nil } }
        )) {
            Button("ok".localized, role: .cancel) {}
        } message: {
            Text(vm.openError ?? "")
        }
        .alert("error".localized, isPresented: Binding(
            get: { vm.actionError != nil },
            set: { if !$0 { vm.actionError = nil } }
        )) {
            Button("ok".localized, role: .cancel) {}
        } message: {
            Text(vm.actionError ?? "")
        }
        .alert(vm.successMessage ?? "", isPresented: Binding(
            get: { vm.successMessage != nil },
            set: { if !$0 { vm.successMessage = nil } }
        )) {
            Button("ok".localized, role: .cancel) {}
        }
    }
}

private struct DocumentRow: View {
    let doc: DocumentDTO
    let isOpening: Bool
    let isDeleting: Bool
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(doc.title).font(.headline)
                    if let kind = PatientDocumentType.label(for: doc.kind) {
                        Text(kind).font(.caption).foregroundColor(.secondary)
                    }
                    HStack(spacing: 8) {
                        if let date = doc.date { Text(date) }
                        if let bytes = doc.fileSizeBytes ?? doc.fileSize {
                            Text(DocumentsViewModel.formatSize(bytes))
                        }
                    }
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    if let notes = doc.notes, !notes.isEmpty {
                        Text(notes).font(.caption).foregroundColor(.secondary).lineLimit(2)
                    }
                }
                Spacer()
                if isOpening || isDeleting {
                    ProgressView()
                } else {
                    Image(systemName: "arrow.up.right.square").foregroundColor(.accentColor)
                }
            }
            .padding(.vertical, 4)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Upload sheet (the web's upload form: type, collection date, notes)

private struct DocumentUploadSheet: View {
    @ObservedObject var vm: DocumentsViewModel
    let staged: PendingDocument

    var body: some View {
        NavigationStack {
            Form {
                Section("selected_file".localized) {
                    Label(staged.fileName, systemImage: "doc")
                    Text(DocumentsViewModel.formatSize(staged.data.count))
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                Section {
                    Picker("document_type".localized, selection: $vm.documentType) {
                        ForEach(PatientDocumentType.allCases) { type in
                            Text(type.label).tag(type)
                        }
                    }
                }
                Section {
                    Toggle("collection_date_optional".localized, isOn: $vm.hasCollectionDate)
                    if vm.hasCollectionDate {
                        DatePicker("date".localized, selection: $vm.collectionDate, displayedComponents: .date)
                    }
                }
                Section {
                    TextField("notes_placeholder".localized, text: $vm.notes, axis: .vertical)
                        .lineLimit(2 ... 6)
                } header: {
                    Text("notes_optional".localized)
                } footer: {
                    Text("\(vm.notes.count)/\(DocumentUploadRules.maxNotesLength)")
                        .foregroundColor(vm.notes.count > DocumentUploadRules.maxNotesLength ? .red : .secondary)
                }
                if let error = vm.uploadError {
                    Section {
                        Text(error).foregroundColor(.red)
                    }
                }
            }
            .disabled(vm.isUploading)
            .navigationTitle("upload_document".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("cancel".localized) { vm.cancelUpload() }
                        .disabled(vm.isUploading)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if vm.isUploading {
                        ProgressView()
                    } else {
                        Button("upload".localized) { Task { await vm.upload() } }
                    }
                }
            }
        }
        // A closed sheet cannot abandon a request the server may already have
        // stored: the upload runs in the view model, and dismissal waits.
        .interactiveDismissDisabled(vm.isUploading)
    }
}

/// A downloaded document, kept only for the lifetime of the preview.
struct DocumentPreviewItem: Identifiable {
    let id = UUID()
    let url: URL
}

/// QuickLook renders PDFs, images and office files without an extra viewer.
struct DocumentPreview: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: QLPreviewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(url: url) }

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL
        init(url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
            url as NSURL
        }
    }
}

// MARK: - View model

@MainActor
final class DocumentsViewModel: ObservableObject {
    @Published var documents: [DocumentDTO] = []
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var openingId: String?
    @Published var preview: DocumentPreviewItem?
    @Published var openError: String?

    // Upload
    @Published var pending: PendingDocument?
    @Published var documentType: PatientDocumentType = .other
    @Published var hasCollectionDate = false
    @Published var collectionDate = Date()
    @Published var notes = ""
    @Published var isUploading = false
    @Published var uploadError: String?

    // Delete
    @Published var deleteCandidate: DocumentDTO?
    @Published var deletingId: String?

    /// Picker, read and delete failures — shown under one "Error" title.
    @Published var actionError: String?
    /// The web's success toasts; here an alert, raised after the sheet closes.
    @Published var successMessage: String?
    private var successPending: String?

    /// Kept apart from `preview`: SwiftUI nils the sheet's item before the
    /// onDismiss callback runs, so the URL must survive that for the delete.
    private var previewFileURL: URL?

    private static var previewDirectory: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("documents", isDirectory: true)
    }

    /// `collectionDate` is a LocalDate on the wire (`LocalDate.parse`).
    private static let wireDate: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .iso8601)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    func load() async {
        // Anything left from an earlier preview (a crash, a kill mid-preview)
        // is PHI in tmp; sweep it before showing the list.
        try? FileManager.default.removeItem(at: Self.previewDirectory)
        isLoading = true
        errorMessage = nil
        do {
            let page: PageDTO<DocumentDTO> = try await APIClient.shared.get(
                APIEndpoints.documents,
                queryItems: [URLQueryItem(name: "page", value: "0"),
                             URLQueryItem(name: "size", value: "50")]
            )
            documents = page.content
        } catch {
            // The list endpoint pages; a bare array is not a shape it returns.
            // Any failure is shown as one, not as "no documents".
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }

    /// Downloads through the authenticated client into a temp file for
    /// QuickLook. `downloadUrl` was decoded and never used: the bytes are
    /// only served to their owner, so an external viewer cannot fetch them.
    func open(_ doc: DocumentDTO) async {
        guard let id = doc.id, openingId == nil else { return }
        openingId = id
        defer { openingId = nil }
        do {
            let (data, mime) = try await APIClient.shared.downloadFile(APIEndpoints.documentDownload(id: id))
            // QuickLook picks its renderer from the extension, so one is
            // derived from the server's media type first (the stored mimeType
            // is what the uploader declared), then from the display name. The
            // id keeps two documents with the same name apart, and the file
            // lives in tmp for the preview's lifetime (see discardPreview).
            let ext = Self.fileExtension(mimeType: mime ?? doc.mimeType, name: doc.title)
            let name = ext.isEmpty ? id : "\(id).\(ext)"
            let url = Self.previewDirectory.appendingPathComponent(name)
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try data.write(to: url, options: [.atomic, .completeFileProtection])
            previewFileURL = url
            preview = DocumentPreviewItem(url: url)
        } catch {
            openError = error.localizedDescription
        }
    }

    static func fileExtension(mimeType: String?, name: String) -> String {
        if let mime = mimeType, mime != "application/octet-stream",
           let ext = UTType(mimeType: mime)?.preferredFilenameExtension {
            return ext
        }
        return (name as NSString).pathExtension.lowercased()
    }

    static func formatSize(_ bytes: Int) -> String {
        String(format: "file_size_kb".localized, String(format: "%.1f", Double(bytes) / 1024))
    }

    /// PHI does not outlive the preview.
    func discardPreview() {
        if let url = previewFileURL {
            try? FileManager.default.removeItem(at: url)
        }
        previewFileURL = nil
        preview = nil
    }

    // MARK: Upload

    /// A file from the document picker. The URL is security-scoped: it can be
    /// read only between start/stop, and only in this process.
    func stage(fileURL url: URL) {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        let data: Data
        do {
            data = try Data(contentsOf: url)
        } catch {
            actionError = "document_read_failed".localized
            return
        }
        stage(data: data, fileName: url.lastPathComponent)
    }

    /// A photo from the library. The server accepts JPG/PNG/GIF/BMP/TIFF by
    /// extension, so anything else the library holds (HEIC above all) is
    /// re-encoded as JPEG the way the avatar upload does.
    func stage(photo: PhotosPickerItem) async {
        guard let raw = try? await photo.loadTransferable(type: Data.self) else {
            actionError = "photo_process_failed".localized
            return
        }
        let stamp = Self.photoStamp.string(from: Date())
        let accepted: [(UTType, String)] = [(.jpeg, "jpg"), (.png, "png"), (.gif, "gif"), (.tiff, "tiff"), (.bmp, "bmp")]
        let types = photo.supportedContentTypes
        if let match = accepted.first(where: { pair in types.contains { $0.conforms(to: pair.0) } }) {
            stage(data: raw, fileName: "photo-\(stamp).\(match.1)")
            return
        }
        guard let image = UIImage(data: raw), let jpeg = image.jpegData(compressionQuality: 0.9) else {
            actionError = "photo_process_failed".localized
            return
        }
        stage(data: jpeg, fileName: "photo-\(stamp).jpg")
    }

    private static let photoStamp: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMdd-HHmmss"
        return f
    }()

    /// The same checks the server makes, before the bytes leave the phone.
    private func stage(data: Data, fileName: String) {
        let ext = (fileName as NSString).pathExtension.lowercased()
        guard DocumentUploadRules.allowedExtensions.contains(ext) else {
            actionError = "document_type_not_allowed".localized
            return
        }
        guard !data.isEmpty else {
            actionError = "document_empty".localized
            return
        }
        guard data.count <= DocumentUploadRules.maxBytes else {
            actionError = String(format: "document_too_large".localized, DocumentUploadRules.maxMegabytes)
            return
        }
        documentType = .other
        hasCollectionDate = false
        collectionDate = Date()
        notes = ""
        uploadError = nil
        pending = PendingDocument(fileName: fileName,
                                  mimeType: DocumentUploadRules.mimeType(forExtension: ext),
                                  data: data)
    }

    func cancelUpload() {
        guard !isUploading else { return }
        pending = nil
    }

    /// The sheet's onDismiss: the success alert is raised once the
    /// dismissal is over, otherwise SwiftUI drops it.
    func uploadSheetDismissed() {
        if let message = successPending {
            successPending = nil
            successMessage = message
        }
    }

    /// Multipart POST, exactly what the web sends: `file`, `documentType`,
    /// and `collectionDate` / `notes` only when set. The request is the view
    /// model's own task so closing the sheet cannot abandon it.
    func upload() async {
        guard let staged = pending, !isUploading else { return }
        let trimmedNotes = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmedNotes.count <= DocumentUploadRules.maxNotesLength else {
            uploadError = String(format: "notes_too_long".localized, DocumentUploadRules.maxNotesLength)
            return
        }
        var fields = ["documentType": documentType.rawValue]
        if hasCollectionDate { fields["collectionDate"] = Self.wireDate.string(from: collectionDate) }
        if !trimmedNotes.isEmpty { fields["notes"] = trimmedNotes }

        isUploading = true
        uploadError = nil
        let task = Task<Result<DocumentDTO, Error>, Never> {
            do {
                let doc: DocumentDTO = try await APIClient.shared.uploadMultipart(
                    APIEndpoints.documents,
                    fileData: staged.data,
                    fileName: staged.fileName,
                    mimeType: staged.mimeType,
                    fields: fields
                )
                return .success(doc)
            } catch {
                return .failure(error)
            }
        }
        let result = await task.value
        isUploading = false
        switch result {
        case .success:
            successPending = "document_upload_success".localized
            pending = nil
            await load()
        case .failure(let error):
            uploadError = "document_upload_failed".localized + ": " + error.localizedDescription
        }
    }

    // MARK: Delete

    /// Soft delete on the server, owner-checked there. One at a time, in the
    /// view model's own task, so leaving the screen cannot lose the result.
    func delete(_ doc: DocumentDTO) async {
        guard let id = doc.id, deletingId == nil else { return }
        deleteCandidate = nil
        deletingId = id
        let task = Task<Error?, Never> {
            do {
                let _: EmptyResponse = try await APIClient.shared.delete(APIEndpoints.documentById(id: id))
                return nil
            } catch {
                return error
            }
        }
        let failure = await task.value
        deletingId = nil
        if let failure {
            actionError = "document_delete_failed".localized + ": " + failure.localizedDescription
            // Gone already (deleted from the web, say): the list is stale.
            if case APIError.httpError(let status, _) = failure, status == 404 {
                await load()
            }
        } else {
            documents.removeAll { $0.id == id }
            successMessage = "document_delete_success".localized
            await load()
        }
    }
}
