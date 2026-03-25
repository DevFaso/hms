import SwiftUI

struct LabResultsView: View {
    @StateObject private var vm = LabResultsViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if vm.isLoading && vm.results.isEmpty {
                    ProgressView("Loading results…")
                } else if vm.results.isEmpty {
                    ContentUnavailableView("No Lab Results",
                        systemImage: "testtube.2",
                        description: Text("No lab results on record."))
                } else {
                    List(vm.results) { result in
                        LabResultDetailRow(result: result)
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("Lab Results")
            .refreshable { await vm.load() }
            .alert("Error", isPresented: .constant(vm.errorMessage != nil)) {
                Button("OK") { vm.errorMessage = nil }
            } message: { Text(vm.errorMessage ?? "") }
        }
        .task { await vm.load() }
    }
}

struct LabResultDetailRow: View {
    let result: LabResultDTO
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(result.testName ?? "Test").font(.headline)
                Spacer()
                if result.isCritical {
                    StatusBadge(text: "Critical", color: "red")
                } else if result.isAbnormal {
                    StatusBadge(text: "Abnormal", color: "orange")
                } else {
                    StatusBadge(text: result.statusDisplay, color: "green")
                }
            }
            if let val = result.result, let unit = result.unit {
                Text("\(val) \(unit)").font(.subheadline).foregroundColor(.secondary)
            }
            if let range = result.referenceRange {
                Text("Reference: \(range)").font(.caption).foregroundColor(.secondary)
            }
            if let date = result.resultDate ?? result.orderedDate {
                Text(date).font(.caption2).foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 4)
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
