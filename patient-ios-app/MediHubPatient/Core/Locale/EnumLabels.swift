import Foundation

// MARK: - Wire enum labels

/// Human labels for the backend enum values the health-records, visit,
/// billing and family-access screens show, in the app's language.
///
/// Those screens printed the wire value run through `.capitalized`, so a
/// French patient read "Revisions_required" or "Acknowledged". The wording
/// mirrors the portal's `PORTAL.ENUM.*` families in en/fr/es. A value this
/// build does not know yet reads "Unknown" in the patient's language rather
/// than the raw token.
///
/// Keys are `enum_<family>_<value>`, e.g. `enum_referral_status_acknowledged`.
/// They are constructed, so `EnumLabelTests` lists every family's wire values
/// and checks each resolves in all three bundles.
enum EnumLabel {
    enum Family: String, CaseIterable {
        case appointmentStatus = "appointment_status"
        case claimStatus = "claim_status"
        case dischargeDisposition = "discharge_disposition"
        case encounterStatus = "encounter_status"
        case encounterType = "encounter_type"
        case gender
        case immunizationStatus = "immunization_status"
        case invoiceStatus = "invoice_status"
        case paymentMethod = "payment_method"
        case proxyPermission = "proxy_permission"
        case proxyStatus = "proxy_status"
        case referralStatus = "referral_status"
        case referralUrgency = "referral_urgency"
        case relationship
        case treatmentPlanStatus = "treatment_plan_status"
        case vitalSource = "vital_source"
    }

    /// `ACKNOWLEDGED`, `acknowledged`, `In Progress` and `in-progress` all
    /// land on the same key.
    static func key(_ family: Family, _ raw: String) -> String {
        let token = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: " ", with: "_")
            .replacingOccurrences(of: "-", with: "_")
            .lowercased()
        return "enum_" + family.rawValue + "_" + token
    }

    /// Nil for an absent or blank value, so callers keep their own "—".
    ///
    /// `rawFallback` is for fields that are free text as often as a code (a
    /// gender or a relationship typed at registration): an unrecognised value
    /// is then shown as written, not replaced by "Unknown".
    static func label(_ family: Family, _ raw: String?, rawFallback: Bool = false, bundle: Bundle? = nil) -> String? {
        guard let raw, !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        let lookup = Self.key(family, raw)
        let source = bundle ?? LocalizationManager.shared.bundle
        let missing = "__MISSING__"
        let value = source.localizedString(forKey: lookup, value: missing, table: nil)
        if value != missing, !value.isEmpty { return value }
        if rawFallback { return raw.trimmingCharacters(in: .whitespacesAndNewlines) }
        return source.localizedString(forKey: "enum_unknown", value: "Unknown", table: nil)
    }

    /// `label`, or "—" when there is no value at all.
    static func text(_ family: Family, _ raw: String?, rawFallback: Bool = false) -> String {
        label(family, raw, rawFallback: rawFallback) ?? "—"
    }
}
