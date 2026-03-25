import Foundation

// MARK: - Billing / Invoice Models

struct InvoiceDTO: Codable, Identifiable {
    let id: Int?
    let invoiceNumber: String?
    let invoiceDate: String?
    let dueDate: String?
    let amount: Double?
    let balance: Double?
    let status: String?
    let description: String?
    let hospitalName: String?
    let items: [InvoiceItemDTO]?

    var isPaid: Bool { status?.uppercased() == "PAID" }
    var isCancelled: Bool { status?.uppercased() == "CANCELLED" }

    var displayBalance: Double {
        balance ?? amount ?? 0
    }

    var statusColor: String {
        switch status?.uppercased() {
        case "PAID":      return "green"
        case "OVERDUE":   return "red"
        case "PENDING":   return "yellow"
        case "CANCELLED": return "gray"
        default:          return "orange"
        }
    }
}

struct InvoiceItemDTO: Codable, Identifiable {
    let id: Int?
    let description: String?
    let quantity: Int?
    let unitPrice: Double?
    let totalPrice: Double?
    let serviceDate: String?
}

// MARK: - Page wrapper (Spring Page<T>)

struct PageDTO<T: Codable>: Codable {
    let content: [T]
    let totalElements: Int?
    let totalPages: Int?
    let number: Int?       // current page (0-based)
    let size: Int?
    let last: Bool?
}
