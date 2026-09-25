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
        .alert("error".localized, isPresented: .constant(vm.errorMessage != nil)) {
            Button("ok".localized) { vm.errorMessage = nil }
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
                    Image(systemName: result.symbolName)
                        .foregroundColor(result.tone.symbolColor)
                    Text(result.testName ?? "test_name".localized).font(.headline)
                }
                if result.isPending {
                    Text("lab_result_pending".localized)
                        .font(.caption).foregroundColor(.secondary)
                } else {
                    if let value = result.valueWithUnit {
                        Text(value).font(.caption)
                    }
                    if let range = result.displayReferenceRange {
                        Text(String(format: "reference_with_value".localized, range))
                            .font(.caption).foregroundColor(.secondary)
                    }
                    if result.referenceRangeUnitUncertain {
                        // The row is where the alarming juxtaposition appears —
                        // "5.4 mmol/L" directly above "70 - 110 mg/dL" — so the
                        // caveat belongs here too, not only in the sheet behind it.
                        Text("lab_reference_range_unit_uncertain".localized)
                            .font(.caption2).foregroundColor(.secondary)
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
                        if let range = result.displayReferenceRange {
                            detailRow("reference_range".localized, range)
                        }
                        if result.referenceRangeUnitUncertain {
                            Text("lab_reference_range_unit_uncertain".localized)
                                .font(.caption).foregroundColor(.secondary)
                        }
                        if result.isCritical {
                            Label("lab_interpretation_critical".localized,
                                  systemImage: "exclamationmark.triangle.fill")
                                .foregroundColor(.red)
                        } else if result.isAbnormal {
                            Label("lab_interpretation_abnormal".localized,
                                  systemImage: "exclamationmark.circle")
                                .foregroundColor(.orange)
                        } else if result.isGradedNormal {
                            // Only when there WAS a range to be inside:
                            // PatientLabResultServiceImpl.resolveStatus returns
                            // NORMAL from statusOf(null) too, i.e. when nothing
                            // graded the row at all, and a qualitative result
                            // must not be told it is "within normal range".
                            Label("lab_interpretation_normal".localized,
                                  systemImage: "checkmark.circle.fill")
                                .foregroundColor(.green)
                        }
                    }
                }

                Section("lab_dates_section".localized) {
                    // Labelled "Ordered", not "Collected": PatientLabResultServiceImpl
                    // fills collectedAt from LabOrder.getOrderDatetime(), and LabOrder
                    // carries no sample-collection timestamp at all.
                    if let d = result.collectedAt {
                        detailRow("ordered_at".localized, String(d.prefix(10)))
                    }
                    // Not while pending: toResponse sets resultedAt BEFORE the
                    // redaction early-return (fetchRows sorts on resultDate), so
                    // an unreleased row still carries one — and printing
                    // "Resulted: 22/09" two rows under "the laboratory has not
                    // released this result yet" contradicts it.
                    if !result.isPending, let d = result.resultedAt {
                        detailRow("resulted".localized, String(d.prefix(10)))
                    }
                }

                // resolveStaffName returns nil whenever the order has no
                // staff, so a result can carry a performer and no orderer;
                // nesting one inside the other hid the performer entirely.
                if result.orderedBy?.isEmpty == false || result.performedBy?.isEmpty == false {
                    Section("provider".localized) {
                        if let orderedBy = result.orderedBy, !orderedBy.isEmpty {
                            detailRow("ordered_by".localized, orderedBy)
                        }
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
