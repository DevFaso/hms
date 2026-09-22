import Foundation

// MARK: - Accounting of disclosures (matches DisclosureAccountingDTO / AccessLogEntryDTO)

/// One access or disclosure of the patient's record, classified from the
/// patient's point of view (`DisclosureCategory` on the backend). Dates are
/// kept as the wire strings and formatted by the view.
struct DisclosureEntryDTO: Decodable, Identifiable {
    let rawId: String?
    let actor: String?
    let eventType: String?
    let entityType: String?
    let resourceId: String?
    let description: String?
    let status: String?
    let timestamp: String?
    let actorRole: String?
    let hospitalName: String?
    /// EMERGENCY_ACCESS, TREATMENT_ACCESS, SHARED_WITH_PROVIDER, INSURANCE, COPY_RELEASED, IDENTITY_CHANGE.
    let category: String?
    /// True when the record went to someone outside the treating team.
    let externalDisclosure: Bool?

    /// Audit ids are UUIDs; a row without one still needs a stable identity for the list.
    var id: String { rawId ?? "\(actor ?? "")|\(timestamp ?? "")|\(category ?? "")" }

    enum CodingKeys: String, CodingKey {
        case rawId = "id"
        case actor, eventType, entityType, resourceId, description, status, timestamp
        case actorRole, hospitalName, category, externalDisclosure
    }
}

/// A page of entries plus the counts across the whole window, so the two rows
/// that matter (an emergency override, a release elsewhere) lead the page
/// instead of hiding under months of routine chart opens.
struct DisclosureAccountingDTO: Decodable {
    let from: String?
    let to: String?
    let countsByCategory: [String: Int]?
    let totalEvents: Int?
    let externalDisclosures: Int?
    let entries: [DisclosureEntryDTO]?
    let totalPages: Int?
    let page: Int?
}

// MARK: - Record-sharing opt-out (matches RecordSharingOptOutDTO, bare DTO on the wire)

struct RecordSharingOptOutDTO: Decodable {
    let patientId: String?
    let inForce: Bool?
    let optedOutAt: String?
    let reason: String?
    let revokedAt: String?
}

/// Body of `POST /patients/{id}/record-sharing/opt-out`; the reason is never required (max 1000).
struct OptOutRequest: Encodable {
    let reason: String?
}
