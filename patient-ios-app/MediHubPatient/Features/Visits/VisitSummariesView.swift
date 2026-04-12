import SwiftUI

// MARK: - Visit Summaries (After-Visit Summaries) — Read-only

struct VisitSummariesView: View {
    var embeddedInNav: Bool = true
    @StateObject private var vm = VisitSummariesViewModel()

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
            if vm.isLoading, vm.summaries.isEmpty {
                ProgressView("loading".localized)
            } else if vm.summaries.isEmpty {
                ContentUnavailableView(
                    "no_summaries".localized,
                    systemImage: "doc.text.fill",
                    description: Text("no_summaries_desc".localized)
                )
            } else {
                List(vm.summaries) { summary in
                    VisitSummaryRow(summary: summary)
                }
                .listStyle(.insetGrouped)
            }
        }
        .navigationTitle("visit_summaries_title".localized)
        .refreshable { await vm.load() }
    }
}

// MARK: - Summary Row

struct VisitSummaryRow: View {
    let summary: AfterVisitSummaryDTO

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Provider + date header
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(summary.dischargingProviderName ?? "Provider")
                        .font(.subheadline.weight(.semibold))
                    if let hospital = summary.hospitalName {
                        Text(hospital)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                Spacer()
                if let date = summary.dischargeDate ?? summary.dischargeTime {
                    Text(String(date.prefix(10)))
                        .font(.caption2)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color.accentColor.opacity(0.1))
                        .foregroundColor(.accentColor)
                        .clipShape(Capsule())
                }
            }

            // Diagnosis
            if let diag = summary.dischargeDiagnosis, !diag.isEmpty {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Diagnosis")
                        .font(.caption.weight(.medium))
                        .foregroundColor(.secondary)
                    Text(diag)
                        .font(.subheadline)
                }
            }

            // Discharge condition
            if let condition = summary.dischargeCondition {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Condition at Discharge")
                        .font(.caption.weight(.medium))
                        .foregroundColor(.secondary)
                    Text(condition)
                        .font(.subheadline)
                }
            }

            // Follow-up instructions
            if let instructions = summary.followUpInstructions {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Follow-up Instructions")
                        .font(.caption.weight(.medium))
                        .foregroundColor(.secondary)
                    Text(instructions)
                        .font(.subheadline)
                        .lineLimit(3)
                }
            }

            // Follow-up appointments
            if let appts = summary.followUpAppointments, !appts.isEmpty {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(appts.indices, id: \.self) { idx in
                        let appt = appts[idx]
                        HStack(spacing: 4) {
                            Image(systemName: "calendar.badge.clock")
                                .font(.caption)
                                .foregroundColor(.orange)
                            Text("Follow-up: \(appt.providerName ?? "") \(appt.appointmentDate ?? "")")
                                .font(.caption)
                                .foregroundColor(.orange)
                        }
                    }
                }
            }

            // Status badge
            HStack {
                Spacer()
                Text(summary.isFinalized == true ? "Finalized" : "Draft")
                    .font(.system(size: 10, weight: .bold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(summary.isFinalized == true ? Color.green.opacity(0.12) : Color.orange.opacity(0.12))
                    .foregroundColor(summary.isFinalized == true ? .green : .orange)
                    .clipShape(Capsule())
            }
        }
        .padding(.vertical, 4)
    }
}

// MARK: - ViewModel

@MainActor
final class VisitSummariesViewModel: ObservableObject {
    @Published var summaries: [AfterVisitSummaryDTO] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    func load() async {
        isLoading = true
        errorMessage = nil
        summaries = await (try? APIClient.shared.get(
            APIEndpoints.afterVisitSummaries,
            queryItems: [
                URLQueryItem(name: "page", value: "0"),
                URLQueryItem(name: "size", value: "50")
            ]
        )) ?? []
        isLoading = false
    }
}
