import SwiftUI

struct AccessLogView: View {
    @StateObject private var viewModel = AccessLogViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading && viewModel.logs.isEmpty {
                    HMSLoadingView(message: "Loading access logs...")
                } else if let error = viewModel.errorMessage, viewModel.logs.isEmpty {
                    HMSErrorView(message: error) { Task { await viewModel.loadLogs(reset: true) } }
                } else if viewModel.logs.isEmpty {
                    HMSEmptyState(icon: "eye", title: "No Access Logs", message: "Records of who accessed your data will appear here.")
                } else {
                    logsList
                }
            }
            .navigationTitle("Access Log")
            .task { await viewModel.loadLogs(reset: true) }
            .refreshable { await viewModel.loadLogs(reset: true) }
        }
    }

    private var logsList: some View {
        List {
            ForEach(viewModel.logs) { entry in
                accessLogRow(entry)
            }
            if viewModel.hasMore {
                HStack {
                    Spacer()
                    ProgressView()
                        .task { await viewModel.loadLogs() }
                    Spacer()
                }
            }
        }
        .listStyle(.plain)
    }

    private func accessLogRow(_ entry: AccessLogEntry) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: accessIcon(entry.accessType))
                    .foregroundColor(.hmsPrimary)
                    .frame(width: 24)
                Text(entry.accessedBy ?? "Unknown User")
                    .font(.hmsBodyMedium)
                    .foregroundColor(.hmsTextPrimary)
                Spacer()
                if let date = entry.accessDate {
                    Text(date)
                        .font(.hmsOverline)
                        .foregroundColor(.hmsTextTertiary)
                }
            }

            if let role = entry.accessedByRole {
                Text(role)
                    .font(.hmsCaption)
                    .foregroundColor(.hmsAccent)
            }

            HStack {
                if let type = entry.accessType {
                    HMSStatusBadge(text: type, color: .hmsInfo)
                }
                if let resource = entry.resourceType {
                    HMSStatusBadge(text: resource, color: .hmsTextSecondary)
                }
            }

            if let desc = entry.description {
                Text(desc)
                    .font(.hmsCaption)
                    .foregroundColor(.hmsTextSecondary)
                    .lineLimit(2)
            }
        }
        .padding(.vertical, 4)
    }

    private func accessIcon(_ type: String?) -> String {
        switch type?.uppercased() {
        case "VIEW", "READ":   return "eye"
        case "UPDATE", "EDIT": return "pencil"
        case "CREATE":         return "plus.circle"
        case "DELETE":         return "trash"
        case "PRINT":          return "printer"
        case "EXPORT":         return "square.and.arrow.up"
        default:               return "doc.text.magnifyingglass"
        }
    }
}
