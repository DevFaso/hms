import Foundation

@MainActor
final class BillingViewModel: ObservableObject {
    @Published var invoices: [InvoiceDTO] = []
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var currentPage = 0
    @Published var hasMorePages = true

    private let pageSize = 20
    private let api = APIClient.shared

    func load() async {
        isLoading = true
        errorMessage = nil
        currentPage = 0
        defer { isLoading = false }

        do {
            let page = try await api.request(
                .invoices(page: 0, size: pageSize),
                type: PagedInvoices.self
            )
            invoices = page.content
            hasMorePages = page.number < page.totalPages - 1
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func loadNextPage() async {
        guard hasMorePages, !isLoading else { return }
        let nextPage = currentPage + 1

        do {
            let page = try await api.request(
                .invoices(page: nextPage, size: pageSize),
                type: PagedInvoices.self
            )
            invoices.append(contentsOf: page.content)
            currentPage = nextPage
            hasMorePages = page.number < page.totalPages - 1
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    var totalOutstanding: Double {
        invoices
            .filter { $0.status?.uppercased() != "PAID" }
            .compactMap { $0.totalAmount }
            .reduce(0, +)
    }

    var totalPaid: Double {
        invoices
            .filter { $0.status?.uppercased() == "PAID" }
            .compactMap { $0.totalAmount }
            .reduce(0, +)
    }
}

// MARK: - DTOs

struct PagedInvoices: Decodable {
    let content: [InvoiceDTO]
    let totalElements: Int
    let totalPages: Int
    let number: Int
    let size: Int

    init(content: [InvoiceDTO] = [], totalElements: Int = 0, totalPages: Int = 0, number: Int = 0, size: Int = 20) {
        self.content = content
        self.totalElements = totalElements
        self.totalPages = totalPages
        self.number = number
        self.size = size
    }
}

struct InvoiceDTO: Decodable, Identifiable {
    let id: String
    let invoiceNumber: String?
    let invoiceDate: String?
    let dueDate: String?
    let totalAmount: Double?
    let paidAmount: Double?
    let status: String?
    let hospitalName: String?
    let items: [InvoiceItemDTO]?

    var balanceDue: Double {
        (totalAmount ?? 0) - (paidAmount ?? 0)
    }

    var formattedTotal: String {
        formatCurrency(totalAmount ?? 0)
    }

    var formattedBalance: String {
        formatCurrency(balanceDue)
    }

    private func formatCurrency(_ amount: Double) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .currency
        formatter.currencyCode = "USD"
        return formatter.string(from: NSNumber(value: amount)) ?? "$0.00"
    }
}

struct InvoiceItemDTO: Decodable, Identifiable {
    let id: String
    let description: String?
    let quantity: Int?
    let unitPrice: Double?
    let totalPrice: Double?
    let serviceDate: String?
}
