import Foundation

// MARK: - Medication Models (matches MedicationSummary)

struct MedicationDTO: Codable, Identifiable, Hashable {
    let id: String?
    let name: String?
    let dosage: String?
    let frequency: String?
    let prescribedBy: String?
    let startDate: String?
    let status: String?

    // Legacy compat
    let medicationName: String?
    let genericName: String?
    let route: String?
    let endDate: String?
    let instructions: String?
    let refillsRemaining: Int?

    /// `PatientMedicationResponseDTO` is built one-to-one FROM prescriptions
    /// and its `id` IS the prescription id, so these two answer the refill
    /// question for a prescription authoritatively: `refillRequestOpen` is
    /// computed over every refill row for the page (`latestRefillsFor`, no
    /// pagination), unlike anything the app can derive from a page of
    /// `/me/patient/refills`.
    let refillable: Bool?
    let refillRequestOpen: Bool?
    /// The wire name of that request's status, so the app can tell "awaiting
    /// review" from "your provider put it on hold" without a second call.
    let refillRequestStatus: String?

    /// Display name: prefer `name`, fall back to `medicationName`
    var displayName: String {
        name ?? medicationName ?? "Medication"
    }
}

// MARK: - Prescription Models

/// Wire contract: `PrescriptionResponseDTO`
/// (`hospital-core/.../payload/dto/PrescriptionResponseDTO.java`), served by
/// `GET /me/patient/prescriptions` through
/// `PatientPortalServiceImpl.getMyPrescriptions`, which strips the
/// pharmacist-to-prescriber clarification exchange and leaves everything else.
///
/// `quantity`, `refills`, `refillsRemaining`, `expiryDate`, `diagnosisCode`
/// and `diagnosisDescription` used to be mapped here and are not on THIS DTO.
/// The counter does exist on the domain — `Prescription` has
/// `refillsAllowed`/`refillsRemaining`/`refillsUsed`, and
/// `PatientMedicationResponseDTO` serves all three — but
/// `PrescriptionResponseDTO` omits them, so the prescriptions tab cannot read
/// one. Whether a refill may be requested is decided by
/// `PrescriptionStatus.isRefillable`, the same rule
/// `PrescriptionStatus.isRefillable()` applies server-side. `prescribedBy`
/// and `prescribedDate` are served, under the names `staffFullName` and
/// `createdAt`.
struct PrescriptionDTO: Codable, Identifiable, Hashable {
    let id: String?
    let medicationName: String?
    let medicationDisplayName: String?
    let dosage: String?
    let frequency: String?
    let duration: String?
    let route: String?
    let status: String?
    let staffFullName: String?
    let createdAt: String?
    /// Where the prescription went: the partner pharmacy it was routed to, or
    /// the community pharmacy it was dispatched to by SMS. Nil while the
    /// order is still at — or was filled by — the hospital's own dispensary.
    let pharmacyName: String?
    let instructions: String?

    var displayName: String {
        if let display = medicationDisplayName, !display.isEmpty { return display }
        if let name = medicationName, !name.isEmpty { return name }
        return "prescription".localized
    }

    var statusEnum: PrescriptionStatus { PrescriptionStatus(wire: status) }
}

/// Mirrors `com.example.hms.enums.PrescriptionStatus`. Every `switch` is
/// exhaustive and carries no `default`, so adding a case here without a label
/// fails the build rather than falling back to the raw wire name — which is
/// how a patient came to read `PENDING_STOCK` and `PARTNER_REJECTED` on their
/// own prescription list (gap G16).
enum PrescriptionStatus: String, CaseIterable {
    case draft = "DRAFT"
    case pendingSignature = "PENDING_SIGNATURE"
    case signed = "SIGNED"
    case transmitted = "TRANSMITTED"
    case transmissionFailed = "TRANSMISSION_FAILED"
    case cancelled = "CANCELLED"
    case discontinued = "DISCONTINUED"
    case pendingClarification = "PENDING_CLARIFICATION"
    case dispensed = "DISPENSED"
    case partiallyFilled = "PARTIALLY_FILLED"
    case pendingStock = "PENDING_STOCK"
    case requiresExternalFill = "REQUIRES_EXTERNAL_FILL"
    case sentToPartner = "SENT_TO_PARTNER"
    case partnerAccepted = "PARTNER_ACCEPTED"
    case partnerRejected = "PARTNER_REJECTED"
    case partnerDispensed = "PARTNER_DISPENSED"
    case printedForPatient = "PRINTED_FOR_PATIENT"

    /// App-side fallback for a status this build does not know yet.
    case unknown = "__UNKNOWN__"

    init(wire: String?) {
        let trimmed = (wire ?? "").trimmingCharacters(in: .whitespaces).uppercased()
        self = PrescriptionStatus(rawValue: trimmed) ?? .unknown
    }

