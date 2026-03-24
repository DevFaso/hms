import SwiftUI

struct ConsentsView: View {
    @StateObject private var viewModel = ConsentsViewModel()
    @State private var showRevokeAlert = false
    @State private var consentToRevoke: ConsentDto?

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading && viewModel.consents.isEmpty {
                    HMSLoadingView(message: "Loading consents...")
                } else if let error = viewModel.errorMessage, viewModel.consents.isEmpty {
                    HMSErrorView(message: error) { Task { await viewModel.loadConsents() } }
                } else if viewModel.consents.isEmpty {
                    HMSEmptyState(icon: "lock.shield", title: "No Consents", message: "Your data sharing consents will appear here.")
                } else {
                    consentsList
                }
            }
            .navigationTitle("Privacy & Sharing")
            .task { await viewModel.loadConsents() }
            .refreshable { await viewModel.loadConsents() }
            .alert("Revoke Consent", isPresented: $showRevokeAlert) {
                Button("Revoke", role: .destructive) {
                    if let consent = consentToRevoke,
                       let fromId = consent.fromHospitalId,
                       let toId = consent.toHospitalId {
                        Task { await viewModel.revokeConsent(fromHospitalId: fromId, toHospitalId: toId) }
                    }
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Are you sure you want to revoke this data sharing consent? This action cannot be undone.")
            }
        }
    }

    private var consentsList: some View {
        List(viewModel.consents) { consent in
            consentRow(consent)
                .swipeActions(edge: .trailing) {
                    if consent.isActive {
                        Button("Revoke", role: .destructive) {
                            consentToRevoke = consent
                            showRevokeAlert = true
                        }
                    }
                }
        }
        .listStyle(.plain)
    }

    private func consentRow(_ consent: ConsentDto) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(consent.fromHospitalName ?? "Hospital")
                        .font(.hmsBodyMedium)
                        .foregroundColor(.hmsTextPrimary)
                    HStack(spacing: 4) {
                        Image(systemName: "arrow.right")
                            .font(.caption2)
                            .foregroundColor(.hmsTextTertiary)
                        Text(consent.toHospitalName ?? "Hospital")
                            .font(.hmsCaption)
                            .foregroundColor(.hmsTextSecondary)
                    }
                }
                Spacer()
                HMSStatusBadge(
                    text: consent.status ?? "Unknown",
                    color: consent.isActive ? .hmsSuccess : .hmsTextSecondary
                )
            }

            HStack {
                if let type = consent.consentType {
                    Label(type, systemImage: "doc.text")
                        .font(.hmsCaption)
                        .foregroundColor(.hmsTextTertiary)
                }
                Spacer()
                if let date = consent.grantedDate {
                    Text(date)
                        .font(.hmsOverline)
                        .foregroundColor(.hmsTextTertiary)
                }
            }
        }
        .padding(.vertical, 4)
    }
}
