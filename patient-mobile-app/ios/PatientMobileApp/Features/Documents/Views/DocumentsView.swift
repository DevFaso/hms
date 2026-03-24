import SwiftUI

struct DocumentsView: View {
    @StateObject private var viewModel = DocumentsViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading && viewModel.documents.isEmpty {
                    HMSLoadingView(message: "Loading documents...")
                } else if let error = viewModel.error, viewModel.documents.isEmpty {
                    HMSErrorView(message: error) { Task { await viewModel.loadDocuments(reset: true) } }
                } else if viewModel.documents.isEmpty {
                    HMSEmptyState(
                        icon: "doc.text",
                        title: "No Documents",
                        message: "You don't have any documents yet."
                    )
                } else {
                    documentList
                }
            }
            .navigationTitle("Documents")
            .refreshable { await viewModel.loadDocuments(reset: true) }
            .task { await viewModel.loadDocuments(reset: true) }
        }
    }

    private var documentList: some View {
        List {
            ForEach(viewModel.documents) { doc in
                NavigationLink {
                    DocumentDetailView(document: doc, viewModel: viewModel)
                } label: {
                    documentRow(doc)
                }
            }
            if viewModel.hasMore {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .task { await viewModel.loadDocuments() }
            }
        }
        .listStyle(.plain)
    }

    private func documentRow(_ doc: DocumentDto) -> some View {
        HStack(spacing: 12) {
            Image(systemName: viewModel.documentIcon(doc.mimeType))
                .font(.title2)
                .foregroundStyle(.accentColor)
                .frame(width: 40, height: 40)
                .background(Color.accentColor.opacity(0.1))
                .clipShape(RoundedRectangle(cornerRadius: 8))

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(doc.documentName ?? doc.fileName ?? "Document")
                        .font(.headline)
                        .lineLimit(1)
                    if doc.isConfidential == true {
                        Image(systemName: "lock.fill")
                            .font(.caption)
                            .foregroundStyle(.orange)
                            .accessibilityLabel("Confidential document")
                    }
                }
                if let category = doc.category ?? doc.documentType {
                    Text(category)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                HStack {
                    if let date = doc.uploadDate ?? doc.createdAt {
                        Text(String(date.prefix(10)))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    if let size = doc.fileSize {
                        Text("• \(viewModel.fileSizeFormatted(size))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .padding(.vertical, 2)
    }
}

// MARK: - Detail View

struct DocumentDetailView: View {
    let document: DocumentDto
    let viewModel: DocumentsViewModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Icon + Name
                HMSCard {
                    HStack(spacing: 16) {
                        Image(systemName: viewModel.documentIcon(document.mimeType))
                            .font(.largeTitle)
                            .foregroundStyle(.accentColor)
                            .frame(width: 60, height: 60)
                            .background(Color.accentColor.opacity(0.1))
                            .clipShape(RoundedRectangle(cornerRadius: 12))

                        VStack(alignment: .leading, spacing: 4) {
                            Text(document.documentName ?? "Document")
                                .font(.title3.bold())
                            if let type = document.documentType {
                                Text(type)
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                            HMSStatusBadge(status: document.status ?? "uploaded")
                        }
                    }
                }

                // File Info
                HMSSectionHeader(title: "File Information")
                HMSCard {
                    VStack(alignment: .leading, spacing: 8) {
                        if let fileName = document.fileName {
                            infoRow(icon: "doc", label: "File Name", value: fileName)
                        }
                        if let mime = document.mimeType {
                            infoRow(icon: "tag", label: "Type", value: mime)
                        }
                        if let size = document.fileSize {
                            infoRow(icon: "internaldrive", label: "Size", value: viewModel.fileSizeFormatted(size))
                        }
                        if let category = document.category {
                            infoRow(icon: "folder", label: "Category", value: category)
                        }
                        if document.isConfidential == true {
                            HStack {
                                Image(systemName: "lock.fill")
                                    .foregroundStyle(.orange)
                                Text("Confidential Document")
                                    .font(.subheadline)
                                    .foregroundStyle(.orange)
                            }
                        }
                    }
                }

                // Upload Info
                HMSSectionHeader(title: "Upload Details")
                HMSCard {
                    VStack(alignment: .leading, spacing: 8) {
                        if let uploader = document.uploadedBy {
                            infoRow(icon: "person", label: "Uploaded By", value: uploader)
                        }
                        if let role = document.uploadedByRole {
                            infoRow(icon: "person.badge.key", label: "Role", value: role)
                        }
                        if let hospital = document.hospitalName {
                            infoRow(icon: "cross.circle", label: "Hospital", value: hospital)
                        }
                        if let dept = document.departmentName {
                            infoRow(icon: "building.2", label: "Department", value: dept)
                        }
                        if let date = document.uploadDate ?? document.createdAt {
                            infoRow(icon: "calendar", label: "Date", value: String(date.prefix(10)))
                        }
                    }
                }

                if let desc = document.description, !desc.isEmpty {
                    HMSSectionHeader(title: "Description")
                    HMSCard { Text(desc).font(.body) }
                }
            }
            .padding()
        }
        .navigationTitle("Document Details")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func infoRow(icon: String, label: String, value: String) -> some View {
        HStack {
            Label(label, systemImage: icon)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .frame(width: 130, alignment: .leading)
            Text(value)
                .font(.subheadline)
        }
    }
}
