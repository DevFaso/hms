import SwiftUI

struct BillingView: View {
    @StateObject private var viewModel = BillingViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading {
                    HMSLoadingView(message: "Loading invoices...")
                } else if let error = viewModel.errorMessage {
                    HMSErrorView(message: error) { Task { await viewModel.load() } }
                } else if viewModel.invoices.isEmpty {
                    HMSEmptyState(icon: "creditcard", title: "No Invoices", message: "Your billing history will appear here.")
                } else {
                    ScrollView {
                        VStack(spacing: 16) {
                            // Summary cards
                            HStack(spacing: 12) {
                                SummaryCard(
                                    title: "Outstanding",
                                    amount: formatCurrency(viewModel.totalOutstanding),
                                    color: viewModel.totalOutstanding > 0 ? .hmsWarning : .hmsSuccess,
                                    icon: "exclamationmark.circle"
                                )
                                SummaryCard(
                                    title: "Paid",
                                    amount: formatCurrency(viewModel.totalPaid),
                                    color: .hmsSuccess,
                                    icon: "checkmark.circle"
                                )
                            }

                            // Invoice list
                            LazyVStack(spacing: 12) {
                                ForEach(viewModel.invoices) { invoice in
                                    NavigationLink(destination: InvoiceDetailView(invoice: invoice)) {
                                        InvoiceCard(invoice: invoice)
                                    }
                                    .buttonStyle(.plain)
                                    .onAppear {
                                        if invoice.id == viewModel.invoices.last?.id {
                                            Task { await viewModel.loadNextPage() }
                                        }
                                    }
                                }
                            }
                        }
                        .padding(16)
                    }
                }
            }
            .navigationTitle("Billing")
            .refreshable { await viewModel.load() }
            .task { await viewModel.load() }
        }
    }

    private func formatCurrency(_ amount: Double) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .currency
        formatter.currencyCode = "USD"
        return formatter.string(from: NSNumber(value: amount)) ?? "$0.00"
    }
}

// MARK: - Summary Card

private struct SummaryCard: View {
    let title: String
    let amount: String
    let color: Color
    let icon: String

    var body: some View {
        HMSCard {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Image(systemName: icon)
                        .foregroundColor(color)
                    Text(title)
                        .font(.hmsCaption)
                        .foregroundColor(.hmsTextSecondary)
                }
                Text(amount)
                    .font(.hmsTitle3)
                    .foregroundColor(color)
            }
        }
    }
}

// MARK: - Invoice Card

private struct InvoiceCard: View {
    let invoice: InvoiceDTO

    var body: some View {
        HMSCard {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(invoice.invoiceNumber ?? "Invoice")
                            .font(.hmsBodyMedium)
                            .foregroundColor(.hmsTextPrimary)
                        if let date = invoice.invoiceDate {
                            Text(date)
                                .font(.hmsCaption)
                                .foregroundColor(.hmsTextTertiary)
                        }
                    }
                    Spacer()
                    HMSStatusBadge(
                        text: invoice.status ?? "Unknown",
                        color: invoiceStatusColor(invoice.status)
                    )
                }

                Divider()

                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Total")
                            .font(.hmsCaption)
                            .foregroundColor(.hmsTextTertiary)
                        Text(invoice.formattedTotal)
                            .font(.hmsBodyMedium)
                            .foregroundColor(.hmsTextPrimary)
                    }
                    Spacer()
                    if invoice.balanceDue > 0 {
                        VStack(alignment: .trailing, spacing: 2) {
                            Text("Balance Due")
                                .font(.hmsCaption)
                                .foregroundColor(.hmsTextTertiary)
                            Text(invoice.formattedBalance)
                                .font(.hmsBodyMedium)
                                .foregroundColor(.hmsWarning)
                        }
                    }
                }

                if let hospital = invoice.hospitalName {
                    Label(hospital, systemImage: "cross.fill")
                        .font(.hmsCaption)
                        .foregroundColor(.hmsTextTertiary)
                }
            }
        }
    }

    private func invoiceStatusColor(_ status: String?) -> Color {
        switch status?.uppercased() {
        case "PAID": return .hmsSuccess
        case "PENDING", "PARTIALLY_PAID": return .hmsWarning
        case "OVERDUE": return .hmsError
        case "CANCELLED": return .hmsTextSecondary
        default: return .hmsInfo
        }
    }
}

// MARK: - Invoice Detail

struct InvoiceDetailView: View {
    let invoice: InvoiceDTO

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                // Header
                HMSCard {
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(invoice.invoiceNumber ?? "Invoice")
                                .font(.hmsTitle3)
                                .foregroundColor(.hmsTextPrimary)
                            HMSStatusBadge(
                                text: invoice.status ?? "Unknown",
                                color: detailStatusColor(invoice.status)
                            )
                        }
                        Spacer()
                        Image(systemName: "doc.text.fill")
                            .font(.system(size: 36))
                            .foregroundColor(.hmsPrimary)
                    }
                }

                // Info section
                HMSCard {
                    VStack(alignment: .leading, spacing: 12) {
                        InfoRow(label: "Invoice Date", value: invoice.invoiceDate ?? "N/A")
                        if let due = invoice.dueDate {
                            InfoRow(label: "Due Date", value: due)
                        }
                        if let hospital = invoice.hospitalName {
                            InfoRow(label: "Hospital", value: hospital)
                        }
                        Divider()
                        InfoRow(label: "Total Amount", value: invoice.formattedTotal)
                        InfoRow(label: "Paid", value: formatCurrency(invoice.paidAmount ?? 0))
                        InfoRow(label: "Balance Due", value: invoice.formattedBalance, highlight: invoice.balanceDue > 0)
                    }
                }

                // Line items
                if let items = invoice.items, !items.isEmpty {
                    HMSCard {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Items")
                                .font(.hmsSubheadline)
                                .foregroundColor(.hmsTextPrimary)

                            ForEach(items) { item in
                                VStack(alignment: .leading, spacing: 4) {
                                    HStack {
                                        Text(item.description ?? "Service")
                                            .font(.hmsBody)
                                            .foregroundColor(.hmsTextPrimary)
                                        Spacer()
                                        Text(formatCurrency(item.totalPrice ?? 0))
                                            .font(.hmsBodyMedium)
                                            .foregroundColor(.hmsTextPrimary)
                                    }
                                    if let qty = item.quantity, let unit = item.unitPrice {
                                        Text("\(qty) × \(formatCurrency(unit))")
                                            .font(.hmsCaption)
                                            .foregroundColor(.hmsTextTertiary)
                                    }
                                }
                                if item.id != items.last?.id {
                                    Divider()
                                }
                            }
                        }
                    }
                }
            }
            .padding(16)
        }
        .navigationTitle("Invoice")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func detailStatusColor(_ status: String?) -> Color {
        switch status?.uppercased() {
        case "PAID": return .hmsSuccess
        case "PENDING", "PARTIALLY_PAID": return .hmsWarning
        case "OVERDUE": return .hmsError
        default: return .hmsInfo
        }
    }

    private func formatCurrency(_ amount: Double) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .currency
        formatter.currencyCode = "USD"
        return formatter.string(from: NSNumber(value: amount)) ?? "$0.00"
    }
}

private struct InfoRow: View {
    let label: String
    let value: String
    var highlight: Bool = false

    var body: some View {
        HStack {
            Text(label)
                .font(.hmsCaption)
                .foregroundColor(.hmsTextTertiary)
            Spacer()
            Text(value)
                .font(.hmsBody)
                .foregroundColor(highlight ? .hmsWarning : .hmsTextPrimary)
        }
    }
}
