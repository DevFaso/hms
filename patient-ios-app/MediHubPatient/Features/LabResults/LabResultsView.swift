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

    /// A pending row is never a green tick: the lab has not released it and
    /// there is nothing to be reassured by.
    private var symbol: String {
        if result.isPending { return "hourglass" }
        return result.isAbnormal || result.isCritical
            ? "exclamationmark.triangle.fill"
            : "checkmark.circle.fill"
    }

    private var symbolColor: Color {
        switch result.tone {
        case .positive: .green
        case .attention: .orange
        case .negative: .red
        case .neutral: .secondary
        }
    }

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Image(systemName: symbol).foregroundColor(symbolColor)
                    Text(result.testName ?? "test_name".localized).font(.headline)
                }
                if result.isPending {
                    Text("lab_result_pending".localized)
                        .font(.caption).foregroundColor(.secondary)
                } else {
                    if let value = result.valueWithUnit {
                        Text(value).font(.caption)
                    }
                    if let range = result.referenceRange {
                        Text("\("reference".localized): \(range)")
                            .font(.caption).foregroundColor(.secondary)
                    }
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 4) {
                StatusBadge(text: result.statusDisplay, color: result.tone.badgeColor)
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
                Section("test_information".localized) {
                    detailRow("test_name".localized, result.testName ?? "—")
                    if let code = result.testCode, !code.isEmpty {
                        detailRow("test_code".localized, code)
                    }
                    if let category = result.category, !category.isEmpty {
                        detailRow("category".localized, category)
                    }
                    HStack {
                        Text("status".localized).foregroundColor(.secondary)
                        Spacer()
                        StatusBadge(text: result.statusDisplay, color: result.tone.badgeColor)
                    }
                    if let lab = result.hospitalName, !lab.isEmpty {
                        detailRow("laboratory".localized, lab)
                    }
                }

                Section("lab_results_section".localized) {
                    if result.isPending {
                        // No value, no reference range and no interpretation:
                        // an unreleased result is redacted server-side and
                        // colouring it "normal" would tell the patient
                        // something untrue.
                        Label("lab_pending_explainer".localized, systemImage: "hourglass")
                            .foregroundColor(.secondary)
                    } else {
                        if let value = result.valueWithUnit {
                            detailRow("lab_value".localized, value)
                        }
                        if let range = result.referenceRange {
                            detailRow("reference_range".localized, range)
                        }
                        if result.isCritical {
                            Label("lab_interpretation_critical".localized,
                                  systemImage: "exclamationmark.triangle.fill")
                                .foregroundColor(.red)
                        } else if result.isAbnormal {
                            Label("lab_interpretation_abnormal".localized,
                                  systemImage: "exclamationmark.circle")
                                .foregroundColor(.orange)
                        } else if result.isNormal {
                            Label("lab_interpretation_normal".localized,
                                  systemImage: "checkmark.circle.fill")
                                .foregroundColor(.green)
                        }
                    }
                }

                Section("lab_dates_section".localized) {
                    if let d = result.collectedAt {
                        detailRow("collected_date".localized, String(d.prefix(10)))
                    }
                    if let d = result.resultedAt {
                        detailRow("resulted".localized, String(d.prefix(10)))
                    }
                }

                if let orderedBy = result.orderedBy, !orderedBy.isEmpty {
                    Section("provider".localized) {
                        detailRow("ordered_by".localized, orderedBy)
                        if let performedBy = result.performedBy, !performedBy.isEmpty {
                            detailRow("performed_by".localized, performedBy)
                        }
                    }
                }

                if let notes = result.notes, !notes.isEmpty {
                    Section("notes".localized) {
                        Text(notes)
                    }
                }
            }
            .navigationTitle("lab_result_details".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("done".localized) { dismiss() }
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