    var labelKey: String {
        switch self {
        case .draft: "rx_status_draft"
        case .pendingSignature: "rx_status_pending_signature"
        case .signed: "rx_status_signed"
        case .transmitted: "rx_status_transmitted"
        case .transmissionFailed: "rx_status_transmission_failed"
        case .cancelled: "rx_status_cancelled"
        case .discontinued: "rx_status_discontinued"
        case .pendingClarification: "rx_status_pending_clarification"
        case .dispensed: "rx_status_dispensed"
        case .partiallyFilled: "rx_status_partially_filled"
        case .pendingStock: "rx_status_pending_stock"
        case .requiresExternalFill: "rx_status_requires_external_fill"
        case .sentToPartner: "rx_status_sent_to_partner"
        case .partnerAccepted: "rx_status_partner_accepted"
        case .partnerRejected: "rx_status_partner_rejected"
        case .partnerDispensed: "rx_status_partner_dispensed"
        case .printedForPatient: "rx_status_printed_for_patient"
        case .unknown: "rx_status_unknown"
        }
    }

    var localizedLabel: String { labelKey.localized }

    var tone: StatusTone {
        switch self {
        case .dispensed, .partnerDispensed, .partnerAccepted: .positive
        case .pendingStock, .partiallyFilled, .pendingClarification, .pendingSignature: .attention
        case .partnerRejected, .transmissionFailed, .cancelled, .discontinued: .negative
        case .draft, .signed, .transmitted, .sentToPartner, .requiresExternalFill,
             .printedForPatient, .unknown: .neutral
        }
    }

    /// The same rule as `PrescriptionStatus.isRefillable()` server-side: a
    /// patient asks for a refill precisely because the medication was already
    /// dispensed, so only a prescription that was never signed or has been
    /// withdrawn is un-refillable. An unrecognised status is treated as
    /// refillable, exactly as the backend's `default ->` branch does; the
    /// request endpoint re-checks and is the real gate.
    var isRefillable: Bool {
        switch self {
        case .draft, .pendingSignature, .cancelled, .discontinued: false
        case .signed, .transmitted, .transmissionFailed, .pendingClarification, .dispensed,
             .partiallyFilled, .pendingStock, .requiresExternalFill, .sentToPartner,
             .partnerAccepted, .partnerRejected, .partnerDispensed, .printedForPatient,
             .unknown: true
        }
    }

    /// Every status the backend can actually send — `unknown` is ours.
    static var wireCases: [PrescriptionStatus] { allCases.filter { $0 != .unknown } }
}

// MARK: - Refill Models (matches MedicationRefillResponseDTO)

struct RefillDTO: Codable, Identifiable {
    let id: String?
    let prescriptionId: String?
    let medicationName: String?
    let patientId: String?
    let status: String?
    let preferredPharmacy: String?
    let notes: String?
    let providerNotes: String?
    let requestedAt: String?
    let updatedAt: String?

    var statusEnum: RefillStatus { RefillStatus(wire: status) }
}

/// Mirrors `com.example.hms.enums.RefillStatus`.
enum RefillStatus: String, CaseIterable {
    case requested = "REQUESTED"
    case paused = "PAUSED"
    case approved = "APPROVED"
    case denied = "DENIED"
    case dispensed = "DISPENSED"
    case cancelled = "CANCELLED"

    /// App-side fallback for a status this build does not know yet.
    case unknown = "__UNKNOWN__"

    init(wire: String?) {
        let trimmed = (wire ?? "").trimmingCharacters(in: .whitespaces).uppercased()
        self = RefillStatus(rawValue: trimmed) ?? .unknown
    }

    var labelKey: String {
        switch self {
        case .requested: "refill_status_requested"
        case .paused: "refill_status_paused"
        case .approved: "refill_status_approved"
        case .denied: "refill_status_denied"
        case .dispensed: "refill_status_dispensed"
        case .cancelled: "refill_status_cancelled"
        case .unknown: "refill_status_unknown"
        }
    }

    var localizedLabel: String { labelKey.localized }

    var tone: StatusTone {
        switch self {
        case .approved, .dispensed: .positive
        case .requested, .paused: .attention
        case .denied: .negative
        case .cancelled, .unknown: .neutral
        }
    }

    /// Still with the provider, so a second request for the same
    /// prescription would be refused: `OPEN_REFILL_STATUSES` in
    /// `PatientPortalServiceImpl`. Deliberately a separate rule from
    /// `isCancellable` even though the two sets coincide today — one is about
    /// what the patient may withdraw, the other about what blocks a new
    /// request, and they are free to diverge.
    var isOpen: Bool {
        switch self {
        case .requested, .paused: true
        case .approved, .denied, .dispensed, .cancelled, .unknown: false
        }
    }

    /// REQUESTED and PAUSED are the two states `cancelMyRefill` lets the
    /// patient withdraw. The swipe action used to offer PENDING, which this
    /// backend has never had, and never offered PAUSED, which it does.
    var isCancellable: Bool {
        switch self {
        case .requested, .paused: true
        case .approved, .denied, .dispensed, .cancelled, .unknown: false
        }
    }

    /// Every status the backend can actually send — `unknown` is ours.
    static var wireCases: [RefillStatus] { allCases.filter { $0 != .unknown } }
}

struct RefillRequest: Encodable {
    let prescriptionId: String
    let preferredPharmacy: String?
    let notes: String?
}
