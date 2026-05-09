import SwiftUI

struct PharmacyInvoicesView: View {
    var embeddedInNav: Bool = true
    @StateObject private var vm = PharmacyInvoicesViewModel()
    @State private var selectedTab = 0

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
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                PharmacySummaryTile(title: "total_paid".localized, amount: vm.totalPaid, currency: vm.payments.first?.displayCurrency ?? "XOF")
                PharmacySummaryTile(title: "total_claimed".localized, amount: vm.totalClaimed, currency: vm.claims.first?.displayCurrency ?? "XOF")
            }
            .padding()

            Picker("pharmacy_invoices".localized, selection: $selectedTab) {
                Text("payments".localized).tag(0)
                Text("claims".localized).tag(1)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)

            if vm.isLoading, vm.payments.isEmpty, vm.claims.isEmpty {
                Spacer()
                ProgressView("loading".localized)
                Spacer()
            } else if selectedTab == 0 {
                paymentList
            } else {
                claimList
            }
        }
        .navigationTitle("pharmacy_invoices".localized)
        .refreshable { await vm.load() }
        .overlay(alignment: .bottom) {
            if let error = vm.errorMessage {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(Color.red)
                    .clipShape(Capsule())
                    .padding()
            }
        }
    }

    private var paymentList: some View {
        Group {
            if vm.payments.isEmpty {
                ContentUnavailableView("no_pharmacy_payments".localized, systemImage: "cross.case", description: Text("no_pharmacy_payments_desc".localized))
            } else {
                List(vm.payments) { payment in
                    PharmacyPaymentRow(payment: payment)
                }
                .listStyle(.insetGrouped)
            }
        }
    }

    private var claimList: some View {
        Group {
            if vm.claims.isEmpty {
                ContentUnavailableView("no_pharmacy_claims".localized, systemImage: "doc.text.magnifyingglass", description: Text("no_pharmacy_claims_desc".localized))
            } else {
                List(vm.claims) { claim in
                    PharmacyClaimRow(claim: claim)
                }
                .listStyle(.insetGrouped)
            }
        }
    }
}

private struct PharmacySummaryTile: View {
    let title: String
    let amount: Double
    let currency: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text("\(amount, specifier: "%.0f") \(currency)")
                .font(.headline)
                .foregroundStyle(Color("BrandBlue"))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color("BrandBlue").opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

private struct PharmacyPaymentRow: View {
    let payment: PharmacyPaymentDTO

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(payment.displayMethod).font(.headline)
                Spacer()
                Text("\(payment.amount ?? 0, specifier: "%.0f") \(payment.displayCurrency)")
                    .font(.headline)
                    .foregroundStyle(.green)
            }
            if let createdAt = payment.createdAt {
                Text(String(createdAt.prefix(16)).replacingOccurrences(of: "T", with: " "))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let reference = payment.referenceNumber, !reference.isEmpty {
                Text(String(format: "reference_format".localized, reference))
                    .font(.caption)
            }
            if let notes = payment.notes, !notes.isEmpty {
                Text(notes).font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

private struct PharmacyClaimRow: View {
    let claim: PharmacyClaimDTO

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(claim.coverageReference ?? "claim".localized).font(.headline)
                Spacer()
                Text("\(claim.amount ?? 0, specifier: "%.0f") \(claim.displayCurrency)")
                    .font(.headline)
            }
            StatusBadge(text: claim.displayStatus, color: claim.claimStatus == "PAID" ? "green" : "orange")
            if let date = claim.submittedAt ?? claim.createdAt {
                Text(String(date.prefix(16)).replacingOccurrences(of: "T", with: " "))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let reason = claim.rejectionReason, !reason.isEmpty {
                Text(reason).font(.caption).foregroundStyle(.red)
            }
            if let notes = claim.notes, !notes.isEmpty {
                Text(notes).font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

@MainActor
final class PharmacyInvoicesViewModel: ObservableObject {
    @Published var payments: [PharmacyPaymentDTO] = []
    @Published var claims: [PharmacyClaimDTO] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    var totalPaid: Double { payments.reduce(0) { $0 + ($1.amount ?? 0) } }
    var totalClaimed: Double { claims.reduce(0) { $0 + ($1.amount ?? 0) } }

    func load() async {
        isLoading = true
        errorMessage = nil

        async let paymentsRequest: PageDTO<PharmacyPaymentDTO> = APIClient.shared.get(
            APIEndpoints.pharmacyPayments,
            queryItems: [URLQueryItem(name: "page", value: "0"), URLQueryItem(name: "size", value: "50")]
        )
        async let claimsRequest: PageDTO<PharmacyClaimDTO> = APIClient.shared.get(
            APIEndpoints.pharmacyClaims,
            queryItems: [URLQueryItem(name: "page", value: "0"), URLQueryItem(name: "size", value: "50")]
        )

        var errors: [String] = []
        do {
            let paymentsPage = try await paymentsRequest
            payments = paymentsPage.content
        } catch {
            payments = []
            errors.append(String(format: "pharmacy_payments_load_failed".localized, error.localizedDescription))
        }
        do {
            let claimsPage = try await claimsRequest
            claims = claimsPage.content
        } catch {
            claims = []
            errors.append(String(format: "pharmacy_claims_load_failed".localized, error.localizedDescription))
        }
        errorMessage = errors.isEmpty ? nil : errors.joined(separator: "\n")
        isLoading = false
    }
}
