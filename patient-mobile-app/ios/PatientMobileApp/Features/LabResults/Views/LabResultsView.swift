import SwiftUI

struct LabResultsView: View {
    @StateObject private var viewModel = LabResultsViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading {
                    HMSLoadingView(message: "Loading lab results...")
                } else if let error = viewModel.errorMessage {
                    HMSErrorView(message: error) { Task { await viewModel.loadResults() } }
                } else if viewModel.results.isEmpty {
                    HMSEmptyState(icon: "flask", title: "No Lab Results", message: "Your lab results will appear here once available.")
                } else {
                    labResultsList
                }
            }
            .navigationTitle("Lab Results")
            .task { await viewModel.loadResults() }
            .refreshable { await viewModel.loadResults() }
        }
    }

    private var labResultsList: some View {
        List(viewModel.results) { result in
            NavigationLink {
                LabResultDetailView(result: result)
            } label: {
                labResultRow(result)
            }
        }
        .listStyle(.plain)
    }

    private func labResultRow(_ result: LabResultDto) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(result.testName ?? "Lab Test")
                    .font(.hmsBodyMedium)
                    .foregroundColor(.hmsTextPrimary)
                Spacer()
                HMSStatusBadge(
                    text: result.status ?? "Unknown",
                    color: statusColor(result.status)
                )
            }
            if let orderedBy = result.orderedBy {
                Text("Dr. \(orderedBy)")
                    .font(.hmsCaption)
                    .foregroundColor(.hmsTextSecondary)
            }
            if let date = result.resultDate ?? result.orderedDate {
                Text(date)
                    .font(.hmsCaption)
                    .foregroundColor(.hmsTextTertiary)
            }
        }
        .padding(.vertical, 4)
    }

    private func statusColor(_ status: String?) -> Color {
        switch status?.uppercased() {
        case "COMPLETED": return .hmsSuccess
        case "PENDING":   return .hmsWarning
        case "CANCELLED": return .hmsError
        default:          return .hmsTextSecondary
        }
    }
}

// MARK: - Detail View

struct LabResultDetailView: View {
    let result: LabResultDto

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Header
                HMSCard {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(result.testName ?? "Lab Test")
                            .font(.hmsHeadline)
                            .foregroundColor(.hmsTextPrimary)
                        if let lab = result.labName {
                            Label(lab, systemImage: "building.2")
                                .font(.hmsCaption)
                                .foregroundColor(.hmsTextSecondary)
                        }
                        if let orderedBy = result.orderedBy {
                            Label("Dr. \(orderedBy)", systemImage: "stethoscope")
                                .font(.hmsCaption)
                                .foregroundColor(.hmsTextSecondary)
                        }
                        HStack {
                            if let orderedDate = result.orderedDate {
                                Label(orderedDate, systemImage: "calendar")
                                    .font(.hmsCaption)
                                    .foregroundColor(.hmsTextTertiary)
                            }
                            Spacer()
                            HMSStatusBadge(
                                text: result.status ?? "Unknown",
                                color: statusColor(result.status)
                            )
                        }
                    }
                }

                // Results table
                if let tests = result.results, !tests.isEmpty {
                    HMSSectionHeader(title: "Results")
                    VStack(spacing: 0) {
                        ForEach(tests) { test in
                            testRow(test)
                            Divider()
                        }
                    }
                    .background(Color.hmsSurface)
                    .cornerRadius(12)
                }

                // Notes
                if let notes = result.notes, !notes.isEmpty {
                    HMSSectionHeader(title: "Notes")
                    HMSCard {
                        Text(notes)
                            .font(.hmsBody)
                            .foregroundColor(.hmsTextPrimary)
                    }
                }
            }
            .padding(16)
        }
        .navigationTitle("Result Details")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func testRow(_ test: LabTestResult) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(test.parameterName ?? "—")
                    .font(.hmsBodyMedium)
                    .foregroundColor(test.isAbnormal == true ? .hmsError : .hmsTextPrimary)
                if let ref = test.referenceRange {
                    Text("Ref: \(ref)")
                        .font(.hmsOverline)
                        .foregroundColor(.hmsTextTertiary)
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(test.value ?? "—")
                    .font(.hmsBodyMedium)
                    .foregroundColor(test.isAbnormal == true ? .hmsError : .hmsTextPrimary)
                if let unit = test.unit {
                    Text(unit)
                        .font(.hmsOverline)
                        .foregroundColor(.hmsTextTertiary)
                }
            }
            if test.isAbnormal == true {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundColor(.hmsError)
                    .font(.caption)
            }
        }
        .padding(12)
    }

    private func statusColor(_ status: String?) -> Color {
        switch status?.uppercased() {
        case "COMPLETED": return .hmsSuccess
        case "PENDING":   return .hmsWarning
        case "CANCELLED": return .hmsError
        default:          return .hmsTextSecondary
        }
    }
}
