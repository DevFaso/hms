import SwiftUI

struct LabResultsView: View {
    var embeddedInNav: Bool = true
    @StateObject private var vm = LabResultsViewModel()
    @State private var selectedResult: LabResultDTO?

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
            if vm.isLoading, vm.results.isEmpty {
                ProgressView("loading".localized)
            } else if vm.results.isEmpty {
                ContentUnavailableView("no_lab_results".localized,
                                       systemImage: "testtube.2",
                                       description: Text("no_lab_results_desc".localized))
            } else {
                List(vm.results) { result in
                    Button { selectedResult = result } label: {
                        LabResultSummaryRow(result: result)
                    }
                    .buttonStyle(.plain)
                }
                .listStyle(.insetGrouped)
            }
        }
        .navigationTitle("lab_results_title".localized)
        .refreshable { await vm.load() }
        .alert("Error", isPresented: .constant(vm.errorMessage != nil)) {
            Button("OK") { vm.errorMessage = nil }
        } message: { Text(vm.errorMessage ?? "") }
        .sheet(item: $selectedResult) { result in
            LabResultDetailSheet(result: result)
        }
    }
}

// MARK: - Summary Row (list cell)
struct LabResultSummaryRow: View {
    let result: LabResultDTO
    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Image(systemName: result.abnormal ? "exclamationmark.triangle.fill" : "checkmark.circle.fill")
                        .foregroundColor(result.abnormal ? .red : .green)
                    Text(result.testName ?? "Test").font(.headline)
                }
                if let range = result.referenceRange {
                    Text("Reference: \(range)").font(.caption).foregroundColor(.secondary)
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 4) {
                if result.isCritical {
                    StatusBadge(text: "Critical", color: "red")
                } else if result.abnormal {
                    StatusBadge(text: "Abnormal", color: "orange")
                } else {
                    StatusBadge(text: result.statusDisplay, color: "green")
                }
                Image(systemName: "chevron.right")
                    .foregroundColor(.secondary).font(.caption)
            }
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Detail Sheet
struct LabResultDetailSheet: View {
    let result: LabResultDTO
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Test Information") {
                    detailRow("Test Name", result.testName ?? "—")
                    if let code = result.testCode, !code.isEmpty {
                        detailRow("Test Code", code)
                    }
                    detailRow("Status", result.statusDisplay)
                    if let lab = result.labName, !lab.isEmpty {
                        detailRow("Lab", lab)
                    }
                }

                Section("Results") {
                    if let val = result.result {
                        detailRow("Result", "\(val) \(result.unit ?? "")".trimmingCharacters(in: .whitespaces))
                    } else {
                        Text("Result pending").foregroundColor(.secondary)
                    }
                    if let range = result.referenceRange {
                        detailRow("Reference Range", range)
                    }
                    if result.isCritical {
                        Label("Critical value — contact your provider", systemImage: "exclamationmark.triangle.fill")
                            .foregroundColor(.red)
                    } else if result.abnormal {
                        Label("Abnormal — review with your provider", systemImage: "exclamationmark.circle")
                            .foregroundColor(.orange)
                    } else {
                        Label("Within normal range", systemImage: "checkmark.circle.fill")
                            .foregroundColor(.green)
                    }
                }

                Section("Dates") {
                    if let d = result.orderedDate { detailRow("Ordered", String(d.prefix(10))) }
                    if let d = result.collectedDate { detailRow("Collected", String(d.prefix(10))) }
                    if let d = result.resultDate { detailRow("Resulted", String(d.prefix(10))) }
                }

                if let orderedBy = result.orderedBy, !orderedBy.isEmpty {
                    Section("Provider") {
                        detailRow("Ordered By", orderedBy)
                    }
                }

                if let notes = result.notes, !notes.isEmpty {
                    Section("Notes") {
                        Text(notes)
                    }
                }
            }
            .navigationTitle("Lab Result Details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func detailRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundColor(.secondary)
            Spacer()
            Text(value).bold()
        }
    }
}

@MainActor
final class LabResultsViewModel: ObservableObject {
    @Published var results: [LabResultDTO] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    func load() async {
        isLoading = true
        do {
            results = try await APIClient.shared.get(
                APIEndpoints.labResults,
                queryItems: [URLQueryItem(name: "limit", value: "50")]
            )
        } catch { errorMessage = error.localizedDescription }
        isLoading = false
    }
}
