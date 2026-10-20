import Foundation

// MARK: - Billing / Invoice Models (matches PortalInvoice)

struct InvoiceDTO: Codable, Identifiable {
    let id: String?
    let invoiceNumber: String?
    let date: String?
    let dueDate: String?
    let amount: Double?
    let balance: Double?
    let status: String?
    let facility: String?
    let description: String?

    // Legacy compat
    let invoiceDate: String?
    let hospitalName: String?
    let items: [InvoiceItemDTO]?

    var isPaid: Bool {
        status?.uppercased() == "PAID"
    }

    var isCancelled: Bool {
        status?.uppercased() == "CANCELLED"
    }

    var displayBalance: Double {
        balance ?? amount ?? 0
    }

    var displayDate: String? {
        date ?? invoiceDate
    }

    var displayFacility: String? {
        facility ?? hospitalName
    }

    var statusColor: String {
        switch status?.uppercased() {
        case "PAID": "green"
        case "OVERDUE": "red"
        case "PENDING": "yellow"
        case "CANCELLED": "gray"
        default: "orange"
        }
    }
}

struct InvoiceItemDTO: Codable, Identifiable {
    let id: String?
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
    let number: Int? // current page (0-based)
    let size: Int?
    let last: Bool?
}

// MARK: - Pharmacy self-service models

struct PharmacyPaymentDTO: Codable, Identifiable, Hashable {
    let id: String?
    let dispenseId: String?
    let patientId: String?
    let hospitalId: String?
    let paymentMethod: String?
    let amount: Double?
    let currency: String?
    let referenceNumber: String?
    let receivedBy: String?
    let notes: String?
    let createdAt: String?
    let updatedAt: String?
    var status: String? = nil

    /// QA D404: a payment voided with its cancelled dispense is not money paid.
    var countsTowardPaid: Bool { status?.uppercased() != "VOIDED" }

    var displayMethod: String {
        EnumLabel.label(.paymentMethod, paymentMethod) ?? "payment".localized
    }

    var displayCurrency: String { currency ?? "XOF" }
}

struct PharmacyClaimDTO: Codable, Identifiable, Hashable {
    let id: String?
    let dispenseId: String?
    let patientId: String?
    let hospitalId: String?
    let coverageReference: String?
    let claimStatus: String?
    let amount: Double?
    let currency: String?
    let submittedAt: String?
    let submittedBy: String?
    let rejectionReason: String?
    let notes: String?
    let createdAt: String?
    let updatedAt: String?

    var displayStatus: String {
        EnumLabel.text(.claimStatus, claimStatus ?? "SUBMITTED")
    }

    var displayCurrency: String { currency ?? "XOF" }
}

extension Array where Element == PharmacyPaymentDTO {
    /// QA D404: what the patient has paid at the pharmacy, voided payments left out.
    var paidTotal: Double { filter(\.countsTowardPaid).reduce(0) { $0 + ($1.amount ?? 0) } }
}
