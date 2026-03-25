import SwiftUI

struct BillingView: View {
    @StateObject private var vm = BillingViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if vm.isLoading && vm.invoices.isEmpty {
                    ProgressView("Loading invoices…")
                } else {
                    VStack(spacing: 0) {
                        // Balance due banner
                        if vm.totalDue > 0 {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Outstanding Balance").font(.caption).foregroundColor(.white.opacity(0.85))
                                    Text(vm.totalDue, format: .currency(code: "USD"))
                                        .font(.title2).bold().foregroundColor(.white)
                                }
                                Spacer()
                                Button("Pay Now") { /* TODO: payment flow */ }
                                    .buttonStyle(.bordered)
                                    .tint(.white)
                            }
                            .padding()
                            .background(Color.orange)
                        }

                        if vm.invoices.isEmpty {
                            ContentUnavailableView("No Invoices",
                                systemImage: "creditcard",
                                description: Text("No billing records found."))
                        } else {
                            List(vm.invoices) { invoice in
                                InvoiceRowView(invoice: invoice)
                            }
                            .listStyle(.insetGrouped)
                        }
                    }
                }
            }
            .navigationTitle("Billing")
            .refreshable { await vm.load() }
        }
        .task { await vm.load() }
    }
}

struct InvoiceRowView: View {
    let invoice: InvoiceDTO
    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(invoice.invoiceNumber ?? "Invoice").font(.headline)
                if let desc = invoice.description {
                    Text(desc).font(.caption).foregroundColor(.secondary)
                }
                if let date = invoice.invoiceDate {
                    Text(date).font(.caption2).foregroundColor(.secondary)
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 4) {
                Text(invoice.displayBalance, format: .currency(code: "USD"))
                    .font(.headline)
                    .foregroundColor(invoice.isPaid ? .secondary : .primary)
                StatusBadge(text: invoice.status?.capitalized ?? "Pending",
                            color: invoice.statusColor)
            }
        }
        .padding(.vertical, 4)
    }
}

@MainActor
final class BillingViewModel: ObservableObject {
    @Published var invoices: [InvoiceDTO] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    var totalDue: Double {
        invoices
            .filter { !$0.isPaid && !$0.isCancelled }
            .reduce(0) { $0 + $1.displayBalance }
    }

    func load() async {
        isLoading = true
        do {
            let page: PageDTO<InvoiceDTO> = try await APIClient.shared.get(
                APIEndpoints.invoices,
                queryItems: [URLQueryItem(name: "page", value: "0"),
                             URLQueryItem(name: "size", value: "50")]
            )
            invoices = page.content
        } catch {
            // fallback: try direct array
            invoices = (try? await APIClient.shared.get(APIEndpoints.invoices)) ?? []
        }
        isLoading = false
    }
}
