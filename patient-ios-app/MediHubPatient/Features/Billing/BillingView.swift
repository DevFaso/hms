import SwiftUI

struct BillingView: View {
    var embeddedInNav: Bool = true
    @StateObject private var vm = BillingViewModel()
    @State private var payTarget: InvoiceDTO?

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
                                .contentShape(Rectangle())
                                .onTapGesture {
                                    if !invoice.isPaid && !invoice.isCancelled {
                                        payTarget = invoice
                                    }
                                }
                        }
                        .listStyle(.insetGrouped)
                    }
                }
            }
        }
        .navigationTitle("Billing")
        .refreshable { await vm.load() }
        .sheet(item: $payTarget) { invoice in
            PaymentSheet(invoice: invoice, vm: vm)
        }
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
                if let facility = invoice.displayFacility {
                    Text(facility).font(.caption2).foregroundColor(.secondary)
                }
                if let date = invoice.displayDate {
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

// MARK: - Payment Sheet

struct PaymentSheet: View {
    let invoice: InvoiceDTO
    @ObservedObject var vm: BillingViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var amount = ""
    @State private var selectedMethod = "CARD"
    @State private var reference = ""
    @State private var notes = ""
    @State private var isSubmitting = false
    @State private var errorMsg: String?

    private let methods = ["CARD", "BANK_TRANSFER", "MOBILE_MONEY", "CASH"]

    var body: some View {
        NavigationStack {
            Form {
                Section("Invoice") {
                    HStack {
                        Text(invoice.invoiceNumber ?? "Invoice")
                        Spacer()
                        Text(invoice.displayBalance, format: .currency(code: "USD")).bold()
                    }
                }

                Section("Payment Amount") {
                    TextField("Amount", text: $amount)
                        .keyboardType(.decimalPad)
                        .onAppear {
                            amount = String(format: "%.2f", invoice.displayBalance)
                        }
                }

                Section("Payment Method") {
                    Picker("Method", selection: $selectedMethod) {
                        ForEach(methods, id: \.self) { m in
                            Text(m.replacingOccurrences(of: "_", with: " ").capitalized).tag(m)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Section("Reference (Optional)") {
                    TextField("Transaction Reference", text: $reference)
                }

                Section("Notes (Optional)") {
                    TextField("Notes", text: $notes)
                }

                if let err = errorMsg {
                    Section {
                        Text(err).foregroundColor(.red)
                    }
                }
            }
            .navigationTitle("Make Payment")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Pay") {
                        Task { await submitPayment() }
                    }
                    .disabled(isSubmitting || (Double(amount) ?? 0) <= 0)
                }
            }
        }
    }

    private func submitPayment() async {
        isSubmitting = true
        errorMsg = nil
        let req = PatientPaymentRequest(
            amount: Double(amount) ?? 0,
            paymentMethod: selectedMethod,
            transactionReference: reference.isEmpty ? nil : reference,
            notes: notes.isEmpty ? nil : notes
        )
        do {
            let _: InvoiceDTO = try await APIClient.shared.post(
                APIEndpoints.payInvoice(id: invoice.id ?? ""),
                body: req
            )
            await vm.load()
            dismiss()
        } catch {
            errorMsg = error.localizedDescription
        }
        isSubmitting = false
    }
}

extension InvoiceDTO: @retroactive Hashable {
    static func == (lhs: InvoiceDTO, rhs: InvoiceDTO) -> Bool { lhs.id == rhs.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
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
